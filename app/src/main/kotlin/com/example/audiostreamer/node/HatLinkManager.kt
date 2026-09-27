package com.example.audiostreamer.node

import com.example.audiostreamer.AudioCodec
import com.example.audiostreamer.AudioConfig
import com.example.audiostreamer.AudioFormatConfig
import com.example.audiostreamer.AppLogger as Log
import com.example.audiostreamer.node.discovery.DiscoveredNodeEntry
import com.example.audiostreamer.node.transport.HatTransportRegistry
import com.example.audiostreamer.node.transport.HatTransportType
import com.example.audiostreamer.node.transport.TransportAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the network links ([HatLink]), discovered-node transport candidates, and application
 * streams ([HatStream]) for the local HAT Node.
 *
 * **Architecture Invariants:**
 * 1. A Link is a communication relationship between two Nodes.
 * 2. A Stream is an application-level data flow over a Link.
 * 3. A Link is inherently bidirectional.
 * 4. Either Node can create a stream over an existing Link.
 * 5. Multiple streams may share the same Link concurrently.
 * 6. Neither side is permanently locked into a transmitter or receiver role.
 * 7. Multiple remote Nodes are supported concurrently via separate Links.
 * 8. Avoids duplicate links: a live link to the same node/address is reused, never duplicated.
 * 9. BLE and NFC are discovery/bootstrap mechanisms only and must never be audio transport candidates.
 * 10. Retains discovered-node candidate state across connection cycles for telemetry/diagnostics.
 * 11. Once a [HatLink] exists, the application layer does not care which transport created it.
 *
 * NOTE: links are established by the live audio path (a receiver heartbeat registers a client,
 * [LinkAdapters] then calls [getOrCreateLink]); this object is the link/stream *registry and
 * candidate ledger*, not a connection *scheduler*. See git history for the removed policy-based
 * connect/reconnect/attempt engine, which had no production caller.
 */
object HatLinkManager {
    private const val TAG = "HatLinkManager"

    private val lock = Any()

    // Keyed by Link ID
    private val links = ConcurrentHashMap<String, HatLink>()

    // Retained link management state per discovered Node ID
    private val nodeContexts = ConcurrentHashMap<String, DiscoveredNodeLinkContext>()

    private val _activeLinks = MutableStateFlow<List<HatLink>>(emptyList())
    val activeLinks: StateFlow<List<HatLink>> = _activeLinks.asStateFlow()

    val activeStreams: StateFlow<List<HatStream>> = HatSessionManager.activeStreams

    private val _trackedNodeContexts = MutableStateFlow<List<DiscoveredNodeLinkContext>>(emptyList())
    val trackedNodeContexts: StateFlow<List<DiscoveredNodeLinkContext>> = _trackedNodeContexts.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────────
    // Candidate Transport Management
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sets or updates candidate audio transports for the given remote node.
     * Enforces that all candidates are high-bandwidth audio transports (LAN, WIFI_DIRECT).
     * BLE and NFC are rejected.
     */
    fun setNodeCandidates(remoteNode: NodeInfo, candidates: List<TransportCandidate>) {
        candidates.forEach {
            require(it.type.isAudioTransport) {
                "Transport ${it.type} is not an audio transport. Only high-bandwidth transports (LAN, WIFI_DIRECT) can be candidates."
            }
        }
        val context = nodeContexts.computeIfAbsent(remoteNode.id) {
            DiscoveredNodeLinkContext(remoteNode = remoteNode)
        }
        synchronized(lock) {
            context.candidateTransports.clear()
            context.candidateTransports.addAll(candidates)
            context.lastStateChangeEpochMs = System.currentTimeMillis()
            _trackedNodeContexts.value = nodeContexts.values.toList()
        }
        Log.i(TAG, "Set ${candidates.size} candidate transport(s) for node ${remoteNode.id}")
    }

