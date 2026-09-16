package com.example.audiostreamer.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverDiagnosticsStateTest {

    @Test
    fun defaultStateHasSafeZeroMetrics() {
        val state = ReceiverDiagnosticsState()
        assertFalse(state.isReceiving)
        assertEquals(48000, state.sampleRate)
        assertEquals("Auto", state.profileName)
        assertEquals(0f, state.estimatedPlayoutLatencyMs, 0.001f)
        assertEquals(0f, state.jitterBufferMs, 0.001f)
        assertEquals(0f, state.audioTrackQueuedMs, 0.001f)
        assertEquals(0L, state.audioTrackQueuedFrames)
        assertEquals(0, state.audioTrackBufferSizeFrames)
        assertEquals(0, state.audioTrackBufferCapacityFrames)
        assertEquals(0f, state.audioTrackBufferMs, 0.001f)
        assertEquals(0f, state.targetWatermarkMs, 0.001f)
        assertEquals(0.0, state.jitterMs, 0.001)
        assertEquals(0, state.bufferAvailableSlots)
        assertEquals(0, state.bufferTotalSlots)
        assertEquals(0, state.bufferFillPercent)
        assertEquals(1.0, state.driftCorrectionRatio, 0.0001)
        assertEquals(0L, state.packetsReceived)
        assertEquals(0L, state.packetsLost)
        assertEquals(0L, state.underruns)
        assertEquals(0L, state.framesWritten)
        assertEquals(0L, state.playbackHead)
    }

    @Test
    fun latencyModelDerivationMatchesPlayoutComponents() {
        // T_playout = T_jb + T_track
        val jbMs = 30.0f // e.g. 3 packets * 10ms
        val trackMs = 24.0f // e.g. 1152 frames / 48000 * 1000ms
        val totalMs = jbMs + trackMs

        val state = ReceiverDiagnosticsState(
            isReceiving = true,
            sampleRate = 48000,
            profileName = "Low Latency (Opus)",
            estimatedPlayoutLatencyMs = totalMs,
            jitterBufferMs = jbMs,
            audioTrackQueuedMs = trackMs,
            audioTrackQueuedFrames = 1152L,
            audioTrackBufferSizeFrames = 1680,
            audioTrackBufferCapacityFrames = 7680,
            audioTrackBufferMs = trackMs,
            targetWatermarkMs = 40.0f,
            jitterMs = 1.5,
            bufferAvailableSlots = 3,
            bufferTotalSlots = 16,
            bufferFillPercent = 18,
            driftCorrectionRatio = 1.0002,
            packetsReceived = 1500L,
            packetsLost = 2L,
            underruns = 0L,
            audioTrackWrites = 750L,
            framesWritten = 720000L,
            playbackHead = 718848L
        )

        assertTrue(state.isReceiving)
        assertEquals(54.0f, state.estimatedPlayoutLatencyMs, 0.001f)
        assertEquals(state.jitterBufferMs + state.audioTrackQueuedMs, state.estimatedPlayoutLatencyMs, 0.001f)
        assertEquals(1152L, state.audioTrackQueuedFrames)
        assertEquals(1680, state.audioTrackBufferSizeFrames)
        assertEquals(7680, state.audioTrackBufferCapacityFrames)
        assertEquals(1500L, state.packetsReceived)
        assertEquals(2L, state.packetsLost)
    }
}
