package com.example.audiostreamer

/**
 * RFC 3550 Inter-Arrival Jitter Estimator and Floating Target Watermark Manager.
 *
 * Responsibilities:
 * - Measures transit time variance using packet audio timeline timestamps and local arrival clocks.
 * - Computes exponentially smoothed inter-arrival jitter metric (RFC 3550 standard algorithm).
 * - Adapts floating target buffer watermarks in Auto mode to balance latency vs underrun risk.
 * - Slowly relaxes buffer watermark during clean playback and increases cushion on underruns.
 */
class JitterEstimator(initialProfile: String = AudioConfig.PROFILE_MUSIC) {
    var lastArrivalNanos: Long = 0L
        private set
    var lastPacketSeq: Int = -1
        private set
    var lastReceivedTimestamp: Long = -1L
        private set
    var estimatedJitterMs: Double = 0.0
        private set
    var targetWatermarkMs: Float = if (initialProfile == AudioConfig.PROFILE_AUTO) 50.0f else 40.0f
        private set
    var targetWatermarkSlots: Int = AudioConfig.getTargetWatermarkSlots(initialProfile)
        private set

    private var cleanPlaybackFramesCount: Int = 0

    fun configure(slots: Int, watermarkMs: Float) {
        targetWatermarkSlots = slots
        targetWatermarkMs = watermarkMs
        cleanPlaybackFramesCount = 0
    }

    fun reset(initialProfile: String) {
        lastArrivalNanos = 0L
        lastPacketSeq = -1
        lastReceivedTimestamp = -1L
        estimatedJitterMs = 0.0
        targetWatermarkMs = if (initialProfile == AudioConfig.PROFILE_AUTO) 50.0f else 40.0f
        targetWatermarkSlots = AudioConfig.getTargetWatermarkSlots(initialProfile)
        cleanPlaybackFramesCount = 0
    }

    /**
     * Updates inter-arrival jitter upon arrival of an audio packet.
     */
    fun onPacketArrived(
        nowNanos: Long,
        sequence: Int,
        timestamp: Long,
        sampleRate: Int,
        isCompressed: Boolean,
        packetDurationMs: Float,
        currentProfile: String,
        slotCount: Int
    ) {
        if (lastArrivalNanos > 0L && lastReceivedTimestamp >= 0L && lastPacketSeq != -1) {
            val deltaSeq = SequenceTracker.diff(sequence, lastPacketSeq)
            if (deltaSeq in 1..50) {
                val deltaFrames = timestamp - lastReceivedTimestamp
                val currentRate = if (sampleRate > 0) sampleRate else AudioConfig.SAMPLE_RATE_48000
                val sendTimeDeltaMs = if (deltaFrames in 1..100000) {
                    (deltaFrames * 1000.0) / currentRate
                } else {
                    deltaSeq * (if (isCompressed) 20.0 else packetDurationMs.toDouble())
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
                    val nominalSlots = kotlin.math.ceil(targetWatermarkMs / (if (isCompressed) 20.0f else packetDurationMs)).toInt().coerceIn(2, slotCount - 4)
                    targetWatermarkSlots = nominalSlots
                }
            }
        }
        lastArrivalNanos = nowNanos
        lastPacketSeq = sequence
        lastReceivedTimestamp = timestamp
    }

    /**
     * Called during sustained clean playback to gradually relax buffer watermark.
     */
    fun onCleanPlayback(
        currentProfile: String,
        isCompressed: Boolean,
        packetDurationMs: Float,
        slotCount: Int
    ) {
        if (currentProfile == AudioConfig.PROFILE_AUTO) {
            cleanPlaybackFramesCount++
            if (cleanPlaybackFramesCount >= 100) {
                cleanPlaybackFramesCount = 0
                val baselineTarget = (estimatedJitterMs * 3.5).toFloat().coerceIn(35.0f, 400.0f)
                if (targetWatermarkMs > baselineTarget) {
                    targetWatermarkMs = (targetWatermarkMs - 2.0f).coerceAtLeast(baselineTarget)
                    val nominalDurationMs = if (isCompressed) 20.0f else packetDurationMs
                    targetWatermarkSlots = kotlin.math.ceil(targetWatermarkMs / nominalDurationMs).toInt().coerceIn(2, slotCount - 4)
                }
            }
        }
    }

    /**
     * Called upon buffer underrun in Auto mode to increase the jitter cushion.
     */
    fun onUnderrun(
        currentProfile: String,
        isCompressed: Boolean,
        packetDurationMs: Float,
        slotCount: Int
    ) {
        if (currentProfile == AudioConfig.PROFILE_AUTO) {
            targetWatermarkMs = (targetWatermarkMs + 30.0f).coerceAtMost(400.0f)
            val nominalDurationMs = if (isCompressed) 20.0f else packetDurationMs
            targetWatermarkSlots = kotlin.math.ceil(targetWatermarkMs / nominalDurationMs).toInt().coerceIn(2, slotCount - 4)
            cleanPlaybackFramesCount = 0
        }
    }
}
