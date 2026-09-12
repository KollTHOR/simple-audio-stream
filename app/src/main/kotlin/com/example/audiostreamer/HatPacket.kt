package com.example.audiostreamer

/**
 * High-definition Audio Transport (HAT) Packet Protocol.
 *
 * 24-byte versioned header with explicit, un-overloaded fields:
 * Offset  Size  Field              Description
 * ----------------------------------------------------------------------------------
 * 0..1     2    magic              0x4854 ("HT", HAT Transport)
 * 2        1    version            Protocol version (currently 1)
 * 3        1    packetType         Packet type (Audio, Silence, FEC, Control, etc.)
 * 4..5     2    sequenceNumber     16-bit sequence number (Big-Endian uint16)
 * 6..7     2    payloadLength      Payload length in bytes (Big-Endian uint16)
 * 8..15    8    timestamp          Audio timeline timestamp in frames (Big-Endian uint64)
 * 16       1    codec              Codec identifier (Raw PCM, Lossless, Opus, AAC)
 * 17       1    profile            Streaming profile (Auto, Music, Low Latency)
 * 18       1    sampleRateCode     Sample rate enumeration (44.1k, 48k, etc.)
 * 19       1    bitDepth           Bit depth (16, 24)
 * 20       1    channels           Channel count (2 for stereo)
 * 21       1    volumeOrCaps       Volume (0..100) or capabilities mask
 * 22       1    fecBlockSize       FEC parity block size (K, e.g. 4)
 * 23       1    flags              Protocol flags (e.g. Wi-Fi Direct P2P active)
 */
object HatPacket {
    const val HEADER_SIZE = 24
    const val MAGIC: Short = 0x4854 // "HT"
    const val MAGIC_BYTE_0: Byte = 0x48 // 'H'
    const val MAGIC_BYTE_1: Byte = 0x54 // 'T'
    const val PROTOCOL_VERSION: Byte = 1

    // Packet Types (Byte 3)
    const val TYPE_AUDIO: Byte = 0x01
    const val TYPE_SILENCE_HEARTBEAT: Byte = 0x02
    const val TYPE_FEC_PARITY: Byte = 0x03
    const val TYPE_CONTROL: Byte = 0x04
    const val TYPE_RECEIVER_HEARTBEAT: Byte = 0x05
    const val TYPE_REVERSE_VOLUME_SYNC: Byte = 0x06
    const val TYPE_DISCONNECT: Byte = 0x07
    const val TYPE_DISCOVERY_PROBE: Byte = 0x08
    const val TYPE_DISCOVERY_ANNOUNCE: Byte = 0x09

    // Codec Types (Byte 16)
    const val CODEC_RAW_PCM: Byte = 0x00
    const val CODEC_LOSSLESS_PCM: Byte = 0x01
    const val CODEC_OPUS: Byte = 0x02
    const val CODEC_AAC: Byte = 0x03

    // Stream Profile Types (Byte 17)
    const val PROFILE_AUTO: Byte = 0x01
    const val PROFILE_MUSIC: Byte = 0x02
    const val PROFILE_LOW_LATENCY: Byte = 0x03

    // Sample Rate Codes (Byte 18)
    const val RATE_NONE: Byte = 0x00
    const val RATE_44100: Byte = 0x01
    const val RATE_48000: Byte = 0x02
    const val RATE_88200: Byte = 0x03
    const val RATE_96000: Byte = 0x04
    const val RATE_176400: Byte = 0x05
    const val RATE_192000: Byte = 0x06

    // Bit Depths (Byte 19)
    const val BIT_DEPTH_NONE: Byte = 0
    const val BIT_DEPTH_16: Byte = 16
    const val BIT_DEPTH_24: Byte = 24

    // Channels (Byte 20)
    const val CHANNELS_STEREO: Byte = 2

    // Flags (Byte 23)
    const val FLAG_NONE: Byte = 0x00
    const val FLAG_P2P_ACTIVE: Byte = 0x01

    data class Header(
        val version: Byte = PROTOCOL_VERSION,
        val packetType: Byte,
        val sequenceNumber: Int = 0,
        val payloadLength: Int = 0,
        val timestamp: Long = 0L,
        val codec: Byte = CODEC_RAW_PCM,
        val profile: Byte = PROFILE_MUSIC,
        val sampleRateCode: Byte = RATE_48000,
        val bitDepth: Byte = BIT_DEPTH_24,
        val channels: Byte = CHANNELS_STEREO,
        val volumeOrCaps: Byte = 0,
        val fecBlockSize: Byte = 0,
        val flags: Byte = FLAG_NONE
    ) {
        val sampleRateHz: Int get() = rateCodeToHz(sampleRateCode)
    }

