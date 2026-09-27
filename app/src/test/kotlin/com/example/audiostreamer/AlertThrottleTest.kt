package com.example.audiostreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertThrottleTest {

    @Test
    fun firstAlertAlwaysPasses() {
        val throttle = AlertThrottle()
        assertEquals(AlertThrottle.Verdict.ALLOW, throttle.accept("ERROR|boom", 1_000L))
    }

    @Test
    fun identicalMessageInsideWindowIsSuppressed() {
        val throttle = AlertThrottle(sameMessageWindowMs = 8_000, globalFloorMs = 1_200)
        assertEquals(AlertThrottle.Verdict.ALLOW, throttle.accept("ERROR|boom", 1_000L))
        assertEquals(AlertThrottle.Verdict.DUPLICATE_SUPPRESSED, throttle.accept("ERROR|boom", 2_000L))
        assertEquals(AlertThrottle.Verdict.DUPLICATE_SUPPRESSED, throttle.accept("ERROR|boom", 8_999L))
    }

    @Test
    fun identicalMessagePassesAgainAfterWindowExpires() {
        val throttle = AlertThrottle(sameMessageWindowMs = 8_000, globalFloorMs = 1_200)
        assertEquals(AlertThrottle.Verdict.ALLOW, throttle.accept("ERROR|boom", 1_000L))
        throttle.accept("ERROR|boom", 2_000L)
        // Past the 8 s duplicate window (measured from the last *attempt*, which keeps a
        // continuously-failing subsystem merged into one alert until it actually pauses).
        assertEquals(AlertThrottle.Verdict.ALLOW, throttle.accept("ERROR|boom", 10_100L))
    }

    @Test
    fun distinctMessagesAreRateLimitedByGlobalFloor() {
        val throttle = AlertThrottle(sameMessageWindowMs = 8_000, globalFloorMs = 1_200)
        assertEquals(AlertThrottle.Verdict.ALLOW, throttle.accept("ERROR|boom", 1_000L))
        // A second, different message inside the global floor is dropped entirely.
        assertEquals(AlertThrottle.Verdict.RATE_LIMITED, throttle.accept("ERROR|another", 1_500L))
        // Dropped alerts are never marked as seen, so they become eligible once the floor passes
        // instead of being swallowed forever.
        assertEquals(AlertThrottle.Verdict.ALLOW, throttle.accept("ERROR|another", 2_300L))
    }

    @Test
    fun droppedAlertEventuallyReachesTheUser() {
        val throttle = AlertThrottle(sameMessageWindowMs = 8_000, globalFloorMs = 1_200)
        assertEquals(AlertThrottle.Verdict.ALLOW, throttle.accept("ERROR|first", 0L))
        var t = 100L
        var allowed = false
        while (t < 5_000L) {
            if (throttle.accept("ERROR|second", t) == AlertThrottle.Verdict.ALLOW) {
                allowed = true
                break
            }
            t += 100L
        }
        assertTrue("a distinct alert dropped at the floor must surface within a few seconds", allowed)
    }

    @Test
    fun severityIsPartOfTheKey() {
        val throttle = AlertThrottle(sameMessageWindowMs = 8_000, globalFloorMs = 0)
        assertEquals(AlertThrottle.Verdict.ALLOW, throttle.accept("WARN|same text", 1_000L))
        assertEquals(AlertThrottle.Verdict.ALLOW, throttle.accept("ERROR|same text", 1_001L))
    }

    @Test
    fun duplicateAttemptsStillCountTowardTheDuplicateWindow() {
        // A retry loop that fires every 500 ms must never leak a second alert: each attempt
        // refreshes the window.
        val throttle = AlertThrottle(sameMessageWindowMs = 2_000, globalFloorMs = 0)
        assertEquals(AlertThrottle.Verdict.ALLOW, throttle.accept("ERROR|flap", 0L))
        var t = 500L
        while (t < 30_000L) {
            assertEquals("attempt at $t", AlertThrottle.Verdict.DUPLICATE_SUPPRESSED, throttle.accept("ERROR|flap", t))
            t += 500L
        }
    }

    @Test
    fun resetClearsHistory() {
        val throttle = AlertThrottle()
        throttle.accept("ERROR|boom", 1_000L)
        throttle.reset()
        assertEquals(AlertThrottle.Verdict.ALLOW, throttle.accept("ERROR|boom", 1_500L))
    }

    @Test
    fun trackedMessageMapIsBounded() {
        // The map clears once it grows too large so a long-lived process cannot leak memory.
        val throttle = AlertThrottle(sameMessageWindowMs = 0, globalFloorMs = 0)
        for (i in 0 until 300) {
            throttle.accept("ERROR|message-$i", i.toLong() * 10_000L)
        }
        assertEquals(AlertThrottle.Verdict.ALLOW, throttle.accept("ERROR|message-0", 9_999_999L))
    }
}
