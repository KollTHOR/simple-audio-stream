package com.example.audiostreamer.node

import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Compact, versioned capability exchange payload transferred during the HAT connection handshake.
 *
 * Requirements:
 * - Exchanged: Node identity, protocol version, node name, capabilities (transports,
 *   audio inputs, audio outputs, codecs, sample rates, channels, PCM formats).
 * - Compact wire representation (~150-250 bytes JSON payload).
 * - No unnecessary Android/device metadata (no build IDs, OS versions, manufacturer codes).
 * - Node identity is a stable logical ID (never IP or MAC address).
 * - Unknown future fields safely ignored.
 * - Missing optional fields handled with safe defaults.
 */
data class NodeCapabilityExchange(
    val version: Int = CURRENT_SCHEMA_VERSION,
    val nodeId: String,
    val nodeName: String,
    val protocolVersion: Int,
    val capabilities: NodeCapabilities,
    val extraAttributes: Map<String, String> = emptyMap()
) {
    init {
        require(nodeId.isNotBlank()) { "Node ID cannot be blank" }
        require(!NodeIdentity.isIpOrMac(nodeId)) { "Node ID cannot be an IP or MAC address" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        fun fromNode(node: NodeInfo): NodeCapabilityExchange = NodeCapabilityExchange(
            version = CURRENT_SCHEMA_VERSION,
            nodeId = node.id,
            nodeName = node.name,
            protocolVersion = node.capabilities.protocolVersion,
            capabilities = node.capabilities
        )

        fun fromJson(json: JSONObject): NodeCapabilityExchange {
            val schemaVersion = json.optInt("v", CURRENT_SCHEMA_VERSION)
            val id = json.getString("id")
            val name = json.optString("name", "Unknown Node")
            val protoVer = json.optInt("pv", 1)

            val caps = if (json.has("caps")) {
                NodeCapabilities.fromJson(json.getJSONObject("caps"))
            } else {
                NodeCapabilities()
            }

            val extras = mutableMapOf<String, String>()
            val extrasJson = json.optJSONObject("extras")
            if (extrasJson != null) {
                val keys = extrasJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    extras[key] = extrasJson.optString(key)
                }
            }

            return NodeCapabilityExchange(
                version = schemaVersion,
                nodeId = id,
                nodeName = name,
                protocolVersion = protoVer,
                capabilities = caps,
                extraAttributes = extras
            )
        }

        fun parseOrNull(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): NodeCapabilityExchange? {
            return try {
                if (length <= 0 || offset + length > bytes.size) return null
                val str = String(bytes, offset, length, StandardCharsets.UTF_8).trim()
                if (!str.startsWith("{") || !str.endsWith("}")) return null
                fromJson(JSONObject(str))
            } catch (e: Exception) {
                null
            }
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", version)
        put("id", nodeId)
        put("name", nodeName)
        put("pv", protocolVersion)
        put("caps", capabilities.toJson())
        if (extraAttributes.isNotEmpty()) {
            put("extras", JSONObject(extraAttributes))
        }
    }

    fun toByteArray(): ByteArray = toJson().toString().toByteArray(StandardCharsets.UTF_8)

    fun toNodeInfo(state: NodeState = NodeState.ACTIVE_STREAMING, role: StreamRole = StreamRole.IDLE): NodeInfo {
        return NodeInfo(
            identity = NodeIdentity(id = nodeId, name = nodeName),
            capabilities = capabilities,
            state = state,
            activeRole = role
        )
    }
}
