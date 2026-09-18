package com.example.audiostreamer.node.transport

import com.example.audiostreamer.NetworkUtils
import com.example.audiostreamer.WifiDirectManager

/**
 * Central registry managing available [HatTransport] backends.
 *
 * Requirements:
 * - Link layer accesses IP-based audio transports transparently.
 * - Wraps LAN and Wi-Fi Direct implementations.
 * - Exposes unified diagnostics.
 */
object HatTransportRegistry {
    private val lanTransportInstance = LanTransport()
    private val wifiDirectTransportInstance = WifiDirectTransport()
    private val wifiAwareTransportInstance = WifiAwareTransport()
    private val bleTransportInstance = BleTransport()
    private val nfcBootstrapTransportInstance = NfcBootstrapTransport()

    fun getTransport(type: HatTransportType): HatTransport = when (type) {
        HatTransportType.LAN -> lanTransportInstance
        HatTransportType.WIFI_DIRECT -> wifiDirectTransportInstance
        HatTransportType.WIFI_AWARE -> wifiAwareTransportInstance
        HatTransportType.BLE -> bleTransportInstance
        HatTransportType.NFC_BOOTSTRAP -> nfcBootstrapTransportInstance
    }

    fun getIpTransport(type: HatTransportType): HatIpTransport = when (type) {
        HatTransportType.LAN -> lanTransportInstance
        HatTransportType.WIFI_DIRECT -> wifiDirectTransportInstance
        HatTransportType.WIFI_AWARE -> wifiAwareTransportInstance
        else -> throw IllegalArgumentException("Transport type $type is not an IP audio transport")
    }

    /**
     * Determines and returns the appropriate IP-based audio transport based on the remote address
     * or active Wi-Fi Direct connection status.
     *
     * Invariant: The Link layer does not care whether the IP network is normal LAN,
     * Wi-Fi Direct, or Wi-Fi Aware. It receives a unified [HatIpTransport] interface.
     */
    fun selectBestAudioTransport(remoteAddress: String? = null): HatIpTransport {
        val isDirectP2p = remoteAddress?.startsWith("192.168.49.") == true ||
                (WifiDirectManager.isConnected.value && NetworkUtils.getLocalIpAddress()?.startsWith("192.168.49.") == true)
        return if (isDirectP2p) {
            wifiDirectTransportInstance
        } else {
            lanTransportInstance
        }
    }

    fun allTransports(): List<HatTransport> = listOf(
        lanTransportInstance,
        wifiDirectTransportInstance,
        wifiAwareTransportInstance,
        bleTransportInstance,
        nfcBootstrapTransportInstance
    )

    fun getDiagnosticsSnapshot(): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>()
        allTransports().forEach { transport ->
            map[transport.type.name] = transport.getDiagnostics()
        }
        return map
    }

    /**
     * Closes all active transports.
     */
    fun closeAll() {
        allTransports().forEach { transport ->
            try {
                transport.close()
            } catch (ignored: Exception) {}
        }
    }
}
