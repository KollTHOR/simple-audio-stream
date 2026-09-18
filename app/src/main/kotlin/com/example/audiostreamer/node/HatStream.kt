package com.example.audiostreamer.node

import com.example.audiostreamer.AudioBitDepth
import com.example.audiostreamer.AudioChannelLayout
import com.example.audiostreamer.AudioCodec
import com.example.audiostreamer.AudioFormatConfig
import com.example.audiostreamer.AudioSampleRate
import org.json.JSONObject
import java.util.UUID

/**
 * Direction of an audio stream relative to the local node.
 */
enum class StreamDirection {
    OUTBOUND,      // Audio data flows from local Node to remote Node
    INBOUND,       // Audio data flows from remote Node to local Node
    BIDIRECTIONAL  // Duplex / interactive two-way stream
}

/**
 * Functional application category of the audio stream.
 */
enum class StreamType {
    MUSIC,         // High-fidelity music or media playback
    MICROPHONE,    // Low-latency voice/mic return capture
    VOICE_CALL,    // Interactive voice conversation
    MEDIA,         // System or screen projection audio
    CONTROL        // Out-of-band sync, clock drift, or telemetry stream
}

/**
 * Lifecycle state of an active or negotiating [HatStream].
 *
 * Full stream lifecycle:
 * - REQUEST: Stream requested by source node, proposing format, codec, and direction.
 * - ACCEPT: Stream accepted by destination node, confirming negotiated parameters.
 * - REJECT: Stream request rejected by destination node (with failure reason).
 * - START: Stream is active and flowing audio data.
 * - PAUSE: Stream temporarily paused without closing the session or parent link.
 * - STOP: Stream gracefully stopped and terminated. The parent [HatLink] remains persistent.
 */
enum class StreamLifecycleState {
    REQUEST,
    ACCEPT,
    REJECT,
    START,
    PAUSE,
    STOP;

    val isActive: Boolean get() = this == START
    val isNegotiating: Boolean get() = this == REQUEST || this == ACCEPT
    val isTerminal: Boolean get() = this == REJECT || this == STOP

    fun canTransitionTo(next: StreamLifecycleState): Boolean = when (this) {
        REQUEST -> next == ACCEPT || next == REJECT || next == STOP || next == START
        ACCEPT -> next == START || next == STOP || next == REJECT
        START -> next == PAUSE || next == STOP
        PAUSE -> next == START || next == STOP
        REJECT -> false
        STOP -> false
    }

    companion object {
        // Backwards compatibility aliases
        @JvmField val ACTIVE = START
        @JvmField val INITIALIZING = REQUEST
        @JvmField val PAUSED = PAUSE
        @JvmField val STOPPED = STOP
        @JvmField val FAILED = REJECT
    }
}

/**
 * Represents an application-level audio data flow established over a [HatLink].
 *
 * Architecture Invariants:
 * 1. A Stream is an application-level data flow over a persistent, bidirectional Link.
 * 2. Either Node can create a stream over an existing Link.
 * 3. Multiple streams may share the same Link concurrently (e.g. Phone -> M300 music AND M300 -> Phone mic).
 * 4. Stopping, pausing, or rejecting one stream NEVER closes or drops the parent [HatLink].
 * 5. The stream binds an explicit source, destination, generation, codec, format, and direction.
 */
data class HatStream(
    val id: String = UUID.randomUUID().toString(),
    val linkId: String,
    val generation: Long = 1L,
    val sourceNode: NodeInfo,
    val destinationNode: NodeInfo,
    val direction: StreamDirection,
    val streamType: StreamType = StreamType.MUSIC,
    val audioFormat: AudioFormatConfig = AudioFormatConfig(),
    val codec: AudioCodec = AudioCodec.PCM,
    val state: StreamLifecycleState = StreamLifecycleState.START,
    val startedEpochMs: Long = System.currentTimeMillis(),
    val rejectionReason: String? = null,
    val packetsTransferred: Long = 0L,
    val bytesTransferred: Long = 0L,
    val lastStateChangeEpochMs: Long = System.currentTimeMillis()
) {
    init {
        require(id.isNotBlank()) { "Stream ID cannot be blank" }
        require(linkId.isNotBlank()) { "Link ID cannot be blank" }
    }

    val negotiatedFormat: AudioFormatConfig get() = audioFormat

    val isActive: Boolean
        get() = state == StreamLifecycleState.START

    val isPaused: Boolean
        get() = state == StreamLifecycleState.PAUSE

    val isStopped: Boolean
        get() = state == StreamLifecycleState.STOP

    fun describe(): String {
        val rej = if (rejectionReason != null) " (rejected: $rejectionReason)" else ""
        return "HatStream[$id on Link $linkId]: ${sourceNode.name} -> ${destinationNode.name} ($streamType, ${codec.name}, gen=$generation, $state)$rej"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("linkId", linkId)
        put("generation", generation)
        put("sourceNode", sourceNode.toJson())
        put("destinationNode", destinationNode.toJson())
        put("direction", direction.name)
        put("streamType", streamType.name)
        put("codec", codec.name)
        put("sampleRate", audioFormat.sampleRateHz)
        put("bitDepth", audioFormat.bitDepth.bits)
        put("channels", audioFormat.channels)
        put("state", state.name)
        put("startedEpochMs", startedEpochMs)
        put("packetsTransferred", packetsTransferred)
        put("bytesTransferred", bytesTransferred)
        if (rejectionReason != null) {
            put("rejectionReason", rejectionReason)
        }
        put("lastStateChangeEpochMs", lastStateChangeEpochMs)
    }

    companion object {
        fun fromJson(json: JSONObject): HatStream {
            val audioFormat = AudioFormatConfig(
                sampleRate = AudioSampleRate.fromHz(json.optInt("sampleRate", 48000)),
                bitDepth = AudioBitDepth.fromBits(json.optInt("bitDepth", 16)),
                channelLayout = AudioChannelLayout.STEREO
            )
            return HatStream(
                id = json.getString("id"),
                linkId = json.getString("linkId"),
                generation = json.optLong("generation", 1L),
                sourceNode = NodeInfo.fromJson(json.getJSONObject("sourceNode")),
                destinationNode = NodeInfo.fromJson(json.getJSONObject("destinationNode")),
                direction = StreamDirection.valueOf(json.optString("direction", StreamDirection.OUTBOUND.name)),
                streamType = StreamType.valueOf(json.optString("streamType", StreamType.MUSIC.name)),
                audioFormat = audioFormat,
                codec = AudioCodec.valueOf(json.optString("codec", AudioCodec.PCM.name)),
                state = try {
                    StreamLifecycleState.valueOf(json.optString("state", StreamLifecycleState.START.name))
                } catch (e: Exception) {
                    StreamLifecycleState.START
                },
                startedEpochMs = json.optLong("startedEpochMs", System.currentTimeMillis()),
                packetsTransferred = json.optLong("packetsTransferred", 0L),
                bytesTransferred = json.optLong("bytesTransferred", 0L),
                rejectionReason = json.optString("rejectionReason").takeIf { it.isNotEmpty() },
                lastStateChangeEpochMs = json.optLong("lastStateChangeEpochMs", System.currentTimeMillis())
            )
        }
    }
}
