package com.example.audiostreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioLevelMeterTest {

    private fun create16BitStereoFrame(sampleL: Short, sampleR: Short): ByteArray {
        val bytes = ByteArray(4)
        bytes[0] = (sampleL.toInt() and 0xFF).toByte()
        bytes[1] = ((sampleL.toInt() shr 8) and 0xFF).toByte()
        bytes[2] = (sampleR.toInt() and 0xFF).toByte()
        bytes[3] = ((sampleR.toInt() shr 8) and 0xFF).toByte()
        return bytes
    }

    private fun create24BitStereoFrame(sampleL: Int, sampleR: Int): ByteArray {
        val bytes = ByteArray(6)
        bytes[0] = (sampleL and 0xFF).toByte()
        bytes[1] = ((sampleL shr 8) and 0xFF).toByte()
        bytes[2] = ((sampleL shr 16) and 0xFF).toByte()

        bytes[3] = (sampleR and 0xFF).toByte()
        bytes[4] = ((sampleR shr 8) and 0xFF).toByte()
        bytes[5] = ((sampleR shr 16) and 0xFF).toByte()
        return bytes
    }

    @Test
    fun testSilence16BitAnd24Bit() {
        val meter = AudioLevelMeter()

        // 16-bit silence
        val silence16 = ByteArray(400) // 100 frames
        val m16 = meter.analyze(silence16, 0, silence16.size, is24Bit = false)
        assertEquals(0, m16.peak)
        assertEquals(0, m16.peakLeft)
        assertEquals(0, m16.peakRight)
        assertEquals(0.0, m16.rms, 0.0001)
        assertEquals(0.0, m16.rmsLeft, 0.0001)
        assertEquals(0.0, m16.rmsRight, 0.0001)
        assertEquals(0, m16.peakPercent)
        assertEquals(0, m16.rmsPercent)
        assertEquals(100, m16.frameCount)

        // 24-bit silence
        val silence24 = ByteArray(600) // 100 frames
        val m24 = meter.analyze(silence24, 0, silence24.size, is24Bit = true)
        assertEquals(0, m24.peak)
        assertEquals(0, m24.peakLeft)
        assertEquals(0, m24.peakRight)
        assertEquals(0.0, m24.rms, 0.0001)
        assertEquals(0.0, m24.rmsLeft, 0.0001)
        assertEquals(0.0, m24.rmsRight, 0.0001)
        assertEquals(0, m24.peakPercent)
        assertEquals(0, m24.rmsPercent)
        assertEquals(100, m24.frameCount)
    }

    @Test
    fun testFullScaleSine16Bit() {
        val meter = AudioLevelMeter()
        val sampleRate = 48000
        val freq = 1000.0 // 1 kHz
        val frameCount = 480 // 10ms = 10 complete cycles
        val buf = ByteArray(frameCount * 4)

        for (f in 0 until frameCount) {
            val phase = 2.0 * PI * freq * f / sampleRate
            val sampleVal = (sin(phase) * 32767.0).toInt().toShort()
            val frame = create16BitStereoFrame(sampleVal, sampleVal)
            System.arraycopy(frame, 0, buf, f * 4, 4)
        }

        val metrics = meter.analyze(buf, 0, buf.size, is24Bit = false)
        assertEquals(32767, metrics.peak)
        assertEquals(32767, metrics.peakLeft)
        assertEquals(32767, metrics.peakRight)
        assertEquals(100, metrics.peakPercent)

        // RMS of sine wave is Peak / sqrt(2) ~ 32767 * 0.7071 ~ 23169.8
        val expectedRms = 32767.0 / kotlin.math.sqrt(2.0)
        assertEquals(expectedRms, metrics.rms, 400.0) // Within ~1.5% due to finite discrete sampling
        assertTrue("RMS percent should be approx 70-71%", metrics.rmsPercent in 70..71)
    }

    @Test
    fun testFullScaleSine24Bit() {
        val meter = AudioLevelMeter()
        val sampleRate = 48000
        val freq = 1000.0
        val frameCount = 480
        val buf = ByteArray(frameCount * 6)

        for (f in 0 until frameCount) {
            val phase = 2.0 * PI * freq * f / sampleRate
            val sampleVal = (sin(phase) * 8388607.0).toInt()
            val frame = create24BitStereoFrame(sampleVal, sampleVal)
            System.arraycopy(frame, 0, buf, f * 6, 6)
        }

        val metrics = meter.analyze(buf, 0, buf.size, is24Bit = true)
        assertEquals(8388607, metrics.peak)
        assertEquals(8388607, metrics.peakLeft)
        assertEquals(8388607, metrics.peakRight)
        assertEquals(100, metrics.peakPercent)

        // RMS of sine is 8388607 / sqrt(2) ~ 5931641.7
        val expectedRms = 8388607.0 / kotlin.math.sqrt(2.0)
        assertEquals(expectedRms, metrics.rms, 100000.0)
        assertTrue("RMS percent should be approx 70-71%", metrics.rmsPercent in 70..71)
    }

    @Test
    fun testImpulseDetectionAcrossAllFramesAndChannels16Bit() {
        // Test that an impulse on ANY frame (0, 1, 2, 3...) and on EITHER channel
        // is captured with 100% accuracy.
        // In the old implementation (which skipped with i += 16), impulses on frames 1, 2, 3
        // or on the Right channel were completely missed (peak = 0).
        val meter = AudioLevelMeter()
        val frameCount = 8

        for (targetFrame in 0 until frameCount) {
            // Test Left channel impulse
            val bufL = ByteArray(frameCount * 4)
            val baseL = targetFrame * 4
            bufL[baseL] = 0xFF.toByte()
            bufL[baseL + 1] = 0x7F.toByte() // +32767

            val mLeft = meter.analyze(bufL, 0, bufL.size, is24Bit = false, updateInterval = false)
            assertEquals("Failed to detect Left impulse at frame $targetFrame", 32767, mLeft.peak)
            assertEquals("Left peak at frame $targetFrame", 32767, mLeft.peakLeft)
            assertEquals("Right peak should be 0 for Left impulse at frame $targetFrame", 0, mLeft.peakRight)
            assertEquals(100, mLeft.peakPercent)

            // Test Right channel impulse
            val bufR = ByteArray(frameCount * 4)
            val baseR = targetFrame * 4 + 2
            bufR[baseR] = 0xFF.toByte()
            bufR[baseR + 1] = 0x7F.toByte() // +32767

            val mRight = meter.analyze(bufR, 0, bufR.size, is24Bit = false, updateInterval = false)
            assertEquals("Failed to detect Right impulse at frame $targetFrame", 32767, mRight.peak)
            assertEquals("Left peak should be 0 for Right impulse at frame $targetFrame", 0, mRight.peakLeft)
            assertEquals("Right peak at frame $targetFrame", 32767, mRight.peakRight)
            assertEquals(100, mRight.peakPercent)
        }
    }

    @Test
    fun testImpulseDetectionAcrossAllFramesAndChannels24Bit() {
        // In the old implementation (which skipped with i += 24), impulses on frames 1, 2, 3
        // or on the Right channel were completely missed.
        val meter = AudioLevelMeter()
        val frameCount = 8

        for (targetFrame in 0 until frameCount) {
            // Test Left channel 24-bit impulse (+8388607 = 0x7FFFFF)
            val bufL = ByteArray(frameCount * 6)
            val baseL = targetFrame * 6
            bufL[baseL] = 0xFF.toByte()
            bufL[baseL + 1] = 0xFF.toByte()
            bufL[baseL + 2] = 0x7F.toByte()

            val mLeft = meter.analyze(bufL, 0, bufL.size, is24Bit = true, updateInterval = false)
            assertEquals("Failed to detect Left 24-bit impulse at frame $targetFrame", 8388607, mLeft.peak)
            assertEquals(8388607, mLeft.peakLeft)
            assertEquals(0, mLeft.peakRight)
            assertEquals(100, mLeft.peakPercent)

            // Test Right channel 24-bit impulse (+8388607)
            val bufR = ByteArray(frameCount * 6)
            val baseR = targetFrame * 6 + 3
            bufR[baseR] = 0xFF.toByte()
            bufR[baseR + 1] = 0xFF.toByte()
            bufR[baseR + 2] = 0x7F.toByte()

            val mRight = meter.analyze(bufR, 0, bufR.size, is24Bit = true, updateInterval = false)
            assertEquals("Failed to detect Right 24-bit impulse at frame $targetFrame", 8388607, mRight.peak)
            assertEquals(0, mRight.peakLeft)
            assertEquals(8388607, mRight.peakRight)
            assertEquals(100, mRight.peakPercent)
        }
    }

    @Test
    fun testLeftOnlySignal() {
        val meter = AudioLevelMeter()
        val frameCount = 64

        // 16-bit Left only
        val buf16 = ByteArray(frameCount * 4)
        for (f in 0 until frameCount) {
            val frame = create16BitStereoFrame(16384.toShort(), 0.toShort())
            System.arraycopy(frame, 0, buf16, f * 4, 4)
        }
        val m16 = meter.analyze(buf16, 0, buf16.size, is24Bit = false)
        assertEquals(16384, m16.peak)
        assertEquals(16384, m16.peakLeft)
        assertEquals(0, m16.peakRight)
        assertEquals(50, m16.peakPercent)
        assertEquals(16384.0, m16.rmsLeft, 0.01)
        assertEquals(0.0, m16.rmsRight, 0.0001)

        // 24-bit Left only
        val buf24 = ByteArray(frameCount * 6)
        for (f in 0 until frameCount) {
            val frame = create24BitStereoFrame(4194304, 0)
            System.arraycopy(frame, 0, buf24, f * 6, 6)
        }
        val m24 = meter.analyze(buf24, 0, buf24.size, is24Bit = true)
        assertEquals(4194304, m24.peak)
        assertEquals(4194304, m24.peakLeft)
        assertEquals(0, m24.peakRight)
        assertEquals(50, m24.peakPercent)
    }

    @Test
    fun testRightOnlySignal() {
        val meter = AudioLevelMeter()
        val frameCount = 64

        // 16-bit Right only: In the old bug, right-only audio resulted in peak = 0!
        val buf16 = ByteArray(frameCount * 4)
        for (f in 0 until frameCount) {
            val frame = create16BitStereoFrame(0.toShort(), 24576.toShort())
            System.arraycopy(frame, 0, buf16, f * 4, 4)
        }
        val m16 = meter.analyze(buf16, 0, buf16.size, is24Bit = false)
        assertEquals(24576, m16.peak)
        assertEquals(0, m16.peakLeft)
        assertEquals(24576, m16.peakRight)
        assertEquals(75, m16.peakPercent)
        assertEquals(0.0, m16.rmsLeft, 0.0001)
        assertEquals(24576.0, m16.rmsRight, 0.01)

        // 24-bit Right only
        val buf24 = ByteArray(frameCount * 6)
        for (f in 0 until frameCount) {
            val frame = create24BitStereoFrame(0, 6291456)
            System.arraycopy(frame, 0, buf24, f * 6, 6)
        }
        val m24 = meter.analyze(buf24, 0, buf24.size, is24Bit = true)
        assertEquals(6291456, m24.peak)
        assertEquals(0, m24.peakLeft)
        assertEquals(6291456, m24.peakRight)
        assertEquals(75, m24.peakPercent)
    }

    @Test
    fun testLowLevelSignalAndSilenceThreshold() {
        val meter = AudioLevelMeter()
        val frameCount = 100

        // 16-bit signal below silence threshold (SILENCE_AMPLITUDE_THRESHOLD_16BIT = 16)
        val bufBelow16 = ByteArray(frameCount * 4)
        for (f in 0 until frameCount) {
            val frame = create16BitStereoFrame(10.toShort(), 12.toShort())
            System.arraycopy(frame, 0, bufBelow16, f * 4, 4)
        }
        val mBelow16 = meter.analyze(bufBelow16, 0, bufBelow16.size, is24Bit = false)
        assertEquals(12, mBelow16.peak)
        assertTrue(mBelow16.peak <= AudioConfig.SILENCE_AMPLITUDE_THRESHOLD_16BIT)

        // 16-bit signal above silence threshold
        val bufAbove16 = ByteArray(frameCount * 4)
        for (f in 0 until frameCount) {
            val frame = create16BitStereoFrame(25.toShort(), 8.toShort())
            System.arraycopy(frame, 0, bufAbove16, f * 4, 4)
        }
        val mAbove16 = meter.analyze(bufAbove16, 0, bufAbove16.size, is24Bit = false)
        assertEquals(25, mAbove16.peak)
        assertTrue(mAbove16.peak > AudioConfig.SILENCE_AMPLITUDE_THRESHOLD_16BIT)

        // 24-bit signal below silence threshold (SILENCE_AMPLITUDE_THRESHOLD_24BIT = 4096)
        val bufBelow24 = ByteArray(frameCount * 6)
        for (f in 0 until frameCount) {
            val frame = create24BitStereoFrame(1000, 2048)
            System.arraycopy(frame, 0, bufBelow24, f * 6, 6)
        }
        val mBelow24 = meter.analyze(bufBelow24, 0, bufBelow24.size, is24Bit = true)
        assertEquals(2048, mBelow24.peak)
        assertTrue(mBelow24.peak <= AudioConfig.SILENCE_AMPLITUDE_THRESHOLD_24BIT)

        // 24-bit signal above silence threshold
        val bufAbove24 = ByteArray(frameCount * 6)
        for (f in 0 until frameCount) {
            val frame = create24BitStereoFrame(5000, 3000)
            System.arraycopy(frame, 0, bufAbove24, f * 6, 6)
        }
        val mAbove24 = meter.analyze(bufAbove24, 0, bufAbove24.size, is24Bit = true)
        assertEquals(5000, mAbove24.peak)
        assertTrue(mAbove24.peak > AudioConfig.SILENCE_AMPLITUDE_THRESHOLD_24BIT)
    }

    @Test
    fun test24BitValuesNearFullScale() {
        val meter = AudioLevelMeter()

        // Positive full scale (+8388607) and near full scale (+8388600)
        val posFrame = create24BitStereoFrame(8388607, 8388600)
        val mPos = meter.analyze(posFrame, 0, posFrame.size, is24Bit = true)
        assertEquals(8388607, mPos.peak)
        assertEquals(8388607, mPos.peakLeft)
        assertEquals(8388600, mPos.peakRight)
        assertEquals(100, mPos.peakPercent)

        // Negative full scale (-8388608 = 0x800000) and near full scale (-8388600)
        val negFrame = create24BitStereoFrame(-8388608, -8388600)
        val mNeg = meter.analyze(negFrame, 0, negFrame.size, is24Bit = true)
        assertEquals(8388608, mNeg.peak)
        assertEquals(8388608, mNeg.peakLeft)
        assertEquals(8388600, mNeg.peakRight)
        assertEquals(100, mNeg.peakPercent)

        // Verify sign extension boundary (-1: 0xFFFFFF, -2: 0xFFFFFE)
        val smallNegFrame = create24BitStereoFrame(-1, -2)
        val mSmallNeg = meter.analyze(smallNegFrame, 0, smallNegFrame.size, is24Bit = true)
        assertEquals(2, mSmallNeg.peak)
        assertEquals(1, mSmallNeg.peakLeft)
        assertEquals(2, mSmallNeg.peakRight)
        assertEquals(0, mSmallNeg.peakPercent)
    }

    @Test
    fun testIntervalPeakAndReset() {
        val meter = AudioLevelMeter()

        // Feed chunk with moderate peak
        val chunk1 = create16BitStereoFrame(10000.toShort(), 12000.toShort())
        meter.analyze(chunk1, 0, chunk1.size, is24Bit = false, updateInterval = true)

        // Feed chunk with higher peak
        val chunk2 = create16BitStereoFrame(20000.toShort(), 5000.toShort())
        meter.analyze(chunk2, 0, chunk2.size, is24Bit = false, updateInterval = true)

        // Interval peak should be 20000
        val intervalPeak = meter.getAndResetIntervalPeak()
        assertEquals(20000, intervalPeak)

        // After reset, interval peak should be 0
        val resetPeak = meter.getAndResetIntervalPeak()
        assertEquals(0, resetPeak)
    }

    @Test
    fun testDoesNotModifyBuffer() {
        val meter = AudioLevelMeter()
        val original = ByteArray(24) { (it * 7).toByte() }
        val copy = original.clone()

        meter.analyze(original, 0, original.size, is24Bit = true)

        for (i in original.indices) {
            assertEquals("Audio byte at $i was modified!", copy[i], original[i])
        }
    }
}
