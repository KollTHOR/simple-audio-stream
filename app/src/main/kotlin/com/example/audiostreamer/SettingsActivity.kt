package com.example.audiostreamer

import android.content.ClipData
import android.content.ClipboardManager
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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.audiostreamer.diagnostics.DiagnosticsViewModel
import com.example.audiostreamer.diagnostics.LatencyGraphView
import com.example.audiostreamer.diagnostics.ReceiverDiagnosticsState
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
        private const val GITHUB_REPO_URL = "https://github.com/KollTHOR/simple-audio-stream"
        private const val GITHUB_API_RELEASES =
            "https://api.github.com/repos/KollTHOR/simple-audio-stream/releases?per_page=10"
        private const val GITHUB_API_LATEST_RELEASE =
            "https://api.github.com/repos/KollTHOR/simple-audio-stream/releases/latest"

        // Deep-link intent extra keys
        const val EXTRA_CATEGORY = "extra_category"
        const val CATEGORY_AUDIO = "audio"
        const val CATEGORY_DIAGNOSTICS = "diagnostics"
        const val CATEGORY_UPDATES = "updates"
        const val CATEGORY_ABOUT = "about"
    }

    enum class Category {
        MENU,
        AUDIO,
        DIAGNOSTICS,
        UPDATES,
        ABOUT
    }

    enum class UpdateState {
        CHECK,
        DOWNLOAD,
        INSTALL
    }

    // Top Header
    private lateinit var btnBack: ImageView
    private lateinit var tvSettingsTitle: TextView
    private lateinit var tvSettingsSubtitle: TextView

    // Top-Level Menu
    private lateinit var layoutCategoryMenu: LinearLayout
    private lateinit var cardMenuAudio: MaterialCardView
    private lateinit var cardMenuDiagnostics: MaterialCardView
    private lateinit var cardMenuUpdates: MaterialCardView
    private lateinit var cardMenuAbout: MaterialCardView
    private lateinit var tvMenuAudioBadge: TextView
    private lateinit var tvMenuDiagBadge: TextView
    private lateinit var tvMenuUpdatesBadge: TextView

    // Category Layouts
    private lateinit var layoutCategoryAudio: LinearLayout
    private lateinit var layoutCategoryDiagnostics: LinearLayout
    private lateinit var layoutCategoryUpdates: LinearLayout
    private lateinit var layoutCategoryAbout: LinearLayout

    // Audio Category Views
    private lateinit var toggleProfileGroup: MaterialButtonToggleGroup
    private lateinit var btnProfileAuto: MaterialButton
    private lateinit var btnProfileVideo: MaterialButton
    private lateinit var btnProfileMusic: MaterialButton
    private lateinit var tvProfileDescription: TextView
    private lateinit var cardSampleRate: MaterialCardView
    private lateinit var toggleRateGroup: MaterialButtonToggleGroup
    private lateinit var btnRateAuto: MaterialButton
    private lateinit var btnRate44k: MaterialButton
    private lateinit var btnRate48k: MaterialButton
    private lateinit var btnRate96k: MaterialButton
    private lateinit var btnRate192k: MaterialButton
    private lateinit var tvRateDescription: TextView
    private lateinit var cardBitDepth: MaterialCardView
    private lateinit var toggleBitGroup: MaterialButtonToggleGroup
    private lateinit var btnBitAuto: MaterialButton
    private lateinit var btnBit16: MaterialButton
    private lateinit var btnBit24: MaterialButton
    private lateinit var tvBitDescription: TextView
    private lateinit var switchSyncDeviceVolume: SwitchMaterial
    private lateinit var btnAppInfo: MaterialButton
    private lateinit var btnAccessibilitySettings: MaterialButton
    private lateinit var cardAudioStats: MaterialCardView
    private lateinit var tvSourceCapability: TextView
    private lateinit var tvReceiverCapability: TextView
    private lateinit var tvDetectedMediaApp: TextView
    private lateinit var tvDetectedMediaFormat: TextView
    private lateinit var tvDetectedStreamStatus: TextView
    private lateinit var btnRefreshAudioStats: ImageView

    // Diagnostics Category Views
    private lateinit var graphPlayoutLatency: LatencyGraphView
    private lateinit var tvDiagReceiverStatus: TextView
    private lateinit var tvDiagLatencyVal: TextView
    private lateinit var tvDiagJitterVal: TextView
    private lateinit var tvDiagWatermarkVal: TextView
    private lateinit var tvDiagMinAvgMax: TextView
    private lateinit var tvDiagTimelineBreakdown: TextView
    private lateinit var tvDiagBufferDepth: TextView
    private lateinit var pbDiagBufferHealth: ProgressBar
    private lateinit var tvDiagClockDrift: TextView
    private lateinit var tvDiagTrackQueue: TextView
    private lateinit var tvDiagTrackTarget: TextView
    private lateinit var tvDiagTrackCapacity: TextView
    private lateinit var tvDiagUnderruns: TextView
    private lateinit var tvDiagTrackWrites: TextView
    private lateinit var tvDiagPktsReceived: TextView
    private lateinit var tvDiagPktsLost: TextView
    private lateinit var tvDiagPktsLate: TextView
    private lateinit var tvDiagPktsDup: TextView
    private lateinit var tvDiagPktsOoo: TextView
    private lateinit var tvDiagFecRecovered: TextView
    private lateinit var tvDiagSnapshotPreview: TextView
    private lateinit var btnViewLogs: MaterialButton
    private lateinit var btnCopyLogs: MaterialButton
    private lateinit var btnShareLogs: MaterialButton

    // Updates Category Views
    private lateinit var tvAppVersion: TextView
    private lateinit var tvUpdateStatus: TextView
    private lateinit var pbDownload: ProgressBar
    private lateinit var btnCheckUpdate: MaterialButton

    // About Category Views
    private lateinit var tvAboutVersion: TextView
    private lateinit var btnGithubRepo: MaterialButton
    private lateinit var btnAboutAppInfo: MaterialButton
    private lateinit var btnAboutAccessibility: MaterialButton

    // Diagnostics ViewModel
    private lateinit var diagnosticsViewModel: DiagnosticsViewModel

    // State
    private var currentCategory = Category.MENU
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

        diagnosticsViewModel = ViewModelProvider(this)[DiagnosticsViewModel::class.java]

        bindViews()
        setupTopHeaderAndNavigation()
        setupAudioCategory()
        setupDiagnosticsCategory()
        setupUpdatesCategory()
        setupAboutCategory()

        // Handle initial category from Intent extra (e.g. from MainActivity receiver shortcut)
        val targetCategoryStr = intent.getStringExtra(EXTRA_CATEGORY)
        val initialCategory = when (targetCategoryStr?.lowercase(Locale.ROOT)) {
            CATEGORY_AUDIO -> Category.AUDIO
            CATEGORY_DIAGNOSTICS -> Category.DIAGNOSTICS
            CATEGORY_UPDATES -> Category.UPDATES
            CATEGORY_ABOUT -> Category.ABOUT
            else -> Category.MENU
        }
        showCategory(initialCategory)

        observeDiagnostics()
    }

    private fun bindViews() {
        btnBack = findViewById(R.id.btn_back)
        tvSettingsTitle = findViewById(R.id.tv_settings_title)
        tvSettingsSubtitle = findViewById(R.id.tv_settings_subtitle)

        // Menu
        layoutCategoryMenu = findViewById(R.id.layout_category_menu)
        cardMenuAudio = findViewById(R.id.card_menu_audio)
        cardMenuDiagnostics = findViewById(R.id.card_menu_diagnostics)
        cardMenuUpdates = findViewById(R.id.card_menu_updates)
        cardMenuAbout = findViewById(R.id.card_menu_about)
        tvMenuAudioBadge = findViewById(R.id.tv_menu_audio_badge)
        tvMenuDiagBadge = findViewById(R.id.tv_menu_diag_badge)
        tvMenuUpdatesBadge = findViewById(R.id.tv_menu_updates_badge)

        // Category Containers
        layoutCategoryAudio = findViewById(R.id.layout_category_audio)
        layoutCategoryDiagnostics = findViewById(R.id.layout_category_diagnostics)
        layoutCategoryUpdates = findViewById(R.id.layout_category_updates)
        layoutCategoryAbout = findViewById(R.id.layout_category_about)

        // Audio Views
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
        switchSyncDeviceVolume = findViewById(R.id.switch_sync_device_volume)
        btnAppInfo = findViewById(R.id.btn_app_info)
        btnAccessibilitySettings = findViewById(R.id.btn_accessibility_settings)
        cardAudioStats = findViewById(R.id.card_audio_stats)
        tvSourceCapability = findViewById(R.id.tv_source_capability)
        tvReceiverCapability = findViewById(R.id.tv_receiver_capability)
        tvDetectedMediaApp = findViewById(R.id.tv_detected_media_app)
        tvDetectedMediaFormat = findViewById(R.id.tv_detected_media_format)
        tvDetectedStreamStatus = findViewById(R.id.tv_detected_stream_status)
        btnRefreshAudioStats = findViewById(R.id.btn_refresh_audio_stats)

        // Diagnostics Views
        graphPlayoutLatency = findViewById(R.id.graph_playout_latency)
        tvDiagReceiverStatus = findViewById(R.id.tv_diag_receiver_status)
        tvDiagLatencyVal = findViewById(R.id.tv_diag_latency_val)
        tvDiagJitterVal = findViewById(R.id.tv_diag_jitter_val)
        tvDiagWatermarkVal = findViewById(R.id.tv_diag_watermark_val)
        tvDiagMinAvgMax = findViewById(R.id.tv_diag_min_avg_max)
        tvDiagTimelineBreakdown = findViewById(R.id.tv_diag_timeline_breakdown)
        tvDiagBufferDepth = findViewById(R.id.tv_diag_buffer_depth)
        pbDiagBufferHealth = findViewById(R.id.pb_diag_buffer_health)
        tvDiagClockDrift = findViewById(R.id.tv_diag_clock_drift)
        tvDiagTrackQueue = findViewById(R.id.tv_diag_track_queue)
        tvDiagTrackTarget = findViewById(R.id.tv_diag_track_target)
        tvDiagTrackCapacity = findViewById(R.id.tv_diag_track_capacity)
        tvDiagUnderruns = findViewById(R.id.tv_diag_underruns)
        tvDiagTrackWrites = findViewById(R.id.tv_diag_track_writes)
        tvDiagPktsReceived = findViewById(R.id.tv_diag_pkts_received)
        tvDiagPktsLost = findViewById(R.id.tv_diag_pkts_lost)
        tvDiagPktsLate = findViewById(R.id.tv_diag_pkts_late)
        tvDiagPktsDup = findViewById(R.id.tv_diag_pkts_dup)
        tvDiagPktsOoo = findViewById(R.id.tv_diag_pkts_ooo)
        tvDiagFecRecovered = findViewById(R.id.tv_diag_fec_recovered)
        tvDiagSnapshotPreview = findViewById(R.id.tv_diag_snapshot_preview)
        btnViewLogs = findViewById(R.id.btn_view_logs)
        btnCopyLogs = findViewById(R.id.btn_copy_logs)
        btnShareLogs = findViewById(R.id.btn_share_logs)

        // Updates Views
        tvAppVersion = findViewById(R.id.tv_app_version)
        tvUpdateStatus = findViewById(R.id.tv_update_status)
        pbDownload = findViewById(R.id.pb_download)
        btnCheckUpdate = findViewById(R.id.btn_check_update)

        // About Views
        tvAboutVersion = findViewById(R.id.tv_about_version)
        btnGithubRepo = findViewById(R.id.btn_github_repo)
        btnAboutAppInfo = findViewById(R.id.btn_about_app_info)
        btnAboutAccessibility = findViewById(R.id.btn_about_accessibility)
    }

    private fun setupTopHeaderAndNavigation() {
        btnBack.setOnClickListener {
            if (currentCategory != Category.MENU) {
                showCategory(Category.MENU)
            } else {
                finish()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentCategory != Category.MENU) {
                    showCategory(Category.MENU)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        cardMenuAudio.setOnClickListener { showCategory(Category.AUDIO) }
        cardMenuDiagnostics.setOnClickListener { showCategory(Category.DIAGNOSTICS) }
        cardMenuUpdates.setOnClickListener { showCategory(Category.UPDATES) }
        cardMenuAbout.setOnClickListener { showCategory(Category.ABOUT) }
    }

    fun showCategory(category: Category) {
        currentCategory = category

        layoutCategoryMenu.visibility = if (category == Category.MENU) View.VISIBLE else View.GONE
        layoutCategoryAudio.visibility = if (category == Category.AUDIO) View.VISIBLE else View.GONE
        layoutCategoryDiagnostics.visibility = if (category == Category.DIAGNOSTICS) View.VISIBLE else View.GONE
        layoutCategoryUpdates.visibility = if (category == Category.UPDATES) View.VISIBLE else View.GONE
        layoutCategoryAbout.visibility = if (category == Category.ABOUT) View.VISIBLE else View.GONE

        when (category) {
            Category.MENU -> {
                tvSettingsTitle.text = "Settings"
                tvSettingsSubtitle.text = "Simple Audio Stream"
                updateCategoryMenuBadges()
                diagnosticsViewModel.stopSampling()
            }
            Category.AUDIO -> {
                tvSettingsTitle.text = "Audio Settings"
                tvSettingsSubtitle.text = "Streaming Profiles & Preferences"
                diagnosticsViewModel.stopSampling()
            }
            Category.DIAGNOSTICS -> {
                tvSettingsTitle.text = "Diagnostics"
                tvSettingsSubtitle.text = "Estimated Playout & Pipeline Status"
                updateAudioStatsUi()
                diagnosticsViewModel.startSampling()
            }
            Category.UPDATES -> {
                tvSettingsTitle.text = "App Updates"
                tvSettingsSubtitle.text = "GitHub Releases & APK Updater"
                diagnosticsViewModel.stopSampling()
            }
            Category.ABOUT -> {
                tvSettingsTitle.text = "About"
                tvSettingsSubtitle.text = "App Overview & Setup"
                updateAccessibilityButton()
                diagnosticsViewModel.stopSampling()
            }
        }
    }

    private fun updateCategoryMenuBadges() {
        val prefs = getSharedPreferences("stream_prefs", Context.MODE_PRIVATE)
        val profile = prefs.getString(AudioConfig.PREF_KEY_PROFILE, AudioConfig.PROFILE_AUTO) ?: AudioConfig.PROFILE_AUTO
        val profileDesc = when (profile) {
            AudioConfig.PROFILE_VIDEO, AudioConfig.PROFILE_LOW_LATENCY -> "Low Latency (Opus 48k)"
            AudioConfig.PROFILE_MUSIC -> "Music Mode (Lossless PCM)"
            else -> "Auto Adaptive (24-bit/48k)"
        }
        tvMenuAudioBadge.text = profileDesc

        val isRx = AudioSinkService.isRunning.get()
        if (isRx) {
            val snap = diagnosticsViewModel.currentSnapshot()
            val lat = snap.estimatedPlayoutLatencyMs
            tvMenuDiagBadge.text = if (lat > 0f) String.format(Locale.US, "Active • %.1f ms", lat) else "Receiver Active"
            tvMenuDiagBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            tvMenuDiagBadge.text = "Receiver Idle"
            tvMenuDiagBadge.setTextColor(ContextCompat.getColor(this, R.color.text_hint))
        }

        tvMenuUpdatesBadge.text = "v${BuildConfig.VERSION_NAME}"
    }

    // =========================================================================
    // AUDIO SETTINGS
    // =========================================================================
    private fun setupAudioCategory() {
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

        val initialSyncVol = prefs.getBoolean(AudioConfig.PREF_KEY_SYNC_DEVICE_VOLUME, true)
        switchSyncDeviceVolume.isChecked = initialSyncVol
        switchSyncDeviceVolume.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(AudioConfig.PREF_KEY_SYNC_DEVICE_VOLUME, isChecked).apply()
        }

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

        btnRefreshAudioStats.setOnClickListener {
            updateAudioStatsUi()
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

    private fun updateRateUi(rate: String) {
        val colorPrimary = ContextCompat.getColor(this, R.color.primary)
        val colorCard = ContextCompat.getColor(this, R.color.card_bg)
        val colorTextSecondary = ContextCompat.getColor(this, R.color.text_secondary)

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

    private fun notifySettingsChanged() {
        if (AudioCaptureService.isRunning.get()) {
            val restartIntent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioConfig.ACTION_RESTART_CAPTURE
            }
            startService(restartIntent)
            Log.i(TAG, "Sent ACTION_RESTART_CAPTURE to apply setting change live")
        }
    }

    // =========================================================================
    // DIAGNOSTICS & RECEIVER LATENCY (TASK 3 & 4)
    // =========================================================================
    private fun setupDiagnosticsCategory() {
        btnViewLogs.setOnClickListener {
            AppLogger.showLogViewerDialog(this)
        }

        btnCopyLogs.setOnClickListener {
            copyDiagnosticsToClipboard()
        }

        btnShareLogs.setOnClickListener {
            AppLogger.shareLogs(this)
        }
    }

    private fun observeDiagnostics() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                diagnosticsViewModel.state.collect { state ->
                    updateDiagnosticsUi(state)
                }
            }
        }
    }

    private fun updateDiagnosticsUi(state: ReceiverDiagnosticsState) {
        val isRx = state.isReceiving
        val greenColor = ContextCompat.getColor(this, R.color.status_green)
        val hintColor = ContextCompat.getColor(this, R.color.text_hint)

        if (isRx) {
            tvDiagReceiverStatus.text = "Receiver Active • ${state.sampleRate / 1000}kHz (${state.profileName})"
            tvDiagReceiverStatus.setTextColor(greenColor)
        } else {
            tvDiagReceiverStatus.text = "Receiver Idle"
            tvDiagReceiverStatus.setTextColor(hintColor)
        }

        val latencyMs = state.estimatedPlayoutLatencyMs
        tvDiagLatencyVal.text = if (isRx && latencyMs > 0f) String.format(Locale.US, "%.1f ms", latencyMs) else "-- ms"
        tvDiagJitterVal.text = if (isRx) String.format(Locale.US, "%.2f ms", state.jitterMs) else "-- ms"
        tvDiagWatermarkVal.text = if (isRx) String.format(Locale.US, "%.0f ms", state.targetWatermarkMs) else "-- ms"

        // Update Latency Graph (TASK 3)
        val samples = diagnosticsViewModel.latencyHistory.getSnapshot()
        graphPlayoutLatency.updateData(
            samples = samples,
            currentMs = latencyMs,
            targetMs = state.targetWatermarkMs,
            idle = !isRx
        )

        // Rolling Min / Avg / Max
        if (isRx && diagnosticsViewModel.latencyHistory.size > 0) {
            val min = diagnosticsViewModel.latencyHistory.getMin()
            val avg = diagnosticsViewModel.latencyHistory.getAverage()
            val max = diagnosticsViewModel.latencyHistory.getMax()
            tvDiagMinAvgMax.text = String.format(Locale.US, "Min: %.1fms • Avg: %.1fms • Max: %.1fms", min, avg, max)
        } else {
            tvDiagMinAvgMax.text = "Min: -- • Avg: -- • Max: --"
        }

        // Estimated Playout breakdown
        if (isRx && latencyMs > 0f) {
            tvDiagTimelineBreakdown.text = String.format(
                Locale.US,
                "Jitter Buffer: %.1fms | AudioTrack Queue: %.1fms",
                state.jitterBufferMs,
                state.audioTrackQueuedMs
            )
        } else {
            tvDiagTimelineBreakdown.text = "Jitter Buffer: -- | AudioTrack Queue: --"
        }

        // Jitter buffer & clock health
        val slots = state.bufferAvailableSlots
        val totalSlots = state.bufferTotalSlots
        val fillPct = state.bufferFillPercent
        tvDiagBufferDepth.text = if (isRx) "$slots / $totalSlots slots ($fillPct%)" else "0 / 0 slots (0%)"
        pbDiagBufferHealth.progress = if (isRx) fillPct.coerceIn(0, 100) else 0

        tvDiagClockDrift.text = String.format(Locale.US, "%.4fx", state.driftCorrectionRatio)

        val sr = if (state.sampleRate > 0) state.sampleRate else 48000
        if (isRx) {
            tvDiagTrackQueue.text = String.format(
                Locale.US,
                "%d frames (%.1f ms)",
                state.audioTrackQueuedFrames,
                state.audioTrackQueuedMs
            )

            val targetFrames = state.audioTrackBufferSizeFrames
            val targetMs = if (targetFrames > 0) (targetFrames.toFloat() / sr.toFloat()) * 1000f else 0f
            tvDiagTrackTarget.text = if (targetFrames > 0) {
                String.format(Locale.US, "%d frames (%.1f ms)", targetFrames, targetMs)
            } else {
                "--"
            }

            val capFrames = state.audioTrackBufferCapacityFrames
            val capMs = if (capFrames > 0) (capFrames.toFloat() / sr.toFloat()) * 1000f else 0f
            tvDiagTrackCapacity.text = if (capFrames > 0) {
                String.format(Locale.US, "%d frames (%.1f ms)", capFrames, capMs)
            } else {
                "--"
            }
        } else {
            tvDiagTrackQueue.text = "--"
            tvDiagTrackTarget.text = "--"
            tvDiagTrackCapacity.text = "--"
        }

        tvDiagUnderruns.text = "${state.underruns}"
        tvDiagUnderruns.setTextColor(if (state.underruns > 0) ContextCompat.getColor(this, R.color.status_red) else greenColor)
        tvDiagTrackWrites.text = "${state.audioTrackWrites} writes / ${state.framesWritten} frames"

        // Packet transport & recovery
        tvDiagPktsReceived.text = "${state.packetsReceived}"
        tvDiagPktsLost.text = "${state.packetsLost}"
        tvDiagPktsLate.text = "${state.packetsLate}"
        tvDiagPktsDup.text = "${state.packetsDuplicate}"
        tvDiagPktsOoo.text = "${state.packetsOutOfOrder}"
        tvDiagFecRecovered.text = "${state.fecRecovered}"

        // Realtime diagnostic snapshot preview
        val snapText = diagnosticsViewModel.getDiagnosticSnapshotText()
        tvDiagSnapshotPreview.text = if (snapText.isNotBlank()) snapText else "Waiting for stream metrics..."
    }

    private fun copyDiagnosticsToClipboard() {
        val snapshot = diagnosticsViewModel.getDiagnosticSnapshotText()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("HAT Runtime Diagnostics", snapshot)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Diagnostics copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    // =========================================================================
    // APP UPDATES
    // =========================================================================
    private fun setupUpdatesCategory() {
        tvAppVersion.text = "Installed: Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
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

    // =========================================================================
    // ABOUT & SETUP
    // =========================================================================
    private fun setupAboutCategory() {
        tvAboutVersion.text = "Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"

        btnGithubRepo.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL))
            startActivity(intent)
        }

        btnAboutAppInfo.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
            Toast.makeText(this, "Tap the 3 dots at top-right -> Allow restricted settings", Toast.LENGTH_LONG).show()
        }

        btnAboutAccessibility.setOnClickListener {
            if (VolumeKeyInterceptorService.isRunning.get()) {
                Toast.makeText(this, "Background volume key interception is active", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Turn on Audio Streamer in Accessibility settings", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }

    private fun updateAccessibilityButton() {
        val isServiceActive = VolumeKeyInterceptorService.isRunning.get()
        if (isServiceActive) {
            btnAccessibilitySettings.text = "Step 2: Accessibility (Active)"
            btnAboutAccessibility.text = "Accessibility (Active)"
            val colorGreen = ContextCompat.getColor(this, R.color.status_green)
            btnAccessibilitySettings.setTextColor(colorGreen)
            btnAccessibilitySettings.strokeColor = ColorStateList.valueOf(colorGreen)
            btnAboutAccessibility.setTextColor(colorGreen)
            btnAboutAccessibility.strokeColor = ColorStateList.valueOf(colorGreen)

            btnAppInfo.text = "Step 1: App Info (Completed)"
            btnAboutAppInfo.text = "App Info (Configured)"
            btnAppInfo.setTextColor(colorGreen)
            btnAppInfo.strokeColor = ColorStateList.valueOf(colorGreen)
            btnAboutAppInfo.setTextColor(colorGreen)
            btnAboutAppInfo.strokeColor = ColorStateList.valueOf(colorGreen)
        } else {
            btnAccessibilitySettings.text = "Step 2: Enable in Accessibility Settings"
            btnAboutAccessibility.text = "Open Accessibility Settings"
            val colorPrimary = ContextCompat.getColor(this, R.color.primary)
            btnAccessibilitySettings.setTextColor(colorPrimary)
            btnAccessibilitySettings.strokeColor = ColorStateList.valueOf(colorPrimary)
            btnAboutAccessibility.setTextColor(colorPrimary)
            btnAboutAccessibility.strokeColor = ColorStateList.valueOf(colorPrimary)

            btnAppInfo.text = "Step 1: Open App Info (Allow Restricted Settings)"
            btnAboutAppInfo.text = "Open App Info"
            val colorText = ContextCompat.getColor(this, R.color.text_primary)
            val colorStroke = ContextCompat.getColor(this, R.color.card_stroke)
            btnAppInfo.setTextColor(colorText)
            btnAppInfo.strokeColor = ColorStateList.valueOf(colorStroke)
            btnAboutAppInfo.setTextColor(colorText)
            btnAboutAppInfo.strokeColor = ColorStateList.valueOf(colorStroke)
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

    override fun onResume() {
        super.onResume()
        checkInstallPermissionOnResume()
        updateAccessibilityButton()
        updateCategoryMenuBadges()
        if (currentCategory == Category.DIAGNOSTICS) {
            updateAudioStatsUi()
            diagnosticsViewModel.startSampling()
        }
    }

    override fun onPause() {
        super.onPause()
        diagnosticsViewModel.stopSampling()
    }
}
