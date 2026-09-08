package com.example.audiostreamer

import org.json.JSONObject
import java.util.UUID

enum class ConnectionType {
    LOCAL_WIFI,      // Connected through same Wi-Fi router / Mobile Hotspot
    WIFI_DIRECT,     // Autonomous Wi-Fi Direct P2P (No router / hotspot)
    MULTI_UNICAST    // Multi-receiver group
}

data class ConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val targetIp: String,
    val port: Int = AudioConfig.DEFAULT_PORT,
    val connectionType: ConnectionType = ConnectionType.LOCAL_WIFI,
    val preferredStreamingProfile: String = AudioConfig.PROFILE_AUTO,
    val macOrP2pAddress: String? = null,
    val capabilitiesMask: Int = 0,
    val isFavorite: Boolean = false,
    val lastConnectedTimeMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("targetIp", targetIp)
            put("port", port)
            put("connectionType", connectionType.name)
            put("preferredStreamingProfile", preferredStreamingProfile)
            put("macOrP2pAddress", macOrP2pAddress ?: "")
            put("capabilitiesMask", capabilitiesMask)
            put("isFavorite", isFavorite)
            put("lastConnectedTimeMs", lastConnectedTimeMs)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): ConnectionProfile {
            val typeStr = json.optString("connectionType", ConnectionType.LOCAL_WIFI.name)
            val connType = try {
                ConnectionType.valueOf(typeStr)
            } catch (e: Exception) {
                ConnectionType.LOCAL_WIFI
            }
            return ConnectionProfile(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", "Audio Receiver"),
                targetIp = json.optString("targetIp", "192.168.1.255"),
                port = json.optInt("port", AudioConfig.DEFAULT_PORT),
                connectionType = connType,
                preferredStreamingProfile = json.optString("preferredStreamingProfile", AudioConfig.PROFILE_AUTO),
                macOrP2pAddress = json.optString("macOrP2pAddress").takeIf { it.isNotEmpty() },
                capabilitiesMask = json.optInt("capabilitiesMask", 0),
                isFavorite = json.optBoolean("isFavorite", false),
                lastConnectedTimeMs = json.optLong("lastConnectedTimeMs", System.currentTimeMillis())
            )
        }
    }
}
