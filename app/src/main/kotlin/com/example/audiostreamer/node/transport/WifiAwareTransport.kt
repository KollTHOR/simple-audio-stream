package com.example.audiostreamer.node.transport

import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Placeholder for future Wi-Fi Aware (Neighbor Awareness Networking - NAN) IP transport backend.
 *
 * Requirements:
 * - Do not implement Wi-Fi Aware yet.
 * - Conforms to [HatIpTransport] so Link layer will treat it identically when implemented.
 */
class WifiAwareTransport : HatIpTransport {
    override val type: HatTransportType = HatTransportType.WIFI_AWARE
    override val isAvailable: Boolean = false
    override val state: TransportState = TransportState.UNAVAILABLE
    override val localAddress: TransportAddress? = null
    override val remoteAddress: TransportAddress? = null
    override val socket: DatagramSocket? = null

    override fun connect(remote: TransportAddress): Result<Unit> =
        Result.failure(UnsupportedOperationException("Wi-Fi Aware transport is not implemented yet"))

    override fun listen(port: Int): Result<Unit> =
        Result.failure(UnsupportedOperationException("Wi-Fi Aware transport is not implemented yet"))

    override fun send(packet: DatagramPacket) {
        throw UnsupportedOperationException("Wi-Fi Aware transport is not implemented yet")
    }

    override fun receive(packet: DatagramPacket) {
        throw UnsupportedOperationException("Wi-Fi Aware transport is not implemented yet")
    }

    override fun broadcast(packet: DatagramPacket) {
        throw UnsupportedOperationException("Wi-Fi Aware transport is not implemented yet")
    }

    override fun close() {}

    override fun getDiagnostics(): Map<String, Any?> = linkedMapOf(
        "type" to type.name,
        "isAudioTransport" to type.isAudioTransport,
        "isAvailable" to false,
        "state" to state.name,
        "status" to "NOT_IMPLEMENTED"
    )
}
