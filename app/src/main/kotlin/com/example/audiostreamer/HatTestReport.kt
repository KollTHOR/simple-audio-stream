package com.example.audiostreamer

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data model + serializers for the HAT automated diagnostic test harness (Phase 3.2).
 *
 * Everything in this file is pure Kotlin (no Android types reachable on the hot path of serialization) so the
 * report can be unit tested on the JVM without AudioRecord/AudioTrack hardware.
 */

/** Lifecycle of an automated test run, as displayed in Settings → Diagnostics → Full HAT Test. */
enum class HatTestState {
    IDLE, PREPARING, RUNNING, COMPLETING, EXPORTING, COMPLETED, FAILED, CANCELLED, NOT_EXECUTED
}

/**
 * Per-scenario outcome.
 *
 * Requirements:
 *  - PASS: complete TX + RX telemetry and no fatal failures.
 *  - WARN: complete TX + RX telemetry with recoverable errors/underruns/loss.
 *  - FAIL: complete telemetry but actual fatal runtime failure.
 *  - INCOMPLETE: test could not obtain required telemetry (e.g. receiver telemetry disappeared).
 *  - NOT_EXECUTED: preconditions failed or receiver not participating.
 *  - SKIPPED: cancelled before execution.
 */
enum class HatTestVerdict(val label: String) {
    PASS("PASS"),
    WARN("WARN"),
    FAIL("FAIL"),
    INCOMPLETE("INCOMPLETE"),
    NOT_EXECUTED("NOT_EXECUTED"),
    SKIPPED("SKIPPED"),
    MANUAL_REQUIRED("MANUAL_REQUIRED")
}

/**
 * The scenarios executed, in order:
 *  1. BASELINE: 30s current profile
 *  2. RELIABLE: 30s
 *  3. BALANCED: 30s
 *  4. LOW_LATENCY: 30s
 *  5. PROFILE_STRESS: 10s each BALANCED, RELIABLE, BALANCED, LOW_LATENCY, BALANCED; then 30s final BALANCED.
 */
enum class HatTestScenario(
    val id: String,
    val displayName: String,
    /** Target transmitter profile, or null when the scenario does not change the profile. */
    val targetProfile: LatencyTarget?
) {
    BASELINE("BASELINE", "Baseline", null),
    RELIABLE("RELIABLE", "Reliable", LatencyTarget.RELIABLE),
    BALANCED("BALANCED", "Balanced", LatencyTarget.BALANCED),
    LOW_LATENCY("LOW_LATENCY", "Low Latency", LatencyTarget.LOW_LATENCY),
    PROFILE_STRESS("PROFILE_STRESS", "Profile Switch Stress", null);

    companion object {
        val STRESS_SEQUENCE: List<LatencyTarget> = listOf(
            LatencyTarget.BALANCED,
            LatencyTarget.RELIABLE,
            LatencyTarget.BALANCED,
            LatencyTarget.LOW_LATENCY,
            LatencyTarget.BALANCED
        )
    }
}

/** Maps a transport latency target onto the `stream_prefs` profile string the running service reads. */
val LatencyTarget.profilePreference: String
    get() = when (this) {
        LatencyTarget.LOW_LATENCY -> AudioConfig.PROFILE_LOW_LATENCY
        LatencyTarget.BALANCED -> AudioConfig.PROFILE_AUTO
        LatencyTarget.RELIABLE -> AudioConfig.PROFILE_MUSIC
    }

/**
 * Per-scenario aggregate metrics.
 *
 * Rules (Phase 3.2):
 *  - Scenario metrics MUST be deltas across the scenario window, never cumulative global counters.
 *  - Receiver metrics are null when receiver telemetry is unavailable. NEVER use zero for unavailable receiver data.
 */
data class HatTestMetrics(
    val receiverParticipating: Boolean = false,
    val packetsGenerated: Long? = null,
    val packetsSent: Long? = null,
    val bytesSent: Long? = null,
    val sendErrors: Long? = null,
    val captureReads: Long? = null,
    val captureErrors: Long? = null,
    val consecutiveCaptureErrors: Long? = null,
    val framesCaptured: Long? = null,
    val packetsReceived: Long? = null,
    val bytesReceived: Long? = null,
    val packetsLost: Long? = null,
    val packetsLate: Long? = null,
    val packetsOutOfOrder: Long? = null,
    val packetsDuplicate: Long? = null,
    val fecRecovered: Long? = null,
    val decodeErrors: Long? = null,
    val audioTrackWrites: Long? = null,
    val framesWritten: Long? = null,
    val underruns: Long? = null,
    val writeErrors: Long? = null,
    val generationMismatches: Long? = null,
    val codecMismatches: Long? = null,
    val configInitFailures: Long? = null,
    val configAnnounceFailures: Long? = null,
    val configProducerStartFailures: Long? = null,
    val minJitterMs: Double? = null,
    val avgJitterMs: Double? = null,
    val maxJitterMs: Double? = null,
    val minBufferMs: Double? = null,
    val avgBufferMs: Double? = null,
    val maxBufferMs: Double? = null,
    val minTargetLatencyMs: Double? = null,
    val avgTargetLatencyMs: Double? = null,
    val maxTargetLatencyMs: Double? = null,
    val avgDriftPpm: Double? = null,
    val playbackHead: Long? = null,
    val avgCaptureReadMs: Double? = null,
    val maxCaptureReadMs: Double? = null,
    val avgEncodeMs: Double? = null,
    val maxEncodeMs: Double? = null,
    val avgSendMs: Double? = null,
    val maxSendMs: Double? = null,
    val avgReceiveMs: Double? = null,
    val maxReceiveMs: Double? = null,
    val avgDecodeMs: Double? = null,
    val maxDecodeMs: Double? = null,
    val avgWriteMs: Double? = null,
    val maxWriteMs: Double? = null
) {
    val hasReceiverTelemetry: Boolean
        get() = receiverParticipating && packetsReceived != null && audioTrackWrites != null
}

