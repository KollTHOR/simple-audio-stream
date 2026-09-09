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
        val appName: String,
        val packageName: String,
        val sampleRate: Int,
        val is24Bit: Boolean,
        val isPlaying: Boolean,
        val description: String
    )

    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    private var activeListener: ((DetectedMediaFormat) -> Unit)? = null
    @Volatile var lastReportedFormat: DetectedMediaFormat? = null
        private set

    /**
     * Inspects active playback configurations and returns the currently playing media format.
     * If multiple are active, prioritizes media playback (USAGE_MEDIA) over system sounds.
     */
    fun getActiveMediaFormat(context: Context): DetectedMediaFormat {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) {
            return DetectedMediaFormat(
                appName = "None",
                packageName = "",
                sampleRate = AudioConfig.SAMPLE_RATE_48000,
                is24Bit = false,
                isPlaying = false,
                description = "Default 48.0 kHz (AudioManager unavailable)"
            )
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
        var detectedAppName = "None"
        var detectedPkgName = ""

        for (config in configs) {
            val attr = config.audioAttributes
            val usage = attr.usage
            val isMedia = (usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME)
            if (!isMedia) continue

            val uid = getClientUid(config)
            val (appLabel, pkg) = getAppInfoForUid(context, uid)

            val isConfigPlaying = isConfigurationActive(config)
            var configRate = extractSampleRate(config)
            val config24Bit = isConfiguration24Bit(config, configRate)

            // If sample rate was not explicitly reported by port, check known streaming apps
            if (configRate == 0 && isConfigPlaying) {
                val lowerPkg = pkg.lowercase()
                val lowerLabel = appLabel.lowercase()
                if (lowerPkg.contains("tidal") || lowerLabel.contains("tidal") ||
                    lowerPkg.contains("spotify") || lowerLabel.contains("spotify") ||
                    lowerPkg.contains("qobuz") || lowerLabel.contains("deezer") ||
                    lowerPkg.contains("apple.android.music")) {
                    configRate = AudioConfig.SAMPLE_RATE_44100
                } else if (lowerPkg.contains("youtube") || lowerLabel.contains("youtube")) {
                    configRate = AudioConfig.SAMPLE_RATE_48000
                }
            }

            if (isConfigPlaying) {
                isPlayingMedia = true
                detectedAppName = appLabel
                detectedPkgName = pkg
                if (configRate > 0) {
                    detectedRate = configRate
                    isDetected24Bit = config24Bit
                    break
                }
            } else if (detectedRate == 0 && configRate > 0) {
                // Keep as standby if no active player found yet
                detectedAppName = appLabel
                detectedPkgName = pkg
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
            "$detectedAppName: ${effectiveRate / 1000.0} kHz / ${if (effective24Bit) "24-bit" else "16-bit"} (Playing)"
        } else if (detectedAppName != "None") {
            "$detectedAppName: ${effectiveRate / 1000.0} kHz / ${if (effective24Bit) "24-bit" else "16-bit"} (Paused/Standby)"
        } else {
            "System Idle: ${effectiveRate / 1000.0} kHz / ${if (effective24Bit) "24-bit" else "16-bit"}"
        }

        val result = DetectedMediaFormat(
            appName = detectedAppName,
            packageName = detectedPkgName,
            sampleRate = effectiveRate,
            is24Bit = effective24Bit,
            isPlaying = isPlayingMedia,
            description = desc
        )
        lastReportedFormat = result
        return result
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
                    lastReportedFormat = current
                    activeListener?.invoke(current)
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
    }

    private fun getClientUid(config: AudioPlaybackConfiguration): Int {
        try {
            val method = config.javaClass.getMethod("getClientUid")
            val uid = method.invoke(config) as? Int
            if (uid != null && uid > 0) return uid
        } catch (ignored: Exception) {}

        try {
            val field = config.javaClass.getDeclaredField("mClientUid")
            field.isAccessible = true
            val uid = field.getInt(config)
            if (uid > 0) return uid
        } catch (ignored: Exception) {}

        return 0
    }

    private fun getAppInfoForUid(context: Context, uid: Int): Pair<String, String> {
        if (uid <= 0) return Pair("None", "")
        try {
            val pm = context.packageManager
            val packages = pm.getPackagesForUid(uid)
            if (!packages.isNullOrEmpty()) {
                val pkg = packages[0]
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                return Pair(label, pkg)
            }
        } catch (ignored: Exception) {}
        return Pair("UID $uid", "")
    }

    private fun extractSampleRate(config: AudioPlaybackConfiguration): Int {
        // Attempt 1: Direct reflection on getSampleRate()
        try {
            val method = config.javaClass.getMethod("getSampleRate")
            val rate = method.invoke(config) as? Int
            if (rate != null && rate > 0) return rate
        } catch (ignored: Exception) {}

        // Attempt 2: mFormatInfo field reflection
        try {
            val f = config.javaClass.getDeclaredField("mFormatInfo")
            f.isAccessible = true
            val fi = f.get(config)
            if (fi != null) {
                val srf = fi.javaClass.getDeclaredField("mSampleRate")
                srf.isAccessible = true
                val rate = srf.getInt(fi)
                if (rate > 0) return rate
            }
        } catch (ignored: Exception) {}

        // Attempt 3: Parse toString() for sampleRate=XXXXX
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

        // Attempt 3: mPlayerState field
        try {
            val field = config.javaClass.getDeclaredField("mPlayerState")
            field.isAccessible = true
            val state = field.getInt(config)
            return state == 2
        } catch (ignored: Exception) {}

        // Attempt 4: toString() contains state:started or state:PLAYER_STATE_STARTED
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
