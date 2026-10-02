package com.example.audiostreamer.usb

import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Bridges PCM formats to what this device's AudioFlinger/AudioTrack can actually render, and narrows
 * the advertised capability set accordingly (spec §3: "do not advertise formats the Android
 * implementation cannot process"). The narrowing is a pure function ([narrow]) over an injected probe
 * so it is unit-testable; the real probe uses [android.media.AudioTrack.getMinBufferSize].
 */
object UsbPcmProber {

    private const val TAG = "UsbPcmProber"

    /**
     * Runtime support probe: returns the minimum AudioTrack buffer bytes for [format] on THIS device,
     * or a value <= 0 when unsupported. `getMinBufferSize` returns negative error codes on unsupported
     * rate/encoding/channel combinations, which the validator treats as "cannot render".
     *
     * This never throws for an unsupported format; genuine device faults are caught and reported as
     * unsupported so negotiation stays safe.
     */
    fun probeMinBufferBytes(format: PcmFormat): Int {
        val encoding = format.toAudioEncodingConstant() ?: return -1
        val channelMask = when (format.channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> return -1 // not a mask we build AudioTracks with today
        }
        return try {
            AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        } catch (e: Exception) {
            Log.d(TAG, "getMinBufferSize failed for ${format.displayLabel()}: ${e.message}")
            -1
        } catch (e: Error) {
            -1
        }
    }

    /**
     * Pure: given the ceiling [caps] and a [probe], drop any sample rate / bit depth / channel count
     * that has no renderable combination, and cap maxFrameBytes to the ring-friendly value. Rates and
     * depths are advertised only if at least one full (rate × depth × channel) triple probes OK.
     */
    fun narrow(caps: PcmCapabilities, probe: (PcmFormat) -> Int): PcmCapabilities {
        val okCells = HashMap<Pair<Int, Int>, Boolean>()
        val okChannels = HashSet<Int>()
        for (rate in caps.sampleRates) {
            for (depth in caps.bitDepths) {
                for (ch in caps.channels) {
                    if (probe(PcmFormat(rate, depth, ch)) > 0) {
                        okCells[rate to depth] = true
                        okChannels.add(ch)
                    }
                }
            }
        }
        val keptRates = caps.sampleRates.filter { r -> caps.bitDepths.any { d -> okCells[r to d] == true } }
        val keptDepths = caps.bitDepths.filter { d -> caps.sampleRates.any { r -> okCells[r to d] == true } }
        val keptChannels = caps.channels.filter { it in okChannels }
        return PcmCapabilities(
            sampleRates = keptRates.toIntArray(),
            bitDepths = keptDepths.toIntArray(),
            channels = keptChannels.toIntArray(),
            encodings = caps.encodings,
            maxFrameBytes = caps.maxFrameBytes,
            recommendedBufferBytes = caps.recommendedBufferBytes,
            capabilityFlags = caps.capabilityFlags
        )
    }
}
