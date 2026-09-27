package com.example.audiostreamer

/**
 * decides whether an alert is allowed through, without any Android dependency so it can be
 * unit-tested. Two rules fight two real failure modes:
 *
 * - A retrying subsystem (socket send loop, NSD resolve) re-emits the *same* error every few
 *   hundred milliseconds. The per-message window collapses that into one alert.
 * - A flapping link alternates between *different* messages. The global floor keeps the UI from
 *   becoming a strobe light while still letting the logcat record everything.
 */
class AlertThrottle(
    private val sameMessageWindowMs: Long = DEFAULT_SAME_MESSAGE_WINDOW_MS,
    private val globalFloorMs: Long = DEFAULT_GLOBAL_FLOOR_MS
) {
    enum class Verdict { ALLOW, DUPLICATE_SUPPRESSED, RATE_LIMITED }

    private val lastSeenPerMessage = HashMap<String, Long>()
    private var lastAllowedMs = Long.MIN_VALUE

    /**
     * Records an alert attempt at [nowMs] and returns whether it should reach the user.
     * [key] should be severity + message text.
     *
     * A RATE_LIMITED result does not mark the message as seen: it was never shown, so it must be
     * eligible again as soon as the floor expires. Otherwise a burst of distinct failures could
     * silently swallow every one of them.
     */
    fun accept(key: String, nowMs: Long): Verdict {
        val lastForMessage = lastSeenPerMessage[key]
        if (lastForMessage != null && nowMs - lastForMessage < sameMessageWindowMs) {
            lastSeenPerMessage[key] = nowMs
            return Verdict.DUPLICATE_SUPPRESSED
        }
        if (lastAllowedMs != Long.MIN_VALUE && nowMs - lastAllowedMs < globalFloorMs) {
            return Verdict.RATE_LIMITED
        }
        lastSeenPerMessage[key] = nowMs
        lastAllowedMs = nowMs
        if (lastSeenPerMessage.size > MAX_TRACKED) {
            lastSeenPerMessage.clear()
            lastSeenPerMessage[key] = nowMs
        }
        return Verdict.ALLOW
    }

    fun reset() {
        lastSeenPerMessage.clear()
        lastAllowedMs = Long.MIN_VALUE
    }

    companion object {
        const val DEFAULT_SAME_MESSAGE_WINDOW_MS = 8_000L
        const val DEFAULT_GLOBAL_FLOOR_MS = 1_200L
        private const val MAX_TRACKED = 128
    }
}
