package com.example.audiostreamer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetransmitProtocolTest {

    @Test
    fun nackRoundTripsThroughWireFormat() {
        val seqs = listOf(10, 11, 4096, 65535)
        val payload = RetransmitProtocol.encodeMissingSeqs(seqs)
        val decoded = RetransmitProtocol.decodeMissingSeqs(payload, 0, payload.size)
        assertEquals(seqs.sorted(), decoded)
    }

    @Test
    fun emptyListEncodesToCountOnly() {
        val payload = RetransmitProtocol.encodeMissingSeqs(emptyList())
        assertEquals(2, payload.size)
        assertEquals(emptyList<Int>(), RetransmitProtocol.decodeMissingSeqs(payload, 0, payload.size))
    }

    @Test
    fun duplicatesAndOrderingAreNormalized() {
        val payload = RetransmitProtocol.encodeMissingSeqs(listOf(5, 3, 5, 3, 9, 9))
        assertEquals(listOf(3, 5, 9), RetransmitProtocol.decodeMissingSeqs(payload, 0, payload.size))
    }

    @Test
    fun oversizedRequestIsTruncatedToCap() {
        val many = (0..(HatPacket.RETX_MAX_SEQS_PER_REQUEST + 50)).toList()
        val payload = RetransmitProtocol.encodeMissingSeqs(many)
        val decoded = RetransmitProtocol.decodeMissingSeqs(payload, 0, payload.size)!!
        assertEquals(HatPacket.RETX_MAX_SEQS_PER_REQUEST, decoded.size)
    }

    @Test
    fun seqsWrapThroughUInt16() {
        val payload = RetransmitProtocol.encodeMissingSeqs(listOf(65536, -1))
        // 65536 & 0xFFFF == 0 and -1 & 0xFFFF == 65535
        assertEquals(listOf(0, 65535), RetransmitProtocol.decodeMissingSeqs(payload, 0, payload.size))
    }

    @Test
    fun malformedPayloadsAreRejected() {
        // Declared count disagrees with actual length.
        val bad = ByteArray(6)
        HatPacket.writeUInt16BE(bad, 0, 10) // claims 10 seqs in a 4-byte body
        assertNull(RetransmitProtocol.decodeMissingSeqs(bad, 0, bad.size))
        // Odd length cannot encode whole u16 entries.
        assertNull(RetransmitProtocol.decodeMissingSeqs(ByteArray(5), 0, 5))
        // Truncated header.
        assertNull(RetransmitProtocol.decodeMissingSeqs(ByteArray(1), 0, 1))
    }

    @Test
    fun decodeRespectsOffset() {
        val payload = RetransmitProtocol.encodeMissingSeqs(listOf(7, 8))
        val framed = ByteArray(4 + payload.size)
        System.arraycopy(payload, 0, framed, 4, payload.size)
        assertEquals(listOf(7, 8), RetransmitProtocol.decodeMissingSeqs(framed, 4, payload.size))
    }
}

class RetransmitBufferTest {

    @Test
    fun storesAndRetrievesExactDatagram() {
        val buf = RetransmitBuffer(capacity = 8)
        val packet = ByteArray(100)
        for (i in packet.indices) packet[i] = (i and 0xFF).toByte()
        assertTrue(buf.record(seq = 42, generation = 1L, data = packet, offset = 0, len = packet.size))

        val dest = ByteArray(packet.size)
        val len = buf.lookupInto(seq = 42, requireGeneration = 1L, dest = dest)
        assertEquals(packet.size, len)
        assertArrayEquals(packet, dest)
    }

    @Test
    fun generationMismatchReturnsNothing() {
        val buf = RetransmitBuffer(capacity = 8)
        buf.record(5, generation = 2L, ByteArray(8) { 1 }, 0, 8)
        assertEquals(0, buf.lookupInto(5, requireGeneration = 3L, dest = ByteArray(8)))
        // Passing null disables the generation check.
        assertEquals(8, buf.lookupInto(5, requireGeneration = null, dest = ByteArray(8)))
    }

    @Test
    fun ringAliasIsDetectedByRecordedSeq() {
        // capacity 8 => seq 3 and seq 11 map to the same slot; after overwriting 11, lookup(3) misses.
        val buf = RetransmitBuffer(capacity = 8)
        buf.record(3, 1L, ByteArray(4) { 9 }, 0, 4)
        buf.record(11, 1L, ByteArray(4) { 7 }, 0, 4)
        assertEquals(0, buf.lookupInto(3, requireGeneration = 1L, dest = ByteArray(4)))
        assertEquals(4, buf.lookupInto(11, requireGeneration = 1L, dest = ByteArray(4)))
    }

