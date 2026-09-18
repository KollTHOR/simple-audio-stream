package com.example.audiostreamer.node

import com.example.audiostreamer.AudioCodec
import com.example.audiostreamer.AudioFormatConfig
import com.example.audiostreamer.AppLogger as Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents an active, bidirectional communication session between two HAT Nodes over a persistent [HatLink].
 * Encapsulates all concurrent streams multiplexed over that link.
 */
data class HatSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val linkId: String,
    val localNode: NodeInfo,
    val remoteNode: NodeInfo,
    val establishedEpochMs: Long = System.currentTimeMillis(),
    val activeStreams: List<HatStream> = emptyList()
) {
    val streamCount: Int get() = activeStreams.size
    val hasActiveStreaming: Boolean get() = activeStreams.any { it.state == StreamLifecycleState.START }
}

/**
 * Manages bidirectional session and stream lifecycles ([StreamLifecycleState]) across persistent [HatLink]s.
 *
 * **Architecture Invariants:**
 * 1. A [HatLink] is persistent and bidirectional.
 * 2. Multiple independent [HatStream]s may use the same Link concurrently (e.g. Forward Music + Return Mic).
 * 3. Full stream lifecycle: REQUEST -> ACCEPT -> START (or REJECT), PAUSE, STOP.
 * 4. Stopping, pausing, or rejecting a stream does NOT terminate the underlying [HatLink].
 * 5. Application streams are multiplexed over the same connection without interference.
 * 6. Neither Node is permanently locked into a transmitter or receiver role.
 */
object HatSessionManager {
    private const val TAG = "HatSessionManager"
    private val lock = Any()

    // Keyed by streamId
    private val streams = ConcurrentHashMap<String, HatStream>()

    private val _activeStreams = MutableStateFlow<List<HatStream>>(emptyList())
    val activeStreams: StateFlow<List<HatStream>> = _activeStreams.asStateFlow()

    private val _sessions = MutableStateFlow<List<HatSession>>(emptyList())
    val sessions: StateFlow<List<HatSession>> = _sessions.asStateFlow()

    /**
     * Initiates a new stream request over the specified link.
     * State transitions to [StreamLifecycleState.REQUEST].
     */
    fun requestStream(
        linkId: String,
        streamType: StreamType = StreamType.MUSIC,
        direction: StreamDirection,
        generation: Long = 1L,
        sourceNode: NodeInfo,
        destinationNode: NodeInfo,
        audioFormat: AudioFormatConfig = AudioFormatConfig(),
        codec: AudioCodec = AudioCodec.PCM,
        streamId: String? = null
    ): Result<HatStream> {
        synchronized(lock) {
            val link = HatLinkManager.activeLinks.value.firstOrNull { it.id == linkId }
            if (link == null) {
                return Result.failure(IllegalArgumentException("Cannot request stream: Link $linkId does not exist or is not active"))
            }

            val newId = streamId ?: UUID.randomUUID().toString()
            val stream = HatStream(
                id = newId,
                linkId = linkId,
                generation = generation,
                sourceNode = sourceNode,
                destinationNode = destinationNode,
                direction = direction,
                streamType = streamType,
                audioFormat = audioFormat,
                codec = codec,
                state = StreamLifecycleState.REQUEST,
                startedEpochMs = System.currentTimeMillis(),
                lastStateChangeEpochMs = System.currentTimeMillis()
            )

            streams[stream.id] = stream
            publishState()
            Log.i(TAG, "Stream requested: ${stream.describe()}")
            return Result.success(stream)
        }
    }

    /**
     * Accepts a requested stream, optionally confirming or refining the negotiated format/codec.
     * State transitions from [StreamLifecycleState.REQUEST] to [StreamLifecycleState.ACCEPT].
     */
    fun acceptStream(
        streamId: String,
        negotiatedFormat: AudioFormatConfig? = null,
        negotiatedCodec: AudioCodec? = null
    ): Result<HatStream> {
        synchronized(lock) {
            val stream = streams[streamId]
                ?: return Result.failure(IllegalArgumentException("Stream $streamId not found"))

            if (!stream.state.canTransitionTo(StreamLifecycleState.ACCEPT)) {
                return Result.failure(IllegalStateException("Cannot accept stream in state ${stream.state}"))
            }

            val updated = stream.copy(
                state = StreamLifecycleState.ACCEPT,
                audioFormat = negotiatedFormat ?: stream.audioFormat,
                codec = negotiatedCodec ?: stream.codec,
                lastStateChangeEpochMs = System.currentTimeMillis()
            )
            streams[streamId] = updated
            publishState()
            Log.i(TAG, "Stream accepted: ${updated.describe()}")
            return Result.success(updated)
        }
    }

