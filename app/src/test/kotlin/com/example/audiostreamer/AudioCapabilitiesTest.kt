package com.example.audiostreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCapabilitiesTest {

    @Test
    fun testAndroidNativeRatesAreRestrictedTo48kAnd44k() {
        // Verify Android native rates only include 48 kHz (primary) and 44.1 kHz (CD fallback)
        assertEquals(2, AudioCapabilities.ANDROID_NATIVE_RATES.size)
        assertTrue(AudioCapabilities.ANDROID_NATIVE_RATES.contains(48000))
        assertTrue(AudioCapabilities.ANDROID_NATIVE_RATES.contains(44100))

        // Multi-platform rates preserve architecture for non-Android platforms
        assertTrue(AudioCapabilities.ALL_PLATFORM_RATES.contains(96000))
        assertTrue(AudioCapabilities.ALL_PLATFORM_RATES.contains(192000))
    }

    @Test
    fun testHighestMutuallySupportedRatePrioritizes48kHz() {
        val txMask = AudioCapabilities.CAP_FLAG_48000 or AudioCapabilities.CAP_FLAG_44100
        val rxMask = AudioCapabilities.CAP_FLAG_48000 or AudioCapabilities.CAP_FLAG_44100

        val rate = AudioCapabilities.getHighestMutuallySupportedRate(txMask, rxMask, AudioConfig.SAMPLE_RATE_48000)
        assertEquals(48000, rate)
    }

    @Test
    fun testHighestMutuallySupportedRateFallsBackTo44kIf48kNotAvailable() {
        val txMask = AudioCapabilities.CAP_FLAG_44100
        val rxMask = AudioCapabilities.CAP_FLAG_44100 or AudioCapabilities.CAP_FLAG_48000

        val rate = AudioCapabilities.getHighestMutuallySupportedRate(txMask, rxMask, AudioConfig.SAMPLE_RATE_48000)
        assertEquals(44100, rate)
    }

    @Test
    fun testPacketPayloadSizingCalculations() {
        // 48 kHz 24-bit stereo (5ms = 240 frames * 2 ch * 3 bytes = 1440 bytes)
        assertEquals(1440, AudioConfig.getPacketPayloadSize(48000, is24Bit = true))

        // 48 kHz 16-bit stereo (5ms = 240 frames * 2 ch * 2 bytes = 960 bytes)
        assertEquals(960, AudioConfig.getPacketPayloadSize(48000, is24Bit = false))

        // 44.1 kHz 24-bit stereo (5ms = 220 frames * 2 ch * 3 bytes = 1320 bytes)
        assertEquals(1320, AudioConfig.getPacketPayloadSize(44100, is24Bit = true))

        // 44.1 kHz 16-bit stereo (5ms = 220 frames * 2 ch * 2 bytes = 880 bytes)
        assertEquals(880, AudioConfig.getPacketPayloadSize(44100, is24Bit = false))
    }
}
