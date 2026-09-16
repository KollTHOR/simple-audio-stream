package com.example.audiostreamer.diagnostics

/**
 * Thread-safe, fixed-capacity circular buffer holding a rolling 60-second window
 * of receiver latency samples.
 *
 * Designed for zero allocations on each sample append:
 * - Preallocates a fixed FloatArray of [capacity] entries (120 samples @ 2 Hz = 60s).
 * - Computes min, max, average, and copies chronological data without mutating state.
 */
class LatencyHistory(val capacity: Int = DEFAULT_CAPACITY) {

    companion object {
        /** 120 samples at 500ms intervals = exactly 60 seconds rolling window. */
        const val DEFAULT_CAPACITY = 120
    }

    private val buffer = FloatArray(capacity)
    private var head = 0
    private var count = 0
    private val lock = Any()

    /**
     * Appends a new latency sample in milliseconds.
     * Overwrites the oldest sample once the 60-second capacity is reached.
     */
    fun addSample(latencyMs: Float) {
        val safeVal = if (latencyMs.isFinite() && latencyMs >= 0f) latencyMs else 0f
        synchronized(lock) {
            buffer[head] = safeVal
            head = (head + 1) % capacity
            if (count < capacity) {
                count++
            }
        }
    }

    /**
     * Returns a chronologically ordered snapshot of the samples currently in history
     * (oldest sample at index 0, newest sample at the end).
     */
    fun getSnapshot(): FloatArray {
        synchronized(lock) {
            if (count == 0) return FloatArray(0)
            val result = FloatArray(count)
            val start = (head - count + capacity) % capacity
            for (i in 0 until count) {
                result[i] = buffer[(start + i) % capacity]
            }
            return result
        }
    }

    /**
     * Copies chronological samples directly into [destination] to prevent allocations.
     * Returns the number of samples copied.
     */
    fun copyInto(destination: FloatArray): Int {
        synchronized(lock) {
            val toCopy = minOf(count, destination.size)
            if (toCopy == 0) return 0
            val start = (head - count + capacity) % capacity
            for (i in 0 until toCopy) {
                destination[i] = buffer[(start + i) % capacity]
            }
            return toCopy
        }
    }

    fun getMin(): Float {
        synchronized(lock) {
            if (count == 0) return 0f
            var min = Float.MAX_VALUE
            for (i in 0 until count) {
                val v = buffer[i]
                if (v < min) min = v
            }
            return if (min == Float.MAX_VALUE) 0f else min
        }
    }

    fun getMax(): Float {
        synchronized(lock) {
            if (count == 0) return 0f
            var max = 0f
            for (i in 0 until count) {
                val v = buffer[i]
                if (v > max) max = v
            }
            return max
        }
    }

    fun getAverage(): Float {
        synchronized(lock) {
            if (count == 0) return 0f
            var sum = 0.0
            for (i in 0 until count) {
                sum += buffer[i]
            }
            return (sum / count).toFloat()
        }
    }

    val size: Int
        get() = synchronized(lock) { count }

    fun clear() {
        synchronized(lock) {
            head = 0
            count = 0
            buffer.fill(0f)
        }
    }
}
