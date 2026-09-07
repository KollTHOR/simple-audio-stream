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

    private var availableCount = 0
    private var isBuffering = true
    private var consecutiveUnderruns = 0
    private var lastSampleLeft16: Short = 0
    private var lastSampleRight16: Short = 0
    private var lastSampleLeft24: Int = 0
    private var lastSampleRight24: Int = 0
    private var lastPacketSize: Int = AudioConfig.PACKET_SIZE_16BIT
    private var smoothBufferFill: Float = preRollThreshold.toFloat()
    private var isTransmitterSilent = false

    private val isSlotFilled = BooleanArray(maxSlots)
    private val slotSeq = IntArray(maxSlots) { -1 }
    private var expectedReadSeq = -1
    private var syntheticSeq = 0
    private var packetsSinceCatchUp = 0

    private fun seqDiff(s1: Int, s2: Int): Int {
        val diff = (s1 - s2) and 0xFFFF
        return if (diff > 32767) diff - 65536 else diff
    }

    private fun dropOldestSlot() {
        if (expectedReadSeq == -1) return
        var checked = 0
        while (checked < maxSlots) {
            val slot = expectedReadSeq and (maxSlots - 1)
            val wasFilled = isSlotFilled[slot] && slotSeq[slot] == expectedReadSeq
            isSlotFilled[slot] = false
            slotSeq[slot] = -1
            expectedReadSeq = (expectedReadSeq + 1) and 0xFFFF
            if (wasFilled) {
                if (availableCount > 0) availableCount--
                break
            }
            checked++
        }
    }

    fun onSilenceHeartbeat() {
        lock.withLock {
            isTransmitterSilent = true
            consecutiveUnderruns = 0
            notEmptyCondition.signal()
        }
    }

    fun setProfile(profile: String) {
        lock.withLock {
            if (currentProfile == profile) return
            currentProfile = profile
            slotCount = AudioConfig.getJitterBufferSlots(profile)
            preRollThreshold = AudioConfig.getPreRollPackets(profile)
            maxUnderrunFrames = AudioConfig.getMaxUnderrunFrames(profile)
            waitTimeoutMs = AudioConfig.getReceiverWaitTimeoutMs(profile)
            targetWatermarkSlots = AudioConfig.getTargetWatermarkSlots(profile)
            smoothBufferFill = preRollThreshold.toFloat()

            while (availableCount > slotCount) {
                dropOldestSlot()
            }
        }
    }

    fun write(sequence: Int, data: ByteArray, offset: Int, length: Int) {
        if (length <= 0 || length > AudioConfig.MAX_PACKET_SIZE) return

        lock.withLock {
            if (isTransmitterSilent) {
                isTransmitterSilent = false
                expectedReadSeq = sequence
                isBuffering = false
                availableCount = 0
                for (i in 0 until maxSlots) {
                    isSlotFilled[i] = false
                    slotSeq[i] = -1
                }
            } else if (expectedReadSeq == -1 || kotlin.math.abs(seqDiff(sequence, expectedReadSeq)) > maxSlots / 2) {
                expectedReadSeq = sequence
                isBuffering = true
                availableCount = 0
                for (i in 0 until maxSlots) {
                    isSlotFilled[i] = false
                    slotSeq[i] = -1
                }
            }

            val diff = seqDiff(sequence, expectedReadSeq)
            if (diff < 0) {
                // Outdated packet already passed playback point
                return
            }
            if (diff >= maxSlots) {
                // Sequence leap / reconnection
                expectedReadSeq = sequence
                isBuffering = true
                availableCount = 0
                for (i in 0 until maxSlots) {
                    isSlotFilled[i] = false
                    slotSeq[i] = -1
                }
            }

            val slot = sequence and (maxSlots - 1)
            if (isSlotFilled[slot] && slotSeq[slot] == sequence) {
                // Duplicate packet
                return
            }

            while (availableCount >= slotCount) {
                dropOldestSlot()
            }

            System.arraycopy(data, offset, buffer[slot], 0, length)
            packetLengths[slot] = length
            slotSeq[slot] = sequence
            if (!isSlotFilled[slot]) {
                isSlotFilled[slot] = true
                availableCount++
            }

            smoothBufferFill = smoothBufferFill * 0.998f + availableCount * 0.002f

            // Smooth latency catch-up for Low Latency mode:
            // Absorb momentary Wi-Fi aggregation bursts without dropping audio.
            // Only drop if buffer has sustained backlog beyond target watermark, and at most 1 packet per 100 packets (~500ms).
            if (currentProfile == AudioConfig.PROFILE_LOW_LATENCY && smoothBufferFill > targetWatermarkSlots + 6) {
                packetsSinceCatchUp++
                if (packetsSinceCatchUp >= 100) {
                    dropOldestSlot()
                    packetsSinceCatchUp = 0
                    smoothBufferFill -= 1.0f
                }
            } else {
                packetsSinceCatchUp = 0
            }

            if (isBuffering && availableCount >= preRollThreshold) {
                isBuffering = false
                consecutiveUnderruns = 0
            }

            notEmptyCondition.signal()
        }
    }

    fun write(data: ByteArray, offset: Int, length: Int) {
        val s = syntheticSeq
        syntheticSeq = (syntheticSeq + 1) and 0xFFFF
        write(s, data, offset, length)
    }

    fun recoverFecPacket(
        baseSeq: Int,
        blockSize: Int,
        parityPayload: ByteArray,
        parityOffset: Int,
        parityLen: Int
    ): Boolean {
        if (blockSize !in 2..16 || parityLen <= 0 || parityLen > AudioConfig.MAX_PACKET_SIZE) {
            return false
        }

        lock.withLock {
            if (expectedReadSeq == -1) return false

            var missingSeq = -1
            var missingCount = 0
            var maxLen = parityLen

            for (i in 0 until blockSize) {
                val seq = (baseSeq + i) and 0xFFFF
                if (seqDiff(seq, expectedReadSeq) < 0) {
                    // Already played, cannot reconstruct for future playback
                    return false
                }
                val slot = seq and (maxSlots - 1)
                if (isSlotFilled[slot] && slotSeq[slot] == seq) {
                    val len = packetLengths[slot]
                    if (len > maxLen) maxLen = len
                } else {
                    missingSeq = seq
                    missingCount++
                }
            }

            // Exactly 1 dropped packet can be reconstructed losslessly
            if (missingCount == 1 && missingSeq != -1) {
                val slot = missingSeq and (maxSlots - 1)
                val recovered = buffer[slot]
                System.arraycopy(parityPayload, parityOffset, recovered, 0, parityLen)
                if (maxLen > parityLen) {
                    recovered.fill(0, parityLen, maxLen)
                }

                for (i in 0 until blockSize) {
                    val seq = (baseSeq + i) and 0xFFFF
                    if (seq == missingSeq) continue
                    val sSlot = seq and (maxSlots - 1)
                    if (isSlotFilled[sSlot] && slotSeq[sSlot] == seq) {
                        val pData = buffer[sSlot]
                        val pLen = packetLengths[sSlot]
                        val xorLen = minOf(pLen, maxLen)
                        for (b in 0 until xorLen) {
                            recovered[b] = (recovered[b].toInt() xor pData[b].toInt()).toByte()
                        }
                    }
                }

                packetLengths[slot] = maxLen
                slotSeq[slot] = missingSeq
                if (!isSlotFilled[slot]) {
                    isSlotFilled[slot] = true
                    availableCount++
                }
                notEmptyCondition.signal()
                return true
            }

            return false
        }
    }

    /**
     * Reads a chunk into outputBuffer.
     * Waits on condition if momentary jitter delay occurs.
     * Uses smooth PLC without triggering silence cascades on minor delays.
     */
    fun read(output: ByteArray): Int {
        lock.withLock {
            if (expectedReadSeq == -1) {
                val fillLen = minOf(output.size, lastPacketSize)
                output.fill(0, 0, fillLen)
                return fillLen
            }

            if (isBuffering) {
                if (availableCount >= preRollThreshold) {
                    isBuffering = false
                    consecutiveUnderruns = 0
                } else {
                    val fillLen = minOf(output.size, lastPacketSize)
                    output.fill(0, 0, fillLen)
                    return fillLen
                }
            }

            var slot = expectedReadSeq and (maxSlots - 1)
            var hasPacket = isSlotFilled[slot] && (slotSeq[slot] == expectedReadSeq)

            if (!hasPacket) {
                if (isTransmitterSilent) {
                    val fillLen = minOf(output.size, lastPacketSize)
                    output.fill(0, 0, fillLen)
                    return fillLen
                }

                var nanosLeft = TimeUnit.MILLISECONDS.toNanos(waitTimeoutMs)
                try {
                    while (!hasPacket && nanosLeft > 0L) {
                        nanosLeft = notEmptyCondition.awaitNanos(nanosLeft)
                        slot = expectedReadSeq and (maxSlots - 1)
                        hasPacket = isSlotFilled[slot] && (slotSeq[slot] == expectedReadSeq)
                    }
                } catch (ignored: InterruptedException) {
                    val fillLen = minOf(output.size, lastPacketSize)
                    output.fill(0, 0, fillLen)
                    return fillLen
                }
            }

            if (hasPacket) {
                val len = packetLengths[slot]
                System.arraycopy(buffer[slot], 0, output, 0, len)
                isSlotFilled[slot] = false
                slotSeq[slot] = -1
                if (availableCount > 0) availableCount--
                expectedReadSeq = (expectedReadSeq + 1) and 0xFFFF
                consecutiveUnderruns = 0
                lastPacketSize = len

                // Audio Clock Drift Management:
                // Smooth zero-crossing micro-resampling (1 frame = 20.8 microseconds)
                // Prevents long-term buffer accumulation or drainage without clicks or pitch wobble
                if (currentProfile == AudioConfig.PROFILE_MUSIC) {
                    val driftDelta = smoothBufferFill - targetWatermarkSlots
                    if (driftDelta > 16f && len >= 12) {
                        applyZeroCrossingFrameDrop(output, len)
                        smoothBufferFill -= 0.5f
                    } else if (driftDelta < -16f && len >= 12) {
                        applyZeroCrossingFrameDuplicate(output, len)
                        smoothBufferFill += 0.5f
                    }
                }

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
                // Still empty after waiting: increment underrun count and advance expectedReadSeq
                expectedReadSeq = (expectedReadSeq + 1) and 0xFFFF
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
            expectedReadSeq = -1
            syntheticSeq = 0
            availableCount = 0
            isBuffering = true
            consecutiveUnderruns = 0
            for (i in 0 until maxSlots) {
                isSlotFilled[i] = false
                slotSeq[i] = -1
            }
            lastSampleLeft16 = 0
            lastSampleRight16 = 0
            lastSampleLeft24 = 0
            lastSampleRight24 = 0
            packetsSinceCatchUp = 0
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

    private fun applyZeroCrossingFrameDrop(output: ByteArray, len: Int) {
        val is24 = (len == AudioConfig.PACKET_SIZE_24BIT)
        val frameBytes = if (is24) 6 else 4
        val totalFrames = len / frameBytes
        val searchStart = totalFrames / 4
        val searchEnd = (3 * totalFrames) / 4

        var bestFrame = searchStart
        var minAbs = Int.MAX_VALUE

        for (f in searchStart until searchEnd) {
            val idx = f * frameBytes
            val sampleL = if (is24) {
                val raw = (output[idx].toInt() and 0xFF) or
                    ((output[idx + 1].toInt() and 0xFF) shl 8) or
                    ((output[idx + 2].toInt() and 0xFF) shl 16)
                if (raw and 0x800000 != 0) raw or 0xFF000000.toInt() else raw
            } else {
                ((output[idx].toInt() and 0xFF) or (output[idx + 1].toInt() shl 8)).toShort().toInt()
            }
            val absVal = kotlin.math.abs(sampleL)
            if (absVal < minAbs) {
                minAbs = absVal
                bestFrame = f
                if (absVal == 0) break
            }
        }

        val dropIdx = bestFrame * frameBytes
        val remaining = len - dropIdx - frameBytes
        if (remaining > 0) {
            System.arraycopy(output, dropIdx + frameBytes, output, dropIdx, remaining)
            System.arraycopy(output, len - 2 * frameBytes, output, len - frameBytes, frameBytes)
        }
    }

    private fun applyZeroCrossingFrameDuplicate(output: ByteArray, len: Int) {
        val is24 = (len == AudioConfig.PACKET_SIZE_24BIT)
        val frameBytes = if (is24) 6 else 4
        val totalFrames = len / frameBytes
        val searchStart = totalFrames / 4
        val searchEnd = (3 * totalFrames) / 4

        var bestFrame = searchStart
        var minAbs = Int.MAX_VALUE

        for (f in searchStart until searchEnd) {
            val idx = f * frameBytes
            val sampleL = if (is24) {
                val raw = (output[idx].toInt() and 0xFF) or
                    ((output[idx + 1].toInt() and 0xFF) shl 8) or
                    ((output[idx + 2].toInt() and 0xFF) shl 16)
                if (raw and 0x800000 != 0) raw or 0xFF000000.toInt() else raw
            } else {
                ((output[idx].toInt() and 0xFF) or (output[idx + 1].toInt() shl 8)).toShort().toInt()
            }
            val absVal = kotlin.math.abs(sampleL)
            if (absVal < minAbs) {
                minAbs = absVal
                bestFrame = f
                if (absVal == 0) break
            }
        }

        val insertIdx = bestFrame * frameBytes
        val shiftLen = len - insertIdx - frameBytes
        if (shiftLen > 0) {
            System.arraycopy(output, insertIdx, output, insertIdx + frameBytes, shiftLen)
        }
    }

    fun getSlotCount(): Int = slotCount
    fun getCurrentProfile(): String = currentProfile
}
