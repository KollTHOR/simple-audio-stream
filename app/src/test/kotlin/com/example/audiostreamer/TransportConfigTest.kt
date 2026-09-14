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
    fun testRepeatedIdenticalAnnouncementsAreIdempotent() {
        val configGen1 = NegotiatedStreamConfig(
            audioFormat = AudioFormatConfig(AudioSampleRate.RATE_48000, AudioBitDepth.BIT_24),
            codec = AudioCodec.PCM,
            transportProfile = TransportProfile.create(LatencyTarget.BALANCED),
            generation = 1L
        )

        val authority = StreamConfigurationAuthority(configGen1)
        assertEquals(1L, authority.currentGeneration)
        assertEquals(configGen1, authority.currentConfig)

        // Repeated announcement of the exact same generation
        val repeatResult = authority.applyUpdate(configGen1)
        assertTrue(repeatResult is ConfigTransitionResult.IdempotentIgnored)
        assertEquals(1L, (repeatResult as ConfigTransitionResult.IdempotentIgnored).generation)

        // State remains completely unchanged
        assertEquals(1L, authority.currentGeneration)
        assertEquals(configGen1, authority.currentConfig)
    }

    @Test
    fun testStaleGenerationUpdateIsRejected() {
        val configGen2 = NegotiatedStreamConfig(
            audioFormat = AudioFormatConfig(AudioSampleRate.RATE_48000, AudioBitDepth.BIT_24),
            codec = AudioCodec.PCM,
            transportProfile = TransportProfile.create(LatencyTarget.RELIABLE),
            generation = 2L
        )
        val configGen1 = NegotiatedStreamConfig(
            audioFormat = AudioFormatConfig(AudioSampleRate.RATE_44100, AudioBitDepth.BIT_16),
            codec = AudioCodec.PCM,
            transportProfile = TransportProfile.create(LatencyTarget.BALANCED),
            generation = 1L
        )

        val authority = StreamConfigurationAuthority(configGen2)
        assertEquals(2L, authority.currentGeneration)

        // Incoming update with older generation 1L
        val staleResult = authority.applyUpdate(configGen1)
        assertTrue(staleResult is ConfigTransitionResult.RejectedStale)
        val rejected = staleResult as ConfigTransitionResult.RejectedStale
        assertEquals(1L, rejected.incomingGeneration)
        assertEquals(2L, rejected.currentGeneration)

        // Current config must NOT be overwritten by stale packet
        assertEquals(2L, authority.currentGeneration)
        assertEquals(configGen2, authority.currentConfig)
        assertEquals(AudioSampleRate.RATE_48000, authority.currentConfig?.audioFormat?.sampleRate)
    }

    @Test
    fun testNewerGenerationUpdateIsApplied() {
        val configGen1 = NegotiatedStreamConfig(
            audioFormat = AudioFormatConfig(AudioSampleRate.RATE_48000, AudioBitDepth.BIT_24),
            codec = AudioCodec.PCM,
            transportProfile = TransportProfile.create(LatencyTarget.RELIABLE),
            generation = 1L
        )
        val configGen2 = NegotiatedStreamConfig(
            audioFormat = AudioFormatConfig(AudioSampleRate.RATE_48000, AudioBitDepth.BIT_16),
            codec = AudioCodec.OPUS,
            transportProfile = TransportProfile.create(LatencyTarget.LOW_LATENCY, isCompressedCodec = true),
            generation = 2L
        )

        val authority = StreamConfigurationAuthority(configGen1)
        assertEquals(1L, authority.currentGeneration)

        // Incoming update with newer generation 2L
        val updateResult = authority.applyUpdate(configGen2)
        assertTrue(updateResult is ConfigTransitionResult.Applied)
        val applied = updateResult as ConfigTransitionResult.Applied
        assertFalse(applied.isInitial)
        assertEquals(configGen2, applied.config)

        // Current authority state transitioned to generation 2
        assertEquals(2L, authority.currentGeneration)
        assertEquals(AudioCodec.OPUS, authority.currentConfig?.codec)
        assertEquals(2L, authority.currentConfig?.generation)
        assertTrue(authority.currentConfig!!.transitionLogDescription.contains("generation=2"))
        assertTrue(authority.currentConfig!!.transitionLogDescription.contains("codec=OPUS"))
    }

    @Test
    fun testMultipleReceiversWithDifferentCapabilities() {
        // Receiver 1: supports 48 kHz (0x02) and 96 kHz (0x08) -> 0x0A
        val rx1Mask = AudioCapabilities.CAP_FLAG_48000 or AudioCapabilities.CAP_FLAG_96000
        // Receiver 2: supports 44.1 kHz (0x01) and 48 kHz (0x02) -> 0x03
        val rx2Mask = AudioCapabilities.CAP_FLAG_44100 or AudioCapabilities.CAP_FLAG_48000
        // Receiver 3: supports 48 kHz (0x02), 88.2 kHz (0x04), and 192 kHz (0x20) -> 0x26
        val rx3Mask = AudioCapabilities.CAP_FLAG_48000 or AudioCapabilities.CAP_FLAG_88200 or AudioCapabilities.CAP_FLAG_192000

        // Test multi-receiver resolution: common intersection must be 48 kHz (0x02)
        val mutuallySupported = StreamNegotiator.resolveMutuallySupportedCapabilities(listOf(rx1Mask, rx2Mask, rx3Mask))
        assertEquals(AudioCapabilities.CAP_FLAG_48000, mutuallySupported)

        // Negotiation across all 3 receivers must deterministically produce 48 kHz
        val txMask = AudioCapabilities.CAP_FLAG_48000 or AudioCapabilities.CAP_FLAG_44100 or AudioCapabilities.CAP_FLAG_96000
        val request = StreamNegotiator.NegotiationRequest(
            preferredProfile = LatencyTarget.BALANCED,
            preferredSampleRateHz = 96000, // Prefers 96kHz, but must clamp to mutually supported 48kHz
            preferred24Bit = true,
            txCapabilitiesMask = txMask,
            rxCapabilitiesList = listOf(rx1Mask, rx2Mask, rx3Mask)
        )
        val config = StreamNegotiator.negotiate(request, generation = 5L)
        assertEquals(48000, config.sampleRateHz)
        assertEquals(5L, config.generation)

        // Adding another receiver must NOT oscillate the configuration
        val rx4Mask = AudioCapabilities.CAP_FLAG_48000
        val request2 = request.copy(rxCapabilitiesList = listOf(rx1Mask, rx2Mask, rx3Mask, rx4Mask))
        val config2 = StreamNegotiator.negotiate(request2, generation = 5L)
        assertEquals(48000, config2.sampleRateHz)
        assertEquals(config.audioFormat, config2.audioFormat)
        assertEquals(config.codec, config2.codec)
    }

    @Test
    fun testAsynchronousStaleConfigUpdateArrivingAfterCurrentConfig() {
        val authority = StreamConfigurationAuthority()

        val configGen1 = NegotiatedStreamConfig(
            audioFormat = AudioFormatConfig(AudioSampleRate.RATE_48000, AudioBitDepth.BIT_16),
            codec = AudioCodec.PCM,
            transportProfile = TransportProfile.create(LatencyTarget.BALANCED),
            generation = 1L
        )
        val configGen2 = NegotiatedStreamConfig(
            audioFormat = AudioFormatConfig(AudioSampleRate.RATE_44100, AudioBitDepth.BIT_16),
            codec = AudioCodec.PCM,
            transportProfile = TransportProfile.create(LatencyTarget.BALANCED),
            generation = 2L
        )
        val configGen3 = NegotiatedStreamConfig(
            audioFormat = AudioFormatConfig(AudioSampleRate.RATE_48000, AudioBitDepth.BIT_24),
            codec = AudioCodec.PCM,
            transportProfile = TransportProfile.create(LatencyTarget.RELIABLE),
            generation = 3L
        )

        // Initial config applied
        authority.applyUpdate(configGen1)
        assertEquals(1L, authority.currentGeneration)

        // A newer update (generation 3) is applied first (e.g. fast callback)
        val res3 = authority.applyUpdate(configGen3)
        assertTrue(res3 is ConfigTransitionResult.Applied)
        assertEquals(3L, authority.currentGeneration)
        assertEquals(24, authority.currentConfig?.bitDepthBits)

        // A delayed/asynchronous older callback for generation 2 arrives after generation 3
        val res2 = authority.applyUpdate(configGen2)
        assertTrue(res2 is ConfigTransitionResult.RejectedStale)
        assertEquals(2L, (res2 as ConfigTransitionResult.RejectedStale).incomingGeneration)
        assertEquals(3L, res2.currentGeneration)

        // Authority MUST strictly keep generation 3 and reject generation 2
        assertEquals(3L, authority.currentGeneration)
        assertEquals(24, authority.currentConfig?.bitDepthBits)
        assertEquals(configGen3, authority.currentConfig)
    }

    @Test
    fun testPcmAndLosslessPacketAgreementCompatibility() {
        val pcmConfig = NegotiatedStreamConfig(
            audioFormat = AudioFormatConfig(AudioSampleRate.RATE_48000, AudioBitDepth.BIT_24),
            codec = AudioCodec.PCM,
            transportProfile = TransportProfile.create(LatencyTarget.RELIABLE),
            generation = 1L
        )

        // 1. Raw PCM audio packet matches
        val rawPcmHeader = pcmConfig.createHeader(
            packetType = HatPacket.TYPE_AUDIO,
            sequenceNumber = 10,
            payloadLength = 1440,
            timestamp = 0L
        )
        assertTrue(pcmConfig.validatePacketAgreement(rawPcmHeader).isAgreed)

        // 2. Lossless compressed audio packet during PCM stream must be AGREED (does not trigger stream codec switch)
        val losslessPcmHeader = rawPcmHeader.copy(codec = HatPacket.CODEC_LOSSLESS_PCM)
        val losslessResult = pcmConfig.validatePacketAgreement(losslessPcmHeader)
        assertTrue("Lossless PCM packet must be compatible with PCM stream config", losslessResult.isAgreed)

        // 3. Individual foreign packet (Opus) must be rejected
        val opusHeader = rawPcmHeader.copy(codec = HatPacket.CODEC_OPUS)
        val opusResult = pcmConfig.validatePacketAgreement(opusHeader)
        assertFalse(opusResult.isAgreed)
        assertTrue((opusResult as AgreementResult.Disagreement).reason.contains("Codec disagreement"))

        // 4. Individual foreign packet (AAC) must be rejected
        val aacHeader = rawPcmHeader.copy(codec = HatPacket.CODEC_AAC)
        val aacResult = pcmConfig.validatePacketAgreement(aacHeader)
        assertFalse(aacResult.isAgreed)
        assertTrue((aacResult as AgreementResult.Disagreement).reason.contains("Codec disagreement"))
    }

    @Test
    fun testStaleAudioPacketAfterGenerationTransition() {
        val configGen1 = NegotiatedStreamConfig(
            audioFormat = AudioFormatConfig(AudioSampleRate.RATE_48000, AudioBitDepth.BIT_24),
            codec = AudioCodec.PCM,
            transportProfile = TransportProfile.create(LatencyTarget.BALANCED),
            generation = 1L
        )

        val configGen2 = NegotiatedStreamConfig(
            audioFormat = AudioFormatConfig(AudioSampleRate.RATE_44100, AudioBitDepth.BIT_16),
            codec = AudioCodec.PCM,
            transportProfile = TransportProfile.create(LatencyTarget.RELIABLE),
            generation = 2L
        )

        // Transmitter sends an audio packet under generation 1
        val pkt1 = configGen1.createHeader(
            packetType = HatPacket.TYPE_AUDIO,
            sequenceNumber = 100,
            payloadLength = 1440,
            timestamp = 50000L
        )
        val buf1 = ByteArray(HatPacket.HEADER_SIZE + 1440)
        HatPacket.writeHeader(buf1, 0, pkt1)
        val parsed1 = HatPacket.parseHeader(buf1, 0, buf1.size)
        assertNotNull(parsed1)
        assertEquals(1L, parsed1?.generation)
        assertEquals(50000L, parsed1?.timestamp)

        // Stream transitions to generation 2
        val activeGenTag = configGen2.generation and 0x7FL
        assertEquals(2L, activeGenTag)

        // Receiver evaluating incoming audio packet 1 against active generation 2:
        val isStale = parsed1?.generation != activeGenTag
        assertTrue("Packet from generation 1 must be flagged as stale when generation 2 is active", isStale)

        // Conversely, a packet created under generation 2 matches
        val pkt2 = configGen2.createHeader(
            packetType = HatPacket.TYPE_AUDIO,
            sequenceNumber = 1,
            payloadLength = 880,
            timestamp = 0L
        )
        val buf2 = ByteArray(HatPacket.HEADER_SIZE + 880)
        HatPacket.writeHeader(buf2, 0, pkt2)
        val parsed2 = HatPacket.parseHeader(buf2, 0, buf2.size)
        assertNotNull(parsed2)
        assertEquals(2L, parsed2?.generation)
        assertEquals(activeGenTag, parsed2?.generation)
    }

    @Test
    fun testOldPacketArrivingAfterNewGenerationAnnouncement() {
        val authority = StreamConfigurationAuthority()

        val configGen1 = NegotiatedStreamConfig(
            audioFormat = AudioFormatConfig(AudioSampleRate.RATE_48000, AudioBitDepth.BIT_24),
            codec = AudioCodec.PCM,
            transportProfile = TransportProfile.create(LatencyTarget.BALANCED),
            generation = 1L
        )
        val configGen2 = NegotiatedStreamConfig(
            audioFormat = AudioFormatConfig(AudioSampleRate.RATE_44100, AudioBitDepth.BIT_16),
            codec = AudioCodec.PCM,
            transportProfile = TransportProfile.create(LatencyTarget.RELIABLE),
            generation = 2L
        )

        // Establish initial generation 1
        authority.applyUpdate(configGen1)
        assertEquals(1L, authority.currentGeneration)

        // New generation announcement arrives
        val transitionResult = authority.applyUpdate(configGen2)
        assertTrue(transitionResult is ConfigTransitionResult.Applied)
        assertEquals(2L, authority.currentGeneration)

        // An old packet in-flight from generation 1 arrives AFTER the announcement
        val oldPacketHeader = configGen1.createHeader(
            packetType = HatPacket.TYPE_AUDIO,
            sequenceNumber = 999,
            payloadLength = 1440,
            timestamp = 100000L
        )
        val buf = ByteArray(HatPacket.HEADER_SIZE + 1440)
        HatPacket.writeHeader(buf, 0, oldPacketHeader)
        val parsedOld = HatPacket.parseHeader(buf, 0, buf.size)
        assertNotNull(parsedOld)

        val activeExpectedTag = authority.currentGeneration and 0x7FL
        val isStale = (parsedOld?.generation != activeExpectedTag)
        assertTrue("In-flight packet from generation 1 must be detected as stale after generation 2 announcement", isStale)
    }

    @Test
    fun testNewReceiverJoiningActiveStream_IncompatibleRejected() {
        val activeConfig = NegotiatedStreamConfig(
            audioFormat = AudioFormatConfig(AudioSampleRate.RATE_48000, AudioBitDepth.BIT_24),
            codec = AudioCodec.PCM,
            transportProfile = TransportProfile.create(LatencyTarget.BALANCED),
            generation = 1L
        )

        // Incompatible receiver: only supports 44.1 kHz
        val incompatibleCaps = AudioCapabilities.CAP_FLAG_44100
        val canConsume = activeConfig.canReceiverConsume(incompatibleCaps)
        assertFalse("Incompatible receiver must not be able to consume 48kHz active stream", canConsume)

        // Active configuration is NOT changed or renegotiated
        assertEquals(48000, activeConfig.sampleRateHz)
        assertEquals(1L, activeConfig.generation)
    }

    @Test
    fun testNewReceiverJoiningActiveStream_CompatibleAccepted() {
        val activeConfig = NegotiatedStreamConfig(
            audioFormat = AudioFormatConfig(AudioSampleRate.RATE_48000, AudioBitDepth.BIT_24),
            codec = AudioCodec.PCM,
            transportProfile = TransportProfile.create(LatencyTarget.BALANCED),
            generation = 1L
        )

        // Compatible receiver: supports 48 kHz (and 44.1 kHz)
        val compatibleCaps = AudioCapabilities.CAP_FLAG_48000 or AudioCapabilities.CAP_FLAG_44100
        val canConsume = activeConfig.canReceiverConsume(compatibleCaps)
        assertTrue("Compatible receiver must be able to consume 48kHz active stream", canConsume)

        // Legacy receiver with caps = 0 (unstated) also assumed compatible
        assertTrue("Legacy receiver with caps 0 must be accepted", activeConfig.canReceiverConsume(0))

        // Active configuration remains authoritative and untouched
        assertEquals(48000, activeConfig.sampleRateHz)
        assertEquals(1L, activeConfig.generation)
    }

    @Test
    fun testPcmLogicalStreamCarryingRawAndLosslessWirePackets() {
        // AudioCodec wire support contract
        assertTrue(AudioCodec.PCM.isWireCodecSupported(HatPacket.CODEC_RAW_PCM))
        assertTrue(AudioCodec.PCM.isWireCodecSupported(HatPacket.CODEC_LOSSLESS_PCM))
        assertFalse(AudioCodec.PCM.isWireCodecSupported(HatPacket.CODEC_OPUS))
        assertFalse(AudioCodec.PCM.isWireCodecSupported(HatPacket.CODEC_AAC))

        assertTrue(AudioCodec.LOSSLESS.isWireCodecSupported(HatPacket.CODEC_RAW_PCM))
        assertTrue(AudioCodec.LOSSLESS.isWireCodecSupported(HatPacket.CODEC_LOSSLESS_PCM))

        assertTrue(AudioCodec.OPUS.isWireCodecSupported(HatPacket.CODEC_OPUS))
        assertFalse(AudioCodec.OPUS.isWireCodecSupported(HatPacket.CODEC_RAW_PCM))

        val pcmConfig = NegotiatedStreamConfig(
            audioFormat = AudioFormatConfig(AudioSampleRate.RATE_48000, AudioBitDepth.BIT_24),
            codec = AudioCodec.PCM,
            transportProfile = TransportProfile.create(LatencyTarget.RELIABLE),
            generation = 1L
        )

        // Wire packets with RAW PCM and LOSSLESS PCM both agree without mutating current config
        val rawHeader = pcmConfig.createHeader(HatPacket.TYPE_AUDIO, 1, 1440, 0L)
        val losslessHeader = rawHeader.copy(codec = HatPacket.CODEC_LOSSLESS_PCM)

        assertTrue(pcmConfig.validatePacketAgreement(rawHeader).isAgreed)
        assertTrue(pcmConfig.validatePacketAgreement(losslessHeader).isAgreed)
        assertEquals(AudioCodec.PCM, pcmConfig.codec)
    }

    @Test
    fun testGenerationTransitionWithInFlightPacketsFlushed() {
        val jitterBuffer = JitterBuffer()
        val configGen1 = NegotiatedStreamConfig(
            audioFormat = AudioFormatConfig(AudioSampleRate.RATE_48000, AudioBitDepth.BIT_24),
            codec = AudioCodec.PCM,
            transportProfile = TransportProfile.create(LatencyTarget.BALANCED),
            generation = 1L
        )
        jitterBuffer.applyConfiguration(configGen1)

        val payload = ByteArray(1440) { 1 }
        jitterBuffer.write(1, 0L, payload, 0, payload.size)
        jitterBuffer.write(2, 240L, payload, 0, payload.size)
        jitterBuffer.write(3, 480L, payload, 0, payload.size)
        assertEquals(3, jitterBuffer.getAvailableCount())

        // Generation transition occurs: reset jitter buffer to flush old in-flight packets
        val configGen2 = NegotiatedStreamConfig(
            audioFormat = AudioFormatConfig(AudioSampleRate.RATE_44100, AudioBitDepth.BIT_16),
            codec = AudioCodec.PCM,
            transportProfile = TransportProfile.create(LatencyTarget.RELIABLE),
            generation = 2L
        )
        jitterBuffer.reset()
        jitterBuffer.applyConfiguration(configGen2)

        // Jitter buffer queue is completely clear of old-generation packets
        assertEquals(0, jitterBuffer.getAvailableCount())

        // New generation packets enter clean jitter buffer
        val newPayload = ByteArray(880) { 2 }
        jitterBuffer.write(1, 0L, newPayload, 0, newPayload.size)
        assertEquals(1, jitterBuffer.getAvailableCount())
    }
}
