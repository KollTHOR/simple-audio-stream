package com.example.audiostreamer

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class JitterBuffer(
    private val slotCount: Int = AudioConfig.JITTER_BUFFER_SLOTS,
    private val packetSize: Int = AudioConfig.PACKET_SIZE,
    private val preRollThreshold: Int = AudioConfig.PRE_ROLL_PACKETS,
    private val maxUnderrunFrames: Int = AudioConfig.MAX_UNDERRUN_CONCEAL_FRAMES,
    private val waitTimeoutMs: Long = AudioConfig.RECEIVER_WAIT_TIMEOUT_MS
) {
    private val buffer = Array(slotCount) { ByteArray(packetSize) }
    private val lock = ReentrantLock()
    private val notEmptyCondition = lock.newCondition()

    private var writeIndex = 0
    private var readIndex = 0
    private var availableCount = 0
    private var isBuffering = true
    private var consecutiveUnderruns = 0
    private var lastSampleLeft: Short = 0
    private var lastSampleRight: Short = 0

    fun write(data: ByteArray, offset: Int, length: Int) {
        if (length != packetSize) return

        lock.withLock {
            if (availableCount >= slotCount) {
                // Buffer overflow: drop oldest slot to maintain continuous stream
                readIndex = (readIndex + 1) % slotCount
                availableCount--
            }

            System.arraycopy(data, offset, buffer[writeIndex], 0, packetSize)
            writeIndex = (writeIndex + 1) % slotCount
            availableCount++

            if (isBuffering && availableCount >= preRollThreshold) {
                isBuffering = false
                consecutiveUnderruns = 0
            }

            notEmptyCondition.signal()
        }
    }

    /**
     * Reads a chunk into outputBuffer.
     * Waits on condition if momentary jitter delay occurs.
     * Uses smooth PLC without triggering silence cascades on minor delays.
     */
    fun read(output: ByteArray): Int {
        lock.withLock {
            if (isBuffering) {
                // If buffering, check if pre-roll threshold is reached
                if (availableCount >= preRollThreshold) {
                    isBuffering = false
                    consecutiveUnderruns = 0
                } else {
                    output.fill(0)
                    return packetSize
                }
            }

            // If empty, wait briefly for the next packet to absorb network jitter
            if (availableCount == 0) {
                try {
                    notEmptyCondition.await(waitTimeoutMs, TimeUnit.MILLISECONDS)
                } catch (ignored: InterruptedException) {
                    output.fill(0)
                    return packetSize
                }
            }

            if (availableCount > 0) {
                System.arraycopy(buffer[readIndex], 0, output, 0, packetSize)
                readIndex = (readIndex + 1) % slotCount
                availableCount--
                consecutiveUnderruns = 0

                // Cache last samples for concealment if needed
                val lastIdx = packetSize - 4
                lastSampleLeft = ((output[lastIdx].toInt() and 0xFF) or (output[lastIdx + 1].toInt() shl 8)).toShort()
                lastSampleRight = ((output[lastIdx + 2].toInt() and 0xFF) or (output[lastIdx + 3].toInt() shl 8)).toShort()

                return packetSize
            } else {
                // Still empty after waiting: increment underrun count
                consecutiveUnderruns++
                if (consecutiveUnderruns >= maxUnderrunFrames) {
                    // Sustained drop: enter buffering
                    isBuffering = true
                    lastSampleLeft = 0
                    lastSampleRight = 0
                    output.fill(0)
                    return packetSize
                }

                // Graceful decay concealment for brief network dropout
                var curL = lastSampleLeft.toInt()
                var curR = lastSampleRight.toInt()
                var i = 0
                while (i < packetSize) {
                    curL = (curL * 85) / 100
                    curR = (curR * 85) / 100
                    output[i] = (curL and 0xFF).toByte()
                    output[i + 1] = ((curL shr 8) and 0xFF).toByte()
                    output[i + 2] = (curR and 0xFF).toByte()
                    output[i + 3] = ((curR shr 8) and 0xFF).toByte()
                    i += 4
                }
                lastSampleLeft = curL.toShort()
                lastSampleRight = curR.toShort()
                return packetSize
            }
        }
    }

    fun reset() {
        lock.withLock {
            writeIndex = 0
            readIndex = 0
            availableCount = 0
            isBuffering = true
            consecutiveUnderruns = 0
            lastSampleLeft = 0
            lastSampleRight = 0
            notEmptyCondition.signalAll()
        }
    }

    fun getFillLevel(): Int {
        lock.withLock {
            return (availableCount * 100) / slotCount
        }
    }

    fun getAvailableCount(): Int {
        lock.withLock {
            return availableCount
        }
    }

    fun getSlotCount(): Int = slotCount
}
