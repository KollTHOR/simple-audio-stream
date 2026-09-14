package com.example.audiostreamer

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data model + serializers for the HAT automated diagnostic test harness (Phase 3.1).
 *
 * Everything in this file is pure Kotlin (no Android types reachable on the hot path of serialization) so the
 * report can be unit tested on the JVM without AudioRecord/AudioTrack hardware. JSON is written by a tiny
 * local writer instead of org.json because org.json is only available through the (mocked) Android runtime in
 * local unit tests.
 */

/** Lifecycle of an automated test run, as displayed in Settings → Diagnostics → Full HAT Test. */
enum class HatTestState {
    IDLE, PREPARING, RUNNING, COMPLETING, EXPORTING, COMPLETED, FAILED, CANCELLED
}

/** Per-scenario outcome. `MANUAL_REQUIRED` is an honest "this device/build cannot do it automatically". */
enum class HatTestVerdict(val label: String) {
    PASS("PASS"),
    WARN("WARN"),
    FAIL("FAIL"),
    MANUAL_REQUIRED("MANUAL_REQUIRED"),
    SKIPPED("SKIPPED")
}

/**
 * The scenarios executed, in order.
 *
 * NOTE on profile naming: this build exposes three streaming modes — Auto Adaptive, Low Latency (Opus) and
 * Music (uncompressed/uncapped lossless). The phase spec names four profile scenarios, so:
 *  - "Normal"   and "Uncapped" both resolve to the app's Music / uncompressed lossless mode ([LatencyTarget.RELIABLE]),
 *    which the app itself labels "Uncapped Music Mode". They are still recorded as distinct scenarios, and the
 *    report always states the requested *and* the applied profile so no transition is ever fabricated.
 *  - "Adaptive"  resolves to Auto Adaptive ([LatencyTarget.BALANCED]).
 *  - "Low Latency" resolves to the Opus low-latency profile ([LatencyTarget.LOW_LATENCY]).
 */
