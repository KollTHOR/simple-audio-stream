package com.example.audiostreamer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Unit tests for the Full HAT Test harness.
 *
 * The runner is exercised through its real code path — no parallel test-only transaction architecture — with a
 * virtual clock and a fake environment, so no AudioRecord/AudioTrack hardware is required and a full multi
 * minute scenario sweep runs in milliseconds.
 */
class HatTestRunnerTest {

    // ---------------------------------------------------------------------------------------------
    // Sequencing / success path
    // ---------------------------------------------------------------------------------------------

    @Test
    fun scenariosRunInTheDocumentedOrder() = runBlocking {
        val env = FakeEnv()
        val runner = HatTestRunner(env, testConfig(), VirtualClock(), RecordingExporter())

        val report = runner.run()

        val expected = HatTestScenario.entries
            .filter { it != HatTestScenario.RECEIVER_RECONNECT && it != HatTestScenario.NETWORK_INTERRUPTION }
            .map { it.id }
        assertEquals(expected, report.scenarios.map { it.scenarioId })
        assertEquals(expected.size, report.summary.total)
        assertEquals(1, report.scenarios.first().index)
        assertEquals(expected.size, report.scenarios.last().index)
    }

    @Test
    fun successfulRunSwitchesProfilesCommitsTransitionsAndExports() = runBlocking {
        val env = FakeEnv()
        val exporter = RecordingExporter()
        val runner = HatTestRunner(env, testConfig(), VirtualClock(), exporter)

        val report = runner.run()

        // One profile request per profile scenario (baseline does not change the profile).
        assertEquals(
            listOf("RELIABLE", "BALANCED", "RELIABLE", "LOW_LATENCY") + HatTestScenario.STRESS_SEQUENCE.map { it.name },
            env.appliedProfiles.toList()
        )

        val normal = report.scenarios.first { it.scenarioId == HatTestScenario.NORMAL.id }
        assertTrue(normal.transitionRequired)
        assertTrue(normal.transitionCompleted)
        assertTrue(normal.endGeneration > normal.startGeneration)
        assertTrue(normal.generationMonotonic)

        val baseline = report.scenarios.first { it.scenarioId == HatTestScenario.BASELINE.id }
        assertFalse(baseline.transitionRequired)
        assertNull(baseline.requestedProfile)

        assertTrue(report.summary.verdict == "PASS")
        assertEquals(2, exporter.files.size)
        assertTrue(exporter.files.keys.any { it.endsWith(".log") })
        assertTrue(exporter.files.keys.any { it.endsWith(".json") })
        assertEquals(HatTestState.COMPLETED, runner.progress.value.state)
        assertTrue(runner.progress.value.exportedUris.isNotEmpty())
    }

    @Test
    fun progressReportsOverallAndScenarioCompletion() = runBlocking {
        val runner = HatTestRunner(FakeEnv(), testConfig(), VirtualClock(), RecordingExporter())

        val report = runner.run()

        assertEquals(HatTestState.COMPLETED, runner.progress.value.state)
        assertEquals(1f, runner.progress.value.overallProgress)
        assertEquals(report.scenarios.size, runner.progress.value.scenarioCount)
        assertTrue(runner.progress.value.elapsedMs > 0L)
        assertNotNull(runner.progress.value.report)
    }

    @Test
    fun targetProfileAlreadyActiveIsReportedAsNoTransitionInsteadOfFaked() = runBlocking {
        // The fake starts on RELIABLE, which is exactly the NORMAL scenario's target.
        val env = FakeEnv(initialProfile = "RELIABLE")
        val runner = HatTestRunner(env, testConfig(), VirtualClock(), RecordingExporter())

        val report = runner.run()

        val normal = report.scenarios.first { it.scenarioId == HatTestScenario.NORMAL.id }
        assertFalse(normal.transitionRequired)
        assertTrue(normal.transitionCompleted)
        assertEquals(normal.startGeneration, normal.endGeneration)
        assertTrue(normal.reason.contains("already active"))
        // The scenario still measured its window rather than being skipped.
        assertTrue(normal.durationMs > 0L)
    }

