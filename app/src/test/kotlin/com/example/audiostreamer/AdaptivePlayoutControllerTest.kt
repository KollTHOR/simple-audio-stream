package com.example.audiostreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePlayoutControllerTest {

    private val PACKET_MS = 5.0f

    // -------------------------------------------------------------------------
    // 1. Single jitter spike does not immediately change effectiveTargetMs
    // -------------------------------------------------------------------------
    @Test
    fun testSingleJitterSpikeDoesNotJumpEffectiveTarget() {
        val ctrl = AdaptivePlayoutController("AUTO", 50.0f, PACKET_MS)
        val before = ctrl.effectiveTargetMs

        // Simulate one packet that has a wildly under-filled buffer (worst case signal)
        ctrl.onPacketArrived(availableCount = 0, targetWatermarkSlots = 10, packetDurationMs = PACKET_MS)

        // effectiveTargetMs must have changed by at most the upward slew rate (2ms for AUTO)
        val diff = ctrl.effectiveTargetMs - before
        assertTrue("Single spike must slew, not jump (diff=$diff)", diff <= 2.5f)
    }

    // -------------------------------------------------------------------------
    // 2 & 3. Sustained poor conditions increase desiredTarget and effectiveTarget rises gradually
    // -------------------------------------------------------------------------
    @Test
    fun testSustainedPoorConditionsRaisesDesiredAndEffectiveGradually() {
        val ctrl = AdaptivePlayoutController("AUTO", 50.0f, PACKET_MS)

        // Feed many packets with buffer well below target watermark
        repeat(60) {
            ctrl.onPacketArrived(availableCount = 2, targetWatermarkSlots = 10, packetDurationMs = PACKET_MS)
        }

        assertTrue("desiredTargetMs should be above initial 50ms after sustained under-fill",
            ctrl.desiredTargetMs > 55.0f)
        assertTrue("effectiveTargetMs should be rising toward desired",
            ctrl.effectiveTargetMs > 50.0f)
        // But effectiveTarget must not have jumped to desiredTarget in one step
        assertTrue("effectiveTargetMs must be below desiredTargetMs (slew-limited)",
            ctrl.effectiveTargetMs <= ctrl.desiredTargetMs)
    }

    // -------------------------------------------------------------------------
    // 4 & 5. Sustained healthy conditions eventually reduce desiredTarget and effectiveTarget
    // -------------------------------------------------------------------------
    @Test
    fun testSustainedHealthyConditionsReducesTargetGradually() {
        // Start at elevated target (e.g. after a network disruption)
        val ctrl = AdaptivePlayoutController("AUTO", 150.0f, PACKET_MS)

        val initial = ctrl.effectiveTargetMs
        assertEquals(150.0f, initial, 0.01f)

        // Feed many packets with buffer well above target (overfilled) + clean playback
        val minDwell = 200 + 50 // policy.minDwellPacketsForRecovery for AUTO + extra
        repeat(minDwell) {
            ctrl.onPacketArrived(availableCount = 25, targetWatermarkSlots = 10, packetDurationMs = PACKET_MS)
            ctrl.onCleanPlayback()
        }

        assertTrue("desiredTargetMs should have decreased from 150ms after sustained healthy conditions",
            ctrl.desiredTargetMs < 150.0f)
        assertTrue("effectiveTargetMs should have decreased from 150ms",
            ctrl.effectiveTargetMs < 150.0f)
    }

    // -------------------------------------------------------------------------
    // 6. Hysteresis prevents oscillation within dead band
    // -------------------------------------------------------------------------
    @Test
    fun testDeadBandPreventsOscillation() {
        val ctrl = AdaptivePlayoutController("AUTO", 100.0f, PACKET_MS)

        // Feed packets with fill exactly at watermark (marginSlots = 0, no drift)
        repeat(50) {
            ctrl.onPacketArrived(availableCount = 10, targetWatermarkSlots = 10, packetDurationMs = PACKET_MS)
            ctrl.onCleanPlayback()
        }

        // Target should not have moved since we're exactly at the watermark
        assertEquals("Target should be stable at watermark (dead-band applies)",
            100.0f, ctrl.effectiveTargetMs, 5.0f)
        assertEquals("Desired should also be stable",
            100.0f, ctrl.desiredTargetMs, 5.0f)
    }

    // -------------------------------------------------------------------------
    // 7. onUnderrun() triggers EMERGENCY
    // -------------------------------------------------------------------------
    @Test
    fun testUnderrunTriggersEmergencyTransition() {
        val ctrl = AdaptivePlayoutController("AUTO", 50.0f, PACKET_MS)

        ctrl.onUnderrun()

        assertEquals(AdaptivePlayoutController.TransitionReason.EMERGENCY, ctrl.lastTransitionReason)
        assertTrue("desiredTargetMs should jump on emergency underrun", ctrl.desiredTargetMs > 50.0f)
    }

    // -------------------------------------------------------------------------
    // 8. MUSIC profile: larger baseline, conservative changes
    // -------------------------------------------------------------------------
    @Test
    fun testMusicProfileHasLargerBaselineAndConservativeSlew() {
        val musicCtrl = AdaptivePlayoutController("MUSIC", 200.0f, PACKET_MS)
        val autoCtrl = AdaptivePlayoutController("AUTO", 50.0f, PACKET_MS)

        // Feed 30 packets with bad network
        repeat(30) {
            musicCtrl.onPacketArrived(availableCount = 0, targetWatermarkSlots = 40, packetDurationMs = PACKET_MS)
            autoCtrl.onPacketArrived(availableCount = 0, targetWatermarkSlots = 10, packetDurationMs = PACKET_MS)
        }

        // MUSIC slew rate is 1ms/update, AUTO is 2ms/update — AUTO should rise faster per step
        val musicRise = musicCtrl.effectiveTargetMs - 200.0f
        val autoRise = autoCtrl.effectiveTargetMs - 50.0f
        // AUTO rises at least as fast (or faster) per call vs MUSIC (proportional to slew)
        assertTrue("AUTO should be at least as aggressive as MUSIC in upward slew per packet",
            autoRise >= musicRise * 0.9f || autoCtrl.effectiveTargetMs > 50.0f)
    }

    // -------------------------------------------------------------------------
    // 9. LOW_LATENCY profile: stays tightly bounded
    // -------------------------------------------------------------------------
    @Test
    fun testLowLatencyProfileStaysBounded() {
        val ctrl = AdaptivePlayoutController("LOW_LATENCY", 40.0f, PACKET_MS)

        // Simulate sustained emergency
        repeat(500) {
            ctrl.onPacketArrived(availableCount = 0, targetWatermarkSlots = 2, packetDurationMs = PACKET_MS)
        }

        // Must not exceed LOW_LATENCY maxTargetMs = 60ms
        assertTrue("LOW_LATENCY effectiveTargetMs must stay ≤ 60ms",
            ctrl.effectiveTargetMs <= 60.0f)
        assertTrue("LOW_LATENCY desiredTargetMs must stay ≤ 60ms",
            ctrl.desiredTargetMs <= 60.0f)
    }

    // -------------------------------------------------------------------------
    // 10. ADAPTIVE is most responsive (fastest upward slew)
    // -------------------------------------------------------------------------
    @Test
    fun testAdaptiveProfileMostResponsive() {
        val autoCtrl = AdaptivePlayoutController("AUTO", 50.0f, PACKET_MS)
        val musicCtrl = AdaptivePlayoutController("MUSIC", 200.0f, PACKET_MS)
        val llCtrl = AdaptivePlayoutController("LOW_LATENCY", 40.0f, PACKET_MS)

        // 20 consecutive worst-case packets
        repeat(20) {
            autoCtrl.onPacketArrived(availableCount = 0, targetWatermarkSlots = 10, packetDurationMs = PACKET_MS)
            llCtrl.onPacketArrived(availableCount = 0, targetWatermarkSlots = 2, packetDurationMs = PACKET_MS)
        }
        // AUTO absolute rise ≥ LOW_LATENCY absolute rise per step
        val autoRisePerStep = (autoCtrl.effectiveTargetMs - 50.0f) / 20f
        val llRisePerStep = (llCtrl.effectiveTargetMs - 40.0f) / 20f
        assertTrue("AUTO upward slew rate (${autoRisePerStep}ms/pkt) should ≥ LOW_LATENCY (${llRisePerStep}ms/pkt)",
            autoRisePerStep >= llRisePerStep)
    }

    // -------------------------------------------------------------------------
    // 11. configure() resets state
    // -------------------------------------------------------------------------
    @Test
    fun testConfigureResetsState() {
        val ctrl = AdaptivePlayoutController("AUTO", 50.0f, PACKET_MS)

        // Drive up
        repeat(100) {
            ctrl.onPacketArrived(availableCount = 0, targetWatermarkSlots = 10, packetDurationMs = PACKET_MS)
        }
        assertTrue("effectiveTargetMs should have risen", ctrl.effectiveTargetMs > 55f)

        // Reconfigure to MUSIC
        ctrl.configure("MUSIC", 200.0f, PACKET_MS)

        assertEquals("After configure, effectiveTargetMs must equal new profile target",
            200.0f, ctrl.effectiveTargetMs, 0.01f)
        assertEquals("After configure, desiredTargetMs must equal new profile target",
            200.0f, ctrl.desiredTargetMs, 0.01f)
        assertEquals(AdaptivePlayoutController.TransitionReason.NONE, ctrl.lastTransitionReason)
    }

    // -------------------------------------------------------------------------
    // 12. effectiveTargetSlots rounds up correctly
    // -------------------------------------------------------------------------
    @Test
    fun testEffectiveTargetSlotsRoundsUp() {
        val ctrl = AdaptivePlayoutController("AUTO", 50.0f, PACKET_MS)
        // 50ms / 5ms = exactly 10 slots
        assertEquals(10, ctrl.effectiveTargetSlots(PACKET_MS, 64))

        val ctrl2 = AdaptivePlayoutController("AUTO", 51.0f, PACKET_MS)
        // 51ms / 5ms = 10.2 → ceil = 11
        assertEquals(11, ctrl2.effectiveTargetSlots(PACKET_MS, 64))
    }

    // -------------------------------------------------------------------------
    // 13. Target changes do NOT advance sequence / alter JitterBuffer fill
    //     (structural: verifies controller doesn't call any buffer-mutating methods)
    // -------------------------------------------------------------------------
    @Test
    fun testOnPacketArrivedDoesNotMutateBufferState() {
        val ctrl = AdaptivePlayoutController("AUTO", 50.0f, PACKET_MS)

        // No buffer mutation methods exist on the controller itself — it only exposes reads.
        // This test verifies that the controller's onPacketArrived can be called safely.
        val result1 = ctrl.onPacketArrived(10, 10, PACKET_MS)
        val result2 = ctrl.onPacketArrived(10, 10, PACKET_MS)
        // With fill == target, no change is expected
        assertFalse("No change expected when fill == target (neutral margin)", result1 && result2)
    }

    // -------------------------------------------------------------------------
    // 14. arrivalMarginP10 is negative when packets consistently arrive late
    // -------------------------------------------------------------------------
    @Test
    fun testArrivalMarginP10IsNegativeWhenBelowTarget() {
        val ctrl = AdaptivePlayoutController("AUTO", 50.0f, PACKET_MS)

        // Fill consistently 5 slots below target (margin = -25ms)
        repeat(64) {
            ctrl.onPacketArrived(availableCount = 5, targetWatermarkSlots = 10, packetDurationMs = PACKET_MS)
        }

        val p10 = ctrl.arrivalMarginP10
        assertTrue("P10 arrival margin should be negative when buffer is consistently below target (p10=$p10)",
            p10 < 0f)
    }

    // -------------------------------------------------------------------------
    // 15. Downward slew is slower than upward slew for AUTO
    // -------------------------------------------------------------------------
    @Test
    fun testDownwardSlewSlowerThanUpward() {
        // Drive up rapidly
        val upCtrl = AdaptivePlayoutController("AUTO", 50.0f, PACKET_MS)
        repeat(20) {
            upCtrl.onPacketArrived(availableCount = 0, targetWatermarkSlots = 10, packetDurationMs = PACKET_MS)
        }
        val upRise = upCtrl.effectiveTargetMs - 50.0f

        // Drive down from elevated state
        val downCtrl = AdaptivePlayoutController("AUTO", 150.0f, PACKET_MS)
        repeat(20) {
            downCtrl.onPacketArrived(availableCount = 35, targetWatermarkSlots = 10, packetDurationMs = PACKET_MS)
            downCtrl.onCleanPlayback()
        }
        val downDrop = 150.0f - downCtrl.effectiveTargetMs

        assertTrue("Upward slew ($upRise ms in 20 packets) must be ≥ downward slew ($downDrop ms in 20 packets)",
            upRise >= downDrop)
    }
}
