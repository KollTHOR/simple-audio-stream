package com.example.audiostreamer

import java.util.zip.CRC32

/**
 * High-definition Audio Transport (HAT) XOR Forward Error Correction (FEC) Protocol.
 *
 * Audit and Assumptions:
 * 1. Prior Design Limitations:
 *    - Assumed uniform payload length across all audio packets in a block. For compressed variable-bitrate
 *      streams (Opus, AAC, ASLC), packets vary in byte length. The prior design zero-padded to maxPayloadLen
 *      and recovered packets to maxPayloadLen, introducing trailing zero garbage into decoders and causing
 *      corruption or decode failures.
 *    - Relied solely on jitterBuffer.hasPacket(seq) without verifying that packet length or content matched
 *      the current stream block, allowing stale sequence numbers or corrupt slots to poison XOR arithmetic.
 *    - Lacked payload integrity verification: if two packets were lost in a block (M >= 2) or bit errors occurred,
 *      XOR produced arbitrary corrupt noise which was silently treated as valid audio.
 *
 * 2. Hardened Protocol Design:
 *    - Explicit typed header for FEC parity payloads:
 *      Offset  Size  Field          Description
 *      -------------------------------------------------------------------------
 *      0        1    fecVersion     FEC Protocol version (currently 1)
 *      1        1    blockSize      FEC block size K (2..16)
 *      2..3     2    reserved       Reserved (0x0000)
 *      4..      2*K  lengths        Array of K 16-bit Big-Endian packet lengths
 *      4+2*K..  4*K  crc32s         Array of K 32-bit Big-Endian CRC32 checksums
 *      4+6*K..  var  parityData     XOR parity bytes (max of lengths in block)
 *
 *    - Guarantees:
 *      1. Exact original length L_m is recovered for any single lost packet.
 *      2. Pre-verification: existing packets in JitterBuffer are validated against their recorded CRC32.
 *      3. Post-verification: recovered payload is validated against recorded CRC32 before insertion.
 *      4. If M >= 2 packets are missing or corrupted, recovery safely aborts without modifying the buffer.
 *      5. Duplicate parity packets or already-present packets cleanly result in no-op.
 */
object FecProtocol {
    const val VERSION: Byte = 1
    const val MIN_BLOCK_SIZE = 2
    const val MAX_BLOCK_SIZE = 16
    const val HEADER_SIZE = 4 // 1 byte version, 1 byte blockSize, 2 bytes reserved

    fun metadataSize(blockSize: Int): Int = HEADER_SIZE + (6 * blockSize)

    fun readUInt16BE(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or
                (data[offset + 1].toInt() and 0xFF)
    }

    fun writeUInt16BE(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

    fun readUInt32BE(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF) shl 24) or
                ((data[offset + 1].toLong() and 0xFF) shl 16) or
                ((data[offset + 2].toLong() and 0xFF) shl 8) or
                (data[offset + 3].toLong() and 0xFF)
    }

    fun writeUInt32BE(data: ByteArray, offset: Int, value: Long) {
        data[offset] = ((value ushr 24) and 0xFF).toByte()
        data[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }
}

/**
 * Hardened XOR FEC Encoder.
 * Accumulates K audio packets, tracks exact individual payload lengths and CRC32 checksums,
 * and generates a single typed parity packet with embedded framing metadata.
 */
class FecEncoder(val blockSize: Int = AudioConfig.FEC_BLOCK_SIZE) {
    init {
        require(blockSize in FecProtocol.MIN_BLOCK_SIZE..FecProtocol.MAX_BLOCK_SIZE) {
            "FEC block size must be between ${FecProtocol.MIN_BLOCK_SIZE} and ${FecProtocol.MAX_BLOCK_SIZE}"
        }
    }

    private val parityPayload = ByteArray(AudioConfig.MAX_PACKET_SIZE)
    private val parityPacket = ByteArray(AudioConfig.HEADER_SIZE + AudioConfig.MAX_PACKET_SIZE)
    private val packetLengths = IntArray(FecProtocol.MAX_BLOCK_SIZE)
    private val packetCrcs = LongArray(FecProtocol.MAX_BLOCK_SIZE)
    private val crc = CRC32()

    private var blockCount = 0
    private var baseSeq = 0
    private var baseTimestamp = 0L
    private var maxPayloadLen = 0

    fun reset() {
        blockCount = 0
        baseSeq = 0
        baseTimestamp = 0L
        maxPayloadLen = 0
    }

