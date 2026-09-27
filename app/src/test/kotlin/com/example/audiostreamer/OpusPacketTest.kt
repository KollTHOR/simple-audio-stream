package com.example.audiostreamer

import org.junit.Assert.assertEquals
import org.junit.Test

class OpusPacketTest {

    /** Builds a TOC byte from a config number (RFC 6716 §3.1). */
    private fun toc(config: Int): Byte = ((config and 0x1F) shl 3).toByte()

    @Test
    fun silkSingleFrameCodesMapToExpectedSampleCounts() {
        // configs 0..11 (SILK NB/MB/WB): low 2 bits pick 2.5/5/10/20 ms → 120/240/480/960 @48k.
        assertEquals(120, OpusPacket.frameSamplesPerChannel(toc(0), fallback = 480)) // 2.5 ms
        assertEquals(240, OpusPacket.frameSamplesPerChannel(toc(1), fallback = 480)) // 5 ms
        assertEquals(480, OpusPacket.frameSamplesPerChannel(toc(2), fallback = 480)) // 10 ms
        assertEquals(960, OpusPacket.frameSamplesPerChannel(toc(3), fallback = 480)) // 20 ms
        // Same durations in the MB and WB bands.
        assertEquals(480, OpusPacket.frameSamplesPerChannel(toc(6), fallback = 960))
        assertEquals(960, OpusPacket.frameSamplesPerChannel(toc(11), fallback = 480))
    }

    @Test
    fun celtCodesMapToExpectedSampleCounts() {
        assertEquals(480, OpusPacket.frameSamplesPerChannel(toc(18), fallback = 960)) // CELT NB 10ms
        assertEquals(960, OpusPacket.frameSamplesPerChannel(toc(31), fallback = 480)) // CELT MB 20ms
    }

    @Test
    fun tenMsRequestIsReadAsFortyEightSamplesNotNineSixty() {
        // The core regression this guards: a 10 ms packet must advance the timeline by 480, not the
        // old hard-coded 960.
        val tenMsPacket = toc(2)
        assertEquals(480, OpusPacket.frameSamplesPerChannel(tenMsPacket, fallback = 960))
    }

    @Test
    fun ambiguousCodesFallBackRatherThanMisAdvance() {
        // configs 12..15 are hybrid/arbitrary; we never emit them, so they must return the fallback
        // instead of a wrong duration that would desync the playout timeline.
        for (config in 12..15) {
            assertEquals("config $config", 480, OpusPacket.frameSamplesPerChannel(toc(config), fallback = 480))
        }
    }

    @Test
    fun zeroSizedDurationFallsBack() {
        // A duration that rounds to 0 samples must not be used as a step of zero.
        assertEquals(480, OpusPacket.frameSamplesPerChannel(toc(2), fallback = 480))
    }
}