/** Stream configuration observed for a scenario (from the live CONFIG / PLAYBACK snapshot sections). */
data class HatTestConfigSnapshot(
    val profile: String? = null,
    val logicalCodec: String? = null,
    val wireCodec: Int? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val bitDepth: Int? = null,
    val frameSize: Int? = null,
    val packetSize: Int? = null,
    val fecEnabled: Boolean? = null,
    val fecBlockSize: Int? = null,
    val targetLatencyMs: Double? = null,
    val bufferSizeFrames: Int? = null,
    val bufferCapacityFrames: Int? = null,
    val requestedPerformanceMode: Int? = null,
    val actualPerformanceMode: Int? = null
)

/**
 * One generation/profile transition validation and end-to-end timing record (Phase 3.2).
 *
 * Labeled measurements:
 *  - same-device (TX): config → first TX
 *  - cross-device: first TX → first RX (note: clock skew between unsynchronized devices is uncalibrated)
 *  - same-device (RX): first RX → first decode, first decode → first AudioTrack write
 */
data class HatTestTransition(
    val index: Int,
    val oldProfile: String?,
    val newProfile: String,
    val oldGeneration: Long,
    val newGeneration: Long,
    val transitionStart: Long,
    val configCommitted: Long?,
    val configAnnounced: Long?,
    val firstTx: Long?,
    val firstRx: Long?,
    val firstDecode: Long?,
    val firstAudioWrite: Long?,
    val transitionComplete: Long?,
    val durationMs: Long,
    val transitionRequired: Boolean,
    val ok: Boolean,
    val reason: String? = null,
    val generationMonotonic: Boolean = newGeneration > oldGeneration,
    val receiverAcked: Boolean = firstRx != null,
    val configToFirstTxMs: Long? = null,
    val firstTxToFirstRxMs: Long? = null,
    val firstRxToFirstDecodeMs: Long? = null,
    val firstDecodeToFirstAudioWriteMs: Long? = null
)

data class HatTestScenarioResult(
    val index: Int,
    val scenarioId: String,
    val scenarioName: String,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val durationMs: Long,
    val requestedProfile: String?,
    val appliedProfile: String?,
    val startGeneration: Long,
    val endGeneration: Long,
    val generationMonotonic: Boolean,
    val transitionRequired: Boolean,
    val transitionCompleted: Boolean,
    val transitionMs: Long?,
    val verdict: HatTestVerdict,
    val reason: String,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val latencyTargetChanges: List<String> = emptyList(),
    val transitions: List<HatTestTransition> = emptyList(),
    val metrics: HatTestMetrics = HatTestMetrics(),
    val config: HatTestConfigSnapshot = HatTestConfigSnapshot(),
    val snapshotStart: String = "",
    val snapshotEnd: String = ""
)

data class HatTestRunInfo(
    val testRunId: String,
    val testSessionId: String = "",
    val streamRunId: String,
    val streamId: String,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val durationMs: Long,
    val appVersion: String,
    val versionCode: Int,
    val buildType: String,
    val gitRevision: String?
)

data class HatTestDeviceInfo(
    val manufacturer: String?,
    val model: String?,
    val product: String?,
    val device: String?,
    val board: String?,
    val androidVersion: String?,
    val apiLevel: Int?,
    val fingerprint: String?,
    val audioOutputDevice: String?,
    val networkTransport: String?
)

data class HatTestReceiverInfo(
    val testSessionId: String,
    val device: String,
    val appVersion: String,
    val generation: Long,
    val endpoint: String? = null
)

data class HatTestNetworkInfo(
    val transport: String?,
    val endpoint: String?,
    val activeReceivers: Int
)

data class HatTestTxMetrics(
    val packetsGenerated: Long,
    val packetsSent: Long,
    val sendErrors: Long,
    val captureReads: Long,
    val captureErrors: Long,
    val framesCaptured: Long,
    val avgCaptureReadMs: Double?,
    val maxCaptureReadMs: Double?,
    val avgEncodeMs: Double?,
    val maxEncodeMs: Double?,
    val avgSendMs: Double?,
    val maxSendMs: Double?
)

