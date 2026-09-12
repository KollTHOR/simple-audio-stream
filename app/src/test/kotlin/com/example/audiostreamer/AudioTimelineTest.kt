package com.example.audiostreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTimelineTest {

    private val payloadLen = 1440 // 24-bit stereo 48kHz 5ms = 240 frames * 6 bytes

    private fun createDummyAudioPayload(marker: Byte): ByteArray {
        val payload = ByteArray(payloadLen)
        payload.fill(marker)
        return payload
    }

    @Test
    fun testNormalSequentialPackets() {
        val jitterBuffer = JitterBuffer(AudioConfig.PROFILE_LOW_LATENCY)
        jitterBuffer.set24Bit(true)
        jitterBuffer.setSampleRate(AudioConfig.SAMPLE_RATE_48000)

        val framesPerPacket = 240L
        for (i in 0 until 10) {
            val payload = createDummyAudioPayload(i.toByte())
            val ts = i * framesPerPacket
            jitterBuffer.write(sequence = i, timestamp = ts, data = payload, offset = 0, length = payload.size)
            assertEquals(ts, jitterBuffer.getPacketTimestamp(i))
        }

        assertEquals(9 * framesPerPacket, jitterBuffer.getLastReceivedTimestamp())

        // Read through packets and verify timeline progression
        val readBuf = ByteArray(payloadLen)
        for (i in 0 until 10) {
            val bytesRead = jitterBuffer.read(readBuf)
            assertEquals(payloadLen, bytesRead)
            assertEquals(i.toByte(), readBuf[0])
            val expectedNextTs = (i + 1) * framesPerPacket
            assertEquals(expectedNextTs, jitterBuffer.getExpectedReadTimestamp())
        }
    }

    @Test
    fun testMissingPacketsAndFecRecovery() {
        val jitterBuffer = JitterBuffer(AudioConfig.PROFILE_LOW_LATENCY)
        jitterBuffer.set24Bit(true)
        jitterBuffer.setSampleRate(AudioConfig.SAMPLE_RATE_48000)

        val fecEncoder = FecEncoder(blockSize = 4)
        val framesPerPacket = 240L

        val p0 = createDummyAudioPayload(10)
        val p1 = createDummyAudioPayload(20) // This packet will be dropped
        val p2 = createDummyAudioPayload(30)
        val p3 = createDummyAudioPayload(40)

        // Encode block
        fecEncoder.encode(seq = 0, timestamp = 0L, payload = p0, offset = 0, len = p0.size)
        fecEncoder.encode(seq = 1, timestamp = framesPerPacket, payload = p1, offset = 0, len = p1.size)
        fecEncoder.encode(seq = 2, timestamp = 2 * framesPerPacket, payload = p2, offset = 0, len = p2.size)
        val parityBytes = fecEncoder.encode(seq = 3, timestamp = 3 * framesPerPacket, payload = p3, offset = 0, len = p3.size)
        assertNotNull("FEC parity packet must be generated on 4th packet", parityBytes)

        // Deliver packets 0, 2, 3 to receiver (packet 1 lost)
        jitterBuffer.write(sequence = 0, timestamp = 0L, data = p0, offset = 0, length = p0.size)
        jitterBuffer.write(sequence = 2, timestamp = 2 * framesPerPacket, data = p2, offset = 0, length = p2.size)
        jitterBuffer.write(sequence = 3, timestamp = 3 * framesPerPacket, data = p3, offset = 0, length = p3.size)

        assertTrue(jitterBuffer.hasPacket(0))
        assertFalse("Packet 1 must be missing", jitterBuffer.hasPacket(1))
        assertTrue(jitterBuffer.hasPacket(2))
        assertTrue(jitterBuffer.hasPacket(3))

        // Parity packet arrives
        val parityPayloadLen = parityBytes!!.size - HatPacket.HEADER_SIZE
        val recovered = jitterBuffer.recoverFecPacket(
            baseSeq = 0,
            baseTimestamp = 0L,
            blockSize = 4,
            parityPayload = parityBytes,
            parityOffset = HatPacket.HEADER_SIZE,
            parityLen = parityPayloadLen
        )

        assertTrue("Lost packet 1 must be recovered via FEC", recovered)
        assertTrue("Packet 1 must now be in buffer", jitterBuffer.hasPacket(1))
        assertEquals(framesPerPacket, jitterBuffer.getPacketTimestamp(1))

        // Verify recovered packet content
        val dest = ByteArray(payloadLen)
        val copied = jitterBuffer.copyPacketData(1, dest)
        assertEquals(payloadLen, copied)
        assertEquals(20.toByte(), dest[0])
    }

    @Test
    fun testReorderedPackets() {
        val jitterBuffer = JitterBuffer(AudioConfig.PROFILE_LOW_LATENCY)
        jitterBuffer.set24Bit(true)
        jitterBuffer.setSampleRate(AudioConfig.SAMPLE_RATE_48000)

        val framesPerPacket = 240L
        val p0 = createDummyAudioPayload(1)
        val p1 = createDummyAudioPayload(2)
        val p2 = createDummyAudioPayload(3)

        // Arrive out of order: seq 2, then seq 0, then seq 1
        jitterBuffer.write(sequence = 2, timestamp = 2 * framesPerPacket, data = p2, offset = 0, length = p2.size)
        jitterBuffer.write(sequence = 0, timestamp = 0L, data = p0, offset = 0, length = p0.size)
        jitterBuffer.write(sequence = 1, timestamp = 1 * framesPerPacket, data = p1, offset = 0, length = p1.size)

        assertEquals(0L, jitterBuffer.getPacketTimestamp(0))
        assertEquals(framesPerPacket, jitterBuffer.getPacketTimestamp(1))
        assertEquals(2 * framesPerPacket, jitterBuffer.getPacketTimestamp(2))

        // Read in order
        val readBuf = ByteArray(payloadLen)
        jitterBuffer.read(readBuf)
        assertEquals(1.toByte(), readBuf[0])
        jitterBuffer.read(readBuf)
        assertEquals(2.toByte(), readBuf[0])
        jitterBuffer.read(readBuf)
        assertEquals(3.toByte(), readBuf[0])
    }

    @Test
    fun testDuplicatedPackets() {
        val jitterBuffer = JitterBuffer(AudioConfig.PROFILE_LOW_LATENCY)
        jitterBuffer.set24Bit(true)
        jitterBuffer.setSampleRate(AudioConfig.SAMPLE_RATE_48000)

        val framesPerPacket = 240L
        val p0 = createDummyAudioPayload(1)
        val p1 = createDummyAudioPayload(2)

        jitterBuffer.write(sequence = 0, timestamp = 0L, data = p0, offset = 0, length = p0.size)
        jitterBuffer.write(sequence = 1, timestamp = framesPerPacket, data = p1, offset = 0, length = p1.size)
        assertEquals(2, jitterBuffer.getAvailableCount())

        // Send duplicate of seq 1
        jitterBuffer.write(sequence = 1, timestamp = framesPerPacket, data = p1, offset = 0, length = p1.size)
        // Should not duplicate slots or corrupt timestamp
        assertEquals(2, jitterBuffer.getAvailableCount())
        assertEquals(framesPerPacket, jitterBuffer.getPacketTimestamp(1))

        // Read seq 0
        val readBuf = ByteArray(payloadLen)
        jitterBuffer.read(readBuf)
        assertEquals(1.toByte(), readBuf[0])

        // Arrive stale duplicate of seq 0 which has already been played
        jitterBuffer.write(sequence = 0, timestamp = 0L, data = p0, offset = 0, length = p0.size)
        // Verify buffer state untouched and reading seq 1 proceeds smoothly
        jitterBuffer.read(readBuf)
        assertEquals(2.toByte(), readBuf[0])
    }

    @Test
    fun testTimestampDiscontinuitySilenceJump() {
        val jitterBuffer = JitterBuffer(AudioConfig.PROFILE_LOW_LATENCY)
        jitterBuffer.set24Bit(true)
        jitterBuffer.setSampleRate(AudioConfig.SAMPLE_RATE_48000)

        val framesPerPacket = 240L
        val p0 = createDummyAudioPayload(1)
        val p1 = createDummyAudioPayload(2)
        jitterBuffer.write(sequence = 0, timestamp = 0L, data = p0, offset = 0, length = p0.size)
        jitterBuffer.write(sequence = 1, timestamp = framesPerPacket, data = p1, offset = 0, length = p1.size)

        val readBuf = ByteArray(payloadLen)
        jitterBuffer.read(readBuf)
        jitterBuffer.read(readBuf)

        // 100 packets suppressed (24,000 frames)
        val resumedSeq = 102
        val resumedTs = 102 * framesPerPacket // 24480L
        val resumedPayload0 = createDummyAudioPayload(99)
        val resumedPayload1 = createDummyAudioPayload(100)

        jitterBuffer.write(sequence = resumedSeq, timestamp = resumedTs, data = resumedPayload0, offset = 0, length = resumedPayload0.size)
        jitterBuffer.write(sequence = resumedSeq + 1, timestamp = resumedTs + framesPerPacket, data = resumedPayload1, offset = 0, length = resumedPayload1.size)

        assertEquals(resumedTs + framesPerPacket, jitterBuffer.getLastReceivedTimestamp())
        assertEquals(resumedTs, jitterBuffer.getPacketTimestamp(resumedSeq))
        assertEquals(resumedTs + framesPerPacket, jitterBuffer.getPacketTimestamp(resumedSeq + 1))

        // Buffer reads resumed packet cleanly
        jitterBuffer.read(readBuf)
        assertEquals(99.toByte(), readBuf[0])
        assertEquals(resumedTs + framesPerPacket, jitterBuffer.getExpectedReadTimestamp())
    }

    @Test
    fun testStreamRestartTimestampReset() {
        val jitterBuffer = JitterBuffer(AudioConfig.PROFILE_LOW_LATENCY)
        jitterBuffer.set24Bit(true)
        jitterBuffer.setSampleRate(AudioConfig.SAMPLE_RATE_48000)

        val framesPerPacket = 240L
        // Old stream running at high sequence and timestamp
        val pOld = createDummyAudioPayload(5)
        jitterBuffer.write(sequence = 5000, timestamp = 1200000L, data = pOld, offset = 0, length = pOld.size)
        assertEquals(1200000L, jitterBuffer.getLastReceivedTimestamp())

        // Stream restart: seq resets to 0, timestamp resets to 0L
        val pNew0 = createDummyAudioPayload(7)
        val pNew1 = createDummyAudioPayload(8)
        jitterBuffer.write(sequence = 0, timestamp = 0L, data = pNew0, offset = 0, length = pNew0.size)
        jitterBuffer.write(sequence = 1, timestamp = framesPerPacket, data = pNew1, offset = 0, length = pNew1.size)

        // Jitter buffer must recognize stream restart
        assertEquals(framesPerPacket, jitterBuffer.getLastReceivedTimestamp())
        assertEquals(0L, jitterBuffer.getPacketTimestamp(0))
        assertEquals(framesPerPacket, jitterBuffer.getPacketTimestamp(1))

        val readBuf = ByteArray(payloadLen)
        val bytesRead = jitterBuffer.read(readBuf)
        assertEquals(payloadLen, bytesRead)
        assertEquals(7.toByte(), readBuf[0])
        assertEquals(framesPerPacket, jitterBuffer.getExpectedReadTimestamp())
    }

    @Test
    fun testLarge64BitTimelineTimestamps() {
        // Test values exceeding 32-bit unsigned int (> 4,294,967,295 frames, e.g. 24 hours of 48kHz audio = 4,147,200,000 frames; 48 hours = 8,294,400,000 frames)
        val largeTs = 8_294_400_000L // Exceeds 32-bit integer range
        val header = HatPacket.Header(
            packetType = HatPacket.TYPE_AUDIO,
            sequenceNumber = 12345,
            payloadLength = payloadLen,
            timestamp = largeTs,
            sampleRateCode = HatPacket.RATE_48000,
            bitDepth = HatPacket.BIT_DEPTH_24,
            channels = HatPacket.CHANNELS_STEREO
        )

        val buf = ByteArray(HatPacket.HEADER_SIZE + payloadLen)
        HatPacket.writeHeader(buf, 0, header)

        val parsed = HatPacket.parseHeader(buf, 0, buf.size)
        assertNotNull(parsed)
        assertEquals(largeTs, parsed?.timestamp)
    }
}
