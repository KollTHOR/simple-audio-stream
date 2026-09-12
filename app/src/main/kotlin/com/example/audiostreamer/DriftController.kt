package com.example.audiostreamer

/**
 * Audio Clock Drift Controller and Continuous Fractional Resampler.
 *
 * Architecture and Control Strategy:
 * 1. Timeline-Based Drift Measurement:
 *    - Measures long-term equilibrium error: error = smoothBufferFill - targetWatermarkSlots.
 *    - Applies a low-pass filter (alpha = 0.002) to reject short-term network jitter and bursty arrivals.
 *    - Closed-Loop Proportional-Integral (PI) Controller:
 *      * Proportional term (Kp): Provides gentle negative feedback to damp queue excursions.
 *      * Integral term (Ki): Accumulates steady-state crystal clock rate mismatches (in ppm).
 *      * Anti-windup clamping: Restricts integral accumulation to prevent runaway during network stalls.
 *      * Bounded correction: Total rate adjustment delta is clamped to [-MAX_CORRECTION, +MAX_CORRECTION]
 *        (+/- 1000 ppm / +/- 0.1%, corresponding to < 1.7 cents of pitch shift, completely imperceptible).
 *
 * 2. Primary Correction Mechanism: Continuous Fractional Resampling:
 *    - Resamples uncompressed PCM audio chunks using continuous Catmull-Rom cubic Hermite interpolation.
 *    - Maintains phase accumulator across chunk boundaries (phi in [0.0, 1.0)).
 *    - Caches boundary history frames across packets to guarantee C1 continuity and eliminate clicks, pops,
 *      and phase discontinuities.
 *
 * 3. Fallback Mechanism: Emergency Frame Drop / Duplicate:
 *    - Retained strictly as an emergency safety valve for extreme, sustained backlog
 *      (smoothBufferFill > target + 12 for >= 100 packets in low-latency mode).
 */
class DriftController(initialFill: Float = 2.0f) {

    companion object {
        const val MAX_CORRECTION = 0.001 // +/- 1000 ppm (+/- 0.1%)
        const val MAX_INTEGRAL = 0.0005   // +/- 500 ppm max integral
        const val KP = 0.00005           // 50 ppm per slot error
        const val KI = 0.0000005         // 0.5 ppm per packet
        const val DEADBAND_SLOTS = 0.5f  // Half-slot deadband to ignore minor jitter
    }

    var smoothBufferFill: Float = initialFill
        private set

    var correctionRatio: Double = 1.0
        private set

    var driftIntegral: Double = 0.0
        private set

    private var packetsSinceCatchUp: Int = 0
    private var packetsSinceDriftAdjust: Int = 0

    // Resampler continuous phase and boundary history
    private var phase: Double = 0.0
    private val historyBuffer = ByteArray(48) // Up to 8 frames of history (8 * 6 bytes)
    private var historyFramesCount = 0

    // Scratch buffer for in-place resampling
    private val resampleScratch = ByteArray(AudioConfig.MAX_PACKET_SIZE + 256)

    fun reset(initialFill: Float) {
        smoothBufferFill = initialFill
        correctionRatio = 1.0
        driftIntegral = 0.0
        packetsSinceCatchUp = 0
        packetsSinceDriftAdjust = 0
        phase = 0.0
        historyFramesCount = 0
    }

    fun adjustFill(delta: Float) {
        smoothBufferFill += delta
    }

    /**
     * Updates smoothed fill level and adjusts closed-loop PI drift correction ratio.
     */
    fun updateFill(availableCount: Int, targetWatermarkSlots: Int = 10) {
        smoothBufferFill = smoothBufferFill * 0.998f + availableCount * 0.002f

        val error = smoothBufferFill - targetWatermarkSlots
        val effectiveError = when {
            error > DEADBAND_SLOTS -> error - DEADBAND_SLOTS
            error < -DEADBAND_SLOTS -> error + DEADBAND_SLOTS
            else -> 0f
        }

        // Proportional term
        val pTerm = KP * effectiveError

        // Integral term with anti-windup
        driftIntegral = (driftIntegral + KI * effectiveError).coerceIn(-MAX_INTEGRAL, MAX_INTEGRAL)

        // Total rate correction: delta > 0 speeds up consumption, delta < 0 slows down consumption
        val totalCorrection = (pTerm + driftIntegral).coerceIn(-MAX_CORRECTION, MAX_CORRECTION)
        correctionRatio = 1.0 + totalCorrection
    }

