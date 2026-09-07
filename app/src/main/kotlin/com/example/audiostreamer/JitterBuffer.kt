package com.example.audiostreamer

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class JitterBuffer(
    private val slotCount: Int = AudioConfig.JITTER_BUFFER_SLOTS,
    private val packetSize: Int = AudioConfig.PACKET_SIZE,
    private val preRollThreshold: Int = AudioConfig.PRE_ROLL_PACKETS
) {
    private val buffer = Array(slotCount) { ByteArray(packetSize) }
    private val lock = ReentrantLock()
    private val notEmptyCondition = lock.newCondition()

    private var writeIndex = 0
    private var readIndex = 0
    private var availableCount = 0
    private var isBuffering = true
    private var lastSampleLeft: Short = 0
    private var lastSampleRight: Short = 0

    fun write(data: ByteArray, offset: Int, length: Int) {
        if (length != packetSize) return

        lock.withLock {
            if (availableCount >= slotCount) {
                // Buffer overflow: drop oldest slot to maintain low latency
                readIndex = (readIndex + 1) % slotCount
                availableCount--
            }

            System.arraycopy(data, offset, buffer[writeIndex], 0, packetSize)
            writeIndex = (writeIndex + 1) % slotCount
            availableCount++

            if (isBuffering && availableCount >= preRollThreshold) {
                isBuffering = false
            }

            notEmptyCondition.signal()
        }
    }

    /**
     * Reads a chunk into outputBuffer.
     * If buffering or underrun, fills with graceful fade/silence (PLC) to prevent DAC clicks.
     */
    fun read(output: ByteArray): Int {
        lock.withLock {
            if (isBuffering) {
                // Still building pre-roll buffer; output silence
                output.fill(0)
                return packetSize
            }

            if (availableCount > 0) {
                System.arraycopy(buffer[readIndex], 0, output, 0, packetSize)
                readIndex = (readIndex + 1) % slotCount
                availableCount--

                // Cache last samples for concealment if needed
                val lastIdx = packetSize - 4
                lastSampleLeft = ((output[lastIdx].toInt() and 0xFF) or (output[lastIdx + 1].toInt() shl 8)).toShort()
                lastSampleRight = ((output[lastIdx + 2].toInt() and 0xFF) or (output[lastIdx + 3].toInt() shl 8)).toShort()

                return packetSize
            } else {
                // Buffer starvation (underrun): enter pre-roll buffering
                isBuffering = true
                // Graceful fade-to-silence concealment
                var curL = lastSampleLeft.toInt()
                var curR = lastSampleRight.toInt()
                var i = 0
                while (i < packetSize) {
                    curL = (curL * 95) / 100
                    curR = (curR * 95) / 100
                    output[i] = (curL and 0xFF).toByte()
                    output[i + 1] = ((curL shr 8) and 0xFF).toByte()
                    output[i + 2] = (curR and 0xFF).toByte()
                    output[i + 3] = ((curR shr 8) and 0xFF).toByte()
                    i += 4
                }
                lastSampleLeft = 0
                lastSampleRight = 0
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
            lastSampleLeft = 0
            lastSampleRight = 0
        }
    }

    fun getFillLevel(): Int {
        lock.withLock {
            return (availableCount * 100) / slotCount
        }
    }
}
