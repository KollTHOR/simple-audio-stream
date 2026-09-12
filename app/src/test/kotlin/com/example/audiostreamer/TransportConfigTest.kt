package com.example.audiostreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TransportConfigTest {

    @Test
    fun testValidAudioFormatAndPcmCalculations() {
        val format24 = AudioFormatConfig(
            sampleRate = AudioSampleRate.RATE_48000,
            bitDepth = AudioBitDepth.BIT_24,
            channelLayout = AudioChannelLayout.STEREO
        )
        assertEquals(48000, format24.sampleRateHz)
        assertEquals(3, format24.bytesPerSample)
        assertEquals(2, format24.channels)
        assertEquals(6, format24.bytesPerFrame)
        // 5ms at 48kHz = 240 frames * 6 bytes = 1440 bytes
        assertEquals(240, format24.frameCountForDurationMs(5.0f))
        assertEquals(1440, format24.pcmBytesForDurationMs(5.0f))

        val format16 = AudioFormatConfig(
            sampleRate = AudioSampleRate.RATE_44100,
            bitDepth = AudioBitDepth.BIT_16,
            channelLayout = AudioChannelLayout.STEREO
        )
        assertEquals(44100, format16.sampleRateHz)
        assertEquals(2, format16.bytesPerSample)
        assertEquals(4, format16.bytesPerFrame)
        // 5ms at 44.1kHz = 220 frames * 4 bytes = 880 bytes
        assertEquals(220, format16.frameCountForDurationMs(5.0f))
        assertEquals(880, format16.pcmBytesForDurationMs(5.0f))
    }

    @Test
    fun testValidCodecAndFormatCombinations() {
        // 1. 24-bit 48kHz PCM
        val pcmConfig = NegotiatedStreamConfig(
            audioFormat = AudioFormatConfig(AudioSampleRate.RATE_48000, AudioBitDepth.BIT_24),
            codec = AudioCodec.PCM,
            transportProfile = TransportProfile.create(LatencyTarget.BALANCED)
        )
        assertEquals(AudioCodec.PCM, pcmConfig.codec)
        assertEquals(48000, pcmConfig.sampleRateHz)
        assertEquals(24, pcmConfig.bitDepthBits)
        assertFalse(pcmConfig.isCompressed)

        // 2. 24-bit 48kHz Lossless ASLC
        val losslessConfig = NegotiatedStreamConfig(
            audioFormat = AudioFormatConfig(AudioSampleRate.RATE_48000, AudioBitDepth.BIT_24),
            codec = AudioCodec.LOSSLESS,
            transportProfile = TransportProfile.create(LatencyTarget.RELIABLE)
        )
        assertEquals(AudioCodec.LOSSLESS, losslessConfig.codec)
        assertFalse(losslessConfig.isCompressed)

        // 3. 16-bit 48kHz Opus
        val opusConfig = NegotiatedStreamConfig(
            audioFormat = AudioFormatConfig(AudioSampleRate.RATE_48000, AudioBitDepth.BIT_16),
            codec = AudioCodec.OPUS,
            transportProfile = TransportProfile.create(LatencyTarget.LOW_LATENCY, isCompressedCodec = true)
        )
        assertEquals(AudioCodec.OPUS, opusConfig.codec)
        assertTrue(opusConfig.isCompressed)
        assertEquals(960, opusConfig.getFramesPerPacket())

        // 4. 16-bit 48kHz AAC
        val aacConfig48 = NegotiatedStreamConfig(
            audioFormat = AudioFormatConfig(AudioSampleRate.RATE_48000, AudioBitDepth.BIT_16),
            codec = AudioCodec.AAC,
            transportProfile = TransportProfile.create(LatencyTarget.LOW_LATENCY, isCompressedCodec = true)
        )
        assertEquals(AudioCodec.AAC, aacConfig48.codec)
        assertTrue(aacConfig48.isCompressed)
        assertEquals(1024, aacConfig48.getFramesPerPacket())

        // 5. 16-bit 44.1kHz AAC
        val aacConfig44 = NegotiatedStreamConfig(
            audioFormat = AudioFormatConfig(AudioSampleRate.RATE_44100, AudioBitDepth.BIT_16),
            codec = AudioCodec.AAC,
            transportProfile = TransportProfile.create(LatencyTarget.LOW_LATENCY, isCompressedCodec = true)
        )
        assertEquals(44100, aacConfig44.sampleRateHz)
    }

    @Test
    fun testInvalidCodecFormatCombinations() {
        // Opus cannot operate on 96kHz
        try {
            NegotiatedStreamConfig(
                audioFormat = AudioFormatConfig(AudioSampleRate.RATE_96000, AudioBitDepth.BIT_16),
                codec = AudioCodec.OPUS,
                transportProfile = TransportProfile.create(LatencyTarget.LOW_LATENCY, isCompressedCodec = true)
            )
            fail("Opus with 96kHz must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Opus requires 48,000 Hz"))
        }

        // Opus cannot operate on 44.1kHz
        try {
            NegotiatedStreamConfig(
                audioFormat = AudioFormatConfig(AudioSampleRate.RATE_44100, AudioBitDepth.BIT_16),
                codec = AudioCodec.OPUS,
                transportProfile = TransportProfile.create(LatencyTarget.LOW_LATENCY, isCompressedCodec = true)
            )
            fail("Opus with 44.1kHz must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Opus requires 48,000 Hz"))
        }

        // Opus cannot operate on 24-bit PCM
        try {
            NegotiatedStreamConfig(
                audioFormat = AudioFormatConfig(AudioSampleRate.RATE_48000, AudioBitDepth.BIT_24),
                codec = AudioCodec.OPUS,
                transportProfile = TransportProfile.create(LatencyTarget.LOW_LATENCY, isCompressedCodec = true)
            )
            fail("Opus with 24-bit must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Opus operates on 16-bit PCM"))
        }

        // AAC cannot operate on 24-bit PCM
        try {
            NegotiatedStreamConfig(
                audioFormat = AudioFormatConfig(AudioSampleRate.RATE_48000, AudioBitDepth.BIT_24),
                codec = AudioCodec.AAC,
                transportProfile = TransportProfile.create(LatencyTarget.LOW_LATENCY, isCompressedCodec = true)
            )
            fail("AAC with 24-bit must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("AAC operates on 16-bit PCM"))
        }

        // AAC cannot operate on 96kHz or 192kHz
        try {
            NegotiatedStreamConfig(
                audioFormat = AudioFormatConfig(AudioSampleRate.RATE_96000, AudioBitDepth.BIT_16),
                codec = AudioCodec.AAC,
                transportProfile = TransportProfile.create(LatencyTarget.LOW_LATENCY, isCompressedCodec = true)
            )
            fail("AAC with 96kHz must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("AAC supports 44.1k and 48k Hz"))
        }
    }

    @Test
    fun testInvalidFormatParameters() {
        try {
            AudioBitDepth.fromBits(32)
            fail("32-bit should not be accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Unsupported bit depth"))
        }

        try {
            AudioChannelLayout.fromChannelCount(1)
            fail("Mono channel should not be accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Unsupported channel count"))
        }

        try {
            AudioSampleRate.fromHz(32000)
            fail("32kHz sample rate should not be accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Unsupported sample rate"))
        }

        try {
            FecPolicy(enabled = true, blockSize = 1)
            fail("FEC block size 1 must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("FEC block size must be between 2 and 16"))
        }

        try {
            FecPolicy(enabled = true, blockSize = 20)
            fail("FEC block size 20 must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("FEC block size must be between 2 and 16"))
        }
    }

    @Test
    fun testPacketHeaderDerivationAndRoundTrip() {
        val originalConfig = NegotiatedStreamConfig(
            audioFormat = AudioFormatConfig(
                sampleRate = AudioSampleRate.RATE_48000,
                bitDepth = AudioBitDepth.BIT_24,
                channelLayout = AudioChannelLayout.STEREO
            ),
            codec = AudioCodec.PCM,
            transportProfile = TransportProfile.create(
                target = LatencyTarget.BALANCED,
                fecEnabled = true,
                isCompressedCodec = false,
                sampleRateHz = 48000
            )
        )

        val header = originalConfig.createHeader(
            packetType = HatPacket.TYPE_AUDIO,
            sequenceNumber = 500,
            payloadLength = 1440,
            timestamp = 120000L,
            volumeOrCaps = 80
        )

        assertEquals(HatPacket.TYPE_AUDIO, header.packetType)
        assertEquals(500, header.sequenceNumber)
        assertEquals(1440, header.payloadLength)
        assertEquals(120000L, header.timestamp)
        assertEquals(HatPacket.CODEC_RAW_PCM, header.codec)
        assertEquals(HatPacket.PROFILE_AUTO, header.profile)
        assertEquals(HatPacket.RATE_48000, header.sampleRateCode)
        assertEquals(HatPacket.BIT_DEPTH_24, header.bitDepth)
        assertEquals(HatPacket.CHANNELS_STEREO, header.channels)
        assertEquals(80.toByte(), header.volumeOrCaps)

        // Roundtrip reconstruction from header
        val parsedConfig = NegotiatedStreamConfig.fromHeader(header, fecEnabled = true)
        assertNotNull(parsedConfig)
        assertEquals(originalConfig.codec, parsedConfig?.codec)
        assertEquals(originalConfig.audioFormat.sampleRate, parsedConfig?.audioFormat?.sampleRate)
        assertEquals(originalConfig.audioFormat.bitDepth, parsedConfig?.audioFormat?.bitDepth)
        assertEquals(originalConfig.transportProfile.latencyTarget, parsedConfig?.transportProfile?.latencyTarget)
    }

    @Test
    fun testTransmitterReceiverAgreementValidation() {
        val config = NegotiatedStreamConfig(
            audioFormat = AudioFormatConfig(
                sampleRate = AudioSampleRate.RATE_48000,
                bitDepth = AudioBitDepth.BIT_24,
                channelLayout = AudioChannelLayout.STEREO
            ),
            codec = AudioCodec.PCM,
            transportProfile = TransportProfile.create(LatencyTarget.BALANCED)
        )

        // Matching packet header
        val matchingHeader = config.createHeader(
            packetType = HatPacket.TYPE_AUDIO,
            sequenceNumber = 1,
            payloadLength = 1440,
            timestamp = 0L
        )
        val agreeResult = config.validatePacketAgreement(matchingHeader)
        assertTrue(agreeResult.isAgreed)

        // Disagreement 1: Codec mismatch (received Opus instead of PCM)
        val codecMismatchHeader = matchingHeader.copy(codec = HatPacket.CODEC_OPUS)
        val codecResult = config.validatePacketAgreement(codecMismatchHeader)
        assertFalse(codecResult.isAgreed)
        assertTrue((codecResult as AgreementResult.Disagreement).reason.contains("Codec disagreement"))

        // Disagreement 2: Sample rate mismatch (received 44.1kHz instead of 48kHz)
        val rateMismatchHeader = matchingHeader.copy(sampleRateCode = HatPacket.RATE_44100)
        val rateResult = config.validatePacketAgreement(rateMismatchHeader)
        assertFalse(rateResult.isAgreed)
        assertTrue((rateResult as AgreementResult.Disagreement).reason.contains("Sample rate disagreement"))

        // Disagreement 3: Bit depth mismatch (received 16-bit instead of 24-bit PCM)
        val bitDepthMismatchHeader = matchingHeader.copy(bitDepth = HatPacket.BIT_DEPTH_16)
        val bitResult = config.validatePacketAgreement(bitDepthMismatchHeader)
        assertFalse(bitResult.isAgreed)
        assertTrue((bitResult as AgreementResult.Disagreement).reason.contains("Bit depth disagreement"))

        // Disagreement 4: Channel mismatch
        val channelMismatchHeader = matchingHeader.copy(channels = 1)
        val chanResult = config.validatePacketAgreement(channelMismatchHeader)
        assertFalse(chanResult.isAgreed)
        assertTrue((chanResult as AgreementResult.Disagreement).reason.contains("Channels disagreement"))
    }

    @Test
    fun testExplicitStreamNegotiator() {
        // Scenario 1: Low Latency requested with Opus available
        val reqLowLatOpus = StreamNegotiator.NegotiationRequest(
            preferredProfile = LatencyTarget.LOW_LATENCY,
            isOpusEncoderAvailable = true
        )
        val stream1 = StreamNegotiator.negotiate(reqLowLatOpus)
        assertEquals(AudioCodec.OPUS, stream1.codec)
        assertEquals(AudioSampleRate.RATE_48000, stream1.audioFormat.sampleRate)
        assertEquals(AudioBitDepth.BIT_16, stream1.audioFormat.bitDepth)
        assertEquals(LatencyTarget.LOW_LATENCY, stream1.transportProfile.latencyTarget)

        // Scenario 2: Low Latency requested when Opus is unavailable (falls back to AAC)
        val reqLowLatAac = StreamNegotiator.NegotiationRequest(
            preferredProfile = LatencyTarget.LOW_LATENCY,
            isOpusEncoderAvailable = false
        )
        val stream2 = StreamNegotiator.negotiate(reqLowLatAac)
        assertEquals(AudioCodec.AAC, stream2.codec)
        assertEquals(AudioSampleRate.RATE_48000, stream2.audioFormat.sampleRate)
        assertEquals(AudioBitDepth.BIT_16, stream2.audioFormat.bitDepth)

        // Scenario 3: Auto Balanced requested with 24-bit
        val reqBalanced = StreamNegotiator.NegotiationRequest(
            preferredProfile = LatencyTarget.BALANCED,
            preferred24Bit = true,
            preferredSampleRateHz = 48000
        )
        val stream3 = StreamNegotiator.negotiate(reqBalanced)
        assertEquals(AudioCodec.PCM, stream3.codec)
        assertEquals(AudioBitDepth.BIT_24, stream3.audioFormat.bitDepth)
        assertEquals(48000, stream3.sampleRateHz)

        // Scenario 4: Reliable Music requested with 16-bit
        val reqMusic16 = StreamNegotiator.NegotiationRequest(
            preferredProfile = LatencyTarget.RELIABLE,
            preferred24Bit = false,
            preferredSampleRateHz = 48000
        )
        val stream4 = StreamNegotiator.negotiate(reqMusic16)
        assertEquals(AudioBitDepth.BIT_16, stream4.audioFormat.bitDepth)
        assertEquals(LatencyTarget.RELIABLE, stream4.transportProfile.latencyTarget)
    }

    @Test
    fun testStreamIdGenerationAndStability() {
        val id1 = StreamId.generate("pixel7")
        assertTrue(id1.value.startsWith("pixel7-"))
        assertEquals(id1.value, id1.toString())

        val id2 = StreamId("custom-stable-stream-id")
        assertEquals("custom-stable-stream-id", id2.value)
        assertEquals("custom-stable-stream-id", id2.toString())
    }

    @Test
    fun testStreamEndpoint() {
        val ep1 = StreamEndpoint("192.168.1.50")
        assertEquals("192.168.1.50", ep1.host)
        assertEquals(AudioConfig.DEFAULT_PORT, ep1.port)
        assertEquals("192.168.1.50:${AudioConfig.DEFAULT_PORT}", ep1.toString())

        val ep2 = StreamEndpoint("10.0.0.1", 12349)
        assertEquals("10.0.0.1:12349", ep2.toString())
    }

    @Test
    fun testPublishedStreamJsonRoundtrip() {
        val original = PublishedStream(
            id = StreamId("pixel7-abc12345"),
            name = "Studio Living Room",
            endpoint = StreamEndpoint("192.168.1.105", 12345),
            audioFormat = AudioFormatConfig(
                sampleRate = AudioSampleRate.RATE_96000,
                bitDepth = AudioBitDepth.BIT_24,
                channelLayout = AudioChannelLayout.STEREO
            ),
            codec = AudioCodec.LOSSLESS,
            transportProfile = TransportProfile.create(
                target = LatencyTarget.RELIABLE,
                fecEnabled = true,
                isCompressedCodec = false,
                sampleRateHz = 96000
            ),
            isLive = true,
            publishedAtMs = 1726000000000L
        )

        val jsonString = original.toJson()
        assertNotNull(jsonString)
        assertTrue(jsonString.contains("pixel7-abc12345"))
        assertTrue(jsonString.contains("Studio Living Room"))
        assertTrue(jsonString.contains("192.168.1.105"))
        assertTrue(jsonString.contains("LOSSLESS"))

        val parsed = PublishedStream.fromJson(jsonString)
        assertNotNull(parsed)
        assertEquals(original.id, parsed?.id)
        assertEquals(original.name, parsed?.name)
        assertEquals(original.endpoint.host, parsed?.endpoint?.host)
        assertEquals(original.endpoint.port, parsed?.endpoint?.port)
        assertEquals(original.audioFormat.sampleRate, parsed?.audioFormat?.sampleRate)
        assertEquals(original.audioFormat.bitDepth, parsed?.audioFormat?.bitDepth)
        assertEquals(original.audioFormat.channelLayout, parsed?.audioFormat?.channelLayout)
        assertEquals(original.codec, parsed?.codec)
        assertEquals(original.transportProfile.latencyTarget, parsed?.transportProfile?.latencyTarget)
        assertEquals(original.transportProfile.fec.enabled, parsed?.transportProfile?.fec?.enabled)
        assertEquals(original.isLive, parsed?.isLive)

        // Verify conversion to NegotiatedStreamConfig
        val negotiated = parsed?.toNegotiatedStreamConfig()
        assertNotNull(negotiated)
        assertEquals(96000, negotiated?.sampleRateHz)
        assertEquals(24, negotiated?.bitDepthBits)
        assertEquals(AudioCodec.LOSSLESS, negotiated?.codec)
    }

    @Test
    fun testPublishedStreamInvalidJson() {
        assertNull(PublishedStream.fromJson("invalid json string"))
        assertNull(PublishedStream.fromJson("{}")) // Missing required fields
    }
}