    /**
     * Rejects a requested stream with an explicit reason.
     * State transitions from [StreamLifecycleState.REQUEST] to [StreamLifecycleState.REJECT].
     * The parent link remains intact and unharmed.
     */
    fun rejectStream(
        streamId: String,
        reason: String
    ): Result<HatStream> {
        synchronized(lock) {
            val stream = streams[streamId]
                ?: return Result.failure(IllegalArgumentException("Stream $streamId not found"))

            if (!stream.state.canTransitionTo(StreamLifecycleState.REJECT)) {
                return Result.failure(IllegalStateException("Cannot reject stream in state ${stream.state}"))
            }

            val updated = stream.copy(
                state = StreamLifecycleState.REJECT,
                rejectionReason = reason,
                lastStateChangeEpochMs = System.currentTimeMillis()
            )
            streams[streamId] = updated
            publishState()
            Log.i(TAG, "Stream rejected: ${updated.describe()} reason=$reason")
            return Result.success(updated)
        }
    }

    /**
     * Starts audio data flow over an accepted or paused stream.
     * State transitions to [StreamLifecycleState.START].
     */
    fun startStream(
        streamId: String
    ): Result<HatStream> {
        synchronized(lock) {
            val stream = streams[streamId]
                ?: return Result.failure(IllegalArgumentException("Stream $streamId not found"))

            if (!stream.state.canTransitionTo(StreamLifecycleState.START)) {
                return Result.failure(IllegalStateException("Cannot start stream in state ${stream.state}"))
            }

            val updated = stream.copy(
                state = StreamLifecycleState.START,
                lastStateChangeEpochMs = System.currentTimeMillis()
            )
            streams[streamId] = updated
            publishState()
            Log.i(TAG, "Stream started: ${updated.describe()}")
            return Result.success(updated)
        }
    }

    /**
     * Pauses an active stream without closing it or dropping the underlying link.
     * State transitions from [StreamLifecycleState.START] to [StreamLifecycleState.PAUSE].
     */
    fun pauseStream(
        streamId: String
    ): Result<HatStream> {
        synchronized(lock) {
            val stream = streams[streamId]
                ?: return Result.failure(IllegalArgumentException("Stream $streamId not found"))

            if (!stream.state.canTransitionTo(StreamLifecycleState.PAUSE)) {
                return Result.failure(IllegalStateException("Cannot pause stream in state ${stream.state}"))
            }

            val updated = stream.copy(
                state = StreamLifecycleState.PAUSE,
                lastStateChangeEpochMs = System.currentTimeMillis()
            )
            streams[streamId] = updated
            publishState()
            Log.i(TAG, "Stream paused: ${updated.describe()}")
            return Result.success(updated)
        }
    }

    /**
     * Gracefully stops an active or paused stream.
     * State transitions to [StreamLifecycleState.STOP].
     * The parent [HatLink] remains persistent and alive for other streams.
     */
    fun stopStream(
        streamId: String
    ): Result<HatStream> {
        synchronized(lock) {
            val stream = streams[streamId]
                ?: return Result.failure(IllegalArgumentException("Stream $streamId not found"))

            if (!stream.state.canTransitionTo(StreamLifecycleState.STOP)) {
                return Result.failure(IllegalStateException("Cannot stop stream in state ${stream.state}"))
            }

            val updated = stream.copy(
                state = StreamLifecycleState.STOP,
                lastStateChangeEpochMs = System.currentTimeMillis()
            )
            streams[streamId] = updated
            publishState()
            Log.i(TAG, "Stream stopped: ${updated.describe()}")
            return Result.success(updated)
        }
    }

    /**
     * Convenience method to request, accept, and start a stream in one call.
     * Backward-compatible with existing streaming setup.
     */
    fun createOrStartStream(
        linkId: String,
        streamType: StreamType = StreamType.MUSIC,
        direction: StreamDirection,
        generation: Long = 1L,
        sourceNode: NodeInfo,
        destinationNode: NodeInfo,
        audioFormat: AudioFormatConfig = AudioFormatConfig(),
        codec: AudioCodec = AudioCodec.PCM,
        streamId: String? = null
    ): HatStream {
        synchronized(lock) {
            // Check if active stream matching exact link, type, and direction already exists
            val existing = streams.values.firstOrNull {
                it.linkId == linkId && it.streamType == streamType && it.direction == direction &&
                (it.state == StreamLifecycleState.START || it.state == StreamLifecycleState.ACCEPT || it.state == StreamLifecycleState.PAUSE)
            }
            if (existing != null) {
                val updated = existing.copy(
                    generation = generation,
                    audioFormat = audioFormat,
                    codec = codec,
                    state = StreamLifecycleState.START,
                    lastStateChangeEpochMs = System.currentTimeMillis()
                )
                streams[existing.id] = updated
                publishState()
                return updated
            }

            val newStream = HatStream(
                id = streamId ?: UUID.randomUUID().toString(),
                linkId = linkId,
                generation = generation,
                sourceNode = sourceNode,
                destinationNode = destinationNode,
                direction = direction,
                streamType = streamType,
                audioFormat = audioFormat,
                codec = codec,
                state = StreamLifecycleState.START,
                startedEpochMs = System.currentTimeMillis(),
                lastStateChangeEpochMs = System.currentTimeMillis()
            )
            streams[newStream.id] = newStream
            publishState()
            Log.i(TAG, "Created and started stream: ${newStream.describe()}")
            return newStream
        }
    }

