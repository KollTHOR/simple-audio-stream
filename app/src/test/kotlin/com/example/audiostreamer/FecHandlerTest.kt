package com.example.audiostreamer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class FecHandlerTest {

    private fun createPayload(size: Int, seed: Long): ByteArray {
        val random = Random(seed)
        val data = ByteArray(size)
        random.nextBytes(data)
        return data
    }

    @Test
    fun testVariablePayloadLengthsRecovery() {
        // Variable-bitrate packets: 100, 250, 80, 300 bytes
        val sizes = intArrayOf(100, 250, 80, 300)
        val packets = Array(4) { i -> createPayload(sizes[i], (i + 1).toLong()) }

        // Test recovering EACH packet when it is the single lost packet
        for (lostIndex in 0 until 4) {
            val encoder = FecEncoder(blockSize = 4)
            val jitterBuffer = JitterBuffer(AudioConfig.PROFILE_LOW_LATENCY)
            jitterBuffer.set24Bit(true)
            jitterBuffer.setSampleRate(AudioConfig.SAMPLE_RATE_48000)
            val decoder = FecDecoder(jitterBuffer)

            var parityPacket: ByteArray? = null
            for (i in 0 until 4) {
                val p = encoder.encode(
                    seq = i,
                    timestamp = i * 240L,
                    payload = packets[i],
                    offset = 0,
                    len = packets[i].size
                )
                if (i == 3) {
                    parityPacket = p
                } else {
                    assertNull("Intermediate packets should not produce parity", p)
                }
            }
            assertNotNull("Parity packet must be produced on 4th packet", parityPacket)

            // Deliver all packets except lostIndex
            for (i in 0 until 4) {
                if (i != lostIndex) {
                    jitterBuffer.write(
                        sequence = i,
                        timestamp = i * 240L,
                        data = packets[i],
                        offset = 0,
                        length = packets[i].size
                    )
                }
            }

            assertFalse("Lost packet must not be in buffer", jitterBuffer.hasPacket(lostIndex))

            val parityLen = parityPacket!!.size - HatPacket.HEADER_SIZE
            val recovered = decoder.decode(
                baseSeq = 0,
                baseTimestamp = 0L,
                blockSize = 4,
                parityPayload = parityPacket,
                parityOffset = HatPacket.HEADER_SIZE,
                parityLen = parityLen
            )

            assertTrue("Lost packet $lostIndex (${sizes[lostIndex]} bytes) must be recovered", recovered)
            assertTrue("Lost packet must now be in buffer", jitterBuffer.hasPacket(lostIndex))
            assertEquals("Recovered packet must have exact original length", sizes[lostIndex], jitterBuffer.getPacketLength(lostIndex))

            val dest = ByteArray(sizes[lostIndex])
            val copied = jitterBuffer.copyPacketData(lostIndex, dest)
            assertEquals(sizes[lostIndex], copied)
            assertArrayEquals("Recovered payload must match original bit-for-bit", packets[lostIndex], dest)
        }
    }

    @Test
    fun testIncompleteGroupTwoPacketsLost() {
        val encoder = FecEncoder(blockSize = 4)
        val jitterBuffer = JitterBuffer(AudioConfig.PROFILE_LOW_LATENCY)
        val decoder = FecDecoder(jitterBuffer)

        val p0 = createPayload(120, 10L)
        val p1 = createPayload(150, 20L)
        val p2 = createPayload(200, 30L)
        val p3 = createPayload(180, 40L)

        encoder.encode(0, 0L, p0, 0, p0.size)
        encoder.encode(1, 240L, p1, 0, p1.size)
        encoder.encode(2, 480L, p2, 0, p2.size)
        val parityBytes = encoder.encode(3, 720L, p3, 0, p3.size)!!

        // Only p0 and p3 arrive (p1 and p2 lost)
        jitterBuffer.write(0, 0L, p0, 0, p0.size)
        jitterBuffer.write(3, 720L, p3, 0, p3.size)

        val parityLen = parityBytes.size - HatPacket.HEADER_SIZE
        val recovered = decoder.decode(
            baseSeq = 0,
            baseTimestamp = 0L,
            blockSize = 4,
            parityPayload = parityBytes,
            parityOffset = HatPacket.HEADER_SIZE,
            parityLen = parityLen
        )

        assertFalse("Cannot recover when 2 packets are lost", recovered)
        assertFalse(jitterBuffer.hasPacket(1))
        assertFalse(jitterBuffer.hasPacket(2))
    }

    @Test
    fun testReorderedPacketsArrival() {
        val encoder = FecEncoder(blockSize = 4)
        val jitterBuffer = JitterBuffer(AudioConfig.PROFILE_LOW_LATENCY)
        val decoder = FecDecoder(jitterBuffer)

        val packets = Array(4) { i -> createPayload(100 + i * 20, (i + 1).toLong()) }
        for (i in 0 until 3) {
            encoder.encode(i, i * 240L, packets[i], 0, packets[i].size)
        }
        val parityBytes = encoder.encode(3, 720L, packets[3], 0, packets[3].size)!!

        // Reordered arrival: packet 3 arrives, then packet 0, then packet 2 (packet 1 lost)
        jitterBuffer.write(3, 720L, packets[3], 0, packets[3].size)
        jitterBuffer.write(0, 0L, packets[0], 0, packets[0].size)
        jitterBuffer.write(2, 480L, packets[2], 0, packets[2].size)

        val parityLen = parityBytes.size - HatPacket.HEADER_SIZE
        val recovered = decoder.decode(
            baseSeq = 0,
            baseTimestamp = 0L,
            blockSize = 4,
            parityPayload = parityBytes,
            parityOffset = HatPacket.HEADER_SIZE,
            parityLen = parityLen
        )

        assertTrue("Lost packet 1 must be recovered with out-of-order arrivals", recovered)
        assertTrue(jitterBuffer.hasPacket(1))
        assertEquals(packets[1].size, jitterBuffer.getPacketLength(1))

        val dest = ByteArray(packets[1].size)
        jitterBuffer.copyPacketData(1, dest)
        assertArrayEquals(packets[1], dest)
    }

    @Test
    fun testDuplicatePackets() {
        val encoder = FecEncoder(blockSize = 4)
        val jitterBuffer = JitterBuffer(AudioConfig.PROFILE_LOW_LATENCY)
        val decoder = FecDecoder(jitterBuffer)

        val packets = Array(4) { i -> createPayload(128, (i + 1).toLong()) }
        for (i in 0 until 3) {
            encoder.encode(i, i * 240L, packets[i], 0, packets[i].size)
        }
        val parityBytes = encoder.encode(3, 720L, packets[3], 0, packets[3].size)!!

        // Deliver 0, 2, 3
        jitterBuffer.write(0, 0L, packets[0], 0, packets[0].size)
        // Duplicate delivery of packet 0
        jitterBuffer.write(0, 0L, packets[0], 0, packets[0].size)
        jitterBuffer.write(2, 480L, packets[2], 0, packets[2].size)
        jitterBuffer.write(3, 720L, packets[3], 0, packets[3].size)

        val parityLen = parityBytes.size - HatPacket.HEADER_SIZE
        // First parity decode recovers packet 1
        val recovered1 = decoder.decode(
            baseSeq = 0,
            baseTimestamp = 0L,
            blockSize = 4,
            parityPayload = parityBytes,
            parityOffset = HatPacket.HEADER_SIZE,
            parityLen = parityLen
        )
        assertTrue("First decode must recover lost packet", recovered1)

        // Duplicate parity packet arrives
        val recovered2 = decoder.decode(
            baseSeq = 0,
            baseTimestamp = 0L,
            blockSize = 4,
            parityPayload = parityBytes,
            parityOffset = HatPacket.HEADER_SIZE,
            parityLen = parityLen
        )
        assertFalse("Duplicate parity decode must be a clean no-op", recovered2)

        // Late-arriving packet 1 arrives after recovery
        jitterBuffer.write(1, 240L, packets[1], 0, packets[1].size)
        // Verify buffer is unchanged and intact
        val dest = ByteArray(packets[1].size)
        jitterBuffer.copyPacketData(1, dest)
        assertArrayEquals(packets[1], dest)
    }

    @Test
    fun testCorruptedParityPayloadRejection() {
        val encoder = FecEncoder(blockSize = 4)
        val jitterBuffer = JitterBuffer(AudioConfig.PROFILE_LOW_LATENCY)
        val decoder = FecDecoder(jitterBuffer)

        val packets = Array(4) { i -> createPayload(160, (i + 1).toLong()) }
        for (i in 0 until 3) {
            encoder.encode(i, i * 240L, packets[i], 0, packets[i].size)
        }
        val parityBytes = encoder.encode(3, 720L, packets[3], 0, packets[3].size)!!

        // Deliver 0, 2, 3 (packet 1 lost)
        jitterBuffer.write(0, 0L, packets[0], 0, packets[0].size)
        jitterBuffer.write(2, 480L, packets[2], 0, packets[2].size)
        jitterBuffer.write(3, 720L, packets[3], 0, packets[3].size)

        // Corrupt a byte in the XOR parity data
        val corruptedParity = parityBytes.clone()
        val parityDataOffset = HatPacket.HEADER_SIZE + FecProtocol.metadataSize(4)
        corruptedParity[parityDataOffset + 5] = (corruptedParity[parityDataOffset + 5].toInt() xor 0xFF).toByte()

        val parityLen = corruptedParity.size - HatPacket.HEADER_SIZE
        val recovered = decoder.decode(
            baseSeq = 0,
            baseTimestamp = 0L,
            blockSize = 4,
            parityPayload = corruptedParity,
            parityOffset = HatPacket.HEADER_SIZE,
            parityLen = parityLen
        )

        assertFalse("Corrupted parity must fail CRC32 verification and reject reconstruction", recovered)
        assertFalse("Packet 1 must NOT be inserted into jitter buffer", jitterBuffer.hasPacket(1))
    }

    @Test
    fun testCorruptedExistingPacketDetected() {
        val encoder = FecEncoder(blockSize = 4)
        val jitterBuffer = JitterBuffer(AudioConfig.PROFILE_LOW_LATENCY)
        val decoder = FecDecoder(jitterBuffer)

        val packets = Array(4) { i -> createPayload(160, (i + 1).toLong()) }
        for (i in 0 until 3) {
            encoder.encode(i, i * 240L, packets[i], 0, packets[i].size)
        }
        val parityBytes = encoder.encode(3, 720L, packets[3], 0, packets[3].size)!!

        // Deliver packet 0, packet 2, packet 3, but tamper packet 0's content in the buffer
        val corruptedP0 = packets[0].clone()
        corruptedP0[10] = (corruptedP0[10].toInt() xor 0xFF).toByte()

        jitterBuffer.write(0, 0L, corruptedP0, 0, corruptedP0.size)
        jitterBuffer.write(2, 480L, packets[2], 0, packets[2].size)
        jitterBuffer.write(3, 720L, packets[3], 0, packets[3].size)

        // Packet 1 is missing, but packet 0 in buffer fails CRC check -> 2 packets missing/corrupt!
        val parityLen = parityBytes.size - HatPacket.HEADER_SIZE
        val recovered = decoder.decode(
            baseSeq = 0,
            baseTimestamp = 0L,
            blockSize = 4,
            parityPayload = parityBytes,
            parityOffset = HatPacket.HEADER_SIZE,
            parityLen = parityLen
        )

        assertFalse("Cannot recover packet 1 when packet 0 is corrupt (multi-error)", recovered)
        assertFalse("Packet 1 must not be recovered", jitterBuffer.hasPacket(1))
    }

    @Test
    fun testSilenceReset() {
        val encoder = FecEncoder(blockSize = 4)
        val p0 = createPayload(100, 1L)
        val p1 = createPayload(100, 2L)

        encoder.encode(0, 0L, p0, 0, p0.size)
        encoder.encode(1, 240L, p1, 0, p1.size)

        // Silence suppression intervenes: encoder is reset
        encoder.reset()

        // New audio stream begins
        val pNew0 = createPayload(100, 3L)
        val pNew1 = createPayload(100, 4L)
        val pNew2 = createPayload(100, 5L)
        val pNew3 = createPayload(100, 6L)

        assertNull(encoder.encode(10, 1000L, pNew0, 0, pNew0.size))
        assertNull(encoder.encode(11, 1240L, pNew1, 0, pNew1.size))
        assertNull(encoder.encode(12, 1480L, pNew2, 0, pNew2.size))
        val parity = encoder.encode(13, 1720L, pNew3, 0, pNew3.size)
        assertNotNull("Parity should be generated for the new 4-packet block", parity)

        val header = HatPacket.parseHeader(parity!!, 0, parity.size)
        assertNotNull(header)
        assertEquals(HatPacket.TYPE_FEC_PARITY, header!!.packetType)
        assertEquals(10, header.sequenceNumber) // Base sequence number of new block
        assertEquals(1000L, header.timestamp)
    }

    @Test
    fun testEndToEndWithHatPacketSerializerAndParser() {
        val encoder = FecEncoder(blockSize = 4)
        val jitterBuffer = JitterBuffer(AudioConfig.PROFILE_LOW_LATENCY)
        val decoder = FecDecoder(jitterBuffer)

        val p0 = createPayload(200, 101L)
        val p1 = createPayload(350, 102L)
        val p2 = createPayload(120, 103L)
        val p3 = createPayload(400, 104L)

        encoder.encode(100, 10000L, p0, 0, p0.size, codec = HatPacket.CODEC_OPUS)
        encoder.encode(101, 10240L, p1, 0, p1.size, codec = HatPacket.CODEC_OPUS)
        encoder.encode(102, 10480L, p2, 0, p2.size, codec = HatPacket.CODEC_OPUS)
        val wirePacket = encoder.encode(103, 10720L, p3, 0, p3.size, codec = HatPacket.CODEC_OPUS)!!

        // Parse wire packet using HatPacket parser
        val header = HatPacket.parseHeader(wirePacket, 0, wirePacket.size)
        assertNotNull("Header must parse successfully", header)
        assertEquals(HatPacket.TYPE_FEC_PARITY, header!!.packetType)
        assertEquals(100, header.sequenceNumber)
        assertEquals(4, header.fecBlockSize.toInt())
        assertEquals(10000L, header.timestamp)

        // Deliver packets 100, 101, 103 (packet 102 lost)
        jitterBuffer.write(100, 10000L, p0, 0, p0.size)
        jitterBuffer.write(101, 10240L, p1, 0, p1.size)
        jitterBuffer.write(103, 10720L, p3, 0, p3.size)

        // Feed to decoder exactly like AudioSinkService does
        val baseSeq = header.sequenceNumber
        val blockSize = header.fecBlockSize.toInt() and 0xFF
        val parityLen = header.payloadLength
        val recovered = decoder.decode(
            baseSeq = baseSeq,
            baseTimestamp = header.timestamp,
            blockSize = blockSize,
            parityPayload = wirePacket,
            parityOffset = HatPacket.HEADER_SIZE,
            parityLen = parityLen
        )

        assertTrue("Lost packet 102 must be recovered", recovered)
        assertTrue(jitterBuffer.hasPacket(102))
        assertEquals(p2.size, jitterBuffer.getPacketLength(102))

        val dest = ByteArray(p2.size)
        jitterBuffer.copyPacketData(102, dest)
        assertArrayEquals("Packet 102 data must match bit-for-bit", p2, dest)
    }

    @Test
    fun testBlockSizeTwoAndSixteen() {
        // Test K = 2
        run {
            val encoder = FecEncoder(blockSize = 2)
            val jitterBuffer = JitterBuffer(AudioConfig.PROFILE_LOW_LATENCY)
            val decoder = FecDecoder(jitterBuffer)

            val p0 = createPayload(100, 201L)
            val p1 = createPayload(200, 202L)

            assertNull(encoder.encode(0, 0L, p0, 0, p0.size))
            val parity = encoder.encode(1, 240L, p1, 0, p1.size)!!

            jitterBuffer.write(1, 240L, p1, 0, p1.size)
            val parityLen = parity.size - HatPacket.HEADER_SIZE
            val recovered = decoder.decode(0, 0L, 2, parity, HatPacket.HEADER_SIZE, parityLen)
            assertTrue("K=2 recovery must succeed", recovered)
            assertTrue(jitterBuffer.hasPacket(0))
            assertEquals(100, jitterBuffer.getPacketLength(0))
        }

        // Test K = 16
        run {
            val k = 16
            val encoder = FecEncoder(blockSize = k)
            val jitterBuffer = JitterBuffer(AudioConfig.PROFILE_LOW_LATENCY)
            val decoder = FecDecoder(jitterBuffer)

            val packets = Array(k) { i -> createPayload(50 + i * 10, (i + 1).toLong()) }
            var parity: ByteArray? = null
            for (i in 0 until k) {
                val p = encoder.encode(i, i * 240L, packets[i], 0, packets[i].size)
                if (i == k - 1) parity = p
            }
            assertNotNull(parity)

            // Drop packet index 7
            for (i in 0 until k) {
                if (i != 7) {
                    jitterBuffer.write(i, i * 240L, packets[i], 0, packets[i].size)
                }
            }

            val parityLen = parity!!.size - HatPacket.HEADER_SIZE
            val recovered = decoder.decode(0, 0L, k, parity, HatPacket.HEADER_SIZE, parityLen)
            assertTrue("K=16 recovery must succeed", recovered)
            assertTrue(jitterBuffer.hasPacket(7))
            assertEquals(packets[7].size, jitterBuffer.getPacketLength(7))

            val dest = ByteArray(packets[7].size)
            jitterBuffer.copyPacketData(7, dest)
            assertArrayEquals(packets[7], dest)
        }
    }

    @Test
    fun testMalformedParityPayloadRejected() {
        val jitterBuffer = JitterBuffer(AudioConfig.PROFILE_LOW_LATENCY)
        val decoder = FecDecoder(jitterBuffer)

        val dummy = ByteArray(64)

        // Invalid block size
        assertFalse(decoder.decode(0, 0L, 1, dummy, 0, 64))
        assertFalse(decoder.decode(0, 0L, 17, dummy, 0, 64))

        // Truncated payload (< metadataSize)
        assertFalse(decoder.decode(0, 0L, 4, dummy, 0, 10))

        // Invalid version byte
        dummy[0] = 99 // version 99
        dummy[1] = 4 // block size 4
        assertFalse(decoder.decode(0, 0L, 4, dummy, 0, 64))

        // Block size mismatch in metadata
        dummy[0] = FecProtocol.VERSION
        dummy[1] = 8 // says 8, but decoder called with 4
        assertFalse(decoder.decode(0, 0L, 4, dummy, 0, 64))
    }
}
