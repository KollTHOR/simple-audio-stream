package com.example.audiostreamer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.sin

class LosslessAudioCodecTest {

    @Test
    fun test16BitHarmonicAudioLosslessRoundtrip() {
        val codec = LosslessAudioCodec(1024)
        val sampleRate = 48000
        val frameCount = 240 // 5ms @ 48kHz
        val pcm = ByteArray(frameCount * 4)

        for (i in 0 until frameCount) {
            val t = i.toDouble() / sampleRate
            val sL = (sin(2.0 * Math.PI * 440.0 * t) * 16000.0).toInt().toShort()
            val sR = (sin(2.0 * Math.PI * 880.0 * t) * 14000.0).toInt().toShort()
            val p = i * 4
            pcm[p] = (sL.toInt() and 0xFF).toByte()
            pcm[p + 1] = ((sL.toInt() shr 8) and 0xFF).toByte()
            pcm[p + 2] = (sR.toInt() and 0xFF).toByte()
            pcm[p + 3] = ((sR.toInt() shr 8) and 0xFF).toByte()
        }

        val comp = ByteArray(pcm.size * 2)
        val compLen = codec.encode(pcm, 0, pcm.size, false, comp, 0)
        assertTrue("Compressed length should be less than raw PCM", compLen < pcm.size)

        val decomp = ByteArray(pcm.size)
        val decompLen = codec.decode(comp, 0, compLen, false, decomp, 0)
        assertEquals("Decompressed length must match original", pcm.size, decompLen)
        assertArrayEquals("Decoded audio must be bit-for-bit identical to original PCM", pcm, decomp)
    }

    @Test
    fun test24Bit96kHzMusicLosslessRoundtripAndCompressionRatio() {
        val codec = LosslessAudioCodec(1024)
        val sampleRate = 96000
        val frameCount = 480 // 5ms @ 96kHz
        val rawBytes = frameCount * 6
        val pcm = ByteArray(rawBytes)

        for (i in 0 until frameCount) {
            val t = i.toDouble() / sampleRate
            // Rich multi-harmonic musical signal
            val center = sin(2.0 * Math.PI * 220.0 * t) * 0.5 + sin(2.0 * Math.PI * 440.0 * t) * 0.25
            val panL = sin(2.0 * Math.PI * 330.0 * t) * 0.1
            val panR = sin(2.0 * Math.PI * 330.0 * t) * 0.08
            val sL = ((center + panL) * 6000000.0).toInt()
            val sR = ((center + panR) * 6000000.0).toInt()

            val p = i * 6
            pcm[p] = (sL and 0xFF).toByte()
            pcm[p + 1] = ((sL shr 8) and 0xFF).toByte()
            pcm[p + 2] = ((sL shr 16) and 0xFF).toByte()
            pcm[p + 3] = (sR and 0xFF).toByte()
            pcm[p + 4] = ((sR shr 8) and 0xFF).toByte()
            pcm[p + 5] = ((sR shr 16) and 0xFF).toByte()
        }

        val comp = ByteArray(rawBytes * 2)
        val compLen = codec.encode(pcm, 0, rawBytes, true, comp, 0)

        // Verify compression achieves significant savings (> 35% reduction on musical content)
        val savings = 1.0 - (compLen.toDouble() / rawBytes.toDouble())
        assertTrue("Compression should achieve substantial reduction (actual savings: ${(savings * 100).toInt()}%)", savings > 0.35)

        val decomp = ByteArray(rawBytes)
        val decompLen = codec.decode(comp, 0, compLen, true, decomp, 0)
        assertEquals("Decompressed length must match original", rawBytes, decompLen)
        assertArrayEquals("Decoded 24-bit PCM must be bit-for-bit identical to original", pcm, decomp)
    }

    @Test
    fun test24Bit192kHzLosslessRoundtrip() {
        val codec = LosslessAudioCodec(1024)
        val sampleRate = 192000
        val frameCount = 480 // 2.5ms @ 192kHz
        val rawBytes = frameCount * 6
        val pcm = ByteArray(rawBytes)

        for (i in 0 until frameCount) {
            val t = i.toDouble() / sampleRate
            val sL = (sin(2.0 * Math.PI * 1000.0 * t) * 5000000.0).toInt()
            val sR = (sin(2.0 * Math.PI * 1200.0 * t) * 4500000.0).toInt()

            val p = i * 6
            pcm[p] = (sL and 0xFF).toByte()
            pcm[p + 1] = ((sL shr 8) and 0xFF).toByte()
            pcm[p + 2] = ((sL shr 16) and 0xFF).toByte()
            pcm[p + 3] = (sR and 0xFF).toByte()
            pcm[p + 4] = ((sR shr 8) and 0xFF).toByte()
            pcm[p + 5] = ((sR shr 16) and 0xFF).toByte()
        }

        val comp = ByteArray(rawBytes * 2)
        val compLen = codec.encode(pcm, 0, rawBytes, true, comp, 0)

        val decomp = ByteArray(rawBytes)
        val decompLen = codec.decode(comp, 0, compLen, true, decomp, 0)
        assertEquals(rawBytes, decompLen)
        assertArrayEquals(pcm, decomp)
    }

