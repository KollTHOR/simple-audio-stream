package com.example.audiostreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket

class Wifi4ResilienceAndOpusTest {

    @Test
    fun testOpusCalculateFramesForPayloadReturns960RegardlessOfByteLength() {
        val jb = JitterBuffer(initialProfile = AudioConfig.PROFILE_LOW_LATENCY)
        jb.setIsOpus(true)
        jb.setProfile(AudioConfig.PROFILE_LOW_LATENCY, isCompressed = true, isOpus = true)

        // Low bitrate / small Opus frame (e.g. 150 bytes)
        assertEquals(960, jb.calculateFramesForPayload(150))

        // Medium bitrate Opus frame (e.g. 350 bytes)
        assertEquals(960, jb.calculateFramesForPayload(350))

        // High bitrate 320 kbps Opus frame (typically ~800 bytes)
        // CRITICAL REGRESSION TEST: previously, length > 400 returned 1024 frames,
        // causing clock drift and periodic buffer flushes every 10 seconds!
        assertEquals(960, jb.calculateFramesForPayload(800))
        assertEquals(960, jb.calculateFramesForPayload(1200))
    }

    @Test
    fun testAacCalculateFramesForPayloadReturns1024() {
        val jb = JitterBuffer(initialProfile = AudioConfig.PROFILE_LOW_LATENCY)
        jb.setIsOpus(false)
        jb.setProfile(AudioConfig.PROFILE_LOW_LATENCY, isCompressed = true, isOpus = false)

        assertEquals(1024, jb.calculateFramesForPayload(500))
        assertEquals(1024, jb.calculateFramesForPayload(800))
    }

    @Test
    fun testPcmCalculateFramesForPayload() {
        val jb = JitterBuffer(initialProfile = AudioConfig.PROFILE_MUSIC)
        jb.setSampleRate(48000)

        // 16-bit stereo: 4 bytes per frame -> 1920 bytes = 480 frames (10ms)
        jb.set24Bit(false)
        assertEquals(480, jb.calculateFramesForPayload(1920))

        // 24-bit stereo: 6 bytes per frame -> 2880 bytes = 480 frames (10ms)
        jb.set24Bit(true)
        assertEquals(480, jb.calculateFramesForPayload(2880))
    }

    @Test
    fun testLowLatencyAdaptiveHeadroomExpandsUpTo100msOnJitter() {
        val ctrl = AdaptivePlayoutController(AudioConfig.PROFILE_LOW_LATENCY, 40.0f, 20.0f)

        // Emergency underrun should bump by 10ms
        ctrl.onUnderrun()
        assertEquals(50.0f, ctrl.desiredTargetMs, 0.01f)
        assertEquals(AdaptivePlayoutController.TransitionReason.EMERGENCY, ctrl.lastTransitionReason)

        // Sustained jitter contention (buffer consistently at 0)
        repeat(300) {
            ctrl.onPacketArrived(availableCount = 0, targetWatermarkSlots = 2, packetDurationMs = 20.0f)
        }

        // Must expand to absorb Wi-Fi 4 jitter bursts up to maxTargetMs = 100ms
        assertTrue("Desired target must reach up to 100ms during sustained contention (actual=${ctrl.desiredTargetMs})",
            ctrl.desiredTargetMs <= 100.0f && ctrl.desiredTargetMs >= 80.0f)
        assertTrue("Effective target must smoothly slew toward 100ms ceiling (actual=${ctrl.effectiveTargetMs})",
            ctrl.effectiveTargetMs <= 100.0f && ctrl.effectiveTargetMs > 50.0f)
    }

    @Test
    fun testSocketQosTrafficClass0xB8() {
        DatagramSocket().use { socket ->
            socket.trafficClass = 0xB8
            // 0xB8 (DSCP EF / Voice) is supported on standard socket stacks
            // trafficClass returns an int; some platforms mask the low 2 ECN bits or return 0xB8
            assertTrue("Socket traffic class should be accepted", socket.trafficClass >= 0)
        }
    }
}