    /**
     * Feeds an outgoing audio packet into the FEC block accumulator.
     * Returns a complete ByteArray of the parity packet when block is full (K packets), or null otherwise.
     */
    fun encode(
        seq: Int,
        timestamp: Long = 0L,
        payload: ByteArray,
        offset: Int,
        len: Int,
        codec: Byte = HatPacket.CODEC_RAW_PCM,
        profile: Byte = HatPacket.PROFILE_MUSIC,
        sampleRateCode: Byte = HatPacket.RATE_48000,
        bitDepth: Byte = HatPacket.BIT_DEPTH_24,
        volume: Int = 100
    ): ByteArray? {
        val metaSize = FecProtocol.metadataSize(blockSize)
        val maxAllowedPayload = AudioConfig.MAX_PACKET_SIZE - metaSize
        if (len <= 0 || len > maxAllowedPayload) {
            return null
        }

        crc.reset()
        crc.update(payload, offset, len)
        val packetCrc = crc.value

        if (blockCount == 0) {
            baseSeq = seq
            baseTimestamp = timestamp
            maxPayloadLen = len
            packetLengths[0] = len
            packetCrcs[0] = packetCrc
            System.arraycopy(payload, offset, parityPayload, 0, len)
            blockCount = 1
            return null
        }

        packetLengths[blockCount] = len
        packetCrcs[blockCount] = packetCrc

        if (len > maxPayloadLen) {
            parityPayload.fill(0, maxPayloadLen, len)
            maxPayloadLen = len
        }
        for (i in 0 until len) {
            parityPayload[i] = (parityPayload[i].toInt() xor payload[offset + i].toInt()).toByte()
        }
        blockCount++

        if (blockCount == blockSize) {
            val totalParityPayloadLen = metaSize + maxPayloadLen

            HatPacket.writeHeader(
                buffer = parityPacket,
                offset = 0,
                header = HatPacket.Header(
                    packetType = HatPacket.TYPE_FEC_PARITY,
                    sequenceNumber = baseSeq,
                    payloadLength = totalParityPayloadLen,
                    timestamp = baseTimestamp,
                    codec = codec,
                    profile = profile,
                    sampleRateCode = sampleRateCode,
                    bitDepth = bitDepth,
                    channels = HatPacket.CHANNELS_STEREO,
                    volumeOrCaps = volume.coerceIn(0, 100).toByte(),
                    fecBlockSize = blockSize.toByte()
                )
            )

            val metaOffset = HatPacket.HEADER_SIZE
            parityPacket[metaOffset] = FecProtocol.VERSION
            parityPacket[metaOffset + 1] = blockSize.toByte()
            parityPacket[metaOffset + 2] = 0
            parityPacket[metaOffset + 3] = 0

            for (i in 0 until blockSize) {
                FecProtocol.writeUInt16BE(parityPacket, metaOffset + FecProtocol.HEADER_SIZE + (2 * i), packetLengths[i])
                FecProtocol.writeUInt32BE(parityPacket, metaOffset + FecProtocol.HEADER_SIZE + (2 * blockSize) + (4 * i), packetCrcs[i])
            }

            System.arraycopy(parityPayload, 0, parityPacket, metaOffset + metaSize, maxPayloadLen)

            val totalLen = HatPacket.HEADER_SIZE + totalParityPayloadLen
            val result = ByteArray(totalLen)
            System.arraycopy(parityPacket, 0, result, 0, totalLen)

            reset()
            return result
        }

        return null
    }
}

/**
 * Hardened XOR FEC Decoder.
 * Inspects incoming parity packets, validates metadata and checksums, and attempts to
 * recover single missing packets in the block before the playback cursor reaches them.
 */
class FecDecoder(private val jitterBuffer: JitterBuffer) {
    private val tempPacket = ByteArray(AudioConfig.MAX_PACKET_SIZE)
    private val recoveredPayload = ByteArray(AudioConfig.MAX_PACKET_SIZE)
    private val decodedLengths = IntArray(FecProtocol.MAX_BLOCK_SIZE)
    private val decodedCrcs = LongArray(FecProtocol.MAX_BLOCK_SIZE)
    private val crc = CRC32()

