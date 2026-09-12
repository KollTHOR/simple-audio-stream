package com.example.audiostreamer

/**
 * Secondary circular history buffer for FEC parity recovery.
 *
 * Responsibilities:
 * - Retains recently received and read packets in a dedicated circular cache.
 * - Allows FEC decoder to reconstruct lost packets even after earlier packets in the
 *   same FEC block have already been consumed and cleared from the main PacketBuffer.
 * - Provides historical length, timestamp, and payload queries by sequence number.
 */
class FecHistoryBuffer(val historySize: Int = 32) {
    init {
        require((historySize and (historySize - 1)) == 0) { "historySize must be a power of 2, got $historySize" }
    }

    private val historyBuffer = Array(historySize) { ByteArray(AudioConfig.MAX_PACKET_SIZE) }
    private val historyLengths = IntArray(historySize)
    private val historySeq = IntArray(historySize) { -1 }
    private val historyTimestamp = LongArray(historySize) { -1L }

    fun record(sequence: Int, timestamp: Long, data: ByteArray, offset: Int, length: Int) {
        val hSlot = sequence and (historySize - 1)
        System.arraycopy(data, offset, historyBuffer[hSlot], 0, length)
        historyLengths[hSlot] = length
        historySeq[hSlot] = sequence
        historyTimestamp[hSlot] = timestamp
    }

    fun hasPacket(sequence: Int): Boolean {
        val hSlot = sequence and (historySize - 1)
        return historySeq[hSlot] == sequence
    }

    fun getPacketLength(sequence: Int): Int {
        val hSlot = sequence and (historySize - 1)
        return if (historySeq[hSlot] == sequence) historyLengths[hSlot] else 0
    }

    fun getPacketTimestamp(sequence: Int): Long {
        val hSlot = sequence and (historySize - 1)
        return if (historySeq[hSlot] == sequence) historyTimestamp[hSlot] else -1L
    }

    fun copyPacketData(sequence: Int, dest: ByteArray): Int {
        val hSlot = sequence and (historySize - 1)
        if (historySeq[hSlot] == sequence) {
            val len = historyLengths[hSlot]
            System.arraycopy(historyBuffer[hSlot], 0, dest, 0, len)
            return len
        }
        return 0
    }

    fun clear() {
        for (i in 0 until historySize) {
            historySeq[i] = -1
            historyLengths[i] = 0
            historyTimestamp[i] = -1L
        }
    }
}
