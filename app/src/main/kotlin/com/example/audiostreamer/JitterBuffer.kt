package com.example.audiostreamer

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * High-definition Audio Transport (HAT) Jitter Buffer.
 *
 * Architecture and Component Responsibilities:
 * - [PacketBuffer]: Manages circular ring buffer slots for packets, slot occupancy, and available count.
 * - [SequenceTracker]: Owns 16-bit sequence arithmetic, discontinuity/leap detection, and read sequence cursor.
 * - [JitterEstimator]: Owns RFC 3550 inter-arrival jitter estimation and floating target watermark adaptation.
 * - [PlaybackScheduler]: Owns buffering vs active playback state, pre-roll cushions, timeouts, and timeline timestamps.
 * - [DriftController]: Owns smoothed fill level filtering, catch-up drops, and zero-crossing micro-resampling.
 * - [PacketLossConcealment]: Owns end-of-packet sample caching, fade-to-zero synthesis, and resumption crossfading.
 * - [FecHistoryBuffer]: Owns secondary circular history for robust FEC recovery of previously read packets.
 *
 * Timing Model:
 * 1. Monotonic Audio Timeline: Timestamps represent continuous audio frames from stream inception.
 *    Frame sizing is derived dynamically from negotiated bit depth, channels, and sample rate.
 * 2. Pre-roll and Readout Cursor: Playback begins only after availableCount reaches preRollThreshold.
 *    Before readout begins, startup packets arriving up to 32 slots earlier adjust the read cursor.
 * 3. Jitter and Floating Watermark: RFC 3550 exponential moving average J = J + (|D| - J) / 16 adapts
 *    the target watermark in Auto mode between 35ms and 400ms.
 * 4. Micro-Drift Zero-Crossing Compensation: Slight clock differences between sender and receiver crystal
 *    oscillators are compensated by dropping or duplicating one frame (20-22 microseconds) at a zero-crossing
 *    minimum, rate-limited to >= 300-600 packets to eliminate audible comb filtering or DC clicks.
 * 5. Deterministic Concealment: When packets are delayed beyond timeout, a smooth linear decay to zero
 *    is synthesized; upon packet resumption, a 20-frame crossfade is applied.
 */
