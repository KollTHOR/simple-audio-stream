package com.example.audiostreamer

/**
 * High-definition Audio Transport (HAT) Packet Protocol.
 *
 * 24-byte versioned header with explicit fields and 32-bit generation:
 * Offset  Size  Field              Description
 * ----------------------------------------------------------------------------------
 * 0..1     2    magic              0x4854 ("HT", HAT Transport)
 * 2        1    version            Protocol version (currently 1)
 * 3        1    packetType         Packet type (Audio, Silence, FEC, Control, etc.)
 * 4..5     2    sequenceNumber     16-bit sequence number (Big-Endian uint16)
 * 6..7     2    payloadLength      Payload length in bytes (Big-Endian uint16)
 * 8..15    8    timestamp          Audio timeline timestamp in frames (Big-Endian uint64) / 64-bit generation in CONTROL
 * 16       1    packedCodecProfile Codec (bits 0..1), Profile (bits 2..3), SampleRateCode (bits 4..7)
 * 17       1    packedFormatFlags  BitDepth (bits 0..1), Channels (bits 2..3), Flags (bits 4..7)
 * 18       1    volumeOrCaps       Volume (0..100) or capabilities mask
 * 19       1    fecBlockSize       FEC parity block size (K, e.g. 4)
 * 20..23   4    generation         32-bit stream generation (Big-Endian uint32)
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
    const val TYPE_STREAM_INVITE: Byte = 0x0A
    const val TYPE_TRANSMITTER_ANNOUNCE: Byte = 0x0B
    const val TYPE_MEDIA_CONTROL: Byte = 0x0C
    const val TYPE_MEDIA_METADATA: Byte = 0x0D

    // Media Control Commands (Byte 18 / volumeOrCaps)
    const val MEDIA_CMD_PLAY_PAUSE: Byte = 0x01
    const val MEDIA_CMD_PLAY: Byte = 0x02
    const val MEDIA_CMD_PAUSE: Byte = 0x03
    const val MEDIA_CMD_NEXT: Byte = 0x04
    const val MEDIA_CMD_PREVIOUS: Byte = 0x05

    fun describePacketType(type: Byte): String = when (type) {
        TYPE_AUDIO -> "AUDIO"
        TYPE_SILENCE_HEARTBEAT -> "SILENCE_HEARTBEAT"
        TYPE_FEC_PARITY -> "FEC_PARITY"
        TYPE_CONTROL -> "CONTROL"
        TYPE_RECEIVER_HEARTBEAT -> "RECEIVER_HEARTBEAT"
        TYPE_REVERSE_VOLUME_SYNC -> "REVERSE_VOLUME_SYNC"
        TYPE_DISCONNECT -> "DISCONNECT"
        TYPE_DISCOVERY_PROBE -> "DISCOVERY_PROBE"
        TYPE_DISCOVERY_ANNOUNCE -> "DISCOVERY_ANNOUNCE"
        TYPE_STREAM_INVITE -> "STREAM_INVITE"
        TYPE_TRANSMITTER_ANNOUNCE -> "TRANSMITTER_ANNOUNCE"
        TYPE_MEDIA_CONTROL -> "MEDIA_CONTROL"
        TYPE_MEDIA_METADATA -> "MEDIA_METADATA"
        else -> "UNKNOWN(0x${(type.toInt() and 0xFF).toString(16)})"
    }

    // Codec Types (Byte 16 bits 0..1)
    const val CODEC_RAW_PCM: Byte = 0x00
    const val CODEC_LOSSLESS_PCM: Byte = 0x01
    const val CODEC_OPUS: Byte = 0x02
    const val CODEC_AAC: Byte = 0x03

    // Stream Profile Types (Byte 16 bits 2..3)
    const val PROFILE_AUTO: Byte = 0x01
    const val PROFILE_MUSIC: Byte = 0x02
    const val PROFILE_LOW_LATENCY: Byte = 0x03

    // Sample Rate Codes (Byte 16 bits 4..7)
    const val RATE_NONE: Byte = 0x00
    const val RATE_44100: Byte = 0x01
    const val RATE_48000: Byte = 0x02
    const val RATE_88200: Byte = 0x03
    const val RATE_96000: Byte = 0x04
    const val RATE_176400: Byte = 0x05
    const val RATE_192000: Byte = 0x06

    // Bit Depths (Byte 17 bits 0..1)
    const val BIT_DEPTH_NONE: Byte = 0
    const val BIT_DEPTH_16: Byte = 16
    const val BIT_DEPTH_24: Byte = 24

    // Channels (Byte 17 bits 2..3)
    const val CHANNELS_STEREO: Byte = 2

    // Flags (Byte 17 bits 4..7)
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
        val flags: Byte = FLAG_NONE,
        val generation: Long = if (packetType == TYPE_CONTROL) timestamp else 0L
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
     * Big-Endian 32-bit unsigned integer serialization.
     */
    fun writeUInt32BE(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = ((value ushr 24) and 0xFFL).toByte()
        buffer[offset + 1] = ((value ushr 16) and 0xFFL).toByte()
        buffer[offset + 2] = ((value ushr 8) and 0xFFL).toByte()
        buffer[offset + 3] = (value and 0xFFL).toByte()
    }

    /**
     * Big-Endian 32-bit unsigned integer deserialization.
     */
    fun readUInt32BE(buffer: ByteArray, offset: Int): Long {
        return ((buffer[offset].toLong() and 0xFFL) shl 24) or
               ((buffer[offset + 1].toLong() and 0xFFL) shl 16) or
               ((buffer[offset + 2].toLong() and 0xFFL) shl 8) or
               (buffer[offset + 3].toLong() and 0xFFL)
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
     * Validates an incoming packet's stream generation against the receiver's active generation.
     *
     * Rules:
     * 1. Packets matching [currentGeneration] are valid.
     * 2. Legacy behavior: generation 0 packets are accepted while the receiver is initially expecting generation 1.
     * 3. Obsolete, stale, or mismatched generations are rejected.
     */
    fun isGenerationValid(packetGeneration: Long, currentGeneration: Long): Boolean {
        if (currentGeneration == 1L && packetGeneration == 0L) {
            return true
        }
        return packetGeneration == currentGeneration
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

        // 8..15: Audio Timeline Timestamp (UInt64 BE) or Stream Generation in Control Packets
        val tsToWrite = if (header.packetType == TYPE_CONTROL && header.generation > 0L) {
            header.generation
        } else {
            header.timestamp
        }
        writeUInt64BE(buffer, offset + 8, tsToWrite)

        // 16: Packed Codec, Profile, Sample Rate
        val byte16 = (header.codec.toInt() and 0x03) or
                     ((header.profile.toInt() and 0x03) shl 2) or
                     ((header.sampleRateCode.toInt() and 0x0F) shl 4)
        buffer[offset + 16] = byte16.toByte()

        // 17: Packed Bit Depth, Channels, Flags
        val bitDepthCode = when (header.bitDepth) {
            BIT_DEPTH_16 -> 1
            BIT_DEPTH_24 -> 2
            else -> 0
        }
        val byte17 = (bitDepthCode and 0x03) or
                     ((header.channels.toInt() and 0x03) shl 2) or
                     ((header.flags.toInt() and 0x0F) shl 4)
        buffer[offset + 17] = byte17.toByte()

        // 18: Volume or Capabilities
        buffer[offset + 18] = header.volumeOrCaps

        // 19: FEC Block Size
        buffer[offset + 19] = header.fecBlockSize

        // 20..23: 32-bit Stream Generation (UInt32 BE)
        val genToWrite = header.generation and 0xFFFFFFFFL
        writeUInt32BE(buffer, offset + 20, genToWrite)
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
        if (packetType !in TYPE_AUDIO..TYPE_MEDIA_METADATA) {
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

        val byte16 = buffer[offset + 16].toInt() and 0xFF
        val codec = (byte16 and 0x03).toByte()
        val profile = ((byte16 ushr 2) and 0x03).toByte()
        val sampleRateCode = ((byte16 ushr 4) and 0x0F).toByte()

        val byte17 = buffer[offset + 17].toInt() and 0xFF
        val bitDepthCode = byte17 and 0x03
        val bitDepth = when (bitDepthCode) {
            1 -> BIT_DEPTH_16
            2 -> BIT_DEPTH_24
            else -> BIT_DEPTH_NONE
        }
        val channels = ((byte17 ushr 2) and 0x03).toByte()
        val flags = ((byte17 ushr 4) and 0x0F).toByte()

        val volumeOrCaps = buffer[offset + 18]
        val fecBlockSize = buffer[offset + 19]

        // 6. Defensive Type-Specific Validation
        when (packetType) {
            TYPE_SILENCE_HEARTBEAT,
            TYPE_CONTROL,
            TYPE_REVERSE_VOLUME_SYNC,
            TYPE_DISCONNECT,
            TYPE_DISCOVERY_PROBE,
            TYPE_MEDIA_CONTROL -> {
                if (payloadLength != 0) return null
            }
            TYPE_MEDIA_METADATA -> {
                if (payloadLength == 0) return null
            }
            TYPE_RECEIVER_HEARTBEAT -> {
                // Heartbeats may be payload-free or carry NodeCapabilityExchange metadata
            }
            TYPE_FEC_PARITY -> {
                val fecSize = fecBlockSize.toInt() and 0xFF
                if (fecSize !in 2..16 || payloadLength == 0) return null
            }
            TYPE_AUDIO -> {
                if (codec !in CODEC_RAW_PCM..CODEC_AAC) return null
                if (profile !in PROFILE_AUTO..PROFILE_LOW_LATENCY) return null
                if (sampleRateCode !in RATE_44100..RATE_192000) return null
                if ((codec == CODEC_RAW_PCM || codec == CODEC_LOSSLESS_PCM) &&
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
        val generation = if (packetType == TYPE_CONTROL) {
            timestamp
        } else {
            readUInt32BE(buffer, offset + 20)
        }

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
            flags = flags,
            generation = generation
        )
    }

    /**
     * Payload for TYPE_MEDIA_METADATA packets.
     *
     * Wire format (after the 24-byte HAT header):
     *   1     flags byte        bit 0 = isPlaying
     *   8     mediaStateSeq     monotonic session sequence (BE int64)
     *   2+N   packageName       UTF-8 length-prefixed (max 255 bytes)
     *   2+N   title             UTF-8 length-prefixed (max 255 bytes)
     *   2+N   artist            UTF-8 length-prefixed (max 255 bytes)
     *   2+N   album             UTF-8 length-prefixed (max 255 bytes)
     *
     * [mediaStateSequence] is incremented by MediaSessionTracker on every state change.
     * Receiver MUST reject packets whose sequence is <= its own lastReceivedSequence.
     *
     * A packet with empty title/artist/album and [isPlaying]=false is a valid
     * "no-active-media" clear event. Receiver must reset to fallback text.
     */
    data class MediaMetadataPayload(
        val isPlaying: Boolean,
        val title: String,
        val artist: String,
        val album: String,
        /** Monotonically increasing counter. Receiver rejects older values. */
        val mediaStateSequence: Long = 0L,
        /** Package name of the source media app, e.g. "com.aspiro.tidal". */
        val packageName: String = "",
        /** Optional JPEG/WebP compressed album art thumbnail (<= 1400 bytes). */
        val artworkBytes: ByteArray? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as MediaMetadataPayload
            if (isPlaying != other.isPlaying) return false
            if (title != other.title) return false
            if (artist != other.artist) return false
            if (album != other.album) return false
            if (mediaStateSequence != other.mediaStateSequence) return false
            if (packageName != other.packageName) return false
            if (artworkBytes != null) {
                if (other.artworkBytes == null) return false
                if (!artworkBytes.contentEquals(other.artworkBytes)) return false
            } else if (other.artworkBytes != null) return false
            return true
        }

        override fun hashCode(): Int {
            var result = isPlaying.hashCode()
            result = 31 * result + title.hashCode()
            result = 31 * result + artist.hashCode()
            result = 31 * result + album.hashCode()
            result = 31 * result + mediaStateSequence.hashCode()
            result = 31 * result + packageName.hashCode()
            result = 31 * result + (artworkBytes?.contentHashCode() ?: 0)
            return result
        }
    }

    fun serializeMediaMetadata(
        isPlaying: Boolean,
        title: String,
        artist: String,
        album: String,
        mediaStateSequence: Long = 0L,
        packageName: String = "",
        artworkBytes: ByteArray? = null
    ): ByteArray {
        val pkgBytes   = packageName.toByteArray(Charsets.UTF_8).take(255).toByteArray()
        val titleBytes = title.toByteArray(Charsets.UTF_8).take(255).toByteArray()
        val artistBytes = artist.toByteArray(Charsets.UTF_8).take(255).toByteArray()
        val albumBytes = album.toByteArray(Charsets.UTF_8).take(255).toByteArray()
        val artSafeBytes = if (artworkBytes != null && artworkBytes.size <= 65535) artworkBytes else null

        val artLen = artSafeBytes?.size ?: 0
        // 1 (flags) + 8 (seq) + (2+N)*4 + 2 (artLen) + artLen
        val totalLen = 1 + 8 +
            2 + pkgBytes.size +
            2 + titleBytes.size +
            2 + artistBytes.size +
            2 + albumBytes.size +
            2 + artLen
        val buf = ByteArray(totalLen)
        var pos = 0

        // flags byte
        buf[pos++] = if (isPlaying) 1 else 0

        // 8-byte sequence (big-endian int64)
        buf[pos++] = ((mediaStateSequence ushr 56) and 0xFF).toByte()
        buf[pos++] = ((mediaStateSequence ushr 48) and 0xFF).toByte()
        buf[pos++] = ((mediaStateSequence ushr 40) and 0xFF).toByte()
        buf[pos++] = ((mediaStateSequence ushr 32) and 0xFF).toByte()
        buf[pos++] = ((mediaStateSequence ushr 24) and 0xFF).toByte()
        buf[pos++] = ((mediaStateSequence ushr 16) and 0xFF).toByte()
        buf[pos++] = ((mediaStateSequence ushr  8) and 0xFF).toByte()
        buf[pos++] = ( mediaStateSequence          and 0xFF).toByte()

        // packageName
        buf[pos++] = ((pkgBytes.size shr 8) and 0xFF).toByte()
        buf[pos++] = (pkgBytes.size and 0xFF).toByte()
        System.arraycopy(pkgBytes, 0, buf, pos, pkgBytes.size); pos += pkgBytes.size

        // title
        buf[pos++] = ((titleBytes.size shr 8) and 0xFF).toByte()
        buf[pos++] = (titleBytes.size and 0xFF).toByte()
        System.arraycopy(titleBytes, 0, buf, pos, titleBytes.size); pos += titleBytes.size

        // artist
        buf[pos++] = ((artistBytes.size shr 8) and 0xFF).toByte()
        buf[pos++] = (artistBytes.size and 0xFF).toByte()
        System.arraycopy(artistBytes, 0, buf, pos, artistBytes.size); pos += artistBytes.size

        // album
        buf[pos++] = ((albumBytes.size shr 8) and 0xFF).toByte()
        buf[pos++] = (albumBytes.size and 0xFF).toByte()
        System.arraycopy(albumBytes, 0, buf, pos, albumBytes.size); pos += albumBytes.size

        // artworkBytes
        buf[pos++] = ((artLen shr 8) and 0xFF).toByte()
        buf[pos++] = (artLen and 0xFF).toByte()
        if (artLen > 0 && artSafeBytes != null) {
            System.arraycopy(artSafeBytes, 0, buf, pos, artLen)
        }

        return buf
    }

    fun parseMediaMetadata(buffer: ByteArray, offset: Int, length: Int): MediaMetadataPayload? {
        // Minimum: 1 + 8 + 2*4 = 17 bytes (all strings empty)
        if (length < 17 || offset + length > buffer.size) return null
        var pos = offset

        val isPlaying = (buffer[pos++].toInt() and 0x01) != 0

        // 8-byte sequence
        var seq = 0L
        for (i in 0 until 8) { seq = (seq shl 8) or (buffer[pos++].toLong() and 0xFF) }

        fun readField(): String? {
            if (pos + 2 > offset + length) return null
            val len = readUInt16BE(buffer, pos); pos += 2
            if (pos + len > offset + length) return null
            val s = String(buffer, pos, len, Charsets.UTF_8); pos += len
            return s
        }

        val pkg    = readField() ?: return null
        val title  = readField() ?: return null
        val artist = readField() ?: return null
        val album  = readField() ?: return null

        var artBytes: ByteArray? = null
        if (pos + 2 <= offset + length) {
            val artLen = readUInt16BE(buffer, pos); pos += 2
            if (artLen > 0 && pos + artLen <= offset + length) {
                artBytes = ByteArray(artLen)
                System.arraycopy(buffer, pos, artBytes, 0, artLen)
                pos += artLen
            }
        }

        return MediaMetadataPayload(
            isPlaying = isPlaying,
            title = title,
            artist = artist,
            album = album,
            mediaStateSequence = seq,
            packageName = pkg,
            artworkBytes = artBytes
        )
    }
}