    @Test
    fun sequenceWrapAroundMapsConsistently() {
        val buf = RetransmitBuffer(capacity = 8)
        buf.record(65535, 1L, ByteArray(6) { 3 }, 0, 6)
        buf.record(0, 1L, ByteArray(6) { 5 }, 0, 6)
        assertEquals(6, buf.lookupInto(65535, requireGeneration = 1L, dest = ByteArray(6)))
        assertEquals(6, buf.lookupInto(0, requireGeneration = 1L, dest = ByteArray(6)))
    }

    @Test
    fun unknownSequenceLookupIsZero() {
        val buf = RetransmitBuffer(capacity = 8)
        assertEquals(0, buf.lookupInto(99, requireGeneration = 1L, dest = ByteArray(4)))
    }

    @Test
    fun oversizedRecordIsSkippedNotTruncated() {
        val buf = RetransmitBuffer(capacity = 4)
        val tooBig = ByteArray(AudioConfig.MAX_PACKET_SIZE + HatPacket.HEADER_SIZE + 1)
        assertFalse(buf.record(1, 1L, tooBig, 0, tooBig.size))
        assertEquals(0, buf.lookupInto(1, requireGeneration = 1L, dest = ByteArray(1)))
    }

    @Test
    fun clearDropsAllEntries() {
        val buf = RetransmitBuffer(capacity = 8)
        buf.record(1, 1L, ByteArray(4) { 2 }, 0, 4)
        buf.clear()
        assertEquals(0, buf.lookupInto(1, requireGeneration = 1L, dest = ByteArray(4)))
    }

    @Test
    fun nonPowerOfTwoCapacityRejected() {
        var threw = false
        try { RetransmitBuffer(capacity = 7) } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }
}

class NackTrackerTest {

    @Test
    fun receivedPacketClosesItsGap() {
        val tracker = NackTracker()
        tracker.onGapsDetected(listOf(10, 11, 12), nowMs = 100)
        assertEquals(3, tracker.pendingCount)
        tracker.onReceived(11, nowMs = 110)
        assertEquals(2, tracker.pendingCount)
    }

    @Test
    fun dueForNackListsAscendingAndMarksAttempted() {
        val tracker = NackTracker()
        tracker.onGapsDetected(listOf(12, 10, 11), nowMs = 100)
        val due = tracker.dueForNack(nowMs = 101)
        assertEquals(listOf(10, 11, 12), due)
    }

    @Test
    fun samePacketIsNotReRequestedWithinBackoff() {
        val tracker = NackTracker(resendBackoffMs = 24)
        tracker.onGapsDetected(listOf(50), nowMs = 100)
        assertEquals(listOf(50), tracker.dueForNack(101))       // first request
        assertEquals(emptyList<Int>(), tracker.dueForNack(110)) // within backoff
        assertEquals(listOf(50), tracker.dueForNack(130))       // 24ms+ later, retry
    }

    @Test
    fun attemptsAreCappedThenDropped() {
        val tracker = NackTracker(maxAttempts = 2, resendBackoffMs = 10, maxAgeMs = 10_000)
        tracker.onGapsDetected(listOf(7), nowMs = 0)
        assertEquals(listOf(7), tracker.dueForNack(0))
        assertEquals(listOf(7), tracker.dueForNack(10))
        // third would exceed maxAttempts → dropped, nothing pending.
        assertEquals(emptyList<Int>(), tracker.dueForNack(20))
        assertEquals(0, tracker.pendingCount)
    }

    @Test
    fun expiredEntriesArePruned() {
        val tracker = NackTracker(resendBackoffMs = 5, maxAgeMs = 50)
        tracker.onGapsDetected(listOf(3), nowMs = 0)
        assertEquals(1, tracker.pendingCount)
        // A no-op call far past maxAge prunes on entry.
        tracker.onGapsDetected(listOf(4), nowMs = 100)
        assertEquals(1, tracker.pendingCount) // entry 3 pruned; entry 4 just added
        assertTrue(tracker.dueForNack(100).contains(4))
        assertFalse(tracker.dueForNack(100).contains(3))
    }

    @Test
    fun onReceivedAdvancesHighWaterMark() {
        val tracker = NackTracker()
        tracker.onReceived(5, nowMs = 0)
        assertEquals(5, tracker.highestSeen())
        // A later packet advances it.
        tracker.onReceived(100, nowMs = 1)
        assertEquals(100, tracker.highestSeen())
    }

