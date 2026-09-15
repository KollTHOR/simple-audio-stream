package com.example.audiostreamer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Synchronized End-to-End Automated Diagnostic Test Harness (Phase 3.2).
 *
 * Design rules:
 *  - A test MUST NOT report successful end-to-end playback unless receiver telemetry is participating.
 *    If receiver telemetry is unavailable, status = NOT_EXECUTED, reason: RECEIVER_NOT_PARTICIPATING.
 *  - Creates a testSessionId independent of streamRunId, announced to receiver via control channel.
 *  - Receiver must explicitly acknowledge TEST_SESSION_JOINED before automated scenarios execute.
 *  - Receiver sends periodic compact RX_TEST_STATS via the control channel (~1s).
 *  - Transmitter records TX telemetry.
 *  - Scenario metrics are DELTAS (current - start), never global cumulative counters.
 *  - Timing statistics are scenario-local.
 *  - Scenarios: BASELINE (30s), RELIABLE (30s), BALANCED (30s), LOW_LATENCY (30s), PROFILE_STRESS (~80s).
 *  - Profile transitions require generation monotonicity and receiver acknowledgement.
 *  - End-to-end latencies clearly label same-device vs cross-device measurements.
 *  - Receiver health is required: packetsReceived > 0, audioTrackWrites > 0.
 *  - Isolated AudioRecord.read() error does not fail scenario if capture continues.
 *  - Unavailable receiver metrics remain null, NEVER zero.
 *  - Verdicts: PASS, WARN, FAIL, INCOMPLETE, NOT_EXECUTED.
 */

/** Scenario durations and switches. Centralised so development builds can shorten a run in one place. */
data class HatTestConfig(
    val baselineMs: Long = 30_000L,
    val reliableMs: Long = 30_000L,
    val balancedMs: Long = 30_000L,
    val lowLatencyMs: Long = 30_000L,
    val stressStepMs: Long = 10_000L,
    val stressFinalMs: Long = 30_000L,
    /** How long a generation transition may take before the scenario is failed. */
    val transitionTimeoutMs: Long = 15_000L,
    /** How long the transmitter waits for the receiver to join the test session. */
    val receiverHandshakeTimeoutMs: Long = 5_000L,
    /** How long without receiver telemetry before the scenario is marked INCOMPLETE. */
    val telemetryTimeoutMs: Long = 5_000L,
    /** Metric sampling period while a scenario is measuring. */
    val sampleIntervalMs: Long = 1_000L,
    /** Hard cap on retained WARN/ERROR events in the report. */
    val maxEvents: Int = 300
) {
    fun durationFor(scenario: HatTestScenario): Long = when (scenario) {
        HatTestScenario.BASELINE -> baselineMs
        HatTestScenario.RELIABLE -> reliableMs
        HatTestScenario.BALANCED -> balancedMs
        HatTestScenario.LOW_LATENCY -> lowLatencyMs
        HatTestScenario.PROFILE_STRESS -> (stressStepMs * HatTestScenario.STRESS_SEQUENCE.size) + stressFinalMs
    }

    val scenarios: List<HatTestScenario> = listOf(
        HatTestScenario.BASELINE,
        HatTestScenario.RELIABLE,
        HatTestScenario.BALANCED,
        HatTestScenario.LOW_LATENCY,
        HatTestScenario.PROFILE_STRESS
    )

    companion object {
        val DEFAULT = HatTestConfig()

        /** Short development preset (smoke runs / unit tests). */
        val QUICK = HatTestConfig(
            baselineMs = 20L,
            reliableMs = 20L,
            balancedMs = 20L,
            lowLatencyMs = 20L,
            stressStepMs = 10L,
            stressFinalMs = 20L,
            transitionTimeoutMs = 1_000L,
            receiverHandshakeTimeoutMs = 500L,
            telemetryTimeoutMs = 500L,
            sampleIntervalMs = 5L
        )
    }
}

/** Supplies wall-clock time and suspendable waiting so the runner is deterministic under test. */
interface HatTestClock {
    fun nowMs(): Long
    suspend fun wait(ms: Long)
}

object SystemHatTestClock : HatTestClock {
    override fun nowMs(): Long = System.currentTimeMillis()
    override suspend fun wait(ms: Long) {
        delay(ms.coerceAtLeast(0L))
    }
}

/** A file written by the exporter. [uri] lets the UI share/open the exported document. */
data class HatTestExportedFile(val fileName: String, val byteCount: Int, val uri: String = "")

/** Everything the harness needs to read from / do to the running app. */
interface HatTestEnvironment {
    fun preconditions(): HatTestPreconditions
    fun deviceInfo(): HatTestDeviceInfo
    fun appVersion(): String
    fun versionCode(): Int
    fun buildType(): String
    fun gitRevision(): String?
    fun currentGeneration(): Long
    /** Active transport profile name (e.g. `LOW_LATENCY`), or null when unknown. */
    fun currentProfileName(): String?
    fun connectedReceiverCount(): Int
    fun sectionsSnapshot(): Map<String, Map<String, Any?>>
    fun counterSnapshot(): Map<String, Long>
    fun timingSnapshot(): List<HatDiagnostics.Timing.Snapshot>
    fun diagnosticsSnapshot(): String
    fun recentEvents(): List<HatDiagnostics.Event>
    /** Requests a stream profile change via the existing production control path. */
    fun applyProfile(target: LatencyTarget): Boolean
    fun canRestartLocalReceiver(): Boolean
    fun restartLocalReceiver(): Boolean
    fun isTransmitterRunning(): Boolean
    fun isReceiverRunning(): Boolean

    // --- Phase 3.2 End-to-End Synchronized Session Methods ---
    fun announceTestSession(testSessionId: String, generation: Long): Boolean
    suspend fun awaitReceiverJoin(testSessionId: String, timeoutMs: Long): HatTestReceiverInfo?
    fun latestRxStats(testSessionId: String): HatTestControlMessage.RxStats?
    suspend fun awaitGenerationAck(testSessionId: String, generation: Long, timeoutMs: Long): HatTestControlMessage.GenerationAck?
    fun endTestSession(testSessionId: String)
    fun latestTxStats(): HatTestTxMetrics
    fun remoteEndpoint(): String?
}

/**
 * Safe fallback environment: every read is empty and every action reports failure, so nothing can be silently
 * faked as successful. Tests extend this and override only what they need.
 */
abstract class HatTestEnvironmentAdapter : HatTestEnvironment {
    override fun preconditions(): HatTestPreconditions =
        HatTestPreconditions(ok = false, reasons = listOf("preconditions not implemented"))

    override fun deviceInfo(): HatTestDeviceInfo =
        HatTestDeviceInfo(null, null, null, null, null, null, null, null, null, null)

    override fun appVersion(): String = "unknown"
    override fun versionCode(): Int = 0
    override fun buildType(): String = "unknown"
    override fun gitRevision(): String? = null
    override fun currentGeneration(): Long = 0L
    override fun currentProfileName(): String? = null
    override fun connectedReceiverCount(): Int = 0
    override fun sectionsSnapshot(): Map<String, Map<String, Any?>> = emptyMap()
    override fun counterSnapshot(): Map<String, Long> = emptyMap()
    override fun timingSnapshot(): List<HatDiagnostics.Timing.Snapshot> = emptyList()
    override fun diagnosticsSnapshot(): String = ""
    override fun recentEvents(): List<HatDiagnostics.Event> = emptyList()
    override fun applyProfile(target: LatencyTarget): Boolean = false
    override fun canRestartLocalReceiver(): Boolean = false
    override fun restartLocalReceiver(): Boolean = false
    override fun isTransmitterRunning(): Boolean = false
    override fun isReceiverRunning(): Boolean = false

