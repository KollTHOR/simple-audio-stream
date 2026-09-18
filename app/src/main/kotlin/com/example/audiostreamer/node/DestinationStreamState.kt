package com.example.audiostreamer.node

import com.example.audiostreamer.AudioCodec
import com.example.audiostreamer.AudioFormatConfig
import com.example.audiostreamer.node.transport.HatTransportType
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Health status of a remote stream destination.
 */
enum class DestinationHealth {
    /** Connected and receiving packets normally with low loss/jitter. */
    HEALTHY,
    /** Experiencing packet loss, jitter spikes, or elevated RTT. */
    DEGRADED,
    /** Heartbeats or acknowledgments are stale (idle for > 5s). */
    STALE,
    /** Repeated socket send failures or network timeouts. */
    UNREACHABLE,
    /** Gracefully disconnected or link terminated. */
    DISCONNECTED
}

/**
 * Independent transmission and packet metrics tracked for a single destination.
 */
data class DestinationStats(
    val packetsSent: AtomicLong = AtomicLong(0L),
    val bytesSent: AtomicLong = AtomicLong(0L),
    val packetsLost: AtomicLong = AtomicLong(0L),
    val sendErrors: AtomicLong = AtomicLong(0L),
    @Volatile var rttMs: Float = 0f,
    @Volatile var lastSeenEpochMs: Long = System.currentTimeMillis(),
    @Volatile var lastSendErrorEpochMs: Long = 0L,
    val consecutiveErrors: AtomicInteger = AtomicInteger(0)
) {
    fun recordPacketSent(bytes: Int) {
        packetsSent.incrementAndGet()
        bytesSent.addAndGet(bytes.toLong())
        consecutiveErrors.set(0)
    }

    fun recordSendError(e: Throwable? = null) {
        sendErrors.incrementAndGet()
        lastSendErrorEpochMs = System.currentTimeMillis()
        consecutiveErrors.incrementAndGet()
    }

    fun recordHeartbeat(rtt: Float = 0f) {
        lastSeenEpochMs = System.currentTimeMillis()
        if (rtt > 0f) {
            rttMs = rtt
        }
        consecutiveErrors.set(0)
    }

    fun recordPacketLost(count: Long = 1L) {
        packetsLost.addAndGet(count)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("packetsSent", packetsSent.get())
        put("bytesSent", bytesSent.get())
        put("packetsLost", packetsLost.get())
        put("sendErrors", sendErrors.get())
        put("rttMs", rttMs.toDouble())
        put("lastSeenEpochMs", lastSeenEpochMs)
        put("lastSendErrorEpochMs", lastSendErrorEpochMs)
        put("consecutiveErrors", consecutiveErrors.get())
    }
}

/**
 * Independent receiver buffering metrics reported by a single destination.
 *
 * Invariant: One receiver's buffering or jitter MUST NOT affect other receivers.
 */
data class DestinationBufferStats(
    @Volatile var bufferFillSlots: Int = 0,
    @Volatile var targetWatermarkMs: Float = 40f,
    @Volatile var estimatedJitterMs: Float = 0f,
    @Volatile var arrivalMarginMs: Float = 0f,
    @Volatile var underruns: Long = 0L,
    @Volatile var lastUpdateEpochMs: Long = System.currentTimeMillis()
) {
    fun update(
        fillSlots: Int,
        watermarkMs: Float,
        jitterMs: Float = estimatedJitterMs,
        marginMs: Float = arrivalMarginMs,
        underrunCount: Long = underruns
    ) {
        bufferFillSlots = fillSlots
        targetWatermarkMs = watermarkMs
        estimatedJitterMs = jitterMs
        arrivalMarginMs = marginMs
        underruns = underrunCount
        lastUpdateEpochMs = System.currentTimeMillis()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("bufferFillSlots", bufferFillSlots)
        put("targetWatermarkMs", targetWatermarkMs.toDouble())
        put("estimatedJitterMs", estimatedJitterMs.toDouble())
        put("arrivalMarginMs", arrivalMarginMs.toDouble())
        put("underruns", underruns)
        put("lastUpdateEpochMs", lastUpdateEpochMs)
    }
}

/**
 * Independent playout and latency state reported by a single destination.
 */
data class DestinationPlayoutState(
    @Volatile var state: String = "PLAYING",
    @Volatile var latencyTargetMs: Float = 40f,
    @Volatile var smoothPps: Float = 0f,
    @Volatile var smoothBps: Float = 0f,
    @Volatile var lastUpdateEpochMs: Long = System.currentTimeMillis()
) {
    fun update(
        playoutState: String,
        targetLatencyMs: Float,
        pps: Float = smoothPps,
        bps: Float = smoothBps
    ) {
        state = playoutState
        latencyTargetMs = targetLatencyMs
        smoothPps = pps
        smoothBps = bps
        lastUpdateEpochMs = System.currentTimeMillis()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("state", state)
        put("latencyTargetMs", latencyTargetMs.toDouble())
        put("smoothPps", smoothPps.toDouble())
        put("smoothBps", smoothBps.toDouble())
        put("lastUpdateEpochMs", lastUpdateEpochMs)
    }
}

/**
 * Retains complete, independent state for a single destination receiving a fanned-out audio stream.
 *
 * Requirements enforced:
 * 1. Independent connection state ([LinkState], [StreamLifecycleState]).
 * 2. Independent packet statistics ([DestinationStats]).
 * 3. Independent stream generation.
 * 4. Independent health ([DestinationHealth]).
 * 5. Independent network transport ([HatTransportType], e.g. Wi-Fi Direct vs LAN).
 * 6. Independent receiver buffering ([DestinationBufferStats]).
 * 7. Independent playout state ([DestinationPlayoutState]).
 * 8. One receiver becoming slow does NOT force others to increase latency.
 * 9. One receiver disconnecting does NOT stop the stream for others.
 */
data class DestinationStreamState(
    val destinationNode: NodeInfo,
    @Volatile var link: HatLink,
    @Volatile var stream: HatStream,
    @Volatile var connectionState: LinkState = link.state,
    @Volatile var transportType: HatTransportType = link.hatTransportType,
    @Volatile var generation: Long = stream.generation,
    val stats: DestinationStats = DestinationStats(),
    val bufferStats: DestinationBufferStats = DestinationBufferStats(),
    val playoutState: DestinationPlayoutState = DestinationPlayoutState(),
    @Volatile var health: DestinationHealth = DestinationHealth.HEALTHY,
    val joinedEpochMs: Long = System.currentTimeMillis()
) {
    val nodeId: String get() = destinationNode.id
    val nodeName: String get() = destinationNode.name
    val linkId: String get() = link.id
    val streamId: String get() = stream.id

    val isStreaming: Boolean
        get() = stream.state == StreamLifecycleState.START && link.isAlive && health != DestinationHealth.DISCONNECTED

    fun isFormatCompatible(codec: AudioCodec, format: AudioFormatConfig): Boolean =
        stream.codec == codec &&
        stream.audioFormat.sampleRateHz == format.sampleRateHz &&
        stream.audioFormat.channels == format.channels

    fun toJson(): JSONObject = JSONObject().apply {
        put("destinationNode", destinationNode.toJson())
        put("linkId", link.id)
        put("streamId", stream.id)
        put("connectionState", connectionState.name)
        put("transportType", transportType.name)
        put("generation", generation)
        put("health", health.name)
        put("joinedEpochMs", joinedEpochMs)
        put("stats", stats.toJson())
        put("bufferStats", bufferStats.toJson())
        put("playoutState", playoutState.toJson())
    }
}
