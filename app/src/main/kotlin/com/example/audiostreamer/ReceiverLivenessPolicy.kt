package com.example.audiostreamer

/**
 * Decides whether a receiver's claim of "connected to transmitter X" is still believable.
 *
 * The transmitter emits an audio packet, a silence heartbeat (~every 500 ms), or a FEC parity
 * packet at a worst-case cadence of a few hundred milliseconds while a session is live. A gap much
 * larger than that means the sender is gone (crash, Wi-Fi drop, doze) rather than merely idle, so
 * the receiver should stop asserting a connection instead of showing a false "connected" state.
 *
 * This is deliberately a pure function of elapsed time: the sink owns the monotonic clock, and the
 * receiver heartbeat / silence cadence constants live in [AudioConfig].
 */
internal object ReceiverLivenessPolicy {

    /**
     * How long the receiver will keep showing a live connection after the last inbound packet.
     * Must comfortably exceed [AudioConfig.SILENCE_HEARTBEAT_INTERVAL_MS] plus realistic jitter so
     * a genuinely idle-but-alive sender is not falsely flagged.
     */
    const val STALE_AFTER_MS = 6_000L

    /**
     * The receive() poll interval. Smaller than [STALE_AFTER_MS] so staleness is detected close to
     * the threshold, while waking the receive loop rarely enough to be negligible for battery.
     */
    const val RX_POLL_TIMEOUT_MS = 2_000L

    /** True when the connection should be considered lost given ms since the last inbound packet. */
    fun isStale(elapsedSinceLastPacketMs: Long): Boolean =
        elapsedSinceLastPacketMs > STALE_AFTER_MS

    /**
     * True when we should run a liveness check. Skips the very first poll (nothing to age out yet)
     * and any negative/zero delta from a clock reset.
     */
    fun shouldCheck(elapsedSinceLastPacketMs: Long): Boolean =
        elapsedSinceLastPacketMs > 0L
}