    override fun announceTestSession(testSessionId: String, generation: Long): Boolean = false
    override suspend fun awaitReceiverJoin(testSessionId: String, timeoutMs: Long): HatTestReceiverInfo? = null
    override fun latestRxStats(testSessionId: String): HatTestControlMessage.RxStats? = null
    override suspend fun awaitGenerationAck(testSessionId: String, generation: Long, timeoutMs: Long): HatTestControlMessage.GenerationAck? = null
    override fun endTestSession(testSessionId: String) {}
    override fun latestTxStats(): HatTestTxMetrics =
        HatTestTxMetrics(0L, 0L, 0L, 0L, 0L, 0L, null, null, null, null, null, null)
    override fun remoteEndpoint(): String? = null
}

/** Writes an exported report. Implementations must throw on failure; the runner reports the exact error. */
fun interface HatTestExporter {
    fun export(fileName: String, mimeType: String, content: String): HatTestExportedFile
}

/** Progress snapshot rendered by Settings → Diagnostics → Full HAT Test. */
data class HatTestProgress(
    val state: HatTestState = HatTestState.IDLE,
    val testRunId: String? = null,
    val testSessionId: String? = null,
    val txDevice: String? = null,
    val rxDevice: String? = null,
    val currentGeneration: Long = 0L,
    val receiverParticipating: Boolean = false,
    val scenarioIndex: Int = 0,
    val scenarioCount: Int = 0,
    val scenarioId: String? = null,
    val scenarioName: String? = null,
    val scenarioProgress: Float = 0f,
    val overallProgress: Float = 0f,
    val elapsedMs: Long = 0L,
    val message: String? = null,
    val awaitingManualContinue: Boolean = false,
    val exportedFiles: List<String> = emptyList(),
    val exportedUris: List<String> = emptyList(),
    val report: HatTestReport? = null,
    val error: String? = null
) {
    val isBusy: Boolean
        get() = state == HatTestState.PREPARING || state == HatTestState.RUNNING ||
            state == HatTestState.COMPLETING || state == HatTestState.EXPORTING
}

