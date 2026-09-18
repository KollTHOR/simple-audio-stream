package com.example.audiostreamer.node.transport

import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Transport-independent endpoint specification (IP host/interface + port, or hardware identifier).
 */
data class TransportAddress(
    val host: String,
    val port: Int = 0
) {
    val isDirectP2p: Boolean
        get() = host.startsWith("192.168.49.")

    fun toInetSocketAddress(): InetSocketAddress =
        if (port > 0) InetSocketAddress(host, port) else InetSocketAddress(host, 0)

    fun toInetAddress(): InetAddress = InetAddress.getByName(host)

    override fun toString(): String = if (port > 0) "$host:$port" else host

    companion object {
        val ANY = TransportAddress("0.0.0.0", 0)

        fun parse(endpoint: String): TransportAddress {
            val lastColon = endpoint.lastIndexOf(':')
            return if (lastColon > 0 && lastColon < endpoint.length - 1) {
                val hostPart = endpoint.substring(0, lastColon).removePrefix("[").removeSuffix("]")
                val portPart = endpoint.substring(lastColon + 1).toIntOrNull() ?: 0
                TransportAddress(hostPart, portPart)
            } else {
                TransportAddress(endpoint, 0)
            }
        }
    }
}