    fun onUnderrun() {
        // Freeze or slowly decay integral during starvation to prevent windup
        driftIntegral *= 0.95
    }

    /**
     * Resamples a PCM chunk using Catmull-Rom cubic Hermite interpolation.
     * Modifies output buffer in-place and returns the number of valid bytes in output.
     */
    fun resamplePcmChunk(
        output: ByteArray,
        len: Int,
        is24Bit: Boolean
    ): Int {
        val frameBytes = if (is24Bit) 6 else 4
        val inFrames = len / frameBytes
        if (inFrames < 4 || len > AudioConfig.MAX_PACKET_SIZE) {
            return len
        }

        val ratio = correctionRatio
        // If ratio is practically 1.0 and phase is zero, fast identity path
        if (kotlin.math.abs(ratio - 1.0) < 0.000001 && phase < 0.0001) {
            cacheHistory(output, len, frameBytes)
            return len
        }

        var outFrame = 0
        var p = phase
        val maxOutFrames = (output.size - 6) / frameBytes

        while (p < inFrames && outFrame < maxOutFrames) {
            val k = p.toInt()
            val mu = p - k

            // Extract control points for Left and Right channels
            val y0L = getSample(output, len, frameBytes, is24Bit, k - 1, 0)
            val y1L = getSample(output, len, frameBytes, is24Bit, k, 0)
            val y2L = getSample(output, len, frameBytes, is24Bit, k + 1, 0)
            val y3L = getSample(output, len, frameBytes, is24Bit, k + 2, 0)

            val y0R = getSample(output, len, frameBytes, is24Bit, k - 1, 1)
            val y1R = getSample(output, len, frameBytes, is24Bit, k, 1)
            val y2R = getSample(output, len, frameBytes, is24Bit, k + 1, 1)
            val y3R = getSample(output, len, frameBytes, is24Bit, k + 2, 1)

            val interpL = interpolateHermite(y0L, y1L, y2L, y3L, mu)
            val interpR = interpolateHermite(y0R, y1R, y2R, y3R, mu)

            writeSample(resampleScratch, outFrame, frameBytes, is24Bit, interpL, interpR)
            outFrame++
            p += ratio
        }

        // Update phase for next chunk
        phase = (p - inFrames).coerceIn(0.0, 1.0)

        // Cache last frames from input chunk for boundary continuity in next chunk
        cacheHistory(output, len, frameBytes)

        // Copy resampled frames back to output buffer
        val outLen = outFrame * frameBytes
        System.arraycopy(resampleScratch, 0, output, 0, outLen)
        return outLen
    }

    private fun cacheHistory(buffer: ByteArray, len: Int, frameBytes: Int) {
        val framesToSave = minOf(3, len / frameBytes)
        val srcOffset = len - framesToSave * frameBytes
        System.arraycopy(buffer, srcOffset, historyBuffer, 0, framesToSave * frameBytes)
        historyFramesCount = framesToSave
    }

    private fun getSample(
        data: ByteArray,
        len: Int,
        frameBytes: Int,
        is24Bit: Boolean,
        frameIndex: Int,
        channel: Int // 0: Left, 1: Right
    ): Double {
        val inFrames = len / frameBytes
        if (frameIndex < 0) {
            // Read from history buffer if available
            if (historyFramesCount > 0) {
                val histIndex = (historyFramesCount + frameIndex).coerceIn(0, historyFramesCount - 1)
                return readSampleFromBuffer(historyBuffer, histIndex, frameBytes, is24Bit, channel)
            }
            // Clamping fallback for start of stream
            return readSampleFromBuffer(data, 0, frameBytes, is24Bit, channel)
        }
        if (frameIndex >= inFrames) {
            // Clamp to last frame of current chunk
            return readSampleFromBuffer(data, inFrames - 1, frameBytes, is24Bit, channel)
        }
        return readSampleFromBuffer(data, frameIndex, frameBytes, is24Bit, channel)
    }

