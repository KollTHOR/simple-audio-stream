package com.example.audiostreamer.usb

/**
 * The set of PCM capabilities a device can *actually* process, advertised to the desktop host during
 * negotiation. Kept as extensible lists so future additions (176.4/192 kHz already, 32-bit float,
 * multichannel, other codecs/transports) do not require a format change.
 *
 * The Android receiver builds the *advertised* set from [fullMatrix] (the hardware/ASLC ceiling)
 * then narrows it with a runtime AudioTrack probe; anything the probe rejects is not advertised and
 * a CONFIGURE for it is answered with a protocol-level FORMAT_UNSUPPORTED error (never a silent
 * resample — spec §4).
 */
data class PcmCapabilities(
    val sampleRates: IntArray,
    val bitDepths: IntArray,
    val channels: IntArray,
    val encodings: IntArray,
    val maxFrameBytes: Int,
    val recommendedBufferBytes: Int,
    val capabilityFlags: Long = 0L
) {
    /** The maximum single PCM_DATA payload the receiver's ring buffer can accept without overflow. */
    fun supports(format: PcmFormat): Boolean =
        format.sampleRate in sampleRates &&
            format.bitDepth in bitDepths &&
            format.channels in channels &&
            format.encoding in encodings

    fun copyNarrowed(
        sampleRates: IntArray = this.sampleRates,
        bitDepths: IntArray = this.bitDepths
    ): PcmCapabilities = PcmCapabilities(
        sampleRates = sampleRates,
        bitDepths = bitDepths,
        channels = channels,
        encodings = encodings,
        maxFrameBytes = maxFrameBytes,
        recommendedBufferBytes = recommendedBufferBytes,
        capabilityFlags = capabilityFlags
    )

    companion object {
        val SAMPLE_RATES_FULL = intArrayOf(44100, 48000, 88200, 96000, 176400, 192000)
        val BIT_DEPTHS_FULL = intArrayOf(16, 24, 32)
        val CHANNELS_FULL = intArrayOf(1, 2)
        val ENCODINGS_FULL = intArrayOf(AslcPayload.ENCODING_PCM)

        /** Largest frame the receiver advertises it can absorb in one PCM_DATA message. */
        const val DEFAULT_MAX_FRAME_BYTES = 16384

        /** A conservative sink cushion (~50 ms at the highest rate) used as the buffer hint. */
        const val DEFAULT_RECOMMENDED_BUFFER_BYTES = 460800 // 192k * 2ch * 4B * ~0.3s headroom cap

        /** The ceiling capability set (pre runtime probe). */
        fun fullMatrix(): PcmCapabilities = PcmCapabilities(
            sampleRates = SAMPLE_RATES_FULL.copyOf(),
            bitDepths = BIT_DEPTHS_FULL.copyOf(),
            channels = CHANNELS_FULL.copyOf(),
            encodings = ENCODINGS_FULL.copyOf(),
            maxFrameBytes = DEFAULT_MAX_FRAME_BYTES,
            recommendedBufferBytes = DEFAULT_RECOMMENDED_BUFFER_BYTES,
            capabilityFlags = 0L
        )
    }
}
