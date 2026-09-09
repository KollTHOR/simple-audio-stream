package com.example.audiostreamer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.audiostreamer.AppLogger as Log

/**
 * Real-time detector for Android audio engine active playback sessions.
 *
 * Inspects AudioManager.getActivePlaybackConfigurations() to identify active media streams
 * (e.g. Tidal, Spotify, Apple Music, YouTube) and discover their native sample rate (44.1k, 48k, 96k, 192k)
 * and resolution without requiring special elevated permissions.
 */
object AudioPlaybackDetector {
    private const val TAG = "AudioPlaybackDetector"

    data class DetectedMediaFormat(
        val sampleRate: Int,
        val is24Bit: Boolean,
        val isPlaying: Boolean,
        val description: String
    )

    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    private var activeListener: ((DetectedMediaFormat) -> Unit)? = null
    private var lastReportedFormat: DetectedMediaFormat? = null

    /**
     * Inspects active playback configurations and returns the currently playing media format.
     * If multiple are active, prioritizes media playback (USAGE_MEDIA) over system sounds.
     */
    fun getActiveMediaFormat(context: Context): DetectedMediaFormat {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) {
            return DetectedMediaFormat(AudioConfig.SAMPLE_RATE_48000, is24Bit = false, isPlaying = false, "Default 48kHz (AudioManager unavailable)")
        }

        val nativeRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: AudioConfig.SAMPLE_RATE_48000

        val configs = try {
            audioManager.activePlaybackConfigurations
        } catch (e: Exception) {
            Log.w(TAG, "Failed retrieving activePlaybackConfigurations: ${e.message}")
            emptyList<AudioPlaybackConfiguration>()
        }

        var detectedRate = 0
        var isPlayingMedia = false
        var isDetected24Bit = false

        for (config in configs) {
            val attr = config.audioAttributes
            val usage = attr.usage
            val isMedia = (usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME)
            if (!isMedia) continue

            val isConfigPlaying = isConfigurationActive(config)
            val configRate = extractSampleRate(config)
            val config24Bit = isConfiguration24Bit(config, configRate)

            if (isConfigPlaying) {
                isPlayingMedia = true
                if (configRate > 0) {
                    detectedRate = configRate
                    isDetected24Bit = config24Bit
                    break
                }
            } else if (detectedRate == 0 && configRate > 0) {
                // Keep as standby if no active player found yet
                detectedRate = configRate
                isDetected24Bit = config24Bit
            }
        }

        val effectiveRate = when {
            detectedRate in listOf(44100, 48000, 88200, 96000, 176400, 192000) -> detectedRate
            detectedRate > 0 -> {
                // Round to nearest standard rate
                when {
                    detectedRate > 144000 -> 192000
                    detectedRate > 72000 -> 96000
                    detectedRate in 43000..45000 -> 44100
                    else -> 48000
                }
            }
            nativeRate == 44100 -> 44100
            else -> 48000
        }

        // Automatic bit depth: Hi-Res rates (>=88.2kHz) default to 24-bit; standard rates default to 16-bit unless explicitly 24-bit
        val effective24Bit = isDetected24Bit || (effectiveRate >= 88200)

        val desc = if (isPlayingMedia) {
            "Active Media: ${effectiveRate / 1000.0} kHz / ${if (effective24Bit) "24-bit" else "16-bit"}"
        } else {
            "System Idle: ${effectiveRate / 1000.0} kHz / ${if (effective24Bit) "24-bit" else "16-bit"}"
        }

        return DetectedMediaFormat(
            sampleRate = effectiveRate,
            is24Bit = effective24Bit,
            isPlaying = isPlayingMedia,
            description = desc
        )
    }

    /**
     * Registers a callback to receive real-time updates whenever Android playback configuration changes.
     */
    @Synchronized
    fun startMonitoring(context: Context, listener: (DetectedMediaFormat) -> Unit) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        activeListener = listener

        if (playbackCallback == null) {
            val callback = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                    val current = getActiveMediaFormat(context)
                    if (current != lastReportedFormat) {
                        lastReportedFormat = current
                        Log.i(TAG, "AudioPlaybackConfiguration changed: ${current.description}")
                        activeListener?.invoke(current)
                    }
                }
            }
            try {
                audioManager.registerAudioPlaybackCallback(callback, Handler(Looper.getMainLooper()))
                playbackCallback = callback
                lastReportedFormat = getActiveMediaFormat(context)
                Log.i(TAG, "Registered AudioPlaybackCallback. Initial state: ${lastReportedFormat?.description}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed registering AudioPlaybackCallback: ${e.message}")
            }
        }
    }

    /**
     * Unregisters the callback.
     */
    @Synchronized
    fun stopMonitoring(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        playbackCallback?.let {
            try {
                audioManager?.unregisterAudioPlaybackCallback(it)
            } catch (ignored: Exception) {}
            playbackCallback = null
        }
        activeListener = null
        lastReportedFormat = null
    }

    private fun extractSampleRate(config: AudioPlaybackConfiguration): Int {
        // Attempt 1: Direct reflection on getSampleRate()
        try {
            val method = config.javaClass.getMethod("getSampleRate")
            val rate = method.invoke(config) as? Int
            if (rate != null && rate > 0) return rate
        } catch (ignored: Exception) {}

        // Attempt 2: Parse toString() for sampleRate=XXXXX
        try {
            val str = config.toString()
            val match = Regex("""sampleRate=(\d+)""").find(str)
            if (match != null) {
                val r = match.groupValues[1].toIntOrNull()
                if (r != null && r > 0) return r
            }
        } catch (ignored: Exception) {}

        return 0
    }

    private fun isConfigurationActive(config: AudioPlaybackConfiguration): Boolean {
        // Attempt 1: isActive() (API 29+)
        try {
            val method = config.javaClass.getMethod("isActive")
            val active = method.invoke(config) as? Boolean
            if (active != null) return active
        } catch (ignored: Exception) {}

        // Attempt 2: getPlayerState() == 2 (PLAYER_STATE_STARTED)
        try {
            val method = config.javaClass.getMethod("getPlayerState")
            val state = method.invoke(config) as? Int
            if (state != null) {
                return state == 2 // PLAYER_STATE_STARTED
            }
        } catch (ignored: Exception) {}

        // Attempt 3: toString() contains state:started
        try {
            val str = config.toString()
            if (str.contains("state:started") || str.contains("state:PLAYER_STATE_STARTED")) {
                return true
            }
        } catch (ignored: Exception) {}

        return false
    }

    private fun isConfiguration24Bit(config: AudioPlaybackConfiguration, sampleRate: Int): Boolean {
        if (sampleRate >= 88200) return true
        try {
            val str = config.toString().lowercase()
            if (str.contains("24bit") || str.contains("float") || str.contains("32bit")) {
                return true
            }
        } catch (ignored: Exception) {}
        return false
    }
}
