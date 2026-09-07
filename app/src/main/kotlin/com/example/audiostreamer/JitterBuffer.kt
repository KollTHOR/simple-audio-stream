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
    private var wasConcealed = false
    private var lastWasAac = false

    // History buffer for robust FEC recovery even if preceding packets were already read
    private val historySize = 32
    private val historyBuffer = Array(historySize) { ByteArray(AudioConfig.MAX_PACKET_SIZE) }
    private val historyLengths = IntArray(historySize)
    private val historySeq = IntArray(historySize) { -1 }

    private val isSlotFilled = BooleanArray(maxSlots)
    private val slotSeq = IntArray(maxSlots) { -1 }
    private var expectedReadSeq = -1
    private var syntheticSeq = 0
    private var packetsSinceCatchUp = 0
    private var packetsSinceDriftAdjust = 0

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

    fun setProfile(profile: String, isAac: Boolean = false) {
        lock.withLock {
            if (currentProfile == profile && (isAac == lastWasAac)) return
            currentProfile = profile
            lastWasAac = isAac
            if (isAac) {
                slotCount = AudioConfig.LOW_LATENCY_AAC_JITTER_BUFFER_SLOTS
                preRollThreshold = AudioConfig.LOW_LATENCY_AAC_PRE_ROLL_PACKETS
                maxUnderrunFrames = AudioConfig.LOW_LATENCY_AAC_MAX_UNDERRUN_FRAMES
                waitTimeoutMs = AudioConfig.LOW_LATENCY_AAC_WAIT_TIMEOUT_MS
                targetWatermarkSlots = AudioConfig.LOW_LATENCY_AAC_TARGET_WATERMARK_SLOTS
            } else {
                slotCount = AudioConfig.getJitterBufferSlots(profile)
                preRollThreshold = AudioConfig.getPreRollPackets(profile)
                maxUnderrunFrames = AudioConfig.getMaxUnderrunFrames(profile)
                waitTimeoutMs = AudioConfig.getReceiverWaitTimeoutMs(profile)
                targetWatermarkSlots = AudioConfig.getTargetWatermarkSlots(profile)
            }
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

            // Keep packet in circular history for robust FEC recovery
            val hSlot = sequence and (historySize - 1)
            System.arraycopy(data, offset, historyBuffer[hSlot], 0, length)
            historyLengths[hSlot] = length
            historySeq[hSlot] = sequence

            smoothBufferFill = smoothBufferFill * 0.998f + availableCount * 0.002f

            // Smooth latency catch-up for extreme network stalls:
            // Zero-crossing micro-resampling in read() handles normal drift and bursts smoothly.
            // Only drop an oldest slot if buffer has sustained severe backlog beyond target watermark + 16 (~140ms extra).
            if (currentProfile == AudioConfig.PROFILE_LOW_LATENCY && smoothBufferFill > targetWatermarkSlots + 16) {
                packetsSinceCatchUp++
                if (packetsSinceCatchUp >= 150) {
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
                val slot = seq and (maxSlots - 1)
                val hSlot = seq and (historySize - 1)

                val inBuffer = isSlotFilled[slot] && slotSeq[slot] == seq
                val inHistory = historySeq[hSlot] == seq

                if (inBuffer) {
                    val len = packetLengths[slot]
                    if (len > maxLen) maxLen = len
                } else if (inHistory) {
                    val len = historyLengths[hSlot]
                    if (len > maxLen) maxLen = len
                } else {
                    missingSeq = seq
                    missingCount++
                }
            }

            // Exactly 1 dropped packet can be reconstructed losslessly
            if (missingCount == 1 && missingSeq != -1) {
                // If missing packet has already passed playback point, no need to reconstruct
                if (seqDiff(missingSeq, expectedReadSeq) < 0) {
                    return false
                }

                val slot = missingSeq and (maxSlots - 1)
                val recovered = buffer[slot]
                System.arraycopy(parityPayload, parityOffset, recovered, 0, parityLen)
                if (maxLen > parityLen) {
                    recovered.fill(0, parityLen, maxLen)
                }

                for (i in 0 until blockSize) {
                    val seq = (baseSeq + i) and 0xFFFF
                    if (seq == missingSeq) continue
                    val slotIdx = seq and (maxSlots - 1)
                    val hIdx = seq and (historySize - 1)

                    val pData: ByteArray
                    val pLen: Int
                    if (isSlotFilled[slotIdx] && slotSeq[slotIdx] == seq) {
                        pData = buffer[slotIdx]
                        pLen = packetLengths[slotIdx]
                    } else if (historySeq[hIdx] == seq) {
                        pData = historyBuffer[hIdx]
                        pLen = historyLengths[hIdx]
                    } else {
                        // Data missing from both buffer and history: cannot recover safely
                        return false
                    }

                    val xorLen = minOf(pLen, maxLen)
                    for (b in 0 until xorLen) {
                        recovered[b] = (recovered[b].toInt() xor pData[b].toInt()).toByte()
                    }
                }

                packetLengths[slot] = maxLen
                slotSeq[slot] = missingSeq
                if (!isSlotFilled[slot]) {
                    isSlotFilled[slot] = true
                    availableCount++
                }

                // Also save recovered packet into history
                val hSlot = missingSeq and (historySize - 1)
                System.arraycopy(recovered, 0, historyBuffer[hSlot], 0, maxLen)
                historyLengths[hSlot] = maxLen
                historySeq[hSlot] = missingSeq

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

                val isAdts = (len >= 7 && (output[0].toInt() and 0xFF) == 0xFF && (output[1].toInt() and 0xF0) == 0xF0)
                lastWasAac = isAdts

                if (isAdts) {
                    wasConcealed = false
                    // Never perform PCM micro-resampling or sample crossfade on compressed ADTS bitstreams
                    return len
                }

                if (wasConcealed) {
                    wasConcealed = false
                    val is24 = (len == AudioConfig.PACKET_SIZE_24BIT_44K || len == AudioConfig.PACKET_SIZE_24BIT_48K)
                    val frameBytes = if (is24) 6 else 4
                    val totalFrames = len / frameBytes
                    val fadeFrames = minOf(20, totalFrames)
                    if (is24) {
                        for (f in 0 until fadeFrames) {
                            val factor = (f + 1).toFloat() / fadeFrames
                            val i = f * 6
                            val rawL = (output[i].toInt() and 0xFF) or ((output[i + 1].toInt() and 0xFF) shl 8) or ((output[i + 2].toInt() and 0xFF) shl 16)
                            var sL = if (rawL and 0x800000 != 0) rawL or 0xFF000000.toInt() else rawL
                            val rawR = (output[i + 3].toInt() and 0xFF) or ((output[i + 4].toInt() and 0xFF) shl 8) or ((output[i + 5].toInt() and 0xFF) shl 16)
                            var sR = if (rawR and 0x800000 != 0) rawR or 0xFF000000.toInt() else rawR
                            sL = (sL * factor).toInt()
                            sR = (sR * factor).toInt()
                            output[i] = (sL and 0xFF).toByte()
                            output[i + 1] = ((sL shr 8) and 0xFF).toByte()
                            output[i + 2] = ((sL shr 16) and 0xFF).toByte()
                            output[i + 3] = (sR and 0xFF).toByte()
                            output[i + 4] = ((sR shr 8) and 0xFF).toByte()
                            output[i + 5] = ((sR shr 16) and 0xFF).toByte()
                        }
                    } else {
                        for (f in 0 until fadeFrames) {
                            val factor = (f + 1).toFloat() / fadeFrames
                            val i = f * 4
                            var sL = ((output[i].toInt() and 0xFF) or (output[i + 1].toInt() shl 8)).toShort().toInt()
                            var sR = ((output[i + 2].toInt() and 0xFF) or (output[i + 3].toInt() shl 8)).toShort().toInt()
                            sL = (sL * factor).toInt()
                            sR = (sR * factor).toInt()
                            output[i] = (sL and 0xFF).toByte()
                            output[i + 1] = ((sL shr 8) and 0xFF).toByte()
                            output[i + 2] = (sR and 0xFF).toByte()
                            output[i + 3] = ((sR shr 8) and 0xFF).toByte()
                        }
                    }
                }

                // Audio Clock Drift Management:
                // Smooth zero-crossing micro-resampling (1 frame = 20-22 microseconds)
                // Rate-limited to prevent bass modulation comb filtering while holding tight sync
                packetsSinceDriftAdjust++
                val driftDelta = smoothBufferFill - targetWatermarkSlots
                val isLowLat = (currentProfile == AudioConfig.PROFILE_LOW_LATENCY || currentProfile == AudioConfig.PROFILE_VIDEO)
                val isBalanced = (currentProfile == AudioConfig.PROFILE_BALANCED)
                val driftThreshold = if (isLowLat) 8f else if (isBalanced) 12f else 16f
                val minInterval = if (isLowLat) 300 else if (isBalanced) 400 else 600 // At most once every 1.5 - 3 seconds
                if (packetsSinceDriftAdjust >= minInterval && len >= 12) {
                    if (driftDelta > driftThreshold) {
                        applyZeroCrossingFrameDrop(output, len)
                        packetsSinceDriftAdjust = 0
                        smoothBufferFill -= 0.5f
                    } else if (driftDelta < -driftThreshold) {
                        applyZeroCrossingFrameDuplicate(output, len)
                        packetsSinceDriftAdjust = 0
                        smoothBufferFill += 0.5f
                    }
                }

                // Cache last samples for smooth concealment if needed
                val is24Sample = (len == AudioConfig.PACKET_SIZE_24BIT_44K || len == AudioConfig.PACKET_SIZE_24BIT_48K)
                if (is24Sample && len >= 6) {
                    val idxL = len - 6
                    val idxR = len - 3
                    val rawL = (output[idxL].toInt() and 0xFF) or ((output[idxL + 1].toInt() and 0xFF) shl 8) or ((output[idxL + 2].toInt() and 0xFF) shl 16)
                    lastSampleLeft24 = if (rawL and 0x800000 != 0) rawL or 0xFF000000.toInt() else rawL
                    val rawR = (output[idxR].toInt() and 0xFF) or ((output[idxR + 1].toInt() and 0xFF) shl 8) or ((output[idxR + 2].toInt() and 0xFF) shl 16)
                    lastSampleRight24 = if (rawR and 0x800000 != 0) rawR or 0xFF000000.toInt() else rawR
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
                if (consecutiveUnderruns >= maxUnderrunFrames || lastWasAac) {
                    // Sustained drop: enter buffering
                    if (consecutiveUnderruns >= maxUnderrunFrames) {
                        isBuffering = true
                    }
                    wasConcealed = true
                    lastSampleLeft16 = 0
                    lastSampleRight16 = 0
                    lastSampleLeft24 = 0
                    lastSampleRight24 = 0
                    output.fill(0, 0, len)
                    return len
                }

                wasConcealed = true
                val is24Conceal = (len == AudioConfig.PACKET_SIZE_24BIT_44K || len == AudioConfig.PACKET_SIZE_24BIT_48K)
                if (is24Conceal) {
                    val numFrames = len / 6
                    val initL = lastSampleLeft24
                    val initR = lastSampleRight24
                    for (f in 0 until numFrames) {
                        val factor = (numFrames - f).toFloat() / numFrames
                        val sL = (initL * factor).toInt()
                        val sR = (initR * factor).toInt()
                        val i = f * 6
                        output[i] = (sL and 0xFF).toByte()
                        output[i + 1] = ((sL shr 8) and 0xFF).toByte()
                        output[i + 2] = ((sL shr 16) and 0xFF).toByte()
                        output[i + 3] = (sR and 0xFF).toByte()
                        output[i + 4] = ((sR shr 8) and 0xFF).toByte()
                        output[i + 5] = ((sR shr 16) and 0xFF).toByte()
                    }
                    lastSampleLeft24 = 0
                    lastSampleRight24 = 0
                } else {
                    val numFrames = len / 4
                    val initL = lastSampleLeft16.toInt()
                    val initR = lastSampleRight16.toInt()
                    for (f in 0 until numFrames) {
                        val factor = (numFrames - f).toFloat() / numFrames
                        val sL = (initL * factor).toInt()
                        val sR = (initR * factor).toInt()
                        val i = f * 4
                        output[i] = (sL and 0xFF).toByte()
                        output[i + 1] = ((sL shr 8) and 0xFF).toByte()
                        output[i + 2] = (sR and 0xFF).toByte()
                        output[i + 3] = ((sR shr 8) and 0xFF).toByte()
                    }
                    lastSampleLeft16 = 0
                    lastSampleRight16 = 0
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
            packetsSinceDriftAdjust = 0
            lastWasAac = false
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
        val is24 = (len == AudioConfig.PACKET_SIZE_24BIT_48K || len == AudioConfig.PACKET_SIZE_24BIT_44K)
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
            val sampleR = if (is24) {
                val raw = (output[idx + 3].toInt() and 0xFF) or
                    ((output[idx + 4].toInt() and 0xFF) shl 8) or
                    ((output[idx + 5].toInt() and 0xFF) shl 16)
                if (raw and 0x800000 != 0) raw or 0xFF000000.toInt() else raw
            } else {
                ((output[idx + 2].toInt() and 0xFF) or (output[idx + 3].toInt() shl 8)).toShort().toInt()
            }
            val absVal = kotlin.math.abs(sampleL) + kotlin.math.abs(sampleR)
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
        val is24 = (len == AudioConfig.PACKET_SIZE_24BIT_48K || len == AudioConfig.PACKET_SIZE_24BIT_44K)
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
            val sampleR = if (is24) {
                val raw = (output[idx + 3].toInt() and 0xFF) or
                    ((output[idx + 4].toInt() and 0xFF) shl 8) or
                    ((output[idx + 5].toInt() and 0xFF) shl 16)
                if (raw and 0x800000 != 0) raw or 0xFF000000.toInt() else raw
            } else {
                ((output[idx + 2].toInt() and 0xFF) or (output[idx + 3].toInt() shl 8)).toShort().toInt()
            }
            val absVal = kotlin.math.abs(sampleL) + kotlin.math.abs(sampleR)
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
