package com.example.audiostreamer.diagnostics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LatencyHistoryTest {

    @Test
    fun emptyHistoryBehavesSafely() {
        val history = LatencyHistory(capacity = 10)
        assertEquals(0, history.size)
        assertEquals(0, history.getSnapshot().size)
        assertEquals(0f, history.getMin(), 0.001f)
        assertEquals(0f, history.getMax(), 0.001f)
        assertEquals(0f, history.getAverage(), 0.001f)

        val dest = FloatArray(5)
        assertEquals(0, history.copyInto(dest))
    }

    @Test
    fun addsSamplesUpToCapacityChronologically() {
        val history = LatencyHistory(capacity = 5)
        history.addSample(10f)
        history.addSample(20f)
        history.addSample(30f)

        assertEquals(3, history.size)
        assertArrayEquals(floatArrayOf(10f, 20f, 30f), history.getSnapshot(), 0.001f)
        assertEquals(10f, history.getMin(), 0.001f)
        assertEquals(30f, history.getMax(), 0.001f)
        assertEquals(20f, history.getAverage(), 0.001f)
    }

    @Test
    fun ringBufferWrapsAndMaintainsRollingWindow() {
        val history = LatencyHistory(capacity = 3)
        history.addSample(10f)
        history.addSample(20f)
        history.addSample(30f)
        history.addSample(40f) // Overwrites 10f

        assertEquals(3, history.size)
        assertArrayEquals(floatArrayOf(20f, 30f, 40f), history.getSnapshot(), 0.001f)
        assertEquals(20f, history.getMin(), 0.001f)
        assertEquals(40f, history.getMax(), 0.001f)
        assertEquals(30f, history.getAverage(), 0.001f)

        history.addSample(50f) // Overwrites 20f
        assertArrayEquals(floatArrayOf(30f, 40f, 50f), history.getSnapshot(), 0.001f)
    }

    @Test
    fun copyIntoCopiesChronologicalSamplesWithoutAllocation() {
        val history = LatencyHistory(capacity = 4)
        history.addSample(10f)
        history.addSample(20f)
        history.addSample(30f)
        history.addSample(40f)
        history.addSample(50f) // Circularly wraps: oldest is 20f, newest is 50f

        val destination = FloatArray(4)
        val copied = history.copyInto(destination)
        assertEquals(4, copied)
        assertArrayEquals(floatArrayOf(20f, 30f, 40f, 50f), destination, 0.001f)

        // Destination smaller than history count
        val smallDest = FloatArray(2)
        val smallCopied = history.copyInto(smallDest)
        assertEquals(2, smallCopied)
        assertArrayEquals(floatArrayOf(20f, 30f), smallDest, 0.001f)
    }

    @Test
    fun clearResetsRingBuffer() {
        val history = LatencyHistory(capacity = 5)
        history.addSample(15f)
        history.addSample(25f)
        history.clear()

        assertEquals(0, history.size)
        assertEquals(0, history.getSnapshot().size)
        assertEquals(0f, history.getMin(), 0.001f)
        assertEquals(0f, history.getMax(), 0.001f)
    }

    @Test
    fun handlesNaNAndNegativeGracefully() {
        val history = LatencyHistory(capacity = 5)
        history.addSample(Float.NaN)
        history.addSample(-10f)
        history.addSample(Float.POSITIVE_INFINITY)

        assertEquals(3, history.size)
        val snapshot = history.getSnapshot()
        for (sample in snapshot) {
            assertTrue("Sample must be finite non-negative", sample >= 0f && sample.isFinite())
        }
    }

    @Test
    fun concurrentAppendsAndReadsAreThreadSafe() {
        val history = LatencyHistory(capacity = LatencyHistory.DEFAULT_CAPACITY)
        val threads = 4
        val iterationsPerThread = 200
        val pool = Executors.newFixedThreadPool(threads)
        val startLatch = CountDownLatch(1)
        val errors = AtomicInteger(0)

        repeat(threads) { t ->
            pool.execute {
                startLatch.await()
                for (i in 0 until iterationsPerThread) {
                    try {
                        history.addSample(t * 10f + i)
                        val snap = history.getSnapshot()
                        assertTrue(snap.size <= LatencyHistory.DEFAULT_CAPACITY)
                        history.getAverage()
                        history.getMin()
                        history.getMax()
                    } catch (e: Throwable) {
                        errors.incrementAndGet()
                    }
                }
            }
        }

        startLatch.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS))
        assertEquals(0, errors.get())
        assertEquals(LatencyHistory.DEFAULT_CAPACITY, history.size)
    }
}