    // ---------------------------------------------------------------------------------------------
    // Failure handling
    // ---------------------------------------------------------------------------------------------

    @Test
    fun transitionTimeoutFailsTheScenarioWithoutHanging() = runBlocking {
        val env = FakeEnv(autoTransition = false)
        val config = testConfig(transitionTimeoutMs = 100L)
        val runner = HatTestRunner(env, config, VirtualClock(), RecordingExporter())

        val report = runner.run()

        val normal = report.scenarios.first { it.scenarioId == HatTestScenario.NORMAL.id }
        assertEquals(HatTestVerdict.FAIL, normal.verdict)
        assertTrue(normal.reason.contains("did not advance") || normal.reason.contains("transition"))
        assertTrue(normal.transitionRequired)
        assertFalse(normal.transitionCompleted)
    }

    @Test
    fun failedScenarioDoesNotStopTheRemainingScenarios() = runBlocking {
        val env = FakeEnv(autoTransition = false)
        val runner = HatTestRunner(env, testConfig(transitionTimeoutMs = 100L), VirtualClock(), RecordingExporter())

        val report = runner.run()

        assertEquals(6, report.scenarios.size)
        assertEquals(HatTestVerdict.FAIL, report.scenarios[1].verdict)
        assertTrue(report.scenarios[2].index == 3)
        assertEquals(HatTestScenario.PROFILE_STRESS.id, report.scenarios.last().scenarioId)
        assertEquals("FAIL", report.summary.verdict)
        assertTrue(report.summary.findings.isNotEmpty())
    }

    @Test
    fun profileRequestFailureIsReportedAsAFailedTransition() = runBlocking {
        val env = FakeEnv(applyResult = false)
        val runner = HatTestRunner(env, testConfig(), VirtualClock(), RecordingExporter())

        val report = runner.run()

        val normal = report.scenarios.first { it.scenarioId == HatTestScenario.NORMAL.id }
        assertEquals(HatTestVerdict.FAIL, normal.verdict)
        assertTrue(normal.reason.contains("could not be delivered"))
    }

    @Test
    fun generationGoingBackwardsFailsTheScenario() = runBlocking {
        val env = FakeEnv()
        env.generationDropAtCall = 3
        val runner = HatTestRunner(env, testConfig(), VirtualClock(), RecordingExporter())

        val report = runner.run()

        val baseline = report.scenarios.first()
        assertFalse(baseline.generationMonotonic)
        assertEquals(HatTestVerdict.FAIL, baseline.verdict)
        assertTrue(baseline.reason.contains("backwards"))
    }

    @Test
    fun preconditionFailureSkipsEveryScenarioAndStillExportsAFailureReport() = runBlocking {
        val env = FakeEnv(
            preconditions = HatTestPreconditions(
                ok = false,
                reasons = listOf("no receiver is connected (activeReceivers=0)"),
                details = mapOf("activeReceivers" to 0)
            )
        )
        val exporter = RecordingExporter()
        val runner = HatTestRunner(env, testConfig(), VirtualClock(), exporter)

        val report = runner.run()

        assertTrue(report.scenarios.isEmpty())
        assertFalse(report.preconditions.ok)
        assertTrue(report.summary.verdict.startsWith("NOT RUN"))
        assertTrue(report.summary.findings.any { it.contains("no receiver is connected") })
        assertTrue(env.appliedProfiles.isEmpty())
        assertEquals(2, exporter.files.size)
        assertEquals(HatTestState.FAILED, runner.progress.value.state)
        assertNotNull(runner.progress.value.error)
    }

