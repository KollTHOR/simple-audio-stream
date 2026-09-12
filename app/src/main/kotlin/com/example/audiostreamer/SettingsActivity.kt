package com.example.audiostreamer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
        private const val GITHUB_REPO_URL = "https://github.com/KollTHOR/simple-audio-stream"
        private const val GITHUB_API_RELEASES =
            "https://api.github.com/repos/KollTHOR/simple-audio-stream/releases?per_page=10"
        private const val GITHUB_API_LATEST_RELEASE =
            "https://api.github.com/repos/KollTHOR/simple-audio-stream/releases/latest"
    }

    private lateinit var btnBack: ImageView
    private lateinit var tvAppVersion: TextView
    private lateinit var btnGithubRepo: MaterialButton
    private lateinit var tvUpdateStatus: TextView
    private lateinit var pbDownload: ProgressBar
    private lateinit var btnCheckUpdate: MaterialButton
    private lateinit var toggleProfileGroup: MaterialButtonToggleGroup
    private lateinit var btnProfileAuto: MaterialButton
    private lateinit var btnProfileVideo: MaterialButton
    private lateinit var btnProfileMusic: MaterialButton
    private lateinit var tvProfileDescription: TextView
    private lateinit var cardSampleRate: com.google.android.material.card.MaterialCardView
    private lateinit var toggleRateGroup: MaterialButtonToggleGroup
    private lateinit var btnRateAuto: MaterialButton
    private lateinit var btnRate44k: MaterialButton
    private lateinit var btnRate48k: MaterialButton
    private lateinit var btnRate96k: MaterialButton
    private lateinit var btnRate192k: MaterialButton
    private lateinit var tvRateDescription: TextView
    private lateinit var cardBitDepth: com.google.android.material.card.MaterialCardView
    private lateinit var toggleBitGroup: MaterialButtonToggleGroup
    private lateinit var btnBitAuto: MaterialButton
    private lateinit var btnBit16: MaterialButton
    private lateinit var btnBit24: MaterialButton
    private lateinit var tvBitDescription: TextView
    private lateinit var cardAudioStats: com.google.android.material.card.MaterialCardView
    private lateinit var tvSourceCapability: TextView
    private lateinit var tvReceiverCapability: TextView
    private lateinit var tvDetectedMediaApp: TextView
    private lateinit var tvDetectedMediaFormat: TextView
    private lateinit var tvDetectedStreamStatus: TextView
    private lateinit var btnRefreshAudioStats: ImageView
    private lateinit var btnAppInfo: MaterialButton
    private lateinit var btnAccessibilitySettings: MaterialButton

    enum class UpdateState {
        CHECK,
        DOWNLOAD,
        INSTALL
    }

    private var updateState = UpdateState.CHECK
    private var latestReleaseTag: String? = null
    private var latestApkUrl: String? = null
    private var downloadedApkFile: File? = null
    private var isWaitingForInstallPermission = false

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (packageManager.canRequestPackageInstalls()) {
                isWaitingForInstallPermission = false
                val file = downloadedApkFile
                if (file != null && file.exists() && file.length() > 500_000) {
                    installApk(file)
                }
            } else {
                Toast.makeText(this, "Install permission is required to update", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root_settings)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

        btnBack = findViewById(R.id.btn_back)
        tvAppVersion = findViewById(R.id.tv_app_version)
        btnGithubRepo = findViewById(R.id.btn_github_repo)
        tvUpdateStatus = findViewById(R.id.tv_update_status)
        pbDownload = findViewById(R.id.pb_download)
        btnCheckUpdate = findViewById(R.id.btn_check_update)
        toggleProfileGroup = findViewById(R.id.toggle_profile_group)
        btnProfileAuto = findViewById(R.id.btn_profile_auto)
        btnProfileVideo = findViewById(R.id.btn_profile_video)
        btnProfileMusic = findViewById(R.id.btn_profile_music)
        tvProfileDescription = findViewById(R.id.tv_profile_description)
        cardSampleRate = findViewById(R.id.card_sample_rate)
        toggleRateGroup = findViewById(R.id.toggle_rate_group)
        btnRateAuto = findViewById(R.id.btn_rate_auto)
        btnRate44k = findViewById(R.id.btn_rate_44k)
        btnRate48k = findViewById(R.id.btn_rate_48k)
        btnRate96k = findViewById(R.id.btn_rate_96k)
        btnRate192k = findViewById(R.id.btn_rate_192k)
        tvRateDescription = findViewById(R.id.tv_rate_description)
        cardBitDepth = findViewById(R.id.card_bit_depth)
        toggleBitGroup = findViewById(R.id.toggle_bit_group)
        btnBitAuto = findViewById(R.id.btn_bit_auto)
        btnBit16 = findViewById(R.id.btn_bit_16)
        btnBit24 = findViewById(R.id.btn_bit_24)
        tvBitDescription = findViewById(R.id.tv_bit_description)

        cardAudioStats = findViewById(R.id.card_audio_stats)
        tvSourceCapability = findViewById(R.id.tv_source_capability)
        tvReceiverCapability = findViewById(R.id.tv_receiver_capability)
        tvDetectedMediaApp = findViewById(R.id.tv_detected_media_app)
        tvDetectedMediaFormat = findViewById(R.id.tv_detected_media_format)
        tvDetectedStreamStatus = findViewById(R.id.tv_detected_stream_status)
        btnRefreshAudioStats = findViewById(R.id.btn_refresh_audio_stats)

        btnRefreshAudioStats.setOnClickListener {
            updateAudioStatsUi()
        }

        val prefs = getSharedPreferences("stream_prefs", Context.MODE_PRIVATE)
        val currentProfile = prefs.getString(AudioConfig.PREF_KEY_PROFILE, AudioConfig.PROFILE_AUTO) ?: AudioConfig.PROFILE_AUTO
        when (currentProfile) {
            AudioConfig.PROFILE_VIDEO, AudioConfig.PROFILE_LOW_LATENCY -> toggleProfileGroup.check(R.id.btn_profile_video)
            AudioConfig.PROFILE_MUSIC -> toggleProfileGroup.check(R.id.btn_profile_music)
            else -> toggleProfileGroup.check(R.id.btn_profile_auto)
        }
        updateProfileUi(currentProfile)

        toggleProfileGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val selected = when (checkedId) {
                    R.id.btn_profile_video -> AudioConfig.PROFILE_VIDEO
                    R.id.btn_profile_music -> AudioConfig.PROFILE_MUSIC
                    else -> AudioConfig.PROFILE_AUTO
                }
                prefs.edit().putString(AudioConfig.PREF_KEY_PROFILE, selected).apply()
                updateProfileUi(selected)
                notifySettingsChanged()
            }
        }

        val rawRate = prefs.getString(AudioConfig.PREF_KEY_SAMPLE_RATE, AudioConfig.SAMPLE_RATE_AUTO) ?: AudioConfig.SAMPLE_RATE_AUTO
        // Sanitize legacy 96k/192k settings to native 48 kHz
        val currentRate = if (rawRate == AudioConfig.SAMPLE_RATE_96K || rawRate == AudioConfig.SAMPLE_RATE_192K) {
            prefs.edit().putString(AudioConfig.PREF_KEY_SAMPLE_RATE, AudioConfig.SAMPLE_RATE_48K).apply()
            AudioConfig.SAMPLE_RATE_48K
        } else {
            rawRate
        }
        when (currentRate) {
            AudioConfig.SAMPLE_RATE_44K -> toggleRateGroup.check(R.id.btn_rate_44k)
            AudioConfig.SAMPLE_RATE_48K -> toggleRateGroup.check(R.id.btn_rate_48k)
            else -> toggleRateGroup.check(R.id.btn_rate_auto)
        }
        updateRateUi(currentRate)

        toggleRateGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val selected = when (checkedId) {
                    R.id.btn_rate_44k -> AudioConfig.SAMPLE_RATE_44K
                    R.id.btn_rate_48k -> AudioConfig.SAMPLE_RATE_48K
                    else -> AudioConfig.SAMPLE_RATE_AUTO
                }
                prefs.edit().putString(AudioConfig.PREF_KEY_SAMPLE_RATE, selected).apply()
                updateRateUi(selected)
                notifySettingsChanged()
            }
        }

        val currentBit = prefs.getString(AudioConfig.PREF_KEY_BIT_DEPTH, AudioConfig.BIT_DEPTH_AUTO) ?: AudioConfig.BIT_DEPTH_AUTO
        when (currentBit) {
            AudioConfig.BIT_DEPTH_16 -> toggleBitGroup.check(R.id.btn_bit_16)
            AudioConfig.BIT_DEPTH_24 -> toggleBitGroup.check(R.id.btn_bit_24)
            else -> toggleBitGroup.check(R.id.btn_bit_auto)
        }
        updateBitUi(currentBit)

        toggleBitGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val selected = when (checkedId) {
                    R.id.btn_bit_16 -> AudioConfig.BIT_DEPTH_16
                    R.id.btn_bit_24 -> AudioConfig.BIT_DEPTH_24
                    else -> AudioConfig.BIT_DEPTH_AUTO
                }
                prefs.edit().putString(AudioConfig.PREF_KEY_BIT_DEPTH, selected).apply()
                updateBitUi(selected)
                notifySettingsChanged()
            }
        }

        val switchSyncDeviceVolume = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_sync_device_volume)
        val initialSyncVol = prefs.getBoolean(AudioConfig.PREF_KEY_SYNC_DEVICE_VOLUME, true)
        switchSyncDeviceVolume?.isChecked = initialSyncVol
        switchSyncDeviceVolume?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(AudioConfig.PREF_KEY_SYNC_DEVICE_VOLUME, isChecked).apply()
        }

        btnBack.setOnClickListener { finish() }

        val currentVersion = BuildConfig.VERSION_NAME
        tvAppVersion.text = "Version $currentVersion (Build ${BuildConfig.VERSION_CODE})"

        btnGithubRepo.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL))
            startActivity(intent)
        }

        btnAppInfo = findViewById(R.id.btn_app_info)
        btnAccessibilitySettings = findViewById(R.id.btn_accessibility_settings)

        btnAppInfo.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
            Toast.makeText(this, "Tap the 3 dots at top-right -> Allow restricted settings", Toast.LENGTH_LONG).show()
        }

        btnAccessibilitySettings.setOnClickListener {
            if (VolumeKeyInterceptorService.isRunning.get()) {
                Toast.makeText(this, "Background volume key interception is active", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Turn on Audio Streamer in Accessibility settings", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val btnCopyLogs = findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_copy_logs)
        val btnShareLogs = findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_share_logs)
        val btnViewLogs = findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_view_logs)

        btnCopyLogs?.setOnClickListener {
            AppLogger.copyToClipboard(this)
        }
        btnShareLogs?.setOnClickListener {
            AppLogger.shareLogs(this)
        }
        btnViewLogs?.setOnClickListener {
            AppLogger.showLogViewerDialog(this)
        }

        btnCheckUpdate.text = "Check for Updates"
        updateState = UpdateState.CHECK

        btnCheckUpdate.setOnClickListener {
            when (updateState) {
                UpdateState.CHECK -> checkForUpdates()
                UpdateState.DOWNLOAD -> {
                    val url = latestApkUrl
                    val tag = latestReleaseTag
                    if (url != null && tag != null) {
                        downloadAndPromptInstall(url, tag)
                    } else {
                        checkForUpdates()
                    }
                }
                UpdateState.INSTALL -> {
                    val file = downloadedApkFile
                    if (file != null && file.exists() && file.length() > 500_000) {
                        checkPermissionAndInstall(file)
                    } else {
                        checkForUpdates()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkInstallPermissionOnResume()
        updateAccessibilityButton()
        updateAudioStatsUi()
    }

    private fun updateAccessibilityButton() {
        val isServiceActive = VolumeKeyInterceptorService.isRunning.get()
        if (isServiceActive) {
            btnAccessibilitySettings.text = "Step 2: Accessibility (Active)"
            val colorGreen = ContextCompat.getColor(this, R.color.status_green)
            btnAccessibilitySettings.setTextColor(colorGreen)
            btnAccessibilitySettings.strokeColor = ColorStateList.valueOf(colorGreen)
            btnAppInfo.text = "Step 1: App Info (Completed)"
            btnAppInfo.setTextColor(colorGreen)
            btnAppInfo.strokeColor = ColorStateList.valueOf(colorGreen)
        } else {
            btnAccessibilitySettings.text = "Step 2: Enable in Accessibility Settings"
            val colorPrimary = ContextCompat.getColor(this, R.color.primary)
            btnAccessibilitySettings.setTextColor(colorPrimary)
            btnAccessibilitySettings.strokeColor = ColorStateList.valueOf(colorPrimary)
            btnAppInfo.text = "Step 1: Open App Info (Allow Restricted Settings)"
            val colorText = ContextCompat.getColor(this, R.color.text_primary)
            val colorStroke = ContextCompat.getColor(this, R.color.card_stroke)
            btnAppInfo.setTextColor(colorText)
            btnAppInfo.strokeColor = ColorStateList.valueOf(colorStroke)
        }
    }

    private fun checkInstallPermissionOnResume() {
        if (isWaitingForInstallPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (packageManager.canRequestPackageInstalls()) {
                isWaitingForInstallPermission = false
                val file = downloadedApkFile
                if (file != null && file.exists() && file.length() > 500_000) {
                    installApk(file)
                }
            }
        }
    }

    private fun updateProfileUi(profile: String) {
        val colorPrimary = ContextCompat.getColor(this, R.color.primary)
        val colorCard = ContextCompat.getColor(this, R.color.card_bg)
        val colorTextSecondary = ContextCompat.getColor(this, R.color.text_secondary)

        val isVideo = (profile == AudioConfig.PROFILE_VIDEO || profile == AudioConfig.PROFILE_LOW_LATENCY)
        val isMusic = (profile == AudioConfig.PROFILE_MUSIC)
        val isAuto = (!isVideo && !isMusic)

        btnProfileAuto.backgroundTintList = ColorStateList.valueOf(if (isAuto) colorPrimary else colorCard)
        btnProfileAuto.setTextColor(if (isAuto) Color.WHITE else colorTextSecondary)

        btnProfileVideo.backgroundTintList = ColorStateList.valueOf(if (isVideo) colorPrimary else colorCard)
        btnProfileVideo.setTextColor(if (isVideo) Color.WHITE else colorTextSecondary)

        btnProfileMusic.backgroundTintList = ColorStateList.valueOf(if (isMusic) colorPrimary else colorCard)
        btnProfileMusic.setTextColor(if (isMusic) Color.WHITE else colorTextSecondary)

        cardSampleRate.visibility = if (isMusic) View.VISIBLE else View.GONE
        cardBitDepth.visibility = if (isMusic) View.VISIBLE else View.GONE

        tvProfileDescription.text = when {
            isVideo -> "Low Latency (Opus): Locked to 16-bit / 48 kHz with pure compressed Opus (320 kbps VBR) and 40ms cushion. Instantaneous response, 80-85% less Wi-Fi airtime for video lip-sync and gaming."
            isMusic -> "Unlocked Music Mode: Bit-perfect lossless PCM locked to Android native rates (48 kHz primary / 44.1 kHz CD audio) with 24-bit studio precision and zero resampling artifacts."
            else -> "Auto Adaptive Mode: Fully autoselects 24-bit / 48 kHz stereo (or 44.1 kHz for CD audio) based on active Android media playback. Dynamically floats jitter watermark between 35ms and 400ms using RFC 3550 statistical estimation."
        }
    }

    private fun updateAudioStatsUi() {
        val txCaps = AudioCapabilities.getLocalCaptureCapabilitiesMask()
        tvSourceCapability.text = "Android HAL: ${AudioCapabilities.describeCapabilities(txCaps)}"

        val tel = StreamState.telemetry.value
        val rxCapsFromPref = getSharedPreferences("stream_prefs", Context.MODE_PRIVATE).getInt(AudioConfig.PREF_KEY_RECEIVER_CAPS, 0)
        val rxDesc = when {
            tel.receiverCapabilityDesc != "Unknown" && tel.receiverCapabilityDesc.isNotBlank() -> tel.receiverCapabilityDesc
            rxCapsFromPref != 0 -> AudioCapabilities.describeCapabilities(rxCapsFromPref)
            else -> "Pending receiver discovery"
        }
        tvReceiverCapability.text = rxDesc

        val detected = AudioPlaybackDetector.getActiveMediaFormat(this)
        val appText = if (detected.isPlaying) {
            "${detected.appName} (Playing)"
        } else if (detected.appName != "None") {
            "${detected.appName} (Paused/Standby)"
        } else {
            "No Active Media"
        }
        tvDetectedMediaApp.text = appText
        val greenColor = ContextCompat.getColor(this, R.color.status_green)
        val hintColor = ContextCompat.getColor(this, R.color.text_hint)
        tvDetectedMediaApp.setTextColor(if (detected.isPlaying) greenColor else hintColor)

        val rateKHz = detected.sampleRate / 1000.0
        val bitStr = if (detected.is24Bit) "24-bit" else "16-bit"
        tvDetectedMediaFormat.text = "$rateKHz kHz • $bitStr"

        val isTxRunning = AudioCaptureService.isRunning.get()
        if (isTxRunning && tel.isActive && tel.isTransmitter) {
            tvDetectedStreamStatus.text = tel.negotiatedFormatDesc
            tvDetectedStreamStatus.setTextColor(greenColor)
        } else if (isTxRunning) {
            tvDetectedStreamStatus.text = "Transmitter starting..."
            tvDetectedStreamStatus.setTextColor(ContextCompat.getColor(this, R.color.status_blue))
        } else {
            tvDetectedStreamStatus.text = "Idle (Not transmitting)"
            tvDetectedStreamStatus.setTextColor(hintColor)
        }
    }

    private fun updateRateUi(rate: String) {
        val colorPrimary = ContextCompat.getColor(this, R.color.primary)
        val colorCard = ContextCompat.getColor(this, R.color.card_bg)
        val colorTextSecondary = ContextCompat.getColor(this, R.color.text_secondary)

        // Disable 96k and 192k on Android - hardware mix bus runs at 48k/44.1k
        btnRate96k.isEnabled = false
        btnRate96k.alpha = 0.35f
        btnRate96k.text = "96.0k (N/A)"
        btnRate192k.isEnabled = false
        btnRate192k.alpha = 0.35f
        btnRate192k.text = "192k (N/A)"

        val isAutoSelected = (rate == AudioConfig.SAMPLE_RATE_AUTO)
        val is44Selected = (rate == AudioConfig.SAMPLE_RATE_44K)
        val is48Selected = (rate == AudioConfig.SAMPLE_RATE_48K)

        btnRateAuto.backgroundTintList = ColorStateList.valueOf(if (isAutoSelected) colorPrimary else colorCard)
        btnRateAuto.setTextColor(if (isAutoSelected) Color.WHITE else colorTextSecondary)

        btnRate44k.backgroundTintList = ColorStateList.valueOf(if (is44Selected) colorPrimary else colorCard)
        btnRate44k.setTextColor(if (is44Selected) Color.WHITE else colorTextSecondary)

        btnRate48k.backgroundTintList = ColorStateList.valueOf(if (is48Selected) colorPrimary else colorCard)
        btnRate48k.setTextColor(if (is48Selected) Color.WHITE else colorTextSecondary)

        btnRate96k.backgroundTintList = ColorStateList.valueOf(colorCard)
        btnRate96k.setTextColor(colorTextSecondary)

        btnRate192k.backgroundTintList = ColorStateList.valueOf(colorCard)
        btnRate192k.setTextColor(colorTextSecondary)

        tvRateDescription.text = when (rate) {
            AudioConfig.SAMPLE_RATE_44K -> "44.1 kHz: Native CD-quality streaming (1320 bytes 24-bit / 880 bytes 16-bit). Direct uncompressed PCM."
            AudioConfig.SAMPLE_RATE_48K -> "48.0 kHz: Native Android operating point (1440 bytes 24-bit / 960 bytes 16-bit). Direct uncompressed PCM with zero resampler distortion."
            AudioConfig.SAMPLE_RATE_96K, AudioConfig.SAMPLE_RATE_192K -> "Android HAL natively operates at 48.0 kHz. Capturing >48 kHz induces AudioFlinger resampler distortion."
            else -> "Auto: Synchronizes sample rate with Android audio engine. Operates natively at 48.0 kHz 24-bit (or 44.1 kHz for CD audio) to completely avoid AudioFlinger comb filtering."
        }
    }

    private fun updateBitUi(bit: String) {
        val colorPrimary = ContextCompat.getColor(this, R.color.primary)
        val colorCard = ContextCompat.getColor(this, R.color.card_bg)
        val colorTextSecondary = ContextCompat.getColor(this, R.color.text_secondary)

        val isAutoSelected = (bit == AudioConfig.BIT_DEPTH_AUTO)
        val is16Selected = (bit == AudioConfig.BIT_DEPTH_16)
        val is24Selected = (bit == AudioConfig.BIT_DEPTH_24)

        btnBitAuto.backgroundTintList = ColorStateList.valueOf(if (isAutoSelected) colorPrimary else colorCard)
        btnBitAuto.setTextColor(if (isAutoSelected) Color.WHITE else colorTextSecondary)

        btnBit16.backgroundTintList = ColorStateList.valueOf(if (is16Selected) colorPrimary else colorCard)
        btnBit16.setTextColor(if (is16Selected) Color.WHITE else colorTextSecondary)

        btnBit24.backgroundTintList = ColorStateList.valueOf(if (is24Selected) colorPrimary else colorCard)
        btnBit24.setTextColor(if (is24Selected) Color.WHITE else colorTextSecondary)

        tvBitDescription.text = when (bit) {
            AudioConfig.BIT_DEPTH_16 -> "16-bit: Standard 16-bit integer PCM. Bit-for-bit match for CD audio, Tidal HiFi, and Spotify with zero upsampling."
            AudioConfig.BIT_DEPTH_24 -> "24-bit: Lossless 24-bit packed PCM. 144 dB theoretical dynamic range for studio masters."
            else -> "Auto: Automatically uses 16-bit for CD/standard streams (44.1k/48k) to avoid artificial upsampling, and 24-bit for Hi-Res streams."
        }
    }

    private fun notifySettingsChanged() {
        if (AudioCaptureService.isRunning.get()) {
            val restartIntent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioConfig.ACTION_RESTART_CAPTURE
            }
            startService(restartIntent)
            Log.i(TAG, "Sent ACTION_RESTART_CAPTURE to apply setting change live")
        }
    }

    private fun parseVersion(versionStr: String): List<Int> {
        val clean = versionStr.removePrefix("v").trim()
        return clean.split(".").mapNotNull { it.toIntOrNull() }
    }

    private fun compareVersions(v1: List<Int>, v2: List<Int>): Int {
        val maxLen = maxOf(v1.size, v2.size)
        for (i in 0 until maxLen) {
            val p1 = v1.getOrElse(i) { 0 }
            val p2 = v2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }

    private fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
        return compareVersions(parseVersion(remoteTag), parseVersion(currentVersion)) > 0
    }

    private fun checkForUpdates() {
        btnCheckUpdate.isEnabled = false
        tvUpdateStatus.text = "Checking GitHub for latest release..."
        pbDownload.visibility = View.VISIBLE
        pbDownload.isIndeterminate = true

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var tagName: String? = null
                var apkDownloadUrl: String? = null

                // Attempt fetching releases list to identify the highest semantic release with an APK
                try {
                    val releasesUrl = URL(GITHUB_API_RELEASES)
                    val conn = (releasesUrl.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 8000
                        setRequestProperty("Accept", "application/vnd.github.v3+json")
                        setRequestProperty("User-Agent", "SimpleAudioStream-Android")
                    }

                    if (conn.responseCode == 200) {
                        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                        val releasesArray = JSONArray(responseText)

                        var bestTag: String? = null
                        var bestUrl: String? = null
                        var bestVer: List<Int>? = null

                        for (i in 0 until releasesArray.length()) {
                            val rel = releasesArray.getJSONObject(i)
                            if (rel.optBoolean("draft", false)) continue
                            val tag = rel.optString("tag_name", "")
                            if (tag.isEmpty()) continue

                            val assets = rel.optJSONArray("assets") ?: continue
                            var foundApk: String? = null
                            for (j in 0 until assets.length()) {
                                val asset = assets.getJSONObject(j)
                                val name = asset.optString("name")
                                if (name.endsWith(".apk", ignoreCase = true)) {
                                    foundApk = asset.optString("browser_download_url")
                                    break
                                }
                            }

                            if (foundApk != null) {
                                val ver = parseVersion(tag)
                                if (bestVer == null || compareVersions(ver, bestVer) > 0) {
                                    bestVer = ver
                                    bestTag = tag
                                    bestUrl = foundApk
                                }
                            }
                        }

                        tagName = bestTag
                        apkDownloadUrl = bestUrl
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Releases list query failed, falling back to latest: ${e.message}")
                }

                // Fallback to /releases/latest if list did not yield an APK
                if (tagName == null || apkDownloadUrl == null) {
                    val latestConn = (URL(GITHUB_API_LATEST_RELEASE).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 8000
                        setRequestProperty("Accept", "application/vnd.github.v3+json")
                        setRequestProperty("User-Agent", "SimpleAudioStream-Android")
                    }
                    if (latestConn.responseCode == 200) {
                        val responseText = latestConn.inputStream.bufferedReader().use { it.readText() }
                        val releaseJson = JSONObject(responseText)
                        tagName = releaseJson.optString("tag_name", "v1.0.0")
                        val assets = releaseJson.optJSONArray("assets")
                        if (assets != null) {
                            for (i in 0 until assets.length()) {
                                val asset = assets.getJSONObject(i)
                                val name = asset.optString("name")
                                if (name.endsWith(".apk", ignoreCase = true)) {
                                    apkDownloadUrl = asset.optString("browser_download_url")
                                    break
                                }
                            }
                        }
                    }
                }

                val finalTag = tagName
                val finalApkUrl = apkDownloadUrl

                withContext(Dispatchers.Main) {
                    pbDownload.visibility = View.GONE
                    btnCheckUpdate.isEnabled = true

                    if (finalTag == null) {
                        updateState = UpdateState.CHECK
                        tvUpdateStatus.text = "No releases found on GitHub."
                        btnCheckUpdate.text = "Check for Updates"
                        return@withContext
                    }

                    latestReleaseTag = finalTag

                    if (finalApkUrl != null) {
                        latestApkUrl = finalApkUrl
                        val targetDir = cacheDir
                        val versionedApk = File(targetDir, "simple-audio-stream-$finalTag.apk")

                        if (isNewerVersion(finalTag, BuildConfig.VERSION_NAME)) {
                            // Clean any stale cached APKs from earlier versions or external storage
                            try {
                                listOfNotNull(cacheDir, externalCacheDir).forEach { dir ->
                                    dir.listFiles { _, name ->
                                        name.startsWith("simple-audio-stream") && name.endsWith(".apk") && !name.contains(finalTag)
                                    }?.forEach { it.delete() }
                                }
                            } catch (ignored: Exception) {}

                            if (versionedApk.exists() && versionedApk.length() > 500_000) {
                                downloadedApkFile = versionedApk
                                updateState = UpdateState.INSTALL
                                tvUpdateStatus.text = "Update downloaded ($finalTag).\nTap below to install."
                                btnCheckUpdate.text = "Install APK ($finalTag)"
                            } else {
                                downloadedApkFile = null
                                updateState = UpdateState.DOWNLOAD
                                tvUpdateStatus.text = "New release found: $finalTag\nTap below to download and install."
                                btnCheckUpdate.text = "Download Update ($finalTag)"
                            }
                        } else {
                            downloadedApkFile = null
                            updateState = UpdateState.CHECK
                            tvUpdateStatus.text = "You are on the latest version ($finalTag)."
                            btnCheckUpdate.text = "Check for Updates"

                            // Clean up old cached update APKs to free storage
                            try {
                                listOfNotNull(cacheDir, externalCacheDir).forEach { dir ->
                                    dir.listFiles { _, name ->
                                        name.startsWith("simple-audio-stream") && name.endsWith(".apk")
                                    }?.forEach { it.delete() }
                                }
                            } catch (ignored: Exception) {}
                        }
                    } else {
                        updateState = UpdateState.CHECK
                        tvUpdateStatus.text = "Latest release $finalTag found, but no APK asset attached."
                        btnCheckUpdate.text = "Check for Updates"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    pbDownload.visibility = View.GONE
                    btnCheckUpdate.isEnabled = true
                    updateState = UpdateState.CHECK
                    tvUpdateStatus.text = "Failed checking updates: ${e.localizedMessage}"
                    btnCheckUpdate.text = "Check for Updates"
                }
            }
        }
    }

    private fun downloadAndPromptInstall(apkUrl: String, tagName: String) {
        btnCheckUpdate.isEnabled = false
        btnCheckUpdate.text = "Downloading..."
        pbDownload.visibility = View.VISIBLE
        pbDownload.isIndeterminate = false
        pbDownload.progress = 0
        tvUpdateStatus.text = "Downloading APK from GitHub ($tagName)..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val targetDir = cacheDir
                val apkFile = File(targetDir, "simple-audio-stream-$tagName.apk")
                listOfNotNull(cacheDir, externalCacheDir).forEach { dir ->
                    val f = File(dir, "simple-audio-stream-$tagName.apk")
                    if (f.exists()) f.delete()
                }

                var currentUrl = apkUrl
                var redirectCount = 0
                var conn: HttpURLConnection

                // Follow HTTP redirects (GitHub asset downloads redirect to AWS S3)
                while (true) {
                    val url = URL(currentUrl)
                    conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("User-Agent", "SimpleAudioStream-Android")

                    val code = conn.responseCode
                    if (code in 301..308) {
                        currentUrl = conn.getHeaderField("Location")
                        redirectCount++
                        if (redirectCount > 5) throw RuntimeException("Too many redirects")
                    } else if (code == 200) {
                        break
                    } else {
                        throw RuntimeException("HTTP $code on download")
                    }
                }

                val totalLength = conn.contentLength
                var downloadedBytes = 0L

                conn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalLength > 0) {
                                val progress = ((downloadedBytes * 100) / totalLength).toInt()
                                withContext(Dispatchers.Main) {
                                    pbDownload.progress = progress
                                    tvUpdateStatus.text = "Downloading: $progress% (${downloadedBytes / 1024 / 1024}MB / ${totalLength / 1024 / 1024}MB)"
                                }
                            }
                        }
                    }
                }

                downloadedApkFile = apkFile

                withContext(Dispatchers.Main) {
                    pbDownload.visibility = View.GONE
                    btnCheckUpdate.isEnabled = true
                    btnCheckUpdate.text = "Install APK ($tagName)"
                    updateState = UpdateState.INSTALL
                    tvUpdateStatus.text = "Download complete! Opening package installer..."

                    checkPermissionAndInstall(apkFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    pbDownload.visibility = View.GONE
                    btnCheckUpdate.isEnabled = true
                    btnCheckUpdate.text = "Retry Download"
                    updateState = UpdateState.DOWNLOAD
                    tvUpdateStatus.text = "Download failed: ${e.localizedMessage}"
                }
            }
        }
    }

    private fun checkPermissionAndInstall(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                isWaitingForInstallPermission = true
                Toast.makeText(this, "Allow 'Install unknown apps' permission to update", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
                installPermissionLauncher.launch(intent)
                return
            }
        }
        isWaitingForInstallPermission = false
        installApk(apkFile)
    }

    private fun installApk(apkFile: File) {
        try {
            apkFile.setReadable(true, false)
            val contentUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }

            val resolveList = packageManager.queryIntentActivities(installIntent, PackageManager.MATCH_DEFAULT_ONLY)
            for (resolveInfo in resolveList) {
                grantUriPermission(
                    resolveInfo.activityInfo.packageName,
                    contentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch installer", e)
            Toast.makeText(this, "Failed to launch installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
