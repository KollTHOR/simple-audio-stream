package com.example.audiostreamer.diagnostics

import android.media.AudioTrack
import com.example.audiostreamer.AudioConfig
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe per-AudioTrack-instance playback accounting.
 *
 * Tracks submitted PCM audio frames and played frames derived from AudioTrack.playbackHeadPosition.
 * Correctly handles 32-bit unsigned playback head wraparound, track recreation, lifecycle state
 * transitions, and ensures old-track counters never contaminate a new track instance.
 *
 * Queued frames calculation:
 *   queuedFrames = maxOf(0L, submittedFrames - playedFrames)
 * Note: Capacity does NOT clamp or determine this queued frame count.
 */
class AudioTrackPlaybackTracker(
    val trackIdentity: Any? = null,
    @Volatile var sampleRate: Int = AudioConfig.SAMPLE_RATE_48000
) {
    private val submittedFrames = AtomicLong(0L)

    private val headLock = Any()
    private var lastRawHead: Long = 0L
    private var totalPlayedFrames: Long = 0L

    /**
     * Records PCM frames successfully written to the AudioTrack.
     * If [forTrack] is specified and does not match [trackIdentity], the write is ignored
     * so that pending writes to an old/draining AudioTrack do not contaminate this tracker.
     */
    fun onFramesSubmitted(frames: Long, forTrack: Any? = null) {
        if (forTrack != null && this.trackIdentity != null && forTrack !== this.trackIdentity) {
            return
        }
        if (frames > 0L) {
            submittedFrames.addAndGet(frames)
        }
    }

    /**
     * Returns total submitted frames for this track instance.
     */
    fun getSubmittedFrames(): Long = submittedFrames.get()

    /**
     * Updates playback head position and returns total played frames since tracking started.
     * Handles 32-bit unsigned wraparound from AudioTrack.getPlaybackHeadPosition().
     */
    fun updatePlayedFrames(rawHeadInt: Int): Long {
        synchronized(headLock) {
            val rawHead = rawHeadInt.toLong() and 0xFFFFFFFFL
            val delta = (rawHead - lastRawHead) and 0xFFFFFFFFL
            if (delta < 0x80000000L) {
                // Forward progress within unsigned 32-bit range
                totalPlayedFrames += delta
                lastRawHead = rawHead
            } else {
                // Backward jump / reset (e.g. AudioTrack.flush() or stop() resetting head to 0)
                lastRawHead = rawHead
            }
            return totalPlayedFrames
        }
    }

    /**
     * Returns the total cumulative played frames observed for this track instance.
     */
    fun getPlayedFrames(): Long {
        synchronized(headLock) {
            return totalPlayedFrames
        }
    }

    /**
     * Computes the current estimated queued frames in the AudioTrack.
     * queuedFrames = maxOf(0L, submittedFrames - playedFrames)
     * Capacity does NOT cap this value.
     */
    fun computeQueuedFrames(rawHeadInt: Int): Long {
        val played = updatePlayedFrames(rawHeadInt)
        val submitted = submittedFrames.get()
        return maxOf(0L, submitted - played)
    }

    /**
     * Convenience method to update and compute queued frames directly from an AudioTrack.
     */
    fun computeQueuedFramesFromTrack(track: AudioTrack?): Long {
        val rawHead = try {
            if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                track.playbackHeadPosition
            } else 0
        } catch (e: Exception) {
            0
        }
        return computeQueuedFrames(rawHead)
    }

    /**
     * Helper to compute queued milliseconds from frames and sample rate.
     */
    fun computeQueuedMs(queuedFrames: Long, sr: Int = sampleRate): Float {
        return if (sr > 0) (queuedFrames.toFloat() / sr.toFloat()) * 1000f else 0f
    }

    /**
     * Called when AudioTrack.flush() occurs: discards queued frames by aligning submitted frames
     * to played frames.
     */
    fun onFlush() {
        synchronized(headLock) {
            submittedFrames.set(totalPlayedFrames)
        }
    }

    /**
     * Resets all accounting state to zero.
     */
    fun reset() {
        synchronized(headLock) {
            submittedFrames.set(0L)
            lastRawHead = 0L
            totalPlayedFrames = 0L
        }
    }
}