data class HatTestRxMetrics(
    val packetsReceived: Long,
    val packetsLost: Long,
    val packetsLate: Long,
    val packetsOutOfOrder: Long,
    val packetsDuplicate: Long,
    val fecRecovered: Long,
    val decodeErrors: Long,
    val audioTrackWrites: Long,
    val framesWritten: Long,
    val underruns: Long,
    val writeErrors: Long,
    val avgJitterMs: Double?,
    val avgBufferMs: Double?,
    val avgDriftPpm: Double?
)

data class HatTestEndToEndMetrics(
    val transitionsCount: Int,
    val allGenerationsMonotonic: Boolean,
    val allGenerationsAcked: Boolean,
    val avgConfigToFirstTxMs: Double?,
    val avgFirstRxToFirstDecodeMs: Double?,
    val avgFirstDecodeToFirstAudioWriteMs: Double?
)

data class HatTestSummary(
    val total: Int,
    val passed: Int,
    val warned: Int,
    val failed: Int,
    val incomplete: Int,
    val notExecuted: Int,
    val skipped: Int,
    val verdict: String,
    val findings: List<String>
) {
    val manualRequired: Int get() = 0
}

/** A diagnostics event retained for the report (WARN/ERROR + scenario boundaries only, bounded in number). */
data class HatTestEventRecord(
    val timestampMs: Long,
    val severity: String,
    val name: String,
    val generation: Long,
    val testScenario: String?,
    val detail: String
)

/** Precondition evaluation result (never fabricated: the runner refuses to start on a failed check). */
data class HatTestPreconditions(
    val ok: Boolean,
    val reasons: List<String>,
    val details: Map<String, Any?> = emptyMap()
)

/**
 * Top-level report container matching Section 13 JSON structure:
 * run, transmitter, receiver, network, scenarios, transitions, txMetrics, rxMetrics, endToEndMetrics, events, errors, summary.
 */