    fun sampleRateToCode(rateHz: Int): Byte = when (rateHz) {
        44100 -> RATE_44100
        48000 -> RATE_48000
        88200 -> RATE_88200
        96000 -> RATE_96000
        176400 -> RATE_176400
        192000 -> RATE_192000
        else -> RATE_48000
    }

    fun rateCodeToHz(code: Byte): Int = when (code) {
        RATE_44100 -> 44100
        RATE_48000 -> 48000
        RATE_88200 -> 88200
        RATE_96000 -> 96000
        RATE_176400 -> 176400
        RATE_192000 -> 192000
        else -> 48000
    }

    fun profileStringToCode(profileStr: String): Byte = when (profileStr) {
        AudioConfig.PROFILE_VIDEO, AudioConfig.PROFILE_LOW_LATENCY -> PROFILE_LOW_LATENCY
        AudioConfig.PROFILE_AUTO -> PROFILE_AUTO
        else -> PROFILE_MUSIC
    }

    fun profileCodeToString(code: Byte): String = when (code) {
        PROFILE_LOW_LATENCY -> AudioConfig.PROFILE_LOW_LATENCY
        PROFILE_AUTO -> AudioConfig.PROFILE_AUTO
        else -> AudioConfig.PROFILE_MUSIC
    }

    /**
     * Big-Endian 16-bit integer serialization.
     */
    fun writeUInt16BE(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }

    /**
     * Big-Endian 16-bit integer deserialization.
     */
    fun readUInt16BE(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
    }

