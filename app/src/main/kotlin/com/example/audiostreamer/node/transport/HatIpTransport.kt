package com.example.audiostreamer.node.transport

import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * IP-based high-bandwidth audio transport abstraction.
 *
 * Unifies normal Wi-Fi LAN and Wi-Fi Direct.
 * The Link layer exposes the same interface regardless of the underlying IP vector.
 */
interface HatIpTransport : HatTransport {
    /**
     * The active UDP datagram socket managed by this transport, or null if closed/unbound.
     */
    val socket: DatagramSocket?

    /**
     * Transmits a datagram packet over this transport.
     */
    fun send(packet: DatagramPacket)

    /**
     * Receives a datagram packet from this transport.
     */
    fun receive(packet: DatagramPacket)

    /**
     * Transmits a datagram packet via broadcast.
     */
    fun broadcast(packet: DatagramPacket)
}
