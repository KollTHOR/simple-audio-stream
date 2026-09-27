package com.example.audiostreamer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverLivenessPolicyTest {

    @Test
    fun liveSenderIsNeverStale() {
        // An idle-but-alive sender emits a silence heartbeat every 500 ms; those gaps must never
        // be flagged as stale.
        assertFalse(ReceiverLivenessPolicy.isStale(0L))
        assertFalse(ReceiverLivenessPolicy.isStale(AudioConfig.SILENCE_HEARTBEAT_INTERVAL_MS))
        assertFalse(ReceiverLivenessPolicy.isStale(2_000L))
        assertFalse(ReceiverLivenessPolicy.isStale(4_000L))
    }

    @Test
    fun deadSenderIsFlaggedStale() {
        assertTrue(ReceiverLivenessPolicy.isStale(ReceiverLivenessPolicy.STALE_AFTER_MS + 1))
        assertTrue(ReceiverLivenessPolicy.isStale(20_000L))
    }

    @Test
    fun staleThresholdExceedsHeartbeatCadence() {
        // Safety: the drop window must leave room for jitter on top of several missed heartbeats.
        assertTrue(
            ReceiverLivenessPolicy.STALE_AFTER_MS > AudioConfig.SILENCE_HEARTBEAT_INTERVAL_MS * 4,
        )
    }

    @Test
    fun pollIntervalIsFasterThanStaleWindow() {
        assertTrue(ReceiverLivenessPolicy.RX_POLL_TIMEOUT_MS < ReceiverLivenessPolicy.STALE_AFTER_MS)
    }

    @Test
    fun shouldCheckIgnoresNonPositiveDeltas() {
        assertFalse(ReceiverLivenessPolicy.shouldCheck(0L))
        assertFalse(ReceiverLivenessPolicy.shouldCheck(-1L))
        assertTrue(ReceiverLivenessPolicy.shouldCheck(1L))
    }
}
