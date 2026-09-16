package com.example.audiostreamer.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTrackPlaybackTrackerTest {

    @Test
    fun newTrackStartsWithZeroSubmittedFrames() {
        val tracker = AudioTrackPlaybackTracker(trackIdentity = "track1", sampleRate = 48000)
        assertEquals(0L, tracker.getSubmittedFrames())
        assertEquals(0L, tracker.getPlayedFrames())
        assertEquals(0L, tracker.computeQueuedFrames(0))
        assertEquals(0f, tracker.computeQueuedMs(0L), 0.001f)
    }

    @Test
    fun writesIncreaseSubmittedFrames() {
        val tracker = AudioTrackPlaybackTracker(trackIdentity = "track1", sampleRate = 48000)
        tracker.onFramesSubmitted(480L)
        assertEquals(480L, tracker.getSubmittedFrames())

        tracker.onFramesSubmitted(960L)
        assertEquals(1440L, tracker.getSubmittedFrames())
    }

    @Test
    fun playbackHeadReducesQueuedFrames() {
        val tracker = AudioTrackPlaybackTracker(trackIdentity = "track1", sampleRate = 48000)
        tracker.onFramesSubmitted(1920L)

        // Initial observation: hardware head is at 0
        assertEquals(1920L, tracker.computeQueuedFrames(0))
        assertEquals(0L, tracker.getPlayedFrames())

        // Head advances 480 frames
        assertEquals(1440L, tracker.computeQueuedFrames(480))
        assertEquals(480L, tracker.getPlayedFrames())

        // Head advances to 1440 frames
        assertEquals(480L, tracker.computeQueuedFrames(1440))
        assertEquals(1440L, tracker.getPlayedFrames())

        // Head advances to 1920 frames (all played)
        assertEquals(0L, tracker.computeQueuedFrames(1920))
        assertEquals(1920L, tracker.getPlayedFrames())
    }

    @Test
    fun trackRecreationResetsAccounting() {
        val oldTracker = AudioTrackPlaybackTracker(trackIdentity = "oldTrack", sampleRate = 48000)
        oldTracker.onFramesSubmitted(500000L)
        oldTracker.computeQueuedFrames(450000)
        assertEquals(500000L, oldTracker.getSubmittedFrames())
        assertEquals(450000L, oldTracker.getPlayedFrames())

        // Recreate track: fresh tracker instance
        val newTracker = AudioTrackPlaybackTracker(trackIdentity = "newTrack", sampleRate = 48000)
        assertEquals(0L, newTracker.getSubmittedFrames())
        assertEquals(0L, newTracker.getPlayedFrames())
        assertEquals(0L, newTracker.computeQueuedFrames(0))
    }

    @Test
    fun oldTrackFramesNeverAffectNewTrackQueue() {
        val oldTrack = Object()
        val newTrack = Object()
        val newTracker = AudioTrackPlaybackTracker(trackIdentity = newTrack, sampleRate = 48000)

        // Late in-flight write intended for oldTrack arrives
        newTracker.onFramesSubmitted(960L, forTrack = oldTrack)
        assertEquals(0L, newTracker.getSubmittedFrames())
        assertEquals(0L, newTracker.computeQueuedFrames(0))

        // Write for newTrack arrives
        newTracker.onFramesSubmitted(480L, forTrack = newTrack)
        assertEquals(480L, newTracker.getSubmittedFrames())
        assertEquals(480L, newTracker.computeQueuedFrames(0))
    }

    @Test
    fun queueNeverBecomesNegative() {
        val tracker = AudioTrackPlaybackTracker(trackIdentity = "track1", sampleRate = 48000)
        tracker.onFramesSubmitted(480L)

        // Head reports 480 played
        assertEquals(0L, tracker.computeQueuedFrames(480))

        // Timing discrepancy / underrun reports head ahead of submitted frames
        assertEquals(0L, tracker.computeQueuedFrames(600))
        assertTrue(tracker.computeQueuedFrames(600) >= 0L)
        assertEquals(0f, tracker.computeQueuedMs(tracker.computeQueuedFrames(600)), 0.001f)
    }

    @Test
    fun playbackHeadWraparoundIsHandled() {
        val tracker = AudioTrackPlaybackTracker(trackIdentity = "track1", sampleRate = 48000)
        tracker.onFramesSubmitted(1000L)

        // Initial head reading close to unsigned 32-bit ceiling (2^32 - 100)
        val nearCeilingInt = (-100) // 0xFFFFFF9CL = 4294967196L
        assertEquals(1000L, tracker.computeQueuedFrames(nearCeilingInt))
        assertEquals(0L, tracker.getPlayedFrames())

        // Hardware head wraps past 0 to 140
        // Expected advance: 100 frames to reach ceiling + 140 frames past 0 = 240 frames
        val wrappedInt = 140
        val queuedAfterWrap = tracker.computeQueuedFrames(wrappedInt)
        assertEquals(240L, tracker.getPlayedFrames())
        assertEquals(760L, queuedAfterWrap) // 1000 - 240 = 760

        // Next advance: 140 -> 380 (another 240 frames)
        val nextQueued = tracker.computeQueuedFrames(380)
        assertEquals(480L, tracker.getPlayedFrames())
        assertEquals(520L, nextQueued) // 1000 - 480 = 520
    }

    @Test
    fun capacityDoesNotDetermineQueuedAmount() {
        val tracker = AudioTrackPlaybackTracker(trackIdentity = "track1", sampleRate = 48000)

        // Suppose hardware capacity is 7680 frames (160ms), but active queued audio is 1680 frames (35ms)
        tracker.onFramesSubmitted(1680L)
        val queuedFrames = tracker.computeQueuedFrames(0)
        val queuedMs = tracker.computeQueuedMs(queuedFrames)

        // Verify queued frames is strictly 1680 frames (~35ms) and NOT inflated to capacity (7680 frames / 160ms)
        assertEquals(1680L, queuedFrames)
        assertEquals(35.0f, queuedMs, 0.001f)

        // Verify that submitted frames exceeding a nominal capacity of 7680 is not clamped
        tracker.onFramesSubmitted(8320L) // Total: 10000 frames
        val largeQueued = tracker.computeQueuedFrames(0)
        assertEquals(10000L, largeQueued)
    }

    @Test
    fun flushDiscardsQueuedFrames() {
        val tracker = AudioTrackPlaybackTracker(trackIdentity = "track1", sampleRate = 48000)
        tracker.onFramesSubmitted(1920L)
        tracker.computeQueuedFrames(480)
        assertEquals(1440L, tracker.computeQueuedFrames(480))

        // On flush, queued audio is discarded; submitted frames align with played frames
        tracker.onFlush()
        assertEquals(0L, tracker.computeQueuedFrames(480))
        assertEquals(0f, tracker.computeQueuedMs(0L), 0.001f)
    }
}
