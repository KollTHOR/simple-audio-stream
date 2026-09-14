package com.example.audiostreamer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Automated "Full HAT Test" harness (Phase 3.1).
 *
 * The runner executes the supported runtime scenarios sequentially, collects the diagnostics that the
 * already-running services publish through [HatDiagnostics], correlates them with the run id, classifies each
 * scenario and produces a machine + human readable report.
 *
 * Design rules:
 *  - the runner never touches AudioRecord/AudioTrack, the jitter algorithm, FEC, codec negotiation,
 *    generation encoding, packetization or discovery;
 *  - profile switching goes exclusively through the existing production control path
 *    ([HatTestEnvironment.applyProfile] → `stream_prefs` + `ACTION_RESTART_CAPTURE`), never through a
 *    duplicated reconfiguration implementation;
 *  - no diagnostics architecture is duplicated: all metrics/events/snapshots come from [HatDiagnostics];
 *  - nothing runs on the main thread: the runner is a suspend component driven on a background dispatcher.
 *
 * Everything the runner needs from the outside world goes through [HatTestEnvironment]/[HatTestClock]/
 * [HatTestExporter] so the sequencing logic is unit testable without audio hardware.
 */

/** Scenario durations and switches. Centralised so development builds can shorten a run in one place. */
data class HatTestConfig(
    val baselineMs: Long = 30_000L,
    val normalMs: Long = 30_000L,
    val adaptiveMs: Long = 60_000L,
    val uncappedMs: Long = 60_000L,
    val lowLatencyMs: Long = 60_000L,
    val reconnectMs: Long = 30_000L,
    val stressPauseMs: Long = 10_000L,
    val stressTailMs: Long = 30_000L,
    val manualInterruptMs: Long = 30_000L,
    /** How long a generation transition may take before the scenario is failed. */
    val transitionTimeoutMs: Long = 15_000L,
    /** How long the manual "interrupt Wi-Fi then press Continue" checkpoint may stay open. */
    val manualContinueTimeoutMs: Long = 300_000L,
    /** Metric sampling period while a scenario is measuring. */
    val sampleIntervalMs: Long = 1_000L,
    /** Hard cap on retained WARN/ERROR events in the report. */
    val maxEvents: Int = 300,
    val includeBaseline: Boolean = true,
    val includeReceiverReconnect: Boolean = true,
    val includeManualNetworkInterrupt: Boolean = true
) {
    fun durationFor(scenario: HatTestScenario): Long = when (scenario) {
        HatTestScenario.BASELINE -> baselineMs
        HatTestScenario.NORMAL -> normalMs
        HatTestScenario.ADAPTIVE -> adaptiveMs
        HatTestScenario.UNCAPPED -> uncappedMs
        HatTestScenario.LOW_LATENCY -> lowLatencyMs
        HatTestScenario.PROFILE_STRESS -> stressTailMs
        HatTestScenario.RECEIVER_RECONNECT -> reconnectMs
        HatTestScenario.NETWORK_INTERRUPTION -> manualInterruptMs
    }

    val scenarios: List<HatTestScenario>
        get() = HatTestScenario.entries.filter { scenario ->
            when (scenario) {
                HatTestScenario.BASELINE -> includeBaseline
                HatTestScenario.RECEIVER_RECONNECT -> includeReceiverReconnect
                HatTestScenario.NETWORK_INTERRUPTION -> includeManualNetworkInterrupt
                else -> true
            }
        }

    companion object {
        val DEFAULT = HatTestConfig()

        /** Short development preset (smoke runs). Not wired to the Settings entry point. */
        val QUICK = HatTestConfig(
            baselineMs = 5_000L,
            normalMs = 5_000L,
            adaptiveMs = 5_000L,
            uncappedMs = 5_000L,
            lowLatencyMs = 5_000L,
            reconnectMs = 5_000L,
            stressPauseMs = 2_000L,
            stressTailMs = 5_000L,
            manualInterruptMs = 5_000L
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
    /** True when this device runs the receiver and it can be restarted locally. */
    fun canRestartLocalReceiver(): Boolean
    fun restartLocalReceiver(): Boolean
    fun isTransmitterRunning(): Boolean
    fun isReceiverRunning(): Boolean
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
}

/** Writes an exported report. Implementations must throw on failure; the runner reports the exact error. */
fun interface HatTestExporter {
    fun export(fileName: String, mimeType: String, content: String): HatTestExportedFile
}

/** Progress snapshot rendered by Settings → Diagnostics → Full HAT Test. */
data class HatTestProgress(
    val state: HatTestState = HatTestState.IDLE,
    val testRunId: String? = null,
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

    /** Set by [cancel]; checked between every wait step so cancellation is prompt and deterministic. */
    @Volatile
    private var cancelled = false

    private val manualGate = CompletableDeferred<Unit>()

    private var runStartMs = 0L
    private var scenarioCount = 0
    private var currentScenario: HatTestScenario? = null
    private var events: EventCollector = EventCollector(64, 0L)

    val isCancelled: Boolean get() = cancelled

    /** Requests cancellation. A pending manual checkpoint is released so it cannot hang the runner. */
    fun cancel() {
        cancelled = true
        manualGate.complete(Unit)
    }

    /** Releases a pending manual checkpoint (SCENARIO 8). */
    fun continueManualStep() {
        manualGate.complete(Unit)
    }

    // ---------------------------------------------------------------------------------------------
    // Run
    // ---------------------------------------------------------------------------------------------

    suspend fun run(): HatTestReport {
        // NOTE: a cancellation requested before the run started is deliberately NOT cleared here, so a runner
        // that was cancelled while it was still being dispatched cannot silently run anyway.
        val startMs = clock.nowMs()
        runStartMs = startMs
        val scenarioList = config.scenarios
        scenarioCount = scenarioList.size
        events = EventCollector(config.maxEvents, startMs)

        publish(
            state = HatTestState.PREPARING,
            message = "Checking preconditions",
            scenarioCount = scenarioCount,
            scenarioIndex = 0
        )

        val testRunId = HatDiagnostics.newRunId(env.deviceInfo().model)
        HatDiagnostics.startTestRun(testRunId)
        HatDiagnostics.info(
            "HAT_TEST_RUN_START",
            mapOf("testRunId" to testRunId, "scenarioCount" to scenarioCount, "config" to config.toString())
        )

        val preconditions = try {
            env.preconditions()
        } catch (t: Throwable) {
            HatTestPreconditions(
                ok = false,
                reasons = listOf("precondition check failed: ${t.javaClass.simpleName}: ${t.message}")
            )
        }

        val results = ArrayList<HatTestScenarioResult>(scenarioCount)

        if (!preconditions.ok) {
            // Refuse to run anything: the app must not pretend a test succeeded on a broken setup.
            HatDiagnostics.error(
                "HAT_TEST_PRECONDITION_FAILED",
                mapOf("testRunId" to testRunId, "reasons" to preconditions.reasons.joinToString("; "))
            )
        } else {
            publish(
                state = HatTestState.RUNNING,
                message = "Running ${scenarioList.size} scenarios",
                scenarioCount = scenarioCount
            )
            for ((index, scenario) in scenarioList.withIndex()) {
                if (cancelled) break
                currentScenario = scenario
                results += executeScenario(index + 1, scenario)
                events.collect(safeEvents())
                if (cancelled) break
            }
        }

        val endMs = clock.nowMs()
        val snapshot = safeSnapshot()
        val collectedEvents = events.finish()
        val summary = summarize(results, preconditions)

        publish(state = HatTestState.COMPLETING, message = "Building report", scenarioCount = scenarioCount)

        val report = HatTestReport(
            run = HatTestRunInfo(
                testRunId = testRunId,
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
            device = try {
                env.deviceInfo()
            } catch (t: Throwable) {
                HatTestDeviceInfo(null, null, null, null, null, null, null, null, null, null)
            },
            preconditions = preconditions,
            scenarios = results,
            finalSnapshot = snapshot,
            events = collectedEvents,
            summary = summary,
            notes = buildNotes(results, preconditions)
        )

        HatDiagnostics.info(
            "HAT_TEST_RUN_END",
            mapOf(
                "testRunId" to testRunId,
                "verdict" to summary.verdict,
                "failed" to summary.failed,
                "warned" to summary.warned,
                "passed" to summary.passed
            )
        )
        HatDiagnostics.endTestRun()

        val exportFailure = exportReport(report)
        val finalState = when {
            cancelled -> HatTestState.CANCELLED
            !preconditions.ok -> HatTestState.FAILED
            exportFailure != null -> HatTestState.FAILED
            else -> HatTestState.COMPLETED
        }
        publish(
            state = finalState,
            message = when {
                cancelled -> "Test cancelled - partial report exported"
                !preconditions.ok -> "Test not started - preconditions failed"
                exportFailure != null -> "Test complete, export failed: $exportFailure"
                else -> "Test complete"
            },
            error = when {
                cancelled -> "cancelled"
                !preconditions.ok -> preconditions.reasons.joinToString("; ")
                else -> exportFailure
            },
            awaitingManualContinue = false,
            report = report
        )
        currentScenario = null
        return report
    }

    // ---------------------------------------------------------------------------------------------
    // Scenario execution
    // ---------------------------------------------------------------------------------------------

    private suspend fun executeScenario(index: Int, scenario: HatTestScenario): HatTestScenarioResult {
        val startMs = clock.nowMs()
        val startSections = safeSections()
        val startSnapshot = safeSnapshot()
        val startTimings = timingMap()
        val startGeneration = safeGeneration()
        val startProfile = safeProfile()

        val counters = CounterAccumulator()
        val gauges = GaugeAccumulator()
        counters.sample(startSections, safeNamedCounters())
        gauges.sample(startSections)

        val outcome = ScenarioOutcome(appliedProfile = startProfile)
        HatDiagnostics.startScenario(scenario.id, scenario.displayName)
        publish(
            state = HatTestState.RUNNING,
            message = scenario.displayName,
            scenarioIndex = index,
            scenarioId = scenario.id,
            scenarioName = scenario.displayName,
            scenarioProgress = 0f,
            awaitingManualContinue = false
        )

        try {
            runScenarioBody(scenario, outcome, counters, gauges)
        } catch (c: CancellationException) {
            cancelled = true
            if (outcome.verdict == HatTestVerdict.PASS) {
                outcome.verdict = HatTestVerdict.SKIPPED
                outcome.reason = "test cancelled during scenario"
            }
        } catch (t: Throwable) {
            outcome.verdict = HatTestVerdict.FAIL
            outcome.reason = "test runner exception: ${t.javaClass.simpleName}: ${t.message}"
            outcome.errors += outcome.reason
        }

        // Final sample + end-of-scenario snapshot. Non-suspending, so it still runs after cancellation.
        val endSections = safeSections()
        counters.sample(endSections, safeNamedCounters())
        gauges.sample(endSections)
        val endSnapshot = safeSnapshot()
        val endGeneration = safeGeneration()
        val endProfile = safeProfile()
        val endMs = clock.nowMs()
        val endTimings = timingMap()

        if (outcome.appliedProfile == null) outcome.appliedProfile = endProfile

        val generationMonotonic = endGeneration >= startGeneration
        if (!generationMonotonic && outcome.verdict == HatTestVerdict.PASS) {
            outcome.verdict = HatTestVerdict.FAIL
            outcome.reason = "stream generation moved backwards (start=$startGeneration end=$endGeneration)"
            outcome.errors += outcome.reason
        }

        val metrics = buildMetrics(counters, gauges, startTimings, endTimings)

        // Scenario-scoped diagnostics: WARN/ERROR events recorded while this scenario was running.
        val scenarioEvents = safeEvents().filter { it.timestampMs >= startMs && it.timestampMs <= endMs }
        for (event in scenarioEvents) {
            when (event.severity) {
                HatDiagnostics.Severity.ERROR -> outcome.errors += renderEvent(event)
                HatDiagnostics.Severity.WARN -> outcome.warnings += renderEvent(event)
                else -> {}
            }
        }
        outcome.latencyTargetChanges += scenarioEvents
            .filter { it.name == "JITTER_LATENCY_CHANGE" }
            .map { renderEvent(it) }

        // A scenario interrupted by cancellation is never reported as a pass: it did not measure its window.
        if (cancelled && outcome.verdict == HatTestVerdict.PASS) {
            outcome.verdict = HatTestVerdict.SKIPPED
            outcome.reason = "test cancelled during scenario (measurement incomplete)"
        }
        if (outcome.verdict == HatTestVerdict.PASS) {
            val classification = classify(scenario, outcome, metrics, endSections)
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
            awaitingManualContinue = false
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
        counters: CounterAccumulator,
        gauges: GaugeAccumulator
    ) {
        when (scenario) {
            HatTestScenario.BASELINE -> {
                outcome.reason = "baseline measured with the current profile left unchanged"
                awaitDuration(config.durationFor(scenario), counters, gauges)
            }

            HatTestScenario.NORMAL,
            HatTestScenario.ADAPTIVE,
            HatTestScenario.UNCAPPED,
            HatTestScenario.LOW_LATENCY -> {
                val target = scenario.targetProfile
                if (target == null) {
                    outcome.reason = "scenario has no target profile"
                    awaitDuration(config.durationFor(scenario), counters, gauges)
                    return
                }
                val transition = applyAndAwaitTransition(target, outcome, transitionsIndex = 0)
                outcome.transitions += transition
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
                    "profile switched to ${target.name} in ${transition.durationMs}ms"
                } else {
                    "profile already active; measured without a transition"
                }
                awaitDuration(config.durationFor(scenario), counters, gauges)
            }

            HatTestScenario.PROFILE_STRESS -> {
                var allTransitionsOk = true
                var previousGeneration = safeGeneration()
                for ((i, target) in HatTestScenario.STRESS_SEQUENCE.withIndex()) {
                    if (cancelled) return
                    val transition = applyAndAwaitTransition(target, outcome, transitionsIndex = i + 1)
                    outcome.transitions += transition
                    if (transition.transitionRequired && !transition.ok) {
                        allTransitionsOk = false
                        outcome.errors += "transition #${i + 1} to ${target.name}: ${transition.reason}"
                    }
                    if (transition.toGeneration < previousGeneration) {
                        allTransitionsOk = false
                        outcome.errors += "generation not monotonic on transition #${i + 1}: " +
                            "${previousGeneration} → ${transition.toGeneration}"
                    }
                    previousGeneration = transition.toGeneration
                    awaitDuration(config.stressPauseMs, counters, gauges)
                }
                outcome.transitionRequired = outcome.transitions.any { it.transitionRequired }
                outcome.transitionCompleted = allTransitionsOk
                outcome.transitionMs = outcome.transitions.sumOf { it.durationMs }
                outcome.reason = if (allTransitionsOk) {
                    "all ${outcome.transitions.size} profile transitions completed with monotonic generations"
                } else {
                    "one or more profile transitions failed or were not monotonic"
                }
                if (!allTransitionsOk) outcome.verdict = HatTestVerdict.FAIL
                awaitDuration(config.stressTailMs, counters, gauges)
            }

            HatTestScenario.RECEIVER_RECONNECT -> {
                if (!safeBoolean { env.canRestartLocalReceiver() }) {
                    outcome.verdict = HatTestVerdict.MANUAL_REQUIRED
                    outcome.reason = "receiver reconnect cannot be triggered automatically from this device; " +
                        "restart the receiver manually if needed"
                    return
                }
                if (!safeBoolean { env.restartLocalReceiver() }) {
                    outcome.verdict = HatTestVerdict.FAIL
                    outcome.reason = "local receiver could not be restarted"
                    outcome.errors += outcome.reason
                    return
                }
                outcome.reason = "local receiver restarted; measuring recovery"
                awaitDuration(config.durationFor(scenario), counters, gauges)
            }

            HatTestScenario.NETWORK_INTERRUPTION -> {
                outcome.reason = "manual network interruption"
                publish(
                    state = HatTestState.RUNNING,
                    message = "Pause test here and temporarily interrupt Wi-Fi, then resume.",
                    awaitingManualContinue = true
                )
                val resumed = awaitManualContinue()
                if (!resumed) {
                    outcome.verdict = HatTestVerdict.FAIL
                    outcome.reason = "manual network interruption checkpoint timed out"
                    outcome.errors += outcome.reason
                    return
                }
                if (cancelled) return
                outcome.reason = "manual interruption completed; recovery measured"
                awaitDuration(config.durationFor(scenario), counters, gauges)
            }
        }
    }

    private suspend fun awaitManualContinue(): Boolean {
        val resumed = try {
            withTimeoutOrNull(config.manualContinueTimeoutMs) { manualGate.await() }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
        return resumed != null && !cancelled
    }

    /**
     * Requests a profile change through the existing production path and waits for the new generation to become
     * authoritative. A transition is never fabricated: when the requested profile is already active it is
     * reported as `transitionRequired = false` and the scenario still measures.
     */
    private suspend fun applyAndAwaitTransition(
        target: LatencyTarget,
        outcome: ScenarioOutcome,
        transitionsIndex: Int
    ): HatTestTransition {
        val beforeProfile = safeProfile()
        val beforeGeneration = safeGeneration()

        if (beforeProfile != null && beforeProfile.equals(target.name, ignoreCase = true)) {
            outcome.appliedProfile = beforeProfile
            return HatTestTransition(
                index = transitionsIndex,
                fromProfile = beforeProfile,
                toProfile = target.name,
                fromGeneration = beforeGeneration,
                toGeneration = beforeGeneration,
                durationMs = 0L,
                transitionRequired = false,
                ok = true,
                reason = "profile already active; no transition required"
            )
        }

        val requestedAt = clock.nowMs()
        if (!safeBoolean { env.applyProfile(target) }) {
            return HatTestTransition(
                index = transitionsIndex,
                fromProfile = beforeProfile,
                toProfile = target.name,
                fromGeneration = beforeGeneration,
                toGeneration = beforeGeneration,
                durationMs = clock.nowMs() - requestedAt,
                transitionRequired = true,
                ok = false,
                reason = "profile change request could not be delivered"
            )
        }
        outcome.appliedProfile = target.name

        val pollMs = minOf(config.sampleIntervalMs, 250L).coerceAtLeast(10L)
        var ok = false
        while (!cancelled) {
            if (clock.nowMs() - requestedAt >= config.transitionTimeoutMs) break
            clock.wait(pollMs)
            val generation = safeGeneration()
            val profile = safeProfile()
            if (generation > beforeGeneration && (profile == null || profile.equals(target.name, ignoreCase = true))) {
                ok = true
                break
            }
        }
        val durationMs = clock.nowMs() - requestedAt
        return HatTestTransition(
            index = transitionsIndex,
            fromProfile = beforeProfile,
            toProfile = target.name,
            fromGeneration = beforeGeneration,
            toGeneration = safeGeneration(),
            durationMs = durationMs,
            transitionRequired = true,
            ok = ok,
            reason = if (ok) null else "generation did not advance to ${target.name} within ${config.transitionTimeoutMs}ms"
        )
    }

    /** Measures for [durationMs], sampling counters/gauges once per sample interval. */
    private suspend fun awaitDuration(
        durationMs: Long,
        counters: CounterAccumulator,
        gauges: GaugeAccumulator
    ) {
        if (durationMs <= 0L) return
        var elapsed = 0L
        while (elapsed < durationMs) {
            if (cancelled) return
            val step = minOf(config.sampleIntervalMs, durationMs - elapsed).coerceAtLeast(1L)
            clock.wait(step)
            elapsed += step
            if (cancelled) return
            val sections = safeSections()
            counters.sample(sections, safeNamedCounters())
            gauges.sample(sections)
            events.collect(safeEvents())
            publish(
                state = HatTestState.RUNNING,
                message = currentScenario?.displayName,
                scenarioProgress = (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Classification
    // ---------------------------------------------------------------------------------------------

    private fun classify(
        scenario: HatTestScenario,
        outcome: ScenarioOutcome,
        metrics: HatTestMetrics,
        endSections: Map<String, Map<String, Any?>>
    ): Pair<HatTestVerdict, String> {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>(outcome.warnings)

        if (metrics.configInitFailures > 0L) errors += "capture pipeline initialization failed during the scenario"
        if (metrics.configAnnounceFailures > 0L) errors += "stream announcement failed during the scenario"
        if (metrics.configProducerStartFailures > 0L) errors += "producer startup failed during the scenario"
        if (metrics.captureReadErrors > 0L && metrics.framesCaptured <= 0L) {
            errors += "capture read failed with no frames captured"
        }
        if (metrics.generationMismatches > 0L) {
            errors += "receiver rejected ${metrics.generationMismatches} packet(s) from a stale generation"
        }
        if (metrics.codecMismatches > 0L) {
            warnings += "receiver rejected ${metrics.codecMismatches} packet(s) with an unknown codec/config"
        }

        // Playback health is only asserted where the local device actually plays audio (receiver role).
        val playbackEnd = endSections["PLAYBACK"]
        if (playbackEnd != null && playbackEnd.isNotEmpty()) {
            val playState = (playbackEnd["playState"] as? Number)?.toInt()
            if (playState != null && playState != PLAY_STATE_PLAYING) {
                errors += "playback stopped unexpectedly during the scenario (playState=$playState)"
            }
        }

        if (metrics.underruns > 0L) warnings += "${metrics.underruns} playback underrun(s)"
        if (metrics.packetsLost > 0L) warnings += "${metrics.packetsLost} lost/concealed packet(s)"
        if (metrics.packetsLate > 0L) warnings += "${metrics.packetsLate} late packet(s)"
        if (metrics.packetsOutOfOrder > 0L) warnings += "${metrics.packetsOutOfOrder} out-of-order packet(s)"
        if (metrics.fecRecovered > 0L) warnings += "${metrics.fecRecovered} packet(s) recovered by FEC"
        if (metrics.decodeErrors > 0L) warnings += "${metrics.decodeErrors} decode error(s)"
        if (metrics.writeErrors > 0L) warnings += "${metrics.writeErrors} AudioTrack write error(s)"
        if (metrics.sendErrors > 0L) warnings += "${metrics.sendErrors} socket send error(s)"

        val targetSwing = swing(metrics.minTargetLatencyMs, metrics.maxTargetLatencyMs)
        if (targetSwing != null && targetSwing > LATENCY_INSTABILITY_MS) {
            warnings += "latency target moved by ${formatDouble(targetSwing)}ms during the scenario"
        }
        val jitterPeak = metrics.maxJitterMs
        if (jitterPeak != null && jitterPeak > JITTER_WARN_MS) {
            warnings += "jitter peaked at ${formatDouble(jitterPeak)}ms"
        }
        if (metrics.packetsSent > 0L && metrics.packetsReceived <= 0L && metrics.hasReceiverTelemetry) {
            warnings += "transmitter sent packets but the receiver side reported none"
        }

        outcome.errors += errors
        outcome.warnings += warnings

        val label = if (scenario === HatTestScenario.BASELINE) "baseline" else "scenario"
        return when {
            errors.isNotEmpty() -> HatTestVerdict.FAIL to errors.joinToString("; ")
            warnings.isNotEmpty() -> HatTestVerdict.WARN to
                "$label completed with ${warnings.size} warning(s): ${warnings.joinToString("; ")}"
            // A clean scenario keeps the reason produced by its own body (e.g. "profile already active; ...").
            else -> HatTestVerdict.PASS to outcome.reason
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Metrics
    // ---------------------------------------------------------------------------------------------

    private fun buildMetrics(
        counters: CounterAccumulator,
        gauges: GaugeAccumulator,
        startTimings: Map<String, HatDiagnostics.Timing.Snapshot>,
        endTimings: Map<String, HatDiagnostics.Timing.Snapshot>
    ): HatTestMetrics {
        val captureRead = timingWindow("captureRead", startTimings, endTimings)
        val encode = timingWindow("encode", startTimings, endTimings)
        val send = timingWindow("send", startTimings, endTimings)
        val receive = timingWindow("receive", startTimings, endTimings)
        val decode = timingWindow("decode", startTimings, endTimings)
        val write = timingWindow("audioTrackWrite", startTimings, endTimings)

        return HatTestMetrics(
            packetsGenerated = counters.total(MetricsKey.PACKETS_GENERATED),
            packetsSent = counters.total(MetricsKey.PACKETS_SENT),
            bytesSent = counters.total(MetricsKey.BYTES_SENT),
            sendErrors = counters.total(MetricsKey.SEND_ERRORS),
            packetsReceived = counters.total(MetricsKey.PACKETS_RECEIVED),
            bytesReceived = counters.total(MetricsKey.BYTES_RECEIVED),
            packetsLost = counters.total(MetricsKey.PACKETS_LOST),
            packetsLate = counters.total(MetricsKey.PACKETS_LATE),
            packetsOutOfOrder = counters.total(MetricsKey.PACKETS_OUT_OF_ORDER),
            packetsDuplicate = counters.total(MetricsKey.PACKETS_DUPLICATE),
            fecRecovered = counters.total(MetricsKey.FEC_RECOVERED),
            decodeErrors = counters.total(MetricsKey.DECODE_ERRORS),
            writeErrors = counters.total(MetricsKey.WRITE_ERRORS),
            underruns = counters.total(MetricsKey.UNDERRUNS),
            captureReadErrors = counters.total(MetricsKey.CAPTURE_READ_ERRORS),
            framesCaptured = counters.total(MetricsKey.FRAMES_CAPTURED),
            generationMismatches = counters.total(MetricsKey.GENERATION_MISMATCHES),
            codecMismatches = counters.total(MetricsKey.CODEC_MISMATCHES),
            configInitFailures = counters.total(MetricsKey.CONFIG_INIT_FAILURES),
            configAnnounceFailures = counters.total(MetricsKey.CONFIG_ANNOUNCE_FAILURES),
            configProducerStartFailures = counters.total(MetricsKey.CONFIG_PRODUCER_START_FAILURES),
            minJitterMs = gauges.minOf(MetricsKey.JITTER_MS),
            avgJitterMs = gauges.avgOf(MetricsKey.JITTER_MS),
            maxJitterMs = gauges.maxOf(MetricsKey.JITTER_MS),
            minBufferMs = gauges.minOf(MetricsKey.BUFFER_MS),
            avgBufferMs = gauges.avgOf(MetricsKey.BUFFER_MS),
            maxBufferMs = gauges.maxOf(MetricsKey.BUFFER_MS),
            minTargetLatencyMs = gauges.minOf(MetricsKey.TARGET_LATENCY_MS),
            avgTargetLatencyMs = gauges.avgOf(MetricsKey.TARGET_LATENCY_MS),
            maxTargetLatencyMs = gauges.maxOf(MetricsKey.TARGET_LATENCY_MS),
            avgCaptureReadMs = captureRead.first,
            maxCaptureReadMs = captureRead.second,
            avgEncodeMs = encode.first,
            maxEncodeMs = encode.second,
            avgSendMs = send.first,
            maxSendMs = send.second,
            avgReceiveMs = receive.first,
            maxReceiveMs = receive.second,
            avgDecodeMs = decode.first,
            maxDecodeMs = decode.second,
            avgWriteMs = write.first,
            maxWriteMs = write.second
        )
    }

    /**
     * Per-scenario average (exact, from the totals delta) and maximum (the worst case recorded up to the end of
     * the scenario, because per-operation maxima are cumulative for the process — documented in the report).
     */
    private fun timingWindow(
        name: String,
        start: Map<String, HatDiagnostics.Timing.Snapshot>,
        end: Map<String, HatDiagnostics.Timing.Snapshot>
    ): Pair<Double?, Double?> {
        val s = start[name] ?: return null to null
        val e = end[name] ?: return null to null
        val countDelta = e.count - s.count
        if (countDelta <= 0L) return null to null
        val totalDeltaNs = (e.avgNs * e.count) - (s.avgNs * s.count)
        val avgMs = if (totalDeltaNs > 0L) totalDeltaNs.toDouble() / countDelta.toDouble() / 1_000_000.0 else 0.0
        val maxMs = e.maxNs / 1_000_000.0
        return avgMs to maxMs
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
            actualPerformanceMode = (playback["performanceMode"] as? Number)?.toInt()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Report assembly
    // ---------------------------------------------------------------------------------------------

    private fun summarize(results: List<HatTestScenarioResult>, preconditions: HatTestPreconditions): HatTestSummary {
        val passed = results.count { it.verdict == HatTestVerdict.PASS }
        val warned = results.count { it.verdict == HatTestVerdict.WARN }
        val failed = results.count { it.verdict == HatTestVerdict.FAIL }
        val skipped = results.count { it.verdict == HatTestVerdict.SKIPPED }
        val manual = results.count { it.verdict == HatTestVerdict.MANUAL_REQUIRED }
        val verdict = when {
            !preconditions.ok -> "NOT RUN (preconditions failed)"
            results.isEmpty() -> "NO SCENARIOS EXECUTED"
            failed > 0 -> "FAIL"
            warned > 0 || manual > 0 -> "PASS WITH WARNINGS"
            else -> "PASS"
        }
        val findings = ArrayList<String>()
        if (!preconditions.ok) preconditions.reasons.forEach { findings += "precondition: $it" }
        for (result in results) {
            if (result.verdict != HatTestVerdict.PASS) {
                findings += "#${result.index} ${result.scenarioName}: ${result.verdict.label} - ${result.reason}"
            }
        }
        return HatTestSummary(
            total = results.size,
            passed = passed,
            warned = warned,
            failed = failed,
            skipped = skipped,
            manualRequired = manual,
            verdict = verdict,
            findings = findings
        )
    }

    private fun buildNotes(
        results: List<HatTestScenarioResult>,
        preconditions: HatTestPreconditions
    ): List<String> {
        val notes = ArrayList<String>()
        notes += "Scenario durations come from HatTestConfig (development builds may use HatTestConfig.QUICK)."
        notes += "NORMAL and UNCAPPED both resolve to this build's Music / uncompressed lossless profile; " +
            "ADAPTIVE maps to Auto Adaptive and LOW LATENCY to the Opus low-latency profile."
        notes += "max*Ms metric values are the worst case recorded up to the end of the scenario " +
            "(per-operation maxima are cumulative for the process); avg*Ms are exact per-scenario averages."
        notes += "'jitterProcess' timing is not instrumented in this build; the jitter path was not modified."
        if (!preconditions.ok) notes += "No scenario was executed because a precondition failed."
        val notExecuted = config.scenarios.size - results.size
        if (notExecuted > 0) notes += "$notExecuted scenario(s) were not executed (run stopped early or cancelled)."
        for (result in results) {
            if (result.verdict == HatTestVerdict.MANUAL_REQUIRED) {
                notes += "SCENARIO ${result.index} (${result.scenarioName}) requires manual action: ${result.reason}"
            }
        }
        return notes
    }

    private fun exportReport(report: HatTestReport): String? {
        val activeExporter = exporter ?: return "no exporter configured in this build"
        publish(state = HatTestState.EXPORTING, message = "Writing report to Downloads/HAT")
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
        awaitingManualContinue: Boolean = _progress.value.awaitingManualContinue,
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
            testRunId = _progress.value.testRunId
                ?: HatDiagnostics.testRunId().ifEmpty { null },
            scenarioIndex = scenarioIndex,
            scenarioCount = scenarioCount,
            scenarioId = scenarioId,
            scenarioName = scenarioName,
            scenarioProgress = scenarioProgress,
            overallProgress = overall,
            elapsedMs = elapsed,
            message = message,
            awaitingManualContinue = awaitingManualContinue,
            exportedFiles = exportedFiles,
            exportedUris = exportedUris,
            report = report,
            error = error
        )
        _progress.value = next
        try {
            listener(next)
        } catch (ignored: Throwable) {
            // a progress listener must never break a run
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Safe environment access (a diagnostics/UI failure must never abort a run silently)
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

    private fun safeNamedCounters(): Map<String, Long> = try {
        env.counterSnapshot()
    } catch (t: Throwable) {
        emptyMap()
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

    private fun renderEvent(event: HatDiagnostics.Event): String {
        val base = event.render()
        val tr = event.throwable ?: return base
        return "$base :: ${tr.javaClass.simpleName}: ${tr.message}"
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

    /**
     * Sums monotonically increasing counters as positive deltas between samples, picking the first available
     * source per metric (transmitter sections on a transmitter, receiver sections on a receiver, named
     * HatDiagnostics counters where the value lives there — section name "" marks a named counter). A counter
     * reset inside a scenario (e.g. a receiver reconfigure) is ignored rather than producing a negative delta.
     */
    private class CounterAccumulator {
        class Spec(val metric: String, val candidates: List<Pair<String, String>>)

        private val totals = HashMap<String, Long>()
        private val last = HashMap<String, Long>()

        fun sample(sections: Map<String, Map<String, Any?>>, named: Map<String, Long>) {
            for (spec in SPECS) {
                var found: Long? = null
                for ((section, key) in spec.candidates) {
                    found = if (section.isEmpty()) {
                        named[key]
                    } else {
                        (sections[section]?.get(key) as? Number)?.toLong()
                    }
                    if (found != null) break
                }
                val value = found ?: continue
                val previous = last[spec.metric]
                if (previous != null && value > previous) {
                    totals[spec.metric] = (totals[spec.metric] ?: 0L) + (value - previous)
                }
                last[spec.metric] = value
            }
        }

        fun total(metric: String): Long = totals[metric] ?: 0L

        companion object {
            /** Empty section name means "read from the HatDiagnostics named counter table". */
            private const val NAMED = ""

            val SPECS: List<Spec> = listOf(
                Spec(MetricsKey.PACKETS_GENERATED, listOf("TX" to "packetsGenerated", "CAPTURE" to "packetsGenerated")),
                Spec(MetricsKey.PACKETS_SENT, listOf("TX" to "packetsSent", "CAPTURE" to "packetsSent")),
                Spec(MetricsKey.BYTES_SENT, listOf("TX" to "bytesSent", "CAPTURE" to "bytesSent")),
                Spec(MetricsKey.SEND_ERRORS, listOf("TX" to "sendErrors")),
                Spec(MetricsKey.PACKETS_RECEIVED, listOf("RX" to "packetsReceived")),
                Spec(MetricsKey.BYTES_RECEIVED, listOf("RX" to "bytesReceived")),
                Spec(MetricsKey.PACKETS_LOST, listOf("RX" to "lostPackets", "JITTER" to "missingPackets")),
                Spec(MetricsKey.PACKETS_LATE, listOf("RX" to "latePackets", "JITTER" to "latePackets")),
                Spec(MetricsKey.PACKETS_OUT_OF_ORDER, listOf("RX" to "outOfOrder", "JITTER" to "outOfOrder")),
                Spec(MetricsKey.PACKETS_DUPLICATE, listOf("RX" to "duplicates", "JITTER" to "duplicates")),
                Spec(MetricsKey.FEC_RECOVERED, listOf("RX" to "fecRecovered", "JITTER" to "fecRecovered")),
                Spec(MetricsKey.DECODE_ERRORS, listOf("RX" to "decodeErrors")),
                Spec(MetricsKey.WRITE_ERRORS, listOf("PLAYBACK" to "writeErrors")),
                Spec(MetricsKey.UNDERRUNS, listOf("PLAYBACK" to "underruns")),
                Spec(MetricsKey.CAPTURE_READ_ERRORS, listOf("CAPTURE" to "readErrors")),
                Spec(MetricsKey.FRAMES_CAPTURED, listOf("CAPTURE" to "framesCaptured")),
                Spec(MetricsKey.GENERATION_MISMATCHES, listOf("RX" to "unknownGeneration")),
                Spec(MetricsKey.CODEC_MISMATCHES, listOf("RX" to "unknownCodec")),
                Spec(MetricsKey.CONFIG_INIT_FAILURES, listOf(NAMED to "config_init_failures")),
                Spec(MetricsKey.CONFIG_ANNOUNCE_FAILURES, listOf(NAMED to "config_announce_failures")),
                Spec(MetricsKey.CONFIG_PRODUCER_START_FAILURES, listOf(NAMED to "config_producer_start_failures"))
            )
        }
    }

    /** Min/max/average of an instantaneous reading sampled once per interval. */
    private class GaugeAccumulator {
        private val min = HashMap<String, Double>()
        private val max = HashMap<String, Double>()
        private val sum = HashMap<String, Double>()
        private val count = HashMap<String, Int>()

        fun sample(sections: Map<String, Map<String, Any?>>) {
            for ((metric, source) in GAUGE_SOURCES) {
                val value = (sections[source.first]?.get(source.second) as? Number)?.toDouble() ?: continue
                if (!value.isFinite()) continue
                min[metric] = minOf(min[metric] ?: value, value)
                max[metric] = maxOf(max[metric] ?: value, value)
                sum[metric] = (sum[metric] ?: 0.0) + value
                count[metric] = (count[metric] ?: 0) + 1
            }
        }

        fun minOf(metric: String): Double? = min[metric]
        fun maxOf(metric: String): Double? = max[metric]

        fun avgOf(metric: String): Double? {
            val n = count[metric] ?: 0
            if (n == 0) return null
            return (sum[metric] ?: 0.0) / n
        }
    }

    /**
     * Retains a bounded, de-duplicated set of WARN/ERROR + harness-boundary events for the report. The ring
     * itself is already bounded in [HatDiagnostics]; this only keeps the interesting slice for one run.
     */
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

    private companion object {
        const val PLAY_STATE_PLAYING = 3
        const val LATENCY_INSTABILITY_MS = 150.0
        const val JITTER_WARN_MS = 80.0
    }
}

/** Metric keys shared by the runner and its accumulators. */
private object MetricsKey {
    const val PACKETS_GENERATED = "packetsGenerated"
    const val PACKETS_SENT = "packetsSent"
    const val BYTES_SENT = "bytesSent"
    const val SEND_ERRORS = "sendErrors"
    const val PACKETS_RECEIVED = "packetsReceived"
    const val BYTES_RECEIVED = "bytesReceived"
    const val PACKETS_LOST = "packetsLost"
    const val PACKETS_LATE = "packetsLate"
    const val PACKETS_OUT_OF_ORDER = "packetsOutOfOrder"
    const val PACKETS_DUPLICATE = "packetsDuplicate"
    const val FEC_RECOVERED = "fecRecovered"
    const val DECODE_ERRORS = "decodeErrors"
    const val WRITE_ERRORS = "writeErrors"
    const val UNDERRUNS = "underruns"
    const val CAPTURE_READ_ERRORS = "captureReadErrors"
    const val FRAMES_CAPTURED = "framesCaptured"
    const val GENERATION_MISMATCHES = "generationMismatches"
    const val CODEC_MISMATCHES = "codecMismatches"
    const val CONFIG_INIT_FAILURES = "configInitFailures"
    const val CONFIG_ANNOUNCE_FAILURES = "configAnnounceFailures"
    const val CONFIG_PRODUCER_START_FAILURES = "configProducerStartFailures"
    const val JITTER_MS = "jitterMs"
    const val BUFFER_MS = "bufferMs"
    const val TARGET_LATENCY_MS = "targetLatencyMs"
}

/** (metric key) → (snapshot section, key) for the instantaneous readings sampled during a scenario. */
private val GAUGE_SOURCES: List<Pair<String, Pair<String, String>>> = listOf(
    MetricsKey.JITTER_MS to ("JITTER" to "jitterMs"),
    MetricsKey.BUFFER_MS to ("JITTER" to "bufferMs"),
    MetricsKey.TARGET_LATENCY_MS to ("JITTER" to "targetLatencyMs")
)