    @Test
    fun testSilenceCompression() {
        val codec = LosslessAudioCodec(1024)
        val frameCount = 480
        val pcm = ByteArray(frameCount * 6) // all zeros

        val comp = ByteArray(1024)
        val compLen = codec.encode(pcm, 0, pcm.size, true, comp, 0)
        assertEquals("Silence should compress to header only (3 bytes)", 3, compLen)
        assertEquals(LosslessAudioCodec.MODE_SILENCE, comp[0])

        val decomp = ByteArray(pcm.size)
        val decompLen = codec.decode(comp, 0, compLen, true, decomp, 0)
        assertEquals(pcm.size, decompLen)
        assertArrayEquals(pcm, decomp)
    }

    @Test
    fun testWhiteNoiseRawFallback() {
        val codec = LosslessAudioCodec(1024)
        val frameCount = 240
        val pcm = ByteArray(frameCount * 4)
        val random = Random(42)
        random.nextBytes(pcm)

        val comp = ByteArray(pcm.size * 2)
        val compLen = codec.encode(pcm, 0, pcm.size, false, comp, 0)

        val decomp = ByteArray(pcm.size)
        val decompLen = codec.decode(comp, 0, compLen, false, decomp, 0)
        assertEquals(pcm.size, decompLen)
        assertArrayEquals(pcm, decomp)
    }

    @Test
    fun testExtremeSampleBoundaryValues() {
        val codec = LosslessAudioCodec(1024)
        val frameCount = 4
        val pcm = ByteArray(frameCount * 6)

        // Extreme 24-bit values: min (-8388608), max (+8388607), 0, -1
        val testVals = intArrayOf(-8388608, 8388607, 0, -1)
        for (i in 0 until frameCount) {
            val sL = testVals[i]
            val sR = testVals[(i + 1) % 4]
            val p = i * 6
            pcm[p] = (sL and 0xFF).toByte()
            pcm[p + 1] = ((sL shr 8) and 0xFF).toByte()
            pcm[p + 2] = ((sL shr 16) and 0xFF).toByte()
            pcm[p + 3] = (sR and 0xFF).toByte()
            pcm[p + 4] = ((sR shr 8) and 0xFF).toByte()
            pcm[p + 5] = ((sR shr 16) and 0xFF).toByte()
        }

        val comp = ByteArray(pcm.size * 2)
        val compLen = codec.encode(pcm, 0, pcm.size, true, comp, 0)

        val decomp = ByteArray(pcm.size)
        val decompLen = codec.decode(comp, 0, compLen, true, decomp, 0)
        assertEquals(pcm.size, decompLen)
        assertArrayEquals(pcm, decomp)
    }

    @Test
    fun testEncodingAndDecodingSpeedSubMillisecond() {
        val codec = LosslessAudioCodec(1024)
        val frameCount = 480 // 5ms @ 96kHz
        val pcm = ByteArray(frameCount * 6)
        for (i in 0 until frameCount) {
            val t = i.toDouble() / 96000.0
            val sL = (sin(2.0 * Math.PI * 440.0 * t) * 6000000.0).toInt()
            val sR = (sin(2.0 * Math.PI * 880.0 * t) * 6000000.0).toInt()
            val p = i * 6
            pcm[p] = (sL and 0xFF).toByte()
            pcm[p + 1] = ((sL shr 8) and 0xFF).toByte()
            pcm[p + 2] = ((sL shr 16) and 0xFF).toByte()
            pcm[p + 3] = (sR and 0xFF).toByte()
            pcm[p + 4] = ((sR shr 8) and 0xFF).toByte()
            pcm[p + 5] = ((sR shr 16) and 0xFF).toByte()
        }

        val comp = ByteArray(pcm.size * 2)
        val decomp = ByteArray(pcm.size)

        // Warmup JIT
        for (i in 0 until 1000) {
            val cl = codec.encode(pcm, 0, pcm.size, true, comp, 0)
            codec.decode(comp, 0, cl, true, decomp, 0)
        }

        val iterations = 5000
        val t0 = System.nanoTime()
        for (i in 0 until iterations) {
            codec.encode(pcm, 0, pcm.size, true, comp, 0)
        }
        val t1 = System.nanoTime()
        val cl = codec.encode(pcm, 0, pcm.size, true, comp, 0)
        for (i in 0 until iterations) {
            codec.decode(comp, 0, cl, true, decomp, 0)
        }
        val t2 = System.nanoTime()

        val avgEncodeUs = (t1 - t0) / (iterations * 1000.0)
        val avgDecodeUs = (t2 - t1) / (iterations * 1000.0)

        println("ASLC Benchmark: Encode 5ms frame (480 samples @ 96k 24b): ${avgEncodeUs} us | Decode: ${avgDecodeUs} us")
        assertTrue("Encode time must be sub-millisecond (< 500 us)", avgEncodeUs < 500.0)
        assertTrue("Decode time must be sub-millisecond (< 500 us)", avgDecodeUs < 500.0)
    }
}