    @Test
    fun exportFailureIsReportedWithTheExactReason() = runBlocking {
        val env = FakeEnv()
        val exporter = RecordingExporter(failure = "no space left on device")
        val runner = HatTestRunner(env, testConfig(), VirtualClock(), exporter)

        runner.run()

        assertEquals(HatTestState.FAILED, runner.progress.value.state)
        assertTrue(runner.progress.value.error.orEmpty().contains("no space left on device"))
        // The run itself still produced a report for the user to inspect.
        assertNotNull(runner.progress.value.report)
        assertEquals(HatTestScenario.entries.size - 2, runner.progress.value.report!!.scenarios.size)
    }

    // ---------------------------------------------------------------------------------------------
    // Cancellation / manual checkpoint
    // ---------------------------------------------------------------------------------------------

    @Test
    fun cancellationStopsTheRunAndExportsAPartialReport() = runBlocking {
        val env = FakeEnv()
        val clock = GateClock()
        val exporter = RecordingExporter()
        val runner = HatTestRunner(env, testConfig(), clock, exporter)

        val job = launch { runner.run() }
        awaitCondition { runner.progress.value.state == HatTestState.RUNNING }
        val scenariosStartedBeforeCancel = env.appliedProfiles.size

        runner.cancel()
        clock.release()
        job.join()

        assertEquals(HatTestState.CANCELLED, runner.progress.value.state)
        assertEquals("cancelled", runner.progress.value.error)
        assertTrue(exporter.files.size == 2)
        assertEquals(1, runner.progress.value.report!!.scenarios.size)
        assertEquals(HatTestVerdict.SKIPPED, runner.progress.value.report!!.scenarios.first().verdict)
        // No further profile change may be requested once cancelled.
        assertEquals(scenariosStartedBeforeCancel, env.appliedProfiles.size)
        assertTrue(runner.isCancelled)
    }

    @Test
    fun manualNetworkCheckpointWaitsForTheUserAndThenMeasuresRecovery() = runBlocking {
        val env = FakeEnv()
        val runner = HatTestRunner(
            env,
            testConfig(includeManualNetworkInterrupt = true),
            VirtualClock(),
            RecordingExporter()
        )

        val job = launch { runner.run() }
        awaitCondition { runner.progress.value.awaitingManualContinue }

        assertTrue(runner.progress.value.message.orEmpty().contains("interrupt Wi-Fi"))

        runner.continueManualStep()
        job.join()

        val report = runner.progress.value.report!!
        val interruption = report.scenarios.first { it.scenarioId == HatTestScenario.NETWORK_INTERRUPTION.id }
        assertTrue(interruption.verdict == HatTestVerdict.PASS || interruption.verdict == HatTestVerdict.WARN)
        assertTrue(interruption.durationMs > 0L)
        assertEquals(HatTestState.COMPLETED, runner.progress.value.state)
        assertFalse(runner.progress.value.awaitingManualContinue)
    }

    @Test
    fun receiverReconnectIsMarkedManualRequiredAndNeverFaked() = runBlocking {
        val env = FakeEnv()
        val runner = HatTestRunner(
            env,
            testConfig(includeReceiverReconnect = true),
            VirtualClock(),
            RecordingExporter()
        )

        val report = runner.run()

        val reconnect = report.scenarios.first { it.scenarioId == HatTestScenario.RECEIVER_RECONNECT.id }
        assertEquals(HatTestVerdict.MANUAL_REQUIRED, reconnect.verdict)
        assertTrue(reconnect.reason.contains("cannot be triggered automatically"))
        assertEquals(1, report.summary.manualRequired)
        assertTrue(report.summary.verdict == "PASS WITH WARNINGS")
        assertTrue(report.notes.any { it.contains("requires manual action") })
    }