    /**
     * Extracts high-bandwidth audio transport candidates from a unified [DiscoveredNodeEntry].
     * Ignores BLE and NFC bootstrap sources for audio transport candidacy.
     */
    fun updateCandidatesFromDiscoveredNode(entry: DiscoveredNodeEntry) {
        val context = nodeContexts.computeIfAbsent(entry.id) {
            DiscoveredNodeLinkContext(remoteNode = entry.nodeInfo)
        }

        synchronized(lock) {
            val candidateList = mutableListOf<TransportCandidate>()

            // 1. Wi-Fi Direct
            val wifiDirectEp = entry.getWifiDirectEndpoint()
            if (wifiDirectEp != null || entry.hasTransport(NodeTransportType.WIFI_DIRECT)) {
                val availability = when {
                    wifiDirectEp?.address != null -> TransportAvailability.AVAILABLE
                    entry.hasTransport(NodeTransportType.WIFI_DIRECT) -> TransportAvailability.PROBING
                    else -> TransportAvailability.UNAVAILABLE
                }
                candidateList.add(
                    TransportCandidate(
                        type = HatTransportType.WIFI_DIRECT,
                        availability = availability,
                        endpointAddress = wifiDirectEp?.address,
                        endpointPort = wifiDirectEp?.port ?: AudioConfig.DEFAULT_PORT,
                        isDirect = true,
                        lastVerifiedEpochMs = wifiDirectEp?.lastSeenEpochMs ?: entry.lastSeenEpochMs,
                        details = wifiDirectEp?.details ?: emptyMap()
                    )
                )
            }

            // 2. LAN
            val lanEp = entry.getLanEndpoint()
            if (lanEp != null || entry.hasTransport(NodeTransportType.LOCAL_WIFI)) {
                val isLanAvail = com.example.audiostreamer.NetworkUtils.isLanAvailable()
                val availability = when {
                    !isLanAvail -> TransportAvailability.UNAVAILABLE
                    lanEp?.address != null -> TransportAvailability.AVAILABLE
                    entry.hasTransport(NodeTransportType.LOCAL_WIFI) -> TransportAvailability.PROBING
                    else -> TransportAvailability.UNAVAILABLE
                }
                candidateList.add(
                    TransportCandidate(
                        type = HatTransportType.LAN,
                        availability = availability,
                        endpointAddress = lanEp?.address,
                        endpointPort = lanEp?.port ?: AudioConfig.DEFAULT_PORT,
                        isDirect = false,
                        lastVerifiedEpochMs = lanEp?.lastSeenEpochMs ?: entry.lastSeenEpochMs,
                        details = lanEp?.details ?: emptyMap()
                    )
                )
            }

            // Retain previous FAILED state if address hasn't changed
            val existingMap = context.candidateTransports.associateBy { it.type }
            val mergedCandidates = candidateList.map { newCand ->
                val prev = existingMap[newCand.type]
                if (prev != null && prev.availability == TransportAvailability.FAILED && prev.endpointAddress == newCand.endpointAddress) {
                    newCand.copy(availability = TransportAvailability.FAILED, failureReason = prev.failureReason)
                } else {
                    newCand
                }
            }

            context.candidateTransports.clear()
            context.candidateTransports.addAll(mergedCandidates)

            // Link state sync
            val active = links.values.firstOrNull { it.remoteNode.id == entry.id && it.isAlive }
            if (active != null) {
                context.activeLink = active
                context.lastSelectedTransport = active.activeTransportType
            }
            context.lastStateChangeEpochMs = System.currentTimeMillis()
            _trackedNodeContexts.value = nodeContexts.values.toList()
        }
    }

    fun getCandidatesForNode(nodeId: String): List<TransportCandidate> {
        val context = nodeContexts[nodeId] ?: return emptyList()
        synchronized(lock) {
            return context.candidateTransports.toList()
        }
    }

    fun getNodeLinkContext(nodeId: String): DiscoveredNodeLinkContext? = nodeContexts[nodeId]

    fun getAllNodeLinkContexts(): List<DiscoveredNodeLinkContext> = nodeContexts.values.toList()

