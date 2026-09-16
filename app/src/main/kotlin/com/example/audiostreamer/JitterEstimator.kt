package com.example.audiostreamer

/**
 * RFC 3550 Inter-Arrival Jitter Estimator and Target Watermark Manager.
 *
 * Responsibilities:
 * - Measures transit time variance using packet audio timeline timestamps and local arrival clocks.
 * - Computes exponentially smoothed inter-arrival jitter metric (RFC 3550 standard algorithm).
 * - Tracks [desiredTargetMs]: the immediate, RFC-jitter-driven target (may change per packet).
 * - Exposes [applyEffectiveTarget] so the [AdaptivePlayoutController] can write back the
 *   slew-limited effective target into [targetWatermarkMs] / [targetWatermarkSlots].
 *
 * What changed vs. the previous version:
 * - [onPacketArrived] no longer directly writes [targetWatermarkMs]/[targetWatermarkSlots];
 *   it updates [desiredTargetMs] only. The [AdaptivePlayoutController] owns the effective target.
 * - [onUnderrun] no longer jumps [targetWatermarkMs] by +30 ms instantly; the controller handles
 *   that via [AdaptivePlayoutController.onUnderrun].
 * - [onCleanPlayback] no longer directly decays [targetWatermarkMs]; the controller handles that.
 * - The effective target is written back via [applyEffectiveTarget], keeping targetWatermarkSlots
 *   as the single source of truth for slot-based playout control.
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

    /**
     * Immediate network-analysis-driven target in ms, updated per packet from RFC 3550 jitter.
     * May differ from [targetWatermarkMs] (the slew-limited effective target).
     */
    var desiredTargetMs: Float = targetWatermarkMs
        private set

    private var cleanPlaybackFramesCount: Int = 0

    fun configure(slots: Int, watermarkMs: Float) {
        targetWatermarkSlots = slots
        targetWatermarkMs = watermarkMs
        desiredTargetMs = watermarkMs
        cleanPlaybackFramesCount = 0
    }

    fun reset(initialProfile: String) {
        lastArrivalNanos = 0L
        lastPacketSeq = -1
        lastReceivedTimestamp = -1L
        estimatedJitterMs = 0.0
        targetWatermarkMs = if (initialProfile == AudioConfig.PROFILE_AUTO) 50.0f else 40.0f
        targetWatermarkSlots = AudioConfig.getTargetWatermarkSlots(initialProfile)
        desiredTargetMs = targetWatermarkMs
        cleanPlaybackFramesCount = 0
    }

    /**
     * Applies the slew-limited effective target from [AdaptivePlayoutController] back into
     * [targetWatermarkMs] and [targetWatermarkSlots]. This is the only path that should update
     * these fields during normal operation.
     */
    fun applyEffectiveTarget(effectiveMs: Float, packetDurationMs: Float, slotCount: Int) {
        targetWatermarkMs = effectiveMs.coerceIn(25f, 400f)
        val duration = if (packetDurationMs > 0f) packetDurationMs else 5.0f
        targetWatermarkSlots = kotlin.math.ceil(targetWatermarkMs / duration)
            .toInt().coerceIn(2, slotCount - 4)
    }

    private fun isAutoProfile(profile: String): Boolean =
        profile == AudioConfig.PROFILE_AUTO || profile.equals("BALANCED", ignoreCase = true)

    /**
     * Updates inter-arrival jitter upon arrival of an audio packet.
     * Updates [desiredTargetMs] for Auto mode but does NOT touch [targetWatermarkMs] or
     * [targetWatermarkSlots] — those are managed by [AdaptivePlayoutController].
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

                // Update desiredTargetMs for Auto mode — this is the fast, unsmoothed signal
                if (isAutoProfile(currentProfile)) {
                    val rawDesired = (estimatedJitterMs * 3.5).toFloat().coerceIn(35.0f, 400.0f)
                    // Only increase desiredTargetMs (rising jitter needs faster response)
                    if (rawDesired > desiredTargetMs) {
                        desiredTargetMs = rawDesired
                    }
                    // NOTE: targetWatermarkMs / targetWatermarkSlots are NOT updated here.
                    // The AdaptivePlayoutController will call applyEffectiveTarget() with the
                    // slew-limited result.
                }
            }
        }
        lastArrivalNanos = nowNanos
        lastPacketSeq = sequence
        lastReceivedTimestamp = timestamp
    }

    /**
     * Called during sustained clean playback to gradually relax desiredTargetMs.
     * Does NOT touch [targetWatermarkMs]/[targetWatermarkSlots] — those are managed by
     * [AdaptivePlayoutController].
     */
    fun onCleanPlayback(
        currentProfile: String,
        isCompressed: Boolean,
        packetDurationMs: Float,
        slotCount: Int
    ) {
        if (isAutoProfile(currentProfile)) {
            cleanPlaybackFramesCount++
            if (cleanPlaybackFramesCount >= 100) {
                cleanPlaybackFramesCount = 0
                val baselineDesired = (estimatedJitterMs * 3.5).toFloat().coerceIn(35.0f, 400.0f)
                if (desiredTargetMs > baselineDesired) {
                    // Slowly decay desiredTargetMs toward the RFC-jitter baseline
                    desiredTargetMs = (desiredTargetMs - 2.0f).coerceAtLeast(baselineDesired)
                }
                // NOTE: targetWatermarkMs/Slots NOT touched here — AdaptivePlayoutController owns them.
            }
        }
    }

    /**
     * Called upon buffer underrun.
     * Resets the clean-playback counter; actual target increase is handled by
     * [AdaptivePlayoutController.onUnderrun] to ensure it is slew-limited.
     */
    fun onUnderrun(
        currentProfile: String,
        @Suppress("UNUSED_PARAMETER") isCompressed: Boolean,
        @Suppress("UNUSED_PARAMETER") packetDurationMs: Float,
        @Suppress("UNUSED_PARAMETER") slotCount: Int
    ) {
        if (isAutoProfile(currentProfile)) {
            cleanPlaybackFramesCount = 0
            // Bump desiredTargetMs to signal network risk — the AdaptivePlayoutController
            // will also call onUnderrun() and handle the emergency increase.
            desiredTargetMs = (desiredTargetMs + 30.0f).coerceAtMost(400.0f)
        }
    }
}