    fun getStream(streamId: String): HatStream? = streams[streamId]

    fun getStreamsForLink(linkId: String): List<HatStream> {
        return streams.values.filter { it.linkId == linkId }
    }

    fun getActiveStreams(): List<HatStream> {
        return streams.values.filter { it.state == StreamLifecycleState.START }
    }

    fun closeStream(streamId: String) {
        synchronized(lock) {
            val removed = streams.remove(streamId)
            if (removed != null) {
                publishState()
                Log.i(TAG, "Closed and removed stream $streamId")
            }
        }
    }

    fun closeStreamsForLink(linkId: String) {
        synchronized(lock) {
            val toRemove = streams.values.filter { it.linkId == linkId }
            for (s in toRemove) {
                streams.remove(s.id)
            }
            if (toRemove.isNotEmpty()) {
                publishState()
                Log.i(TAG, "Closed ${toRemove.size} stream(s) for link $linkId")
            }
        }
    }

    fun recordStreamActivity(
        streamId: String,
        packetsIncrement: Long = 1L,
        bytesIncrement: Long = 0L
    ) {
        val stream = streams[streamId] ?: return
        synchronized(lock) {
            val updated = stream.copy(
                packetsTransferred = stream.packetsTransferred + packetsIncrement,
                bytesTransferred = stream.bytesTransferred + bytesIncrement
            )
            streams[streamId] = updated
            publishState()
        }
    }

    fun recordStreamActivityForLink(
        linkId: String,
        direction: StreamDirection? = null,
        packetsIncrement: Long = 1L,
        bytesIncrement: Long = 0L
    ) {
        synchronized(lock) {
            val matching = streams.values.filter {
                it.linkId == linkId && (direction == null || it.direction == direction) && it.state == StreamLifecycleState.START
            }
            for (s in matching) {
                streams[s.id] = s.copy(
                    packetsTransferred = s.packetsTransferred + packetsIncrement,
                    bytesTransferred = s.bytesTransferred + bytesIncrement
                )
            }
            if (matching.isNotEmpty()) {
                publishState()
            }
        }
    }

    fun getSessionForLink(linkId: String): HatSession? {
        val link = HatLinkManager.activeLinks.value.firstOrNull { it.id == linkId } ?: return null
        val linkStreams = getStreamsForLink(linkId)
        return HatSession(
            sessionId = "session-$linkId",
            linkId = linkId,
            localNode = link.localNode,
            remoteNode = link.remoteNode,
            establishedEpochMs = link.establishedEpochMs,
            activeStreams = linkStreams
        )
    }

    fun getAllSessions(): List<HatSession> {
        val activeLinks = HatLinkManager.activeLinks.value
        return activeLinks.map { link ->
            val linkStreams = getStreamsForLink(link.id)
            HatSession(
                sessionId = "session-${link.id}",
                linkId = link.id,
                localNode = link.localNode,
                remoteNode = link.remoteNode,
                establishedEpochMs = link.establishedEpochMs,
                activeStreams = linkStreams
            )
        }
    }

    fun clear() {
        synchronized(lock) {
            streams.clear()
            publishState()
            Log.i(TAG, "Cleared all active streams and sessions")
        }
    }

    private fun publishState() {
        _activeStreams.value = streams.values.toList()
        _sessions.value = getAllSessions()
    }

    fun getDiagnosticsSnapshot(): Map<String, Any?> {
        val streamList = streams.values.toList()
        val sessionsList = getAllSessions()
        val map = linkedMapOf<String, Any?>()
        map["totalStreamsCount"] = streamList.size
        map["activeStreamingCount"] = streamList.count { it.state == StreamLifecycleState.START }
        map["pausedStreamsCount"] = streamList.count { it.state == StreamLifecycleState.PAUSE }
        map["totalSessionsCount"] = sessionsList.size

        map["streams"] = streamList.map { s ->
            linkedMapOf(
                "streamId" to s.id,
                "linkId" to s.linkId,
                "sourceNodeId" to s.sourceNode.id,
                "destinationNodeId" to s.destinationNode.id,
                "direction" to s.direction.name,
                "streamType" to s.streamType.name,
                "codec" to s.codec.name,
                "sampleRate" to s.audioFormat.sampleRateHz,
                "state" to s.state.name,
                "packets" to s.packetsTransferred,
                "bytes" to s.bytesTransferred,
                "rejectionReason" to s.rejectionReason
            )
        }
        return map
    }
}
