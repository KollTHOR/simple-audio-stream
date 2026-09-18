package com.example.audiostreamer.node.transport

/**
 * Operational lifecycle states for a [HatTransport].
 */
enum class TransportState {
    UNAVAILABLE,
    AVAILABLE,
    LISTENING,
    CONNECTING,
    CONNECTED,
    CLOSED,
    FAILED
}