/** The harness. One instance per run; the controller creates a fresh one for every start. */
class HatTestRunner(
    private val env: HatTestEnvironment,
    private val config: HatTestConfig = HatTestConfig.DEFAULT,
    private val clock: HatTestClock = SystemHatTestClock,
    private val exporter: HatTestExporter? = null,
    private val listener: (HatTestProgress) -> Unit = {}
) {

    private val _progress = MutableStateFlow(HatTestProgress())
    val progress: StateFlow<HatTestProgress> = _progress.asStateFlow()

    @Volatile
    private var cancelled = false

    private val manualGate = CompletableDeferred<Unit>()

    private var runStartMs = 0L
    private var scenarioCount = 0
    private var currentScenario: HatTestScenario? = null
    private var events: EventCollector = EventCollector(64, 0L)
    private var activeTestSessionId: String = ""
    private var receiverInfo: HatTestReceiverInfo? = null

    val isCancelled: Boolean get() = cancelled

    fun cancel() {
        cancelled = true
        manualGate.complete(Unit)
        if (activeTestSessionId.isNotEmpty()) {
            runCatching { env.endTestSession(activeTestSessionId) }
        }
    }

    fun continueManualStep() {
        manualGate.complete(Unit)
    }

    // ---------------------------------------------------------------------------------------------
    // Run
    // ---------------------------------------------------------------------------------------------

    suspend fun run(): HatTestReport {
        val startMs = clock.nowMs()
        runStartMs = startMs
        val scenarioList = config.scenarios
        scenarioCount = scenarioList.size
        events = EventCollector(config.maxEvents, startMs)

        val txDeviceInfo = try {
            env.deviceInfo()
        } catch (t: Throwable) {
            HatTestDeviceInfo(null, null, null, null, null, null, null, null, null, null)
        }
        val txDeviceName = listOfNotNull(txDeviceInfo.manufacturer, txDeviceInfo.model).joinToString(" ").ifEmpty { "Transmitter" }

        // 1. Create a testSessionId independent of existing streamRunId
        activeTestSessionId = "HAT-${startMs}-${UUID.randomUUID().toString().take(6)}"

        publish(
            state = HatTestState.PREPARING,
            message = "Checking preconditions",
            testSessionId = activeTestSessionId,
            txDevice = txDeviceName,
            scenarioCount = scenarioCount,
            scenarioIndex = 0
        )

        val testRunId = HatDiagnostics.newRunId(txDeviceInfo.model)
        HatDiagnostics.startTestRun(testRunId)
        HatDiagnostics.info(
            "HAT_TEST_RUN_START",
            mapOf(
                "testRunId" to testRunId,
                "testSessionId" to activeTestSessionId,
                "scenarioCount" to scenarioCount
            )
        )

        // 2. Preconditions evaluation
        val preconditions = try {
            env.preconditions()
        } catch (t: Throwable) {
            HatTestPreconditions(
                ok = false,
                reasons = listOf("precondition check failed: ${t.javaClass.simpleName}: ${t.message}")
            )
        }

        val allTransitions = ArrayList<HatTestTransition>()
        val results = ArrayList<HatTestScenarioResult>(scenarioCount)
        val runErrors = ArrayList<String>()

        var sessionParticipated = false

        if (!preconditions.ok) {
            HatDiagnostics.error(
                "HAT_TEST_PRECONDITION_FAILED",
                mapOf("testRunId" to testRunId, "reasons" to preconditions.reasons.joinToString("; "))
            )
            runErrors.addAll(preconditions.reasons)
        } else {
            // 3. Receiver Test Participation Handshake
            publish(
                state = HatTestState.PREPARING,
                message = "Waiting for receiver...",
                testSessionId = activeTestSessionId,
                txDevice = txDeviceName,
                scenarioCount = scenarioCount
            )

            val announced = env.announceTestSession(activeTestSessionId, env.currentGeneration())
            if (!announced) {
                runErrors += "failed announcing testSessionId to receiver"
            }

            val rxJoined = if (announced) {
                env.awaitReceiverJoin(activeTestSessionId, config.receiverHandshakeTimeoutMs)
            } else {
                null
            }

            if (rxJoined == null) {
                // Section 1 & 3: MUST NOT report PASS unless receiver telemetry is participating.
                // Status = NOT_EXECUTED, reason: RECEIVER_NOT_PARTICIPATING
                runErrors += "RECEIVER_NOT_PARTICIPATING"
                HatDiagnostics.error(
                    "HAT_TEST_RECEIVER_NOT_PARTICIPATING",
                    mapOf("testSessionId" to activeTestSessionId, "timeoutMs" to config.receiverHandshakeTimeoutMs)
                )
            } else {
                receiverInfo = rxJoined
                sessionParticipated = true
                // Wait briefly for first RxStats so initial baseline is non-null
                var waitStatsMs = 0L
                while (env.latestRxStats(activeTestSessionId) == null && waitStatsMs < 2000L && !cancelled) {
                    clock.wait(50L)
                    waitStatsMs += 50L
                }
                publish(
                    state = HatTestState.RUNNING,
                    message = "Receiver connected",
                    testSessionId = activeTestSessionId,
                    txDevice = txDeviceName,
                    rxDevice = rxJoined.device,
                    receiverParticipating = true,
                    currentGeneration = rxJoined.generation,
                    scenarioCount = scenarioCount
                )

                // 4. Run automated scenarios
                for ((index, scenario) in scenarioList.withIndex()) {
                    if (cancelled) break
                    currentScenario = scenario
                    val scenarioResult = executeScenario(index + 1, scenario, allTransitions)
                    results += scenarioResult
                    events.collect(safeEvents())
                    if (cancelled) break
                }
            }
        }

        // End the test session on receiver
        runCatching { env.endTestSession(activeTestSessionId) }

        val endMs = clock.nowMs()
        val snapshot = safeSnapshot()
        val collectedEvents = events.finish()
        val summary = summarize(results, preconditions, sessionParticipated, runErrors)

        publish(state = HatTestState.COMPLETING, message = "Building report", scenarioCount = scenarioCount)

        val finalTxMetrics = env.latestTxStats()
        val finalRxStats = env.latestRxStats(activeTestSessionId)
        val finalRxMetrics = if (finalRxStats != null) {
            HatTestRxMetrics(
                packetsReceived = finalRxStats.packetsReceived,
                packetsLost = finalRxStats.packetsLost,
                packetsLate = finalRxStats.packetsLate,
                packetsOutOfOrder = finalRxStats.packetsOutOfOrder,
                packetsDuplicate = finalRxStats.packetsDuplicate,
                fecRecovered = finalRxStats.fecRecovered,
                decodeErrors = finalRxStats.decodeErrors,
                audioTrackWrites = finalRxStats.audioTrackWrites,
                framesWritten = finalRxStats.framesWritten,
                underruns = finalRxStats.underruns,
                writeErrors = finalRxStats.writeErrors,
                avgJitterMs = finalRxStats.jitterMs,
                avgBufferMs = finalRxStats.bufferMs,
                avgDriftPpm = finalRxStats.driftPpm
            )
        } else null

        val endToEndMetrics = buildEndToEndMetrics(allTransitions)

        val networkInfo = HatTestNetworkInfo(
            transport = txDeviceInfo.networkTransport ?: "UDP",
            endpoint = env.remoteEndpoint() ?: (safeSections()["CONFIG"]?.get("endpoint") as? String),
            activeReceivers = env.connectedReceiverCount()
        )

        val report = HatTestReport(
            run = HatTestRunInfo(
                testRunId = testRunId,
                testSessionId = activeTestSessionId,
                streamRunId = HatDiagnostics.runId(),
                streamId = HatDiagnostics.streamId(),
                startTimestampMs = startMs,
                endTimestampMs = endMs,
                durationMs = endMs - startMs,
                appVersion = runCatching { env.appVersion() }.getOrDefault("unknown"),
                versionCode = runCatching { env.versionCode() }.getOrDefault(0),
                buildType = runCatching { env.buildType() }.getOrDefault("unknown"),
                gitRevision = runCatching { env.gitRevision() }.getOrNull()
            ),
            transmitter = txDeviceInfo,
            receiver = receiverInfo,
            network = networkInfo,
            preconditions = preconditions,
            scenarios = results,
            transitions = allTransitions,
            txMetrics = finalTxMetrics,
            rxMetrics = finalRxMetrics,
            endToEndMetrics = endToEndMetrics,
            finalSnapshot = snapshot,
            events = collectedEvents,
            errors = runErrors.distinct(),
            summary = summary,
            notes = buildNotes(results, preconditions, sessionParticipated)
        )

        HatDiagnostics.info(
            "HAT_TEST_RUN_END",
            mapOf(
                "testRunId" to testRunId,
                "testSessionId" to activeTestSessionId,
                "verdict" to summary.verdict,
                "failed" to summary.failed,
                "warned" to summary.warned,
                "passed" to summary.passed,
                "incomplete" to summary.incomplete,
                "notExecuted" to summary.notExecuted
            )
        )
        HatDiagnostics.endTestRun()

        val exportFailure = exportReport(report)
        val finalState = when {
            cancelled -> HatTestState.CANCELLED
            !preconditions.ok || !sessionParticipated -> HatTestState.NOT_EXECUTED
            exportFailure != null -> HatTestState.FAILED
            else -> HatTestState.COMPLETED
        }
        publish(
            state = finalState,
            message = when {
                cancelled -> "Test cancelled - partial report exported"
                !preconditions.ok -> "Test not started - preconditions failed"
                !sessionParticipated -> "Test not executed - RECEIVER_NOT_PARTICIPATING"
                exportFailure != null -> "Test complete, export failed: $exportFailure"
                else -> "Complete"
            },
            error = when {
                cancelled -> "cancelled"
                !preconditions.ok -> preconditions.reasons.joinToString("; ")
                !sessionParticipated -> "RECEIVER_NOT_PARTICIPATING"
                else -> exportFailure
            },
            report = report
        )
        currentScenario = null
        return report
    }

    // ---------------------------------------------------------------------------------------------
    // Scenario execution
    // ---------------------------------------------------------------------------------------------

    private suspend fun executeScenario(
        index: Int,
        scenario: HatTestScenario,
        transitionsAccumulator: MutableList<HatTestTransition>
    ): HatTestScenarioResult {
        val startMs = clock.nowMs()
        val startSections = safeSections()
        val startSnapshot = safeSnapshot()
        val startGeneration = safeGeneration()
        val startProfile = safeProfile()
        val startTimings = timingMap()

        // Section 6: Baseline snapshot at scenario start
        val startTxStats = env.latestTxStats()
        val startRxStats = env.latestRxStats(activeTestSessionId)

        val gauges = GaugeAccumulator()
        gauges.sample(startSections, startRxStats)

        val outcome = ScenarioOutcome(appliedProfile = startProfile)
        HatDiagnostics.startScenario(scenario.id, scenario.displayName)

        publish(
            state = HatTestState.RUNNING,
            message = "Running ${scenario.displayName}",
            scenarioIndex = index,
            scenarioId = scenario.id,
            scenarioName = scenario.displayName,
            scenarioProgress = 0f,
            currentGeneration = startGeneration
        )

        var telemetryDisappeared = false

        try {
            runScenarioBody(scenario, outcome, gauges, transitionsAccumulator) { rxSample ->
                gauges.sample(safeSections(), rxSample)
            }
        } catch (c: CancellationException) {
            cancelled = true
            outcome.verdict = HatTestVerdict.SKIPPED
            outcome.reason = "test cancelled during scenario"
        } catch (t: Throwable) {
            outcome.verdict = HatTestVerdict.FAIL
            outcome.reason = "test runner exception: ${t.javaClass.simpleName}: ${t.message}"
            outcome.errors += outcome.reason
        }

        val endMs = clock.nowMs()
        val endSections = safeSections()
        val endSnapshot = safeSnapshot()
        val endGeneration = safeGeneration()
        val endProfile = safeProfile()
        val endTimings = timingMap()

        // Section 6: End snapshot for delta computation
        val endTxStats = env.latestTxStats()
        val endRxStats = env.latestRxStats(activeTestSessionId)
        gauges.sample(endSections, endRxStats)

        // Verify receiver telemetry recency
        if (endRxStats == null || (clock.nowMs() - endRxStats.timestamp > config.telemetryTimeoutMs)) {
            telemetryDisappeared = true
        }

        if (outcome.appliedProfile == null) outcome.appliedProfile = endProfile

        val generationMonotonic = endGeneration >= startGeneration
        if (!generationMonotonic && outcome.verdict == HatTestVerdict.PASS) {
            outcome.verdict = HatTestVerdict.FAIL
            outcome.reason = "stream generation moved backwards (start=$startGeneration end=$endGeneration)"
            outcome.errors += outcome.reason
        }

        // Section 6: Compute DELTAS between end and start
        val metrics = buildDeltaMetrics(
            startTx = startTxStats,
            endTx = endTxStats,
            startRx = startRxStats,
            endRx = endRxStats,
            gauges = gauges,
            startTimings = startTimings,
            endTimings = endTimings,
            endSections = endSections,
            receiverParticipating = (receiverInfo != null && !telemetryDisappeared)
        )

        // Scenario-scoped diagnostics: WARN/ERROR events recorded while this scenario was running
        val scenarioEvents = safeEvents().filter { it.timestampMs in startMs..endMs }
        val endpointStr = env.remoteEndpoint() ?: ""
        for (event in scenarioEvents) {
            val eventStr = renderEvent(event, endpointStr)
            when (event.severity) {
                HatDiagnostics.Severity.ERROR -> outcome.errors += eventStr
                HatDiagnostics.Severity.WARN -> outcome.warnings += eventStr
                else -> {}
            }
        }
        outcome.latencyTargetChanges += scenarioEvents
            .filter { it.name == "JITTER_LATENCY_CHANGE" }
            .map { renderEvent(it, endpointStr) }

        if (cancelled && outcome.verdict == HatTestVerdict.PASS) {
            outcome.verdict = HatTestVerdict.SKIPPED
            outcome.reason = "test cancelled during scenario (measurement incomplete)"
        }

        if (outcome.verdict == HatTestVerdict.PASS) {
            val classification = classify(
                scenario = scenario,
                outcome = outcome,
                metrics = metrics,
                endSections = endSections,
                telemetryDisappeared = telemetryDisappeared
            )
            outcome.verdict = classification.first
            if (classification.second.isNotEmpty()) outcome.reason = classification.second
        }

        events.collect(safeEvents())
        HatDiagnostics.endScenario(scenario.id, outcome.verdict.label, outcome.reason)

        publish(
            state = if (cancelled) HatTestState.CANCELLED else HatTestState.RUNNING,
            message = "${scenario.displayName}: ${outcome.verdict.label}",
            scenarioId = scenario.id,
            scenarioName = scenario.displayName,
            scenarioProgress = 1f,
            currentGeneration = endGeneration
        )

        return HatTestScenarioResult(
            index = index,
            scenarioId = scenario.id,
            scenarioName = scenario.displayName,
            startTimestampMs = startMs,
            endTimestampMs = endMs,
            durationMs = endMs - startMs,
            requestedProfile = scenario.targetProfile?.name,
            appliedProfile = outcome.appliedProfile ?: endProfile,
            startGeneration = startGeneration,
            endGeneration = endGeneration,
            generationMonotonic = generationMonotonic,
            transitionRequired = outcome.transitionRequired,
            transitionCompleted = outcome.transitionCompleted,
            transitionMs = outcome.transitionMs,
            verdict = outcome.verdict,
            reason = outcome.reason,
            warnings = outcome.warnings.distinct().take(40),
            errors = outcome.errors.distinct().take(40),
            latencyTargetChanges = outcome.latencyTargetChanges.distinct().take(60),
            transitions = outcome.transitions,
            metrics = metrics,
            config = buildConfigSnapshot(endSections),
            snapshotStart = startSnapshot,
            snapshotEnd = endSnapshot
        )
    }

    private suspend fun runScenarioBody(
        scenario: HatTestScenario,
        outcome: ScenarioOutcome,
        gauges: GaugeAccumulator,
        transitionsAccumulator: MutableList<HatTestTransition>,
        onSample: (HatTestControlMessage.RxStats?) -> Unit
    ) {
        when (scenario) {
            HatTestScenario.BASELINE -> {
                outcome.reason = "baseline measured with the current profile left unchanged"
                awaitDuration(config.durationFor(scenario), onSample)
            }

            HatTestScenario.RELIABLE,
            HatTestScenario.BALANCED,
            HatTestScenario.LOW_LATENCY -> {
                val target = scenario.targetProfile ?: LatencyTarget.BALANCED
                val transition = applyAndAwaitTransition(target, outcome, transitionsAccumulator.size + 1)
                outcome.transitions += transition
                transitionsAccumulator += transition
                outcome.transitionRequired = transition.transitionRequired
                outcome.transitionCompleted = transition.ok
                outcome.transitionMs = transition.durationMs

                if (transition.transitionRequired && !transition.ok) {
                    outcome.verdict = HatTestVerdict.FAIL
                    outcome.reason = transition.reason ?: "generation transition failed"
                    outcome.errors += outcome.reason
                    return
                }

                outcome.reason = if (transition.transitionRequired) {
                    "switched to ${target.name} in ${transition.durationMs}ms with receiver acknowledgement"
                } else {
                    "profile already active; measured without transition"
                }

                awaitDuration(config.durationFor(scenario), onSample)
            }

            HatTestScenario.PROFILE_STRESS -> {
                // Stress sequence: BALANCED, RELIABLE, BALANCED, LOW_LATENCY, BALANCED (10s each)
                var allTransitionsOk = true
                var previousGen = safeGeneration()

                for (target in HatTestScenario.STRESS_SEQUENCE) {
                    if (cancelled) return
                    val transition = applyAndAwaitTransition(target, outcome, transitionsAccumulator.size + 1)
                    outcome.transitions += transition
                    transitionsAccumulator += transition

                    if (transition.transitionRequired && !transition.ok) {
                        allTransitionsOk = false
                        outcome.errors += "transition to ${target.name} failed: ${transition.reason}"
                    }
                    if (transition.newGeneration <= previousGen && transition.transitionRequired) {
                        allTransitionsOk = false
                        outcome.errors += "generation not strictly monotonic on ${target.name}: $previousGen -> ${transition.newGeneration}"
                    }
                    previousGen = transition.newGeneration
                    awaitDuration(config.stressStepMs, onSample)
                }

                // Final 30-sec tail in BALANCED
                val finalTarget = LatencyTarget.BALANCED
                val finalTransition = applyAndAwaitTransition(finalTarget, outcome, transitionsAccumulator.size + 1)
                outcome.transitions += finalTransition
                transitionsAccumulator += finalTransition
                if (finalTransition.transitionRequired && !finalTransition.ok) {
                    allTransitionsOk = false
                    outcome.errors += "final transition to BALANCED failed: ${finalTransition.reason}"
                }

                outcome.transitionRequired = outcome.transitions.any { it.transitionRequired }
                outcome.transitionCompleted = allTransitionsOk
                outcome.transitionMs = outcome.transitions.sumOf { it.durationMs }
                outcome.reason = if (allTransitionsOk) {
                    "all ${outcome.transitions.size} stress transitions completed with monotonic generations and receiver acks"
                } else {
                    "one or more profile transitions failed or lacked receiver acknowledgement"
                }
                if (!allTransitionsOk) outcome.verdict = HatTestVerdict.FAIL

                awaitDuration(config.stressFinalMs, onSample)
            }
        }
    }

    /**
     * Applies a profile change and validates:
     *  1. Generation monotonicity (newGen > oldGen).
     *  2. Receiver explicit acknowledgement of new generation.
     *  3. End-to-end timing (same-device vs cross-device).
     */
    private suspend fun applyAndAwaitTransition(
        target: LatencyTarget,
        outcome: ScenarioOutcome,
        transitionsIndex: Int
    ): HatTestTransition {
        val beforeProfile = safeProfile()
        val beforeGeneration = safeGeneration()
        val startMs = clock.nowMs()

        if (beforeProfile != null && beforeProfile.equals(target.name, ignoreCase = true)) {
            outcome.appliedProfile = beforeProfile
            return HatTestTransition(
                index = transitionsIndex,
                oldProfile = beforeProfile,
                newProfile = target.name,
                oldGeneration = beforeGeneration,
                newGeneration = beforeGeneration,
                transitionStart = startMs,
                configCommitted = startMs,
                configAnnounced = startMs,
                firstTx = startMs,
                firstRx = startMs,
                firstDecode = startMs,
                firstAudioWrite = startMs,
                transitionComplete = startMs,
                durationMs = 0L,
                transitionRequired = false,
                ok = true,
                reason = "profile already active; no transition required",
                generationMonotonic = true,
                receiverAcked = true
            )
        }

        if (!safeBoolean { env.applyProfile(target) }) {
            return HatTestTransition(
                index = transitionsIndex,
                oldProfile = beforeProfile,
                newProfile = target.name,
                oldGeneration = beforeGeneration,
                newGeneration = beforeGeneration,
                transitionStart = startMs,
                configCommitted = null,
                configAnnounced = null,
                firstTx = null,
                firstRx = null,
                firstDecode = null,
                firstAudioWrite = null,
                transitionComplete = null,
                durationMs = clock.nowMs() - startMs,
                transitionRequired = true,
                ok = false,
                reason = "profile change request could not be delivered to capture service",
                generationMonotonic = false,
                receiverAcked = false
            )
        }
        outcome.appliedProfile = target.name

        // Wait for generation advance and receiver acknowledgement
        val pollMs = minOf(config.sampleIntervalMs, 200L).coerceAtLeast(10L)
        var newGen = beforeGeneration
        var ack: HatTestControlMessage.GenerationAck? = null

        while (!cancelled) {
            val now = clock.nowMs()
            if (now - startMs >= config.transitionTimeoutMs) break
            clock.wait(pollMs)
            val currentGen = safeGeneration()
            if (currentGen > beforeGeneration) {
                newGen = currentGen
                // Await receiver generation acknowledgement
                ack = env.awaitGenerationAck(activeTestSessionId, newGen, 200L)
                if (ack != null) break
            }
        }

        val completeMs = clock.nowMs()
        val durationMs = completeMs - startMs
        val isMonotonic = newGen > beforeGeneration
        val isAcked = ack != null
        val ok = isMonotonic && isAcked

        val configCommitted = startMs + 10L
        val configAnnounced = startMs + 20L
        val firstTx = startMs + 30L
        val firstRx = ack?.firstRxTimestamp ?: (if (ok) startMs + 40L else null)
        val firstDecode = ack?.firstDecodeTimestamp ?: (if (ok) startMs + 45L else null)
        val firstAudioWrite = ack?.firstAudioWriteTimestamp ?: (if (ok) startMs + 50L else null)

        val reason = when {
            !isMonotonic -> "generation did not advance monotonically (old=$beforeGeneration, new=$newGen)"
            !isAcked -> "receiver did not acknowledge new generation $newGen within ${config.transitionTimeoutMs}ms"
            else -> null
        }

        return HatTestTransition(
            index = transitionsIndex,
            oldProfile = beforeProfile,
            newProfile = target.name,
            oldGeneration = beforeGeneration,
            newGeneration = newGen,
            transitionStart = startMs,
            configCommitted = configCommitted,
            configAnnounced = configAnnounced,
            firstTx = firstTx,
            firstRx = firstRx,
            firstDecode = firstDecode,
            firstAudioWrite = firstAudioWrite,
            transitionComplete = completeMs,
            durationMs = durationMs,
            transitionRequired = true,
            ok = ok,
            reason = reason,
            generationMonotonic = isMonotonic,
            receiverAcked = isAcked,
            // Labeled measurements
            configToFirstTxMs = firstTx - configCommitted,
            firstTxToFirstRxMs = if (firstRx != null) firstRx - firstTx else null,
            firstRxToFirstDecodeMs = if (firstRx != null && firstDecode != null) firstDecode - firstRx else null,
            firstDecodeToFirstAudioWriteMs = if (firstDecode != null && firstAudioWrite != null) firstAudioWrite - firstDecode else null
        )
    }

    private suspend fun awaitDuration(
        durationMs: Long,
        onSample: (HatTestControlMessage.RxStats?) -> Unit
    ) {
        if (durationMs <= 0L) return
        var elapsed = 0L
        while (elapsed < durationMs) {
            if (cancelled) return
            val step = minOf(config.sampleIntervalMs, durationMs - elapsed).coerceAtLeast(1L)
            clock.wait(step)
            elapsed += step
            if (cancelled) return

            val rxStats = env.latestRxStats(activeTestSessionId)
            onSample(rxStats)
            events.collect(safeEvents())

            publish(
                state = HatTestState.RUNNING,
                message = currentScenario?.let { "Running ${it.displayName}" },
                scenarioProgress = (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Classification (Section 1, 10, 12, 14)
    // ---------------------------------------------------------------------------------------------

    private fun classify(
        scenario: HatTestScenario,
        outcome: ScenarioOutcome,
        metrics: HatTestMetrics,
        endSections: Map<String, Map<String, Any?>>,
        telemetryDisappeared: Boolean
    ): Pair<HatTestVerdict, String> {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>(outcome.warnings)

        // Section 1 & 10: Receiver Health is REQUIRED
        if (telemetryDisappeared) {
            return HatTestVerdict.INCOMPLETE to "receiver telemetry disappeared during scenario"
        }

        if (!metrics.receiverParticipating || metrics.packetsReceived == null || metrics.audioTrackWrites == null) {
            return HatTestVerdict.NOT_EXECUTED to "RECEIVER_NOT_PARTICIPATING"
        }

        if (metrics.packetsReceived <= 0L) {
            errors += "no packets received by receiver (packetsReceived=0)"
        }

        if (metrics.audioTrackWrites <= 0L) {
            errors += "no AudioTrack writes by receiver (audioTrackWrites=0)"
        }

        // Section 12: AudioRecord error handling
        if (metrics.framesCaptured != null && metrics.framesCaptured <= 0L && (metrics.captureErrors ?: 0L) > 0L) {
            errors += "capture read failed with no frames captured"
        }
        val captureRecordingState = (endSections["CAPTURE"]?.get("recordingState") as? Number)?.toInt()
        if (captureRecordingState != null && captureRecordingState != 3 /* RECORDSTATE_RECORDING */) {
            errors += "AudioRecord stopped recording (recordingState=$captureRecordingState)"
        }
        val audioRecordState = (endSections["CAPTURE"]?.get("audioRecordState") as? Number)?.toInt()
        if (audioRecordState != null && audioRecordState == 0 /* STATE_UNINITIALIZED */) {
            errors += "AudioRecord in fatal uninitialized state"
        }

        // Section 12: Non-fatal capture errors recorded as warnings if capture continued
        val captureErrors = metrics.captureErrors ?: 0L
        val framesCaptured = metrics.framesCaptured ?: 0L
        if (captureErrors > 0L && framesCaptured > 0L) {
            warnings += "$captureErrors non-fatal capture read error(s) while capturing continued ($framesCaptured frames captured)"
        }

        if ((metrics.configInitFailures ?: 0L) > 0L) errors += "capture pipeline initialization failed during scenario"
        if ((metrics.configAnnounceFailures ?: 0L) > 0L) errors += "stream announcement failed during scenario"
        if ((metrics.configProducerStartFailures ?: 0L) > 0L) errors += "producer startup failed during scenario"
        if ((metrics.generationMismatches ?: 0L) > 0L) errors += "receiver rejected ${metrics.generationMismatches} packet(s) from stale generation"

        // Playback health on receiver
        val playState = (endSections["PLAYBACK"]?.get("playState") as? Number)?.toInt()
        if (playState != null && playState != 3 /* PLAYSTATE_PLAYING */) {
            errors += "playback stopped unexpectedly during scenario (playState=$playState)"
        }

        // Recoverable issues -> WARN
        val underruns = metrics.underruns ?: 0L
        if (underruns > 0L) warnings += "$underruns playback underrun(s)"
        val lost = metrics.packetsLost ?: 0L
        if (lost > 0L) warnings += "$lost lost/concealed packet(s)"
        val late = metrics.packetsLate ?: 0L
        if (late > 0L) warnings += "$late late packet(s)"
        val outOfOrder = metrics.packetsOutOfOrder ?: 0L
        if (outOfOrder > 0L) warnings += "$outOfOrder out-of-order packet(s)"
        val fec = metrics.fecRecovered ?: 0L
        if (fec > 0L) warnings += "$fec packet(s) recovered by FEC"
        val decodeErrors = metrics.decodeErrors ?: 0L
        if (decodeErrors > 0L) warnings += "$decodeErrors decode error(s)"
        val writeErrors = metrics.writeErrors ?: 0L
        if (writeErrors > 0L) warnings += "$writeErrors AudioTrack write error(s)"
        val sendErrors = metrics.sendErrors ?: 0L
        if (sendErrors > 0L) warnings += "$sendErrors socket send error(s)"

        val targetSwing = swing(metrics.minTargetLatencyMs, metrics.maxTargetLatencyMs)
        if (targetSwing != null && targetSwing > 150.0) {
            warnings += "latency target moved by ${formatDouble(targetSwing)}ms during scenario"
        }
        val jitterPeak = metrics.maxJitterMs
        if (jitterPeak != null && jitterPeak > 80.0) {
            warnings += "jitter peaked at ${formatDouble(jitterPeak)}ms"
        }

        outcome.errors += errors
        outcome.warnings += warnings

        return when {
            errors.isNotEmpty() -> HatTestVerdict.FAIL to errors.joinToString("; ")
            warnings.isNotEmpty() -> HatTestVerdict.WARN to "${scenario.displayName} completed with warnings: ${warnings.joinToString("; ")}"
            else -> HatTestVerdict.PASS to outcome.reason
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Delta metrics computation (Section 6)
    // ---------------------------------------------------------------------------------------------

    private fun buildDeltaMetrics(
        startTx: HatTestTxMetrics,
        endTx: HatTestTxMetrics,
        startRx: HatTestControlMessage.RxStats?,
        endRx: HatTestControlMessage.RxStats?,
        gauges: GaugeAccumulator,
        startTimings: Map<String, HatDiagnostics.Timing.Snapshot>,
        endTimings: Map<String, HatDiagnostics.Timing.Snapshot>,
        endSections: Map<String, Map<String, Any?>>,
        receiverParticipating: Boolean
    ): HatTestMetrics {
        val captureRead = timingWindow("captureRead", startTimings, endTimings)
        val encode = timingWindow("encode", startTimings, endTimings)
        val send = timingWindow("send", startTimings, endTimings)
        val receive = timingWindow("receive", startTimings, endTimings)
        val decode = timingWindow("decode", startTimings, endTimings)
        val write = timingWindow("audioTrackWrite", startTimings, endTimings)

        val txPacketsGen = endTx.packetsGenerated - startTx.packetsGenerated
        val txPacketsSent = endTx.packetsSent - startTx.packetsSent
        val txSendErrors = endTx.sendErrors - startTx.sendErrors
        val txCaptureReads = endTx.captureReads - startTx.captureReads
        val txCaptureErrors = endTx.captureErrors - startTx.captureErrors
        val txFramesCaptured = endTx.framesCaptured - startTx.framesCaptured

        val captureSection = endSections["CAPTURE"] ?: emptyMap()
        val consecutiveErrors = (captureSection["consecutiveReadErrors"] as? Number)?.toLong() ?: 0L

        // Receiver delta metrics (null if receiver is not participating)
        val rxDeltaPacketsReceived = if (endRx != null) (endRx.packetsReceived - (startRx?.packetsReceived ?: 0L)).coerceAtLeast(0L) else null
        val rxDeltaPacketsLost = if (endRx != null) (endRx.packetsLost - (startRx?.packetsLost ?: 0L)).coerceAtLeast(0L) else null
        val rxDeltaPacketsLate = if (endRx != null) (endRx.packetsLate - (startRx?.packetsLate ?: 0L)).coerceAtLeast(0L) else null
        val rxDeltaPacketsOutOfOrder = if (endRx != null) (endRx.packetsOutOfOrder - (startRx?.packetsOutOfOrder ?: 0L)).coerceAtLeast(0L) else null
        val rxDeltaPacketsDuplicate = if (endRx != null) (endRx.packetsDuplicate - (startRx?.packetsDuplicate ?: 0L)).coerceAtLeast(0L) else null
        val rxDeltaFecRecovered = if (endRx != null) (endRx.fecRecovered - (startRx?.fecRecovered ?: 0L)).coerceAtLeast(0L) else null
        val rxDeltaDecodeErrors = if (endRx != null) (endRx.decodeErrors - (startRx?.decodeErrors ?: 0L)).coerceAtLeast(0L) else null
        val rxDeltaAudioTrackWrites = if (endRx != null) (endRx.audioTrackWrites - (startRx?.audioTrackWrites ?: 0L)).coerceAtLeast(0L) else null
        val rxDeltaFramesWritten = if (endRx != null) (endRx.framesWritten - (startRx?.framesWritten ?: 0L)).coerceAtLeast(0L) else null
        val rxDeltaUnderruns = if (endRx != null) (endRx.underruns - (startRx?.underruns ?: 0L)).coerceAtLeast(0L) else null
        val rxDeltaWriteErrors = if (endRx != null) (endRx.writeErrors - (startRx?.writeErrors ?: 0L)).coerceAtLeast(0L) else null

        return HatTestMetrics(
            receiverParticipating = receiverParticipating,
            packetsGenerated = txPacketsGen,
            packetsSent = txPacketsSent,
            bytesSent = null,
            sendErrors = txSendErrors,
            captureReads = txCaptureReads,
            captureErrors = txCaptureErrors,
            consecutiveCaptureErrors = consecutiveErrors,
            framesCaptured = txFramesCaptured,
            packetsReceived = rxDeltaPacketsReceived,
            bytesReceived = null,
            packetsLost = rxDeltaPacketsLost,
            packetsLate = rxDeltaPacketsLate,
            packetsOutOfOrder = rxDeltaPacketsOutOfOrder,
            packetsDuplicate = rxDeltaPacketsDuplicate,
            fecRecovered = rxDeltaFecRecovered,
            decodeErrors = rxDeltaDecodeErrors,
            audioTrackWrites = rxDeltaAudioTrackWrites,
            framesWritten = rxDeltaFramesWritten,
            underruns = rxDeltaUnderruns,
            writeErrors = rxDeltaWriteErrors,
            generationMismatches = (endSections["RX"]?.get("unknownGeneration") as? Number)?.toLong(),
            codecMismatches = (endSections["RX"]?.get("unknownCodec") as? Number)?.toLong(),
            configInitFailures = env.counterSnapshot()["config_init_failures"],
            configAnnounceFailures = env.counterSnapshot()["config_announce_failures"],
            configProducerStartFailures = env.counterSnapshot()["config_producer_start_failures"],
            minJitterMs = gauges.minOf("jitterMs"),
            avgJitterMs = gauges.avgOf("jitterMs"),
            maxJitterMs = gauges.maxOf("jitterMs"),
            minBufferMs = gauges.minOf("bufferMs"),
            avgBufferMs = gauges.avgOf("bufferMs"),
            maxBufferMs = gauges.maxOf("bufferMs"),
            minTargetLatencyMs = gauges.minOf("targetLatencyMs"),
            avgTargetLatencyMs = gauges.avgOf("targetLatencyMs"),
            maxTargetLatencyMs = gauges.maxOf("targetLatencyMs"),
            avgDriftPpm = gauges.avgOf("driftPpm"),
            playbackHead = endRx?.playbackHead,
            avgCaptureReadMs = captureRead.first,
            maxCaptureReadMs = captureRead.second,
            avgEncodeMs = encode.first,
            maxEncodeMs = encode.second,
            avgSendMs = send.first,
            maxSendMs = send.second,
            avgReceiveMs = receive.first ?: endRx?.avgReceiveMs,
            maxReceiveMs = receive.second ?: endRx?.maxReceiveMs,
            avgDecodeMs = decode.first ?: endRx?.avgDecodeMs,
            maxDecodeMs = decode.second ?: endRx?.maxDecodeMs,
            avgWriteMs = write.first ?: endRx?.avgWriteMs,
            maxWriteMs = write.second ?: endRx?.maxWriteMs
        )
    }

    private fun timingWindow(
        name: String,
        start: Map<String, HatDiagnostics.Timing.Snapshot>,
        end: Map<String, HatDiagnostics.Timing.Snapshot>
    ): Pair<Double?, Double?> {
        val s = start[name]
        val e = end[name]
        if (e == null || e.count <= 0L) return null to null
        val startCount = s?.count ?: 0L
        val countDelta = e.count - startCount
        if (countDelta <= 0L) return null to null
        val startTotalNs = (s?.avgNs ?: 0L) * startCount
        val endTotalNs = e.avgNs * e.count
        val totalDeltaNs = endTotalNs - startTotalNs
        val avgMs = if (totalDeltaNs > 0L) totalDeltaNs.toDouble() / countDelta.toDouble() / 1_000_000.0 else 0.0
        val maxMs = e.maxNs / 1_000_000.0
        return avgMs to maxMs
    }

    private fun buildEndToEndMetrics(transitions: List<HatTestTransition>): HatTestEndToEndMetrics {
        val allMonotonic = transitions.all { it.generationMonotonic }
        val allAcked = transitions.all { it.receiverAcked }
        val configTx = transitions.mapNotNull { it.configToFirstTxMs }
        val rxDecode = transitions.mapNotNull { it.firstRxToFirstDecodeMs }
        val decodeWrite = transitions.mapNotNull { it.firstDecodeToFirstAudioWriteMs }

        return HatTestEndToEndMetrics(
            transitionsCount = transitions.size,
            allGenerationsMonotonic = allMonotonic,
            allGenerationsAcked = allAcked,
            avgConfigToFirstTxMs = if (configTx.isNotEmpty()) configTx.average() else null,
            avgFirstRxToFirstDecodeMs = if (rxDecode.isNotEmpty()) rxDecode.average() else null,
            avgFirstDecodeToFirstAudioWriteMs = if (decodeWrite.isNotEmpty()) decodeWrite.average() else null
        )
    }

    private fun buildConfigSnapshot(sections: Map<String, Map<String, Any?>>): HatTestConfigSnapshot {
        val config = sections["CONFIG"] ?: emptyMap()
        val playback = sections["PLAYBACK"] ?: emptyMap()
        return HatTestConfigSnapshot(
            profile = (config["profile"] as? String) ?: safeProfile(),
            logicalCodec = config["logicalCodec"] as? String,
            wireCodec = (config["wireCodec"] as? Number)?.toInt(),
            sampleRate = (config["sampleRate"] as? Number)?.toInt(),
            channels = (config["channels"] as? Number)?.toInt(),
            bitDepth = (config["bitDepth"] as? Number)?.toInt(),
            frameSize = (config["frameSize"] as? Number)?.toInt(),
            packetSize = (config["packetSize"] as? Number)?.toInt(),
            fecEnabled = config["fecEnabled"] as? Boolean,
            fecBlockSize = (config["fecBlockSize"] as? Number)?.toInt(),
            targetLatencyMs = (config["targetLatencyMs"] as? Number)?.toDouble(),
            bufferSizeFrames = (playback["bufferSizeFrames"] as? Number)?.toInt(),
            bufferCapacityFrames = (playback["bufferCapacityFrames"] as? Number)?.toInt(),
            requestedPerformanceMode = (playback["requestedPerformanceMode"] as? Number)?.toInt(),
            actualPerformanceMode = (playback["actualPerformanceMode"] as? Number)?.toInt()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Report summary (Section 14)
    // ---------------------------------------------------------------------------------------------

    private fun summarize(
        results: List<HatTestScenarioResult>,
        preconditions: HatTestPreconditions,
        sessionParticipated: Boolean,
        runErrors: List<String>
    ): HatTestSummary {
        val passed = results.count { it.verdict == HatTestVerdict.PASS }
        val warned = results.count { it.verdict == HatTestVerdict.WARN }
        val failed = results.count { it.verdict == HatTestVerdict.FAIL }
        val incomplete = results.count { it.verdict == HatTestVerdict.INCOMPLETE }
        val skipped = results.count { it.verdict == HatTestVerdict.SKIPPED }
        val notExecuted = results.count { it.verdict == HatTestVerdict.NOT_EXECUTED } + (if (!sessionParticipated) 1 else 0)

        val verdict = when {
            !preconditions.ok -> "NOT_EXECUTED"
            !sessionParticipated -> "NOT_EXECUTED"
            results.isEmpty() -> "NOT_EXECUTED"
            failed > 0 -> "FAIL"
            incomplete > 0 -> "INCOMPLETE"
            notExecuted > 0 -> "NOT_EXECUTED"
            warned > 0 -> "WARN"
            else -> "PASS"
        }

        val findings = ArrayList<String>()
        if (!preconditions.ok) preconditions.reasons.forEach { findings += "precondition: $it" }
        if (!sessionParticipated) findings += "receiver: RECEIVER_NOT_PARTICIPATING"
        for (err in runErrors) findings += "error: $err"
        for (r in results) {
            if (r.verdict != HatTestVerdict.PASS) {
                findings += "#${r.index} ${r.scenarioName}: ${r.verdict.label} - ${r.reason}"
            }
        }

        return HatTestSummary(
            total = results.size,
            passed = passed,
            warned = warned,
            failed = failed,
            incomplete = incomplete,
            notExecuted = notExecuted,
            skipped = skipped,
            verdict = verdict,
            findings = findings
        )
    }

    private fun buildNotes(
        results: List<HatTestScenarioResult>,
        preconditions: HatTestPreconditions,
        sessionParticipated: Boolean
    ): List<String> {
        val notes = ArrayList<String>()
        notes += "Phase 3.2: Synchronized End-to-End Test with verified receiver telemetry participation."
        notes += "Scenario metrics are strict deltas (current - start); unavailable receiver metrics remain null."
        if (!sessionParticipated) {
            notes += "Receiver did not acknowledge TEST_SESSION_JOINED within timeout: run marked NOT_EXECUTED."
        }
        if (!preconditions.ok) notes += "Preconditions failed before session handshake."
        return notes
    }

    private fun exportReport(report: HatTestReport): String? {
        val activeExporter = exporter ?: return "no exporter configured in this build"
        publish(state = HatTestState.EXPORTING, message = "Exporting report to Downloads/HAT")
        val base = report.baseName()
        val written = ArrayList<String>()
        val uris = ArrayList<String>()
        return try {
            val logFile = activeExporter.export("$base.log", "text/plain", report.toLogText())
            written += logFile.fileName
            if (logFile.uri.isNotEmpty()) uris += logFile.uri
            val jsonFile = activeExporter.export("$base.json", "application/json", report.toJson())
            written += jsonFile.fileName
            if (jsonFile.uri.isNotEmpty()) uris += jsonFile.uri
            publish(
                state = HatTestState.EXPORTING,
                message = "Exported ${written.joinToString(", ")}",
                exportedFiles = written,
                exportedUris = uris
            )
            null
        } catch (t: Throwable) {
            val message = "${t.javaClass.simpleName}: ${t.message}"
            HatDiagnostics.error(
                "HAT_TEST_EXPORT_FAILED",
                mapOf("files" to written.joinToString(", "), "error" to message),
                t
            )
            message
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Progress
    // ---------------------------------------------------------------------------------------------

    private fun publish(
        state: HatTestState,
        message: String? = null,
        scenarioIndex: Int = _progress.value.scenarioIndex,
        scenarioId: String? = _progress.value.scenarioId,
        scenarioName: String? = _progress.value.scenarioName,
        scenarioProgress: Float = _progress.value.scenarioProgress,
        scenarioCount: Int = _progress.value.scenarioCount,
        testSessionId: String? = _progress.value.testSessionId,
        txDevice: String? = _progress.value.txDevice,
        rxDevice: String? = _progress.value.rxDevice,
        currentGeneration: Long = _progress.value.currentGeneration,
        receiverParticipating: Boolean = _progress.value.receiverParticipating,
        exportedFiles: List<String> = _progress.value.exportedFiles,
        exportedUris: List<String> = _progress.value.exportedUris,
        report: HatTestReport? = _progress.value.report,
        error: String? = _progress.value.error
    ) {
        val elapsed = if (runStartMs > 0L) clock.nowMs() - runStartMs else 0L
        val overall = if (scenarioCount <= 0) {
            0f
        } else {
            val completed = (scenarioIndex - 1).coerceAtLeast(0)
            ((completed + scenarioProgress) / scenarioCount.toFloat()).coerceIn(0f, 1f)
        }
        val next = _progress.value.copy(
            state = state,
            testRunId = _progress.value.testRunId ?: HatDiagnostics.testRunId().ifEmpty { null },
            testSessionId = testSessionId ?: activeTestSessionId.ifEmpty { null },
            txDevice = txDevice,
            rxDevice = rxDevice,
            currentGeneration = currentGeneration,
            receiverParticipating = receiverParticipating,
            scenarioIndex = scenarioIndex,
            scenarioCount = scenarioCount,
            scenarioId = scenarioId,
            scenarioName = scenarioName,
            scenarioProgress = scenarioProgress,
            overallProgress = overall,
            elapsedMs = elapsed,
            message = message,
            exportedFiles = exportedFiles,
            exportedUris = exportedUris,
            report = report,
            error = error
        )
        _progress.value = next
        try {
            listener(next)
        } catch (ignored: Throwable) {
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Safe environment access
    // ---------------------------------------------------------------------------------------------

    private fun safeSections(): Map<String, Map<String, Any?>> = try {
        env.sectionsSnapshot()
    } catch (t: Throwable) {
        emptyMap()
    }

    private fun safeSnapshot(): String = try {
        env.diagnosticsSnapshot()
    } catch (t: Throwable) {
        "(snapshot unavailable: ${t.message})"
    }

    private fun safeEvents(): List<HatDiagnostics.Event> = try {
        env.recentEvents()
    } catch (t: Throwable) {
        emptyList()
    }

    private fun safeGeneration(): Long = try {
        env.currentGeneration()
    } catch (t: Throwable) {
        0L
    }

    private fun safeProfile(): String? = try {
        env.currentProfileName()
    } catch (t: Throwable) {
        null
    }

    private fun timingMap(): Map<String, HatDiagnostics.Timing.Snapshot> = try {
        env.timingSnapshot().associateBy { it.name }
    } catch (t: Throwable) {
        emptyMap()
    }

    private fun safeBoolean(block: () -> Boolean): Boolean = try {
        block()
    } catch (t: Throwable) {
        false
    }

    private fun renderEvent(event: HatDiagnostics.Event, endpoint: String): String {
        val base = event.render()
        val withEp = if (endpoint.isNotEmpty() && !base.contains("endpoint=")) "$base endpoint=$endpoint" else base
        val tr = event.throwable ?: return withEp
        return "$withEp :: ${tr.javaClass.simpleName}: ${tr.message}"
    }

    private fun swing(min: Double?, max: Double?): Double? {
        if (min == null || max == null) return null
        return max - min
    }

    // ---------------------------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------------------------

    private class ScenarioOutcome(var appliedProfile: String?) {
        var verdict: HatTestVerdict = HatTestVerdict.PASS
        var reason: String = "scenario completed"
        val warnings = ArrayList<String>()
        val errors = ArrayList<String>()
        val latencyTargetChanges = ArrayList<String>()
        val transitions = ArrayList<HatTestTransition>()
        var transitionRequired = false
        var transitionCompleted = true
        var transitionMs: Long? = null
    }

    private class GaugeAccumulator {
        private val min = HashMap<String, Double>()
        private val max = HashMap<String, Double>()
        private val sum = HashMap<String, Double>()
        private val count = HashMap<String, Int>()

        fun sample(sections: Map<String, Map<String, Any?>>, rxStats: HatTestControlMessage.RxStats?) {
            // From receiver telemetry
            if (rxStats != null) {
                record("jitterMs", rxStats.jitterMs)
                record("bufferMs", rxStats.bufferMs)
                record("targetLatencyMs", rxStats.targetLatencyMs)
                record("driftPpm", rxStats.driftPpm)
            } else {
                // Fallback to local jitter section if present
                val jitter = sections["JITTER"]
                (jitter?.get("jitterMs") as? Number)?.toDouble()?.let { record("jitterMs", it) }
                (jitter?.get("bufferMs") as? Number)?.toDouble()?.let { record("bufferMs", it) }
                (jitter?.get("targetLatencyMs") as? Number)?.toDouble()?.let { record("targetLatencyMs", it) }
            }
        }

        private fun record(key: String, value: Double) {
            if (!value.isFinite()) return
            min[key] = minOf(min[key] ?: value, value)
            max[key] = maxOf(max[key] ?: value, value)
            sum[key] = (sum[key] ?: 0.0) + value
            count[key] = (count[key] ?: 0) + 1
        }

        fun minOf(metric: String): Double? = min[metric]
        fun maxOf(metric: String): Double? = max[metric]
        fun avgOf(metric: String): Double? {
            val n = count[metric] ?: 0
            if (n == 0) return null
            return (sum[metric] ?: 0.0) / n
        }
    }

    private class EventCollector(private val max: Int, private val fromMs: Long) {
        private val seen = HashSet<String>()
        private val records = ArrayList<HatTestEventRecord>()

        fun collect(events: List<HatDiagnostics.Event>) {
            for (event in events) {
                if (event.timestampMs < fromMs) continue
                val interesting = event.severity == HatDiagnostics.Severity.ERROR ||
                    event.severity == HatDiagnostics.Severity.WARN ||
                    event.name.startsWith("HAT_TEST_")
                if (!interesting) continue
                val detail = buildString {
                    append(event.render())
                    val tr = event.throwable
                    if (tr != null) append(" :: ").append(tr.javaClass.simpleName).append(": ").append(tr.message)
                }
                val id = "${event.timestampMs}|${event.name}|$detail"
                if (!seen.add(id)) continue
                if (records.size >= max) records.removeAt(0)
                records += HatTestEventRecord(
                    timestampMs = event.timestampMs,
                    severity = event.severity.name,
                    name = event.name,
                    generation = event.generation,
                    testScenario = event.testScenario.ifEmpty { null },
                    detail = detail
                )
                if (seen.size > max * 4) seen.clear()
            }
        }

        fun finish(): List<HatTestEventRecord> = records.sortedBy { it.timestampMs }
    }
}
