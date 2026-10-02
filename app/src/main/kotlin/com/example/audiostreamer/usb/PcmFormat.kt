package com.example.audiostreamer.usb

import android.media.AudioFormat

/**
 * A concrete PCM format request/grant. Pure value object describing exactly how PCM bytes are laid
 * out on the wire (and in the ring buffer) — transparent, uncompressed, little-endian interleaved.
 *
 * This intentionally carries NO Android AudioTrack concerns; mapping [bitDepth] to an
 * `AudioFormat.ENCODING_*` constant lives in the output layer so the transport/pipeline stay
 * device-agnostic (spec §5, §13).
 */
data class PcmFormat(
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
    val encoding: Int = AslcPayload.ENCODING_PCM,
    val flags: Long = 0L
) {
    /** Bytes per sample for the supported integer PCM depths (16/24/32). */
    val bytesPerSample: Int get() = bitDepth / 8

    /** Bytes for one interleaved multichannel frame (one sample per channel). */
    val bytesPerFrame: Int get() = bytesPerSample * channels

    /** True if the depth is a supported integer PCM width. */
    val isSupportedBitDepth: Boolean get() = bitDepth == 16 || bitDepth == 24 || bitDepth == 32

    /**
     * Maps to an `android.media.AudioFormat` PCM encoding for the AudioTrack output layer, or null
     * if there is no direct Android representation. 24-bit maps to the packed 24-in-32 form; note
     * Android's "24-bit" is device-dependent (only meaningful at the output layer where it is probed).
     */
    fun toAudioEncodingConstant(): Int? = when (bitDepth) {
        16 -> AudioFormat.ENCODING_PCM_16BIT
        24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
        32 -> AudioFormat.ENCODING_PCM_32BIT
        else -> null
    }

    /** Human-readable label for UI/diagnostics, e.g. "24-bit • 96.0 kHz • Stereo". */
    fun displayLabel(): String {
        val kHz = "%d.%03d kHz".format(sampleRate / 1000, sampleRate % 1000)
        val ch = when (channels) {
            1 -> "Mono"
            2 -> "Stereo"
            else -> "${channels}ch"
        }
        return "$bitDepth-bit • $kHz • $ch"
    }
}
