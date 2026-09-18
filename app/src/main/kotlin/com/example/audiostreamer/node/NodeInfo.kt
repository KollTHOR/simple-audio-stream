package com.example.audiostreamer.node

import android.os.Build
import org.json.JSONObject

/**
 * Current runtime lifecycle availability of a HAT Node.
 */
enum class NodeState {
    IDLE,
    AVAILABLE,
    CONNECTING,
    ACTIVE_STREAMING,
    OFFLINE
}

/**
 * Dynamic streaming role during a streaming session.
 *
 * NOTE: In the HAT Node Architecture, a node does NOT have a permanent transmitter or receiver
 * role. Any node can send or receive streams depending on the negotiated stream.
 */
enum class StreamRole {
    IDLE,
    SENDER,
    RECEIVER,
    TRANSCEIVER
}

/**
 * Device and platform hardware/OS information.
 */
data class DevicePlatformInfo(
    val manufacturer: String = Build.MANUFACTURER ?: "Unknown",
    val model: String = Build.MODEL ?: "Unknown",
    val osName: String = "Android",
    val osVersion: String = Build.VERSION.RELEASE ?: "",
    val apiLevel: Int = Build.VERSION.SDK_INT,
    val appVersion: String = "1.0"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("manufacturer", manufacturer)
        put("model", model)
        put("osName", osName)
        put("osVersion", osVersion)
        put("apiLevel", apiLevel)
        put("appVersion", appVersion)
    }

    companion object {
        fun fromJson(json: JSONObject): DevicePlatformInfo = DevicePlatformInfo(
            manufacturer = json.optString("manufacturer", "Unknown"),
            model = json.optString("model", "Unknown"),
            osName = json.optString("osName", "Android"),
            osVersion = json.optString("osVersion", ""),
            apiLevel = json.optInt("apiLevel", 0),
            appVersion = json.optString("appVersion", "1.0")
        )
    }
}

/**
 * Full state representation of a HAT Node, binding its identity, capabilities,
 * platform info, and current dynamic streaming role.
 */
data class NodeInfo(
    val identity: NodeIdentity,
    val capabilities: NodeCapabilities,
    val deviceInfo: DevicePlatformInfo = DevicePlatformInfo(),
    val state: NodeState = NodeState.AVAILABLE,
    val activeRole: StreamRole = StreamRole.IDLE,
    val activeStreamGeneration: Long = 0L,
    val lastSeenEpochMs: Long = System.currentTimeMillis()
) {
    val id: String get() = identity.id
    val name: String get() = identity.name

    fun canSend(): Boolean = capabilities.hasAudioInput
    fun canReceive(): Boolean = capabilities.hasAudioOutput
    fun isStreaming(): Boolean = state == NodeState.ACTIVE_STREAMING

    fun toJson(): JSONObject = JSONObject().apply {
        put("identity", identity.toJson())
        put("capabilities", capabilities.toJson())
        put("deviceInfo", deviceInfo.toJson())
        put("state", state.name)
        put("activeRole", activeRole.name)
        put("activeStreamGeneration", activeStreamGeneration)
        put("lastSeenEpochMs", lastSeenEpochMs)
    }

    companion object {
        fun fromJson(json: JSONObject): NodeInfo {
            val identity = NodeIdentity.fromJson(json.getJSONObject("identity"))
            val capabilities = NodeCapabilities.fromJson(json.getJSONObject("capabilities"))
            val deviceInfo = if (json.has("deviceInfo")) {
                DevicePlatformInfo.fromJson(json.getJSONObject("deviceInfo"))
            } else DevicePlatformInfo()

            val state = try {
                NodeState.valueOf(json.optString("state", NodeState.AVAILABLE.name))
            } catch (ignored: Exception) {
                NodeState.AVAILABLE
            }

            val role = try {
                StreamRole.valueOf(json.optString("activeRole", StreamRole.IDLE.name))
            } catch (ignored: Exception) {
                StreamRole.IDLE
            }

            return NodeInfo(
                identity = identity,
                capabilities = capabilities,
                deviceInfo = deviceInfo,
                state = state,
                activeRole = role,
                activeStreamGeneration = json.optLong("activeStreamGeneration", 0L),
                lastSeenEpochMs = json.optLong("lastSeenEpochMs", System.currentTimeMillis())
            )
        }
    }
}
