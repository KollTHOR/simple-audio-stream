package com.example.audiostreamer.node

import com.example.audiostreamer.AudioCodec
import com.example.audiostreamer.AudioFormatConfig
import com.example.audiostreamer.ConnectedDevice
import com.example.audiostreamer.DiscoveryManager

/**
 * Adapters bridging the new [HatLink] and [HatStream] abstractions with existing
 * telemetry, service, and UI connection constructs.
 */
object LinkAdapters {

    /**
     * Converts a [HatLink] into a legacy [ConnectedDevice] for UI display and telemetry.
     */
    fun toConnectedDevice(link: HatLink, stream: HatStream? = null): ConnectedDevice {
        val remote = link.remoteNode
        val packetsTotal = (link.metadata.packetsSent + link.metadata.packetsReceived)
        return ConnectedDevice(
            ip = link.metadata.remoteAddress,
            port = link.metadata.remotePort,
            name = remote.name,
            isDirectP2p = link.metadata.isDirectP2p,
            latencyMs = link.metadata.estimatedRttMs.toInt(),
            packetsTransferred = packetsTotal,
            lastSeenMs = link.lastActiveEpochMs,
            nodeId = remote.id
        )
    }

    /**
     * Registers or updates an outbound streaming session from the local node to a remote receiver endpoint.
     * Establishes a bidirectional [HatLink] and creates an outbound [HatStream] over that link.
     */
    fun registerOutboundStream(
        remoteAddress: String,
        remotePort: Int,
        remoteCaps: Int = 0,
        generation: Long = 1L,
        codec: AudioCodec = AudioCodec.PCM,
        format: AudioFormatConfig = AudioFormatConfig(),
        streamType: StreamType = StreamType.MUSIC,
        remoteNode: NodeInfo? = null
    ): Pair<HatLink, HatStream> {
        val friendlyName = DiscoveryManager.getDeviceNameForIp(remoteAddress) ?: "Remote Node ($remoteAddress)"
        val isP2p = remoteAddress.startsWith("192.168.49.")

        // Use provided remoteNode or construct default endpoint NodeInfo
        val effectiveRemoteNode = remoteNode ?: NodeInfo(
            identity = NodeIdentity(id = "${NodeIdentity.ID_PREFIX}ep-${remoteAddress.replace(".", "-")}", name = friendlyName),
            capabilities = NodeCapabilities.fromCapabilitiesMask(mask = remoteCaps),
            state = NodeState.ACTIVE_STREAMING,
            activeRole = StreamRole.RECEIVER
        )

        val link = HatLinkManager.getOrCreateLink(
            remoteNode = effectiveRemoteNode,
            transportType = if (isP2p) NodeTransportType.WIFI_DIRECT else NodeTransportType.LOCAL_WIFI,
            remoteAddress = remoteAddress,
            remotePort = remotePort,
            isDirectP2p = isP2p
        )

        val stream = HatLinkManager.createStream(
            linkId = link.id,
            streamType = streamType,
            direction = StreamDirection.OUTBOUND,
            generation = generation,
            sourceNode = LocalNodeManager.getLocalNode(),
            destinationNode = effectiveRemoteNode,
            audioFormat = format,
            codec = codec
        )

        HatMultiStreamManager.registerDestination(effectiveRemoteNode, link, stream)

        return Pair(link, stream)
    }

    /**
     * Registers or updates an inbound streaming session from a remote sender endpoint to the local node.
     * Establishes a bidirectional [HatLink] and creates an inbound [HatStream] over that link.
     */
    fun registerInboundStream(
        remoteAddress: String,
        remotePort: Int,
        generation: Long = 1L,
        codec: AudioCodec = AudioCodec.PCM,
        format: AudioFormatConfig = AudioFormatConfig(),
        streamType: StreamType = StreamType.MUSIC
    ): Pair<HatLink, HatStream> {
        val friendlyName = DiscoveryManager.getDeviceNameForIp(remoteAddress) ?: "Remote Node ($remoteAddress)"
        val isP2p = remoteAddress.startsWith("192.168.49.")

        val remoteNode = NodeInfo(
            identity = NodeIdentity(id = "${NodeIdentity.ID_PREFIX}ep-${remoteAddress.replace(".", "-")}", name = friendlyName),
            capabilities = NodeCapabilities(),
            state = NodeState.ACTIVE_STREAMING,
            activeRole = StreamRole.SENDER
        )

        val link = HatLinkManager.getOrCreateLink(
            remoteNode = remoteNode,
            transportType = if (isP2p) NodeTransportType.WIFI_DIRECT else NodeTransportType.LOCAL_WIFI,
            remoteAddress = remoteAddress,
            remotePort = remotePort,
            isDirectP2p = isP2p
        )

        val stream = HatLinkManager.createStream(
            linkId = link.id,
            streamType = streamType,
            direction = StreamDirection.INBOUND,
            generation = generation,
            sourceNode = remoteNode,
            destinationNode = LocalNodeManager.getLocalNode(),
            audioFormat = format,
            codec = codec
        )

        return Pair(link, stream)
    }
}

// Extension function on HatLink
fun HatLink.toConnectedDevice(stream: HatStream? = null): ConnectedDevice =
    LinkAdapters.toConnectedDevice(this, stream)