class JitterBuffer(
    initialProfile: String = AudioConfig.PROFILE_MUSIC
) {
    private val maxSlots = AudioConfig.MUSIC_JITTER_BUFFER_SLOTS
    private val lock = ReentrantLock()
    private val notEmptyCondition = lock.newCondition()

    // Subcomponents
    private val packetBuffer = PacketBuffer(maxSlots)
    private val sequenceTracker = SequenceTracker()
    private val jitterEstimator = JitterEstimator(initialProfile)
    private val playbackScheduler = PlaybackScheduler(AudioConfig.getPreRollPackets(initialProfile))
    private val driftController = DriftController(AudioConfig.getPreRollPackets(initialProfile).toFloat())
    private val adaptiveController = AdaptivePlayoutController(
        initialProfile = initialProfile,
        initialTargetMs = if (initialProfile == AudioConfig.PROFILE_AUTO) 80.0f else 40.0f,
        initialPacketDurationMs = 5.0f
    )
    private val plc = PacketLossConcealment()
    private val fecHistory = FecHistoryBuffer(32)

    // Bounded diagnostic counters (instrumentation only: no algorithm behaviour change). Sampled once per
    // statistics interval by HatDiagnostics, never logged per packet.
    private val droppedPackets = java.util.concurrent.atomic.AtomicLong(0L)
    private val duplicatePackets = java.util.concurrent.atomic.AtomicLong(0L)
    private val latePackets = java.util.concurrent.atomic.AtomicLong(0L)
    private val concealedPackets = java.util.concurrent.atomic.AtomicLong(0L)
    private val outOfOrderPackets = java.util.concurrent.atomic.AtomicLong(0L)

    enum class ReadStatus {
        PACKET,
        PACKET_LOST,
        BUFFERING
    }

    data class ReadResult(
        val bytesRead: Int,
        val status: ReadStatus
    )

    private var currentProfile: String = initialProfile
    private var slotCount: Int = AudioConfig.getJitterBufferSlots(initialProfile)
    private var preRollThreshold: Int = AudioConfig.getPreRollPackets(initialProfile)
    private var maxUnderrunFrames: Int = AudioConfig.getMaxUnderrunFrames(initialProfile)
    private var waitTimeoutMs: Long = AudioConfig.getReceiverWaitTimeoutMs(initialProfile)
    private var lastPacketSize: Int = AudioConfig.PACKET_SIZE_16BIT
    private var isCompressedStream = false
    private var isOpusStream = false
    private var is24BitStream = false
    private var packetDurationMs: Float = 5.0f
    private var lastSampleRate: Int = AudioConfig.SAMPLE_RATE_48000

    var lastReadStatus: ReadStatus = ReadStatus.PACKET
        private set

    private val internalFecDecoder by lazy { FecDecoder(this) }

    fun setIsOpus(isOpus: Boolean) {
        lock.withLock {
            isOpusStream = isOpus
        }
    }

    fun set24Bit(is24: Boolean) {
        lock.withLock {
            is24BitStream = is24
        }
    }

    fun setSampleRate(sampleRate: Int) {
        lock.withLock {
            lastSampleRate = sampleRate
            packetDurationMs = AudioConfig.getPacketDurationMs(sampleRate)
        }
    }

    fun calculateFramesForPayload(length: Int): Int {
        if (isOpusStream) {
            return 960
        }
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
        val expSeq = sequenceTracker.expectedReadSeq
        if (expSeq == -1) return
        var checked = 0
        var currSeq = expSeq
        while (checked < maxSlots) {
            val slot = packetBuffer.getSlot(currSeq)
            val wasFilled = packetBuffer.isSlotOccupiedBy(slot, currSeq)
            val len = packetBuffer.getSlotPacketLength(slot)
            val ts = packetBuffer.getSlotTimestamp(slot)
            packetBuffer.clearSlot(slot)
            sequenceTracker.advanceExpectedReadSeq()
            currSeq = sequenceTracker.expectedReadSeq
            if (wasFilled) {
                droppedPackets.incrementAndGet()
                val frames = calculateFramesForPayload(len)
                playbackScheduler.advanceExpectedReadTimestamp(frames, ts)
                break
            }
            checked++
        }
    }

    fun onSilenceHeartbeat() {
        lock.withLock {
            playbackScheduler.onSilence()
            notEmptyCondition.signal()
        }
    }

    fun applyConfiguration(config: NegotiatedStreamConfig) {
        lock.withLock {
            is24BitStream = config.is24Bit
            lastSampleRate = config.sampleRateHz
            packetDurationMs = config.packetDurationMs
            isCompressedStream = config.isCompressed
            isOpusStream = (config.codec == AudioCodec.OPUS)
            currentProfile = config.transportProfile.latencyTarget.toAudioConfigProfile()

            val params = config.transportProfile.jitter
            slotCount = params.slotCount
            preRollThreshold = params.preRollPackets
            maxUnderrunFrames = params.maxUnderrunFrames
            waitTimeoutMs = params.waitTimeoutMs

            jitterEstimator.configure(params.targetWatermarkSlots, params.targetWatermarkMs)
            adaptiveController.configure(currentProfile, params.targetWatermarkMs, packetDurationMs)
            playbackScheduler.onStreamReset(playbackScheduler.expectedReadTimestamp, preRollThreshold)
            driftController.reset(preRollThreshold.toFloat())

            while (packetBuffer.availableCount > slotCount) {
                dropOldestSlot()
            }
            AppLogger.i("JitterBuffer", "Applied negotiated config: format=${config.sampleRateHz}Hz/${if (config.is24Bit) 24 else 16}b, codec=${config.codec.name}, profile=$currentProfile, slots=$slotCount")
        }
    }

    fun setProfile(profile: String, isCompressed: Boolean = false, isOpus: Boolean = false) {
        lock.withLock {
            val normProfile = when (profile.uppercase()) {
                "BALANCED", AudioConfig.PROFILE_AUTO -> AudioConfig.PROFILE_AUTO
                "RELIABLE", AudioConfig.PROFILE_MUSIC -> AudioConfig.PROFILE_MUSIC
                else -> AudioConfig.PROFILE_LOW_LATENCY
            }
            if (currentProfile == normProfile && (isCompressed == isCompressedStream) && (isOpus == isOpusStream)) return
            currentProfile = normProfile
            isCompressedStream = isCompressed
            isOpusStream = isOpus
            if (isCompressed) {
                slotCount = AudioConfig.LOW_LATENCY_JITTER_BUFFER_SLOTS
                preRollThreshold = AudioConfig.LOW_LATENCY_PRE_ROLL_PACKETS
                maxUnderrunFrames = AudioConfig.LOW_LATENCY_MAX_UNDERRUN_FRAMES
                waitTimeoutMs = AudioConfig.LOW_LATENCY_WAIT_TIMEOUT_MS
                val targetSlots = AudioConfig.LOW_LATENCY_TARGET_WATERMARK_SLOTS
                val targetMs = 40.0f
                jitterEstimator.configure(targetSlots, targetMs)
                adaptiveController.configure(normProfile, targetMs, packetDurationMs)
            } else {
                slotCount = AudioConfig.getJitterBufferSlots(normProfile)
                val nominalDuration = packetDurationMs
                val nominalSlots = kotlin.math.ceil(200.0f / nominalDuration).toInt()
                preRollThreshold = if (normProfile == AudioConfig.PROFILE_AUTO) {
                    kotlin.math.ceil(80.0f / nominalDuration).toInt().coerceIn(2, slotCount / 4)
                } else {
                    nominalSlots.coerceIn(4, slotCount / 4)
                }
                maxUnderrunFrames = AudioConfig.getMaxUnderrunFrames(normProfile)
                waitTimeoutMs = AudioConfig.getReceiverWaitTimeoutMs(normProfile)
                val targetMs = if (normProfile == AudioConfig.PROFILE_AUTO) 80.0f else 200.0f
                val targetSlots = kotlin.math.ceil(targetMs / nominalDuration).toInt().coerceIn(2, slotCount - 4)
                jitterEstimator.configure(targetSlots, targetMs)
                adaptiveController.configure(normProfile, targetMs, packetDurationMs)
            }
            playbackScheduler.reset(preRollThreshold)
            driftController.reset(preRollThreshold.toFloat())

            while (packetBuffer.availableCount > slotCount) {
                dropOldestSlot()
            }
            AppLogger.i("JitterBuffer", "Buffer profile updated: profile=$normProfile, compressed=$isCompressed, slots=$slotCount, preRoll=$preRollThreshold, timeout=${waitTimeoutMs}ms, packetDuration=${packetDurationMs}ms")
        }
    }

    fun write(sequence: Int, timestamp: Long, data: ByteArray, offset: Int, length: Int) {
        if (length <= 0 || length > AudioConfig.MAX_PACKET_SIZE) return

        lock.withLock {
            val nominalFramesPerPacket = calculateFramesForPayload(length)
            val maxTolerableFrameDrift = maxSlots * nominalFramesPerPacket

            val expSeq = sequenceTracker.expectedReadSeq
            val expTs = playbackScheduler.expectedReadTimestamp

            val isFirstPacket = (expSeq == -1 || expTs == -1L)
            val isSequenceReset = SequenceTracker.isSequenceReset(sequence, expSeq, slotCount)
            val isBackwardTimestampJump = (expTs != -1L && timestamp < expTs - maxTolerableFrameDrift)
            val isForwardTimestampDiscontinuity = (expTs != -1L && timestamp > expTs + maxTolerableFrameDrift)

            if (playbackScheduler.isTransmitterSilent ||
                (playbackScheduler.isBuffering && packetBuffer.availableCount == 0) ||
                isFirstPacket || isSequenceReset || isBackwardTimestampJump || isForwardTimestampDiscontinuity) {

                plc.clearConcealed()
                sequenceTracker.setExpectedReadSeq(sequence)
                playbackScheduler.onStreamReset(timestamp, preRollThreshold)
                packetBuffer.clear()
            }

            val currentExpSeq = sequenceTracker.expectedReadSeq
            val diff = SequenceTracker.diff(sequence, currentExpSeq)
            if (diff < 0) {
                if (playbackScheduler.canAcceptStartupPacket(diff)) {
                    sequenceTracker.setExpectedReadSeq(sequence)
                    playbackScheduler.setExpectedReadTimestamp(timestamp)
                } else {
                    // Outdated packet already passed playback point
                    latePackets.incrementAndGet()
                    return
                }
            }
            if (SequenceTracker.isAheadOfCapacity(sequence, currentExpSeq, maxSlots)) {
                // Sequence leap / reconnection
                sequenceTracker.setExpectedReadSeq(sequence)
                playbackScheduler.onStreamReset(timestamp, preRollThreshold)
                packetBuffer.clear()
            }

            val slot = packetBuffer.getSlot(sequence)
            if (packetBuffer.isSlotOccupiedBy(slot, sequence)) {
                // Duplicate packet
                duplicatePackets.incrementAndGet()
                return
            }

            while (packetBuffer.availableCount >= slotCount) {
                dropOldestSlot()
            }

            packetBuffer.insert(sequence, timestamp, data, offset, length)
            fecHistory.record(sequence, timestamp, data, offset, length)

            driftController.updateFill(packetBuffer.availableCount, jitterEstimator.targetWatermarkSlots)

            val previousPacketSeq = jitterEstimator.lastPacketSeq
            if (previousPacketSeq != -1 && SequenceTracker.diff(sequence, previousPacketSeq) < 0) {
                outOfOrderPackets.incrementAndGet()
            }

            jitterEstimator.onPacketArrived(
                nowNanos = System.nanoTime(),
                sequence = sequence,
                timestamp = timestamp,
                sampleRate = lastSampleRate,
                isCompressed = isCompressedStream,
                packetDurationMs = packetDurationMs,
                currentProfile = currentProfile,
                slotCount = slotCount
            )

            // Adaptive playout controller: slew effectiveTargetMs toward desiredTargetMs at a
            // bounded rate. This prevents abrupt targetWatermarkSlots changes that would otherwise
            // trigger false catch-up drops and emergency drift-fallback corrections.
            val effectiveChanged = adaptiveController.onPacketArrived(
                availableCount = packetBuffer.availableCount,
                targetWatermarkSlots = jitterEstimator.targetWatermarkSlots,
                packetDurationMs = packetDurationMs
            )
            if (effectiveChanged) {
                jitterEstimator.applyEffectiveTarget(
                    adaptiveController.effectiveTargetMs,
                    packetDurationMs,
                    slotCount
                )
            }

            val isLowLat = (currentProfile == AudioConfig.PROFILE_LOW_LATENCY || currentProfile == AudioConfig.PROFILE_VIDEO)
            if (driftController.checkCatchUpDrop(isLowLat, jitterEstimator.targetWatermarkSlots)) {
                dropOldestSlot()
            }

            playbackScheduler.onPacketArrived(packetBuffer.availableCount, preRollThreshold)

            notEmptyCondition.signal()
        }
    }

    fun write(sequence: Int, data: ByteArray, offset: Int, length: Int) {
        val lastTs = jitterEstimator.lastReceivedTimestamp
        val lastSeq = jitterEstimator.lastPacketSeq
        val inferredTs = if (lastTs >= 0L && lastSeq != -1) {
            val deltaSeq = SequenceTracker.diff(sequence, lastSeq)
            val framesPerPacket = calculateFramesForPayload(length)
            lastTs + (deltaSeq * framesPerPacket)
        } else {
            0L
        }
        write(sequence, inferredTs, data, offset, length)
    }

    fun write(data: ByteArray, offset: Int, length: Int) {
        val s = sequenceTracker.nextSyntheticSeq()
        val frames = calculateFramesForPayload(length)
        val ts = playbackScheduler.nextSyntheticTimestamp(frames)
        write(s, ts, data, offset, length)
    }

    fun hasPacket(seq: Int): Boolean = lock.withLock {
        packetBuffer.hasPacket(seq) || fecHistory.hasPacket(seq)
    }

    fun getPacketLength(seq: Int): Int = lock.withLock {
        val len = packetBuffer.getPacketLength(seq)
        if (len > 0) len else fecHistory.getPacketLength(seq)
    }

    fun getPacketTimestamp(seq: Int): Long = lock.withLock {
        val ts = packetBuffer.getPacketTimestamp(seq)
        if (ts >= 0L) ts else fecHistory.getPacketTimestamp(seq)
    }

    fun getExpectedReadTimestamp(): Long = lock.withLock { playbackScheduler.expectedReadTimestamp }

    fun getLastReceivedTimestamp(): Long = lock.withLock { jitterEstimator.lastReceivedTimestamp }

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
        val copied = packetBuffer.copyPacketData(seq, dest)
        if (copied > 0) copied else fecHistory.copyPacketData(seq, dest)
    }

    fun isPacketPastPlayback(seq: Int): Boolean = lock.withLock {
        val expSeq = sequenceTracker.expectedReadSeq
        if (expSeq == -1) return false
        val diff = SequenceTracker.diff(seq, expSeq)
        if (!playbackScheduler.hasReadStarted || playbackScheduler.isBuffering) {
            return diff < -32
        }
        diff < 0
    }

    fun putRecoveredPacket(sequence: Int, timestamp: Long, data: ByteArray, offset: Int, length: Int, overwrite: Boolean = false): Boolean = lock.withLock {
        val expSeq = sequenceTracker.expectedReadSeq
        if (expSeq == -1) {
            return false
        }
        val diff = SequenceTracker.diff(sequence, expSeq)
        if (diff < 0) {
            if (playbackScheduler.canAcceptStartupPacket(diff)) {
                sequenceTracker.setExpectedReadSeq(sequence)
                playbackScheduler.setExpectedReadTimestamp(timestamp)
            } else {
                return false
            }
        }
        val slot = packetBuffer.getSlot(sequence)
        if (!overwrite && packetBuffer.isSlotOccupiedBy(slot, sequence)) {
            return false
        }

        packetBuffer.insert(sequence, timestamp, data, offset, length)
        fecHistory.record(sequence, timestamp, data, offset, length)

        driftController.updateFill(packetBuffer.availableCount, jitterEstimator.targetWatermarkSlots)
        playbackScheduler.onPacketArrived(packetBuffer.availableCount, preRollThreshold)

        notEmptyCondition.signal()
        return true
    }

    fun putRecoveredPacket(sequence: Int, data: ByteArray, offset: Int, length: Int): Boolean {
        val inferredTs = getPacketTimestamp(sequence).takeIf { it >= 0L } ?: lock.withLock {
            val expTs = playbackScheduler.expectedReadTimestamp
            val expSeq = sequenceTracker.expectedReadSeq
            if (expTs >= 0L && expSeq != -1) {
                val delta = SequenceTracker.diff(sequence, expSeq)
                expTs + (delta * calculateFramesForPayload(length))
            } else {
                0L
            }
        }
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
     * Reads a chunk into outputBuffer and returns a [ReadResult] indicating whether a normal packet,
     * a single/burst lost packet, or buffering silence was returned.
     * Waits on condition if momentary jitter delay occurs.
     * Uses smooth PLC without triggering silence cascades on minor delays.
     */
    fun readPacket(output: ByteArray): ReadResult {
        lock.withLock {
            if (sequenceTracker.expectedReadSeq == -1) {
                val fillLen = minOf(output.size, lastPacketSize)
                output.fill(0, 0, fillLen)
                lastReadStatus = ReadStatus.BUFFERING
                return ReadResult(fillLen, ReadStatus.BUFFERING)
            }

            if (playbackScheduler.isBuffering) {
                if (packetBuffer.availableCount >= preRollThreshold) {
                    playbackScheduler.onPacketArrived(packetBuffer.availableCount, preRollThreshold)
                } else {
                    val fillLen = minOf(output.size, lastPacketSize)
                    output.fill(0, 0, fillLen)
                    lastReadStatus = ReadStatus.BUFFERING
                    return ReadResult(fillLen, ReadStatus.BUFFERING)
                }
            }

            var expSeq = sequenceTracker.expectedReadSeq
            var slot = packetBuffer.getSlot(expSeq)
            var hasPacket = packetBuffer.isSlotOccupiedBy(slot, expSeq)

            if (!hasPacket) {
                if (playbackScheduler.isTransmitterSilent) {
                    val fillLen = minOf(output.size, lastPacketSize)
                    output.fill(0, 0, fillLen)
                    lastReadStatus = ReadStatus.BUFFERING
                    return ReadResult(fillLen, ReadStatus.BUFFERING)
                }

                // Root-cause fix: scale wait timeout with effective target for AUTO and LOW_LATENCY profiles
                // so packets arriving within the target window are never prematurely declared underruns.
                val dynamicWaitMs = if (currentProfile == AudioConfig.PROFILE_AUTO) {
                    maxOf(waitTimeoutMs, minOf(jitterEstimator.targetWatermarkMs.toLong(), 150L))
                } else if (isCompressedStream || currentProfile == AudioConfig.PROFILE_LOW_LATENCY || currentProfile == AudioConfig.PROFILE_VIDEO) {
                    maxOf(waitTimeoutMs, minOf(jitterEstimator.targetWatermarkMs.toLong(), 100L))
                } else {
                    waitTimeoutMs
                }
                var nanosLeft = TimeUnit.MILLISECONDS.toNanos(dynamicWaitMs)
                try {
                    while (!hasPacket && nanosLeft > 0L) {
                        nanosLeft = notEmptyCondition.awaitNanos(nanosLeft)
                        expSeq = sequenceTracker.expectedReadSeq
                        slot = packetBuffer.getSlot(expSeq)
                        hasPacket = packetBuffer.isSlotOccupiedBy(slot, expSeq)
                    }
                } catch (ignored: InterruptedException) {
                    val fillLen = minOf(output.size, lastPacketSize)
                    output.fill(0, 0, fillLen)
                    lastReadStatus = ReadStatus.BUFFERING
                    return ReadResult(fillLen, ReadStatus.BUFFERING)
                }
            }

            playbackScheduler.markReadStarted()

            if (hasPacket) {
                val len = packetBuffer.getSlotPacketLength(slot)
                val ts = packetBuffer.getSlotTimestamp(slot)
                packetBuffer.readPacket(slot, output)
                sequenceTracker.advanceExpectedReadSeq()
                val framesRead = calculateFramesForPayload(len)
                playbackScheduler.advanceExpectedReadTimestamp(framesRead, ts)
                playbackScheduler.resetConsecutiveUnderruns()
                lastPacketSize = len

                val isAdts = (len >= 7 && (output[0].toInt() and 0xFF) == 0xFF && (output[1].toInt() and 0xF0) == 0xF0)
                if (isCompressedStream || isAdts) {
                    plc.clearConcealed()
                    lastReadStatus = ReadStatus.PACKET
                    return ReadResult(len, ReadStatus.PACKET)
                }

                if (plc.wasConcealed) {
                    plc.applyCrossfadeOnResumption(output, len, is24BitStream)
                }

                jitterEstimator.onCleanPlayback(currentProfile, isCompressedStream, packetDurationMs, slotCount)
                adaptiveController.onCleanPlayback()

                // Primary drift correction: Catmull-Rom fractional resampling (±1000 ppm, imperceptible).
                // Adjusts playback rate continuously using the PI controller's correctionRatio.
                // Maintains phase accumulator and 3-frame boundary history across packet edges for C1 continuity.
                val effectiveLen = driftController.resamplePcmChunk(output, len, is24BitStream)

                // Safety-valve fallback: coarse zero-crossing frame drop/dup only when drift is so extreme
                // that ±1000 ppm fractional resampling cannot keep pace (rare, sustained network pathology).
                driftController.checkAndApplyEmergencyDriftFallback(
                    output = output,
                    len = effectiveLen,
                    currentProfile = currentProfile,
                    targetWatermarkSlots = jitterEstimator.targetWatermarkSlots,
                    is24Bit = is24BitStream
                )

                plc.cacheLastSamples(output, effectiveLen, is24BitStream)

                lastReadStatus = ReadStatus.PACKET
                return ReadResult(effectiveLen, ReadStatus.PACKET)
            } else {
                // Underrun
                driftController.onUnderrun()
                sequenceTracker.advanceExpectedReadSeq()
                val len = minOf(output.size, lastPacketSize)
                val nominalFrames = calculateFramesForPayload(len)
                playbackScheduler.advanceExpectedReadTimestamp(nominalFrames)

                jitterEstimator.onUnderrun(currentProfile, isCompressedStream, packetDurationMs, slotCount)
                adaptiveController.onUnderrun()

                val canConceal = !isCompressedStream || isOpusStream
                val enteredBuffering = playbackScheduler.onUnderrun(maxUnderrunFrames, isCompressedStream, canConcealLoss = canConceal)
                if (enteredBuffering) {
                    plc.markConcealed()
                    plc.clearCachedSamples()
                    output.fill(0, 0, len)
                    lastReadStatus = ReadStatus.BUFFERING
                    return ReadResult(len, ReadStatus.BUFFERING)
                }

                concealedPackets.incrementAndGet()
                if (!isCompressedStream) {
                    plc.synthesizeLossConcealment(output, len, is24BitStream)
                }
                lastReadStatus = ReadStatus.PACKET_LOST
                return ReadResult(len, ReadStatus.PACKET_LOST)
            }
        }
    }

    /**
     * Reads a chunk into outputBuffer.
     * Preserves backward compatibility with existing callers.
     */
    fun read(output: ByteArray): Int {
        return readPacket(output).bytesRead
    }

    fun reset() {
        lock.withLock {
            sequenceTracker.reset()
            playbackScheduler.reset(preRollThreshold)
            jitterEstimator.reset(currentProfile)
            driftController.reset(preRollThreshold.toFloat())
            adaptiveController.reset(currentProfile, jitterEstimator.targetWatermarkMs, packetDurationMs)
            plc.reset()
            packetBuffer.clear()
            fecHistory.clear()
            isCompressedStream = false
            isOpusStream = false
            lastReadStatus = ReadStatus.PACKET
            notEmptyCondition.signalAll()
        }
    }

    fun getFillLevel(): Int = lock.withLock {
        (packetBuffer.availableCount * 100) / slotCount
    }

    fun getAvailableCount(): Int = lock.withLock {
        packetBuffer.availableCount
    }

    fun getSlotCount(): Int = lock.withLock { slotCount }
    fun getCurrentProfile(): String = lock.withLock { currentProfile }
    fun getEstimatedJitterMs(): Double = lock.withLock { jitterEstimator.estimatedJitterMs }
    fun getTargetWatermarkMs(): Float = lock.withLock { jitterEstimator.targetWatermarkMs }

    // --- Diagnostics accessors (read-only telemetry; do not affect buffering behaviour) ---
    fun getDroppedPackets(): Long = droppedPackets.get()
    fun getDuplicatePackets(): Long = duplicatePackets.get()
    fun getLatePackets(): Long = latePackets.get()
    fun getConcealedPackets(): Long = concealedPackets.get()
    fun getOutOfOrderPackets(): Long = outOfOrderPackets.get()
    fun getCorrectionRatio(): Double = lock.withLock { driftController.correctionRatio }
    fun getPreRollPackets(): Int = lock.withLock { preRollThreshold }
    fun getPacketDurationMs(): Float = lock.withLock { packetDurationMs }
    fun calculateFramesForCurrentPayload(length: Int): Int = lock.withLock { calculateFramesForPayload(length) }

    // --- Adaptive playout controller diagnostics ---
    /** Raw network-analysis-driven target (may be ahead of effectiveTargetMs). */
    fun getDesiredTargetMs(): Float = lock.withLock { adaptiveController.desiredTargetMs }
    /** Slew-limited effective target actually used for playout control. */
    fun getEffectiveTargetMs(): Float = lock.withLock { adaptiveController.effectiveTargetMs }
    /** P10 arrival-margin statistic: positive = healthy cushion, negative = risk. */
    fun getArrivalMarginP10Ms(): Float = lock.withLock { adaptiveController.arrivalMarginP10 }
    /** Human-readable last target transition reason. */
    fun getTransitionReason(): String = lock.withLock { adaptiveController.lastTransitionReason.name }
}
