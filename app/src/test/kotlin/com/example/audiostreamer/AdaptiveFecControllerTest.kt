package com.example.audiostreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveFecControllerTest {

    @Test
    fun cleanLinkDropsParity() {
        val c = AdaptiveFecController(windowPackets = 100, minOnMs = 1_000)
        c.noteStarted(0)
        // 100 clean packets → 0% loss; after minOnMs parity releases.
        repeat(100) { c.recordSent(lost = false) }
        assertFalse("a clean link must not keep paying FEC overhead", c.isEnabled(5_000))
    }

    @Test
    fun risingLossEnablesParityImmediately() {
        val c = AdaptiveFecController(windowPackets = 100, minOnMs = 1_000)
        // Start disabled by feeding a clean window long past minOnMs.
        c.noteStarted(0)
        repeat(100) { c.recordSent(false) }
        assertFalse(c.isEnabled(5_000))
        // One loss in 100 = 1% ≥ enable (0.5%) → on, no minimum wait to attack.
        c.recordSent(lost = true)
        assertTrue("parity must attack on loss immediately", c.isEnabled(5_100))
    }

    @Test
    fun parityHoldsForMinOnMsBeforeReleasing() {
        val c = AdaptiveFecController(windowPackets = 100, enableLossPercent = 0.5f, disableLossPercent = 0.1f, minOnMs = 3_000)
        c.noteStarted(100)
        c.recordSent(lost = true)
        // Loss clears instantly, but it is still inside minOnMs → must stay enabled.
        repeat(100) { c.recordSent(false) }
        assertTrue("must not release before minOnMs even at zero loss", c.isEnabled(1_500))
        assertFalse(c.isEnabled(5_000))
    }

    @Test
    fun hysteresisBandKeepsStateBetweenThresholds() {
        val c = AdaptiveFecController(windowPackets = 1000, enableLossPercent = 1.0f, disableLossPercent = 0.2f, minOnMs = 0)
        c.noteStarted(0)
        c.reset(); c.noteStarted(0)
        // ~0.5% loss: above disable (0.2) but below enable (1.0). Starting enabled (after reset+note),
        // it should remain enabled because 0.5% > disable threshold.
        repeat(995) { c.recordSent(false) }
        repeat(5) { c.recordSent(true) }
        assertEquals(0.5f, c.lossPercent(), 0.001f)
        assertTrue(c.isEnabled(10_000))
    }

    @Test
    fun resetRestoresInitialState() {
        val c = AdaptiveFecController(windowPackets = 50)
        repeat(50) { c.recordSent(true) }
        assertEquals(100f, c.lossPercent(), 0.001f)
        c.reset()
        assertEquals(0f, c.lossPercent(), 0.001f)
        assertTrue("starts enabled so a new stream is protected", c.isEnabled(0))
    }

    @Test
    fun windowIsSlidingNotAccumulatingForever() {
        val c = AdaptiveFecController(windowPackets = 10, minOnMs = 0)
        c.reset(); c.noteStarted(0)
        repeat(10) { c.recordSent(true) }   // window now fully lossy
        assertEquals(100f, c.lossPercent(), 0.001f)
        repeat(10) { c.recordSent(false) }  // fully clean again
        assertEquals(0f, c.lossPercent(), 0.001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidThresholdsRejected() {
        AdaptiveFecController(enableLossPercent = 0.1f, disableLossPercent = 0.9f)
    }
}