    @Test
    fun capacityBoundsPendingEntries() {
        val tracker = NackTracker(capacity = 4)
        tracker.onGapsDetected((0..99).toList(), nowMs = 0)
        assertTrue(tracker.pendingCount <= 4)
    }

    @Test
    fun dueForNackRespectsPerRequestCap() {
        val tracker = NackTracker(capacity = 256)
        tracker.onGapsDetected((0..200).toList(), nowMs = 0)
        val due = tracker.dueForNack(1)
        assertEquals(HatPacket.RETX_MAX_SEQS_PER_REQUEST, due.size)
        assertNotNull(tracker.highestSeen())
    }

    @Test
    fun noteSilenceAdvancesHighWaterWithoutCreatingGaps() {
        val tracker = NackTracker()
        tracker.onReceived(10, nowMs = 0)
        // A run of silence heartbeats at 11..15 (they carry sequence numbers).
        for (s in 11..15) tracker.noteSilence(s, nowMs = 1)
        // Real audio resumes at 16: must NOT register 11..15 as lost.
        tracker.onReceived(16, nowMs = 2)
        assertEquals(emptyList<Int>(), tracker.dueForNack(2))
    }

    @Test
    fun noteSilenceClosesAPendingGapAtThatSeq() {
        val tracker = NackTracker()
        tracker.onReceived(10, nowMs = 0)
        tracker.onReceived(12, nowMs = 0) // gap at 11
        assertEquals(listOf(11), tracker.dueForNack(1))
        tracker.noteSilence(11, nowMs = 2) // that seq number was consumed by a heartbeat
        assertEquals(0, tracker.pendingCount)
    }

    @Test
    fun noteSilenceNeverMovesHighWaterBackwards() {
        val tracker = NackTracker()
        tracker.onReceived(100, nowMs = 0)
        tracker.noteSilence(50, nowMs = 1) // stale/out-of-order heartbeat
        assertEquals(100, tracker.highestSeen())
    }

    @Test
    fun clearResetsState() {
        val tracker = NackTracker()
        tracker.onReceived(42, nowMs = 0)
        tracker.onGapsDetected(listOf(1, 2, 3), nowMs = 0)
        tracker.clear()
        assertEquals(0, tracker.pendingCount)
        assertEquals(-1, tracker.highestSeen())
    }

    @Test
    fun onReceivedDetectsGapsFromAheadPacket() {
        val tracker = NackTracker()
        tracker.onReceived(10, nowMs = 0)
        // 14 arrives, leaping over 11,12,13 → those become pending gaps.
        tracker.onReceived(14, nowMs = 1)
        assertEquals(listOf(11, 12, 13), tracker.dueForNack(1))
    }

    @Test
    fun outOfOrderArrivalDoesNotCreateGaps() {
        val tracker = NackTracker()
        tracker.onReceived(10, nowMs = 0)
        tracker.onReceived(12, nowMs = 0)   // gap at 11
        tracker.onReceived(11, nowMs = 1)   // arrives late → closes gap, 11 never NACKed
        assertEquals(emptyList<Int>(), tracker.dueForNack(1))
    }

    @Test
    fun duplicateArrivalIsNotTreatedAsGoingBackwards() {
        val tracker = NackTracker()
        tracker.onReceived(50, nowMs = 0)
        tracker.onReceived(50, nowMs = 1) // duplicate: no new gaps, high-water unchanged
        tracker.onReceived(51, nowMs = 2)
        assertEquals(emptyList<Int>(), tracker.dueForNack(2))
    }

    @Test
    fun hugeLeapIsNotNackedEntirely() {
        // A reconnection jumps the sequence far ahead; only the bounded lookback window is requested.
        val tracker = NackTracker(maxGapLookback = 8)
        tracker.onReceived(0, nowMs = 0)
        tracker.onReceived(1000, nowMs = 1)
        val due = tracker.dueForNack(1)
        assertEquals(8, due.size)
        assertEquals(992, due.first()) // 1000-8 .. 999
    }

    @Test
    fun gapFromReceiptRespectsBackoffLikeExplicitGap() {
        val tracker = NackTracker(resendBackoffMs = 24)
        tracker.onReceived(10, nowMs = 0)
        tracker.onReceived(12, nowMs = 0) // gap at 11
        assertEquals(listOf(11), tracker.dueForNack(1))
        assertEquals(emptyList<Int>(), tracker.dueForNack(10)) // within backoff
        assertEquals(listOf(11), tracker.dueForNack(30))
    }
}
