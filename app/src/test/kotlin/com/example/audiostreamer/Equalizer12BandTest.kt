package com.example.audiostreamer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Equalizer12BandTest {

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
    fun testApplyPreset() {
        val eq = Equalizer12Band(48000)
        assertTrue(eq.applyPreset("Bass Boost"))
        assertEquals("Bass Boost", eq.currentPreset)
        assertEquals(6.0f, eq.getBandGain(0), 0.001f)
        assertEquals(5.0f, eq.getBandGain(1), 0.001f)
        assertEquals(0.0f, eq.getBandGain(6), 0.001f)

        assertTrue(eq.applyPreset("Rock"))
        assertEquals("Rock", eq.currentPreset)

        assertFalse(eq.applyPreset("NonExistentPreset"))
    }

    @Test
    fun testCustomBandGainClamping() {
        val eq = Equalizer12Band(48000)
        eq.setBandGain(0, 15.0f) // Should clamp to +12.0
        assertEquals(12.0f, eq.getBandGain(0), 0.001f)
        assertEquals("Custom", eq.currentPreset)

        eq.setBandGain(0, -20.0f) // Should clamp to -12.0
        assertEquals(-12.0f, eq.getBandGain(0), 0.001f)
    }

    @Test
    fun testBypassWhenDisabledOrFlat() {
        val eq = Equalizer12Band(48000)
        eq.isEnabled = false
        eq.setBandGain(0, 6.0f)

        val testBuffer = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)
        val copy = testBuffer.copyOf()

        eq.process16BitStereo(testBuffer, 0, testBuffer.size)
        assertArrayEquals("Buffer must remain untouched when disabled", copy, testBuffer)

        // Enabled but all bands flat
        eq.isEnabled = true
        eq.applyPreset("Flat")
        eq.process16BitStereo(testBuffer, 0, testBuffer.size)
        assertArrayEquals("Buffer must remain untouched when flat", copy, testBuffer)
    }

    @Test
    fun testProcess16BitStereoModifiesBufferWhenActive() {
        val eq = Equalizer12Band(48000)
        eq.isEnabled = true
        eq.applyPreset("Bass Boost")

        // 16-bit PCM test buffer (synthetic low-frequency sine/impulse pattern)
        val buffer = ByteArray(128)
        for (i in 0 until 32) {
            val sample = (kotlin.math.sin(i * 0.1) * 10000.0).toInt().toShort()
            val pos = i * 4
            buffer[pos] = (sample.toInt() and 0xFF).toByte()
            buffer[pos + 1] = ((sample.toInt() ushr 8) and 0xFF).toByte()
            buffer[pos + 2] = (sample.toInt() and 0xFF).toByte()
            buffer[pos + 3] = ((sample.toInt() ushr 8) and 0xFF).toByte()
        }

        val original = buffer.copyOf()
        eq.process16BitStereo(buffer, 0, buffer.size)

        var modifiedCount = 0
        for (i in buffer.indices) {
            if (buffer[i] != original[i]) modifiedCount++
        }
        assertTrue("Active EQ should modify buffer samples", modifiedCount > 0)
    }

    @Test
    fun testProcess24BitStereoModifiesBufferWhenActive() {
        val eq = Equalizer12Band(48000)
        eq.isEnabled = true
        eq.applyPreset("Treble Boost")

        val buffer = ByteArray(120) // 20 frames * 6 bytes
        for (i in 0 until 20) {
            val sample = (kotlin.math.sin(i * 0.5) * 500000.0).toInt()
            val pos = i * 6
            // Left channel (3 bytes)
            buffer[pos] = (sample and 0xFF).toByte()
            buffer[pos + 1] = ((sample ushr 8) and 0xFF).toByte()
            buffer[pos + 2] = ((sample ushr 16) and 0xFF).toByte()
            // Right channel (3 bytes)
            buffer[pos + 3] = (sample and 0xFF).toByte()
            buffer[pos + 4] = ((sample ushr 8) and 0xFF).toByte()
            buffer[pos + 5] = ((sample ushr 16) and 0xFF).toByte()
        }

        val original = buffer.copyOf()
        eq.process24BitStereo(buffer, 0, buffer.size)

        var modifiedCount = 0
        for (i in buffer.indices) {
            if (buffer[i] != original[i]) modifiedCount++
        }
        assertTrue("Active 24-bit EQ should modify buffer samples", modifiedCount > 0)
    }

    @Test
    fun testSampleRateChangeRecalculatesSafely() {
        val eq = Equalizer12Band(44100)
        eq.applyPreset("Rock")
        eq.sampleRate = 48000
        assertEquals(48000, eq.sampleRate)

        eq.sampleRate = 96000
        assertEquals(96000, eq.sampleRate)

        eq.sampleRate = 192000
        assertEquals(192000, eq.sampleRate)
    }
}
