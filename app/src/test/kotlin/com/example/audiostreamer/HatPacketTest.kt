package com.example.audiostreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HatPacketTest {

    @Test
    fun testAudioPacketRoundtrip() {
        val header = HatPacket.Header(
            version = 1,
            packetType = HatPacket.TYPE_AUDIO,
            sequenceNumber = 42000,
            codec = HatPacket.CODEC_RAW_PCM,
            profile = HatPacket.PROFILE_MUSIC,
            sampleRateCode = HatPacket.RATE_48000,
            bitDepth = HatPacket.BIT_DEPTH_24,
            channels = HatPacket.CHANNELS_STEREO,
            volumeOrCaps = 85,
            fecBlockSize = 0,
            flags = HatPacket.FLAG_NONE,
            payloadLength = 1440
        )

        val buffer = ByteArray(HatPacket.HEADER_SIZE + 1440)
        HatPacket.writeHeader(buffer, 0, header)

        // Verify Big-Endian sequence number: 42000 = 0xA410
        assertEquals(0xA4.toByte(), buffer[4])
        assertEquals(0x10.toByte(), buffer[5])

        // Verify Big-Endian payload length: 1440 = 0x05A0
        assertEquals(0x05.toByte(), buffer[14])
        assertEquals(0xA0.toByte(), buffer[15])

        val parsed = HatPacket.parseHeader(buffer, 0, buffer.size)
        assertNotNull(parsed)
        assertEquals(1.toByte(), parsed?.version)
        assertEquals(HatPacket.TYPE_AUDIO, parsed?.packetType)
        assertEquals(42000, parsed?.sequenceNumber)
        assertEquals(HatPacket.CODEC_RAW_PCM, parsed?.codec)
        assertEquals(HatPacket.PROFILE_MUSIC, parsed?.profile)
        assertEquals(HatPacket.RATE_48000, parsed?.sampleRateCode)
        assertEquals(48000, parsed?.sampleRateHz)
        assertEquals(HatPacket.BIT_DEPTH_24, parsed?.bitDepth)
        assertEquals(HatPacket.CHANNELS_STEREO, parsed?.channels)
        assertEquals(85.toByte(), parsed?.volumeOrCaps)
        assertEquals(1440, parsed?.payloadLength)
    }

    @Test
    fun testCompressedAudioPacketRoundtrip() {
        val header = HatPacket.Header(
            version = 1,
            packetType = HatPacket.TYPE_AUDIO,
            sequenceNumber = 1234,
            codec = HatPacket.CODEC_OPUS,
            profile = HatPacket.PROFILE_LOW_LATENCY,
            sampleRateCode = HatPacket.RATE_48000,
            bitDepth = HatPacket.BIT_DEPTH_NONE,
            channels = HatPacket.CHANNELS_STEREO,
            volumeOrCaps = 100,
            fecBlockSize = 0,
            flags = HatPacket.FLAG_P2P_ACTIVE,
            payloadLength = 320
        )

        val buffer = ByteArray(HatPacket.HEADER_SIZE + 320)
        HatPacket.writeHeader(buffer, 0, header)

        val parsed = HatPacket.parseHeader(buffer, 0, buffer.size)
        assertNotNull(parsed)
        assertEquals(HatPacket.CODEC_OPUS, parsed?.codec)
        assertEquals(HatPacket.PROFILE_LOW_LATENCY, parsed?.profile)
        assertEquals(HatPacket.FLAG_P2P_ACTIVE, parsed?.flags)
        assertEquals(320, parsed?.payloadLength)
    }

    @Test
    fun testFecParityPacketRoundtrip() {
        val header = HatPacket.Header(
            version = 1,
            packetType = HatPacket.TYPE_FEC_PARITY,
            sequenceNumber = 100,
            codec = HatPacket.CODEC_RAW_PCM,
            profile = HatPacket.PROFILE_MUSIC,
            sampleRateCode = HatPacket.RATE_48000,
            bitDepth = HatPacket.BIT_DEPTH_24,
            channels = HatPacket.CHANNELS_STEREO,
            volumeOrCaps = 90,
            fecBlockSize = 4,
            flags = HatPacket.FLAG_NONE,
            payloadLength = 1440
        )

        val buffer = ByteArray(HatPacket.HEADER_SIZE + 1440)
        HatPacket.writeHeader(buffer, 0, header)

        val parsed = HatPacket.parseHeader(buffer, 0, buffer.size)
        assertNotNull(parsed)
        assertEquals(HatPacket.TYPE_FEC_PARITY, parsed?.packetType)
        assertEquals(4.toByte(), parsed?.fecBlockSize)
        assertEquals(1440, parsed?.payloadLength)
    }

    @Test
    fun testControlAndHeartbeatPackets() {
        val types = listOf(
            HatPacket.TYPE_SILENCE_HEARTBEAT,
            HatPacket.TYPE_CONTROL,
            HatPacket.TYPE_RECEIVER_HEARTBEAT,
            HatPacket.TYPE_REVERSE_VOLUME_SYNC,
            HatPacket.TYPE_DISCONNECT,
            HatPacket.TYPE_DISCOVERY_PROBE
        )

        for (type in types) {
            val header = HatPacket.Header(
                packetType = type,
                volumeOrCaps = 75,
                payloadLength = 0
            )
            val buffer = ByteArray(HatPacket.HEADER_SIZE)
            HatPacket.writeHeader(buffer, 0, header)

            val parsed = HatPacket.parseHeader(buffer, 0, buffer.size)
            assertNotNull("Failed parsing type $type", parsed)
            assertEquals(type, parsed?.packetType)
            assertEquals(0, parsed?.payloadLength)
            assertEquals(75.toByte(), parsed?.volumeOrCaps)
        }
    }

    @Test
    fun testDiscoveryAnnouncePacket() {
        val payload = "AudioStreamer-Transmitter".toByteArray(Charsets.UTF_8)
        val header = HatPacket.Header(
            packetType = HatPacket.TYPE_DISCOVERY_ANNOUNCE,
            payloadLength = payload.size
        )
        val buffer = ByteArray(HatPacket.HEADER_SIZE + payload.size)
        HatPacket.writeHeader(buffer, 0, header)
        System.arraycopy(payload, 0, buffer, HatPacket.HEADER_SIZE, payload.size)

        val parsed = HatPacket.parseHeader(buffer, 0, buffer.size)
        assertNotNull(parsed)
        assertEquals(HatPacket.TYPE_DISCOVERY_ANNOUNCE, parsed?.packetType)
        assertEquals(payload.size, parsed?.payloadLength)
    }

    @Test
    fun testRejectionOfCorruptedMagic() {
        val buffer = ByteArray(HatPacket.HEADER_SIZE)
        buffer[0] = 0x53 // 'S' instead of 'H'
        buffer[1] = 0x41 // 'A' instead of 'T'
        buffer[2] = HatPacket.PROTOCOL_VERSION
        buffer[3] = HatPacket.TYPE_CONTROL

        val parsed = HatPacket.parseHeader(buffer, 0, buffer.size)
        assertNull(parsed)
    }

    @Test
    fun testRejectionOfUnsupportedVersion() {
        val buffer = ByteArray(HatPacket.HEADER_SIZE)
        buffer[0] = HatPacket.MAGIC_BYTE_0
        buffer[1] = HatPacket.MAGIC_BYTE_1
        buffer[2] = 2 // Unsupported version 2
        buffer[3] = HatPacket.TYPE_CONTROL

        val parsed = HatPacket.parseHeader(buffer, 0, buffer.size)
        assertNull(parsed)
    }

    @Test
    fun testRejectionOfUnknownPacketType() {
        val buffer = ByteArray(HatPacket.HEADER_SIZE)
        buffer[0] = HatPacket.MAGIC_BYTE_0
        buffer[1] = HatPacket.MAGIC_BYTE_1
        buffer[2] = HatPacket.PROTOCOL_VERSION
        buffer[3] = 0x00 // Unknown type

        assertNull(HatPacket.parseHeader(buffer, 0, buffer.size))

        buffer[3] = 0x20 // Out of range
        assertNull(HatPacket.parseHeader(buffer, 0, buffer.size))
    }

    @Test
    fun testRejectionOfTruncatedPacket() {
        val header = HatPacket.Header(
            packetType = HatPacket.TYPE_AUDIO,
            codec = HatPacket.CODEC_RAW_PCM,
            profile = HatPacket.PROFILE_MUSIC,
            sampleRateCode = HatPacket.RATE_48000,
            bitDepth = HatPacket.BIT_DEPTH_24,
            channels = HatPacket.CHANNELS_STEREO,
            payloadLength = 1440
        )
        val buffer = ByteArray(HatPacket.HEADER_SIZE + 1440)
        HatPacket.writeHeader(buffer, 0, header)

        // Datagram truncated: length reported as 500 instead of 1456
        val parsed = HatPacket.parseHeader(buffer, 0, 500)
        assertNull(parsed)
    }

    @Test
    fun testRejectionOfExcessivePayloadLength() {
        val buffer = ByteArray(HatPacket.HEADER_SIZE)
        buffer[0] = HatPacket.MAGIC_BYTE_0
        buffer[1] = HatPacket.MAGIC_BYTE_1
        buffer[2] = HatPacket.PROTOCOL_VERSION
        buffer[3] = HatPacket.TYPE_AUDIO
        // Set payload length to 10000 (> MAX_PACKET_SIZE)
        HatPacket.writeUInt16BE(buffer, 14, 10000)

        val parsed = HatPacket.parseHeader(buffer, 0, buffer.size)
        assertNull(parsed)
    }

    @Test
    fun testRejectionOfControlPacketsWithPayload() {
        // Control packets must not have a payload
        val header = HatPacket.Header(
            packetType = HatPacket.TYPE_CONTROL,
            payloadLength = 100
        )
        val buffer = ByteArray(HatPacket.HEADER_SIZE + 100)
        HatPacket.writeHeader(buffer, 0, header)

        val parsed = HatPacket.parseHeader(buffer, 0, buffer.size)
        assertNull(parsed)
    }

    @Test
    fun testRejectionOfFecPacketWithInvalidBlockSize() {
        val header = HatPacket.Header(
            packetType = HatPacket.TYPE_FEC_PARITY,
            fecBlockSize = 1, // Invalid: must be between 2 and 16
            payloadLength = 1440
        )
        val buffer = ByteArray(HatPacket.HEADER_SIZE + 1440)
        HatPacket.writeHeader(buffer, 0, header)

        assertNull(HatPacket.parseHeader(buffer, 0, buffer.size))

        val header2 = HatPacket.Header(
            packetType = HatPacket.TYPE_FEC_PARITY,
            fecBlockSize = 17, // Invalid: must be <= 16
            payloadLength = 1440
        )
        HatPacket.writeHeader(buffer, 0, header2)
        assertNull(HatPacket.parseHeader(buffer, 0, buffer.size))
    }

    @Test
    fun testRejectionOfAudioPacketWithZeroPayload() {
        val header = HatPacket.Header(
            packetType = HatPacket.TYPE_AUDIO,
            codec = HatPacket.CODEC_RAW_PCM,
            profile = HatPacket.PROFILE_MUSIC,
            sampleRateCode = HatPacket.RATE_48000,
            bitDepth = HatPacket.BIT_DEPTH_24,
            channels = HatPacket.CHANNELS_STEREO,
            payloadLength = 0 // Invalid for audio packet
        )
        val buffer = ByteArray(HatPacket.HEADER_SIZE)
        HatPacket.writeHeader(buffer, 0, header)

        assertNull(HatPacket.parseHeader(buffer, 0, buffer.size))
    }

    @Test
    fun testRejectionOfAudioPacketWithInvalidSampleRateOrChannels() {
        val header = HatPacket.Header(
            packetType = HatPacket.TYPE_AUDIO,
            codec = HatPacket.CODEC_RAW_PCM,
            profile = HatPacket.PROFILE_MUSIC,
            sampleRateCode = HatPacket.RATE_NONE, // Invalid rate code
            bitDepth = HatPacket.BIT_DEPTH_24,
            channels = HatPacket.CHANNELS_STEREO,
            payloadLength = 1440
        )
        val buffer = ByteArray(HatPacket.HEADER_SIZE + 1440)
        HatPacket.writeHeader(buffer, 0, header)
        assertNull(HatPacket.parseHeader(buffer, 0, buffer.size))

        val headerMono = HatPacket.Header(
            packetType = HatPacket.TYPE_AUDIO,
            codec = HatPacket.CODEC_RAW_PCM,
            profile = HatPacket.PROFILE_MUSIC,
            sampleRateCode = HatPacket.RATE_48000,
            bitDepth = HatPacket.BIT_DEPTH_24,
            channels = 1, // Invalid: mono not supported
            payloadLength = 1440
        )
        HatPacket.writeHeader(buffer, 0, headerMono)
        assertNull(HatPacket.parseHeader(buffer, 0, buffer.size))
    }

    @Test
    fun testSampleRateCodeMappings() {
        assertEquals(HatPacket.RATE_44100, HatPacket.sampleRateToCode(44100))
        assertEquals(HatPacket.RATE_48000, HatPacket.sampleRateToCode(48000))
        assertEquals(HatPacket.RATE_88200, HatPacket.sampleRateToCode(88200))
        assertEquals(HatPacket.RATE_96000, HatPacket.sampleRateToCode(96000))
        assertEquals(HatPacket.RATE_176400, HatPacket.sampleRateToCode(176400))
        assertEquals(HatPacket.RATE_192000, HatPacket.sampleRateToCode(192000))

        assertEquals(44100, HatPacket.rateCodeToHz(HatPacket.RATE_44100))
        assertEquals(48000, HatPacket.rateCodeToHz(HatPacket.RATE_48000))
        assertEquals(88200, HatPacket.rateCodeToHz(HatPacket.RATE_88200))
        assertEquals(96000, HatPacket.rateCodeToHz(HatPacket.RATE_96000))
        assertEquals(176400, HatPacket.rateCodeToHz(HatPacket.RATE_176400))
        assertEquals(192000, HatPacket.rateCodeToHz(HatPacket.RATE_192000))
    }

    @Test
    fun testProfileCodeMappings() {
        assertEquals(HatPacket.PROFILE_LOW_LATENCY, HatPacket.profileStringToCode(AudioConfig.PROFILE_LOW_LATENCY))
        assertEquals(HatPacket.PROFILE_LOW_LATENCY, HatPacket.profileStringToCode(AudioConfig.PROFILE_VIDEO))
        assertEquals(HatPacket.PROFILE_AUTO, HatPacket.profileStringToCode(AudioConfig.PROFILE_AUTO))
        assertEquals(HatPacket.PROFILE_MUSIC, HatPacket.profileStringToCode(AudioConfig.PROFILE_MUSIC))

        assertEquals(AudioConfig.PROFILE_LOW_LATENCY, HatPacket.profileCodeToString(HatPacket.PROFILE_LOW_LATENCY))
        assertEquals(AudioConfig.PROFILE_AUTO, HatPacket.profileCodeToString(HatPacket.PROFILE_AUTO))
        assertEquals(AudioConfig.PROFILE_MUSIC, HatPacket.profileCodeToString(HatPacket.PROFILE_MUSIC))
    }
}
