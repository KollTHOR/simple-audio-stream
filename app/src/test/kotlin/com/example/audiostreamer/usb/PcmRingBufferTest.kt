package com.example.audiostreamer.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lock-free SPSC ring buffer invariants the real-time path depends on: wrap correctness,
 * non-blocking partial reads/writes, overflow accounting, and a stress sanity pass with a dedicated
 * producer and consumer thread.
 */
class PcmRingBufferTest {

    @Test
    fun capacityIsRoundedUpToPowerOfTwo() {
        val buf = PcmRingBuffer(1500)
        assertEquals(2048, buf.capacity)
        assertEquals(1024, PcmRingBuffer(1).capacity) // min clamp
    }

    @Test
    fun writeThenReadRoundTripsAcrossWrap() {
        val buf = PcmRingBuffer(1024)
        assertEquals(1024, buf.capacity)

        val w = ByteArray(1000) { (it + 1).toByte() }
        assertEquals(1000, buf.write(w, 0, 1000))       // head=1000, tail=0
        val r1 = ByteArray(900)
        assertEquals(900, buf.read(r1, 0, 900))         // tail=900, avail=100
        for (i in 0 until 900) assertEquals(w[i], r1[i])

        val more = ByteArray(300) { (it + 100).toByte() }
        assertEquals(300, buf.write(more, 0, 300))       // head=1300 -> wraps past 1024
        val r2 = ByteArray(400)
        assertEquals(400, buf.read(r2, 0, 400))
        // Remaining tail of w (900..999) then the whole of more (0..299), read back in order.
        for (i in 0 until 100) assertEquals(w[900 + i], r2[i])
        for (i in 0 until 300) assertEquals(more[i], r2[100 + i])
    }

    @Test
    fun readReturnsZeroWhenEmpty() {
        val buf = PcmRingBuffer(1024)
        assertEquals(0, buf.read(ByteArray(8), 0, 8))
    }

    @Test
    fun writeIsBoundedByFreeSpace() {
        val buf = PcmRingBuffer(1024) // capacity 1024
        val big = ByteArray(2000)
        assertEquals(1024, buf.write(big, 0, big.size)) // only free space written, no side effect
        assertEquals(1024, buf.available())
        assertEquals(0, buf.write(ByteArray(10), 0, 10)) // now full
        assertEquals(0, buf.overflowDroppedBytes) // plain write never counts overflow
    }

    @Test
    fun writeDroppingNewestCountsOverflow() {
        val buf = PcmRingBuffer(1024)
        val written = buf.writeDroppingNewest(ByteArray(2000), 0, 2000)
        assertEquals(1024, written)
        assertEquals(976, buf.overflowDroppedBytes) // 2000 - 1024 dropped by explicit drop policy
    }

    @Test
    fun fillPercentTracksOccupancy() {
        val buf = PcmRingBuffer(1024)
        buf.write(ByteArray(512), 0, 512)
        assertTrue(buf.fillPercent() in 49..51)
        buf.reset()
        assertEquals(0, buf.available())
    }

    @Test
    fun byteCountersTrackTotals() {
        val buf = PcmRingBuffer(1024)
        buf.write(ByteArray(300), 0, 300)
        buf.read(ByteArray(200), 0, 200)
        assertEquals(300, buf.bytesWritten)
        assertEquals(200, buf.bytesRead)
    }

    @Test
    fun concurrentProducerConsumerPreservesByteSequence() {
        // One producer thread pushes a known byte stream; one consumer drains it. The consumer must
        // see every byte in exact order despite SPSC wrap-around (this is the real hot-path usage).
        val total = 200_000
        val buf = PcmRingBuffer(8192)
        val received = ByteArray(total)
        var readCursor = 0

        val producer = Thread {
            val chunk = ByteArray(512)
            var sent = 0
            while (sent < total) {
                for (i in chunk.indices) chunk[i] = ((sent + i) % 251).toByte()
                val n = minOf(chunk.size, total - sent)
                var off = 0
                while (off < n) {
                    val wrote = buf.write(chunk, off, n - off)
                    // If full, yield (consumer will drain); never drop in this test's sizing.
                    if (wrote > 0) off += wrote else Thread.yield()
                }
                sent += n
            }
        }
        val consumer = Thread {
            val out = ByteArray(700)
            while (readCursor < total) {
                val r = buf.read(out, 0, out.size)
                if (r > 0) {
                    System.arraycopy(out, 0, received, readCursor, r)
                    readCursor += r
                } else {
                    Thread.yield()
                }
            }
        }
        producer.start(); consumer.start()
        producer.join(30_000); consumer.join(30_000)
        assertTrue(!producer.isAlive && !consumer.isAlive)

        assertEquals(total, readCursor)
        // Reconstruct expected stream and compare.
        for (i in 0 until total) {
            assertEquals("mismatch at $i", (i % 251).toByte(), received[i])
        }
        // SPSC must never report a spurious overflow at this sizing.
        assertEquals(0, buf.overflowDroppedBytes)
    }
}
