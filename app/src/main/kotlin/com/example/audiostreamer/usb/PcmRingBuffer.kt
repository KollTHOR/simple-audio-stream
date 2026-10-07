package com.example.audiostreamer.usb

import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free single-producer / single-consumer byte ring buffer for the USB PCM path.
 *
 * USB gives a reliable, ordered byte pipe, so — unlike the network [com.example.audiostreamer.JitterBuffer]
 * — there is NO sequence/reorder/NACK/FEC machinery here. The only job is to absorb producer bursts
 * (batched USB reads) vs consumer pacing (AudioTrack writes), i.e. decouple USB transfer timing from
 * audio-underrun timing (spec §6).
 *
 * Capacity is rounded up to a power of two so index wrapping is a mask, and head/tail are monotonically
 * increasing counters (never wrapped), which makes full vs empty unambiguous with a single AtomicLong
 * each and keeps the SPSC protocol wait-free for one writer + one reader.
 *
 * Producer calls [write]; consumer calls [read]. Both are non-blocking: [write] drops (and counts an
 * overflow) only if the consumer has stalled beyond capacity; [read] returns whatever is available so
 * the AudioTrack loop can detect and count underruns itself.
 */
class PcmRingBuffer(requestedCapacityBytes: Int) {

    /** Actual power-of-two capacity in bytes (can grow via [resize]). */
    @Volatile var capacity: Int = ceilPowerOfTwo(requestedCapacityBytes.coerceAtLeast(MIN_CAPACITY))
        private set

    private var buffer = ByteArray(capacity)
    private var mask = capacity - 1

    // Monotonic byte counters (never masked). head = written, tail = read.
    private val head = AtomicLong(0L)
    private val tail = AtomicLong(0L)

    // ---- Stats exposed for diagnostics (spec §6, §10) ----
    /** Total bytes written by the producer (received from USB). */
    val bytesWritten: Long get() = head.get()

    /** Total bytes consumed by the reader (handed to AudioTrack). */
    val bytesRead: Long get() = tail.get()

    private val _overflowDrops = AtomicLong(0L)
    /** Bytes discarded because the buffer was full (consumer starvation / rate mismatch). */
    val overflowDroppedBytes: Long get() = _overflowDrops.get()

    /**
     * Current fill in bytes. Read-only estimate from the counters.
     */
    fun available(): Int {
        val h = head.get()
        val t = tail.get()
        return (h - t).toInt().coerceIn(0, capacity)
    }

    fun fillPercent(): Int = if (capacity == 0) 0 else (available() * 100 / capacity)

    /** Free space available for a writer right now. */
    fun remainingCapacity(): Int = capacity - available()

    /**
     * Writes up to [length] bytes from [src]; returns the number actually written (may be less when
     * the buffer is near-full). A partial write is NOT an overflow — backpressure/drop policy belongs
     * to the caller (the receiver decides whether to retry or drop). Returns 0 when full.
     */
    fun write(src: ByteArray, offset: Int, length: Int): Int {
        val h = head.get()
        val t = tail.get()
        val free = capacity - (h - t).toInt()
        if (free <= 0 || length <= 0) return 0
        val toWrite = minOf(length, free)
        val destOff = (h and mask.toLong()).toInt()
        val firstChunk = minOf(toWrite, capacity - destOff)
        System.arraycopy(src, offset, buffer, destOff, firstChunk)
        val secondChunk = toWrite - firstChunk
        if (secondChunk > 0) {
            System.arraycopy(src, offset + firstChunk, buffer, 0, secondChunk)
        }
        head.set(h + toWrite)
        return toWrite
    }

    /**
     * Real-time convenience: write as much as fits and return the number of *newest* bytes that had
     * to be dropped because the buffer was full (consumer starvation). Increments [overflowDroppedBytes]
     * by that amount. The receive path uses this so a stalled consumer never blocks the USB reader.
     */
    fun writeDroppingNewest(src: ByteArray, offset: Int, length: Int): Int {
        val written = write(src, offset, length)
        val dropped = length - written
        if (dropped > 0) _overflowDrops.addAndGet(dropped.toLong())
        return written
    }

    /**
     * Reads up to [length] available bytes into [dst] at [offset]. Returns bytes copied (may be less
     * than requested); 0 means currently empty (caller decides whether that is an underrun).
     */
    fun read(dst: ByteArray, offset: Int, length: Int): Int {
        val t = tail.get()
        val h = head.get()
        val avail = (h - t).toInt()
        if (avail <= 0) return 0
        val toRead = minOf(length, avail)
        val srcOff = (t and mask.toLong()).toInt()
        val firstChunk = minOf(toRead, capacity - srcOff)
        System.arraycopy(buffer, srcOff, dst, offset, firstChunk)
        val secondChunk = toRead - firstChunk
        if (secondChunk > 0) {
            System.arraycopy(buffer, 0, dst, offset + firstChunk, secondChunk)
        }
        tail.set(t + toRead)
        return toRead
    }

    /** Resets counters and occupancy (e.g. on STOP or reconfigure). Does not reallocate. */
    fun reset() {
        head.set(0L)
        tail.set(0L)
        _overflowDrops.set(0L)
    }

    /**
     * Re-sizes the ring for a new PCM format (power-of-two, >= [MIN_CAPACITY], >= requested). Must
     * only be called while the consumer is stopped (the producer thread does this on (re)configure):
     * it reallocates the backing array and clears occupancy. No-op if the size is unchanged.
     */
    @Synchronized
    fun resize(requestedCapacityBytes: Int) {
        val newCapacity = ceilPowerOfTwo(requestedCapacityBytes.coerceAtLeast(MIN_CAPACITY))
        if (newCapacity == capacity) {
            reset()
            return
        }
        capacity = newCapacity
        buffer = ByteArray(newCapacity)
        mask = newCapacity - 1
        reset()
    }

    private companion object {
        const val MIN_CAPACITY = 1024

        fun ceilPowerOfTwo(v: Int): Int {
            var p = 1
            while (p < v) p = p shl 1
            return p
        }
    }
}