    /**
     * Big-Endian 64-bit integer serialization.
     */
    fun writeUInt64BE(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = ((value shr 56) and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 48) and 0xFF).toByte()
        buffer[offset + 2] = ((value shr 40) and 0xFF).toByte()
        buffer[offset + 3] = ((value shr 32) and 0xFF).toByte()
        buffer[offset + 4] = ((value shr 24) and 0xFF).toByte()
        buffer[offset + 5] = ((value shr 16) and 0xFF).toByte()
        buffer[offset + 6] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 7] = (value and 0xFF).toByte()
    }

    /**
     * Big-Endian 64-bit integer deserialization.
     */
    fun readUInt64BE(buffer: ByteArray, offset: Int): Long {
        return ((buffer[offset].toLong() and 0xFFL) shl 56) or
               ((buffer[offset + 1].toLong() and 0xFFL) shl 48) or
               ((buffer[offset + 2].toLong() and 0xFFL) shl 40) or
               ((buffer[offset + 3].toLong() and 0xFFL) shl 32) or
               ((buffer[offset + 4].toLong() and 0xFFL) shl 24) or
               ((buffer[offset + 5].toLong() and 0xFFL) shl 16) or
               ((buffer[offset + 6].toLong() and 0xFFL) shl 8) or
               (buffer[offset + 7].toLong() and 0xFFL)
    }

    /**
     * Writes an explicit HAT header into [buffer] starting at [offset].
     */
    fun writeHeader(buffer: ByteArray, offset: Int = 0, header: Header) {
        require(buffer.size >= offset + HEADER_SIZE) { "Buffer too small for HAT header" }

        // 0..1: Magic "HT"
        buffer[offset] = MAGIC_BYTE_0
        buffer[offset + 1] = MAGIC_BYTE_1

        // 2: Protocol Version
        buffer[offset + 2] = header.version

        // 3: Packet Type
        buffer[offset + 3] = header.packetType

        // 4..5: Sequence Number (UInt16 BE)
        writeUInt16BE(buffer, offset + 4, header.sequenceNumber and 0xFFFF)

        // 6..7: Payload Length (UInt16 BE)
        writeUInt16BE(buffer, offset + 6, header.payloadLength and 0xFFFF)

        // 8..15: Audio Timeline Timestamp (UInt64 BE)
        writeUInt64BE(buffer, offset + 8, header.timestamp)

        // 16: Codec
        buffer[offset + 16] = header.codec

        // 17: Stream Profile
        buffer[offset + 17] = header.profile

        // 18: Sample Rate Code
        buffer[offset + 18] = header.sampleRateCode

        // 19: Bit Depth
        buffer[offset + 19] = header.bitDepth

        // 20: Channels
        buffer[offset + 20] = header.channels

        // 21: Volume or Capabilities
        buffer[offset + 21] = header.volumeOrCaps

        // 22: FEC Block Size
        buffer[offset + 22] = header.fecBlockSize

        // 23: Flags
        buffer[offset + 23] = header.flags
    }

    /**
     * Strictly and defensively parses a HAT header from [buffer].
     * Returns null if:
     * - length is less than HEADER_SIZE
     * - magic is invalid
     * - version is unsupported
     * - packet type is unknown
     * - payload length is out of bounds or exceeds packet length
     * - negative timestamp
     * - type-specific invariants are violated
     */
    fun parseHeader(buffer: ByteArray, offset: Int = 0, length: Int): Header? {
        if (length < HEADER_SIZE || offset < 0 || offset + HEADER_SIZE > buffer.size) {
            return null
        }

        // 1. Strict Magic Check
        if (buffer[offset] != MAGIC_BYTE_0 || buffer[offset + 1] != MAGIC_BYTE_1) {
            return null
        }

        // 2. Strict Version Check
        val version = buffer[offset + 2]
        if (version != PROTOCOL_VERSION) {
            return null
        }

        // 3. Strict Packet Type Check
        val packetType = buffer[offset + 3]
        if (packetType !in TYPE_AUDIO..TYPE_DISCOVERY_ANNOUNCE) {
            return null
        }

        // 4. Strict Payload Length Check
        val payloadLength = readUInt16BE(buffer, offset + 6)
        if (payloadLength < 0 || payloadLength > AudioConfig.MAX_PACKET_SIZE) {
            return null
        }
        if (length < HEADER_SIZE + payloadLength) {
            return null // Truncated datagram
        }

        // 5. Strict Audio Timeline Timestamp Check
        val timestamp = readUInt64BE(buffer, offset + 8)
        if (timestamp < 0L) {
            return null
        }

        val codec = buffer[offset + 16]
        val profile = buffer[offset + 17]
        val sampleRateCode = buffer[offset + 18]
        val bitDepth = buffer[offset + 19]
        val channels = buffer[offset + 20]
        val volumeOrCaps = buffer[offset + 21]
        val fecBlockSize = buffer[offset + 22]
        val flags = buffer[offset + 23]

        // 6. Defensive Type-Specific Validation
        when (packetType) {
            TYPE_SILENCE_HEARTBEAT,
            TYPE_CONTROL,
            TYPE_RECEIVER_HEARTBEAT,
            TYPE_REVERSE_VOLUME_SYNC,
            TYPE_DISCONNECT,
            TYPE_DISCOVERY_PROBE -> {
                if (payloadLength != 0) return null
            }
            TYPE_FEC_PARITY -> {
                val fecSize = fecBlockSize.toInt() and 0xFF
                if (fecSize !in 2..16 || payloadLength == 0) return null
            }
            TYPE_AUDIO -> {
                if (codec !in CODEC_RAW_PCM..CODEC_AAC) return null
                if (profile !in PROFILE_AUTO..PROFILE_LOW_LATENCY) return null
                if (sampleRateCode !in RATE_44100..RATE_192000) return null
                if (codec in listOf(CODEC_RAW_PCM, CODEC_LOSSLESS_PCM) &&
                    bitDepth != BIT_DEPTH_16 && bitDepth != BIT_DEPTH_24) {
                    return null
                }
                if (codec == CODEC_OPUS) {
                    if (sampleRateCode != RATE_48000) return null
                    if (bitDepth != BIT_DEPTH_16 && bitDepth != BIT_DEPTH_NONE) return null
                }
                if (codec == CODEC_AAC) {
                    if (sampleRateCode != RATE_48000 && sampleRateCode != RATE_44100) return null
                    if (bitDepth != BIT_DEPTH_16 && bitDepth != BIT_DEPTH_NONE) return null
                }
                if (channels != CHANNELS_STEREO) return null
                if (payloadLength == 0) return null
            }
        }

        val sequenceNumber = readUInt16BE(buffer, offset + 4)

        return Header(
            version = version,
            packetType = packetType,
            sequenceNumber = sequenceNumber,
            payloadLength = payloadLength,
            timestamp = timestamp,
            codec = codec,
            profile = profile,
            sampleRateCode = sampleRateCode,
            bitDepth = bitDepth,
            channels = channels,
            volumeOrCaps = volumeOrCaps,
            fecBlockSize = fecBlockSize,
            flags = flags
        )
    }
}
