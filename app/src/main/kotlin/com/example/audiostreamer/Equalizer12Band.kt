package com.example.audiostreamer

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * High-performance 12-Band Master Equalizer for 16-bit and 24-bit stereo PCM streams.
 *
 * Implements a cascaded series of 2nd-order Direct Form II Transposed Biquad Peaking EQ
 * filters (Robert Bristow-Johnson Audio EQ Cookbook) optimized for real-time mobile audio.
 *
 * Features:
 * - 12 Standard ISO/audio center frequencies (32 Hz to 20 kHz).
 * - Real-time gain adjustment (-12 dB to +12 dB) per band.
 * - Zero-allocation in-place buffer processing.
 * - Automatic band bypass when gain is 0 dB; full buffer bypass when flat or disabled.
 * - Soft-clamping against digital clipping.
 * - Preset management and SharedPreferences persistence.
 */
class Equalizer12Band(
    initialSampleRate: Int = AudioConfig.SAMPLE_RATE_48000
) {
    companion object {
        const val BAND_COUNT = 12
        const val MIN_GAIN_DB = -12.0f
        const val MAX_GAIN_DB = 12.0f
        const val DEFAULT_Q = 1.4142f // Butterworth-equivalent for smooth adjacent band overlap

        val CENTER_FREQUENCIES = floatArrayOf(
            32.0f,
            64.0f,
            125.0f,
            250.0f,
            500.0f,
            1000.0f,
            2000.0f,
            4000.0f,
            8000.0f,
            12000.0f,
            16000.0f,
            20000.0f
        )

        val BAND_LABELS = arrayOf(
            "32Hz", "64Hz", "125Hz", "250Hz", "500Hz", "1kHz",
            "2kHz", "4kHz", "8kHz", "12kHz", "16kHz", "20kHz"
        )

        val PRESETS = mapOf(
            "Flat" to FloatArray(BAND_COUNT) { 0.0f },
            "Bass Boost" to floatArrayOf(6.0f, 5.0f, 4.0f, 2.5f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f),
            "Treble Boost" to floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.5f, 1.5f, 3.0f, 4.5f, 5.5f, 6.0f),
            "Rock" to floatArrayOf(4.5f, 3.5f, 2.0f, 0.0f, -1.0f, -1.5f, 0.0f, 1.5f, 3.0f, 4.0f, 4.5f, 4.5f),
            "Vocal" to floatArrayOf(-2.0f, -2.5f, -1.5f, 0.5f, 2.5f, 4.0f, 4.0f, 3.0f, 1.5f, 0.0f, -1.5f, -2.0f),
            "Electronic" to floatArrayOf(5.5f, 4.5f, 2.5f, 0.0f, -1.0f, 0.5f, 1.5f, 2.5f, 3.5f, 4.5f, 5.0f, 4.0f),
            "Acoustic" to floatArrayOf(3.5f, 2.5f, 1.0f, 0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 3.5f, 3.0f, 2.0f)
        )

        private const val PREF_KEY_ENABLED = "eq_enabled"
        private const val PREF_KEY_PRESET = "eq_preset"
        private const val PREF_KEY_BAND_PREFIX = "eq_band_"
    }

    @Volatile
    var isEnabled: Boolean = false

    @Volatile
    var currentPreset: String = "Flat"
        private set

    var sampleRate: Int = initialSampleRate
        set(value) {
            if (field != value && value > 0) {
                field = value
                recalculateAllCoefficients()
            }
        }

    // Gains in dB for each band (-12f..+12f)
    private val bandGainsDb = FloatArray(BAND_COUNT) { 0.0f }

    // Biquad coefficients per band: b0, b1, b2, a1, a2 (normalized by a0)
    private val b0 = FloatArray(BAND_COUNT)
    private val b1 = FloatArray(BAND_COUNT)
    private val b2 = FloatArray(BAND_COUNT)
    private val a1 = FloatArray(BAND_COUNT)
    private val a2 = FloatArray(BAND_COUNT)
    private val isBandBypassed = BooleanArray(BAND_COUNT) { true }

    // Direct Form II Transposed filter delay registers: 2 states per channel per band
    private val d1L = FloatArray(BAND_COUNT)
    private val d2L = FloatArray(BAND_COUNT)
    private val d1R = FloatArray(BAND_COUNT)
    private val d2R = FloatArray(BAND_COUNT)

    init {
        recalculateAllCoefficients()
    }

    fun getBandGain(bandIndex: Int): Float {
        if (bandIndex !in 0 until BAND_COUNT) return 0.0f
        return bandGainsDb[bandIndex]
    }

    fun getAllBandGains(): FloatArray {
        return bandGainsDb.copyOf()
    }

    @Synchronized
    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in 0 until BAND_COUNT) return
        val clamped = gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        bandGainsDb[bandIndex] = clamped
        currentPreset = "Custom"
        recalculateBand(bandIndex)
    }

    @Synchronized
    fun setAllBandGains(gains: FloatArray, presetName: String = "Custom") {
        for (i in 0 until minOf(BAND_COUNT, gains.size)) {
            bandGainsDb[i] = gains[i].coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
            recalculateBand(i)
        }
        currentPreset = presetName
    }

    @Synchronized
    fun applyPreset(presetName: String): Boolean {
        val preset = PRESETS[presetName] ?: return false
        setAllBandGains(preset, presetName)
        return true
    }

    @Synchronized
    fun resetFilterState() {
        for (i in 0 until BAND_COUNT) {
            d1L[i] = 0.0f
            d2L[i] = 0.0f
            d1R[i] = 0.0f
            d2R[i] = 0.0f
        }
    }

    private fun recalculateAllCoefficients() {
        for (i in 0 until BAND_COUNT) {
            recalculateBand(i)
        }
    }

    private fun recalculateBand(band: Int) {
        val gain = bandGainsDb[band]
        if (abs(gain) < 0.05f) {
            isBandBypassed[band] = true
            b0[band] = 1.0f
            b1[band] = 0.0f
            b2[band] = 0.0f
            a1[band] = 0.0f
            a2[band] = 0.0f
            return
        }

        isBandBypassed[band] = false

        // Center frequency clamped to safe zone below Nyquist
        val maxSafeFreq = (sampleRate * 0.45f).coerceAtMost(22000.0f)
        val f0 = CENTER_FREQUENCIES[band].coerceAtMost(maxSafeFreq)

        val a = 10.0.pow((gain / 40.0)).toFloat()
        val omega0 = (2.0 * PI * f0 / sampleRate).toFloat()
        val alpha = (sin(omega0.toDouble()) / (2.0 * DEFAULT_Q)).toFloat()
        val cosOmega0 = cos(omega0.toDouble()).toFloat()

        val rawB0 = 1.0f + alpha * a
        val rawB1 = -2.0f * cosOmega0
        val rawB2 = 1.0f - alpha * a
        val rawA0 = 1.0f + alpha / a
        val rawA1 = -2.0f * cosOmega0
        val rawA2 = 1.0f - alpha / a

        b0[band] = rawB0 / rawA0
        b1[band] = rawB1 / rawA0
        b2[band] = rawB2 / rawA0
        a1[band] = rawA1 / rawA0
        a2[band] = rawA2 / rawA0
    }

    /**
     * In-place stereo 16-bit PCM equalization (4 bytes per frame: 2 bytes Left, 2 bytes Right).
     */
    fun process16BitStereo(buffer: ByteArray, offset: Int, length: Int) {
        if (!isEnabled) return
        var allBypassed = true
        for (i in 0 until BAND_COUNT) {
            if (!isBandBypassed[i]) {
                allBypassed = false
                break
            }
        }
        if (allBypassed) return

        val frameCount = length / 4
        var pos = offset

        for (f in 0 until frameCount) {
            // Read 16-bit signed LE
            val rawL = (buffer[pos].toInt() and 0xFF) or (buffer[pos + 1].toInt() shl 8)
            val rawR = (buffer[pos + 2].toInt() and 0xFF) or (buffer[pos + 3].toInt() shl 8)
            var sampleL = rawL.toShort().toFloat()
            var sampleR = rawR.toShort().toFloat()

            // Cascade through 12 bands
            for (b in 0 until BAND_COUNT) {
                if (isBandBypassed[b]) continue

                val coeffB0 = b0[b]
                val coeffB1 = b1[b]
                val coeffB2 = b2[b]
                val coeffA1 = a1[b]
                val coeffA2 = a2[b]

                // Left channel Direct Form II Transposed
                val yL = coeffB0 * sampleL + d1L[b]
                d1L[b] = coeffB1 * sampleL - coeffA1 * yL + d2L[b]
                d2L[b] = coeffB2 * sampleL - coeffA2 * yL
                sampleL = yL

                // Right channel Direct Form II Transposed
                val yR = coeffB0 * sampleR + d1R[b]
                d1R[b] = coeffB1 * sampleR - coeffA1 * yR + d2R[b]
                d2R[b] = coeffB2 * sampleR - coeffA2 * yR
                sampleR = yR
            }

            // Soft-clamping / saturation to 16-bit range
            val clampedL = sampleL.coerceIn(-32768.0f, 32767.0f).toInt()
            val clampedR = sampleR.coerceIn(-32768.0f, 32767.0f).toInt()

            // Write back in-place
            buffer[pos] = (clampedL and 0xFF).toByte()
            buffer[pos + 1] = ((clampedL ushr 8) and 0xFF).toByte()
            buffer[pos + 2] = (clampedR and 0xFF).toByte()
            buffer[pos + 3] = ((clampedR ushr 8) and 0xFF).toByte()

            pos += 4
        }
    }

    /**
     * In-place stereo 24-bit packed PCM equalization (6 bytes per frame: 3 bytes Left, 3 bytes Right).
     */
    fun process24BitStereo(buffer: ByteArray, offset: Int, length: Int) {
        if (!isEnabled) return
        var allBypassed = true
        for (i in 0 until BAND_COUNT) {
            if (!isBandBypassed[i]) {
                allBypassed = false
                break
            }
        }
        if (allBypassed) return

        val frameCount = length / 6
        var pos = offset

        for (f in 0 until frameCount) {
            // Read 24-bit packed signed LE
            val b0L = buffer[pos].toInt() and 0xFF
            val b1L = buffer[pos + 1].toInt() and 0xFF
            val b2L = buffer[pos + 2].toInt()
            val rawL = b0L or (b1L shl 8) or (b2L shl 16)

            val b0R = buffer[pos + 3].toInt() and 0xFF
            val b1R = buffer[pos + 4].toInt() and 0xFF
            val b2R = buffer[pos + 5].toInt()
            val rawR = b0R or (b1R shl 8) or (b2R shl 16)

            var sampleL = rawL.toFloat()
            var sampleR = rawR.toFloat()

            for (b in 0 until BAND_COUNT) {
                if (isBandBypassed[b]) continue

                val coeffB0 = b0[b]
                val coeffB1 = b1[b]
                val coeffB2 = b2[b]
                val coeffA1 = a1[b]
                val coeffA2 = a2[b]

                val yL = coeffB0 * sampleL + d1L[b]
                d1L[b] = coeffB1 * sampleL - coeffA1 * yL + d2L[b]
                d2L[b] = coeffB2 * sampleL - coeffA2 * yL
                sampleL = yL

                val yR = coeffB0 * sampleR + d1R[b]
                d1R[b] = coeffB1 * sampleR - coeffA1 * yR + d2R[b]
                d2R[b] = coeffB2 * sampleR - coeffA2 * yR
                sampleR = yR
            }

            val clampedL = sampleL.coerceIn(-8388608.0f, 8388607.0f).toInt()
            val clampedR = sampleR.coerceIn(-8388608.0f, 8388607.0f).toInt()

            buffer[pos] = (clampedL and 0xFF).toByte()
            buffer[pos + 1] = ((clampedL ushr 8) and 0xFF).toByte()
            buffer[pos + 2] = ((clampedL ushr 16) and 0xFF).toByte()
            buffer[pos + 3] = (clampedR and 0xFF).toByte()
            buffer[pos + 4] = ((clampedR ushr 8) and 0xFF).toByte()
            buffer[pos + 5] = ((clampedR ushr 16) and 0xFF).toByte()

            pos += 6
        }
    }

    fun saveToPreferences(prefs: SharedPreferences) {
        prefs.edit().apply {
            putBoolean(PREF_KEY_ENABLED, isEnabled)
            putString(PREF_KEY_PRESET, currentPreset)
            for (i in 0 until BAND_COUNT) {
                putFloat("$PREF_KEY_BAND_PREFIX$i", bandGainsDb[i])
            }
            apply()
        }
    }

    fun loadFromPreferences(prefs: SharedPreferences) {
        isEnabled = prefs.getBoolean(PREF_KEY_ENABLED, false)
        val savedPreset = prefs.getString(PREF_KEY_PRESET, "Flat") ?: "Flat"
        currentPreset = savedPreset
        for (i in 0 until BAND_COUNT) {
            val gain = prefs.getFloat("$PREF_KEY_BAND_PREFIX$i", 0.0f)
            bandGainsDb[i] = gain.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
            recalculateBand(i)
        }
    }
}
