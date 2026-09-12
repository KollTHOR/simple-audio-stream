package com.example.audiostreamer

/**
 * Playback Scheduler and Buffer State Machine for the JitterBuffer.
 *
 * Responsibilities:
 * - Manages buffering states (pre-roll buffering vs active playback readout).
 * - Tracks expected read audio timeline timestamp (`expectedReadTimestamp`).
 * - Tracks underrun counts and triggers re-buffering on sustained underruns.
 * - Handles transmitter silence state transitions and condition signaling.
 * - Maintains timeline continuity across packet loss and synthetic stream playback.
 */
class PlaybackScheduler(initialPreRoll: Int = 2) {
    var isBuffering: Boolean = (initialPreRoll > 1)
        private set

    var hasReadStarted: Boolean = false
        private set

    var consecutiveUnderruns: Int = 0
        private set

    var isTransmitterSilent: Boolean = false
        private set

    var expectedReadTimestamp: Long = -1L
        private set

    var syntheticTimestamp: Long = 0L
        private set

    fun reset(preRollThreshold: Int) {
        isBuffering = (preRollThreshold > 1)
        hasReadStarted = false
        consecutiveUnderruns = 0
        isTransmitterSilent = false
        expectedReadTimestamp = -1L
        syntheticTimestamp = 0L
    }

    fun onStreamReset(timestamp: Long, preRollThreshold: Int) {
        isTransmitterSilent = false
        hasReadStarted = false
        expectedReadTimestamp = timestamp
        isBuffering = (preRollThreshold > 1)
        consecutiveUnderruns = 0
    }

    fun onSilence() {
        isTransmitterSilent = true
        consecutiveUnderruns = 0
        hasReadStarted = false
    }

    fun onPacketArrived(availableCount: Int, preRollThreshold: Int) {
        if (isBuffering && availableCount >= preRollThreshold) {
            isBuffering = false
            consecutiveUnderruns = 0
        }
    }

    fun canAcceptStartupPacket(diff: Int): Boolean {
        return (!hasReadStarted || isBuffering) && diff in -32..-1
    }

    fun markReadStarted() {
        hasReadStarted = true
    }

    fun resetConsecutiveUnderruns() {
        consecutiveUnderruns = 0
    }

    fun advanceExpectedReadTimestamp(frames: Int, packetTimestamp: Long = -1L) {
        if (packetTimestamp >= 0L) {
            expectedReadTimestamp = packetTimestamp + frames
        } else if (expectedReadTimestamp >= 0L) {
            expectedReadTimestamp += frames
        }
    }

    fun setExpectedReadTimestamp(timestamp: Long) {
        expectedReadTimestamp = timestamp
    }

    fun nextSyntheticTimestamp(frames: Int): Long {
        val ts = syntheticTimestamp
        syntheticTimestamp += frames
        return ts
    }

    /**
     * Records an underrun event and checks if sustained underrun requires entering buffering state.
     * Returns true if buffering state was entered.
     */
    fun onUnderrun(maxUnderrunFrames: Int, isCompressed: Boolean): Boolean {
        consecutiveUnderruns++
        val shouldBuffer = consecutiveUnderruns >= maxUnderrunFrames || isCompressed
        if (consecutiveUnderruns >= maxUnderrunFrames) {
            isBuffering = true
        }
        return shouldBuffer
    }
}
