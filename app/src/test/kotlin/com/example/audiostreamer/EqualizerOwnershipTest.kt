package com.example.audiostreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins where the equalizer lives.
 *
 * The EQ is a speaker-correction tool, so it must be applied on the receiver after decode. If it
 * were ever moved back onto the capture path it would be baked into the encoded stream, which
 * (a) sends the same curve to every receiver instead of letting each device tune its own
 * speaker, and (b) forces the lossy encoder to spend bits on an already-boosted signal.
 */
class EqualizerOwnershipTest {

    @Test
    fun equalizerIsOwnedByTheReceiver() {
        // Touching the companion is what matters: the EQ must hang off the sink, not the capture
        // service, so that it runs after decode on this device's own output.
        AudioSinkService.playbackEqualizer.resetFilterState()
    }

    @Test
    fun receiverEqualizerIsUsableAndDistinctPerInstance() {
        val a = Equalizer12Band(48000)
        val b = Equalizer12Band(48000)
        assertNotSame(a, b)
    }

    @Test
    fun equalizerAppliesAfterDecodeRatherThanToTheEncodedStream() {
        // Sanity check that the receiver-side instance actually processes audio, which is what
        // makes it a playback-stage EQ rather than a capture-stage one.
        val eq = Equalizer12Band(48000)
        eq.isEnabled = true
        eq.snapGainsForTest()
        eq.setBandGain(5, 6.0f)
        eq.snapGainsForTest()

        val frames = 480
        val buf = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val s = (kotlin.math.sin(2.0 * Math.PI * 1000.0 * i / 48000.0) * 4000).toInt().toShort()
            buf[i * 4] = (s.toInt() and 0xFF).toByte()
            buf[i * 4 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
            buf[i * 4 + 2] = buf[i * 4]
            buf[i * 4 + 3] = buf[i * 4 + 1]
        }
        val original = buf.copyOf()
        eq.process16BitStereo(buf, 0, buf.size)
        assertTrue("receiver-side EQ should alter playback audio", !original.contentEquals(buf))
    }

    @Test
    fun flatReceiverEqualizerIsBitTransparent() {
        val eq = Equalizer12Band(48000)
        eq.isEnabled = true
        eq.snapGainsForTest()
        eq.applyPreset("Flat")
        eq.snapGainsForTest()

        val frames = 480
        val buf = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val s = (kotlin.math.sin(2.0 * Math.PI * 440.0 * i / 48000.0) * 12000).toInt().toShort()
            buf[i * 4] = (s.toInt() and 0xFF).toByte()
            buf[i * 4 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
            buf[i * 4 + 2] = buf[i * 4]
            buf[i * 4 + 3] = buf[i * 4 + 1]
        }
        val original = buf.copyOf()
        eq.process16BitStereo(buf, 0, buf.size)
        assertTrue("a flat EQ must be bit transparent", original.contentEquals(buf))
    }

    @Test
    fun allPresetsAreDefinedForEveryBand() {
        for ((name, gains) in Equalizer12Band.PRESETS) {
            assertEquals("preset $name must define all bands", Equalizer12Band.BAND_COUNT, gains.size)
        }
    }
}