enum class HatTestScenario(
    val id: String,
    val displayName: String,
    /** Target transmitter profile, or null when the scenario does not change the profile. */
    val targetProfile: LatencyTarget?
) {
    BASELINE("BASELINE", "Baseline", null),
    NORMAL("NORMAL", "Normal", LatencyTarget.RELIABLE),
    ADAPTIVE("ADAPTIVE", "Adaptive", LatencyTarget.BALANCED),
    UNCAPPED("UNCAPPED", "Uncapped", LatencyTarget.RELIABLE),
    LOW_LATENCY("LOW_LATENCY", "Low Latency", LatencyTarget.LOW_LATENCY),
    PROFILE_STRESS("PROFILE_STRESS", "Profile Switch Stress", null),
    RECEIVER_RECONNECT("RECEIVER_RECONNECT", "Receiver Reconnect", null),
    NETWORK_INTERRUPTION("NETWORK_INTERRUPTION", "Network Interruption (Manual)", null);

    companion object {
        /** Normal → Adaptive → Low Latency → Uncapped → Adaptive → Normal, expressed as wire profiles. */
        val STRESS_SEQUENCE: List<LatencyTarget> = listOf(
            LatencyTarget.RELIABLE,
            LatencyTarget.BALANCED,
            LatencyTarget.LOW_LATENCY,
            LatencyTarget.RELIABLE,
            LatencyTarget.BALANCED,
            LatencyTarget.RELIABLE
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
 * Per-scenario aggregate metrics. Counters are deltas across the scenario window; the jitter/buffer/latency
 * values are sampled once per statistics interval during the scenario (they are instantaneous readings, not
 * counters). Null means "not observable on this device" (e.g. receiver-side metrics on a transmitter).
 */
data class HatTestMetrics(
    val packetsGenerated: Long = 0L,
    val packetsSent: Long = 0L,
    val bytesSent: Long = 0L,
    val sendErrors: Long = 0L,
    val packetsReceived: Long = 0L,
    val bytesReceived: Long = 0L,
    val packetsLost: Long = 0L,
    val packetsLate: Long = 0L,
    val packetsOutOfOrder: Long = 0L,
    val packetsDuplicate: Long = 0L,
    val fecRecovered: Long = 0L,
    val decodeErrors: Long = 0L,
    val writeErrors: Long = 0L,
    val underruns: Long = 0L,
    val captureReadErrors: Long = 0L,
    val framesCaptured: Long = 0L,
    val generationMismatches: Long = 0L,
    val codecMismatches: Long = 0L,
    val configInitFailures: Long = 0L,
    val configAnnounceFailures: Long = 0L,
    val configProducerStartFailures: Long = 0L,
    val minJitterMs: Double? = null,
    val avgJitterMs: Double? = null,
    val maxJitterMs: Double? = null,
    val minBufferMs: Double? = null,
    val avgBufferMs: Double? = null,
    val maxBufferMs: Double? = null,
    val minTargetLatencyMs: Double? = null,
    val avgTargetLatencyMs: Double? = null,
    val maxTargetLatencyMs: Double? = null,
    val avgCaptureReadMs: Double? = null,
    val maxCaptureReadMs: Double? = null,
    val avgDecodeMs: Double? = null,
    val maxDecodeMs: Double? = null,
    val avgWriteMs: Double? = null,
    val maxWriteMs: Double? = null,
    val avgSendMs: Double? = null,
    val maxSendMs: Double? = null,
    val avgEncodeMs: Double? = null,
    val maxEncodeMs: Double? = null,
    val avgReceiveMs: Double? = null,
    val maxReceiveMs: Double? = null
) {
    val hasReceiverTelemetry: Boolean get() = packetsReceived > 0L || decodeErrors > 0L || packetsLost > 0L
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

/** One generation/profile transition, used by the profile-switch stress scenario. */
data class HatTestTransition(
    val index: Int,
    val fromProfile: String?,
    val toProfile: String,
    val fromGeneration: Long,
    val toGeneration: Long,
    val durationMs: Long,
    val transitionRequired: Boolean,
    val ok: Boolean,
    val reason: String? = null
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

data class HatTestSummary(
    val total: Int,
    val passed: Int,
    val warned: Int,
    val failed: Int,
    val skipped: Int,
    val manualRequired: Int,
    val verdict: String,
    val findings: List<String>
)

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

data class HatTestReport(
    val run: HatTestRunInfo,
    val device: HatTestDeviceInfo,
    val preconditions: HatTestPreconditions,
    val scenarios: List<HatTestScenarioResult>,
    val finalSnapshot: String,
    val events: List<HatTestEventRecord>,
    val summary: HatTestSummary,
    val notes: List<String> = emptyList()
) {

    /** `HAT_Test_<timestamp>` — the shared base name for both exported files. */
    fun baseName(): String = "HAT_Test_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(run.startTimestampMs))

    // ---------------------------------------------------------------------------------------------
    // Human readable export
    // ---------------------------------------------------------------------------------------------

    fun toLogText(): String {
        val sb = StringBuilder(16 * 1024)
        val bar = "================================================"
        sb.appendLine(bar)
        sb.appendLine("HAT AUTOMATED DIAGNOSTIC TEST")
        sb.appendLine(bar)
        sb.appendLine()

        sb.appendLine("[RUN]")
        sb.appendLine("testRunId=${run.testRunId}")
        sb.appendLine("streamRunId=${run.streamRunId}")
        sb.appendLine("streamId=${run.streamId}")
        sb.appendLine("started=${formatTimestamp(run.startTimestampMs)}")
        sb.appendLine("ended=${formatTimestamp(run.endTimestampMs)}")
        sb.appendLine("durationMs=${run.durationMs}")
        sb.appendLine()

        sb.appendLine("[DEVICE]")
        sb.appendLine("manufacturer=${device.manufacturer}")
        sb.appendLine("model=${device.model}")
        sb.appendLine("product=${device.product}")
        sb.appendLine("device=${device.device}")
        sb.appendLine("board=${device.board}")
        sb.appendLine()

        sb.appendLine("[ANDROID]")
        sb.appendLine("version=${device.androidVersion}")
        sb.appendLine("apiLevel=${device.apiLevel}")
        sb.appendLine("fingerprint=${device.fingerprint}")
        sb.appendLine("audioOutputDevice=${device.audioOutputDevice}")
        sb.appendLine("networkTransport=${device.networkTransport}")
        sb.appendLine()

        sb.appendLine("[APP VERSION]")
        sb.appendLine("versionName=${run.appVersion} (code ${run.versionCode})")
        sb.appendLine("buildType=${run.buildType}")
        sb.appendLine()

        sb.appendLine("[GIT REVISION]")
        sb.appendLine(run.gitRevision ?: "unavailable")
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
            for (t in scenario.transitions) {
                sb.appendLine(
                    "  #${t.index} ${t.fromProfile} → ${t.toProfile} gen ${t.fromGeneration}→${t.toGeneration} " +
                        "durationMs=${t.durationMs} required=${t.transitionRequired} ok=${t.ok}" +
                        (t.reason?.let { " reason=$it" } ?: "")
                )
            }
            sb.appendLine()
            sb.appendLine("[CONFIG]")
            sb.appendLine("profile=${scenario.config.profile}")
            sb.appendLine("logicalCodec=${scenario.config.logicalCodec}")
            sb.appendLine("wireCodec=${scenario.config.wireCodec}")
            sb.appendLine("sampleRate=${scenario.config.sampleRate}")
            sb.appendLine("channels=${scenario.config.channels}")
            sb.appendLine("bitDepth=${scenario.config.bitDepth}")
            sb.appendLine("frameSize=${scenario.config.frameSize}")
            sb.appendLine("packetSize=${scenario.config.packetSize}")
            sb.appendLine("fecEnabled=${scenario.config.fecEnabled}")
            sb.appendLine("fecBlockSize=${scenario.config.fecBlockSize}")
            sb.appendLine("targetLatencyMs=${formatDouble(scenario.config.targetLatencyMs)}")
            sb.appendLine("requestedPerformanceMode=${scenario.config.requestedPerformanceMode}")
            sb.appendLine("actualPerformanceMode=${scenario.config.actualPerformanceMode}")
            sb.appendLine()
            sb.appendLine("[METRICS]")
            appendMetrics(sb, scenario.metrics)
            sb.appendLine()
            sb.appendLine("[WARNINGS]")
            if (scenario.warnings.isEmpty()) sb.appendLine("(none)") else scenario.warnings.forEach { sb.appendLine("- $it") }
            sb.appendLine()
            sb.appendLine("[ERRORS]")
            if (scenario.errors.isEmpty()) sb.appendLine("(none)") else scenario.errors.forEach { sb.appendLine("- $it") }
            sb.appendLine()
            if (scenario.latencyTargetChanges.isNotEmpty()) {
                sb.appendLine("[LATENCY TARGET CHANGES] (${scenario.latencyTargetChanges.size})")
                scenario.latencyTargetChanges.forEach { sb.appendLine("- $it") }
                sb.appendLine()
            }
            sb.appendLine("[SNAPSHOT AT SCENARIO START]")
            sb.appendLine(scenario.snapshotStart.ifBlank { "(none)" })
            sb.appendLine()
            sb.appendLine("[SNAPSHOT AT SCENARIO END]")
            sb.appendLine(scenario.snapshotEnd.ifBlank { "(none)" })
            sb.appendLine()
        }

        if (events.isNotEmpty()) {
            sb.appendLine(bar)
            sb.appendLine("DIAGNOSTIC EVENTS (WARN/ERROR during the run, ${events.size})")
            sb.appendLine(bar)
            for (event in events) {
                sb.appendLine(
                    "${formatTimestamp(event.timestampMs)} [${event.severity}] ${event.name} " +
                        "gen=${event.generation} scenario=${event.testScenario ?: "-"} :: ${event.detail}"
                )
            }
            sb.appendLine()
        }

        sb.appendLine(bar)
        sb.appendLine("FINAL SUMMARY")
        sb.appendLine(bar)
        sb.appendLine("total=${summary.total} passed=${summary.passed} warned=${summary.warned} failed=${summary.failed} " +
            "skipped=${summary.skipped} manualRequired=${summary.manualRequired}")
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
        sb.appendLine("packetsGenerated=${m.packetsGenerated}")
        sb.appendLine("packetsSent=${m.packetsSent}")
        sb.appendLine("bytesSent=${m.bytesSent}")
        sb.appendLine("sendErrors=${m.sendErrors}")
        sb.appendLine("packetsReceived=${m.packetsReceived}")
        sb.appendLine("bytesReceived=${m.bytesReceived}")
        sb.appendLine("packetsLost=${m.packetsLost}")
        sb.appendLine("packetsLate=${m.packetsLate}")
        sb.appendLine("packetsOutOfOrder=${m.packetsOutOfOrder}")
        sb.appendLine("packetsDuplicate=${m.packetsDuplicate}")
        sb.appendLine("fecRecovered=${m.fecRecovered}")
        sb.appendLine("decodeErrors=${m.decodeErrors}")
        sb.appendLine("writeErrors=${m.writeErrors}")
        sb.appendLine("underruns=${m.underruns}")
        sb.appendLine("captureReadErrors=${m.captureReadErrors}")
        sb.appendLine("framesCaptured=${m.framesCaptured}")
        sb.appendLine("generationMismatches=${m.generationMismatches}")
        sb.appendLine("codecMismatches=${m.codecMismatches}")
        sb.appendLine("configInitFailures=${m.configInitFailures}")
        sb.appendLine("configAnnounceFailures=${m.configAnnounceFailures}")
        sb.appendLine("configProducerStartFailures=${m.configProducerStartFailures}")
        sb.appendLine("minJitterMs=${formatDouble(m.minJitterMs)}")
        sb.appendLine("avgJitterMs=${formatDouble(m.avgJitterMs)}")
        sb.appendLine("maxJitterMs=${formatDouble(m.maxJitterMs)}")
        sb.appendLine("minBufferMs=${formatDouble(m.minBufferMs)}")
        sb.appendLine("avgBufferMs=${formatDouble(m.avgBufferMs)}")
        sb.appendLine("maxBufferMs=${formatDouble(m.maxBufferMs)}")
        sb.appendLine("minTargetLatencyMs=${formatDouble(m.minTargetLatencyMs)}")
        sb.appendLine("avgTargetLatencyMs=${formatDouble(m.avgTargetLatencyMs)}")
        sb.appendLine("maxTargetLatencyMs=${formatDouble(m.maxTargetLatencyMs)}")
        sb.appendLine("avgCaptureReadMs=${formatDouble(m.avgCaptureReadMs)}")
        sb.appendLine("maxCaptureReadMs=${formatDouble(m.maxCaptureReadMs)}")
        sb.appendLine("avgDecodeMs=${formatDouble(m.avgDecodeMs)}")
        sb.appendLine("maxDecodeMs=${formatDouble(m.maxDecodeMs)}")
        sb.appendLine("avgWriteMs=${formatDouble(m.avgWriteMs)}")
        sb.appendLine("maxWriteMs=${formatDouble(m.maxWriteMs)}")
        sb.appendLine("avgSendMs=${formatDouble(m.avgSendMs)}")
        sb.appendLine("maxSendMs=${formatDouble(m.maxSendMs)}")
        sb.appendLine("avgEncodeMs=${formatDouble(m.avgEncodeMs)}")
        sb.appendLine("maxEncodeMs=${formatDouble(m.maxEncodeMs)}")
        sb.appendLine("avgReceiveMs=${formatDouble(m.avgReceiveMs)}")
        sb.appendLine("maxReceiveMs=${formatDouble(m.maxReceiveMs)}")
    }

    // ---------------------------------------------------------------------------------------------
    // Machine readable export
    // ---------------------------------------------------------------------------------------------

    fun toJson(): String = HatTestJson.compose(
        "run" to HatTestJson.rawObj(
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
        "device" to HatTestJson.rawObj(
            "manufacturer" to device.manufacturer,
            "model" to device.model,
            "product" to device.product,
            "device" to device.device,
            "board" to device.board,
            "androidVersion" to device.androidVersion,
            "apiLevel" to device.apiLevel,
            "fingerprint" to device.fingerprint,
            "audioOutputDevice" to device.audioOutputDevice,
            "networkTransport" to device.networkTransport
        ),
        "preconditions" to HatTestJson.rawObj(
            "ok" to preconditions.ok,
            "reasons" to preconditions.reasons,
            "details" to preconditions.details
        ),
        "scenarios" to scenarios.map { it.toJsonMap() },
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
        "summary" to HatTestJson.rawObj(
            "total" to summary.total,
            "passed" to summary.passed,
            "warned" to summary.warned,
            "failed" to summary.failed,
            "skipped" to summary.skipped,
            "manualRequired" to summary.manualRequired,
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
        "metrics" to HatTestJson.rawObj(
            "packetsGenerated" to metrics.packetsGenerated,
            "packetsSent" to metrics.packetsSent,
            "bytesSent" to metrics.bytesSent,
            "sendErrors" to metrics.sendErrors,
            "packetsReceived" to metrics.packetsReceived,
            "bytesReceived" to metrics.bytesReceived,
            "packetsLost" to metrics.packetsLost,
            "packetsLate" to metrics.packetsLate,
            "packetsOutOfOrder" to metrics.packetsOutOfOrder,
            "packetsDuplicate" to metrics.packetsDuplicate,
            "fecRecovered" to metrics.fecRecovered,
            "decodeErrors" to metrics.decodeErrors,
            "writeErrors" to metrics.writeErrors,
            "underruns" to metrics.underruns,
            "captureReadErrors" to metrics.captureReadErrors,
            "framesCaptured" to metrics.framesCaptured,
            "generationMismatches" to metrics.generationMismatches,
            "codecMismatches" to metrics.codecMismatches,
            "configInitFailures" to metrics.configInitFailures,
            "configAnnounceFailures" to metrics.configAnnounceFailures,
            "configProducerStartFailures" to metrics.configProducerStartFailures,
            "minJitterMs" to metrics.minJitterMs,
            "avgJitterMs" to metrics.avgJitterMs,
            "maxJitterMs" to metrics.maxJitterMs,
            "minBufferMs" to metrics.minBufferMs,
            "avgBufferMs" to metrics.avgBufferMs,
            "maxBufferMs" to metrics.maxBufferMs,
            "minTargetLatencyMs" to metrics.minTargetLatencyMs,
            "avgTargetLatencyMs" to metrics.avgTargetLatencyMs,
            "maxTargetLatencyMs" to metrics.maxTargetLatencyMs,
            "avgCaptureReadMs" to metrics.avgCaptureReadMs,
            "maxCaptureReadMs" to metrics.maxCaptureReadMs,
            "avgDecodeMs" to metrics.avgDecodeMs,
            "maxDecodeMs" to metrics.maxDecodeMs,
            "avgWriteMs" to metrics.avgWriteMs,
            "maxWriteMs" to metrics.maxWriteMs,
            "avgSendMs" to metrics.avgSendMs,
            "maxSendMs" to metrics.maxSendMs,
            "avgEncodeMs" to metrics.avgEncodeMs,
            "maxEncodeMs" to metrics.maxEncodeMs,
            "avgReceiveMs" to metrics.avgReceiveMs,
            "maxReceiveMs" to metrics.maxReceiveMs
        ),
        "warnings" to warnings,
        "errors" to errors,
        "latencyTargetChanges" to latencyTargetChanges,
        "transitions" to transitions.map { t ->
            HatTestJson.rawObj(
                "index" to t.index,
                "fromProfile" to t.fromProfile,
                "toProfile" to t.toProfile,
                "fromGeneration" to t.fromGeneration,
                "toGeneration" to t.toGeneration,
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
internal object HatTestJson {

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
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(escape(key)).append("\":").append(value(value))
        }
        sb.append('}')
        return sb.toString()
    }

    fun objOf(map: Map<String, Any?>): String {
        val sb = StringBuilder(64)
        sb.append('{')
        var first = true
        for ((key, value) in map) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(escape(key)).append("\":").append(value(value))
        }
        sb.append('}')
        return sb.toString()
    }

    fun arr(values: Iterable<*>): String {
        val sb = StringBuilder(32)
        sb.append('[')
        var first = true
        for (v in values) {
            if (!first) sb.append(',')
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
