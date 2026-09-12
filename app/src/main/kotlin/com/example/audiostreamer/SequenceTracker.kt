package com.example.audiostreamer

/**
 * 16-bit Sequence Number Tracker and Discontinuity Detector for HAT Transport.
 *
 * Sequence numbers are unsigned 16-bit integers in range [0, 65535] (modulo 65536).
 *
 * Responsibilities:
 * - 16-bit modular sequence arithmetic and signed distance calculation.
 * - Tracking expected read sequence and last received packet sequence.
 * - Generating sequential synthetic sequence numbers for local/unsequenced audio.
 * - Detecting sequence resets (leaps > half buffer capacity) and buffer overflow leaps.
 */
class SequenceTracker {
    var expectedReadSeq: Int = -1
        private set

    var lastPacketSeq: Int = -1
        private set

    private var syntheticSeq: Int = 0

    fun reset() {
        expectedReadSeq = -1
        lastPacketSeq = -1
        syntheticSeq = 0
    }

    fun initExpectedReadSeq(seq: Int) {
        expectedReadSeq = seq and 0xFFFF
    }

    fun setExpectedReadSeq(seq: Int) {
        expectedReadSeq = seq and 0xFFFF
    }

    fun advanceExpectedReadSeq() {
        if (expectedReadSeq != -1) {
            expectedReadSeq = (expectedReadSeq + 1) and 0xFFFF
        }
    }

    fun setLastPacketSeq(seq: Int) {
        lastPacketSeq = seq and 0xFFFF
    }

    fun nextSyntheticSeq(): Int {
        val s = syntheticSeq
        syntheticSeq = (syntheticSeq + 1) and 0xFFFF
        return s
    }

    companion object {
        /**
         * Computes signed sequence difference (s1 - s2) modulo 65536.
         * Returns a value in [-32768, 32767]:
         * - Positive value: s1 is ahead of s2.
         * - Negative value: s1 is behind s2.
         * - Zero: s1 equals s2.
         */
        fun diff(s1: Int, s2: Int): Int {
            val d = (s1 - s2) and 0xFFFF
            return if (d > 32767) d - 65536 else d
        }

        /**
         * Checks if the arrival of sequence represents a sequence reset or major jump.
         */
        fun isSequenceReset(sequence: Int, expectedReadSeq: Int, slotCount: Int): Boolean {
            if (expectedReadSeq == -1) return false
            return kotlin.math.abs(diff(sequence, expectedReadSeq)) > slotCount / 2
        }

        /**
         * Checks if the sequence is ahead of buffer capacity relative to read cursor.
         */
        fun isAheadOfCapacity(sequence: Int, expectedReadSeq: Int, maxSlots: Int): Boolean {
            if (expectedReadSeq == -1) return false
            return diff(sequence, expectedReadSeq) >= maxSlots
        }
    }
}