    // ─────────────────────────────────────────────────────────────────────────
    // Existing Core Link & Stream APIs
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Gets an existing link or creates a new one to the specified remote Node and address.
     * Inherently bidirectional (canSend = true, canReceive = true).
     */
    fun getOrCreateLink(
        remoteNode: NodeInfo,
        transportType: NodeTransportType = NodeTransportType.LOCAL_WIFI,
        remoteAddress: String,
        remotePort: Int,
        isDirectP2p: Boolean = false,
        localNode: NodeInfo = LocalNodeManager.getLocalNode(),
        transport: com.example.audiostreamer.node.transport.HatTransport? = null
    ): HatLink {
        synchronized(lock) {
            val effectiveTransport = transport ?: HatTransportRegistry.selectBestAudioTransport(remoteAddress)
            // Check for existing link by remote node ID or by address:port
            val existing = links.values.firstOrNull { link ->
                (link.remoteNode.id == remoteNode.id) ||
                (link.metadata.remoteAddress == remoteAddress && link.metadata.remotePort == remotePort)
            }

            if (existing != null) {
                val updated = existing.copy(
                    state = LinkState.CONNECTED,
                    lastActiveEpochMs = System.currentTimeMillis(),
                    transport = existing.transport ?: effectiveTransport,
                    metadata = existing.metadata.copy(
                        remoteAddress = remoteAddress,
                        remotePort = remotePort,
                        isDirectP2p = isDirectP2p
                    )
                )
                links[existing.id] = updated
                updateNodeContextActiveLink(remoteNode.id, updated)
                publishState()
                return updated
            }

            val newLink = HatLink(
                localNode = localNode,
                remoteNode = remoteNode,
                transportType = transportType,
                state = LinkState.CONNECTED,
                canSend = true,
                canReceive = true,
                metadata = LinkMetadata(
                    remoteAddress = remoteAddress,
                    remotePort = remotePort,
                    isDirectP2p = isDirectP2p
                ),
                establishedEpochMs = System.currentTimeMillis(),
                lastActiveEpochMs = System.currentTimeMillis(),
                transport = effectiveTransport
            )

            links[newLink.id] = newLink
            updateNodeContextActiveLink(remoteNode.id, newLink)
            publishState()
            Log.i(TAG, "Created bidirectional link: ${newLink.describe()}")
            return newLink
        }
    }

    private fun updateNodeContextActiveLink(nodeId: String, link: HatLink) {
        val context = nodeContexts[nodeId]
        if (context != null) {
            context.activeLink = link
            context.lastSelectedTransport = link.activeTransportType
            context.lastStateChangeEpochMs = System.currentTimeMillis()
        }
    }

    /**
     * Creates an application-level [HatStream] over an existing [HatLink].
     * Either Node can establish a stream over the link.
     */
    fun createStream(
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
        val link = links[linkId] ?: throw IllegalArgumentException("Cannot create stream: Link $linkId does not exist")
        return HatSessionManager.createOrStartStream(
            linkId = linkId,
            streamType = streamType,
            direction = direction,
            generation = generation,
            sourceNode = sourceNode,
            destinationNode = destinationNode,
            audioFormat = audioFormat,
            codec = codec,
            streamId = streamId
        )
    }

    /**
     * Requests a new stream over the specified link in [StreamLifecycleState.REQUEST] state.
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
    ): Result<HatStream> = HatSessionManager.requestStream(
        linkId, streamType, direction, generation, sourceNode, destinationNode, audioFormat, codec, streamId
    )

    fun acceptStream(
        streamId: String,
        negotiatedFormat: AudioFormatConfig? = null,
        negotiatedCodec: AudioCodec? = null
    ): Result<HatStream> = HatSessionManager.acceptStream(streamId, negotiatedFormat, negotiatedCodec)

    fun rejectStream(streamId: String, reason: String): Result<HatStream> =
        HatSessionManager.rejectStream(streamId, reason)

    fun startStream(streamId: String): Result<HatStream> =
        HatSessionManager.startStream(streamId)

    fun pauseStream(streamId: String): Result<HatStream> =
        HatSessionManager.pauseStream(streamId)

    fun stopStream(streamId: String): Result<HatStream> =
        HatSessionManager.stopStream(streamId)

    /**
     * Retrieves all active streams associated with a link.
     */
    fun getStreamsForLink(linkId: String): List<HatStream> {
        return HatSessionManager.getStreamsForLink(linkId)
    }

