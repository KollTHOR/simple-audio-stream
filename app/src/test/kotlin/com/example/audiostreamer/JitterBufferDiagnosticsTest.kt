package com.example.audiostreamer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the fix for the Opus diagnostics inflation: reported per-packet duration must follow the
 * ACTUAL Opus frame size (10 ms), not the shared compressed-codec nominal (20 ms), or every latency
 * and buffer-ms figure reads ~2x high.
 */
class JitterBufferDiagnosticsTest {

    private fun opusBuffer(): JitterBuffer {
        val jb = JitterBuffer(initialProfile = AudioConfig.PROFILE_LOW_LATENCY)
        jb.setIsOpus(true)
        jb.setSampleRate(AudioConfig.SAMPLE_RATE_48000)
        return jb
    }

    @Test
    fun opusPacketDurationIsTenMsNotTwenty() {
        val jb = opusBuffer()
        // 480 samples @ 48 kHz = 10 ms, even though the configured compressed nominal is 20 ms.
        assertEquals(10.0f, jb.getPacketDurationMs(), 0.001f)
    }

    @Test
    fun opusPacketDurationTracksActualDecoderFrameSize() {
        val jb = opusBuffer()
        // An OEM encoder that pinned to 20 ms (960 samples) must report 20 ms, not a stale 10.
        jb.reportOpusFrameSize(960)
        assertEquals(20.0f, jb.getPacketDurationMs(), 0.001f)
        jb.reportOpusFrameSize(480)
        assertEquals(10.0f, jb.getPacketDurationMs(), 0.001f)
    }

    @Test
    fun framesPerPacketForDiagnosticsMatchesOpusFrameSize() {
        val jb = opusBuffer()
        assertEquals(AudioConfig.OPUS_FRAME_SAMPLES_48K, jb.getFramesPerPacketForDiagnostics())
        jb.reportOpusFrameSize(960)
        assertEquals(960, jb.getFramesPerPacketForDiagnostics())
    }

    @Test
    fun nonOpusStillUsesConfiguredPacketDuration() {
        val jb = JitterBuffer(initialProfile = AudioConfig.PROFILE_LOW_LATENCY)
        jb.setSampleRate(AudioConfig.SAMPLE_RATE_48000)
        // Not opus: 48k PCM nominal from AudioConfig.getPacketDurationMs.
        assertEquals(AudioConfig.getPacketDurationMs(AudioConfig.SAMPLE_RATE_48000), jb.getPacketDurationMs(), 0.001f)
    }
}
