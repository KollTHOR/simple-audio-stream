package com.example.audiostreamer.node

import com.example.audiostreamer.AudioCodec
import com.example.audiostreamer.AudioFormatConfig
import com.example.audiostreamer.AppLogger as Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages multi-destination audio stream fan-out from a single source HAT Node to multiple destination Nodes.
 *
 * **Architecture Invariants:**
 * 1. Single source Node maintains multiple concurrent destinations (e.g. Phone -> M300, Phone -> Tablet, Phone -> Speaker).
 * 2. The source encoder encodes audio ONCE when destinations share compatible negotiated formats.
 * 3. Encoded packets fan out independently to each destination's specific link/transport.
 * 4. Each destination maintains independent:
 *    - connection state
 *    - packet statistics
 *    - stream generation
 *    - health
 *    - network transport (e.g. Wi-Fi Direct for M300, LAN for Tablet)
 *    - receiver buffering
 *    - playout state
 * 5. One receiver becoming slow does NOT force other receivers to increase latency.
 * 6. One receiver disconnecting does NOT stop the stream for others.
 * 7. A new receiver may join an already-running stream without restarting capture or encoder.
 * 8. A receiver may leave without restarting source capture or encoder.
 */
object HatMultiStreamManager {
    private const val TAG = "HatMultiStreamManager"
    private val lock = Any()

    // Keyed by destination Node ID
    private val destinations = ConcurrentHashMap<String, DestinationStreamState>()

    private val _activeDestinations = MutableStateFlow<List<DestinationStreamState>>(emptyList())
    val activeDestinations: StateFlow<List<DestinationStreamState>> = _activeDestinations.asStateFlow()

    // Shared encoder configuration
    @Volatile var activeEncoderCodec: AudioCodec? = null
        private set
    @Volatile var activeEncoderFormat: AudioFormatConfig? = null
        private set
    @Volatile var activeGeneration: Long = 1L
        private set

    /**
     * Configures the shared audio encoder settings.
     * When destinations have matching formats, audio is encoded once and fanned out.
     */
    fun configureSharedEncoder(
        codec: AudioCodec,
        format: AudioFormatConfig,
        generation: Long = 1L
    ) {
        synchronized(lock) {
            activeEncoderCodec = codec
            activeEncoderFormat = format
            activeGeneration = generation
            Log.i(TAG, "Configured shared encoder: codec=${codec.name}, rate=${format.sampleRateHz}, gen=$generation")
        }
    }

    /**
     * Checks whether a destination node is format-compatible with the active shared encoder.
     */
    fun isFormatCompatible(
        codec: AudioCodec,
        format: AudioFormatConfig
    ): Boolean {
        val curCodec = activeEncoderCodec ?: return true
        val curFormat = activeEncoderFormat ?: return true
        return curCodec == codec &&
               curFormat.sampleRateHz == format.sampleRateHz &&
               curFormat.channels == format.channels
    }

    /**
     * Registers a new or existing destination node for stream fan-out.
     *
     * Invariant: A new receiver joins the already-running stream without restarting
     * the source capture or encoder.
     */
    fun registerDestination(
        destNode: NodeInfo,
        link: HatLink,
        stream: HatStream
    ): DestinationStreamState {
        synchronized(lock) {
            val existing = destinations[destNode.id]
            if (existing != null) {
                existing.link = link
                existing.stream = stream
                existing.connectionState = link.state
                existing.transportType = link.hatTransportType
                existing.generation = stream.generation
                existing.health = DestinationHealth.HEALTHY
                existing.stats.lastSeenEpochMs = System.currentTimeMillis()
                publishState()
                Log.i(TAG, "Updated destination ${destNode.name} (${destNode.id}) on link ${link.id}")
                return existing
            }

            val newState = DestinationStreamState(
                destinationNode = destNode,
                link = link,
                stream = stream,
                connectionState = link.state,
                transportType = link.hatTransportType,
                generation = stream.generation,
                health = DestinationHealth.HEALTHY,
                joinedEpochMs = System.currentTimeMillis()
            )
            destinations[destNode.id] = newState
            publishState()
            Log.i(TAG, "Registered new destination ${destNode.name} (${destNode.id}) via ${link.hatTransportType} on link ${link.id}. Total destinations: ${destinations.size}")
            return newState
        }
    }

    /**
     * Removes a destination from the fan-out router upon disconnection.
     *
     * Invariant: One receiver disconnecting must NOT stop the stream for others!
     * The receiver leaves without restarting the source capture or encoder.
     */
    fun removeDestination(
        nodeId: String,
        reason: String = "disconnect"
    ): DestinationStreamState? {
        synchronized(lock) {
            val removed = destinations.remove(nodeId)
            if (removed != null) {
                removed.health = DestinationHealth.DISCONNECTED
                removed.connectionState = LinkState.DISCONNECTED
                // Terminate this destination's stream, but do NOT affect other destinations
                HatSessionManager.stopStream(removed.stream.id)
                publishState()
                Log.i(TAG, "Removed destination ${removed.nodeName} ($nodeId), reason=$reason. Remaining destinations: ${destinations.size}")
            }
            return removed
        }
    }

