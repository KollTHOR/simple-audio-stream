package com.example.audiostreamer

/**
 * XOR Forward Error Correction (FEC) engine.
 * Generates one parity packet for every K packets (K = 4 by default) to allow
 * lossless reconstruction of any single lost packet without latency overhead.
 */
class FecEncoder(val blockSize: Int = AudioConfig.FEC_BLOCK_SIZE) {
    private val parityPayload = ByteArray(AudioConfig.MAX_PACKET_SIZE)
    private val parityPacket = ByteArray(AudioConfig.HEADER_SIZE + AudioConfig.MAX_PACKET_SIZE)
    private var blockCount = 0
    private var baseSeq = 0
    private var maxPayloadLen = 0

    fun reset() {
        blockCount = 0
        maxPayloadLen = 0
    }

    /**
     * Feeds an outgoing audio packet into the FEC block accumulator.
     * Returns a complete ByteArray of the parity packet when block is full (K=4), or null otherwise.
     */
    fun encode(
        seq: Int,
        payload: ByteArray,
        offset: Int,
        len: Int,
        flags: Byte
    ): ByteArray? {
        if (len <= 0 || len > AudioConfig.MAX_PACKET_SIZE) {
            return null
        }

        if (blockCount == 0) {
            baseSeq = seq
            maxPayloadLen = len
            System.arraycopy(payload, offset, parityPayload, 0, len)
            blockCount = 1
            return null
        }

        if (len > maxPayloadLen) {
            parityPayload.fill(0, maxPayloadLen, len)
            maxPayloadLen = len
        }
        for (i in 0 until len) {
            parityPayload[i] = (parityPayload[i].toInt() xor payload[offset + i].toInt()).toByte()
        }
        blockCount++

        if (blockCount == blockSize) {
            // Header: Magic(2) + BaseSeq(2) + BlockSize(1) + Flags(1) + PayloadLen(2)
            parityPacket[0] = (AudioConfig.MAGIC_HEADER.toInt() shr 8).toByte()
            parityPacket[1] = (AudioConfig.MAGIC_HEADER.toInt() and 0xFF).toByte()
            parityPacket[2] = (baseSeq shr 8).toByte()
            parityPacket[3] = (baseSeq and 0xFF).toByte()
            parityPacket[4] = blockSize.toByte()
            parityPacket[5] = (flags.toInt() or AudioConfig.FLAG_FEC_PARITY.toInt()).toByte()
            parityPacket[6] = (maxPayloadLen shr 8).toByte()
            parityPacket[7] = (maxPayloadLen and 0xFF).toByte()

            System.arraycopy(parityPayload, 0, parityPacket, AudioConfig.HEADER_SIZE, maxPayloadLen)

            val totalLen = AudioConfig.HEADER_SIZE + maxPayloadLen
            val result = ByteArray(totalLen)
            System.arraycopy(parityPacket, 0, result, 0, totalLen)

            blockCount = 0
            maxPayloadLen = 0
            return result
        }

        return null
    }
}

/**
 * XOR FEC Decoder.
 * Inspects incoming parity packets and attempts to recover single missing packets in the block
 * before the playback cursor reaches them.
 */
class FecDecoder(private val jitterBuffer: JitterBuffer) {
    private val tempPacket = ByteArray(AudioConfig.MAX_PACKET_SIZE)
    private val recoveredPayload = ByteArray(AudioConfig.MAX_PACKET_SIZE)

    /**
     * Processes an incoming FEC parity packet.
     * Returns true if a lost packet was successfully reconstructed and inserted into JitterBuffer.
     */
    fun decode(
        baseSeq: Int,
        blockSize: Int,
        parityPayload: ByteArray,
        parityOffset: Int,
        parityLen: Int
    ): Boolean {
        if (parityLen <= 0 || parityLen > AudioConfig.MAX_PACKET_SIZE) return false
        if (blockSize !in 2..16) return false

        var missingSeq = -1
        var missingCount = 0
        var maxLen = parityLen

        for (i in 0 until blockSize) {
            val seq = (baseSeq + i) and 0xFFFF
            if (jitterBuffer.hasPacket(seq)) {
                val len = jitterBuffer.getPacketLength(seq)
                if (len > maxLen) maxLen = len
            } else {
                missingSeq = seq
                missingCount++
            }
        }

        // Exactly 1 dropped packet can be reconstructed losslessly
        if (missingCount != 1 || missingSeq == -1) {
            return false
        }

        // Check if missingSeq has already passed read cursor
        if (jitterBuffer.isPacketPastPlayback(missingSeq)) {
            return false
        }

        // Initialize recovered buffer with parity bytes
        System.arraycopy(parityPayload, parityOffset, recoveredPayload, 0, parityLen)
        if (maxLen > parityLen) {
            recoveredPayload.fill(0, parityLen, maxLen)
        }

        // XOR with all other packets in the block
        for (i in 0 until blockSize) {
            val seq = (baseSeq + i) and 0xFFFF
            if (seq == missingSeq) continue

            val len = jitterBuffer.copyPacketData(seq, tempPacket)
            if (len <= 0) {
                return false
            }
            val xorLen = minOf(len, maxLen)
            for (b in 0 until xorLen) {
                recoveredPayload[b] = (recoveredPayload[b].toInt() xor tempPacket[b].toInt()).toByte()
            }
        }

        // Write recovered packet into missing slot in JitterBuffer
        return jitterBuffer.putRecoveredPacket(missingSeq, recoveredPayload, 0, maxLen)
    }
}
