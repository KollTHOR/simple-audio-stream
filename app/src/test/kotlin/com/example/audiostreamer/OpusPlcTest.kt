package com.example.audiostreamer

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class OpusPlcTest {

    @Test
    fun testOpusDecoderNormalDecode() {
        val sampleRate = 48000
        val channels = 2
        val frameSize = 240 // 5ms @ 48kHz

        val encoder = OpusEncoder(sampleRate, channels, OpusApplication.OPUS_APPLICATION_RESTRICTED_LOWDELAY)
        encoder.bitrate = 320000

        val decoder = AudioSinkService.OpusDecoder(sampleRate, channels)

        // Generate 5ms of 440Hz sine wave PCM
        val pcmIn = ShortArray(frameSize * channels)
        for (i in 0 until frameSize) {
            val s = (sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 16000.0).toInt().toShort()
            pcmIn[i * 2] = s
            pcmIn[i * 2 + 1] = s
        }

        val packetBuf = ByteArray(1275)
        val packetLen = encoder.encode(pcmIn, 0, frameSize, packetBuf, 0, packetBuf.size)
        assertTrue("Encoder must produce compressed packet", packetLen > 0)

        val pcmList = decoder.decode(packetBuf, 0, packetLen)
        assertEquals("Decoder must return 1 PCM chunk", 1, pcmList.size)
        assertEquals("Decoded PCM must be 960 bytes (240 stereo 16-bit samples)", 960, pcmList[0].size)
        assertEquals(240, decoder.lastFrameSizeSamples)
    }

    @Test
    fun testOpusDecoderPlcProducesGenuineAudioContinuationAndPreservesState() {
        val sampleRate = 48000
        val channels = 2
        val frameSize = 240 // 5ms @ 48kHz

        val encoder = OpusEncoder(sampleRate, channels, OpusApplication.OPUS_APPLICATION_RESTRICTED_LOWDELAY)
        encoder.bitrate = 320000
        val decoder = AudioSinkService.OpusDecoder(sampleRate, channels)

        val pcmIn = ShortArray(frameSize * channels)
        val packetBuf = ByteArray(1275)

        // Feed 10 consecutive 5ms frames of a 440Hz tone to establish decoder filter/pitch state
        for (f in 0 until 10) {
            for (i in 0 until frameSize) {
                val s = (sin(2.0 * Math.PI * 440.0 * (f * frameSize + i) / sampleRate) * 16000.0).toInt().toShort()
                pcmIn[i * 2] = s
                pcmIn[i * 2 + 1] = s
            }
            val len = encoder.encode(pcmIn, 0, frameSize, packetBuf, 0, packetBuf.size)
            decoder.decode(packetBuf, 0, len)
        }

        // Simulate a missing/late packet: invoke decoder PLC
        val plcPcm = decoder.decodePlc(frameSize)
        assertNotNull("Decoder PLC must return synthesized PCM continuation", plcPcm)
        assertEquals("PLC output must match nominal frame size (960 bytes)", 960, plcPcm!!.size)

        // Verify PLC synthesized audio has non-zero energy (genuine PLC extrapolation, NOT flat zeros)
        var totalAbs = 0L
        for (i in 0 until plcPcm.size step 2) {
            val sample = ((plcPcm[i].toInt() and 0xFF) or (plcPcm[i + 1].toInt() shl 8)).toShort()
            totalAbs += kotlin.math.abs(sample.toInt())
        }
        val avgAmplitude = totalAbs / (plcPcm.size / 2)
        assertTrue("PLC continuation must extrapolate audio energy (avg amplitude $avgAmplitude > 1000)", avgAmplitude > 1000)

        // Verify state continuity: feed next frame (frame 11) after PLC and verify seamless decode
        for (i in 0 until frameSize) {
            val s = (sin(2.0 * Math.PI * 440.0 * (11 * frameSize + i) / sampleRate) * 16000.0).toInt().toShort()
            pcmIn[i * 2] = s
            pcmIn[i * 2 + 1] = s
        }
        val nextLen = encoder.encode(pcmIn, 0, frameSize, packetBuf, 0, packetBuf.size)
        val postPlcPcm = decoder.decode(packetBuf, 0, nextLen)
        assertEquals(1, postPlcPcm.size)
        assertEquals(960, postPlcPcm[0].size)

        decoder.release()
    }

    @Test
    fun testOpusDecoderPlcDurationMatching20ms() {
        val sampleRate = 48000
        val channels = 2
        val frameSize = 960 // 20ms @ 48kHz

        val encoder = OpusEncoder(sampleRate, channels, OpusApplication.OPUS_APPLICATION_RESTRICTED_LOWDELAY)
        encoder.bitrate = 320000
        val decoder = AudioSinkService.OpusDecoder(sampleRate, channels)

        val pcmIn = ShortArray(frameSize * channels)
        for (i in 0 until frameSize) {
            val s = (sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 16000.0).toInt().toShort()
            pcmIn[i * 2] = s
            pcmIn[i * 2 + 1] = s
        }

        val packetBuf = ByteArray(1275)
        val packetLen = encoder.encode(pcmIn, 0, frameSize, packetBuf, 0, packetBuf.size)
        decoder.decode(packetBuf, 0, packetLen)

        // PLC for 20ms frame
        val plcPcm = decoder.decodePlc(960)
        assertNotNull(plcPcm)
        assertEquals("PLC output for 20ms frame must be 3840 bytes (960 stereo samples)", 3840, plcPcm!!.size)
    }

    @Test
    fun testJitterBufferOpusLossSignaling() {
        val jb = JitterBuffer(initialProfile = AudioConfig.PROFILE_LOW_LATENCY)
        jb.setIsOpus(true)
        jb.setProfile(AudioConfig.PROFILE_LOW_LATENCY, isCompressed = true, isOpus = true)

        val dummyData = ByteArray(120) { 1 }
        // Pre-roll requires 2 packets
        jb.write(sequence = 0, timestamp = 0L, data = dummyData, offset = 0, length = dummyData.size)
        jb.write(sequence = 1, timestamp = 240L, data = dummyData, offset = 0, length = dummyData.size)

        val outBuf = ByteArray(AudioConfig.MAX_PACKET_SIZE)

        // First read: sequence 0 is available
        val r0 = jb.readPacket(outBuf)
        assertEquals(JitterBuffer.ReadStatus.PACKET, r0.status)
        assertEquals(120, r0.bytesRead)

        // Second read: sequence 1 is available
        val r1 = jb.readPacket(outBuf)
        assertEquals(JitterBuffer.ReadStatus.PACKET, r1.status)
        assertEquals(120, r1.bytesRead)

        // Third read: sequence 2 is MISSING (underrun / late packet)
        // With Opus PLC enabled, single missing packet must signal PACKET_LOST (not BUFFERING!)
        val r2 = jb.readPacket(outBuf)
        assertEquals("Single missing Opus packet must signal PACKET_LOST for PLC", JitterBuffer.ReadStatus.PACKET_LOST, r2.status)
        assertEquals("Concealed packets counter must increment", 1L, jb.getConcealedPackets())

        // Consecutive missing packets up to maxUnderrunFrames (8) should continue signaling PACKET_LOST
        for (i in 3 until 9) {
            val rLost = jb.readPacket(outBuf)
            assertEquals("Burst missing Opus packet #$i must signal PACKET_LOST", JitterBuffer.ReadStatus.PACKET_LOST, rLost.status)
        }

        // On prolonged starvation (consecutive underruns >= maxUnderrunFrames), rebuffering takes over
        val rStarvation = jb.readPacket(outBuf)
        assertEquals("Prolonged starvation beyond maxUnderrunFrames must enter BUFFERING", JitterBuffer.ReadStatus.BUFFERING, rStarvation.status)
    }

    @Test
    fun testJitterBufferPcmPreservesSmoothConcealment() {
        val jb = JitterBuffer(initialProfile = AudioConfig.PROFILE_AUTO)
        jb.setIsOpus(false)

        val pcmData = ByteArray(960) { 10 }
        val preRoll = AudioConfig.getPreRollPackets(AudioConfig.PROFILE_AUTO)
        for (i in 0 until preRoll) {
            jb.write(sequence = i, timestamp = i * 240L, data = pcmData, offset = 0, length = pcmData.size)
        }

        val outBuf = ByteArray(AudioConfig.MAX_PACKET_SIZE)
        for (i in 0 until preRoll) {
            val r = jb.readPacket(outBuf)
            assertEquals(JitterBuffer.ReadStatus.PACKET, r.status)
        }

        // Sequence `preRoll` is missing in PCM: returns PACKET_LOST with synthesized PCM fade
        val rLost = jb.readPacket(outBuf)
        assertEquals(JitterBuffer.ReadStatus.PACKET_LOST, rLost.status)
        assertEquals(960, rLost.bytesRead)
    }
}
