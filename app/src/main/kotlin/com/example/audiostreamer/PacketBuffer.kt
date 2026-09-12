package com.example.audiostreamer

/**
 * Ring buffer storage for audio packets in the jitter buffer.
 *
 * Responsibilities:
 * - Direct byte array buffer management for pre-allocated packet slots.
 * - Storing and indexing packets by their 16-bit sequence number modulo maxSlots.
 * - Tracking per-slot metadata: lengths, sequence numbers, timestamps, and occupancy.
 * - Managing available packet count for buffering watermark decisions.
 */
class PacketBuffer(val maxSlots: Int = AudioConfig.MUSIC_JITTER_BUFFER_SLOTS) {
    init {
        require((maxSlots and (maxSlots - 1)) == 0) { "maxSlots must be a power of 2, got $maxSlots" }
    }

    private val buffer = Array(maxSlots) { ByteArray(AudioConfig.MAX_PACKET_SIZE) }
    private val packetLengths = IntArray(maxSlots) { AudioConfig.PACKET_SIZE_16BIT }
    private val isSlotFilled = BooleanArray(maxSlots)
    private val slotSeq = IntArray(maxSlots) { -1 }
    private val slotTimestamp = LongArray(maxSlots) { -1L }

    var availableCount: Int = 0
        private set

    fun getSlot(sequence: Int): Int = sequence and (maxSlots - 1)

    fun isSlotOccupied(slot: Int): Boolean = isSlotFilled[slot]

    fun isSlotOccupiedBy(slot: Int, sequence: Int): Boolean = isSlotFilled[slot] && slotSeq[slot] == sequence

    fun getSlotSequence(slot: Int): Int = slotSeq[slot]

    fun getSlotTimestamp(slot: Int): Long = slotTimestamp[slot]

    fun getSlotPacketLength(slot: Int): Int = packetLengths[slot]

    fun hasPacket(sequence: Int): Boolean {
        val slot = getSlot(sequence)
        return isSlotFilled[slot] && slotSeq[slot] == sequence
    }

    fun getPacketLength(sequence: Int): Int {
        val slot = getSlot(sequence)
        return if (isSlotFilled[slot] && slotSeq[slot] == sequence) packetLengths[slot] else 0
    }

    fun getPacketTimestamp(sequence: Int): Long {
        val slot = getSlot(sequence)
        return if (isSlotFilled[slot] && slotSeq[slot] == sequence) slotTimestamp[slot] else -1L
    }

    fun insert(sequence: Int, timestamp: Long, data: ByteArray, offset: Int, length: Int) {
        val slot = getSlot(sequence)
        System.arraycopy(data, offset, buffer[slot], 0, length)
        packetLengths[slot] = length
        slotSeq[slot] = sequence
        slotTimestamp[slot] = timestamp
        if (!isSlotFilled[slot]) {
            isSlotFilled[slot] = true
            availableCount++
        }
    }

    fun readPacket(slot: Int, dest: ByteArray): Int {
        if (!isSlotFilled[slot]) return 0
        val len = packetLengths[slot]
        System.arraycopy(buffer[slot], 0, dest, 0, len)
        isSlotFilled[slot] = false
        slotSeq[slot] = -1
        slotTimestamp[slot] = -1L
        if (availableCount > 0) availableCount--
        return len
    }

    fun copyPacketData(sequence: Int, dest: ByteArray): Int {
        val slot = getSlot(sequence)
        if (isSlotFilled[slot] && slotSeq[slot] == sequence) {
            val len = packetLengths[slot]
            System.arraycopy(buffer[slot], 0, dest, 0, len)
            return len
        }
        return 0
    }

    fun clearSlot(slot: Int): Boolean {
        val wasFilled = isSlotFilled[slot]
        isSlotFilled[slot] = false
        slotSeq[slot] = -1
        slotTimestamp[slot] = -1L
        if (wasFilled && availableCount > 0) {
            availableCount--
        }
        return wasFilled
    }

    fun clear() {
        availableCount = 0
        for (i in 0 until maxSlots) {
            isSlotFilled[i] = false
            slotSeq[i] = -1
            slotTimestamp[i] = -1L
        }
    }
}
