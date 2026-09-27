package com.example.audiostreamer

/**
 * Reads the frame size actually carried by an Opus packet from its TOC byte (RFC 6716 §3.1, Table 3).
 *
 * The sender's MediaCodec Opus encoder may be asked for 10 ms frames, but some OEM builds emit a
 * different size; the timeline must advance by what the packet *really* contains, never by a
 * hard-coded guess, or the receiver playout drifts and the (just-fixed) stability regresses. This is
 * the single source of truth for that, shared by the capture thread and the jitter buffer.
 */
object OpusPacket {
    /** For SILK (0..11) and CELT (16..31) blocks, `config & 3` selects 2.5/5/10/20 ms. */
    private val DURATION_MS_BY_LOW_BITS = floatArrayOf(2.5f, 5.0f, 10.0f, 20.0f)
    private const val FULLBAND_SAMPLES_PER_MS = 48f // 48 kHz → samples per millisecond

    /**
     * Decodes the frame size of the packet whose first byte is [tocByte], in samples-per-channel at
     * 48 kHz. Returns [fallback] for the hybrid/arbitrary codes (12..15) the sender never emits here,
     * so an unexpected code can never mis-advance the timeline.
     */
    fun frameSamplesPerChannel(tocByte: Byte, fallback: Int): Int {
        val config = (tocByte.toInt() ushr 3) and 0x1F
        if (config in 12..15) return fallback
        val durationMs = DURATION_MS_BY_LOW_BITS[config and 3]
        val samples = (durationMs * FULLBAND_SAMPLES_PER_MS).toInt()
        return if (samples > 0) samples else fallback
    }
}
