package com.example.audiostreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveFecControllerTest {

    @Test
    fun startsDisabledSoACleanLinkPaysNoOverhead() {
        val c = AdaptiveFecController(windowPackets = 100)
        // Fresh controller: parity off, and a clean window keeps it off indefinitely.
        assertFalse(c.isEnabled(0))
        repeat(100) { c.recordSent(lost = false) }
        assertFalse("a clean link must never enable parity", c.isEnabled(60_000))
    }

    @Test
    fun lossEngagesParityImmediately() {
        val c = AdaptiveFecController(windowPackets = 100, enableLossPercent = 1.0f, disableLossPercent = 0.2f)
        assertFalse(c.isEnabled(0))
        // One loss in 100 = 1% meets the 1% enable threshold.
        repeat(99) { c.recordSent(false) }
        c.recordSent(lost = true)
        assertTrue("parity must engage on loss", c.isEnabled(1_000))
    }

    @Test
    fun engagedParityHoldsForMinOnMsEvenAfterLossClears() {
        val c = AdaptiveFecController(
            windowPackets = 100,
            enableLossPercent = 1.0f,
            disableLossPercent = 0.2f,
            minOnMs = 3_000
        )
        repeat(99) { c.recordSent(false) }
        c.recordSent(lost = true)
        assertTrue(c.isEnabled(1_000)) // engages at 1s
        // Loss flushes out of the window, but we are still inside minOnMs since enabling → stays on.
        repeat(100) { c.recordSent(false) }
        assertEquals(0f, c.lossPercent(), 0.001f)
        assertTrue("must not release before minOnMs", c.isEnabled(2_500))
        // Past minOnMs with zero loss → releases.
        assertFalse(c.isEnabled(4_100))
    }

    @Test
    fun hysteresisBandKeepsPriorState() {
        val c = AdaptiveFecController(
            windowPackets = 1000,
            enableLossPercent = 1.0f,
            disableLossPercent = 0.2f,
            minOnMs = 0
        )
        // 0.5% loss sits in the band between disable (0.2) and enable (1.0): from a disabled start
        // it must NOT enable (requires >= enable threshold).
        repeat(995) { c.recordSent(false) }
        repeat(5) { c.recordSent(true) }
        assertEquals(0.5f, c.lossPercent(), 0.001f)
        assertFalse("in-band loss from OFF must not engage", c.isEnabled(10_000))
    }

    @Test
    fun resetRestoresDisabledInitialState() {
        val c = AdaptiveFecController(windowPackets = 50, enableLossPercent = 1.0f, minOnMs = 0)
        c.recordSent(lost = true)
        c.recordSent(lost = true) // 2/50 = 4% > 1% → enabled
        assertTrue(c.isEnabled(1_000))
        c.reset()
        assertEquals(0f, c.lossPercent(), 0.001f)
        assertFalse("reset returns to the protected-off default", c.isEnabled(2_000))
    }

    @Test
    fun windowSlidesRatherThanAccumulatingForever() {
        val c = AdaptiveFecController(windowPackets = 10, minOnMs = 0)
        repeat(10) { c.recordSent(lost = true) }
        assertEquals(100f, c.lossPercent(), 0.001f)
        repeat(10) { c.recordSent(lost = false) }
        assertEquals(0f, c.lossPercent(), 0.001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidThresholdsRejected() {
        AdaptiveFecController(enableLossPercent = 0.1f, disableLossPercent = 0.9f)
    }
}
