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
 * Unit tests for the Full HAT Test harness (Phase 3.2 Synchronized Diagnostic Test).
 *
 * Verifies all 9 mandatory Phase 3.2 requirements:
 *  1. Receiver handshake (successful join starts scenarios)
 *  2. Missing receiver telemetry / receiver handshake timeout -> NOT_EXECUTED, RECEIVER_NOT_PARTICIPATING, report generated, no PASS
 *  3. Telemetry timeout during test (receiver telemetry stops -> INCOMPLETE)
 *  4. Scenario-local counter deltas (current - start, verify with advancing counters that scenarios don't report cumulative numbers)
 *  5. Unavailable receiver metrics remain null, NEVER 0
 *  6. Scenario sequencing (BASELINE -> RELIABLE -> BALANCED -> LOW_LATENCY -> PROFILE_STRESS)
 *  7. Generation transition validation (monotonicity, receiver ack, labeled latencies)
 *  8. Session cancellation
 *  9. Full report generation (JSON + log, matching Section 13 format, nulls for missing receiver metrics)
 */
class HatTestRunnerTest {

    // ---------------------------------------------------------------------------------------------
    // 1. Receiver Handshake (Successful join starts scenarios)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun receiverHandshakeSucceedsAndRunsScenarios() = runBlocking {
        val env = FakeEnv(receiverParticipating = true)
        val exporter = RecordingExporter()
        val runner = HatTestRunner(env, testConfig(), VirtualClock(), exporter)

        val report = runner.run()

        assertNotNull(env.announcedSessionId)
        assertTrue(env.announcedSessionId!!.startsWith("HAT-"))
        assertEquals("ReceiverModel", report.receiver?.device)
        assertEquals("1.8.6", report.receiver?.appVersion)
        assertEquals("PASS", report.summary.verdict)
        assertEquals(HatTestState.COMPLETED, runner.progress.value.state)
        assertTrue(runner.progress.value.receiverParticipating)
        assertTrue(env.sessionEnded)
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Missing Receiver Telemetry -> NOT_EXECUTED, RECEIVER_NOT_PARTICIPATING
    // ---------------------------------------------------------------------------------------------

    @Test
    fun missingReceiverHandshakeReportsNotExecutedWithReasonReceiverNotParticipating() = runBlocking {
        val env = FakeEnv(receiverParticipating = false)
        val exporter = RecordingExporter()
        val runner = HatTestRunner(env, testConfig(), VirtualClock(), exporter)

        val report = runner.run()

        assertEquals("NOT_EXECUTED", report.summary.verdict)
        assertEquals(HatTestState.NOT_EXECUTED, runner.progress.value.state)
        assertTrue(report.summary.findings.any { it.contains("RECEIVER_NOT_PARTICIPATING") })
        assertTrue(report.errors.any { it.contains("RECEIVER_NOT_PARTICIPATING") })
        assertTrue(report.scenarios.isEmpty())
        assertNull(report.rxMetrics)
        assertFalse(report.summary.verdict == "PASS")

        // Report must still be exported even when not executed
        assertEquals(2, exporter.files.size)
        assertTrue(exporter.files.keys.any { it.endsWith(".json") })
        assertTrue(exporter.files.keys.any { it.endsWith(".log") })

        val json = exporter.files.entries.first { it.key.endsWith(".json") }.value
        assertTrue(json.contains("\"RECEIVER_NOT_PARTICIPATING\""))
        assertTrue(json.contains("\"verdict\": \"NOT_EXECUTED\""))
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Telemetry Timeout During Test -> INCOMPLETE
    // ---------------------------------------------------------------------------------------------

    @Test
    fun telemetryDisappearingDuringScenarioMarksIncomplete() = runBlocking {
        val env = FakeEnv(receiverParticipating = true, telemetryTimeout = true)
        val runner = HatTestRunner(env, testConfig(), VirtualClock(), RecordingExporter())

        val report = runner.run()

        assertEquals("INCOMPLETE", report.summary.verdict)
        assertTrue(report.scenarios.first().verdict == HatTestVerdict.INCOMPLETE)
        assertTrue(report.scenarios.first().reason.contains("receiver telemetry disappeared"))
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Scenario-local Counter Deltas (current - start, never cumulative)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun scenarioMetricsReportStrictDeltasNotCumulativeCounters() = runBlocking {
        val env = FakeEnv(receiverParticipating = true)
        // Configure dynamic increments: every call increments counters
        var txCounter = 1000L
        var rxCounter = 1000L
        var writeCounter = 500L
        env.customTxSupplier = {
            txCounter += 100L
            HatTestTxMetrics(
                packetsGenerated = txCounter,
                packetsSent = txCounter,
                sendErrors = 0L,
                captureReads = 500L,
                captureErrors = 0L,
                framesCaptured = 480000L,
                avgCaptureReadMs = 4.0,
                maxCaptureReadMs = 8.0,
                avgEncodeMs = 1.5,
                maxEncodeMs = 3.0,
                avgSendMs = 0.5,
                maxSendMs = 2.0
            )
        }
        env.customRxSupplier = {
            rxCounter += 80L
            writeCounter += 40L
            HatTestControlMessage.RxStats(
                testSessionId = env.announcedSessionId ?: "test",
                generation = env.generation.get(),
                timestamp = System.currentTimeMillis(),
                packetsReceived = rxCounter,
                packetsLost = 0L,
                packetsLate = 0L,
                packetsOutOfOrder = 0L,
                packetsDuplicate = 0L,
                fecRecovered = 0L,
                decodeErrors = 0L,
                bufferPackets = 4,
                bufferFrames = 384,
                bufferMs = 20.0,
                targetLatencyMs = 35.0,
                jitterMs = 2.0,
                driftPpm = 0.0,
                audioTrackWrites = writeCounter,
                framesWritten = writeCounter * 960L,
                underruns = 0L,
                writeErrors = 0L,
                avgReceiveMs = 1.0,
                maxReceiveMs = 2.0,
                avgDecodeMs = 1.5,
                maxDecodeMs = 3.0,
                avgWriteMs = 0.5,
                maxWriteMs = 1.0,
                playbackHead = 50000L
            )
        }

        val runner = HatTestRunner(env, testConfig(), VirtualClock(), RecordingExporter())
        val report = runner.run()

        assertTrue(report.scenarios.size >= 2)
        val sc1 = report.scenarios[0]
        val sc2 = report.scenarios[1]

        val tx1 = sc1.metrics.packetsSent
        val tx2 = sc2.metrics.packetsSent
        val rx1 = sc1.metrics.packetsReceived
        val rx2 = sc2.metrics.packetsReceived
        assertNotNull(tx1)
        assertNotNull(tx2)
        assertNotNull(rx1)
        assertNotNull(rx2)
        assertTrue(tx1!! in 100L..500L)
        assertTrue(rx1!! in 80L..400L)
        assertTrue(tx2!! in 100L..500L)
        assertTrue(rx2!! in 80L..400L)

        // Neither scenario should report the absolute cumulative counter (> 1000L)
        assertTrue(tx1 < 1000L)
        assertTrue(tx2 < 1000L)
        assertTrue(rx1 < 1000L)
        assertTrue(rx2 < 1000L)
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Unavailable Receiver Metrics Remain null, NEVER 0
    // ---------------------------------------------------------------------------------------------

    @Test
    fun unavailableReceiverMetricsRemainNullNeverZero() = runBlocking {
        val env = FakeEnv(receiverParticipating = false)
        val exporter = RecordingExporter()
        val runner = HatTestRunner(env, testConfig(), VirtualClock(), exporter)

        val report = runner.run()

        assertNull(report.rxMetrics)
        val json = report.toJson()
        assertTrue("JSON must contain rxMetrics as null", json.contains("\"rxMetrics\": null"))
        assertFalse("JSON must not fabricate packetsReceived as 0 when unavailable", json.contains("\"packetsReceived\": 0"))
    }

    // ---------------------------------------------------------------------------------------------
    // 6. Scenario Sequencing (5 Scenarios in documented order)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun scenariosRunInDocumentedOrder() = runBlocking {
        val env = FakeEnv(receiverParticipating = true)
        val runner = HatTestRunner(env, testConfig(), VirtualClock(), RecordingExporter())

        val report = runner.run()

        val expected = listOf(
            HatTestScenario.BASELINE.id,
            HatTestScenario.RELIABLE.id,
            HatTestScenario.BALANCED.id,
            HatTestScenario.LOW_LATENCY.id,
            HatTestScenario.PROFILE_STRESS.id
        )
        assertEquals(expected, report.scenarios.map { it.scenarioId })
        assertEquals(5, report.scenarios.size)
        assertEquals(1, report.scenarios.first().index)
        assertEquals(5, report.scenarios.last().index)
    }

    // ---------------------------------------------------------------------------------------------
    // 7. Generation Transition Validation
    // ---------------------------------------------------------------------------------------------

    @Test
    fun generationTransitionRequiresMonotonicityAndReceiverAckWithLabeledLatencies() = runBlocking {
        val env = FakeEnv(receiverParticipating = true)
        val runner = HatTestRunner(env, testConfig(), VirtualClock(), RecordingExporter())

        val report = runner.run()

        val reliable = report.scenarios.first { it.scenarioId == HatTestScenario.RELIABLE.id }
        assertTrue(reliable.transitionRequired)
        assertTrue(reliable.transitionCompleted)
        assertTrue(reliable.generationMonotonic)
        assertTrue(reliable.endGeneration > reliable.startGeneration)

        assertTrue(reliable.transitions.isNotEmpty())
        val transition = reliable.transitions.first()
        assertTrue(transition.ok)
        assertTrue(transition.generationMonotonic)
        assertTrue(transition.receiverAcked)
        assertNotNull(transition.configToFirstTxMs)
        assertNotNull(transition.firstTxToFirstRxMs)
        assertNotNull(transition.firstRxToFirstDecodeMs)
        assertNotNull(transition.firstDecodeToFirstAudioWriteMs)
    }

    @Test
    fun transitionFailsWhenReceiverDoesNotAcknowledge() = runBlocking {
        val env = FakeEnv(receiverParticipating = true, ackTransitions = false)
        val config = testConfig(transitionTimeoutMs = 100L)
        val runner = HatTestRunner(env, config, VirtualClock(), RecordingExporter())

        val report = runner.run()

        val reliable = report.scenarios.first { it.scenarioId == HatTestScenario.RELIABLE.id }
        assertEquals(HatTestVerdict.FAIL, reliable.verdict)
        assertTrue(reliable.reason.contains("receiver did not acknowledge"))
        assertFalse(reliable.transitionCompleted)
    }

    // ---------------------------------------------------------------------------------------------
    // 8. Session Cancellation
    // ---------------------------------------------------------------------------------------------

    @Test
    fun sessionCancellationHaltsExecutionExportsPartialReport() = runBlocking {
        val gate = GateClock()
        val env = FakeEnv(receiverParticipating = true)
        val exporter = RecordingExporter()
        val runner = HatTestRunner(env, testConfig(), gate, exporter)

        val job = launch {
            runner.run()
        }

        delay(50)
        runner.cancel()
        gate.release()
        job.join()

        assertEquals(HatTestState.CANCELLED, runner.progress.value.state)
        assertEquals(2, exporter.files.size)
        assertTrue(exporter.files.keys.any { it.endsWith(".json") })
        assertTrue(exporter.files.keys.any { it.endsWith(".log") })
        assertTrue(env.sessionEnded)
    }

    // ---------------------------------------------------------------------------------------------
    // 9. Full Report Generation (JSON + Log Export)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun fullReportExportMatchesSection13Schema() = runBlocking {
        val env = FakeEnv(receiverParticipating = true)
        env.snapshotText = "{\"CAPTURE\":{\"recordingState\":3}}"
        val exporter = RecordingExporter()
        val runner = HatTestRunner(env, testConfig(), VirtualClock(), exporter)

        val report = runner.run()
        val json = report.toJson()
        val logText = exporter.files.entries.first { it.key.endsWith(".log") }.value

        for (key in listOf(
            "run", "transmitter", "receiver", "network", "preconditions",
            "scenarios", "transitions", "txMetrics", "rxMetrics", "endToEndMetrics",
            "finalSnapshot", "events", "errors", "summary", "notes"
        )) {
            assertTrue("JSON is missing section '$key'", json.contains("\"$key\""))
        }

        assertTrue(json.contains("\"testSessionId\""))
        assertTrue(json.contains("\"BASELINE\""))
        assertTrue(json.contains("\"packetsSent\""))
        assertTrue(json.contains("\"packetsReceived\""))
        assertTrue(json.contains("\"audioTrackWrites\""))

        assertTrue(logText.contains("HAT AUTOMATED DIAGNOSTIC TEST"))
        assertTrue(logText.contains("SCENARIO 1 - Baseline"))
        assertTrue(logText.contains("[METRICS"))
        assertTrue(logText.contains("FINAL SUMMARY"))
        assertTrue(logText.contains("END OF HAT AUTOMATED DIAGNOSTIC TEST"))
    }

    // ---------------------------------------------------------------------------------------------
    // 10. Non-Fatal AudioRecord.read() Error Handling
    // ---------------------------------------------------------------------------------------------

    @Test
    fun nonFatalCaptureErrorsWarnInsteadOfFailingScenario() = runBlocking {
        val env = FakeEnv(receiverParticipating = true)
        var frames = 480000L
        var reads = 500L
        var packets = 500L
        var captureErrors = 0L
        // Inject capture errors into TxMetrics while frames are captured
        env.customTxSupplier = {
            frames += 48000L
            reads += 50L
            packets += 50L
            captureErrors += 2L
            HatTestTxMetrics(
                packetsGenerated = packets,
                packetsSent = packets,
                sendErrors = 0L,
                captureReads = reads,
                captureErrors = captureErrors,
                framesCaptured = frames,
                avgCaptureReadMs = 4.0,
                maxCaptureReadMs = 8.0,
                avgEncodeMs = 1.5,
                maxEncodeMs = 3.0,
                avgSendMs = 0.5,
                maxSendMs = 2.0
            )
        }

        val runner = HatTestRunner(env, testConfig(), VirtualClock(), RecordingExporter())
        val report = runner.run()

        val baseline = report.scenarios.first()
        assertEquals(HatTestVerdict.WARN, baseline.verdict)
        assertTrue(baseline.warnings.any { it.contains("non-fatal capture read error") })
        assertEquals("WARN", report.summary.verdict)
    }

    // ---------------------------------------------------------------------------------------------
    // Test Doubles
    // ---------------------------------------------------------------------------------------------

    private fun testConfig(
        baselineMs: Long = 20L,
        reliableMs: Long = 20L,
        balancedMs: Long = 20L,
        lowLatencyMs: Long = 20L,
        stressStepMs: Long = 10L,
        stressFinalMs: Long = 20L,
        transitionTimeoutMs: Long = 500L,
        receiverHandshakeTimeoutMs: Long = 200L,
        telemetryTimeoutMs: Long = 200L,
        sampleIntervalMs: Long = 5L
    ) = HatTestConfig(
        baselineMs = baselineMs,
        reliableMs = reliableMs,
        balancedMs = balancedMs,
        lowLatencyMs = lowLatencyMs,
        stressStepMs = stressStepMs,
        stressFinalMs = stressFinalMs,
        transitionTimeoutMs = transitionTimeoutMs,
        receiverHandshakeTimeoutMs = receiverHandshakeTimeoutMs,
        telemetryTimeoutMs = telemetryTimeoutMs,
        sampleIntervalMs = sampleIntervalMs
    )

    private class VirtualClock : HatTestClock {
        private var now = 1_000_000L
        override fun nowMs(): Long = now
        override suspend fun wait(ms: Long) {
            now += ms.coerceAtLeast(0L)
        }
    }

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
        var receiverParticipating: Boolean = true,
        var telemetryTimeout: Boolean = false,
        var ackTransitions: Boolean = true
    ) : HatTestEnvironmentAdapter() {

        private val preconditionsResult = preconditions
        val generation = AtomicLong(5L)

        @Volatile
        var profileName: String? = initialProfile

        val appliedProfiles = CopyOnWriteArrayList<String>()
        var snapshotCalls = 0
        var snapshotText: String = "snapshot"
        var announcedSessionId: String? = null
        var sessionEnded: Boolean = false

        var customTxSupplier: (() -> HatTestTxMetrics)? = null
        var customRxSupplier: (() -> HatTestControlMessage.RxStats)? = null

        val rxPackets = AtomicLong(500L)
        val rxWrites = AtomicLong(250L)
        val rxFrames = AtomicLong(240000L)
        val txPackets = AtomicLong(500L)
        val txFrames = AtomicLong(480000L)
        val txReads = AtomicLong(500L)

        override fun preconditions(): HatTestPreconditions = preconditionsResult
        override fun currentGeneration(): Long = generation.get()
        override fun currentProfileName(): String? = profileName
        override fun connectedReceiverCount(): Int = if (receiverParticipating) 1 else 0

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
            return mapOf(
                "CONFIG" to mapOf("profile" to profileName, "endpoint" to "192.168.1.100:50005"),
                "CAPTURE" to mapOf("recordingState" to 3, "audioRecordState" to 1),
                "PLAYBACK" to mapOf("playState" to 3)
            )
        }

        override fun counterSnapshot(): Map<String, Long> = emptyMap()
        override fun diagnosticsSnapshot(): String = snapshotText
        override fun recentEvents(): List<HatDiagnostics.Event> = emptyList()
        override fun isTransmitterRunning(): Boolean = true
        override fun isReceiverRunning(): Boolean = receiverParticipating
        override fun remoteEndpoint(): String? = if (receiverParticipating) "192.168.1.100:50005" else null

        override fun deviceInfo(): HatTestDeviceInfo =
            HatTestDeviceInfo("TestVendor", "TestModel", "product", "device", "board", "14", 34, "fingerprint", "AudioTrack", "UDP")

        override fun appVersion(): String = "1.8.6"
        override fun versionCode(): Int = 59
        override fun buildType(): String = "debug"
        override fun gitRevision(): String? = null

        override fun announceTestSession(testSessionId: String, generation: Long): Boolean {
            announcedSessionId = testSessionId
            return receiverParticipating
        }

        override suspend fun awaitReceiverJoin(testSessionId: String, timeoutMs: Long): HatTestReceiverInfo? {
            if (!receiverParticipating) return null
            return HatTestReceiverInfo(
                testSessionId = testSessionId,
                device = "ReceiverModel",
                appVersion = "1.8.6",
                generation = generation.get(),
                endpoint = "192.168.1.100:50005"
            )
        }

        override fun latestRxStats(testSessionId: String): HatTestControlMessage.RxStats? {
            if (!receiverParticipating) return null
            val custom = customRxSupplier?.invoke()
            if (custom != null) return custom

            val timestamp = if (telemetryTimeout) 1_000L else System.currentTimeMillis()
            val rP = rxPackets.addAndGet(50L)
            val rW = rxWrites.addAndGet(25L)
            val rF = rxFrames.addAndGet(24000L)
            return HatTestControlMessage.RxStats(
                testSessionId = testSessionId,
                generation = generation.get(),
                timestamp = timestamp,
                packetsReceived = rP,
                packetsLost = 0L,
                packetsLate = 0L,
                packetsOutOfOrder = 0L,
                packetsDuplicate = 0L,
                fecRecovered = 0L,
                decodeErrors = 0L,
                bufferPackets = 4,
                bufferFrames = 384,
                bufferMs = 20.0,
                targetLatencyMs = 35.0,
                jitterMs = 2.0,
                driftPpm = 0.0,
                audioTrackWrites = rW,
                framesWritten = rF,
                underruns = 0L,
                writeErrors = 0L,
                avgReceiveMs = 1.0,
                maxReceiveMs = 2.0,
                avgDecodeMs = 1.5,
                maxDecodeMs = 3.0,
                avgWriteMs = 0.5,
                maxWriteMs = 1.0,
                playbackHead = 50000L
            )
        }

        override suspend fun awaitGenerationAck(testSessionId: String, generation: Long, timeoutMs: Long): HatTestControlMessage.GenerationAck? {
            if (!receiverParticipating || !ackTransitions) return null
            return HatTestControlMessage.GenerationAck(
                testSessionId = testSessionId,
                generation = generation,
                profile = profileName,
                firstRxTimestamp = 1_000_030L,
                firstDecodeTimestamp = 1_000_035L,
                firstAudioWriteTimestamp = 1_000_040L
            )
        }

        override fun endTestSession(testSessionId: String) {
            sessionEnded = true
        }

        override fun latestTxStats(): HatTestTxMetrics {
            val custom = customTxSupplier?.invoke()
            if (custom != null) return custom

            val tP = txPackets.addAndGet(50L)
            val tF = txFrames.addAndGet(48000L)
            val tR = txReads.addAndGet(50L)
            return HatTestTxMetrics(
                packetsGenerated = tP,
                packetsSent = tP,
                sendErrors = 0L,
                captureReads = tR,
                captureErrors = 0L,
                framesCaptured = tF,
                avgCaptureReadMs = 4.0,
                maxCaptureReadMs = 8.0,
                avgEncodeMs = 1.5,
                maxEncodeMs = 3.0,
                avgSendMs = 0.5,
                maxSendMs = 2.0
            )
        }
    }
}
