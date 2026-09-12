package com.example.audiostreamer

/**
 * Audio Clock Drift Controller and Micro-Resampler.
 *
 * Responsibilities:
 * - Tracks low-pass filtered smoothed buffer fill level (`smoothBufferFill`).
 * - Detects crystal clock drift between sender and receiver hardware audio clocks.
 * - Applies micro-resampling (dropping or duplicating a single audio frame of 20-22 microseconds)
 *   at zero-crossing minima to prevent audible comb filtering and pops.
 * - Triggers smooth packet catch-up drops during extreme sustained backlog in low-latency profiles.
 */
class DriftController(initialFill: Float = 2.0f) {
    var smoothBufferFill: Float = initialFill
        private set

    private var packetsSinceCatchUp: Int = 0
    private var packetsSinceDriftAdjust: Int = 0

    fun reset(initialFill: Float) {
        smoothBufferFill = initialFill
        packetsSinceCatchUp = 0
        packetsSinceDriftAdjust = 0
    }

    fun updateFill(availableCount: Int) {
        smoothBufferFill = smoothBufferFill * 0.998f + availableCount * 0.002f
    }

    fun adjustFill(delta: Float) {
        smoothBufferFill += delta
    }

    /**
     * Checks if sustained backlog in low-latency profile requires dropping the oldest slot.
     * Returns true if a catch-up drop should be executed.
     */
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

    /**
     * Checks and applies zero-crossing micro-drift adjustments to the audio chunk.
     */
    fun checkAndApplyDriftAdjustment(
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
        val driftThreshold = if (isLowLat) 8f else if (isAuto) 10f else 16f
        val minInterval = if (isLowLat) 300 else if (isAuto) 350 else 600

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