    @Test
    fun receiverReconnectRunsWhenTheEnvironmentCanRestartTheLocalReceiver() = runBlocking {
        val env = FakeEnv(receiverRestartSupported = true, receiverRestartResult = true)
        val runner = HatTestRunner(
            env,
            testConfig(includeReceiverReconnect = true),
            VirtualClock(),
            RecordingExporter()
        )

        val report = runner.run()

        val reconnect = report.scenarios.first { it.scenarioId == HatTestScenario.RECEIVER_RECONNECT.id }
        assertEquals(1, env.receiverRestarts)
        assertTrue(reconnect.verdict == HatTestVerdict.PASS || reconnect.verdict == HatTestVerdict.WARN)
    }

    // ---------------------------------------------------------------------------------------------
    // Aggregation
    // ---------------------------------------------------------------------------------------------

    @Test
    fun counterAggregationTelescopesDeltasAcrossSamples() = runBlocking {
        val env = FakeEnv()
        env.behavior = { call ->
            mapOf(
                "CAPTURE" to mapOf(
                    "packetsGenerated" to call * 10L,
                    "packetsSent" to call * 10L,
                    "framesCaptured" to call * 100L,
                    "readErrors" to 0L
                )
            )
        }
        val runner = HatTestRunner(env, testConfig(), VirtualClock(), RecordingExporter())

        val report = runner.run()

        // baseline: start sample (call 1) + 4 interval samples + end sample (call 6) => delta of 5 steps.
        val baseline = report.scenarios.first()
        assertEquals(50L, baseline.metrics.packetsGenerated)
        assertEquals(50L, baseline.metrics.packetsSent)
        assertEquals(500L, baseline.metrics.framesCaptured)
        assertEquals(0L, baseline.metrics.captureReadErrors)
    }

    @Test
    fun counterResetsInsideAScenarioNeverProduceNegativeDeltas() = runBlocking {
        val env = FakeEnv()
        env.behavior = { call ->
            val value = if (call <= 2) call * 10L else (call - 2L)
            mapOf("CAPTURE" to mapOf("packetsGenerated" to value))
        }
        val runner = HatTestRunner(env, testConfig(), VirtualClock(), RecordingExporter())

        val report = runner.run()

        // 10 (grow) + reset ignored + 1 + 1 + 1 = 13
        assertEquals(13L, report.scenarios.first().metrics.packetsGenerated)
        assertTrue(report.scenarios.all { it.metrics.packetsGenerated >= 0L })
    }

    @Test
    fun jitterAndBufferGaugesAreSampledWithMinAvgMax() = runBlocking {
        val env = FakeEnv()
        env.behavior = { call ->
            mapOf(
                "JITTER" to mapOf(
                    "jitterMs" to call.toDouble(),
                    "bufferMs" to (call * 2).toDouble(),
                    "targetLatencyMs" to 40.0
                )
            )
        }
        val runner = HatTestRunner(env, testConfig(), VirtualClock(), RecordingExporter())

        val report = runner.run()

        // Samples are the scenario-start section, one per interval and the scenario-end section.
        val baseline = report.scenarios.first()
        assertEquals(1.0, baseline.metrics.minJitterMs!!, 0.0001)
        assertEquals(6.0, baseline.metrics.maxJitterMs!!, 0.0001)
        assertEquals(3.5, baseline.metrics.avgJitterMs!!, 0.0001)
        assertEquals(2.0, baseline.metrics.minBufferMs!!, 0.0001)
        assertEquals(12.0, baseline.metrics.maxBufferMs!!, 0.0001)
        // A constant reading collapses to a single value and a zero swing.
        assertEquals(40.0, baseline.metrics.minTargetLatencyMs!!, 0.0001)
        assertEquals(40.0, baseline.metrics.maxTargetLatencyMs!!, 0.0001)
    }

    // ---------------------------------------------------------------------------------------------
    // Classification
    // ---------------------------------------------------------------------------------------------

    @Test
    fun cleanRunClassifiesEverythingAsPass() = runBlocking {
        val runner = HatTestRunner(FakeEnv(), testConfig(), VirtualClock(), RecordingExporter())

        val report = runner.run()

        assertTrue(report.scenarios.all { it.verdict == HatTestVerdict.PASS })
        assertEquals("PASS", report.summary.verdict)
        assertEquals(6, report.summary.passed)
    }

