package com.example.audiostreamer.node.transport

import com.example.audiostreamer.node.NodeTransportType

/**
 * High-definition Audio Transport (HAT) transport classifications.
 *
 * Requirements:
 * - LAN and WIFI_DIRECT: High-bandwidth IP-based audio transports.
 * - BLE and NFC_BOOTSTRAP: NOT audio transports. They are discovery/bootstrap mechanisms.
 */
enum class HatTransportType(
    val isAudioTransport: Boolean,
    val isIpBased: Boolean,
    val isBootstrapOnly: Boolean
) {
    LAN(isAudioTransport = true, isIpBased = true, isBootstrapOnly = false),
    WIFI_DIRECT(isAudioTransport = true, isIpBased = true, isBootstrapOnly = false),
    BLE(isAudioTransport = false, isIpBased = false, isBootstrapOnly = true),
    NFC_BOOTSTRAP(isAudioTransport = false, isIpBased = false, isBootstrapOnly = true);

    fun toNodeTransportType(): NodeTransportType = when (this) {
        LAN -> NodeTransportType.LOCAL_WIFI
        WIFI_DIRECT -> NodeTransportType.WIFI_DIRECT
        BLE -> NodeTransportType.BLUETOOTH_LE
        NFC_BOOTSTRAP -> NodeTransportType.NFC
    }

    companion object {
        fun fromNodeTransportType(nodeType: NodeTransportType): HatTransportType = when (nodeType) {
            NodeTransportType.LOCAL_WIFI -> LAN
            NodeTransportType.WIFI_DIRECT -> WIFI_DIRECT
            NodeTransportType.BLUETOOTH_LE -> BLE
            NodeTransportType.NFC -> NFC_BOOTSTRAP
            NodeTransportType.CELLULAR -> LAN
        }
    }
}
