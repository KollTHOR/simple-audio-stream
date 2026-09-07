package com.example.audiostreamer

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class JitterBuffer(
    initialProfile: String = AudioConfig.PROFILE_MUSIC
) {
    private val maxSlots = AudioConfig.MUSIC_JITTER_BUFFER_SLOTS
    private val buffer = Array(maxSlots) { ByteArray(AudioConfig.MAX_PACKET_SIZE) }
    private val packetLengths = IntArray(maxSlots) { AudioConfig.PACKET_SIZE_16BIT }
    private val lock = ReentrantLock()
    private val notEmptyCondition = lock.newCondition()

    private var currentProfile: String = initialProfile
    private var slotCount: Int = AudioConfig.getJitterBufferSlots(initialProfile)
    private var preRollThreshold: Int = AudioConfig.getPreRollPackets(initialProfile)
    private var maxUnderrunFrames: Int = AudioConfig.getMaxUnderrunFrames(initialProfile)
    private var waitTimeoutMs: Long = AudioConfig.getReceiverWaitTimeoutMs(initialProfile)
    private var targetWatermarkSlots: Int = AudioConfig.getTargetWatermarkSlots(initialProfile)

    private var writeIndex = 0
    private var readIndex = 0
    private var availableCount = 0
    private var isBuffering = true
    private var consecutiveUnderruns = 0
    private var lastSampleLeft16: Short = 0
    private var lastSampleRight16: Short = 0
    private var lastSampleLeft24: Int = 0
    private var lastSampleRight24: Int = 0
    private var lastPacketSize: Int = AudioConfig.PACKET_SIZE_16BIT

    fun setProfile(profile: String) {
        lock.withLock {
            if (currentProfile == profile) return
            currentProfile = profile
            slotCount = AudioConfig.getJitterBufferSlots(profile)
            preRollThreshold = AudioConfig.getPreRollPackets(profile)
            maxUnderrunFrames = AudioConfig.getMaxUnderrunFrames(profile)
            waitTimeoutMs = AudioConfig.getReceiverWaitTimeoutMs(profile)
            targetWatermarkSlots = AudioConfig.getTargetWatermarkSlots(profile)

            while (availableCount > slotCount) {
                readIndex = (readIndex + 1) % maxSlots
                availableCount--
            }
        }
    }

    fun write(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0 || length > AudioConfig.MAX_PACKET_SIZE) return

        lock.withLock {
            // Gentle latency catch-up ONLY for low latency mode (when targetWatermarkSlots < slotCount):
            // If buffer accumulated noticeable backlog (> targetWatermarkSlots + 4),
            // drop at most 1 oldest packet per write to smoothly converge without stutter.
            if (targetWatermarkSlots < slotCount && availableCount > targetWatermarkSlots + 4) {
                readIndex = (readIndex + 1) % maxSlots
                availableCount--
            }

            // Hard capacity clamp: buffer overflow
            if (availableCount >= slotCount) {
                readIndex = (readIndex + 1) % maxSlots
                availableCount--
            }

            System.arraycopy(data, offset, buffer[writeIndex], 0, length)
            packetLengths[writeIndex] = length
            writeIndex = (writeIndex + 1) % maxSlots
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
                    val fillLen = minOf(output.size, lastPacketSize)
                    output.fill(0, 0, fillLen)
                    return fillLen
                }
            }

            // If empty, wait briefly for the next packet to absorb network jitter
            if (availableCount == 0) {
                try {
                    notEmptyCondition.await(waitTimeoutMs, TimeUnit.MILLISECONDS)
                } catch (ignored: InterruptedException) {
                    val fillLen = minOf(output.size, lastPacketSize)
                    output.fill(0, 0, fillLen)
                    return fillLen
                }
            }

            if (availableCount > 0) {
                val len = packetLengths[readIndex]
                System.arraycopy(buffer[readIndex], 0, output, 0, len)
                readIndex = (readIndex + 1) % maxSlots
                availableCount--
                consecutiveUnderruns = 0
                lastPacketSize = len

                // Cache last samples for concealment if needed
                if (len == AudioConfig.PACKET_SIZE_24BIT && len >= 6) {
                    val idxL = len - 6
                    val idxR = len - 3
                    lastSampleLeft24 = (output[idxL].toInt() and 0xFF) or ((output[idxL + 1].toInt() and 0xFF) shl 8) or (output[idxL + 2].toInt() shl 16)
                    lastSampleRight24 = (output[idxR].toInt() and 0xFF) or ((output[idxR + 1].toInt() and 0xFF) shl 8) or (output[idxR + 2].toInt() shl 16)
                } else if (len >= 4) {
                    val lastIdx = len - 4
                    lastSampleLeft16 = ((output[lastIdx].toInt() and 0xFF) or (output[lastIdx + 1].toInt() shl 8)).toShort()
                    lastSampleRight16 = ((output[lastIdx + 2].toInt() and 0xFF) or (output[lastIdx + 3].toInt() shl 8)).toShort()
                }

                return len
            } else {
                // Still empty after waiting: increment underrun count
                consecutiveUnderruns++
                val len = minOf(output.size, lastPacketSize)
                if (consecutiveUnderruns >= maxUnderrunFrames) {
                    // Sustained drop: enter buffering
                    isBuffering = true
                    lastSampleLeft16 = 0
                    lastSampleRight16 = 0
                    lastSampleLeft24 = 0
                    lastSampleRight24 = 0
                    output.fill(0, 0, len)
                    return len
                }

                // Graceful decay concealment for brief network dropout
                if (len == AudioConfig.PACKET_SIZE_24BIT) {
                    var curL = lastSampleLeft24
                    var curR = lastSampleRight24
                    var i = 0
                    while (i <= len - 6) {
                        curL = (curL * 85) / 100
                        curR = (curR * 85) / 100
                        output[i] = (curL and 0xFF).toByte()
                        output[i + 1] = ((curL shr 8) and 0xFF).toByte()
                        output[i + 2] = ((curL shr 16) and 0xFF).toByte()
                        output[i + 3] = (curR and 0xFF).toByte()
                        output[i + 4] = ((curR shr 8) and 0xFF).toByte()
                        output[i + 5] = ((curR shr 16) and 0xFF).toByte()
                        i += 6
                    }
                    lastSampleLeft24 = curL
                    lastSampleRight24 = curR
                } else {
                    var curL = lastSampleLeft16.toInt()
                    var curR = lastSampleRight16.toInt()
                    var i = 0
                    while (i <= len - 4) {
                        curL = (curL * 85) / 100
                        curR = (curR * 85) / 100
                        output[i] = (curL and 0xFF).toByte()
                        output[i + 1] = ((curL shr 8) and 0xFF).toByte()
                        output[i + 2] = (curR and 0xFF).toByte()
                        output[i + 3] = ((curR shr 8) and 0xFF).toByte()
                        i += 4
                    }
                    lastSampleLeft16 = curL.toShort()
                    lastSampleRight16 = curR.toShort()
                }
                return len
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
            lastSampleLeft16 = 0
            lastSampleRight16 = 0
            lastSampleLeft24 = 0
            lastSampleRight24 = 0
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
    fun getCurrentProfile(): String = currentProfile
}
