package com.example.audiostreamer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Build
import android.util.Log

object AudioCapabilities {
    private const val TAG = "AudioCapabilities"

    const val CAP_FLAG_44100: Int = 1 shl 0 // 0x01
    const val CAP_FLAG_48000: Int = 1 shl 1 // 0x02
    const val CAP_FLAG_88200: Int = 1 shl 2 // 0x04
    const val CAP_FLAG_96000: Int = 1 shl 3 // 0x08
    const val CAP_FLAG_176400: Int = 1 shl 4 // 0x10
    const val CAP_FLAG_192000: Int = 1 shl 5 // 0x20

    // Multi-platform architecture rates (preserved for non-Android platforms)
    val ALL_PLATFORM_RATES = listOf(192000, 176400, 96000, 88200, 48000, 44100)

    // Android native rates: 48 kHz (primary operating point) and 44.1 kHz (CD fallback).
    // Higher rates (88.2k/96k/176.4k/192k) are NOT advertised on Android to prevent
    // AudioFlinger resampler distortion and comb filtering.
    val ANDROID_NATIVE_RATES = listOf(AudioConfig.SAMPLE_RATE_48000, AudioConfig.SAMPLE_RATE_44100)

    @Volatile
    private var cachedPlaybackMask: Int? = null

    @Volatile
    private var cachedCaptureMask: Int? = null

    fun rateToCapFlag(rate: Int): Int = when (rate) {
        AudioConfig.SAMPLE_RATE_44100 -> CAP_FLAG_44100
        AudioConfig.SAMPLE_RATE_48000 -> CAP_FLAG_48000
        AudioConfig.SAMPLE_RATE_88200 -> CAP_FLAG_88200
        AudioConfig.SAMPLE_RATE_96000 -> CAP_FLAG_96000
        AudioConfig.SAMPLE_RATE_176400 -> CAP_FLAG_176400
        AudioConfig.SAMPLE_RATE_192000 -> CAP_FLAG_192000
        else -> CAP_FLAG_48000
    }

    fun isPlaybackSupported(sampleRate: Int, is24Bit: Boolean = true): Boolean {
        return try {
            val encoding = if (is24Bit && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioFormat.ENCODING_PCM_24BIT_PACKED
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioConfig.CHANNEL_OUT_MASK,
                encoding
            )
            if (minBuf <= 0) return false

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioConfig.CHANNEL_OUT_MASK)
                        .setEncoding(encoding)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            val isInit = (track.state == AudioTrack.STATE_INITIALIZED)
            track.release()
            isInit
        } catch (e: Exception) {
            Log.d(TAG, "isPlaybackSupported($sampleRate, 24b=$is24Bit) exception: ${e.message}")
            false
        }
    }

    fun isCaptureSupported(sampleRate: Int, is24Bit: Boolean = true): Boolean {
        return try {
            val encoding = if (is24Bit && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioFormat.ENCODING_PCM_24BIT_PACKED
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioConfig.CHANNEL_IN_MASK,
                encoding
            )
            minBuf > 0
        } catch (e: Exception) {
            Log.d(TAG, "isCaptureSupported($sampleRate, 24b=$is24Bit) exception: ${e.message}")
            false
        }
    }

    @Synchronized
    fun getLocalPlaybackCapabilitiesMask(): Int {
        cachedPlaybackMask?.let { return it }
        var mask = 0
        for (rate in ANDROID_NATIVE_RATES) {
            if (isPlaybackSupported(rate, is24Bit = true) || isPlaybackSupported(rate, is24Bit = false)) {
                mask = mask or rateToCapFlag(rate)
            }
        }
        if (mask == 0) {
            mask = CAP_FLAG_48000
        }
        cachedPlaybackMask = mask
        Log.i(TAG, "Local playback capabilities mask: 0x${Integer.toHexString(mask)} (${describeCapabilitiesMask(mask)})")
        return mask
    }

    @Synchronized
    fun getLocalCaptureCapabilitiesMask(): Int {
        cachedCaptureMask?.let { return it }
        var mask = 0
        for (rate in ANDROID_NATIVE_RATES) {
            if (isCaptureSupported(rate, is24Bit = true) || isCaptureSupported(rate, is24Bit = false)) {
                mask = mask or rateToCapFlag(rate)
            }
        }
        if (mask == 0) {
            mask = CAP_FLAG_48000
        }
        cachedCaptureMask = mask
        Log.i(TAG, "Local capture capabilities mask: 0x${Integer.toHexString(mask)} (${describeCapabilitiesMask(mask)})")
        return mask
    }

    fun getHighestMutuallySupportedRate(txMask: Int, rxMask: Int, preferredRate: Int = AudioConfig.SAMPLE_RATE_48000): Int {
        val effectiveTx = if (txMask == 0) (CAP_FLAG_48000 or CAP_FLAG_44100) else txMask
        val effectiveRx = if (rxMask == 0) (CAP_FLAG_48000 or CAP_FLAG_44100) else rxMask
        val common = effectiveTx and effectiveRx

        val prefFlag = rateToCapFlag(preferredRate)
        if ((common and prefFlag) != 0) {
            return preferredRate
        }

        // On Android, 48 kHz is the rock-solid primary operating point.
        if ((common and CAP_FLAG_48000) != 0) {
            return AudioConfig.SAMPLE_RATE_48000
        }
        if ((common and CAP_FLAG_44100) != 0) {
            return AudioConfig.SAMPLE_RATE_44100
        }

        // Fallback for multi-platform connections preserving higher rates
        for (rate in ALL_PLATFORM_RATES) {
            if ((common and rateToCapFlag(rate)) != 0) {
                return rate
            }
        }

        return AudioConfig.SAMPLE_RATE_48000
    }

    fun describeCapabilitiesMask(mask: Int): String {
        val list = mutableListOf<String>()
        if ((mask and CAP_FLAG_48000) != 0) list.add("48.0kHz (Primary)")
        if ((mask and CAP_FLAG_44100) != 0) list.add("44.1kHz")
        if ((mask and CAP_FLAG_88200) != 0) list.add("88.2kHz")
        if ((mask and CAP_FLAG_96000) != 0) list.add("96.0kHz")
        if ((mask and CAP_FLAG_176400) != 0) list.add("176.4kHz")
        if ((mask and CAP_FLAG_192000) != 0) list.add("192.0kHz")
        return if (list.isEmpty()) "None" else list.joinToString(", ")
    }

    fun describeCapabilities(mask: Int): String {
        val bitStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "24-bit" else "16-bit"
        val rates = mutableListOf<String>()
        if ((mask and CAP_FLAG_48000) != 0) rates.add("48.0 kHz")
        if ((mask and CAP_FLAG_44100) != 0) rates.add("44.1 kHz")
        if ((mask and CAP_FLAG_96000) != 0) rates.add("96.0 kHz")
        if ((mask and CAP_FLAG_192000) != 0) rates.add("192.0 kHz")
        val rateDesc = if (rates.isEmpty()) "48.0 kHz" else rates.joinToString("/")
        return "$bitStr • $rateDesc Stereo"
    }
}
