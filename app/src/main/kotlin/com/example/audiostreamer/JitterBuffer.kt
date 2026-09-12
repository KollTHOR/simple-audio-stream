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
    private var isCompressedStream = false
    private var is24BitStream = false
    private var packetDurationMs: Float = 5.0f

    fun set24Bit(is24: Boolean) {
        lock.withLock {
            is24BitStream = is24
        }
    }

    private var lastSampleRate: Int = AudioConfig.SAMPLE_RATE_48000

    fun setSampleRate(sampleRate: Int) {
        lock.withLock {
            lastSampleRate = sampleRate
            packetDurationMs = AudioConfig.getPacketDurationMs(sampleRate)
        }
    }

    // RFC 3550 Inter-Arrival Jitter Estimation & Floating Target Watermark
    private var lastArrivalNanos: Long = 0L
    private var lastPacketSeq: Int = -1
    private var lastReceivedTimestamp: Long = -1L
    private var expectedReadTimestamp: Long = -1L
    private var estimatedJitterMs: Double = 0.0
    private var targetWatermarkMs: Float = if (initialProfile == AudioConfig.PROFILE_AUTO) 50.0f else 40.0f
    private var cleanPlaybackFramesCount: Int = 0

    // History buffer for robust FEC recovery even if preceding packets were already read
    private val historySize = 32
    private val historyBuffer = Array(historySize) { ByteArray(AudioConfig.MAX_PACKET_SIZE) }
    private val historyLengths = IntArray(historySize)
    private val historySeq = IntArray(historySize) { -1 }
    private val historyTimestamp = LongArray(historySize) { -1L }

    private val isSlotFilled = BooleanArray(maxSlots)
    private val slotSeq = IntArray(maxSlots) { -1 }
    private val slotTimestamp = LongArray(maxSlots) { -1L }
    private var expectedReadSeq = -1
    private var syntheticSeq = 0
    private var syntheticTimestamp = 0L
    private var packetsSinceCatchUp = 0
    private var packetsSinceDriftAdjust = 0

    private fun seqDiff(s1: Int, s2: Int): Int {
        val diff = (s1 - s2) and 0xFFFF
        return if (diff > 32767) diff - 65536 else diff
    }

    fun calculateFramesForPayload(length: Int): Int {
        if (isCompressedStream) {
            return if (length in 1..400) 960 else 1024
        }
        val bytesPerSample = if (is24BitStream) 3 else 2
        val bytesPerFrame = AudioConfig.CHANNELS * bytesPerSample
        if (length > 0 && bytesPerFrame > 0) {
            return length / bytesPerFrame
        }
        val rate = if (lastSampleRate > 0) lastSampleRate else AudioConfig.SAMPLE_RATE_48000
        return AudioConfig.getFramesPerPacket(rate)
    }

    private fun dropOldestSlot() {
        if (expectedReadSeq == -1) return
        var checked = 0
        while (checked < maxSlots) {
            val slot = expectedReadSeq and (maxSlots - 1)
            val wasFilled = isSlotFilled[slot] && slotSeq[slot] == expectedReadSeq
            val len = packetLengths[slot]
            val ts = slotTimestamp[slot]
            isSlotFilled[slot] = false
            slotSeq[slot] = -1
            slotTimestamp[slot] = -1L
            expectedReadSeq = (expectedReadSeq + 1) and 0xFFFF
            if (wasFilled) {
                val frames = calculateFramesForPayload(len)
                if (ts >= 0L) {
                    expectedReadTimestamp = ts + frames
                } else if (expectedReadTimestamp >= 0L) {
                    expectedReadTimestamp += frames
                }
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

    fun applyConfiguration(config: NegotiatedStreamConfig) {
        lock.withLock {
            is24BitStream = config.is24Bit
            lastSampleRate = config.sampleRateHz
            packetDurationMs = config.packetDurationMs
            isCompressedStream = config.isCompressed
            currentProfile = config.transportProfile.latencyTarget.name

            val params = config.transportProfile.jitter
            slotCount = params.slotCount
            preRollThreshold = params.preRollPackets
            maxUnderrunFrames = params.maxUnderrunFrames
            waitTimeoutMs = params.waitTimeoutMs
            targetWatermarkSlots = params.targetWatermarkSlots
            targetWatermarkMs = params.targetWatermarkMs

            cleanPlaybackFramesCount = 0
            smoothBufferFill = preRollThreshold.toFloat()

            while (availableCount > slotCount) {
                dropOldestSlot()
            }
            AppLogger.i("JitterBuffer", "Applied negotiated config: format=${config.sampleRateHz}Hz/${if (config.is24Bit) 24 else 16}b, codec=${config.codec.name}, profile=${config.transportProfile.latencyTarget.name}, slots=$slotCount")
        }
    }

    fun setProfile(profile: String, isCompressed: Boolean = false) {
        lock.withLock {
            if (currentProfile == profile && (isCompressed == isCompressedStream)) return
            currentProfile = profile
            isCompressedStream = isCompressed
            if (isCompressed) {
                slotCount = AudioConfig.LOW_LATENCY_JITTER_BUFFER_SLOTS
                preRollThreshold = AudioConfig.LOW_LATENCY_PRE_ROLL_PACKETS
                maxUnderrunFrames = AudioConfig.LOW_LATENCY_MAX_UNDERRUN_FRAMES
                waitTimeoutMs = AudioConfig.LOW_LATENCY_WAIT_TIMEOUT_MS
                targetWatermarkSlots = AudioConfig.LOW_LATENCY_TARGET_WATERMARK_SLOTS
                targetWatermarkMs = 40.0f
            } else {
                slotCount = AudioConfig.getJitterBufferSlots(profile)
                val nominalDuration = packetDurationMs
                val nominalSlots = kotlin.math.ceil(200.0f / nominalDuration).toInt()
                preRollThreshold = if (profile == AudioConfig.PROFILE_AUTO) {
                    kotlin.math.ceil(50.0f / nominalDuration).toInt().coerceIn(2, slotCount / 4)
                } else {
                    nominalSlots.coerceIn(4, slotCount / 4)
                }
                maxUnderrunFrames = AudioConfig.getMaxUnderrunFrames(profile)
                waitTimeoutMs = AudioConfig.getReceiverWaitTimeoutMs(profile)
                targetWatermarkMs = if (profile == AudioConfig.PROFILE_AUTO) 50.0f else 200.0f
                targetWatermarkSlots = kotlin.math.ceil(targetWatermarkMs / nominalDuration).toInt().coerceIn(2, slotCount - 4)
            }
            cleanPlaybackFramesCount = 0
            smoothBufferFill = preRollThreshold.toFloat()

            while (availableCount > slotCount) {
                dropOldestSlot()
            }
            AppLogger.i("JitterBuffer", "Buffer profile updated: profile=$profile, compressed=$isCompressed, slots=$slotCount, preRoll=$preRollThreshold, timeout=${waitTimeoutMs}ms, packetDuration=${packetDurationMs}ms")
        }
    }

    fun write(sequence: Int, timestamp: Long, data: ByteArray, offset: Int, length: Int) {
        if (length <= 0 || length > AudioConfig.MAX_PACKET_SIZE) return

        lock.withLock {
            val nominalFramesPerPacket = calculateFramesForPayload(length)
            val maxTolerableFrameDrift = slotCount * nominalFramesPerPacket

            val isFirstPacket = (expectedReadSeq == -1 || expectedReadTimestamp == -1L)
            val isSequenceReset = (expectedReadSeq != -1 && kotlin.math.abs(seqDiff(sequence, expectedReadSeq)) > slotCount / 2)
            val isBackwardTimestampJump = (expectedReadTimestamp != -1L && timestamp < expectedReadTimestamp - maxTolerableFrameDrift)
            val isForwardTimestampDiscontinuity = (expectedReadTimestamp != -1L && timestamp > expectedReadTimestamp + maxTolerableFrameDrift)

            if (isTransmitterSilent || (isBuffering && availableCount == 0) || isFirstPacket || isSequenceReset || isBackwardTimestampJump || isForwardTimestampDiscontinuity) {
                isTransmitterSilent = false
                wasConcealed = false
                expectedReadSeq = sequence
                expectedReadTimestamp = timestamp
                isBuffering = (preRollThreshold > 1)
                availableCount = 0
                for (i in 0 until maxSlots) {
                    isSlotFilled[i] = false
                    slotSeq[i] = -1
                    slotTimestamp[i] = -1L
                }
            }

            val diff = seqDiff(sequence, expectedReadSeq)
            if (diff < 0) {
                if (isBuffering && diff >= -32) {
                    // During initial pre-roll before playback has started, adjust read cursor
                    // to the earliest packet received to preserve out-of-order startup packets.
                    expectedReadSeq = sequence
                    expectedReadTimestamp = timestamp
                } else {
                    // Outdated packet already passed playback point
                    return
                }
            }
            if (diff >= maxSlots) {
                // Sequence leap / reconnection
                expectedReadSeq = sequence
                expectedReadTimestamp = timestamp
                isBuffering = true
                availableCount = 0
                for (i in 0 until maxSlots) {
                    isSlotFilled[i] = false
                    slotSeq[i] = -1
                    slotTimestamp[i] = -1L
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
            slotTimestamp[slot] = timestamp
            if (!isSlotFilled[slot]) {
                isSlotFilled[slot] = true
                availableCount++
            }

            // Keep packet in circular history for robust FEC recovery
            val hSlot = sequence and (historySize - 1)
            System.arraycopy(data, offset, historyBuffer[hSlot], 0, length)
            historyLengths[hSlot] = length
            historySeq[hSlot] = sequence
            historyTimestamp[hSlot] = timestamp

            smoothBufferFill = smoothBufferFill * 0.998f + availableCount * 0.002f

            // RFC 3550 Inter-Arrival Jitter Estimation using audio timeline
            val nowNanos = System.nanoTime()
            if (lastArrivalNanos > 0L && lastReceivedTimestamp >= 0L && lastPacketSeq != -1) {
                val deltaSeq = seqDiff(sequence, lastPacketSeq)
                if (deltaSeq in 1..50) {
                    val deltaFrames = timestamp - lastReceivedTimestamp
                    val currentRate = if (lastSampleRate > 0) lastSampleRate else AudioConfig.SAMPLE_RATE_48000
                    val sendTimeDeltaMs = if (deltaFrames in 1..100000) {
                        (deltaFrames * 1000.0) / currentRate
                    } else {
                        deltaSeq * (if (isCompressedStream) 20.0 else packetDurationMs.toDouble())
                    }
                    val arrivalDeltaMs = (nowNanos - lastArrivalNanos) / 1_000_000.0
                    val transitDiff = arrivalDeltaMs - sendTimeDeltaMs
                    val absD = kotlin.math.abs(transitDiff)
                    // RFC 3550: J = J + (|D| - J) / 16.0
                    estimatedJitterMs += (absD - estimatedJitterMs) / 16.0

                    // Dynamic floating watermark for Auto Mode (35ms - 400ms)
                    if (currentProfile == AudioConfig.PROFILE_AUTO) {
                        val dynamicTargetMs = (estimatedJitterMs * 3.5).toFloat().coerceIn(35.0f, 400.0f)
                        if (dynamicTargetMs > targetWatermarkMs) {
                            targetWatermarkMs = targetWatermarkMs * 0.9f + dynamicTargetMs * 0.1f
                        }
                        val nominalSlots = kotlin.math.ceil(targetWatermarkMs / (if (isCompressedStream) 20.0f else packetDurationMs)).toInt().coerceIn(2, slotCount - 4)
                        targetWatermarkSlots = nominalSlots
                    }
                }
            }
            lastArrivalNanos = nowNanos
            lastPacketSeq = sequence
            lastReceivedTimestamp = timestamp

            // Smooth catch-up only on extreme sustained network backlog
            val isLowLat = (currentProfile == AudioConfig.PROFILE_LOW_LATENCY || currentProfile == AudioConfig.PROFILE_VIDEO)
            if (isLowLat && smoothBufferFill > targetWatermarkSlots + 12) {
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
                AppLogger.d("JitterBuffer", "Pre-roll satisfied: available=$availableCount, threshold=$preRollThreshold, profile=$currentProfile")
            }

            notEmptyCondition.signal()
        }
    }

    fun write(sequence: Int, data: ByteArray, offset: Int, length: Int) {
        val inferredTs = if (lastReceivedTimestamp >= 0L && lastPacketSeq != -1) {
            val deltaSeq = seqDiff(sequence, lastPacketSeq)
            val framesPerPacket = calculateFramesForPayload(length)
            lastReceivedTimestamp + (deltaSeq * framesPerPacket)
        } else {
            0L
        }
        write(sequence, inferredTs, data, offset, length)
    }

    fun write(data: ByteArray, offset: Int, length: Int) {
        val s = syntheticSeq
        syntheticSeq = (syntheticSeq + 1) and 0xFFFF
        val ts = syntheticTimestamp
        syntheticTimestamp += calculateFramesForPayload(length)
        write(s, ts, data, offset, length)
    }

    private val internalFecDecoder by lazy { FecDecoder(this) }

    fun hasPacket(seq: Int): Boolean = lock.withLock {
        val slot = seq and (maxSlots - 1)
        val hSlot = seq and (historySize - 1)
        (isSlotFilled[slot] && slotSeq[slot] == seq) || (historySeq[hSlot] == seq)
    }

    fun getPacketLength(seq: Int): Int = lock.withLock {
        val slot = seq and (maxSlots - 1)
        val hSlot = seq and (historySize - 1)
        if (isSlotFilled[slot] && slotSeq[slot] == seq) {
            packetLengths[slot]
        } else if (historySeq[hSlot] == seq) {
            historyLengths[hSlot]
        } else {
            0
        }
    }

    fun getPacketTimestamp(seq: Int): Long = lock.withLock {
        val slot = seq and (maxSlots - 1)
        val hSlot = seq and (historySize - 1)
        if (isSlotFilled[slot] && slotSeq[slot] == seq) {
            slotTimestamp[slot]
        } else if (historySeq[hSlot] == seq) {
            historyTimestamp[hSlot]
        } else {
            -1L
        }
    }

    fun getExpectedReadTimestamp(): Long = lock.withLock { expectedReadTimestamp }

    fun getLastReceivedTimestamp(): Long = lock.withLock { lastReceivedTimestamp }

    fun findBlockTimestamp(baseSeq: Int, blockSize: Int, missingSeq: Int): Triple<Int, Long, Int>? = lock.withLock {
        for (i in 0 until blockSize) {
            val s = (baseSeq + i) and 0xFFFF
            if (s == missingSeq) continue
            val ts = getPacketTimestamp(s)
            if (ts >= 0L) {
                val len = getPacketLength(s)
                return Triple(s, ts, len)
            }
        }
        null
    }

    fun copyPacketData(seq: Int, dest: ByteArray): Int = lock.withLock {
        val slot = seq and (maxSlots - 1)
        val hSlot = seq and (historySize - 1)
        if (isSlotFilled[slot] && slotSeq[slot] == seq) {
            val len = packetLengths[slot]
            System.arraycopy(buffer[slot], 0, dest, 0, len)
            len
        } else if (historySeq[hSlot] == seq) {
            val len = historyLengths[hSlot]
            System.arraycopy(historyBuffer[hSlot], 0, dest, 0, len)
            len
        } else {
            0
        }
    }

    fun isPacketPastPlayback(seq: Int): Boolean = lock.withLock {
        if (expectedReadSeq == -1) return false
        seqDiff(seq, expectedReadSeq) < 0
    }

    fun putRecoveredPacket(sequence: Int, timestamp: Long, data: ByteArray, offset: Int, length: Int): Boolean = lock.withLock {
        if (expectedReadSeq == -1 || seqDiff(sequence, expectedReadSeq) < 0) {
            return false
        }
        val slot = sequence and (maxSlots - 1)
        if (isSlotFilled[slot] && slotSeq[slot] == sequence) {
            return false
        }

        System.arraycopy(data, offset, buffer[slot], 0, length)
        packetLengths[slot] = length
        slotSeq[slot] = sequence
        slotTimestamp[slot] = timestamp
        if (!isSlotFilled[slot]) {
            isSlotFilled[slot] = true
            availableCount++
        }

        val hSlot = sequence and (historySize - 1)
        System.arraycopy(data, offset, historyBuffer[hSlot], 0, length)
        historyLengths[hSlot] = length
        historySeq[hSlot] = sequence
        historyTimestamp[hSlot] = timestamp

        notEmptyCondition.signal()
        return true
    }

    fun putRecoveredPacket(sequence: Int, data: ByteArray, offset: Int, length: Int): Boolean {
        val inferredTs = getPacketTimestamp(sequence).takeIf { it >= 0L } ?: (sequence.toLong() * calculateFramesForPayload(length))
        return putRecoveredPacket(sequence, inferredTs, data, offset, length)
    }

    fun recoverFecPacket(
        baseSeq: Int,
        baseTimestamp: Long,
        blockSize: Int,
        parityPayload: ByteArray,
        parityOffset: Int,
        parityLen: Int
    ): Boolean {
        return internalFecDecoder.decode(baseSeq, baseTimestamp, blockSize, parityPayload, parityOffset, parityLen)
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
                val ts = slotTimestamp[slot]
                System.arraycopy(buffer[slot], 0, output, 0, len)
                isSlotFilled[slot] = false
                slotSeq[slot] = -1
                slotTimestamp[slot] = -1L
                if (availableCount > 0) availableCount--
                expectedReadSeq = (expectedReadSeq + 1) and 0xFFFF
                val framesRead = calculateFramesForPayload(len)
                expectedReadTimestamp = if (ts >= 0L) ts + framesRead else (if (expectedReadTimestamp >= 0L) expectedReadTimestamp + framesRead else -1L)
                consecutiveUnderruns = 0
                lastPacketSize = len

                val isAdts = (len >= 7 && (output[0].toInt() and 0xFF) == 0xFF && (output[1].toInt() and 0xF0) == 0xF0)
                if (isCompressedStream || isAdts) {
                    wasConcealed = false
                    // Never perform PCM micro-resampling or sample crossfade on compressed bitstreams
                    return len
                }

                if (wasConcealed) {
                    wasConcealed = false
                    val is24 = is24BitStream
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

                if (currentProfile == AudioConfig.PROFILE_AUTO) {
                    cleanPlaybackFramesCount++
                    if (cleanPlaybackFramesCount >= 100) {
                        cleanPlaybackFramesCount = 0
                        val baselineTarget = (estimatedJitterMs * 3.5).toFloat().coerceIn(35.0f, 400.0f)
                        if (targetWatermarkMs > baselineTarget) {
                            targetWatermarkMs = (targetWatermarkMs - 2.0f).coerceAtLeast(baselineTarget)
                            val nominalDurationMs = if (isCompressedStream) 20.0f else packetDurationMs
                            targetWatermarkSlots = kotlin.math.ceil(targetWatermarkMs / nominalDurationMs).toInt().coerceIn(2, slotCount - 4)
                        }
                    }
                }

                // Audio Clock Drift Management:
                // Smooth zero-crossing micro-resampling (1 frame = 20-22 microseconds)
                // Rate-limited to prevent bass modulation comb filtering while holding tight sync across independent crystal clocks
                packetsSinceDriftAdjust++
                val driftDelta = smoothBufferFill - targetWatermarkSlots
                val isLowLat = (currentProfile == AudioConfig.PROFILE_LOW_LATENCY || currentProfile == AudioConfig.PROFILE_VIDEO)
                val isAuto = (currentProfile == AudioConfig.PROFILE_AUTO)
                val driftThreshold = if (isLowLat) 8f else if (isAuto) 10f else 16f
                val minInterval = if (isLowLat) 300 else if (isAuto) 350 else 600
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
                val is24Sample = is24BitStream
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
                val len = minOf(output.size, lastPacketSize)
                val nominalFrames = calculateFramesForPayload(len)
                if (expectedReadTimestamp >= 0L) {
                    expectedReadTimestamp += nominalFrames
                }
                consecutiveUnderruns++
                if (currentProfile == AudioConfig.PROFILE_AUTO) {
                    targetWatermarkMs = (targetWatermarkMs + 30.0f).coerceAtMost(400.0f)
                    val nominalDurationMs = if (isCompressedStream) 20.0f else packetDurationMs
                    targetWatermarkSlots = kotlin.math.ceil(targetWatermarkMs / nominalDurationMs).toInt().coerceIn(2, slotCount - 4)
                    cleanPlaybackFramesCount = 0
                }
                if (consecutiveUnderruns >= maxUnderrunFrames || isCompressedStream) {
                    // Sustained drop or compressed stream: enter buffering
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
                val is24Conceal = is24BitStream
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
            expectedReadTimestamp = -1L
            lastReceivedTimestamp = -1L
            lastPacketSeq = -1
            syntheticSeq = 0
            syntheticTimestamp = 0L
            availableCount = 0
            isBuffering = true
            consecutiveUnderruns = 0
            for (i in 0 until maxSlots) {
                isSlotFilled[i] = false
                slotSeq[i] = -1
                slotTimestamp[i] = -1L
            }
            for (i in 0 until historySize) {
                historySeq[i] = -1
                historyLengths[i] = 0
                historyTimestamp[i] = -1L
            }
            lastSampleLeft16 = 0
            lastSampleRight16 = 0
            lastSampleLeft24 = 0
            lastSampleRight24 = 0
            packetsSinceCatchUp = 0
            packetsSinceDriftAdjust = 0
            isCompressedStream = false
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
        val is24 = is24BitStream
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
        val is24 = is24BitStream
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
    fun getEstimatedJitterMs(): Double = estimatedJitterMs
    fun getTargetWatermarkMs(): Float = targetWatermarkMs
}