    private fun readSampleFromBuffer(
        buf: ByteArray,
        frameIndex: Int,
        frameBytes: Int,
        is24Bit: Boolean,
        channel: Int
    ): Double {
        val base = frameIndex * frameBytes + (if (channel == 0) 0 else if (is24Bit) 3 else 2)
        return if (is24Bit) {
            val raw = (buf[base].toInt() and 0xFF) or
                ((buf[base + 1].toInt() and 0xFF) shl 8) or
                ((buf[base + 2].toInt() and 0xFF) shl 16)
            (if (raw and 0x800000 != 0) raw or 0xFF000000.toInt() else raw).toDouble()
        } else {
            ((buf[base].toInt() and 0xFF) or (buf[base + 1].toInt() shl 8)).toShort().toDouble()
        }
    }

    private fun writeSample(
        buf: ByteArray,
        frameIndex: Int,
        frameBytes: Int,
        is24Bit: Boolean,
        sampleL: Double,
        sampleR: Double
    ) {
        val base = frameIndex * frameBytes
        if (is24Bit) {
            val sL = sampleL.toInt().coerceIn(-8388608, 8388607)
            buf[base] = (sL and 0xFF).toByte()
            buf[base + 1] = ((sL shr 8) and 0xFF).toByte()
            buf[base + 2] = ((sL shr 16) and 0xFF).toByte()

            val sR = sampleR.toInt().coerceIn(-8388608, 8388607)
            buf[base + 3] = (sR and 0xFF).toByte()
            buf[base + 4] = ((sR shr 8) and 0xFF).toByte()
            buf[base + 5] = ((sR shr 16) and 0xFF).toByte()
        } else {
            val sL = sampleL.toInt().coerceIn(-32768, 32767)
            buf[base] = (sL and 0xFF).toByte()
            buf[base + 1] = ((sL shr 8) and 0xFF).toByte()

            val sR = sampleR.toInt().coerceIn(-32768, 32767)
            buf[base + 2] = (sR and 0xFF).toByte()
            buf[base + 3] = ((sR shr 8) and 0xFF).toByte()
        }
    }

    private fun interpolateHermite(y0: Double, y1: Double, y2: Double, y3: Double, mu: Double): Double {
        val c0 = y1
        val c1 = 0.5 * (y2 - y0)
        val c2 = y0 - 2.5 * y1 + 2.0 * y2 - 0.5 * y3
        val c3 = 0.5 * (y3 - y0) + 1.5 * (y1 - y2)
        return ((c3 * mu + c2) * mu + c1) * mu + c0
    }

    // -------------------------------------------------------------------------
    // Fallback Emergency Catch-Up & Zero-Crossing Adjustment
    // -------------------------------------------------------------------------

    fun checkCatchUpDrop(isLowLatency: Boolean, targetWatermarkSlots: Int): Boolean {
        if (isLowLatency && smoothBufferFill > targetWatermarkSlots + 12) {
            packetsSinceCatchUp++
            if (packetsSinceCatchUp >= 100) {
                packetsSinceCatchUp = 0
                smoothBufferFill -= 1.0f
                return true
            }
        } else {
            packetsSinceCatchUp = 0
        }
        return false
    }

    fun checkAndApplyEmergencyDriftFallback(
        output: ByteArray,
        len: Int,
        currentProfile: String,
        targetWatermarkSlots: Int,
        is24Bit: Boolean
    ) {
        packetsSinceDriftAdjust++
        val driftDelta = smoothBufferFill - targetWatermarkSlots
        val isLowLat = (currentProfile == AudioConfig.PROFILE_LOW_LATENCY || currentProfile == AudioConfig.PROFILE_VIDEO)
        val isAuto = (currentProfile == AudioConfig.PROFILE_AUTO)
        val driftThreshold = if (isLowLat) 12f else if (isAuto) 16f else 20f
        val minInterval = if (isLowLat) 500 else if (isAuto) 600 else 800

        if (packetsSinceDriftAdjust >= minInterval && len >= 12) {
            if (driftDelta > driftThreshold) {
                applyZeroCrossingFrameDrop(output, len, is24Bit)
                packetsSinceDriftAdjust = 0
                smoothBufferFill -= 0.5f
            } else if (driftDelta < -driftThreshold) {
                applyZeroCrossingFrameDuplicate(output, len, is24Bit)
                packetsSinceDriftAdjust = 0
                smoothBufferFill += 0.5f
            }
        }
    }

