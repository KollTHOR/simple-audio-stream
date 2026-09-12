package com.example.audiostreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class DriftResamplerTest {

    private fun generateSineChunk(startFrame: Int, numFrames: Int, freq: Double = 440.0, sampleRate: Double = 48000.0, is24Bit: Boolean = true): ByteArray {
        val frameBytes = if (is24Bit) 6 else 4
        val bytes = ByteArray(numFrames * frameBytes)
        for (i in 0 until numFrames) {
            val t = (startFrame + i) / sampleRate
            val sampleVal = sin(2.0 * Math.PI * freq * t)
            if (is24Bit) {
                val amp = (sampleVal * 8000000.0).toInt().coerceIn(-8388608, 8388607)
                val base = i * 6
                bytes[base] = (amp and 0xFF).toByte()
                bytes[base + 1] = ((amp shr 8) and 0xFF).toByte()
                bytes[base + 2] = ((amp shr 16) and 0xFF).toByte()
                bytes[base + 3] = (amp and 0xFF).toByte()
                bytes[base + 4] = ((amp shr 8) and 0xFF).toByte()
                bytes[base + 5] = ((amp shr 16) and 0xFF).toByte()
            } else {
                val amp = (sampleVal * 30000.0).toInt().coerceIn(-32768, 32767)
                val base = i * 4
                bytes[base] = (amp and 0xFF).toByte()
                bytes[base + 1] = ((amp shr 8) and 0xFF).toByte()
                bytes[base + 2] = (amp and 0xFF).toByte()
                bytes[base + 3] = ((amp shr 8) and 0xFF).toByte()
            }
        }
        return bytes
    }

    private fun readLeftSample(buf: ByteArray, frameIndex: Int, is24Bit: Boolean): Double {
        val frameBytes = if (is24Bit) 6 else 4
        val base = frameIndex * frameBytes
        return if (is24Bit) {
            val raw = (buf[base].toInt() and 0xFF) or
                ((buf[base + 1].toInt() and 0xFF) shl 8) or
                ((buf[base + 2].toInt() and 0xFF) shl 16)
            (if (raw and 0x800000 != 0) raw or 0xFF000000.toInt() else raw).toDouble()
        } else {
            ((buf[base].toInt() and 0xFF) or (buf[base + 1].toInt() shl 8)).toShort().toDouble()
        }
    }

    // -------------------------------------------------------------------------
    // 1. Simulation: No Drift
    // -------------------------------------------------------------------------
    @Test
    fun testSimulationNoDrift() {
        val controller = DriftController(initialFill = 10.0f)
        val targetWatermark = 10

        // Simulate 500 packets with zero clock drift (queue oscillates tightly around 10)
        for (i in 0 until 500) {
            val jitterCount = 10 + (if (i % 2 == 0) 0 else 0) // exact 10
            controller.updateFill(jitterCount, targetWatermark)
        }

        // With no drift, correction ratio should remain 1.0 (within deadband)
        assertEquals(10.0f, controller.smoothBufferFill, 0.01f)
        assertEquals(1.0, controller.correctionRatio, 0.000001)
        assertEquals(0.0, controller.driftIntegral, 0.000001)

        // Resampling output must be identical to input
        val chunk = generateSineChunk(0, 240, is24Bit = true)
        val originalChunk = chunk.clone()
        val outLen = controller.resamplePcmChunk(chunk, chunk.size, is24Bit = true)
        assertEquals(originalChunk.size, outLen)
        for (i in 0 until outLen) {
            assertEquals("Sample at index $i must match original", originalChunk[i], chunk[i])
        }
    }

    // -------------------------------------------------------------------------
    // 2. Simulation: Small Positive Drift (+100 ppm)
    // -------------------------------------------------------------------------
    @Test
    fun testSimulationSmallPositiveDrift() {
        val controller = DriftController(initialFill = 10.0f)
        val targetWatermark = 10

        // Transmitter is faster than receiver by 100 ppm
        // Simulating 2000 packets where buffer fill increases by 1 packet every ~500 packets
        var simulatedQueueFill = 10.0
        for (i in 0 until 2000) {
            simulatedQueueFill += 0.002 // +100 ppm drift
            controller.updateFill(simulatedQueueFill.toInt(), targetWatermark)
        }

        // Controller must have detected positive drift
        assertTrue("Buffer fill must be above target", controller.smoothBufferFill > 10.0f)
        assertTrue("Correction ratio must be > 1.0 to consume backlog", controller.correctionRatio > 1.0)
        assertTrue("Drift integral must be positive", controller.driftIntegral > 0.0)

        // Correction must be strictly bounded (+/- 1000 ppm)
        assertTrue(
            "Correction ratio must be bounded by MAX_CORRECTION",
            controller.correctionRatio <= 1.0 + DriftController.MAX_CORRECTION
        )

        // Resampler smoothly processes chunk with ratio > 1.0
        val chunk = generateSineChunk(0, 240, is24Bit = true)
        val outLen = controller.resamplePcmChunk(chunk, chunk.size, is24Bit = true)
        assertTrue("Output length should be valid and positive", outLen > 0)
    }

    // -------------------------------------------------------------------------
    // 3. Simulation: Small Negative Drift (-100 ppm)
    // -------------------------------------------------------------------------
    @Test
    fun testSimulationSmallNegativeDrift() {
        val controller = DriftController(initialFill = 10.0f)
        val targetWatermark = 10

        // Transmitter is slower than receiver by 100 ppm
        var simulatedQueueFill = 10.0
        for (i in 0 until 2000) {
            simulatedQueueFill -= 0.002 // -100 ppm drift
            controller.updateFill(simulatedQueueFill.toInt().coerceAtLeast(1), targetWatermark)
        }

        // Controller must have detected negative drift
        assertTrue("Buffer fill must be below target", controller.smoothBufferFill < 10.0f)
        assertTrue("Correction ratio must be < 1.0 to slow consumption", controller.correctionRatio < 1.0)
        assertTrue("Drift integral must be negative", controller.driftIntegral < 0.0)

        // Correction must be strictly bounded
        assertTrue(
            "Correction ratio must be bounded by MAX_CORRECTION",
            controller.correctionRatio >= 1.0 - DriftController.MAX_CORRECTION
        )

        // Resampler smoothly processes chunk with ratio < 1.0
        val chunk = generateSineChunk(0, 240, is24Bit = true)
        val outLen = controller.resamplePcmChunk(chunk, chunk.size, is24Bit = true)
        assertTrue("Output length should be valid and positive", outLen > 0)
    }

    // -------------------------------------------------------------------------
    // 4. Simulation: Sudden Timing Disturbance (Burst of Packets)
    // -------------------------------------------------------------------------
    @Test
    fun testSimulationSuddenTimingDisturbance() {
        val controller = DriftController(initialFill = 10.0f)
        val targetWatermark = 10

        // Steady state for 200 packets
        for (i in 0 until 200) {
            controller.updateFill(10, targetWatermark)
        }
        assertEquals(1.0, controller.correctionRatio, 0.000001)

        // Sudden burst: 15 packets arrive at once (instantaneous queue spikes to 20 for 3 packets)
        for (i in 0 until 3) {
            controller.updateFill(20, targetWatermark)
        }

        // Low-pass filter (alpha = 0.002) must prevent aggressive reaction
        // Fill change: 3 * (20 - 10) * 0.002 = ~0.06 slots
        assertTrue("Smooth fill should barely move on short burst", controller.smoothBufferFill < 10.2f)
        // Correction ratio should remain well below max limit
        assertTrue("Correction ratio should remain near 1.0", controller.correctionRatio < 1.0001)
    }

    // -------------------------------------------------------------------------
    // 5. Simulation: Packet Loss Combined with Drift
    // -------------------------------------------------------------------------
    @Test
    fun testSimulationPacketLossCombinedWithDrift() {
        val controller = DriftController(initialFill = 10.0f)
        val targetWatermark = 10

        // 1000 packets with small positive drift (+50 ppm) and 5% periodic packet loss
        var queue = 10.0
        for (i in 0 until 1000) {
            queue += 0.001 // +50 ppm
            if (i % 20 == 0) {
                // Packet lost / underrun
                queue -= 1.0
                controller.onUnderrun()
            }
            controller.updateFill(queue.toInt().coerceAtLeast(1), targetWatermark)
        }

        // Anti-windup and onUnderrun damping must keep integral stable and bounded
        assertTrue("Integral must remain strictly within bounds", controller.driftIntegral <= DriftController.MAX_INTEGRAL)
        assertTrue("Integral must remain strictly within bounds", controller.driftIntegral >= -DriftController.MAX_INTEGRAL)
        assertTrue("Rate correction must remain bounded", controller.correctionRatio <= 1.0 + DriftController.MAX_CORRECTION)
    }

    // -------------------------------------------------------------------------
    // 6. Resampler Continuous Waveform (C1 Continuity & No Clicks)
    // -------------------------------------------------------------------------
    @Test
    fun testResamplerC1ContinuityAcrossChunks() {
        val controller = DriftController(initialFill = 10.0f)
        // Manually adjust ratio to +500 ppm to test continuous resampling
        for (i in 0 until 1000) {
            controller.updateFill(15, 10) // Push ratio up
        }
        assertTrue("Ratio should be > 1.0", controller.correctionRatio > 1.0)

        // Resample 10 consecutive chunks of a 440 Hz sine wave
        var lastSamplePrevChunk = 0.0
        val framesPerChunk = 240
        var totalFramesProcessed = 0

        for (chunkIdx in 0 until 10) {
            val chunk = generateSineChunk(totalFramesProcessed, framesPerChunk, freq = 440.0, is24Bit = true)
            val outLen = controller.resamplePcmChunk(chunk, chunk.size, is24Bit = true)
            val outFrames = outLen / 6

            val firstSampleCurrChunk = readLeftSample(chunk, 0, is24Bit = true)
            if (chunkIdx > 0) {
                // Check sample step continuity between consecutive chunks:
                // At 440Hz / 48kHz, max sample derivative is ~460,000 per frame.
                // Over up to ~1.5 frames, max natural delta is ~700,000.
                // A click/glitch would be multi-million amplitude or sign discontinuity.
                val delta = kotlin.math.abs(firstSampleCurrChunk - lastSamplePrevChunk)
                assertTrue("Inter-chunk transition must be smooth (no click), delta was $delta", delta < 1000000.0)
            }
            lastSamplePrevChunk = readLeftSample(chunk, outFrames - 1, is24Bit = true)
            totalFramesProcessed += framesPerChunk
        }
    }

    // -------------------------------------------------------------------------
    // 7. Emergency Fallback Trigger Test
    // -------------------------------------------------------------------------
    @Test
    fun testEmergencyCatchUpFallback() {
        val controller = DriftController(initialFill = 10.0f)
        val targetWatermark = 10

        // Normal fill: catch-up should NOT trigger
        assertFalse(controller.checkCatchUpDrop(isLowLatency = true, targetWatermarkSlots = targetWatermark))

        // Push fill to extreme backlog (> target + 12 = 23 slots)
        for (i in 0 until 1000) {
            controller.updateFill(30, targetWatermark)
        }
        assertTrue(controller.smoothBufferFill > targetWatermark + 12)

        // Should trigger catch-up drop after 100 sustained backlog checks
        var triggered = false
        for (i in 0 until 105) {
            if (controller.checkCatchUpDrop(isLowLatency = true, targetWatermarkSlots = targetWatermark)) {
                triggered = true
                break
            }
        }
        assertTrue("Emergency catch-up drop must trigger under extreme sustained backlog", triggered)
    }
}
