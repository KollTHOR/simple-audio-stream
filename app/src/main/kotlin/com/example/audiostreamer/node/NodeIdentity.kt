package com.example.audiostreamer.node

import org.json.JSONObject
import java.util.UUID

/**
 * Stable, unique identity for a High-definition Audio Transport (HAT) node.
 *
 * Invariants:
 * - The node ID is generated once and persisted locally.
 * - IP addresses and Wi-Fi Direct MAC addresses are transport endpoints, NEVER permanent node identities.
 * - The identity survives network topology changes, IP re-assignments, and P2P group re-creations.
 */
data class NodeIdentity(
    val id: String,
    val name: String
) {
    init {
        require(id.isNotBlank()) { "Node ID cannot be blank" }
        require(name.isNotBlank()) { "Node name cannot be blank" }
        require(!isIp(id)) {
            "IP addresses cannot be used as permanent NodeIdentity"
        }
        require(!isMac(id)) {
            "MAC addresses cannot be used as permanent NodeIdentity"
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
    }

    companion object {
        const val ID_PREFIX = "hat-node-"

        private val IP_V4_REGEX = Regex("^(\\d{1,3}\\.){3}\\d{1,3}(:\\d+)?$")
        private val MAC_REGEX = Regex("^([0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}$")

        fun isMac(id: String): Boolean = id.matches(MAC_REGEX)

        fun isIp(id: String): Boolean {
            if (isMac(id)) return false
            if (id.matches(IP_V4_REGEX)) return true
            if (id == "::" || id == "::1") return true
            val colonCount = id.count { it == ':' }
            if (colonCount in 2..7 && id.all { it.isDigit() || (it in 'a'..'f') || (it in 'A'..'F') || it == ':' }) {
                return true
            }
            return false
        }

        fun isIpOrMac(id: String): Boolean = isIp(id) || isMac(id)

        /**
         * Generates a new cryptographically random node identity.
         */
        fun generate(name: String): NodeIdentity {
            val randomId = "$ID_PREFIX${UUID.randomUUID()}"
            val safeName = name.trim().ifBlank { "HAT Node" }
            return NodeIdentity(id = randomId, name = safeName)
        }

        fun fromJson(json: JSONObject): NodeIdentity {
            val id = json.getString("id")
            val name = json.optString("name", "HAT Node")
            return NodeIdentity(id = id, name = name)
        }
    }
}