    fun applyZeroCrossingFrameDrop(output: ByteArray, len: Int, is24: Boolean) {
        val frameBytes = if (is24) 6 else 4
        val totalFrames = len / frameBytes
        val searchStart = totalFrames / 4
        val searchEnd = (3 * totalFrames) / 4

        var bestFrame = searchStart
        var minAbs = Int.MAX_VALUE

        for (f in searchStart until searchEnd) {
            val idx = f * frameBytes
            val sampleL = if (is24) {
                val raw = (output[idx].toInt() and 0xFF) or
                    ((output[idx + 1].toInt() and 0xFF) shl 8) or
                    ((output[idx + 2].toInt() and 0xFF) shl 16)
                if (raw and 0x800000 != 0) raw or 0xFF000000.toInt() else raw
            } else {
                ((output[idx].toInt() and 0xFF) or (output[idx + 1].toInt() shl 8)).toShort().toInt()
            }
            val sampleR = if (is24) {
                val raw = (output[idx + 3].toInt() and 0xFF) or
                    ((output[idx + 4].toInt() and 0xFF) shl 8) or
                    ((output[idx + 5].toInt() and 0xFF) shl 16)
                if (raw and 0x800000 != 0) raw or 0xFF000000.toInt() else raw
            } else {
                ((output[idx + 2].toInt() and 0xFF) or (output[idx + 3].toInt() shl 8)).toShort().toInt()
            }
            val absVal = kotlin.math.abs(sampleL) + kotlin.math.abs(sampleR)
            if (absVal < minAbs) {
                minAbs = absVal
                bestFrame = f
                if (absVal == 0) break
            }
        }

        val dropIdx = bestFrame * frameBytes
        val remaining = len - dropIdx - frameBytes
        if (remaining > 0) {
            System.arraycopy(output, dropIdx + frameBytes, output, dropIdx, remaining)
            System.arraycopy(output, len - 2 * frameBytes, output, len - frameBytes, frameBytes)
        }
    }

    fun applyZeroCrossingFrameDuplicate(output: ByteArray, len: Int, is24: Boolean) {
        val frameBytes = if (is24) 6 else 4
        val totalFrames = len / frameBytes
        val searchStart = totalFrames / 4
        val searchEnd = (3 * totalFrames) / 4

        var bestFrame = searchStart
        var minAbs = Int.MAX_VALUE

        for (f in searchStart until searchEnd) {
            val idx = f * frameBytes
            val sampleL = if (is24) {
                val raw = (output[idx].toInt() and 0xFF) or
                    ((output[idx + 1].toInt() and 0xFF) shl 8) or
                    ((output[idx + 2].toInt() and 0xFF) shl 16)
                if (raw and 0x800000 != 0) raw or 0xFF000000.toInt() else raw
            } else {
                ((output[idx].toInt() and 0xFF) or (output[idx + 1].toInt() shl 8)).toShort().toInt()
            }
            val sampleR = if (is24) {
                val raw = (output[idx + 3].toInt() and 0xFF) or
                    ((output[idx + 4].toInt() and 0xFF) shl 8) or
                    ((output[idx + 5].toInt() and 0xFF) shl 16)
                if (raw and 0x800000 != 0) raw or 0xFF000000.toInt() else raw
            } else {
                ((output[idx + 2].toInt() and 0xFF) or (output[idx + 3].toInt() shl 8)).toShort().toInt()
            }
            val absVal = kotlin.math.abs(sampleL) + kotlin.math.abs(sampleR)
            if (absVal < minAbs) {
                minAbs = absVal
                bestFrame = f
                if (absVal == 0) break
            }
        }

        val insertIdx = bestFrame * frameBytes
        val shiftLen = len - insertIdx - frameBytes
        if (shiftLen > 0) {
            System.arraycopy(output, insertIdx, output, insertIdx + frameBytes, shiftLen)
        }
    }
}
