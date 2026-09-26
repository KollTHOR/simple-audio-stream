package com.example.audiostreamer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.audiostreamer.Equalizer12Band.Companion.BandKind
import kotlin.math.abs
import kotlin.math.sin

class Equalizer12BandTest {

    // ---------------------------------------------------------------------------------------------
    // Structure
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testInitializationAndBands() {
        val eq = Equalizer12Band(48000)
        assertEquals(12, Equalizer12Band.BAND_COUNT)
        assertEquals(12, Equalizer12Band.CENTER_FREQUENCIES.size)
        assertEquals(12, Equalizer12Band.BAND_LABELS.size)
        assertFalse(eq.isEnabled)
        assertEquals("Flat", eq.currentPreset)

        val gains = eq.getAllBandGains()
        assertEquals(12, gains.size)
        for (g in gains) {
            assertEquals(0.0f, g, 0.001f)
        }
    }

    @Test
    fun outerBandsAreShelvesAndMiddleBandsArePeaking() {
        assertEquals(BandKind.LOW_SHELF, Equalizer12Band.BAND_KINDS[Equalizer12Band.LOW_SHELF_BAND])
        assertEquals(BandKind.HIGH_SHELF, Equalizer12Band.BAND_KINDS[Equalizer12Band.HIGH_SHELF_BAND])
        for (i in 1 until Equalizer12Band.BAND_COUNT - 1) {
            assertEquals(
                "band $i should be a peaking bell",
                BandKind.PEAKING,
                Equalizer12Band.BAND_KINDS[i]
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Filter design: these assertions are the actual reason the EQ sounds correct.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun everySectionIsExactlyUnityAtZeroGain() {
        val freqs = doubleArrayOf(20.0, 100.0, 1000.0, 10000.0, 20000.0)
        for (band in 0 until Equalizer12Band.BAND_COUNT) {
            for (f in freqs) {
                assertEquals(
                    "band $band at $f Hz",
                    0.0,
                    Equalizer12Band.sectionMagnitudeDb(band, f, 0.0f),
                    0.0005
                )
            }
        }
    }

    @Test
    fun peakingBandsHitTheirRequestedGainAtTheCentreFrequency() {
        for (band in 1 until Equalizer12Band.BAND_COUNT - 1) {
            for (gainDb in floatArrayOf(-12.0f, -6.0f, 3.0f, 12.0f)) {
                val measured = Equalizer12Band.sectionMagnitudeDb(band, Equalizer12Band.CENTER_FREQUENCIES[band].toDouble(), gainDb)
                assertEquals(
                    "band $band at $gainDb dB",
                    gainDb.toDouble(),
                    measured,
                    0.02
                )
            }
        }
    }

    @Test
    fun lowShelfBoostsBelowTheCornerAndIsFlatWellAboveIt() {
        val corner = Equalizer12Band.CENTER_FREQUENCIES[Equalizer12Band.LOW_SHELF_BAND].toDouble()
        val gainDb = 6.0f

        // Deep below the corner the shelf sits close to its nominal gain.
        val deep = Equalizer12Band.sectionMagnitudeDb(Equalizer12Band.LOW_SHELF_BAND, 10.0, gainDb)
        assertEquals("shelf should reach its gain below the corner", gainDb.toDouble(), deep, 0.1)

        // Halfway through the shelf transition.
        val mid = Equalizer12Band.sectionMagnitudeDb(Equalizer12Band.LOW_SHELF_BAND, corner, gainDb)
        assertTrue("midpoint should be roughly half the gain, was $mid", mid in 2.0..4.0)

        // Well above the corner there is no boost left.
        for (f in doubleArrayOf(500.0, 1000.0, 5000.0)) {
            val response = Equalizer12Band.sectionMagnitudeDb(Equalizer12Band.LOW_SHELF_BAND, f, gainDb)
            assertTrue("low shelf should not boost at $f Hz, was $response", abs(response) < 0.05)
        }

        // The defining property of a shelf, not a bell: it does not fall back to -inf outside a
        // narrow band, it asymptotes to 0 dB.
        val veryHigh = Equalizer12Band.sectionMagnitudeDb(Equalizer12Band.LOW_SHELF_BAND, 20000.0, gainDb)
        assertEquals(0.0, veryHigh, 0.0005)
    }

    @Test
    fun highShelfBoostsAboveTheCornerAndIsFlatWellBelowIt() {
        val corner = Equalizer12Band.CENTER_FREQUENCIES[Equalizer12Band.HIGH_SHELF_BAND].toDouble()
        val gainDb = 6.0f

        val deep = Equalizer12Band.sectionMagnitudeDb(Equalizer12Band.HIGH_SHELF_BAND, 23000.0, gainDb)
        assertEquals("shelf should reach its gain above the corner", gainDb.toDouble(), deep, 0.1)

        val mid = Equalizer12Band.sectionMagnitudeDb(Equalizer12Band.HIGH_SHELF_BAND, corner, gainDb)
        assertTrue("midpoint should be roughly half the gain, was $mid", mid in 2.0..4.0)

        for (f in doubleArrayOf(50.0, 500.0, 4000.0)) {
            val response = Equalizer12Band.sectionMagnitudeDb(Equalizer12Band.HIGH_SHELF_BAND, f, gainDb)
            assertTrue("high shelf should not boost at $f Hz, was $response", abs(response) < 0.05)
        }
    }

    @Test
    fun presetsAreGentleEnoughForASmallSpeaker() {
        // The original presets stacked three overlapping bells and peaked near +8 dB, which is
        // what made the old EQ sound bad. Cap every preset well below that.
        for ((name, gains) in Equalizer12Band.PRESETS) {
            var peak = 0.0
            var freq = 0.0
            var f = 20.0
            while (f <= 20000.0) {
                val db = Equalizer12Band.cascadeMagnitudeDb(gains, f)
                if (db > peak) {
                    peak = db
                    freq = f
                }
                f *= 1.05
            }
            assertTrue(
                "$name peaks at $peak dB @ ${freq.toInt()} Hz, expected <= 6 dB",
                peak <= 6.0
            )
        }
    }

    @Test
    fun bassBoostIsATiltNotAThumpAndLeavesTheMidrangeAlone() {
        val gains = Equalizer12Band.PRESETS["Bass Boost"]!!
        assertTrue("should lift the low end", Equalizer12Band.cascadeMagnitudeDb(gains, 30.0) > 2.0)
        assertTrue("should not lift the midrange", abs(Equalizer12Band.cascadeMagnitudeDb(gains, 1000.0)) < 1.0)
        assertTrue("should not lift the treble", abs(Equalizer12Band.cascadeMagnitudeDb(gains, 8000.0)) < 0.1)
    }

    @Test
    fun presetsAndGainClamping() {
        val eq = Equalizer12Band(48000)
        assertTrue(eq.applyPreset("Bass Boost"))
        assertEquals("Bass Boost", eq.currentPreset)
        assertEquals(Equalizer12Band.PRESETS["Bass Boost"]!![0], eq.getBandGain(0), 0.001f)
        assertEquals(0.0f, eq.getBandGain(6), 0.001f)

        assertTrue(eq.applyPreset("Rock"))
        assertEquals("Rock", eq.currentPreset)

        assertFalse(eq.applyPreset("NonExistentPreset"))
    }

    @Test
    fun testCustomBandGainClamping() {
        val eq = Equalizer12Band(48000)
        eq.setBandGain(0, 15.0f)
        assertEquals(12.0f, eq.getBandGain(0), 0.001f)
        assertEquals("Custom", eq.currentPreset)

        eq.setBandGain(0, -20.0f)
        assertEquals(-12.0f, eq.getBandGain(0), 0.001f)
    }

    @Test
    fun sampleRateIsClampedBelowNyquist() {
        // At 44.1 kHz the 20 kHz band has to move down or the filter becomes unstable.
        val measured = Equalizer12Band.sectionMagnitudeDb(11, 20000.0, 6.0f, sampleRate = 44100)
        assertTrue("20 kHz section at 44.1 kHz should stay finite and bounded, was $measured", abs(measured) < 12.0)
    }

    // ---------------------------------------------------------------------------------------------
    // Runtime behaviour
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testBypassWhenDisabledOrFlat() {
        val eq = Equalizer12Band(48000)
        eq.isEnabled = false
        eq.setBandGain(0, 6.0f)

        val testBuffer = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)
        val copy = testBuffer.copyOf()
        eq.process16BitStereo(testBuffer, 0, testBuffer.size)
        assertArrayEquals("disabled EQ must not touch the buffer", copy, testBuffer)

        eq.isEnabled = true
        eq.setBandGain(0, 0.0f)
        val copy2 = testBuffer.copyOf()
        eq.process16BitStereo(testBuffer, 0, testBuffer.size)
        assertArrayEquals("flat EQ must not touch the buffer", copy2, testBuffer)
    }

    @Test
    fun gainChangesAreRampedInsteadOfStepping() {
        val eq = Equalizer12Band(48000)
        eq.isEnabled = true
        eq.snapGainsForTest()
        eq.setBandGain(5, 12.0f)

        // First block should only move a fraction of the way toward the target.
        val firstBlock = 64
        val buf = ByteArray(firstBlock * 4)
        eq.process16BitStereo(buf, 0, buf.size)
        val afterFirst = eq.activeBandGain(5)
        assertTrue(
            "first block should still be ramping, jumped straight to $afterFirst",
            afterFirst < 12.0f && afterFirst > 0.0f
        )

        // Ramping is monotonic and eventually lands on the requested value.
        var previous = afterFirst
        var block = ByteArray(480 * 4)
        var guard = 0
        while (eq.activeBandGain(5) < 11.999f && guard < 100) {
            eq.process16BitStereo(block, 0, block.size)
            val now = eq.activeBandGain(5)
            assertTrue("ramp went backwards: $previous -> $now", now >= previous - 0.0001f)
            previous = now
            guard++
        }
        assertEquals("ramp should complete and land on the target", 12.0f, eq.activeBandGain(5), 0.001f)
    }

    @Test
    fun aNewChangeMidRampRestartsFromTheCurrentGain() {
        val eq = Equalizer12Band(48000)
        eq.isEnabled = true
        eq.snapGainsForTest()
        eq.setBandGain(5, 12.0f)
        val block = ByteArray(64 * 4)
        eq.process16BitStereo(block, 0, block.size)
        val midRamp = eq.activeBandGain(5)

        eq.setBandGain(5, -12.0f)
        eq.process16BitStereo(block, 0, block.size)
        val after = eq.activeBandGain(5)
        assertTrue("must not jump straight to the new target, went $midRamp -> $after", after > -12.0f)
        assertEquals("ramp should continue from the current gain, not restart from zero", midRamp - (midRamp + 12.0f) * (64.0f / (48000.0f * 0.050f)), after, 0.35f)
    }

    @Test
    fun reEnablingResetsStaleFilterMemory() {
        val eq = Equalizer12Band(48000)
        eq.isEnabled = true
        eq.snapGainsForTest()
        eq.setBandGain(5, 12.0f)

        // Run some audio so the filter has meaningful state.
        val buf = ByteArray(4800 * 4)
        for (i in 0 until 4800) {
            val s = (sin(i * 0.05) * 20000).toInt().toShort()
            buf[i * 4] = (s.toInt() and 0xFF).toByte()
            buf[i * 4 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
            buf[i * 4 + 2] = buf[i * 4]
            buf[i * 4 + 3] = buf[i * 4 + 1]
        }
        eq.process16BitStereo(buf, 0, buf.size)

        // Disabling then re-enabling must not replay the old tail.
        eq.isEnabled = false
        val silence = ByteArray(480 * 4)
        val afterDisable = silence.copyOf()
        eq.process16BitStereo(silence, 0, silence.size)
        assertArrayEquals("disabled EQ must pass silence through untouched", afterDisable, silence)

        eq.isEnabled = true
        val afterEnable = silence.copyOf()
        eq.process16BitStereo(silence, 0, silence.size)
        assertArrayEquals(
            "re-enabling with stale filter memory produced a transient from silence",
            afterEnable,
            silence
        )
    }

    @Test
    fun process16BitStereoActuallyBoostsTheSignal() {
        val eq = Equalizer12Band(48000)
        eq.isEnabled = true
        eq.snapGainsForTest()
        eq.setBandGain(5, 6.0f) // 1 kHz bell
        eq.snapGainsForTest()

        val frames = 480
        val buf = ByteArray(frames * 4)
        val freq = 1000.0
        for (i in 0 until frames) {
            val s = (sin(2.0 * Math.PI * freq * i / 48000.0) * 4000).toInt().toShort()
            buf[i * 4] = (s.toInt() and 0xFF).toByte()
            buf[i * 4 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
            buf[i * 4 + 2] = buf[i * 4]
            buf[i * 4 + 3] = buf[i * 4 + 1]
        }
        val inputRms = rms(buf)
        eq.process16BitStereo(buf, 0, buf.size)

        // Measure the tail of the buffer, after the filter has settled.
        val outputRms = rms(buf, (frames - 240) * 4, 240 * 4)
        val gainDb = 20.0 * kotlin.math.log10(outputRms / inputRms)
        assertEquals("1 kHz bell should apply its gain", 6.0, gainDb, 0.5)
    }

    @Test
    fun stackedBoostsAreSoftClippedNotFlatTopped() {
        val eq = Equalizer12Band(48000)
        eq.isEnabled = true
        eq.snapGainsForTest()
        for (i in 0 until Equalizer12Band.BAND_COUNT) eq.setBandGain(i, 12.0f)
        eq.snapGainsForTest()

        // A full-scale square wave is the worst case for a hard limiter: a hard clip would
        // produce identical consecutive samples at the peaks.
        val frames = 4800
        val buf = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val s: Short = if ((i / 50) % 2 == 0) 32767 else -32768
            buf[i * 4] = (s.toInt() and 0xFF).toByte()
            buf[i * 4 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
            buf[i * 4 + 2] = buf[i * 4]
            buf[i * 4 + 3] = buf[i * 4 + 1]
        }
        eq.process16BitStereo(buf, 0, buf.size)

        var consecutiveEqual = 0
        var maxRun = 0
        for (i in 1 until frames) {
            val prev = readSample(buf, i - 1)
            val cur = readSample(buf, i)
            if (cur == prev) {
                consecutiveEqual++
                if (consecutiveEqual > maxRun) maxRun = consecutiveEqual
            } else {
                consecutiveEqual = 0
            }
        }

        assertTrue(
            "soft clipping should not produce a flat top (longest run of identical samples: $maxRun)",
            maxRun < 200
        )
        for (i in 0 until frames) {
            val v = readSample(buf, i)
            assertTrue("sample $i out of range: $v", v >= -32768 && v <= 32767)
        }
    }

    @Test
    fun softClipperIsMonotonicAndC1Continuous() {
        var previous = Float.NEGATIVE_INFINITY
        var x = -40000f
        while (x <= 40000f) {
            val y = Equalizer12Band.softClip(x, 32768.0)
            assertTrue("soft clip must be monotonic at $x", y >= previous - 1e-4f)
            assertTrue("soft clip must stay in range at $x (was $y)", y >= -32768f && y <= 32767f)
            previous = y
            x += 37f
        }
        // Below the knee the curve must be perfectly transparent.
        val knee = 32768f * 0.6f
        for (v in floatArrayOf(0f, 1000f, -1000f, knee * 0.5f, -knee * 0.9f)) {
            assertEquals("knee must be transparent", v, Equalizer12Band.softClip(v, 32768.0), 0.001f)
        }
        // Slope at the knee must match the linear region, otherwise the transition itself clicks.
        val eps = 1.0f
        val slope = (Equalizer12Band.softClip(knee + eps, 32768.0) - Equalizer12Band.softClip(knee - eps, 32768.0)) / (2 * eps)
        assertEquals("knee must be C1 continuous", 1.0f, slope, 0.01f)
    }

    @Test
    fun process24BitStereoHandlesNegativeSamples() {
        val eq = Equalizer12Band(48000)
        eq.isEnabled = true
        eq.snapGainsForTest()
        eq.setBandGain(5, 6.0f)
        eq.snapGainsForTest()

        val frames = 960
        val buf = ByteArray(frames * 6)
        for (i in 0 until frames) {
            val v = (sin(2.0 * Math.PI * 1000.0 * i / 48000.0) * 2000000.0).toInt()
            writeSample24(buf, i * 6, v)
            writeSample24(buf, i * 6 + 3, v)
        }
        val before = buf.copyOf()
        eq.process24BitStereo(buf, 0, buf.size)
        assertNotEquals("24-bit path should have modified the buffer", before.toList(), buf.toList())

        // A sign-extension bug shows up as wildly asymmetric positive/negative peaks.
        var maxPositive = 0
        var maxNegative = 0
        for (i in 0 until frames) {
            val v = readSample24(buf, i * 6)
            if (v > maxPositive) maxPositive = v
            if (v < maxNegative) maxNegative = v
        }
        assertTrue("positive peak looks like a sign-extension artifact: $maxPositive", maxPositive <= 8388607)
        assertTrue("negative peak looks truncated: $maxNegative", maxNegative >= -8388608)
        assertTrue(
            "expected a boosted 1 kHz tone, got peak $maxPositive / $maxNegative",
            maxPositive > 2000000 * 1.5 || -maxNegative > 2000000 * 1.5
        )
    }

    @Test
    fun aBandReenteringFromBypassDoesNotReplayAStaleTail() {
        val eq = Equalizer12Band(48000)
        eq.isEnabled = true
        eq.snapGainsForTest()
        eq.setBandGain(5, 12.0f)
        eq.snapGainsForTest()

        // Drive a loud tone so band 5 accumulates a large filter state, then bring it back to 0 dB
        // so the section is bypassed and its state freezes.
        val loud = ByteArray(4800 * 4)
        for (i in 0 until 4800) {
            val s = (sin(2.0 * Math.PI * 1000.0 * i / 48000.0) * 30000).toInt().toShort()
            loud[i * 4] = (s.toInt() and 0xFF).toByte()
            loud[i * 4 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
            loud[i * 4 + 2] = loud[i * 4]
            loud[i * 4 + 3] = loud[i * 4 + 1]
        }
        eq.process16BitStereo(loud, 0, loud.size)

        eq.setBandGain(5, 0.0f)
        eq.snapGainsForTest()

        // Re-engage the band and feed silence. A frozen tail would show up as a non-zero first
        // sample; a clean start must produce exact silence.
        eq.setBandGain(5, 12.0f)
        eq.snapGainsForTest()
        val silence = ByteArray(480 * 4)
        val expected = silence.copyOf()
        eq.process16BitStereo(silence, 0, silence.size)
        assertArrayEquals(
            "re-engaging a band replayed a frozen filter tail",
            expected,
            silence
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun readSample(buf: ByteArray, frame: Int): Int {
        val p = frame * 4
        return ((buf[p].toInt() and 0xFF) or (buf[p + 1].toInt() shl 8)).toShort().toInt()
    }

    private fun writeSample24(buf: ByteArray, p: Int, v: Int) {
        buf[p] = (v and 0xFF).toByte()
        buf[p + 1] = ((v shr 8) and 0xFF).toByte()
        buf[p + 2] = ((v shr 16) and 0xFF).toByte()
    }

    private fun readSample24(buf: ByteArray, p: Int): Int {
        val raw = (buf[p].toInt() and 0xFF) or
            ((buf[p + 1].toInt() and 0xFF) shl 8) or
            ((buf[p + 2].toInt() and 0xFF) shl 16)
        return if (raw and 0x800000 != 0) raw - 0x1000000 else raw
    }

    private fun rms(buf: ByteArray, from: Int = 0, len: Int = buf.size): Double {
        var sum = 0.0
        var count = 0
        var p = from
        while (p + 1 < from + len && p + 3 < buf.size) {
            val s = ((buf[p].toInt() and 0xFF) or (buf[p + 1].toInt() shl 8)).toShort().toDouble()
            sum += s * s
            count++
            p += 4
        }
        return if (count == 0) 0.0 else kotlin.math.sqrt(sum / count)
    }
}