    /**
     * Updates link activity timestamp and throughput counters upon receiving or sending packets.
     */
    fun recordLinkActivity(
        remoteAddress: String,
        remotePort: Int,
        packetsIncrement: Long = 1L,
        bytesIncrement: Long = 0L,
        isRx: Boolean = true
    ) {
        val link = links.values.firstOrNull { it.metadata.remoteAddress == remoteAddress && it.metadata.remotePort == remotePort }
            ?: links.values.firstOrNull { it.metadata.remoteAddress == remoteAddress }
            ?: return

        synchronized(lock) {
            val currentMeta = link.metadata
            val updatedMeta = if (isRx) {
                currentMeta.copy(
                    packetsReceived = currentMeta.packetsReceived + packetsIncrement,
                    bytesReceived = currentMeta.bytesReceived + bytesIncrement
                )
            } else {
                currentMeta.copy(
                    packetsSent = currentMeta.packetsSent + packetsIncrement,
                    bytesSent = currentMeta.bytesSent + bytesIncrement
                )
            }

            links[link.id] = link.copy(
                lastActiveEpochMs = System.currentTimeMillis(),
                metadata = updatedMeta
            )
            val direction = if (isRx) StreamDirection.INBOUND else StreamDirection.OUTBOUND
            HatSessionManager.recordStreamActivityForLink(
                linkId = link.id,
                direction = direction,
                packetsIncrement = packetsIncrement,
                bytesIncrement = bytesIncrement
            )
            publishState()
        }
    }

    /**
     * Records negotiated capabilities on the specified link.
     */
    fun recordNegotiatedCapabilities(linkId: String, capabilities: NegotiatedNodeCapabilities) {
        synchronized(lock) {
            val link = links[linkId] ?: return
            links[linkId] = link.copy(negotiatedCapabilities = capabilities)
            publishState()
            Log.i(TAG, "Recorded negotiated capabilities for link $linkId: ${capabilities.summary()}")
        }
    }

    /**
     * Records negotiated capabilities on all links matching the remote node ID or remote address.
     */
    fun recordNegotiatedCapabilitiesForRemote(remoteNodeIdOrAddress: String, capabilities: NegotiatedNodeCapabilities) {
        synchronized(lock) {
            val matching = links.values.filter {
                it.remoteNode.id == remoteNodeIdOrAddress || it.metadata.remoteAddress == remoteNodeIdOrAddress
            }
            for (l in matching) {
                links[l.id] = l.copy(negotiatedCapabilities = capabilities)
            }
            if (matching.isNotEmpty()) {
                publishState()
                Log.i(TAG, "Recorded negotiated capabilities for remote $remoteNodeIdOrAddress on ${matching.size} link(s)")
            }
        }
    }

    /**
     * Closes an active stream. The parent [HatLink] remains active for other streams.
     */
    fun closeStream(streamId: String) {
        HatSessionManager.stopStream(streamId)
        HatSessionManager.closeStream(streamId)
    }

    /**
     * Closes all streams associated with a link and terminates the link.
     * Retains the discovered node link context and attempt history.
     */
    fun closeLink(linkId: String) {
        synchronized(lock) {
            val link = links.remove(linkId)
            if (link != null) {
                HatSessionManager.closeStreamsForLink(linkId)
                val context = nodeContexts[link.remoteNode.id]
                if (context != null) {
                    context.activeLink = link.copy(state = LinkState.DISCONNECTED)
                    context.lastStateChangeEpochMs = System.currentTimeMillis()
                }
                publishState()
                Log.i(TAG, "Terminated link $linkId and closed associated stream(s)")
            }
        }
    }

    fun closeLinkByRemoteAddress(remoteAddress: String) {
        synchronized(lock) {
            val matching = links.values.filter { it.metadata.remoteAddress == remoteAddress }
            for (l in matching) {
                closeLink(l.id)
            }
        }
    }