data class HatTestReport(
    val run: HatTestRunInfo,
    val transmitter: HatTestDeviceInfo,
    val receiver: HatTestReceiverInfo?,
    val network: HatTestNetworkInfo,
    val preconditions: HatTestPreconditions,
    val scenarios: List<HatTestScenarioResult>,
    val transitions: List<HatTestTransition>,
    val txMetrics: HatTestTxMetrics?,
    val rxMetrics: HatTestRxMetrics?,
    val endToEndMetrics: HatTestEndToEndMetrics?,
    val finalSnapshot: String,
    val events: List<HatTestEventRecord>,
    val errors: List<String>,
    val summary: HatTestSummary,
    val notes: List<String> = emptyList()
) {
    /** Alias for backwards compatibility. */
    val device: HatTestDeviceInfo get() = transmitter

    /** `HAT_Test_<timestamp>` — the shared base name for both exported files. */
    fun baseName(): String = "HAT_Test_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(run.startTimestampMs))

    // ---------------------------------------------------------------------------------------------
    // Human readable export
    // ---------------------------------------------------------------------------------------------

    fun toLogText(): String {
        val sb = StringBuilder(16 * 1024)
        val bar = "================================================"
        sb.appendLine(bar)
        sb.appendLine("HAT AUTOMATED DIAGNOSTIC TEST (SYNCHRONIZED E2E)")
        sb.appendLine(bar)
        sb.appendLine()

        sb.appendLine("[RUN]")
        sb.appendLine("testSessionId=${run.testSessionId.ifEmpty { "unavailable" }}")
        sb.appendLine("testRunId=${run.testRunId}")
        sb.appendLine("streamRunId=${run.streamRunId}")
        sb.appendLine("streamId=${run.streamId}")
        sb.appendLine("started=${formatTimestamp(run.startTimestampMs)}")
        sb.appendLine("ended=${formatTimestamp(run.endTimestampMs)}")
        sb.appendLine("durationMs=${run.durationMs}")
        sb.appendLine()

        sb.appendLine("[TRANSMITTER DEVICE]")
        sb.appendLine("manufacturer=${transmitter.manufacturer}")
        sb.appendLine("model=${transmitter.model}")
        sb.appendLine("device=${transmitter.device}")
        sb.appendLine("androidVersion=${transmitter.androidVersion} (API ${transmitter.apiLevel})")
        sb.appendLine("appVersion=${run.appVersion} (${run.versionCode}, ${run.buildType})")
        sb.appendLine()

        sb.appendLine("[RECEIVER DEVICE]")
        if (receiver != null) {
            sb.appendLine("status=PARTICIPATING")
            sb.appendLine("device=${receiver.device}")
            sb.appendLine("appVersion=${receiver.appVersion}")
            sb.appendLine("generation=${receiver.generation}")
            sb.appendLine("endpoint=${receiver.endpoint ?: "unknown"}")
        } else {
            sb.appendLine("status=NOT_PARTICIPATING")
            sb.appendLine("reason=receiver telemetry unavailable or handshake timed out")
        }
        sb.appendLine()

        sb.appendLine("[NETWORK]")
        sb.appendLine("transport=${network.transport ?: "UDP"}")
        sb.appendLine("endpoint=${network.endpoint ?: "unknown"}")
        sb.appendLine("activeReceivers=${network.activeReceivers}")
        sb.appendLine()

        sb.appendLine("[PRECONDITIONS]")
        sb.appendLine("result=${if (preconditions.ok) "PASS" else "FAIL"}")
        if (preconditions.reasons.isEmpty()) {
            sb.appendLine("(all checks passed)")
        } else {
            for (reason in preconditions.reasons) sb.appendLine("- $reason")
        }
        for ((k, v) in preconditions.details) sb.appendLine("$k=$v")
        sb.appendLine()

        if (notes.isNotEmpty()) {
            sb.appendLine("[NOTES]")
            for (note in notes) sb.appendLine("- $note")
            sb.appendLine()
        }

        for (scenario in scenarios) {
            sb.appendLine(bar)
            sb.appendLine("SCENARIO ${scenario.index} - ${scenario.scenarioName}")
            sb.appendLine(bar)
            sb.appendLine("STATUS=${scenario.verdict.label}")
            sb.appendLine("REASON=${scenario.reason}")
            sb.appendLine("DURATION=${scenario.durationMs}ms (${formatTimestamp(scenario.startTimestampMs)} → ${formatTimestamp(scenario.endTimestampMs)})")
            sb.appendLine("RECEIVER_PARTICIPATING=${scenario.metrics.receiverParticipating}")
            sb.appendLine()
            sb.appendLine("[PROFILE]")
            sb.appendLine("requested=${scenario.requestedProfile ?: "unchanged"}")
            sb.appendLine("applied=${scenario.appliedProfile ?: "unknown"}")
            sb.appendLine()
            sb.appendLine("[GENERATION]")
            sb.appendLine("start=${scenario.startGeneration}")
            sb.appendLine("end=${scenario.endGeneration}")
            sb.appendLine("monotonic=${scenario.generationMonotonic}")
            sb.appendLine("transitionRequired=${scenario.transitionRequired}")
            sb.appendLine("transitionCompleted=${scenario.transitionCompleted}")
            sb.appendLine("transitionMs=${scenario.transitionMs ?: "-"}")
            sb.appendLine()
            sb.appendLine("[METRICS (SCENARIO DELTAS)]")
            appendMetrics(sb, scenario.metrics)
            sb.appendLine()
            sb.appendLine("[WARNINGS]")
            if (scenario.warnings.isEmpty()) sb.appendLine("(none)") else scenario.warnings.forEach { sb.appendLine("- $it") }
            sb.appendLine()
            sb.appendLine("[ERRORS]")
            if (scenario.errors.isEmpty()) sb.appendLine("(none)") else scenario.errors.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }

        if (transitions.isNotEmpty()) {
            sb.appendLine(bar)
            sb.appendLine("PROFILE TRANSITIONS VALIDATION & END-TO-END LATENCY")
            sb.appendLine(bar)
            for (t in transitions) {
                sb.appendLine("#${t.index} ${t.oldProfile} → ${t.newProfile} gen ${t.oldGeneration} → ${t.newGeneration}")
                sb.appendLine("  durationMs=${t.durationMs} required=${t.transitionRequired} ok=${t.ok} monotonic=${t.generationMonotonic} acked=${t.receiverAcked}")
                t.configToFirstTxMs?.let { sb.appendLine("  config → first TX: ${it}ms (same-device TX)") }
                t.firstTxToFirstRxMs?.let { sb.appendLine("  first TX → first RX: ${it}ms (cross-device, uncalibrated clock skew)") }
                t.firstRxToFirstDecodeMs?.let { sb.appendLine("  first RX → first decode: ${it}ms (same-device RX)") }
                t.firstDecodeToFirstAudioWriteMs?.let { sb.appendLine("  first decode → first AudioTrack write: ${it}ms (same-device RX)") }
                t.reason?.let { sb.appendLine("  reason=$it") }
            }
            sb.appendLine()
        }

        if (txMetrics != null) {
            sb.appendLine(bar)
            sb.appendLine("TX AGGREGATE METRICS")
            sb.appendLine(bar)
            sb.appendLine("packetsGenerated=${txMetrics.packetsGenerated}")
            sb.appendLine("packetsSent=${txMetrics.packetsSent}")
            sb.appendLine("sendErrors=${txMetrics.sendErrors}")
            sb.appendLine("captureReads=${txMetrics.captureReads}")
            sb.appendLine("captureErrors=${txMetrics.captureErrors}")
            sb.appendLine("framesCaptured=${txMetrics.framesCaptured}")
            sb.appendLine("avgCaptureReadMs=${formatDouble(txMetrics.avgCaptureReadMs)}")
            sb.appendLine("maxCaptureReadMs=${formatDouble(txMetrics.maxCaptureReadMs)}")
            sb.appendLine("avgEncodeMs=${formatDouble(txMetrics.avgEncodeMs)}")
            sb.appendLine("maxEncodeMs=${formatDouble(txMetrics.maxEncodeMs)}")
            sb.appendLine("avgSendMs=${formatDouble(txMetrics.avgSendMs)}")
            sb.appendLine("maxSendMs=${formatDouble(txMetrics.maxSendMs)}")
            sb.appendLine()
        }

        if (rxMetrics != null) {
            sb.appendLine(bar)
            sb.appendLine("RX AGGREGATE METRICS")
            sb.appendLine(bar)
            sb.appendLine("packetsReceived=${rxMetrics.packetsReceived}")
            sb.appendLine("packetsLost=${rxMetrics.packetsLost}")
            sb.appendLine("packetsLate=${rxMetrics.packetsLate}")
            sb.appendLine("packetsOutOfOrder=${rxMetrics.packetsOutOfOrder}")
            sb.appendLine("packetsDuplicate=${rxMetrics.packetsDuplicate}")
            sb.appendLine("fecRecovered=${rxMetrics.fecRecovered}")
            sb.appendLine("decodeErrors=${rxMetrics.decodeErrors}")
            sb.appendLine("audioTrackWrites=${rxMetrics.audioTrackWrites}")
            sb.appendLine("framesWritten=${rxMetrics.framesWritten}")
            sb.appendLine("underruns=${rxMetrics.underruns}")
            sb.appendLine("writeErrors=${rxMetrics.writeErrors}")
            sb.appendLine("avgJitterMs=${formatDouble(rxMetrics.avgJitterMs)}")
            sb.appendLine("avgBufferMs=${formatDouble(rxMetrics.avgBufferMs)}")
            sb.appendLine("avgDriftPpm=${formatDouble(rxMetrics.avgDriftPpm)}")
            sb.appendLine()
        }

        if (events.isNotEmpty()) {
            sb.appendLine(bar)
            sb.appendLine("DIAGNOSTIC EVENTS (WARN/ERROR during run, ${events.size})")
            sb.appendLine(bar)
            for (event in events) {
                sb.appendLine(
                    "${formatTimestamp(event.timestampMs)} [${event.severity}] ${event.name} " +
                        "gen=${event.generation} scenario=${event.testScenario ?: "-"} :: ${event.detail}"
                )
            }
            sb.appendLine()
        }

        if (errors.isNotEmpty()) {
            sb.appendLine(bar)
            sb.appendLine("RUN ERRORS (${errors.size})")
            sb.appendLine(bar)
            for (err in errors) sb.appendLine("- $err")
            sb.appendLine()
        }

        sb.appendLine(bar)
        sb.appendLine("FINAL SUMMARY")
        sb.appendLine(bar)
        sb.appendLine("total=${summary.total} passed=${summary.passed} warned=${summary.warned} failed=${summary.failed} " +
            "incomplete=${summary.incomplete} notExecuted=${summary.notExecuted} skipped=${summary.skipped}")
        sb.appendLine("verdict=${summary.verdict}")
        if (summary.findings.isEmpty()) {
            sb.appendLine("(no findings)")
        } else {
            for (finding in summary.findings) sb.appendLine("- $finding")
        }
        sb.appendLine()
        sb.appendLine("[FINAL DIAGNOSTIC SNAPSHOT]")
        sb.appendLine(finalSnapshot)
        sb.appendLine(bar)
        sb.appendLine("END OF HAT AUTOMATED DIAGNOSTIC TEST")
        sb.appendLine(bar)
        return sb.toString()
    }

    private fun appendMetrics(sb: StringBuilder, m: HatTestMetrics) {
        sb.appendLine("receiverParticipating=${m.receiverParticipating}")
        sb.appendLine("packetsGenerated=${m.packetsGenerated ?: "-"}")
        sb.appendLine("packetsSent=${m.packetsSent ?: "-"}")
        sb.appendLine("bytesSent=${m.bytesSent ?: "-"}")
        sb.appendLine("sendErrors=${m.sendErrors ?: "-"}")
        sb.appendLine("captureReads=${m.captureReads ?: "-"}")
        sb.appendLine("captureErrors=${m.captureErrors ?: "-"}")
        sb.appendLine("framesCaptured=${m.framesCaptured ?: "-"}")
        sb.appendLine("packetsReceived=${m.packetsReceived ?: "(unavailable - receiver not participating)"}")
        sb.appendLine("bytesReceived=${m.bytesReceived ?: "-"}")
        sb.appendLine("packetsLost=${m.packetsLost ?: "-"}")
        sb.appendLine("packetsLate=${m.packetsLate ?: "-"}")
        sb.appendLine("packetsOutOfOrder=${m.packetsOutOfOrder ?: "-"}")
        sb.appendLine("packetsDuplicate=${m.packetsDuplicate ?: "-"}")
        sb.appendLine("fecRecovered=${m.fecRecovered ?: "-"}")
        sb.appendLine("decodeErrors=${m.decodeErrors ?: "-"}")
        sb.appendLine("audioTrackWrites=${m.audioTrackWrites ?: "(unavailable - receiver not participating)"}")
        sb.appendLine("framesWritten=${m.framesWritten ?: "-"}")
        sb.appendLine("underruns=${m.underruns ?: "-"}")
        sb.appendLine("writeErrors=${m.writeErrors ?: "-"}")
        sb.appendLine("minJitterMs=${formatDouble(m.minJitterMs)}")
        sb.appendLine("avgJitterMs=${formatDouble(m.avgJitterMs)}")
        sb.appendLine("maxJitterMs=${formatDouble(m.maxJitterMs)}")
        sb.appendLine("minBufferMs=${formatDouble(m.minBufferMs)}")
        sb.appendLine("avgBufferMs=${formatDouble(m.avgBufferMs)}")
        sb.appendLine("maxBufferMs=${formatDouble(m.maxBufferMs)}")
        sb.appendLine("minTargetLatencyMs=${formatDouble(m.minTargetLatencyMs)}")
        sb.appendLine("avgTargetLatencyMs=${formatDouble(m.avgTargetLatencyMs)}")
        sb.appendLine("maxTargetLatencyMs=${formatDouble(m.maxTargetLatencyMs)}")
        sb.appendLine("avgDriftPpm=${formatDouble(m.avgDriftPpm)}")
        sb.appendLine("playbackHead=${m.playbackHead ?: "-"}")
        sb.appendLine("avgCaptureReadMs=${formatDouble(m.avgCaptureReadMs)}")
        sb.appendLine("maxCaptureReadMs=${formatDouble(m.maxCaptureReadMs)}")
        sb.appendLine("avgEncodeMs=${formatDouble(m.avgEncodeMs)}")
        sb.appendLine("maxEncodeMs=${formatDouble(m.maxEncodeMs)}")
        sb.appendLine("avgSendMs=${formatDouble(m.avgSendMs)}")
        sb.appendLine("maxSendMs=${formatDouble(m.maxSendMs)}")
        sb.appendLine("avgReceiveMs=${formatDouble(m.avgReceiveMs)}")
        sb.appendLine("maxReceiveMs=${formatDouble(m.maxReceiveMs)}")
        sb.appendLine("avgDecodeMs=${formatDouble(m.avgDecodeMs)}")
        sb.appendLine("maxDecodeMs=${formatDouble(m.maxDecodeMs)}")
        sb.appendLine("avgWriteMs=${formatDouble(m.avgWriteMs)}")
        sb.appendLine("maxWriteMs=${formatDouble(m.maxWriteMs)}")
    }

    // ---------------------------------------------------------------------------------------------
    // Machine readable export (Section 13 JSON structure)
    // ---------------------------------------------------------------------------------------------

    fun toJson(): String = HatTestJson.compose(
        "run" to HatTestJson.rawObj(
            "testSessionId" to run.testSessionId,
            "testRunId" to run.testRunId,
            "streamRunId" to run.streamRunId,
            "streamId" to run.streamId,
            "startTimestampMs" to run.startTimestampMs,
            "endTimestampMs" to run.endTimestampMs,
            "durationMs" to run.durationMs,
            "appVersion" to run.appVersion,
            "versionCode" to run.versionCode,
            "buildType" to run.buildType,
            "gitRevision" to run.gitRevision
        ),
        "transmitter" to HatTestJson.rawObj(
            "manufacturer" to transmitter.manufacturer,
            "model" to transmitter.model,
            "product" to transmitter.product,
            "device" to transmitter.device,
            "board" to transmitter.board,
            "androidVersion" to transmitter.androidVersion,
            "apiLevel" to transmitter.apiLevel,
            "fingerprint" to transmitter.fingerprint,
            "audioOutputDevice" to transmitter.audioOutputDevice,
            "networkTransport" to transmitter.networkTransport
        ),
        "receiver" to receiver?.let { rx ->
            HatTestJson.rawObj(
                "participating" to true,
                "testSessionId" to rx.testSessionId,
                "device" to rx.device,
                "appVersion" to rx.appVersion,
                "generation" to rx.generation,
                "endpoint" to rx.endpoint
            )
        },
        "network" to HatTestJson.rawObj(
            "transport" to network.transport,
            "endpoint" to network.endpoint,
            "activeReceivers" to network.activeReceivers
        ),
        "preconditions" to HatTestJson.rawObj(
            "ok" to preconditions.ok,
            "reasons" to preconditions.reasons,
            "details" to preconditions.details
        ),
        "scenarios" to scenarios.map { it.toJsonMap() },
        "transitions" to transitions.map { t ->
            HatTestJson.rawObj(
                "index" to t.index,
                "oldProfile" to t.oldProfile,
                "newProfile" to t.newProfile,
                "oldGeneration" to t.oldGeneration,
                "newGeneration" to t.newGeneration,
                "transitionStart" to t.transitionStart,
                "configCommitted" to t.configCommitted,
                "configAnnounced" to t.configAnnounced,
                "firstTx" to t.firstTx,
                "firstRx" to t.firstRx,
                "firstDecode" to t.firstDecode,
                "firstAudioWrite" to t.firstAudioWrite,
                "transitionComplete" to t.transitionComplete,
                "durationMs" to t.durationMs,
                "transitionRequired" to t.transitionRequired,
                "ok" to t.ok,
                "generationMonotonic" to t.generationMonotonic,
                "receiverAcked" to t.receiverAcked,
                "configToFirstTxMs" to t.configToFirstTxMs,
                "firstTxToFirstRxMs" to t.firstTxToFirstRxMs,
                "firstRxToFirstDecodeMs" to t.firstRxToFirstDecodeMs,
                "firstDecodeToFirstAudioWriteMs" to t.firstDecodeToFirstAudioWriteMs,
                "reason" to t.reason
            )
        },
        "txMetrics" to txMetrics?.let { tx ->
            HatTestJson.rawObj(
                "packetsGenerated" to tx.packetsGenerated,
                "packetsSent" to tx.packetsSent,
                "sendErrors" to tx.sendErrors,
                "captureReads" to tx.captureReads,
                "captureErrors" to tx.captureErrors,
                "framesCaptured" to tx.framesCaptured,
                "avgCaptureReadMs" to tx.avgCaptureReadMs,
                "maxCaptureReadMs" to tx.maxCaptureReadMs,
                "avgEncodeMs" to tx.avgEncodeMs,
                "maxEncodeMs" to tx.maxEncodeMs,
                "avgSendMs" to tx.avgSendMs,
                "maxSendMs" to tx.maxSendMs
            )
        },
        "rxMetrics" to rxMetrics?.let { rx ->
            HatTestJson.rawObj(
                "packetsReceived" to rx.packetsReceived,
                "packetsLost" to rx.packetsLost,
                "packetsLate" to rx.packetsLate,
                "packetsOutOfOrder" to rx.packetsOutOfOrder,
                "packetsDuplicate" to rx.packetsDuplicate,
                "fecRecovered" to rx.fecRecovered,
                "decodeErrors" to rx.decodeErrors,
                "audioTrackWrites" to rx.audioTrackWrites,
                "framesWritten" to rx.framesWritten,
                "underruns" to rx.underruns,
                "writeErrors" to rx.writeErrors,
                "avgJitterMs" to rx.avgJitterMs,
                "avgBufferMs" to rx.avgBufferMs,
                "avgDriftPpm" to rx.avgDriftPpm
            )
        },
        "endToEndMetrics" to endToEndMetrics?.let { e2e ->
            HatTestJson.rawObj(
                "transitionsCount" to e2e.transitionsCount,
                "allGenerationsMonotonic" to e2e.allGenerationsMonotonic,
                "allGenerationsAcked" to e2e.allGenerationsAcked,
                "avgConfigToFirstTxMs" to e2e.avgConfigToFirstTxMs,
                "avgFirstRxToFirstDecodeMs" to e2e.avgFirstRxToFirstDecodeMs,
                "avgFirstDecodeToFirstAudioWriteMs" to e2e.avgFirstDecodeToFirstAudioWriteMs
            )
        },
        "finalSnapshot" to finalSnapshot,
        "events" to events.map { event ->
            HatTestJson.rawObj(
                "timestampMs" to event.timestampMs,
                "severity" to event.severity,
                "name" to event.name,
                "generation" to event.generation,
                "scenario" to event.testScenario,
                "detail" to event.detail
            )
        },
        "errors" to errors,
        "summary" to HatTestJson.rawObj(
            "total" to summary.total,
            "passed" to summary.passed,
            "warned" to summary.warned,
            "failed" to summary.failed,
            "incomplete" to summary.incomplete,
            "notExecuted" to summary.notExecuted,
            "skipped" to summary.skipped,
            "verdict" to summary.verdict,
            "findings" to summary.findings
        ),
        "notes" to notes
    )

    private fun HatTestScenarioResult.toJsonMap(): HatTestJson.Raw = HatTestJson.rawObj(
        "scenarioId" to scenarioId,
        "scenarioName" to scenarioName,
        "index" to index,
        "startTimestampMs" to startTimestampMs,
        "endTimestampMs" to endTimestampMs,
        "durationMs" to durationMs,
        "requestedProfile" to requestedProfile,
        "appliedProfile" to appliedProfile,
        "startGeneration" to startGeneration,
        "endGeneration" to endGeneration,
        "generationMonotonic" to generationMonotonic,
        "transitionRequired" to transitionRequired,
        "transitionCompleted" to transitionCompleted,
        "transitionMs" to transitionMs,
        "status" to verdict.label,
        "reason" to reason,
        "receiverParticipating" to metrics.receiverParticipating,
        "packetsGenerated" to metrics.packetsGenerated,
        "packetsSent" to metrics.packetsSent,
        "packetsReceived" to metrics.packetsReceived,
        "packetLoss" to metrics.packetsLost,
        "fecRecovered" to metrics.fecRecovered,
        "decodeErrors" to metrics.decodeErrors,
        "audioTrackWrites" to metrics.audioTrackWrites,
        "framesWritten" to metrics.framesWritten,
        "underruns" to metrics.underruns,
        "captureErrors" to metrics.captureErrors,
        "sendErrors" to metrics.sendErrors,
        "jitter" to metrics.avgJitterMs?.let {
            HatTestJson.rawObj("min" to metrics.minJitterMs, "avg" to metrics.avgJitterMs, "max" to metrics.maxJitterMs)
        },
        "buffer" to metrics.avgBufferMs?.let {
            HatTestJson.rawObj("min" to metrics.minBufferMs, "avg" to metrics.avgBufferMs, "max" to metrics.maxBufferMs)
        },
        "latency" to metrics.avgTargetLatencyMs?.let {
            HatTestJson.rawObj("min" to metrics.minTargetLatencyMs, "avg" to metrics.avgTargetLatencyMs, "max" to metrics.maxTargetLatencyMs)
        },
        "timing" to HatTestJson.rawObj(
            "captureRead" to metrics.avgCaptureReadMs?.let { HatTestJson.rawObj("avgMs" to it, "maxMs" to metrics.maxCaptureReadMs) },
            "encode" to metrics.avgEncodeMs?.let { HatTestJson.rawObj("avgMs" to it, "maxMs" to metrics.maxEncodeMs) },
            "send" to metrics.avgSendMs?.let { HatTestJson.rawObj("avgMs" to it, "maxMs" to metrics.maxSendMs) },
            "receive" to metrics.avgReceiveMs?.let { HatTestJson.rawObj("avgMs" to it, "maxMs" to metrics.maxReceiveMs) },
            "decode" to metrics.avgDecodeMs?.let { HatTestJson.rawObj("avgMs" to it, "maxMs" to metrics.maxDecodeMs) },
            "audioTrackWrite" to metrics.avgWriteMs?.let { HatTestJson.rawObj("avgMs" to it, "maxMs" to metrics.maxWriteMs) }
        ),
        "config" to HatTestJson.rawObj(
            "profile" to config.profile,
            "logicalCodec" to config.logicalCodec,
            "wireCodec" to config.wireCodec,
            "sampleRate" to config.sampleRate,
            "channels" to config.channels,
            "bitDepth" to config.bitDepth,
            "frameSize" to config.frameSize,
            "packetSize" to config.packetSize,
            "fecEnabled" to config.fecEnabled,
            "fecBlockSize" to config.fecBlockSize,
            "targetLatencyMs" to config.targetLatencyMs,
            "bufferSizeFrames" to config.bufferSizeFrames,
            "bufferCapacityFrames" to config.bufferCapacityFrames,
            "requestedPerformanceMode" to config.requestedPerformanceMode,
            "actualPerformanceMode" to config.actualPerformanceMode
        ),
        "warnings" to warnings,
        "errors" to errors,
        "latencyTargetChanges" to latencyTargetChanges,
        "transitions" to transitions.map { t ->
            HatTestJson.rawObj(
                "index" to t.index,
                "oldProfile" to t.oldProfile,
                "newProfile" to t.newProfile,
                "oldGeneration" to t.oldGeneration,
                "newGeneration" to t.newGeneration,
                "durationMs" to t.durationMs,
                "transitionRequired" to t.transitionRequired,
                "ok" to t.ok,
                "reason" to t.reason
            )
        },
        "snapshotStart" to snapshotStart,
        "snapshotEnd" to snapshotEnd
    )
}