    /**
     * Processes an incoming FEC parity packet.
     * Returns true if a lost packet was successfully reconstructed, verified, and inserted into JitterBuffer.
     */
    fun decode(
        baseSeq: Int,
        baseTimestamp: Long = 0L,
        blockSize: Int,
        parityPayload: ByteArray,
        parityOffset: Int,
        parityLen: Int
    ): Boolean {
        if (blockSize !in FecProtocol.MIN_BLOCK_SIZE..FecProtocol.MAX_BLOCK_SIZE) return false
        val metaSize = FecProtocol.metadataSize(blockSize)
        if (parityLen < metaSize || parityLen > AudioConfig.MAX_PACKET_SIZE) return false
        if (parityOffset < 0 || parityOffset + parityLen > parityPayload.size) return false

        val version = parityPayload[parityOffset]
        if (version != FecProtocol.VERSION) return false

        val embeddedBlockSize = parityPayload[parityOffset + 1].toInt() and 0xFF
        if (embeddedBlockSize != blockSize) return false

        var maxLen = 0
        val maxAllowedPayload = AudioConfig.MAX_PACKET_SIZE - metaSize
        for (i in 0 until blockSize) {
            val len = FecProtocol.readUInt16BE(parityPayload, parityOffset + FecProtocol.HEADER_SIZE + (2 * i))
            if (len <= 0 || len > maxAllowedPayload) return false
            decodedLengths[i] = len
            if (len > maxLen) maxLen = len
        }

        if (parityLen != metaSize + maxLen) return false

        for (i in 0 until blockSize) {
            decodedCrcs[i] = FecProtocol.readUInt32BE(
                parityPayload,
                parityOffset + FecProtocol.HEADER_SIZE + (2 * blockSize) + (4 * i)
            )
        }

        var missingSeq = -1
        var missingIndex = -1
        var missingCount = 0
        var hasCorruptedSlot = false

        for (i in 0 until blockSize) {
            val seq = (baseSeq + i) and 0xFFFF
            val expectedLen = decodedLengths[i]
            val expectedCrc = decodedCrcs[i]

            if (jitterBuffer.hasPacket(seq)) {
                val actualLen = jitterBuffer.getPacketLength(seq)
                if (actualLen == expectedLen) {
                    val copied = jitterBuffer.copyPacketData(seq, tempPacket)
                    if (copied == expectedLen) {
                        crc.reset()
                        crc.update(tempPacket, 0, expectedLen)
                        if (crc.value == expectedCrc) {
                            // Valid packet already present in jitter buffer
                            continue
                        }
                    }
                }
                // Packet in slot has length mismatch or CRC failure (stale or corrupted)
                hasCorruptedSlot = true
            }

            missingSeq = seq
            missingIndex = i
            missingCount++
        }

        // Exactly 1 dropped packet can be reconstructed losslessly
        if (missingCount != 1 || missingSeq == -1) {
            return false
        }

        // Check if missingSeq has already passed read cursor
        if (jitterBuffer.isPacketPastPlayback(missingSeq)) {
            return false
        }

        val targetLen = decodedLengths[missingIndex]
        val targetCrc = decodedCrcs[missingIndex]
        val parityDataOffset = parityOffset + metaSize

        // Initialize recovered buffer with parity bytes
        System.arraycopy(parityPayload, parityDataOffset, recoveredPayload, 0, maxLen)

        // XOR with all other packets in the block
        for (i in 0 until blockSize) {
            if (i == missingIndex) continue
            val seq = (baseSeq + i) and 0xFFFF
            val len = decodedLengths[i]

            val copied = jitterBuffer.copyPacketData(seq, tempPacket)
            if (copied != len) {
                return false
            }
            for (b in 0 until len) {
                recoveredPayload[b] = (recoveredPayload[b].toInt() xor tempPacket[b].toInt()).toByte()
            }
        }

        // Strict bit-exact CRC32 verification: never accept incorrectly reconstructed payload
        crc.reset()
        crc.update(recoveredPayload, 0, targetLen)
        if (crc.value != targetCrc) {
            return false
        }

        // Determine timestamp for the recovered packet
        val recoveredTimestamp: Long = if (missingIndex == 0 && baseTimestamp >= 0L) {
            baseTimestamp
        } else {
            val existingBlockPacket = jitterBuffer.findBlockTimestamp(baseSeq, blockSize, missingSeq)
            if (existingBlockPacket != null) {
                val (existingSeq, existingTs, existingLen) = existingBlockPacket
                val deltaPackets = (missingSeq - existingSeq)
                val frames = jitterBuffer.calculateFramesForPayload(existingLen)
                existingTs + (deltaPackets * frames)
            } else if (baseTimestamp >= 0L) {
                val frames = jitterBuffer.calculateFramesForPayload(targetLen)
                baseTimestamp + (missingIndex * frames)
            } else {
                0L
            }
        }

        // Write recovered packet into missing slot in JitterBuffer with exact target length
        return jitterBuffer.putRecoveredPacket(missingSeq, recoveredTimestamp, recoveredPayload, 0, targetLen, overwrite = hasCorruptedSlot)
    }
}