    @Test
    fun underrunsAndLossClassifyAsWarnNotFail() = runBlocking {
        val env = FakeEnv()
        env.behavior = { call ->
            mapOf(
                "PLAYBACK" to mapOf("underruns" to call.toLong()),
                "RX" to mapOf("lostPackets" to call.toLong(), "latePackets" to call.toLong())
            )
        }
        val runner = HatTestRunner(env, testConfig(), VirtualClock(), RecordingExporter())

        val report = runner.run()

        val baseline = report.scenarios.first()
        assertEquals(HatTestVerdict.WARN, baseline.verdict)
        assertTrue(baseline.reason.contains("underrun"))
        assertTrue(baseline.warnings.any { it.contains("lost/concealed") })
        assertTrue(report.summary.warned > 0)
        assertEquals("PASS WITH WARNINGS", report.summary.verdict)
    }

    @Test
    fun staleGenerationPacketsClassifyAsFail() = runBlocking {
        val env = FakeEnv()
        env.behavior = { call -> mapOf("RX" to mapOf("unknownGeneration" to call.toLong())) }
        val runner = HatTestRunner(env, testConfig(), VirtualClock(), RecordingExporter())

        val report = runner.run()

        val baseline = report.scenarios.first()
        assertEquals(HatTestVerdict.FAIL, baseline.verdict)
        assertTrue(baseline.reason.contains("stale generation"))
        assertEquals("FAIL", report.summary.verdict)
    }

    @Test
    fun playbackStoppingUnexpectedlyClassifiesAsFail() = runBlocking {
        val env = FakeEnv()
        env.behavior = { _ -> mapOf("PLAYBACK" to mapOf("playState" to 2)) }
        val runner = HatTestRunner(env, testConfig(), VirtualClock(), RecordingExporter())

        val report = runner.run()

        val baseline = report.scenarios.first()
        assertEquals(HatTestVerdict.FAIL, baseline.verdict)
        assertTrue(baseline.reason.contains("playback stopped"))
    }

    @Test
    fun configurationFailuresClassifyAsFail() = runBlocking {
        val env = FakeEnv()
        env.namedCounters = mapOf("config_init_failures" to 2L, "config_announce_failures" to 1L)
        val runner = HatTestRunner(env, testConfig(), VirtualClock(), RecordingExporter())

        val report = runner.run()

        val baseline = report.scenarios.first()
        assertEquals(HatTestVerdict.FAIL, baseline.verdict)
        assertTrue(baseline.reason.contains("capture pipeline initialization failed"))
        assertTrue(baseline.metrics.configInitFailures > 0L)
        assertTrue(baseline.metrics.configAnnounceFailures > 0L)
    }

    // ---------------------------------------------------------------------------------------------
    // Serialization
    // ---------------------------------------------------------------------------------------------

    @Test
    fun jsonAndLogExportsContainTheWholeRun() = runBlocking {
        val env = FakeEnv()
        env.snapshotText = "snapshot-body"
        val exporter = RecordingExporter()
        val runner = HatTestRunner(env, testConfig(), VirtualClock(), exporter)

        val report = runner.run()
        val json = report.toJson()
        val log = exporter.files.entries.first { it.key.endsWith(".json") }.value
        val logText = exporter.files.entries.first { it.key.endsWith(".log") }.value

        assertEquals(json, log)
        for (key in listOf("run", "device", "preconditions", "scenarios", "finalSnapshot", "events", "summary")) {
            assertTrue("json is missing $key", json.contains("\"$key\""))
        }
        assertTrue(json.contains("\"testRunId\""))
        assertTrue(json.contains("\"BASELINE\""))
        assertTrue(json.contains("\"packetsSent\""))
        assertTrue(json.contains("snapshot-body"))

        assertTrue(logText.contains("HAT AUTOMATED DIAGNOSTIC TEST"))
        assertTrue(logText.contains("SCENARIO 1 - Baseline"))
        assertTrue(logText.contains("[METRICS]"))
        assertTrue(logText.contains("[GENERATION]"))
        assertTrue(logText.contains("FINAL SUMMARY"))
        assertTrue(logText.contains("END OF HAT AUTOMATED DIAGNOSTIC TEST"))
    }

