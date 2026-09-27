package com.example.audiostreamer

/**
 * Adaptive XOR-FEC gate.
 *
 * FEC is the *backstop*, not the primary recovery — selective retransmit (ARQ) is. On a LAN most
 * sessions have near-zero loss, so always-on parity would be pure airtime overhead (~1/K) that also
 * delays useful packets. This controller starts **off** and engages parity only while measured loss
 * is material, with hysteresis so it cannot flap packet-to-packet:
 *
 *  - enable immediately when window loss >= [enableLossPercent] (protect fast),
 *  - disable once window loss <= [disableLossPercent] *and* parity has been on for at least
 *    [minOnMs] (release slowly).
 *
 * The window is a fixed ring of booleans (lossy / not) over recent sent packets, so [lossPercent]
 * is a true proportion and the class is cheap enough to touch on the send path. Loss attribution is
 * the caller's job (here: incoming NACKs); this class only measures and decides.
 */
class AdaptiveFecController(
    private val windowPackets: Int = DEFAULT_WINDOW,
    private val enableLossPercent: Float = ENABLE_LOSS_PCT,
    private val disableLossPercent: Float = DISABLE_LOSS_PCT,
    private val minOnMs: Long = DEFAULT_MIN_ON_MS
) {
    companion object {
        const val DEFAULT_WINDOW = 512
        const val ENABLE_LOSS_PCT = 0.5f    // engage parity above ~0.5% loss
        const val DISABLE_LOSS_PCT = 0.1f   // release parity below ~0.1% loss
        const val DEFAULT_MIN_ON_MS = 3_000L
    }

    init {
        require(windowPackets > 0) { "window must be positive" }
        require(disableLossPercent <= enableLossPercent) { "disable threshold must not exceed enable" }
    }

    private val ring = BooleanArray(windowPackets)
    private var head = 0
    private var filled = 0
    private var lossCount = 0
    private var enabled = false
    private var enabledSinceMs = Long.MIN_VALUE

    /**
     * Records one sent packet outcome: [lost] when it corresponds to a retransmission request.
     * Call for every audio packet; pass an accumulated loss count (>1) for a burst.
     */
    fun recordSent(lost: Boolean = false) {
        if (filled == windowPackets) {
            if (ring[head]) lossCount--
        } else {
            filled++
        }
        ring[head] = lost
        if (lost) lossCount++
        head = (head + 1) % windowPackets
    }

    /** Observed loss as a percentage of the current window. */
    fun lossPercent(): Float = if (filled == 0) 0f else (lossCount * 100f) / filled

    /** True when parity should currently be generated. Starts false; engages on loss. */
    fun isEnabled(nowMs: Long): Boolean {
        val pct = lossPercent()
        return if (enabled) {
            val holdElapsed = nowMs - enabledSinceMs
            if (pct <= disableLossPercent && holdElapsed >= minOnMs) {
                enabled = false
                false
            } else {
                true
            }
        } else {
            if (pct >= enableLossPercent) {
                enabled = true
                enabledSinceMs = nowMs
                true
            } else {
                false
            }
        }
    }

    fun reset() {
        ring.fill(false)
        head = 0
        filled = 0
        lossCount = 0
        enabled = false
        enabledSinceMs = Long.MIN_VALUE
    }
}
