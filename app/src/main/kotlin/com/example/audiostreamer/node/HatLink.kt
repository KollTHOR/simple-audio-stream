package com.example.audiostreamer.node

import org.json.JSONObject
import java.util.UUID

/**
 * Lifecycle state of a [HatLink] communication relationship.
 */
enum class LinkState {
    CONNECTING,
    CONNECTED,
    DEGRADED,
    DISCONNECTING,
    DISCONNECTED,
    FAILED
}

/**
 * Transport-level metadata and metrics for an active or historical [HatLink].
 */
data class LinkMetadata(
    val remoteAddress: String,
    val remotePort: Int,
    val localAddress: String? = null,
    val localPort: Int? = null,
    val isDirectP2p: Boolean = false,
    val estimatedRttMs: Float = 0f,
    val packetsSent: Long = 0L,
    val packetsReceived: Long = 0L,
    val bytesSent: Long = 0L,
    val bytesReceived: Long = 0L,
    val extraAttributes: Map<String, String> = emptyMap()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("remoteAddress", remoteAddress)
        put("remotePort", remotePort)
        put("localAddress", localAddress ?: "")
        put("localPort", localPort ?: 0)
        put("isDirectP2p", isDirectP2p)
        put("estimatedRttMs", estimatedRttMs.toDouble())
        put("packetsSent", packetsSent)
        put("packetsReceived", packetsReceived)
        put("bytesSent", bytesSent)
        put("bytesReceived", bytesReceived)
    }

    companion object {
        fun fromJson(json: JSONObject): LinkMetadata = LinkMetadata(
            remoteAddress = json.getString("remoteAddress"),
            remotePort = json.getInt("remotePort"),
            localAddress = json.optString("localAddress").takeIf { it.isNotEmpty() },
            localPort = json.optInt("localPort").takeIf { it > 0 },
            isDirectP2p = json.optBoolean("isDirectP2p", false),
            estimatedRttMs = json.optDouble("estimatedRttMs", 0.0).toFloat(),
            packetsSent = json.optLong("packetsSent", 0L),
            packetsReceived = json.optLong("packetsReceived", 0L),
            bytesSent = json.optLong("bytesSent", 0L),
            bytesReceived = json.optLong("bytesReceived", 0L)
        )
    }
}

/**
 * Represents a persistent, bidirectional communication link between two HAT Nodes.
 *
 * Architecture Invariants:
 * 1. A Link is a communication relationship between two Nodes.
 * 2. A Link is inherently bidirectional: either node can transmit and receive over the link.
 * 3. A Link does NOT have a permanent transmitter or receiver role.
 * 4. Multiple streams may multiplex across a single Link simultaneously (e.g. forward music + reverse mic).
 * 5. Multiple remote Nodes can be connected via separate Links simultaneously.
 */
data class HatLink(
    val id: String = UUID.randomUUID().toString(),
    val localNode: NodeInfo,
    val remoteNode: NodeInfo,
    val transportType: NodeTransportType = NodeTransportType.LOCAL_WIFI,
    val state: LinkState = LinkState.CONNECTED,
    val metadata: LinkMetadata,
    val canSend: Boolean = true,
    val canReceive: Boolean = true,
    val establishedEpochMs: Long = System.currentTimeMillis(),
    val lastActiveEpochMs: Long = System.currentTimeMillis(),
    val negotiatedCapabilities: NegotiatedNodeCapabilities? = null,
    val transport: com.example.audiostreamer.node.transport.HatTransport? = null
) {
    init {
        require(id.isNotBlank()) { "Link ID cannot be blank" }
    }

    val ipTransport: com.example.audiostreamer.node.transport.HatIpTransport?
        get() = transport as? com.example.audiostreamer.node.transport.HatIpTransport

    val hatTransportType: com.example.audiostreamer.node.transport.HatTransportType
        get() = transport?.type ?: com.example.audiostreamer.node.transport.HatTransportType.fromNodeTransportType(transportType)

    val activeTransport: com.example.audiostreamer.node.transport.HatTransport?
        get() = transport

    val activeTransportType: com.example.audiostreamer.node.transport.HatTransportType
        get() = hatTransportType

    fun send(packet: java.net.DatagramPacket) {
        val ip = ipTransport ?: throw IllegalStateException("Link $id has no active IP transport")
        ip.send(packet)
    }

    fun receive(packet: java.net.DatagramPacket) {
        val ip = ipTransport ?: throw IllegalStateException("Link $id has no active IP transport")
        ip.receive(packet)
    }

    val isAlive: Boolean
        get() = state == LinkState.CONNECTED || state == LinkState.CONNECTING || state == LinkState.DEGRADED

    fun describe(): String {
        val negDesc = if (negotiatedCapabilities != null) " [${negotiatedCapabilities.summary()}]" else ""
        return "HatLink[$id]: ${localNode.name} <---> ${remoteNode.name} ($transportType, $state, ${metadata.remoteAddress}:${metadata.remotePort})$negDesc"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("localNode", localNode.toJson())
        put("remoteNode", remoteNode.toJson())
        put("transportType", transportType.name)
        put("state", state.name)
        put("metadata", metadata.toJson())
        put("canSend", canSend)
        put("canReceive", canReceive)
        put("establishedEpochMs", establishedEpochMs)
        put("lastActiveEpochMs", lastActiveEpochMs)
        if (negotiatedCapabilities != null) {
            put("negotiatedCapabilities", negotiatedCapabilities.toJson())
        }
    }

    companion object {
        fun fromJson(json: JSONObject): HatLink = HatLink(
            id = json.getString("id"),
            localNode = NodeInfo.fromJson(json.getJSONObject("localNode")),
            remoteNode = NodeInfo.fromJson(json.getJSONObject("remoteNode")),
            transportType = NodeTransportType.valueOf(json.optString("transportType", NodeTransportType.LOCAL_WIFI.name)),
            state = LinkState.valueOf(json.optString("state", LinkState.CONNECTED.name)),
            metadata = LinkMetadata.fromJson(json.getJSONObject("metadata")),
            canSend = json.optBoolean("canSend", true),
            canReceive = json.optBoolean("canReceive", true),
            establishedEpochMs = json.optLong("establishedEpochMs", System.currentTimeMillis()),
            lastActiveEpochMs = json.optLong("lastActiveEpochMs", System.currentTimeMillis())
        )
    }
}