// -------------------------------------------------------------------------------------------------
// Helpers
// -------------------------------------------------------------------------------------------------

internal fun formatTimestamp(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms))

internal fun formatDouble(value: Double?): String =
    if (value == null) "-" else String.format(Locale.US, "%.2f", value)

/**
 * Minimal, allocation-conscious JSON writer. Only needs to serialize the bounded structures above, and it
 * keeps the report testable on a plain JVM where org.json is not usable.
 */
object HatTestJson {

    /**
     * Marker for an already-serialized JSON fragment. Without it a nested object would be serialized as an
     * escaped string instead of a nested object.
     */
    class Raw(val text: String)

    /** Builds a nested JSON object that keeps nesting when used as a value. */
    fun rawObj(vararg pairs: Pair<String, Any?>): Raw = Raw(obj(*pairs))

    /** Top-level entry point: returns the serialized document as text. */
    fun compose(vararg pairs: Pair<String, Any?>): String = obj(*pairs)

    fun obj(vararg pairs: Pair<String, Any?>): String {
        val sb = StringBuilder(64)
        sb.append('{')
        var first = true
        for ((key, value) in pairs) {
            if (!first) sb.append(", ")
            first = false
            sb.append('"').append(escape(key)).append("\": ").append(value(value))
        }
        sb.append('}')
        return sb.toString()
    }

