package com.example.audiostreamer

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * High-performance, sample-accurate audio level and RMS meter for 16-bit and 24-bit packed PCM.
 *
 * Correctly understands and validates:
 * - Sample width: 16-bit (2 bytes) signed little-endian vs 24-bit (3 bytes) signed packed little-endian
 * - Channel count: Stereo (2 channels) or arbitrary channel counts
 * - Frame size: channelCount * bytesPerSample (4 bytes for 16-bit stereo, 6 bytes for 24-bit stereo)
 * - Channel interleaving: Left at offset 0, Right at offset bytesPerSample
 *
 * Performs block peak and RMS calculations in a single cache-friendly pass without modifying
 * audio samples or rescanning memory.
 */
class AudioLevelMeter(
    val channelCount: Int = AudioConfig.CHANNELS
) {
    @Volatile private var intervalPeakSample: Int = 0
    @Volatile private var intervalRmsSumSquares: Double = 0.0
    @Volatile private var intervalRmsSampleCount: Long = 0L

    data class Metrics(
        val peak: Int,
        val peakLeft: Int,
        val peakRight: Int,
        val rms: Double,
        val rmsLeft: Double,
        val rmsRight: Double,
        val peakPercent: Int,
        val rmsPercent: Int,
        val frameCount: Int
    )

    /**
     * Analyzes a PCM buffer in a single pass.
     *
     * @param buffer byte array containing raw interleaved PCM
     * @param offset start offset in buffer
     * @param length number of valid bytes in buffer
     * @param is24Bit true for 24-bit packed PCM (3 bytes/sample), false for 16-bit (2 bytes/sample)
     * @param updateInterval if true, updates interval peak and RMS accumulators
     */
    fun analyze(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        is24Bit: Boolean,
        updateInterval: Boolean = true
    ): Metrics {
        val bytesPerSample = if (is24Bit) 3 else 2
        val bytesPerFrame = channelCount * bytesPerSample
        if (length < bytesPerFrame || offset < 0 || offset + length > buffer.size) {
            return Metrics(
                peak = 0, peakLeft = 0, peakRight = 0,
                rms = 0.0, rmsLeft = 0.0, rmsRight = 0.0,
                peakPercent = 0, rmsPercent = 0,
                frameCount = 0
            )
        }

        val frameCount = length / bytesPerFrame
        var peakL = 0
        var peakR = 0
        var peakAll = 0
        var sumSqL = 0.0
        var sumSqR = 0.0
        var sumSqAll = 0.0

        var frameBase = offset
        if (is24Bit) {
            if (channelCount == 2) {
                // Optimized 24-bit packed stereo path (6 bytes per frame)
                for (f in 0 until frameCount) {
                    // Left sample (bytes 0, 1, 2)
                    val rawL = (buffer[frameBase].toInt() and 0xFF) or
                        ((buffer[frameBase + 1].toInt() and 0xFF) shl 8) or
                        ((buffer[frameBase + 2].toInt() and 0xFF) shl 16)
                    val sampleL = if (rawL and 0x800000 != 0) rawL or 0xFF000000.toInt() else rawL
                    val absL = abs(sampleL)
                    if (absL > peakL) peakL = absL
                    val dL = sampleL.toDouble()
                    sumSqL += dL * dL

                    // Right sample (bytes 3, 4, 5)
                    val rawR = (buffer[frameBase + 3].toInt() and 0xFF) or
                        ((buffer[frameBase + 4].toInt() and 0xFF) shl 8) or
                        ((buffer[frameBase + 5].toInt() and 0xFF) shl 16)
                    val sampleR = if (rawR and 0x800000 != 0) rawR or 0xFF000000.toInt() else rawR
                    val absR = abs(sampleR)
                    if (absR > peakR) peakR = absR
                    val dR = sampleR.toDouble()
                    sumSqR += dR * dR

                    frameBase += 6
                }
                peakAll = maxOf(peakL, peakR)
                sumSqAll = sumSqL + sumSqR
            } else {
                for (f in 0 until frameCount) {
                    for (ch in 0 until channelCount) {
                        val sOff = frameBase + ch * 3
                        val raw = (buffer[sOff].toInt() and 0xFF) or
                            ((buffer[sOff + 1].toInt() and 0xFF) shl 8) or
                            ((buffer[sOff + 2].toInt() and 0xFF) shl 16)
                        val sample = if (raw and 0x800000 != 0) raw or 0xFF000000.toInt() else raw
                        val absVal = abs(sample)
                        if (absVal > peakAll) peakAll = absVal
                        val d = sample.toDouble()
                        sumSqAll += d * d
                        if (ch == 0) {
                            if (absVal > peakL) peakL = absVal
                            sumSqL += d * d
                        } else if (ch == 1) {
                            if (absVal > peakR) peakR = absVal
                            sumSqR += d * d
                        }
                    }
                    frameBase += bytesPerFrame
                }
            }
        } else {
            if (channelCount == 2) {
                // Optimized 16-bit stereo path (4 bytes per frame)
                for (f in 0 until frameCount) {
                    // Left sample (bytes 0, 1)
                    val rawL = (buffer[frameBase].toInt() and 0xFF) or (buffer[frameBase + 1].toInt() shl 8)
                    val sampleL = rawL.toShort().toInt()
                    val absL = abs(sampleL)
                    if (absL > peakL) peakL = absL
                    val dL = sampleL.toDouble()
                    sumSqL += dL * dL

                    // Right sample (bytes 2, 3)
                    val rawR = (buffer[frameBase + 2].toInt() and 0xFF) or (buffer[frameBase + 3].toInt() shl 8)
                    val sampleR = rawR.toShort().toInt()
                    val absR = abs(sampleR)
                    if (absR > peakR) peakR = absR
                    val dR = sampleR.toDouble()
                    sumSqR += dR * dR

                    frameBase += 4
                }
                peakAll = maxOf(peakL, peakR)
                sumSqAll = sumSqL + sumSqR
            } else {
                for (f in 0 until frameCount) {
                    for (ch in 0 until channelCount) {
                        val sOff = frameBase + ch * 2
                        val raw = (buffer[sOff].toInt() and 0xFF) or (buffer[sOff + 1].toInt() shl 8)
                        val sample = raw.toShort().toInt()
                        val absVal = abs(sample)
                        if (absVal > peakAll) peakAll = absVal
                        val d = sample.toDouble()
                        sumSqAll += d * d
                        if (ch == 0) {
                            if (absVal > peakL) peakL = absVal
                            sumSqL += d * d
                        } else if (ch == 1) {
                            if (absVal > peakR) peakR = absVal
                            sumSqR += d * d
                        }
                    }
                    frameBase += bytesPerFrame
                }
            }
        }

        val totalSamples = frameCount * channelCount
        val rmsAll = if (totalSamples > 0) sqrt(sumSqAll / totalSamples) else 0.0
        val rmsL = if (frameCount > 0) sqrt(sumSqL / frameCount) else 0.0
        val rmsR = if (frameCount > 0) sqrt(sumSqR / frameCount) else 0.0

        val peakPct = calculatePeakPercent(peakAll, is24Bit)
        val rmsPct = calculateRmsPercent(rmsAll, is24Bit)

        if (updateInterval) {
            synchronized(this) {
                if (peakAll > intervalPeakSample) intervalPeakSample = peakAll
                intervalRmsSumSquares += sumSqAll
                intervalRmsSampleCount += totalSamples
            }
        }

        return Metrics(
            peak = peakAll,
            peakLeft = peakL,
            peakRight = peakR,
            rms = rmsAll,
            rmsLeft = rmsL,
            rmsRight = rmsR,
            peakPercent = peakPct,
            rmsPercent = rmsPct,
            frameCount = frameCount
        )
    }

    /**
     * Atomically retrieves the highest peak sample recorded since the last call and resets it.
     */
    @Synchronized
    fun getAndResetIntervalPeak(): Int {
        val p = intervalPeakSample
        intervalPeakSample = 0
        return p
    }

    /**
     * Atomically retrieves the RMS energy recorded since the last call and resets it.
     */
    @Synchronized
    fun getAndResetIntervalRms(): Double {
        val rms = if (intervalRmsSampleCount > 0) {
            sqrt(intervalRmsSumSquares / intervalRmsSampleCount)
        } else {
            0.0
        }
        intervalRmsSumSquares = 0.0
        intervalRmsSampleCount = 0L
        return rms
    }

    /**
     * Resets all accumulated interval statistics.
     */
    @Synchronized
    fun resetInterval() {
        intervalPeakSample = 0
        intervalRmsSumSquares = 0.0
        intervalRmsSampleCount = 0L
    }

    companion object {
        const val MAX_AMPLITUDE_16BIT = 32768.0
        const val MAX_AMPLITUDE_24BIT = 8388608.0

        fun calculatePeakPercent(peak: Int, is24Bit: Boolean): Int {
            val maxScale = if (is24Bit) 8388607.0 else 32767.0
            return Math.round((peak.toDouble() * 100.0) / maxScale).toInt().coerceIn(0, 100)
        }

        fun calculateRmsPercent(rms: Double, is24Bit: Boolean): Int {
            val maxScale = if (is24Bit) 8388607.0 else 32767.0
            return Math.round((rms * 100.0) / maxScale).toInt().coerceIn(0, 100)
        }

        fun amplitudeToDbfs(amplitude: Double, is24Bit: Boolean): Double {
            if (amplitude <= 0.0) return -120.0
            val maxAmp = if (is24Bit) MAX_AMPLITUDE_24BIT else MAX_AMPLITUDE_16BIT
            val dbfs = 20.0 * log10(amplitude / maxAmp)
            return maxOf(-120.0, dbfs)
        }

        fun analyzeStatic(
            buffer: ByteArray,
            offset: Int,
            length: Int,
            is24Bit: Boolean,
            channelCount: Int = AudioConfig.CHANNELS
        ): Metrics {
            val meter = AudioLevelMeter(channelCount)
            return meter.analyze(buffer, offset, length, is24Bit, updateInterval = false)
        }
    }
}