    /**
     * Fans out a single encoded packet to all active, streaming destinations.
     *
     * Invariants:
     * 1. The audio is encoded once, then transmitted to each destination.
     * 2. Transmission errors to one destination are isolated and do NOT fail the fan-out to other destinations.
     */
    fun fanOutPacket(
        packet: DatagramPacket,
        excludeNodeId: String? = null
    ): Map<String, Result<Unit>> {
        val results = mutableMapOf<String, Result<Unit>>()
        val targets = destinations.values.toList()

        for (dest in targets) {
            if (dest.nodeId == excludeNodeId) continue
            if (!dest.isStreaming) continue

            try {
                // Send over the destination's specific link/transport
                dest.link.send(packet)
                dest.stats.recordPacketSent(packet.length)
                dest.health = DestinationHealth.HEALTHY
                results[dest.nodeId] = Result.success(Unit)
            } catch (e: Exception) {
                dest.stats.recordSendError(e)
                if (dest.stats.consecutiveErrors.get() > 5) {
                    dest.health = DestinationHealth.UNREACHABLE
                } else {
                    dest.health = DestinationHealth.DEGRADED
                }
                Log.w(TAG, "Fan-out send failed to ${dest.nodeName} (${dest.nodeId}): ${e.message}")
                results[dest.nodeId] = Result.failure(e)
            }
        }
        return results
    }

    /**
     * Records a fan-out packet transmission result for an explicit destination node.
     */
    fun recordFanOut(
        destinationNodeId: String,
        packetBytes: Int,
        isSuccess: Boolean,
        error: Throwable? = null
    ) {
        val dest = destinations[destinationNodeId] ?: return
        if (isSuccess) {
            dest.stats.recordPacketSent(packetBytes)
            dest.health = DestinationHealth.HEALTHY
        } else {
            dest.stats.recordSendError(error)
            if (dest.stats.consecutiveErrors.get() > 5) {
                dest.health = DestinationHealth.UNREACHABLE
            } else {
                dest.health = DestinationHealth.DEGRADED
            }
        }
    }

    /**
     * Updates receiver feedback (RTT, buffer fill, target watermark) for a specific destination.
     *
     * Invariant: One receiver becoming slow (e.g. increasing target watermark to 200ms)
     * must NOT force other receivers to increase latency.
     */
    fun recordHeartbeat(
        nodeId: String,
        rttMs: Float = 0f,
        bufferFillSlots: Int = 0,
        targetWatermarkMs: Float = 40f,
        underruns: Long = 0L
    ) {
        val dest = destinations[nodeId] ?: return
        dest.stats.recordHeartbeat(rttMs)
        dest.bufferStats.update(
            fillSlots = bufferFillSlots,
            watermarkMs = targetWatermarkMs,
            underrunCount = underruns
        )
    }

    /**
     * Updates playout and latency state for a specific destination.
     */
    fun updatePlayoutState(
        nodeId: String,
        playoutState: String,
        latencyTargetMs: Float,
        pps: Float = 0f,
        bps: Float = 0f
    ) {
        val dest = destinations[nodeId] ?: return
        dest.playoutState.update(
            playoutState = playoutState,
            targetLatencyMs = latencyTargetMs,
            pps = pps,
            bps = bps
        )
    }

    fun getDestination(nodeId: String): DestinationStreamState? = destinations[nodeId]

    fun getDestinationForLink(linkId: String): DestinationStreamState? =
        destinations.values.firstOrNull { it.linkId == linkId }

    fun getAllDestinations(): List<DestinationStreamState> = destinations.values.toList()

    fun getActiveDestinations(): List<DestinationStreamState> =
        destinations.values.filter { it.isStreaming }

    fun countActiveDestinations(): Int = destinations.values.count { it.isStreaming }

    fun hasActiveDestinations(): Boolean = destinations.values.any { it.isStreaming }

    fun clear() {
        synchronized(lock) {
            destinations.clear()
            activeEncoderCodec = null
            activeEncoderFormat = null
            publishState()
            Log.i(TAG, "Cleared all multi-stream destinations")
        }
    }

    private fun publishState() {
        _activeDestinations.value = destinations.values.toList()
    }

    fun getDiagnosticsSnapshot(): Map<String, Any?> {
        val list = destinations.values.toList()
        val map = linkedMapOf<String, Any?>()
        map["totalDestinations"] = list.size
        map["activeStreamingDestinations"] = list.count { it.isStreaming }
        map["sharedEncoderCodec"] = activeEncoderCodec?.name ?: "none"
        map["sharedEncoderFormat"] = activeEncoderFormat?.let { "${it.sampleRateHz}Hz ${it.bitDepth.bits}b" } ?: "none"
        map["sharedGeneration"] = activeGeneration

        map["destinations"] = list.map { dest ->
            linkedMapOf(
                "nodeId" to dest.nodeId,
                "nodeName" to dest.nodeName,
                "linkId" to dest.linkId,
                "streamId" to dest.streamId,
                "transport" to dest.transportType.name,
                "connectionState" to dest.connectionState.name,
                "health" to dest.health.name,
                "generation" to dest.generation,
                "packetsSent" to dest.stats.packetsSent.get(),
                "bytesSent" to dest.stats.bytesSent.get(),
                "sendErrors" to dest.stats.sendErrors.get(),
                "rttMs" to dest.stats.rttMs,
                "targetWatermarkMs" to dest.bufferStats.targetWatermarkMs,
                "latencyTargetMs" to dest.playoutState.latencyTargetMs,
                "playoutState" to dest.playoutState.state
            )
        }
        return map
    }
}