    @Test
    fun jsonEscapingSurvivesSnapshotsWithQuotesNewlinesAndBackslashes() = runBlocking {
        val env = FakeEnv()
        env.snapshotText = "line1\nline2 \"quoted\" \\ backslash\ttab"
        val runner = HatTestRunner(env, testConfig(), VirtualClock(), RecordingExporter())

        val json = runner.run().toJson()

        assertTrue(json.contains("line1\\nline2 \\\"quoted\\\" \\\\ backslash\\ttab"))
        assertFalse(json.contains("line1\nline2"))
    }

    // ---------------------------------------------------------------------------------------------
    // Independence / concurrency
    // ---------------------------------------------------------------------------------------------

    @Test
    fun twoRunsProduceIndependentReportsAndClearTheRunTag() = runBlocking {
        val env = FakeEnv()
        val first = HatTestRunner(env, testConfig(), VirtualClock(), RecordingExporter()).run()
        assertEquals("", HatDiagnostics.testRunId())

        val second = HatTestRunner(env, testConfig(), VirtualClock(), RecordingExporter()).run()

        assertTrue(first.run.testRunId.isNotEmpty())
        assertTrue(second.run.testRunId.isNotEmpty())
        assertTrue(first.run.testRunId != second.run.testRunId)
        assertEquals(first.summary.total, second.summary.total)
        assertFalse(first.baseName().isEmpty())
    }

    @Test
    fun secondStartIsRejectedWhileARunIsActive() = runBlocking {
        val gate = GateClock()
        val secondGate = GateClock()
        val first = HatTestRunner(FakeEnv(), testConfig(), gate, RecordingExporter())
        val second = HatTestRunner(FakeEnv(), testConfig(), secondGate, RecordingExporter())

        try {
            assertTrue(HatTestController.startWith(first))
            assertFalse(HatTestController.startWith(second))

            HatTestController.cancel()
            gate.release()
            awaitCondition { !HatTestController.isRunning() }

            assertTrue(HatTestController.startWith(second))
        } finally {
            HatTestController.cancel()
            gate.release()
            secondGate.release()
        }
        awaitCondition(timeoutMs = 10_000) { !HatTestController.isRunning() }
    }

    @Test
    fun cancellationApiIsSafeWithNoActiveRun() {
        HatTestController.cancel()
        HatTestController.continueManual()
        assertFalse(HatTestController.isRunning())
    }

    // ---------------------------------------------------------------------------------------------
    // Test doubles
    // ---------------------------------------------------------------------------------------------

    private fun testConfig(
        durationMs: Long = 20L,
        sampleIntervalMs: Long = 5L,
        transitionTimeoutMs: Long = 5_000L,
        includeReceiverReconnect: Boolean = false,
        includeManualNetworkInterrupt: Boolean = false
    ) = HatTestConfig(
        baselineMs = durationMs,
        normalMs = durationMs,
        adaptiveMs = durationMs,
        uncappedMs = durationMs,
        lowLatencyMs = durationMs,
        reconnectMs = durationMs,
        stressPauseMs = durationMs,
        stressTailMs = durationMs,
        manualInterruptMs = durationMs,
        transitionTimeoutMs = transitionTimeoutMs,
        sampleIntervalMs = sampleIntervalMs,
        includeReceiverReconnect = includeReceiverReconnect,
        includeManualNetworkInterrupt = includeManualNetworkInterrupt
    )

