package com.example.audiostreamer

import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Speaker-correction equalizer applied to received audio on the receiver's playback thread.
 *
 * Twelve second-order sections, each a Direct Form II Transposed biquad, cascaded in series.
 * The outermost bands are shelving filters (RBJ Audio EQ Cookbook) so the first and last
 * sliders behave like real bass/treble controls; the ten middle bands are peaking bells.
 *
 * Design notes:
 * - Gain changes are ramped over [SMOOTHING_MS] at block rate, so moving a slider cannot
 *   produce zipper noise.
 * - Coefficient arrays are written only from the audio thread. UI updates are published as a
 *   target gain vector plus a version counter, so the audio thread never observes a half-written
 *   coefficient set.
 * - Output is soft-clipped rather than hard-limited, so stacked boosts do not flat-top.
 * - Processing is in place and allocation-free.
 */
class Equalizer12Band(
    initialSampleRate: Int = AudioConfig.SAMPLE_RATE_48000
) {
    companion object {
        const val BAND_COUNT = 12
        const val MIN_GAIN_DB = -12.0f
        const val MAX_GAIN_DB = 12.0f
        const val DEFAULT_Q = 1.4142f // Butterworth-equivalent for smooth adjacent band overlap
        const val LOW_SHELF_BAND = 0
        const val HIGH_SHELF_BAND = BAND_COUNT - 1

        internal const val SHELF_SLOPE = 1.0f
        internal const val BYPASS_GAIN_DB = 0.01f
        internal const val SMOOTHING_MS = 50
        private const val CLIP_KNEE = 0.6f
        private const val CLIP_CEILING = 0.999f

        internal enum class BandKind { LOW_SHELF, PEAKING, HIGH_SHELF }

        internal val BAND_KINDS: Array<BandKind> = Array(BAND_COUNT) { i ->
            when (i) {
                LOW_SHELF_BAND -> BandKind.LOW_SHELF
                HIGH_SHELF_BAND -> BandKind.HIGH_SHELF
                else -> BandKind.PEAKING
            }
        }

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
            "Bass Boost" to floatArrayOf(5.0f, 2.5f, 1.0f, 0.5f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f),
            "Treble Boost" to floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.5f, 2.0f, 1.5f, 3.0f),
            "Rock" to floatArrayOf(3.0f, 0.0f, 1.5f, 0.0f, -1.5f, 0.0f, 1.0f, 2.0f, 2.5f, 0.0f, 0.0f, 2.0f),
            "Vocal" to floatArrayOf(-2.0f, 0.0f, 0.0f, 1.0f, 2.0f, 3.0f, 3.0f, 2.0f, 1.0f, 0.0f, 0.0f, -1.5f),
            "Electronic" to floatArrayOf(4.0f, 2.0f, 0.0f, 0.0f, 0.0f, -2.0f, 1.0f, 1.5f, 2.0f, 0.0f, 0.0f, 1.5f),
            "Acoustic" to floatArrayOf(2.5f, 0.0f, 1.0f, 0.5f, 0.0f, 0.0f, 1.0f, 1.5f, 1.5f, 0.0f, 0.0f, 1.5f)
        )

        private const val PREF_KEY_ENABLED = "eq_enabled"
        private const val PREF_KEY_PRESET = "eq_preset"
        private const val PREF_KEY_BAND_PREFIX = "eq_band_"

        /**
         * Writes b0, b1, b2, a1, a2 for one section into [out] (length >= 5).
         * Pure function: no state, safe to call from tests.
         */
        internal fun computeCoefficients(
            kind: BandKind,
            centerHz: Float,
            gainDb: Float,
            sampleRate: Int,
            out: FloatArray,
            offset: Int = 0
        ) {
            val maxSafeFreq = (sampleRate * 0.45f).coerceAtMost(22000.0f)
            val f0 = centerHz.coerceAtMost(maxSafeFreq).coerceAtLeast(10.0f)
            val amp = 10.0.pow((gainDb / 40.0)).toFloat()
            val omega0 = (2.0 * PI * f0 / sampleRate).toFloat()
            val cosW0 = cos(omega0.toDouble()).toFloat()

            var b0: Float
            var b1: Float
            var b2: Float
            var a0: Float
            var a1: Float
            var a2: Float

            if (kind == BandKind.PEAKING) {
                val alpha = (sin(omega0.toDouble()) / (2.0 * DEFAULT_Q)).toFloat()
                b0 = 1.0f + alpha * amp
                b1 = -2.0f * cosW0
                b2 = 1.0f - alpha * amp
                a0 = 1.0f + alpha / amp
                a1 = -2.0f * cosW0
                a2 = 1.0f - alpha / amp
            } else {
                val sqrtAmp = sqrt(amp)
                val alpha = sin(omega0.toDouble()).toFloat() / 2.0f *
                    sqrt((amp + 1.0f / amp) * (1.0f / SHELF_SLOPE - 1.0f) + 2.0f)
                if (kind == BandKind.LOW_SHELF) {
                    b0 = amp * ((amp + 1.0f) - (amp - 1.0f) * cosW0 + 2.0f * sqrtAmp * alpha)
                    b1 = 2.0f * amp * ((amp - 1.0f) - (amp + 1.0f) * cosW0)
                    b2 = amp * ((amp + 1.0f) - (amp - 1.0f) * cosW0 - 2.0f * sqrtAmp * alpha)
                    a0 = (amp + 1.0f) + (amp - 1.0f) * cosW0 + 2.0f * sqrtAmp * alpha
                    a1 = -2.0f * ((amp - 1.0f) + (amp + 1.0f) * cosW0)
                    a2 = (amp + 1.0f) + (amp - 1.0f) * cosW0 - 2.0f * sqrtAmp * alpha
                } else {
                    b0 = amp * ((amp + 1.0f) + (amp - 1.0f) * cosW0 + 2.0f * sqrtAmp * alpha)
                    b1 = -2.0f * amp * ((amp - 1.0f) + (amp + 1.0f) * cosW0)
                    b2 = amp * ((amp + 1.0f) + (amp - 1.0f) * cosW0 - 2.0f * sqrtAmp * alpha)
                    a0 = (amp + 1.0f) - (amp - 1.0f) * cosW0 + 2.0f * sqrtAmp * alpha
                    a1 = 2.0f * ((amp - 1.0f) - (amp + 1.0f) * cosW0)
                    a2 = (amp + 1.0f) - (amp - 1.0f) * cosW0 - 2.0f * sqrtAmp * alpha
                }
            }

            out[offset] = b0 / a0
            out[offset + 1] = b1 / a0
            out[offset + 2] = b2 / a0
            out[offset + 3] = a1 / a0
            out[offset + 4] = a2 / a0
        }

        /** Steady-state magnitude of one section in dB, for tests and diagnostics. */
        internal fun sectionMagnitudeDb(band: Int, freqHz: Double, gainDb: Float, sampleRate: Int = AudioConfig.SAMPLE_RATE_48000): Double {
            val c = FloatArray(5)
            computeCoefficients(BAND_KINDS[band], CENTER_FREQUENCIES[band], gainDb, sampleRate, c, 0)

            // H(e^jw) = (b0 + b1 e^-jw + b2 e^-j2w) / (1 + a1 e^-jw + a2 e^-j2w)
            val w = 2.0 * PI * freqHz / sampleRate
            val cos1 = cos(w)
            val sin1 = sin(w)
            val cos2 = cos(2.0 * w)
            val sin2 = sin(2.0 * w)

            val numRe = c[0] + c[1] * cos1 + c[2] * cos2
            val numIm = -(c[1] * sin1 + c[2] * sin2)
            val denRe = 1.0 + c[3] * cos1 + c[4] * cos2
            val denIm = -(c[3] * sin1 + c[4] * sin2)

            val numMag = sqrt((numRe * numRe + numIm * numIm).toDouble())
            val denMag = sqrt((denRe * denRe + denIm * denIm).toDouble())
            if (denMag <= 0.0 || numMag <= 0.0) return 0.0
            return 20.0 * kotlin.math.log10(numMag / denMag)
        }

        /** Combined magnitude of every active section in dB. */
        internal fun cascadeMagnitudeDb(gains: FloatArray, freqHz: Double, sampleRate: Int = AudioConfig.SAMPLE_RATE_48000): Double {
            var sum = 0.0
            for (i in 0 until BAND_COUNT) {
                sum += sectionMagnitudeDb(i, freqHz, gains[i], sampleRate)
            }
            return sum
        }

        private const val FULL_SCALE_16 = 32768.0
        private const val FULL_SCALE_24 = 8388608.0

        /**
         * C1-continuous soft clipper. Linear below the knee, asymptotically approaching the
         * ceiling above it, so stacked boosts compress instead of hard-clipping.
         */
        internal fun softClip(x: Float, fullScale: Double): Float {
            val knee = fullScale * CLIP_KNEE
            val ceiling = fullScale * CLIP_CEILING
            val ax = abs(x)
            if (ax <= knee) return x
            val headroom = ceiling - knee
            val compressed = (knee + headroom * tanh((ax - knee) / headroom)).toFloat()
            return if (x < 0.0f) -compressed else compressed
        }

        private fun clip16(x: Float): Float = softClip(x, FULL_SCALE_16)
        private fun clip24(x: Float): Float = softClip(x, FULL_SCALE_24)
    }

    @Volatile
    var isEnabled: Boolean = false

    @Volatile
    var currentPreset: String = "Flat"
        private set

    /** Requested rate. Read by the audio thread at each block; the setter is safe from any thread. */
    var sampleRate: Int = initialSampleRate
        set(value) {
            if (field != value && value > 0) {
                field = value
                requestedVersion.incrementAndGet()
            }
        }

    // ---- Published (UI thread writes) state ----------------------------------------------------------------

    private val requestedGains = FloatArray(BAND_COUNT) { 0.0f }
    private val requestedVersion = AtomicInteger(0)

    fun getBandGain(bandIndex: Int): Float {
        if (bandIndex !in 0 until BAND_COUNT) return 0.0f
        return requestedGains[bandIndex]
    }

    fun getAllBandGains(): FloatArray = requestedGains.copyOf()

    @Synchronized
    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in 0 until BAND_COUNT) return
        requestedGains[bandIndex] = gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        currentPreset = "Custom"
        requestedVersion.incrementAndGet()
    }

    @Synchronized
    fun setAllBandGains(gains: FloatArray, presetName: String = "Custom") {
        for (i in 0 until minOf(BAND_COUNT, gains.size)) {
            requestedGains[i] = gains[i].coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        }
        currentPreset = presetName
        requestedVersion.incrementAndGet()
    }

    @Synchronized
    fun applyPreset(presetName: String): Boolean {
        val preset = PRESETS[presetName] ?: return false
        setAllBandGains(preset, presetName)
        return true
    }

    // ---- Audio-thread-owned state -------------------------------------------------------------------------

    private val activeGains = FloatArray(BAND_COUNT) { 0.0f }
    private val rampFrom = FloatArray(BAND_COUNT) { 0.0f }
    private val rampTo = FloatArray(BAND_COUNT) { 0.0f }
    private val coeff = FloatArray(BAND_COUNT * 5)
    private val isBandBypassed = BooleanArray(BAND_COUNT) { true }

    private val d1L = FloatArray(BAND_COUNT)
    private val d2L = FloatArray(BAND_COUNT)
    private val d1R = FloatArray(BAND_COUNT)
    private val d2R = FloatArray(BAND_COUNT)

    private var seenVersion = -1
    private var activeSampleRate = initialSampleRate
    private var rampRemaining = 0
    private var rampTotal = 1
    private var wasEnabled = false

    /**
     * Picks up any pending UI change and advances the gain ramp by [frames] frames. Called once
     * per buffer from the audio thread; all writes to [coeff] and [activeGains] happen here.
     */
    private fun advanceCoefficients(frames: Int) {
        if (frames <= 0) return

        val version = requestedVersion.get()
        if (version != seenVersion) {
            seenVersion = version
            val newRate = sampleRate
            if (newRate != activeSampleRate) {
                activeSampleRate = newRate
                rampRemaining = 0
            }
            for (i in 0 until BAND_COUNT) {
                rampFrom[i] = activeGains[i]
                rampTo[i] = requestedGains[i]
            }
            rampTotal = maxOf(1, (activeSampleRate * SMOOTHING_MS) / 1000)
            rampRemaining = rampTotal
        }

        if (rampRemaining > 0) {
            val step = min(frames, rampRemaining)
            rampRemaining -= step
            val t = 1.0f - (rampRemaining.toFloat() / rampTotal.toFloat())
            for (i in 0 until BAND_COUNT) {
                val g = rampFrom[i] + (rampTo[i] - rampFrom[i]) * t
                activeGains[i] = g
                applySection(i, g, activeSampleRate)
            }
        }
    }

    private fun applySection(band: Int, gainDb: Float, rate: Int) {
        val base = band * 5
        if (abs(gainDb) < BYPASS_GAIN_DB) {
            isBandBypassed[band] = true
            coeff[base] = 1.0f
            coeff[base + 1] = 0.0f
            coeff[base + 2] = 0.0f
            coeff[base + 3] = 0.0f
            coeff[base + 4] = 0.0f
            return
        }
        if (isBandBypassed[band]) {
            // Entering an active section from bypass: start from a clean state so a frozen tail
            // from an earlier pass is not replayed as a click.
            d1L[band] = 0.0f
            d2L[band] = 0.0f
            d1R[band] = 0.0f
            d2R[band] = 0.0f
        }
        isBandBypassed[band] = false
        computeCoefficients(BAND_KINDS[band], CENTER_FREQUENCIES[band], gainDb, rate, coeff, base)
    }

    /** True when no section is doing work, so the buffer can be passed through untouched. */
    private fun allBypassed(): Boolean {
        for (i in 0 until BAND_COUNT) {
            if (!isBandBypassed[i]) return false
        }
        return true
    }

    private fun onEnableEdge() {
        if (isEnabled != wasEnabled) {
            wasEnabled = isEnabled
            resetStateInternal()
        }
    }

    private fun resetStateInternal() {
        d1L.fill(0.0f)
        d2L.fill(0.0f)
        d1R.fill(0.0f)
        d2R.fill(0.0f)
    }

    /** Clears filter memory. Safe to call from any thread; takes effect on the next block. */
    @Synchronized
    fun resetFilterState() {
        resetStateInternal()
    }

    // ---- Processing --------------------------------------------------------------------------------------

    /** In-place stereo 16-bit PCM equalization (4 bytes per frame: 2 bytes Left, 2 bytes Right). */
    fun process16BitStereo(buffer: ByteArray, offset: Int, length: Int) {
        onEnableEdge()
        if (!isEnabled) return
        val frameCount = length / 4
        if (frameCount <= 0) return

        advanceCoefficients(frameCount)
        if (allBypassed()) return

        var pos = offset
        for (f in 0 until frameCount) {
            val rawL = (buffer[pos].toInt() and 0xFF) or (buffer[pos + 1].toInt() shl 8)
            val rawR = (buffer[pos + 2].toInt() and 0xFF) or (buffer[pos + 3].toInt() shl 8)
            var sampleL = rawL.toShort().toFloat()
            var sampleR = rawR.toShort().toFloat()

            for (b in 0 until BAND_COUNT) {
                if (isBandBypassed[b]) continue
                val base = b * 5
                val cb0 = coeff[base]
                val cb1 = coeff[base + 1]
                val cb2 = coeff[base + 2]
                val ca1 = coeff[base + 3]
                val ca2 = coeff[base + 4]

                val yL = cb0 * sampleL + d1L[b]
                d1L[b] = cb1 * sampleL - ca1 * yL + d2L[b]
                d2L[b] = cb2 * sampleL - ca2 * yL
                sampleL = yL

                val yR = cb0 * sampleR + d1R[b]
                d1R[b] = cb1 * sampleR - ca1 * yR + d2R[b]
                d2R[b] = cb2 * sampleR - ca2 * yR
                sampleR = yR
            }

            val outL = clip16(sampleL).toInt()
            val outR = clip16(sampleR).toInt()
            buffer[pos] = (outL and 0xFF).toByte()
            buffer[pos + 1] = ((outL ushr 8) and 0xFF).toByte()
            buffer[pos + 2] = (outR and 0xFF).toByte()
            buffer[pos + 3] = ((outR ushr 8) and 0xFF).toByte()

            pos += 4
        }
    }

    /** In-place stereo 24-bit packed PCM equalization (6 bytes per frame: 3 bytes Left, 3 bytes Right). */
    fun process24BitStereo(buffer: ByteArray, offset: Int, length: Int) {
        onEnableEdge()
        if (!isEnabled) return
        val frameCount = length / 6
        if (frameCount <= 0) return

        advanceCoefficients(frameCount)
        if (allBypassed()) return

        var pos = offset
        for (f in 0 until frameCount) {
            val rawL = ((buffer[pos].toInt() and 0xFF)) or
                ((buffer[pos + 1].toInt() and 0xFF) shl 8) or
                ((buffer[pos + 2].toInt() and 0xFF) shl 16)
            val rawR = ((buffer[pos + 3].toInt() and 0xFF)) or
                ((buffer[pos + 4].toInt() and 0xFF) shl 8) or
                ((buffer[pos + 5].toInt() and 0xFF) shl 16)
            var sampleL = rawL.toFloat()
            var sampleR = rawR.toFloat()

            for (b in 0 until BAND_COUNT) {
                if (isBandBypassed[b]) continue
                val base = b * 5
                val cb0 = coeff[base]
                val cb1 = coeff[base + 1]
                val cb2 = coeff[base + 2]
                val ca1 = coeff[base + 3]
                val ca2 = coeff[base + 4]

                val yL = cb0 * sampleL + d1L[b]
                d1L[b] = cb1 * sampleL - ca1 * yL + d2L[b]
                d2L[b] = cb2 * sampleL - ca2 * yL
                sampleL = yL

                val yR = cb0 * sampleR + d1R[b]
                d1R[b] = cb1 * sampleR - ca1 * yR + d2R[b]
                d2R[b] = cb2 * sampleR - ca2 * yR
                sampleR = yR
            }

            val outL = clip24(sampleL).toInt()
            val outR = clip24(sampleR).toInt()
            buffer[pos] = (outL and 0xFF).toByte()
            buffer[pos + 1] = ((outL ushr 8) and 0xFF).toByte()
            buffer[pos + 2] = ((outL ushr 16) and 0xFF).toByte()
            buffer[pos + 3] = (outR and 0xFF).toByte()
            buffer[pos + 4] = ((outR ushr 8) and 0xFF).toByte()
            buffer[pos + 5] = ((outR ushr 16) and 0xFF).toByte()

            pos += 6
        }
    }

    /** Current ramped gain for a band, as opposed to the requested UI value. Test/diagnostic hook. */
    internal fun activeBandGain(bandIndex: Int): Float {
        if (bandIndex !in 0 until BAND_COUNT) return 0.0f
        return activeGains[bandIndex]
    }

    // ---- Persistence -------------------------------------------------------------------------------------

    fun saveToPreferences(prefs: SharedPreferences) {
        val snapshot = requestedGains.copyOf()
        prefs.edit().apply {
            putBoolean(PREF_KEY_ENABLED, isEnabled)
            putString(PREF_KEY_PRESET, currentPreset)
            for (i in 0 until BAND_COUNT) {
                putFloat("$PREF_KEY_BAND_PREFIX$i", snapshot[i])
            }
            apply()
        }
    }

    @Synchronized
    fun loadFromPreferences(prefs: SharedPreferences) {
        isEnabled = prefs.getBoolean(PREF_KEY_ENABLED, false)
        currentPreset = prefs.getString(PREF_KEY_PRESET, "Flat") ?: "Flat"
        for (i in 0 until BAND_COUNT) {
            requestedGains[i] = prefs.getFloat("$PREF_KEY_BAND_PREFIX$i", 0.0f).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        }
        requestedVersion.incrementAndGet()
    }

    /** Applies a gain change immediately with no ramp. Test hook. */
    internal fun snapGainsForTest() {
        val version = requestedVersion.get()
        seenVersion = version
        activeSampleRate = sampleRate
        for (i in 0 until BAND_COUNT) {
            activeGains[i] = requestedGains[i]
            applySection(i, activeGains[i], activeSampleRate)
        }
        rampRemaining = 0
    }
}
