package com.example.audiostreamer

/**
 * Adaptive Playout Controller.
 *
 * Separates two distinct concepts that were previously conflated:
 *
 *   desiredTargetMs  – what network analysis says we should target (can change immediately)
 *   effectiveTargetMs – the slew-rate-limited, hysteresis-filtered target used for actual playout
 *
 * Key guarantee: changing desiredTargetMs NEVER directly drops packets, advances sequence numbers,
 * flushes audio, or injects silence. The effectiveTargetMs changes only by the configured slew
 * rate (ms per packet update call), so the jitter buffer's fill/target discrepancy can never
 * create a sudden frame discard in the playout path.
 *
 * Shared across ADAPTIVE, MUSIC, and LOW_LATENCY with profile-specific policies.
 *
 * No Android dependencies — fully unit-testable on the JVM.
 */
class AdaptivePlayoutController(
    initialProfile: String,
    initialTargetMs: Float,
    @Suppress("UNUSED_PARAMETER") initialPacketDurationMs: Float
) {

    // -------------------------------------------------------------------------
    // Transition reason (diagnostic / logging)
    // -------------------------------------------------------------------------

    enum class TransitionReason { NONE, NETWORK_RISK, HEALTHY_RECOVERY, EMERGENCY }

    // -------------------------------------------------------------------------
    // Per-profile policy
    // -------------------------------------------------------------------------

    private data class ProfilePolicy(
        /** Minimum allowed effective target (ms). */
        val minTargetMs: Float,
        /** Maximum allowed effective target (ms). */
        val maxTargetMs: Float,
        /** Max effective-target increase per onPacketArrived call (ms). Upward = faster. */
        val upwardSlewMsPerUpdate: Float,
        /** Max effective-target decrease per onPacketArrived call (ms). Downward = slower. */
        val downwardSlewMsPerUpdate: Float,
        /**
         * Dead-band around current desiredTarget: ignore desired-target changes smaller than
         * this magnitude (ms) to prevent constant micro-adjustments.
         */
        val deadBandMs: Float,
        /**
         * Minimum consecutive clean-playback packets required before allowing downward movement of
         * desiredTarget. Prevents premature recovery during transient bursts.
         */
        val minDwellPacketsForRecovery: Int,
        /**
         * Fill above targetWatermarkSlots (in slots) that is considered "overfilled" (stable,
         * can reduce desiredTarget after dwell period).
         */
        val overfilledThresholdSlots: Int,
        /**
         * Fill below targetWatermarkSlots (in slots) that is considered "at risk" (should
         * increase desiredTarget).
         */
        val underfilledThresholdSlots: Int,
    )

    companion object {
        private fun policyFor(profile: String): ProfilePolicy = when {
            profile == AudioConfig.PROFILE_LOW_LATENCY ||
                profile == AudioConfig.PROFILE_VIDEO ||
                profile.equals("LOW_LATENCY", ignoreCase = true) ->
                ProfilePolicy(
                    minTargetMs = 35f, maxTargetMs = 60f,
                    upwardSlewMsPerUpdate = 0.5f, downwardSlewMsPerUpdate = 0.2f,
                    deadBandMs = 3f, minDwellPacketsForRecovery = 100,
                    overfilledThresholdSlots = 6, underfilledThresholdSlots = 2
                )
            profile == AudioConfig.PROFILE_MUSIC ||
                profile.equals("RELIABLE", ignoreCase = true) ->
                ProfilePolicy(
                    minTargetMs = 180f, maxTargetMs = 400f,
                    upwardSlewMsPerUpdate = 1.0f, downwardSlewMsPerUpdate = 0.2f,
                    deadBandMs = 15f, minDwellPacketsForRecovery = 500,
                    overfilledThresholdSlots = 20, underfilledThresholdSlots = 5
                )
            else -> // AUTO / ADAPTIVE / BALANCED
                ProfilePolicy(
                    minTargetMs = 35f, maxTargetMs = 400f,
                    upwardSlewMsPerUpdate = 2.0f, downwardSlewMsPerUpdate = 0.5f,
                    deadBandMs = 8f, minDwellPacketsForRecovery = 200,
                    overfilledThresholdSlots = 12, underfilledThresholdSlots = 3
                )
        }
    }

    // -------------------------------------------------------------------------
    // Arrival-margin rolling window (bounded, no allocations on hot path)
    // -------------------------------------------------------------------------

    private val arrivalMarginRing = FloatArray(64)
    private var arrivalMarginHead = 0
    private var arrivalMarginCount = 0

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private var policy: ProfilePolicy = policyFor(initialProfile)

    private var _desiredTargetMs: Float =
        initialTargetMs.coerceIn(policy.minTargetMs, policy.maxTargetMs)

    private var _effectiveTargetMs: Float =
        initialTargetMs.coerceIn(policy.minTargetMs, policy.maxTargetMs)

    private var _lastTransitionReason: TransitionReason = TransitionReason.NONE

    /** Count of consecutive packets that arrived with healthy fill (above effective target). */
    private var cleanPacketCount: Int = 0

    // -------------------------------------------------------------------------
    // Public read-only state
    // -------------------------------------------------------------------------

    val desiredTargetMs: Float get() = _desiredTargetMs
    val effectiveTargetMs: Float get() = _effectiveTargetMs
    val lastTransitionReason: TransitionReason get() = _lastTransitionReason

    /** P10 arrival-margin in ms. Positive = cushion above target (healthy). Negative = at risk. */
    val arrivalMarginP10: Float get() = computeP10()

    // -------------------------------------------------------------------------
    // Configuration / reset
    // -------------------------------------------------------------------------

    fun configure(profile: String, targetMs: Float, @Suppress("UNUSED_PARAMETER") packetDurationMs: Float) {
        policy = policyFor(profile)
        val clamped = targetMs.coerceIn(policy.minTargetMs, policy.maxTargetMs)
        _desiredTargetMs = clamped
        _effectiveTargetMs = clamped
        _lastTransitionReason = TransitionReason.NONE
        cleanPacketCount = 0
        arrivalMarginCount = 0
        arrivalMarginHead = 0
    }

    fun reset(profile: String, targetMs: Float, packetDurationMs: Float) =
        configure(profile, targetMs, packetDurationMs)

    // -------------------------------------------------------------------------
    // Hot path — called once per packet arrival inside JitterBuffer.write()
    // -------------------------------------------------------------------------

    /**
     * Updates [desiredTargetMs] based on fill-level analysis, then slews [effectiveTargetMs]
     * one step toward [desiredTargetMs] at the profile-specific slew rate.
     *
     * @return true if [effectiveTargetMs] changed (caller should log/apply the new value)
     */
    fun onPacketArrived(
        availableCount: Int,
        targetWatermarkSlots: Int,
        packetDurationMs: Float,
        @Suppress("UNUSED_PARAMETER") isUnderrun: Boolean = false
    ): Boolean {
        // Track arrival margin in ms: positive = cushion above target
        val marginMs = (availableCount - targetWatermarkSlots) * packetDurationMs
        recordMargin(marginMs)

        // Update desired target based on current fill/margin trend
        updateDesiredTarget(availableCount, targetWatermarkSlots, packetDurationMs)

        // Slew effective target one step toward desired
        val prevEffective = _effectiveTargetMs
        slewEffectiveTarget()

        return kotlin.math.abs(_effectiveTargetMs - prevEffective) > 0.01f
    }

    /**
     * Called when a buffer underrun occurs.
     * Immediately raises [desiredTargetMs] by an emergency increment; [effectiveTargetMs] then
     * slews toward the new desired value at the normal upward rate.
     */
    fun onUnderrun() {
        val emergencyIncrease = when {
            policy.maxTargetMs <= 60f  -> 5f   // LOW_LATENCY: small bump
            policy.maxTargetMs <= 250f -> 20f  // MUSIC
            else                       -> 30f  // AUTO: significant
        }
        _desiredTargetMs = (_desiredTargetMs + emergencyIncrease)
            .coerceIn(policy.minTargetMs, policy.maxTargetMs)
        _lastTransitionReason = TransitionReason.EMERGENCY
        cleanPacketCount = 0
    }

    /** Called during sustained clean playback to count toward the downward dwell requirement. */
    fun onCleanPlayback() {
        cleanPacketCount++
    }

    /**
     * Returns the effective target as a slot count (rounded up), clamped to valid buffer range.
     */
    fun effectiveTargetSlots(packetDurationMs: Float, slotCount: Int): Int =
        kotlin.math.ceil(_effectiveTargetMs / packetDurationMs)
            .toInt().coerceIn(2, slotCount - 4)

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Decides whether [desiredTargetMs] should move up or down based on current fill relative to
     * target, and the P10 arrival margin (lower-tail — worst 10 % of recent packets).
     */
    private fun updateDesiredTarget(
        availableCount: Int,
        targetWatermarkSlots: Int,
        packetDurationMs: Float
    ) {
        val marginSlots = availableCount - targetWatermarkSlots
        val p10Margin = arrivalMarginP10

        when {
            // --- RISK: buffer well below target OR lower-tail packets arriving late ---
            marginSlots < -policy.underfilledThresholdSlots ||
                p10Margin < -packetDurationMs -> {
                val increase = packetDurationMs * 2f
                val newDesired = (_desiredTargetMs + increase)
                    .coerceAtMost(policy.maxTargetMs)
                // Only apply if deviation is outside the dead band
                if (newDesired - _desiredTargetMs > policy.deadBandMs * 0.25f) {
                    _desiredTargetMs = newDesired
                    if (_lastTransitionReason != TransitionReason.EMERGENCY) {
                        _lastTransitionReason = TransitionReason.NETWORK_RISK
                    }
                    cleanPacketCount = 0
                }
            }

            // --- RECOVERY: buffer consistently well above target + dwell satisfied ---
            marginSlots > policy.overfilledThresholdSlots &&
                p10Margin > packetDurationMs &&
                cleanPacketCount >= policy.minDwellPacketsForRecovery -> {
                val decrease = packetDurationMs
                val newDesired = (_desiredTargetMs - decrease)
                    .coerceAtLeast(policy.minTargetMs)
                // Only apply if deviation is outside the dead band
                if (_desiredTargetMs - newDesired > policy.deadBandMs * 0.25f) {
                    _desiredTargetMs = newDesired
                    _lastTransitionReason = TransitionReason.HEALTHY_RECOVERY
                    // Reset dwell counter — another full dwell period is required before next step
                    cleanPacketCount = 0
                }
            }

            else -> { /* within normal operating range — no change */ }
        }
    }

    /**
     * Slews [effectiveTargetMs] by at most one slew-rate step toward [desiredTargetMs].
     * This is the core "no abrupt jump" guarantee: the playout target can only change at
     * the configured rate (ms per packet), never by an arbitrary amount in one packet.
     */
    private fun slewEffectiveTarget() {
        val diff = _desiredTargetMs - _effectiveTargetMs
        when {
            diff > 0.01f -> {
                val step = minOf(diff, policy.upwardSlewMsPerUpdate)
                _effectiveTargetMs = (_effectiveTargetMs + step)
                    .coerceAtMost(policy.maxTargetMs)
                if (step > 0.01f && _lastTransitionReason == TransitionReason.NONE) {
                    _lastTransitionReason = TransitionReason.NETWORK_RISK
                }
            }
            diff < -0.01f -> {
                val step = minOf(-diff, policy.downwardSlewMsPerUpdate)
                _effectiveTargetMs = (_effectiveTargetMs - step)
                    .coerceAtLeast(policy.minTargetMs)
                if (step > 0.01f && _lastTransitionReason == TransitionReason.EMERGENCY) {
                    // Downgrade EMERGENCY only after target has returned to near-desired
                    if (kotlin.math.abs(diff) < policy.deadBandMs) {
                        _lastTransitionReason = TransitionReason.HEALTHY_RECOVERY
                    }
                } else if (step > 0.01f) {
                    _lastTransitionReason = TransitionReason.HEALTHY_RECOVERY
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Arrival-margin ring helpers
    // -------------------------------------------------------------------------

    private fun recordMargin(marginMs: Float) {
        val idx = arrivalMarginHead % arrivalMarginRing.size
        arrivalMarginRing[idx] = marginMs
        arrivalMarginHead++
        if (arrivalMarginCount < arrivalMarginRing.size) arrivalMarginCount++
    }

    /**
     * Returns the P10 (10th percentile) of the recent arrival-margin samples.
     * A negative P10 means the lower-tail 10% of packets are arriving late — a reliable
     * signal that the effective target should be increased.
     */
    private fun computeP10(): Float {
        val count = arrivalMarginCount
        if (count == 0) return 0f
        val size = arrivalMarginRing.size
        // Collect valid samples from the ring (most recent [count] entries)
        val relevant = FloatArray(count) { i ->
            val ringIdx = (arrivalMarginHead - count + i).let {
                ((it % size) + size) % size
            }
            arrivalMarginRing[ringIdx]
        }
        relevant.sort()
        val p10Idx = ((count - 1) * 0.10).toInt().coerceIn(0, count - 1)
        return relevant[p10Idx]
    }
}