    private suspend fun awaitCondition(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(2)
        }
    }

    /** Deterministic clock: every wait advances virtual time instead of sleeping. */
    private class VirtualClock : HatTestClock {
        private var now = 1_000_000L
        override fun nowMs(): Long = now
        override suspend fun wait(ms: Long) {
            now += ms.coerceAtLeast(0L)
        }
    }

    /** Clock that blocks on the first wait until released, so a run can be interrupted deterministically. */
    private class GateClock : HatTestClock {
        private var now = 1_000_000L
        private val gate = CompletableDeferred<Unit>()
        @Volatile private var released = false

        override fun nowMs(): Long = now

        override suspend fun wait(ms: Long) {
            if (!released) gate.await()
            now += ms.coerceAtLeast(0L)
        }

        fun release() {
            released = true
            gate.complete(Unit)
        }
    }

    private class RecordingExporter(private val failure: String? = null) : HatTestExporter {
        val files = LinkedHashMap<String, String>()

        override fun export(fileName: String, mimeType: String, content: String): HatTestExportedFile {
            failure?.let { throw RuntimeException(it) }
            files[fileName] = content
            return HatTestExportedFile(fileName, content.toByteArray(Charsets.UTF_8).size, "content://test/$fileName")
        }
    }

    private class FakeEnv(
        private val initialProfile: String = "BALANCED",
        preconditions: HatTestPreconditions = HatTestPreconditions(true, emptyList(), emptyMap()),
        var applyResult: Boolean = true,
        var autoTransition: Boolean = true,
        var receiverRestartSupported: Boolean = false,
        var receiverRestartResult: Boolean = false
    ) : HatTestEnvironmentAdapter() {

        private val preconditionsResult = preconditions
        private val generation = AtomicLong(5L)

        @Volatile
        private var profileName: String? = initialProfile

        val appliedProfiles = CopyOnWriteArrayList<String>()
        var snapshotCalls = 0
        var generationDropAtCall: Int? = null
        var behavior: ((Int) -> Map<String, Map<String, Any?>>)? = null
        var namedCounters: Map<String, Long> = emptyMap()
        var snapshotText: String = "snapshot"
        var receiverRestarts = 0

        override fun preconditions(): HatTestPreconditions = preconditionsResult
        override fun currentGeneration(): Long = generation.get()
        override fun currentProfileName(): String? = profileName
        override fun connectedReceiverCount(): Int = 1

        override fun applyProfile(target: LatencyTarget): Boolean {
            appliedProfiles += target.name
            if (!applyResult) return false
            if (autoTransition) {
                generation.incrementAndGet()
                profileName = target.name
            }
            return true
        }

        override fun sectionsSnapshot(): Map<String, Map<String, Any?>> {
            snapshotCalls++
            generationDropAtCall?.let { dropAt ->
                if (snapshotCalls >= dropAt) generation.set(1L)
            }
            return behavior?.invoke(snapshotCalls) ?: emptyMap()
        }

        // Scaled by the sample count so the named diagnostics counters grow during a scenario like the real
        // service counters do (a constant value would produce a zero delta).
        override fun counterSnapshot(): Map<String, Long> =
            if (namedCounters.isEmpty()) emptyMap() else namedCounters.mapValues { it.value * snapshotCalls }
        override fun diagnosticsSnapshot(): String = snapshotText
        override fun recentEvents(): List<HatDiagnostics.Event> = emptyList()
        override fun canRestartLocalReceiver(): Boolean = receiverRestartSupported

        override fun restartLocalReceiver(): Boolean {
            receiverRestarts++
            return receiverRestartResult
        }

        override fun isTransmitterRunning(): Boolean = true
        override fun deviceInfo(): HatTestDeviceInfo =
            HatTestDeviceInfo("TestVendor", "TestModel", "product", "device", "board", "14", 34, "fingerprint", "AudioTrack", "UDP")

        override fun appVersion(): String = "1.8.6"
        override fun versionCode(): Int = 59
        override fun buildType(): String = "debug"
        override fun gitRevision(): String? = null
    }
}
