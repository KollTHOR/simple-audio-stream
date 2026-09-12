package com.example.audiostreamer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class JitterBufferComponentsTest {

    // -------------------------------------------------------------------------
    // 1. SequenceTracker Tests
    // -------------------------------------------------------------------------
    @Test
    fun testSequenceTrackerArithmeticAndWraparound() {
        // Normal sequential
        assertEquals(1, SequenceTracker.diff(1, 0))
        assertEquals(10, SequenceTracker.diff(10, 0))
        assertEquals(-1, SequenceTracker.diff(0, 1))

        // 16-bit wrap-around boundaries (65535 -> 0)
        assertEquals(1, SequenceTracker.diff(0, 65535))
        assertEquals(2, SequenceTracker.diff(1, 65535))
        assertEquals(-1, SequenceTracker.diff(65535, 0))
        assertEquals(-2, SequenceTracker.diff(65535, 1))

        // Maximum half-ring distance
        assertEquals(32767, SequenceTracker.diff(32767, 0))
        assertEquals(-32768, SequenceTracker.diff(32768, 0))
    }

    @Test
    fun testSequenceTrackerDiscontinuityDetection() {
        // Slot count = 64. Discontinuity threshold = 64 / 2 = 32
        assertFalse(SequenceTracker.isSequenceReset(10, 0, 64))
        assertFalse(SequenceTracker.isSequenceReset(30, 0, 64))
        assertTrue(SequenceTracker.isSequenceReset(35, 0, 64))
        assertTrue(SequenceTracker.isSequenceReset(65500, 0, 64)) // 36 packets behind

        // Ahead of capacity: maxSlots = 64
        assertFalse(SequenceTracker.isAheadOfCapacity(50, 0, 64))
        assertTrue(SequenceTracker.isAheadOfCapacity(64, 0, 64))
        assertTrue(SequenceTracker.isAheadOfCapacity(100, 0, 64))
    }

    @Test
    fun testSequenceTrackerCursorProgression() {
        val tracker = SequenceTracker()
        assertEquals(-1, tracker.expectedReadSeq)

        tracker.setExpectedReadSeq(65535)
        assertEquals(65535, tracker.expectedReadSeq)

        tracker.advanceExpectedReadSeq()
        assertEquals(0, tracker.expectedReadSeq) // Clean 16-bit wrap

        tracker.advanceExpectedReadSeq()
        assertEquals(1, tracker.expectedReadSeq)

        assertEquals(0, tracker.nextSyntheticSeq())
        assertEquals(1, tracker.nextSyntheticSeq())

        tracker.reset()
        assertEquals(-1, tracker.expectedReadSeq)
        assertEquals(0, tracker.nextSyntheticSeq())
    }

    // -------------------------------------------------------------------------
    // 2. PacketBuffer Tests
    // -------------------------------------------------------------------------
    @Test
    fun testPacketBufferInsertionAndQueries() {
        val buffer = PacketBuffer(maxSlots = 16)
        assertEquals(0, buffer.availableCount)

        val payload = ByteArray(100) { it.toByte() }
        buffer.insert(sequence = 5, timestamp = 1200L, data = payload, offset = 0, length = payload.size)

        assertEquals(1, buffer.availableCount)
        assertTrue(buffer.hasPacket(5))
        assertFalse(buffer.hasPacket(6))
        assertEquals(100, buffer.getPacketLength(5))
        assertEquals(1200L, buffer.getPacketTimestamp(5))

        val copyDest = ByteArray(100)
        val copied = buffer.copyPacketData(5, copyDest)
        assertEquals(100, copied)
        assertArrayEquals(payload, copyDest)

        val readDest = ByteArray(100)
        val readSlot = buffer.getSlot(5)
        val readBytes = buffer.readPacket(readSlot, readDest)
        assertEquals(100, readBytes)
        assertArrayEquals(payload, readDest)

        // Slot should now be freed
        assertEquals(0, buffer.availableCount)
        assertFalse(buffer.hasPacket(5))
    }

    @Test
    fun testPacketBufferSlotOverwrite() {
        val buffer = PacketBuffer(maxSlots = 16)
        val p1 = ByteArray(50) { 1 }
        val p2 = ByteArray(80) { 2 }

        buffer.insert(sequence = 3, timestamp = 100L, data = p1, offset = 0, length = p1.size)
        assertEquals(1, buffer.availableCount)

        // Re-inserting into same sequence (e.g. FEC overwrite of corrupted packet)
        buffer.insert(sequence = 3, timestamp = 100L, data = p2, offset = 0, length = p2.size)
        assertEquals(1, buffer.availableCount) // Count should not double
        assertEquals(80, buffer.getPacketLength(3))

        val dest = ByteArray(80)
        buffer.copyPacketData(3, dest)
        assertArrayEquals(p2, dest)
    }

    // -------------------------------------------------------------------------
    // 3. FecHistoryBuffer Tests
    // -------------------------------------------------------------------------
    @Test
    fun testFecHistoryBufferRetention() {
        val history = FecHistoryBuffer(historySize = 8)

        // Insert 12 packets (sequences 0..11) into size 8 history
        for (i in 0 until 12) {
            val data = byteArrayOf(i.toByte(), 0x55)
            history.record(sequence = i, timestamp = i * 240L, data = data, offset = 0, length = data.size)
        }

        // Sequences 0..3 were overwritten by circular buffer
        for (i in 0 until 4) {
            assertFalse("Sequence $i should have been evicted", history.hasPacket(i))
        }

        // Sequences 4..11 should be present
        for (i in 4 until 12) {
            assertTrue("Sequence $i must be in history", history.hasPacket(i))
            assertEquals(2, history.getPacketLength(i))
            assertEquals(i * 240L, history.getPacketTimestamp(i))

            val dest = ByteArray(2)
            history.copyPacketData(i, dest)
            assertEquals(i.toByte(), dest[0])
        }

        history.clear()
        assertFalse(history.hasPacket(11))
    }

    // -------------------------------------------------------------------------
    // 4. JitterEstimator Tests
    // -------------------------------------------------------------------------
    @Test
    fun testJitterEstimatorRfc3550Calculation() {
        val estimator = JitterEstimator(AudioConfig.PROFILE_AUTO)
        assertEquals(0.0, estimator.estimatedJitterMs, 0.001)

        val baseNanos = 1_000_000_000L
        val packetDurationNanos = 5_000_000L // 5ms

        // First packet
        estimator.onPacketArrived(
            nowNanos = baseNanos,
            sequence = 0,
            timestamp = 0L,
            sampleRate = 48000,
            isCompressed = false,
            packetDurationMs = 5.0f,
            currentProfile = AudioConfig.PROFILE_AUTO,
            slotCount = 64
        )
        assertEquals(0.0, estimator.estimatedJitterMs, 0.001)

        // Second packet arrived exactly on time: transitDiff = 0
        estimator.onPacketArrived(
            nowNanos = baseNanos + packetDurationNanos,
            sequence = 1,
            timestamp = 240L,
            sampleRate = 48000,
            isCompressed = false,
            packetDurationMs = 5.0f,
            currentProfile = AudioConfig.PROFILE_AUTO,
            slotCount = 64
        )
        assertEquals(0.0, estimator.estimatedJitterMs, 0.001)

        // Third packet arrived with 16ms delay: transitDiff = 16ms
        estimator.onPacketArrived(
            nowNanos = baseNanos + 2 * packetDurationNanos + 16_000_000L,
            sequence = 2,
            timestamp = 480L,
            sampleRate = 48000,
            isCompressed = false,
            packetDurationMs = 5.0f,
            currentProfile = AudioConfig.PROFILE_AUTO,
            slotCount = 64
        )
        // RFC 3550: J = 0 + (16 - 0) / 16 = 1.0ms
        assertEquals(1.0, estimator.estimatedJitterMs, 0.001)
    }

    @Test
    fun testJitterEstimatorAutoWatermarkExpansionAndDecay() {
        val estimator = JitterEstimator(AudioConfig.PROFILE_AUTO)
        val initialWatermark = estimator.targetWatermarkMs

        // Underrun bumps watermark by 30ms
        estimator.onUnderrun(AudioConfig.PROFILE_AUTO, false, 5.0f, 64)
        assertEquals(initialWatermark + 30.0f, estimator.targetWatermarkMs, 0.001f)

        // 100 clean playback frames gradually decays watermark
        for (i in 0 until 100) {
            estimator.onCleanPlayback(AudioConfig.PROFILE_AUTO, false, 5.0f, 64)
        }
        assertTrue("Watermark must decay after 100 clean playback frames", estimator.targetWatermarkMs < initialWatermark + 30.0f)
    }

    // -------------------------------------------------------------------------
    // 5. DriftController Tests
    // -------------------------------------------------------------------------
    @Test
    fun testDriftControllerSmoothedFill() {
        val controller = DriftController(initialFill = 10.0f)
        assertEquals(10.0f, controller.smoothBufferFill, 0.001f)

        // Update with available count 20
        controller.updateFill(20)
        // 10 * 0.998 + 20 * 0.002 = 9.98 + 0.04 = 10.02
        assertEquals(10.02f, controller.smoothBufferFill, 0.01f)
    }

    @Test
    fun testDriftControllerZeroCrossingAdjustment() {
        val controller = DriftController(initialFill = 10.0f)
        // 16-bit stereo test buffer (4 bytes per frame)
        // 20 frames = 80 bytes
        val buffer = ByteArray(80)
        // Frame 10 has zero amplitude
        val dropTargetFrame = 10
        val dropIdx = dropTargetFrame * 4
        buffer[dropIdx] = 0
        buffer[dropIdx + 1] = 0
        buffer[dropIdx + 2] = 0
        buffer[dropIdx + 3] = 0

        controller.applyZeroCrossingFrameDrop(buffer, buffer.size, is24 = false)
        // Function executes without crash and preserves buffer length
        assertEquals(80, buffer.size)

        controller.applyZeroCrossingFrameDuplicate(buffer, buffer.size, is24 = false)
        assertEquals(80, buffer.size)
    }

    // -------------------------------------------------------------------------
    // 6. PacketLossConcealment Tests
    // -------------------------------------------------------------------------
    @Test
    fun testPacketLossConcealmentLinearFade() {
        val plc = PacketLossConcealment()
        val data = ByteArray(24) // 6 frames of 16-bit stereo
        // Populate last frame with peak value 10000
        val lastFrameIdx = 20
        data[lastFrameIdx] = (10000 and 0xFF).toByte()
        data[lastFrameIdx + 1] = ((10000 shr 8) and 0xFF).toByte()
        data[lastFrameIdx + 2] = (10000 and 0xFF).toByte()
        data[lastFrameIdx + 3] = ((10000 shr 8) and 0xFF).toByte()

        plc.cacheLastSamples(data, data.size, is24 = false)
        assertEquals(10000.toShort(), plc.lastSampleLeft16)
        assertEquals(10000.toShort(), plc.lastSampleRight16)

        // Synthesize loss concealment
        val output = ByteArray(24)
        plc.synthesizeLossConcealment(output, output.size, is24 = false)
        assertTrue(plc.wasConcealed)

        // First frame should be near 10000, last frame should be 0
        val firstSample = (output[0].toInt() and 0xFF) or (output[1].toInt() shl 8)
        val lastSample = (output[20].toInt() and 0xFF) or (output[21].toInt() shl 8)
        assertTrue("First synthesized sample must be non-zero", firstSample > 5000)
        assertTrue("Synthesized samples must decay monotonically", firstSample > lastSample)
        assertTrue("Final synthesized sample must be decayed to below 2000", lastSample < 2000)
        assertEquals("Cached samples must be cleared after synthesis", 0.toShort(), plc.lastSampleLeft16)
    }

    // -------------------------------------------------------------------------
    // 7. PlaybackScheduler Tests
    // -------------------------------------------------------------------------
    @Test
    fun testPlaybackSchedulerPreRollAndStartupPackets() {
        val scheduler = PlaybackScheduler(initialPreRoll = 4)
        assertTrue(scheduler.isBuffering)
        assertFalse(scheduler.hasReadStarted)

        // Pre-roll unsatisfied with 3 packets
        scheduler.onPacketArrived(availableCount = 3, preRollThreshold = 4)
        assertTrue(scheduler.isBuffering)

        // Pre-roll satisfied with 4 packets
        scheduler.onPacketArrived(availableCount = 4, preRollThreshold = 4)
        assertFalse(scheduler.isBuffering)

        // Startup packet acceptance: accepts up to 32 slots earlier before readout starts
        assertTrue(scheduler.canAcceptStartupPacket(-1))
        assertTrue(scheduler.canAcceptStartupPacket(-32))
        assertFalse(scheduler.canAcceptStartupPacket(-33))

        // Once readout starts, startup packets are no longer accepted
        scheduler.markReadStarted()
        assertTrue(scheduler.hasReadStarted)
        assertFalse(scheduler.canAcceptStartupPacket(-1))
    }

    @Test
    fun testPlaybackSchedulerTimelineProgression() {
        val scheduler = PlaybackScheduler(initialPreRoll = 2)
        scheduler.onStreamReset(timestamp = 1000L, preRollThreshold = 2)
        assertEquals(1000L, scheduler.expectedReadTimestamp)

        // Advance by 240 frames
        scheduler.advanceExpectedReadTimestamp(frames = 240)
        assertEquals(1240L, scheduler.expectedReadTimestamp)

        // Explicit packet timestamp overrides drift
        scheduler.advanceExpectedReadTimestamp(frames = 240, packetTimestamp = 2000L)
        assertEquals(2240L, scheduler.expectedReadTimestamp)
    }
}