    fun objOf(map: Map<String, Any?>): String {
        val sb = StringBuilder(64)
        sb.append('{')
        var first = true
        for ((key, value) in map) {
            if (!first) sb.append(", ")
            first = false
            sb.append('"').append(escape(key)).append("\": ").append(value(value))
        }
        sb.append('}')
        return sb.toString()
    }

    fun arr(values: Iterable<*>): String {
        val sb = StringBuilder(32)
        sb.append('[')
        var first = true
        for (v in values) {
            if (!first) sb.append(", ")
            first = false
            sb.append(value(v))
        }
        sb.append(']')
        return sb.toString()
    }

    fun value(v: Any?): String = when (v) {
        null -> "null"
        is Raw -> v.text
        is String -> "\"" + escape(v) + "\""
        is Boolean -> if (v) "true" else "false"
        is Int, is Long, is Short, is Byte -> v.toString()
        is Double -> if (v.isFinite()) v.toString() else "null"
        is Float -> if (v.isFinite()) v.toString() else "null"
        is Map<*, *> -> objOf(v.entries.associate { it.key.toString() to it.value })
        is Iterable<*> -> arr(v)
        is Array<*> -> arr(v.toList())
        else -> "\"" + escape(v.toString()) + "\""
    }

    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append(String.format(Locale.US, "\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        return sb.toString()
    }
}
