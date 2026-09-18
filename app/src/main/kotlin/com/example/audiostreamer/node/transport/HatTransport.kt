package com.example.audiostreamer.node.transport

/**
 * Generic, transport-independent interface for HAT communication backends.
 *
 * Requirements:
 * - The Link layer interacts with [HatTransport] uniformly without needing
 *   to know whether the underlying IP network is LAN, Wi-Fi Direct, or Wi-Fi Aware.
 * - BLE and NFC are non-audio bootstrap/discovery mechanisms.
 */
interface HatTransport : AutoCloseable {
    val type: HatTransportType
    val isAvailable: Boolean
    val state: TransportState
    val localAddress: TransportAddress?
    val remoteAddress: TransportAddress?

    /**
     * Connects or binds transport to communicate with the target remote address.
     */
    fun connect(remote: TransportAddress): Result<Unit>

    /**
     * Listens or binds transport locally on the specified port.
     */
    fun listen(port: Int): Result<Unit>

    /**
     * Closes the transport and releases underlying sockets or hardware channels.
     */
    override fun close()

    /**
     * Returns diagnostics metadata for this transport instance.
     */
    fun getDiagnostics(): Map<String, Any?>
}
