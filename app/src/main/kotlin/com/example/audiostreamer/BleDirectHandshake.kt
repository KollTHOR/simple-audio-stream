package com.example.audiostreamer

import org.json.JSONObject

internal data class BleDirectHandshake(
    val deviceName: String,
    val ssid: String,
    val passphrase: String,
    val groupOwnerIp: String,
    val port: Int
)

/** JSON codec for transferring the Wi-Fi Direct endpoint over a short BLE GATT read. */
internal object BleDirectHandshakeCodec {
    private const val VERSION = 1

    fun encode(handshake: BleDirectHandshake): ByteArray = JSONObject().apply {
        put("v", VERSION)
        put("r", "rx")
        put("n", handshake.deviceName)
        put("s", handshake.ssid)
        put("p", handshake.passphrase)
        put("g", handshake.groupOwnerIp)
        put("port", handshake.port)
    }.toString().toByteArray(Charsets.UTF_8)

    fun decode(payload: ByteArray): BleDirectHandshake? {
        return try {
            val json = JSONObject(String(payload, Charsets.UTF_8))
            if (json.optInt("v", -1) != VERSION || json.optString("r") != "rx") return null

            val name = json.optString("n").trim().ifBlank { "Wi-Fi Direct Receiver" }
            val ssid = json.optString("s").trim()
            val passphrase = json.optString("p")
            val groupOwnerIp = json.optString("g").trim()
            val port = json.optInt("port", AudioConfig.DEFAULT_PORT)
            if (!ssid.startsWith("DIRECT-") || ssid.length < 10) return null
            if (passphrase.length !in 8..63) return null
            if (groupOwnerIp.isBlank() || port !in 1024..65535) return null

            BleDirectHandshake(name, ssid, passphrase, groupOwnerIp, port)
        } catch (_: Exception) {
            null
        }
    }
}