    /**
     * Prunes inactive links whose heartbeat age exceeds [timeoutMs].
     */
    fun pruneInactiveLinks(timeoutMs: Long = 10_000L): List<HatLink> {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val expired = links.values.filter { (now - it.lastActiveEpochMs) > timeoutMs }
            for (l in expired) {
                closeLink(l.id)
            }
            return expired
        }
    }

    data class BootstrappedPeer(
        val remoteNode: NodeInfo,
        val preferredTransport: NodeTransportType,
        val sessionToken: String? = null,
        val remoteAddress: String? = null,
        val remotePort: Int? = null,
        val bootstrappedAtEpochMs: Long = System.currentTimeMillis()
    )

    private val bootstrappedPeers = ConcurrentHashMap<String, BootstrappedPeer>()

    private val _bootstrappedNodes = MutableStateFlow<List<BootstrappedPeer>>(emptyList())
    val bootstrappedNodes: StateFlow<List<BootstrappedPeer>> = _bootstrappedNodes.asStateFlow()

    /**
     * Records a peer that was bootstrapped via an out-of-band mechanism (such as NFC tap).
     */
    fun recordBootstrappedNode(
        remoteNode: NodeInfo,
        preferredTransport: NodeTransportType,
        sessionToken: String? = null,
        remoteAddress: String? = null,
        remotePort: Int? = null
    ): BootstrappedPeer {
        synchronized(lock) {
            val peer = BootstrappedPeer(
                remoteNode = remoteNode,
                preferredTransport = preferredTransport,
                sessionToken = sessionToken,
                remoteAddress = remoteAddress,
                remotePort = remotePort,
                bootstrappedAtEpochMs = System.currentTimeMillis()
            )
            bootstrappedPeers[remoteNode.id] = peer
            _bootstrappedNodes.value = bootstrappedPeers.values.toList()
            Log.i(TAG, "Recorded bootstrapped peer: ${remoteNode.id} (${remoteNode.name}) transport=$preferredTransport")
            return peer
        }
    }

    fun getBootstrappedPeer(nodeId: String): BootstrappedPeer? = bootstrappedPeers[nodeId]

    /**
     * Clears all links, streams, bootstrapped peers, and node contexts.
     */
    fun clear() {
        synchronized(lock) {
            links.clear()
            HatSessionManager.clear()
            bootstrappedPeers.clear()
            nodeContexts.clear()
            _bootstrappedNodes.value = emptyList()
            _trackedNodeContexts.value = emptyList()
            publishState()
            Log.i(TAG, "Cleared all active links, streams, bootstrapped peers, and node contexts")
        }
    }

    private fun publishState() {
        _activeLinks.value = links.values.toList()
        _trackedNodeContexts.value = nodeContexts.values.toList()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Diagnostics Snapshot
    // ─────────────────────────────────────────────────────────────────────────

    fun getDiagnosticsSnapshot(): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>()
        val activeLinksList = links.values.toList()
        val activeStreamsList = HatSessionManager.activeStreams.value
        val contextsList = nodeContexts.values.toList()

        map["activeLinksCount"] = activeLinksList.size
        map["activeStreamsCount"] = activeStreamsList.size
        map["trackedNodesCount"] = contextsList.size

        val linksInfo = activeLinksList.map { link ->
            linkedMapOf(
                "linkId" to link.id,
                "remoteNodeId" to link.remoteNode.id,
                "remoteNodeName" to link.remoteNode.name,
                "activeTransport" to link.activeTransportType.name,
                "state" to link.state.name,
                "endpoint" to "${link.metadata.remoteAddress}:${link.metadata.remotePort}",
                "packetsSent" to link.metadata.packetsSent,
                "packetsReceived" to link.metadata.packetsReceived,
                "bytesSent" to link.metadata.bytesSent,
                "bytesReceived" to link.metadata.bytesReceived
            )
        }
        map["activeLinks"] = linksInfo

        val nodesInfo = contextsList.map { ctx ->
            linkedMapOf(
                "nodeId" to ctx.remoteNode.id,
                "nodeName" to ctx.remoteNode.name,
                "hasActiveLink" to (ctx.activeLink != null && ctx.activeLink!!.isAlive),
                "lastSelectedTransport" to ctx.lastSelectedTransport?.name,
                "candidates" to ctx.candidateTransports.map { cand ->
                    linkedMapOf(
                        "type" to cand.type.name,
                        "availability" to cand.availability.name,
                        "endpoint" to "${cand.endpointAddress}:${cand.endpointPort}",
                        "failureReason" to cand.failureReason
                    )
                }
            )
        }
        map["trackedNodes"] = nodesInfo

        return map
    }
}
