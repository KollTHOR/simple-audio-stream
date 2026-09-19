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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.example.audiostreamer.update.BuildInfo
import com.example.audiostreamer.update.GithubRelease
import com.example.audiostreamer.update.ReleaseChannel
import com.example.audiostreamer.update.UpdateCompatibility
import com.example.audiostreamer.update.UpdateDownloader
import com.example.audiostreamer.update.UpdateInstaller
import com.example.audiostreamer.update.UpdateRepository
import com.example.audiostreamer.update.VersionComparator
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

    // Updates Category Views (Update Center)
    private lateinit var tvAppVersion: TextView
    private lateinit var tvInstalledDetails: TextView
    private lateinit var btnCheckUpdate: MaterialButton
    private lateinit var rgUpdateChannel: RadioGroup
    private lateinit var rbChannelStable: RadioButton
    private lateinit var rbChannelNightly: RadioButton
    private lateinit var tvChannelDescription: TextView
    private lateinit var tvLatestTitle: TextView
    private lateinit var tvLatestMeta: TextView
    private lateinit var tvUpdateStatus: TextView
    private lateinit var pbDownload: ProgressBar
    private lateinit var tvDownloadProgress: TextView
    private lateinit var btnLatestAction: MaterialButton
    private lateinit var rgHistoryFilter: RadioGroup
    private lateinit var rbFilterAll: RadioButton
    private lateinit var rbFilterStable: RadioButton
    private lateinit var rbFilterNightly: RadioButton
    private lateinit var llReleaseHistoryList: LinearLayout
    private lateinit var tvHistoryStatus: TextView
    private lateinit var btnLoadMoreReleases: MaterialButton

    private lateinit var updateRepo: UpdateRepository
    private lateinit var updateDownloader: UpdateDownloader
    private lateinit var updateInstaller: UpdateInstaller
    private val buildInfo: BuildInfo by lazy { BuildInfo.current() }

    private val allLoadedReleases = mutableListOf<GithubRelease>()
    private var latestCandidate: GithubRelease? = null
    private var currentPage = 1
    private var hasMorePages = false
    private var isFetchingReleases = false
    private var pendingInstallApk: File? = null

    // About Category Views
    private lateinit var tvAboutVersion: TextView
    private lateinit var btnGithubRepo: MaterialButton
    private lateinit var btnAboutAppInfo: MaterialButton
    private lateinit var btnAboutAccessibility: MaterialButton

    // Diagnostics ViewModel
    private lateinit var diagnosticsViewModel: DiagnosticsViewModel

    // State
    private var currentCategory = Category.MENU

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (packageManager.canRequestPackageInstalls()) {
                val file = pendingInstallApk
                if (file != null && file.exists()) {
                    updateInstaller.launchInstallIntent(file)
                }
            } else {
                Toast.makeText(this, "Permission required to install APK update", Toast.LENGTH_SHORT).show()
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

        // Updates Views (Update Center)
        tvAppVersion = findViewById(R.id.tv_app_version)
        tvInstalledDetails = findViewById(R.id.tv_installed_details)
        btnCheckUpdate = findViewById(R.id.btn_check_update)
        rgUpdateChannel = findViewById(R.id.rg_update_channel)
        rbChannelStable = findViewById(R.id.rb_channel_stable)
        rbChannelNightly = findViewById(R.id.rb_channel_nightly)
        tvChannelDescription = findViewById(R.id.tv_channel_description)
        tvLatestTitle = findViewById(R.id.tv_latest_title)
        tvLatestMeta = findViewById(R.id.tv_latest_meta)
        tvUpdateStatus = findViewById(R.id.tv_update_status)
        pbDownload = findViewById(R.id.pb_download)
        tvDownloadProgress = findViewById(R.id.tv_download_progress)
        btnLatestAction = findViewById(R.id.btn_latest_action)
        rgHistoryFilter = findViewById(R.id.rg_history_filter)
        rbFilterAll = findViewById(R.id.rb_filter_all)
        rbFilterStable = findViewById(R.id.rb_filter_stable)
        rbFilterNightly = findViewById(R.id.rb_filter_nightly)
        llReleaseHistoryList = findViewById(R.id.ll_release_history_list)
        tvHistoryStatus = findViewById(R.id.tv_history_status)
        btnLoadMoreReleases = findViewById(R.id.btn_load_more_releases)

        updateRepo = UpdateRepository(this)
        updateDownloader = UpdateDownloader(this)
        updateInstaller = UpdateInstaller(this)

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
                refreshUpdateCenter(forceRefresh = true)
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
    // APP UPDATES (UPDATE CENTER)
    // =========================================================================
    private fun setupUpdatesCategory() {
        tvAppVersion.text = buildInfo.displayVersion
        tvInstalledDetails.text = "Channel: ${buildInfo.channel.displayName} • Commit: ${buildInfo.commitShort} • Built: ${buildInfo.formattedBuildDate}"

        // Set channel radio group from persisted preference
        val currentPref = updateRepo.getPreferredChannel()
        if (currentPref == ReleaseChannel.NIGHTLY) {
            rbChannelNightly.isChecked = true
        } else {
            rbChannelStable.isChecked = true
        }

        rgUpdateChannel.setOnCheckedChangeListener { _, checkedId ->
            val newChannel = if (checkedId == R.id.rb_channel_nightly) ReleaseChannel.NIGHTLY else ReleaseChannel.STABLE
            updateRepo.setPreferredChannel(newChannel)
            updateChannelDescription(newChannel)
            updateLatestCandidateView()
            renderReleaseHistoryList()
        }
        updateChannelDescription(currentPref)

        // Set history filter listener
        rgHistoryFilter.setOnCheckedChangeListener { _, _ ->
            renderReleaseHistoryList()
        }

        // Action button listeners
        btnCheckUpdate.setOnClickListener {
            refreshUpdateCenter(forceRefresh = true)
        }

        btnLatestAction.setOnClickListener {
            val candidate = latestCandidate
            if (candidate != null) {
                handleReleaseAction(candidate)
            } else {
                refreshUpdateCenter(forceRefresh = true)
            }
        }

        btnLoadMoreReleases.setOnClickListener {
            loadMoreReleases()
        }

        // Initial fetch from cache or network
        refreshUpdateCenter(forceRefresh = false)
    }

    private fun updateChannelDescription(channel: ReleaseChannel) {
        if (channel == ReleaseChannel.NIGHTLY) {
            tvChannelDescription.text = "Nightly channel: Receives automated development builds with latest features and experimental changes."
        } else {
            tvChannelDescription.text = "Stable channel: Receives officially validated releases with production reliability."
        }
    }

    private fun refreshUpdateCenter(forceRefresh: Boolean) {
        if (isFetchingReleases) return
        isFetchingReleases = true
        btnCheckUpdate.isEnabled = false
        btnLatestAction.isEnabled = false
        tvUpdateStatus.text = "Checking GitHub for releases..."
        tvHistoryStatus.text = "Loading release history from GitHub..."
        pbDownload.visibility = View.VISIBLE
        pbDownload.isIndeterminate = true
        tvDownloadProgress.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val result = updateRepo.fetchReleases(page = 1, forceRefresh = forceRefresh)
                pbDownload.visibility = View.GONE
                pbDownload.isIndeterminate = false
                btnCheckUpdate.isEnabled = true
                btnLatestAction.isEnabled = true
                isFetchingReleases = false

                allLoadedReleases.clear()
                allLoadedReleases.addAll(result.releases)
                currentPage = 1
                hasMorePages = result.hasMorePages

                if (result.errorMessage != null && result.releases.isEmpty()) {
                    tvUpdateStatus.text = result.errorMessage
                    tvHistoryStatus.text = "Could not load release history: ${result.errorMessage}"
                    btnLatestAction.text = "Retry"
                    btnLoadMoreReleases.visibility = View.GONE
                    return@launch
                }

                updateLatestCandidateView()
                renderReleaseHistoryList()

                if (result.isFromCache) {
                    tvHistoryStatus.text = "Showing ${allLoadedReleases.size} releases (Offline cache)"
                }
            } catch (e: Exception) {
                isFetchingReleases = false
                pbDownload.visibility = View.GONE
                btnCheckUpdate.isEnabled = true
                btnLatestAction.isEnabled = true
                tvUpdateStatus.text = "Error: ${e.localizedMessage ?: "Failed querying releases"}"
                tvHistoryStatus.text = "Failed loading releases: ${e.localizedMessage}"
            }
        }
    }

    private fun loadMoreReleases() {
        if (isFetchingReleases || !hasMorePages) return
        isFetchingReleases = true
        btnLoadMoreReleases.isEnabled = false
        btnLoadMoreReleases.text = "Loading..."

        val nextPage = currentPage + 1
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val result = updateRepo.fetchReleases(page = nextPage, forceRefresh = true)
                isFetchingReleases = false
                btnLoadMoreReleases.isEnabled = true
                btnLoadMoreReleases.text = "Load More Releases"

                if (result.releases.isNotEmpty()) {
                    currentPage = nextPage
                    hasMorePages = result.hasMorePages
                    allLoadedReleases.addAll(result.releases)
                    renderReleaseHistoryList()
                } else {
                    hasMorePages = false
                    btnLoadMoreReleases.visibility = View.GONE
                }
            } catch (e: Exception) {
                isFetchingReleases = false
                btnLoadMoreReleases.isEnabled = true
                btnLoadMoreReleases.text = "Retry Loading More"
                Toast.makeText(this@SettingsActivity, "Failed loading more: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateLatestCandidateView() {
        val preferredChannel = updateRepo.getPreferredChannel()
        latestCandidate = updateRepo.findLatestCandidate(allLoadedReleases, preferredChannel)

        val candidate = latestCandidate
        if (candidate == null) {
            tvLatestTitle.text = "No releases available for ${preferredChannel.displayName}"
            tvLatestMeta.text = ""
            tvUpdateStatus.text = "No downloadable release asset found in this channel."
            tvUpdateStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnLatestAction.text = "Check Again"
            return
        }

        val compatibility = VersionComparator.compare(candidate, buildInfo)
        tvLatestTitle.text = "${candidate.tagName} (${candidate.channel.displayName})"
        val commitText = candidate.commitShort?.let { "• Commit: $it " } ?: ""
        tvLatestMeta.text = "Published: ${candidate.formattedPublishDate} • ${candidate.formattedSize} $commitText"

        when (compatibility) {
            UpdateCompatibility.NEWER -> {
                tvUpdateStatus.text = "Newer update available for installation."
                tvUpdateStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
                btnLatestAction.text = "Update to ${candidate.cleanVersion}"
            }
            UpdateCompatibility.SAME -> {
                tvUpdateStatus.text = "You are on the latest version."
                tvUpdateStatus.setTextColor(ContextCompat.getColor(this, R.color.status_blue))
                btnLatestAction.text = "Re-install (${candidate.cleanVersion})"
            }
            UpdateCompatibility.OLDER -> {
                tvUpdateStatus.text = "Older release selected (Rollback path)."
                tvUpdateStatus.setTextColor(ContextCompat.getColor(this, R.color.status_orange))
                btnLatestAction.text = "Rollback (${candidate.cleanVersion})"
            }
        }
    }

    private fun renderReleaseHistoryList() {
        llReleaseHistoryList.removeAllViews()

        val filterChannel = when (rgHistoryFilter.checkedRadioButtonId) {
            R.id.rb_filter_stable -> ReleaseChannel.STABLE
            R.id.rb_filter_nightly -> ReleaseChannel.NIGHTLY
            else -> null
        }

        val filtered = allLoadedReleases.filter { release ->
            filterChannel == null || release.channel == filterChannel
        }

        if (filtered.isEmpty()) {
            tvHistoryStatus.text = if (allLoadedReleases.isEmpty()) "No releases found." else "No releases match selected filter."
            tvHistoryStatus.visibility = View.VISIBLE
        } else {
            tvHistoryStatus.text = "Showing ${filtered.size} of ${allLoadedReleases.size} loaded releases"
            tvHistoryStatus.visibility = View.VISIBLE

            for (release in filtered) {
                val itemView = layoutInflater.inflate(R.layout.item_release_history, llReleaseHistoryList, false)
                val tvTag = itemView.findViewById<TextView>(R.id.tv_item_tag)
                val tvChannelPill = itemView.findViewById<TextView>(R.id.tv_item_channel_badge)
                val tvStatusPill = itemView.findViewById<TextView>(R.id.tv_item_status_badge)
                val tvMeta = itemView.findViewById<TextView>(R.id.tv_item_meta)
                val tvNotes = itemView.findViewById<TextView>(R.id.tv_item_notes_preview)
                val btnDetails = itemView.findViewById<MaterialButton>(R.id.btn_item_details)
                val btnAction = itemView.findViewById<MaterialButton>(R.id.btn_item_action)

                tvTag.text = release.tagName
                tvChannelPill.text = release.channel.displayName
                if (release.channel == ReleaseChannel.NIGHTLY) {
                    tvChannelPill.setTextColor(ContextCompat.getColor(this, R.color.status_orange))
                } else {
                    tvChannelPill.setTextColor(ContextCompat.getColor(this, R.color.status_green))
                }

                val compatibility = VersionComparator.compare(release, buildInfo)
                when (compatibility) {
                    UpdateCompatibility.NEWER -> {
                        tvStatusPill.text = "NEWER"
                        tvStatusPill.setTextColor(ContextCompat.getColor(this, R.color.status_green))
                        btnAction.text = "Update"
                    }
                    UpdateCompatibility.SAME -> {
                        tvStatusPill.text = "INSTALLED"
                        tvStatusPill.setTextColor(ContextCompat.getColor(this, R.color.status_blue))
                        btnAction.text = "Installed"
                    }
                    UpdateCompatibility.OLDER -> {
                        tvStatusPill.text = "OLDER"
                        tvStatusPill.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                        btnAction.text = "Rollback"
                    }
                }

                val commitStr = release.commitShort?.let { "• $it " } ?: ""
                tvMeta.text = "Published: ${release.formattedPublishDate} • ${release.formattedSize} $commitStr"

                val cleanedNotes = release.body.lines()
                    .filter { !it.startsWith("<!--") && it.isNotBlank() }
                    .take(2)
                    .joinToString(" ")
                    .replace("#", "")
                    .trim()
                tvNotes.text = if (cleanedNotes.isNotEmpty()) cleanedNotes else "Release asset available on GitHub."

                btnDetails.setOnClickListener {
                    showReleaseDetailsDialog(release)
                }

                btnAction.setOnClickListener {
                    handleReleaseAction(release)
                }

                llReleaseHistoryList.addView(itemView)
            }
        }

        btnLoadMoreReleases.visibility = if (hasMorePages) View.VISIBLE else View.GONE
    }

    private fun handleReleaseAction(release: GithubRelease) {
        val compatibility = VersionComparator.compare(release, buildInfo)
        if (compatibility == UpdateCompatibility.OLDER) {
            showRollbackDialog(release) {
                downloadAndInstallRelease(release)
            }
        } else {
            downloadAndInstallRelease(release)
        }
    }

    private fun showRollbackDialog(release: GithubRelease, onProceed: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Older Version Selected (Rollback)")
            .setMessage(
                "Installed: ${buildInfo.displayVersion}\n" +
                "Selected:  Version ${release.cleanVersion} (Build ${release.parsedVersionCode ?: "unknown"})\n\n" +
                "Android System Restriction:\n" +
                "Android security policy normally prevents installing an older version over a newer one without uninstalling the current version first.\n\n" +
                "Recommended Rollback Steps:\n" +
                "1. Tap 'Proceed' to download the APK.\n" +
                "2. If the Android installer blocks the downgrade, uninstall Simple Audio Stream from your device Settings.\n" +
                "   (Note: Uninstalling clears local preferences).\n" +
                "3. Open the downloaded APK from Downloads or Cache to install.\n\n" +
                "Alternatively, via ADB (preserves data):\n" +
                "adb install -d -r simple-audio-stream-${release.tagName}.apk"
            )
            .setPositiveButton("Proceed to Download & Install") { _, _ ->
                onProceed()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showReleaseDetailsDialog(release: GithubRelease) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_release_details, null)
        val tvTag = dialogView.findViewById<TextView>(R.id.tv_dialog_tag)
        val tvChannel = dialogView.findViewById<TextView>(R.id.tv_dialog_channel_pill)
        val tvPublished = dialogView.findViewById<TextView>(R.id.tv_dialog_published)
        val tvCommit = dialogView.findViewById<TextView>(R.id.tv_dialog_commit)
        val tvApkInfo = dialogView.findViewById<TextView>(R.id.tv_dialog_apk_info)
        val tvCompat = dialogView.findViewById<TextView>(R.id.tv_dialog_compatibility)
        val tvNotes = dialogView.findViewById<TextView>(R.id.tv_dialog_notes)
        val btnClose = dialogView.findViewById<MaterialButton>(R.id.btn_dialog_close)
        val btnAction = dialogView.findViewById<MaterialButton>(R.id.btn_dialog_action)

        tvTag.text = release.name.ifEmpty { release.tagName }
        tvChannel.text = release.channel.displayName
        if (release.channel == ReleaseChannel.NIGHTLY) {
            tvChannel.setTextColor(ContextCompat.getColor(this, R.color.status_orange))
        } else {
            tvChannel.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        }

        tvPublished.text = "Published: ${release.formattedPublishDate}"
        tvCommit.text = "Git Commit: ${release.commitSha ?: "unknown"}"
        val checksumStatus = if (release.checksumAsset != null) "SHA-256 Available" else "No checksum published"
        tvApkInfo.text = "APK Size: ${release.formattedSize} • $checksumStatus"

        val compatibility = VersionComparator.compare(release, buildInfo)
        when (compatibility) {
            UpdateCompatibility.NEWER -> {
                tvCompat.text = "Status: Newer than installed (Update Available)"
                tvCompat.setTextColor(ContextCompat.getColor(this, R.color.status_green))
                btnAction.text = "Download & Install"
            }
            UpdateCompatibility.SAME -> {
                tvCompat.text = "Status: Matches installed version"
                tvCompat.setTextColor(ContextCompat.getColor(this, R.color.status_blue))
                btnAction.text = "Re-install APK"
            }
            UpdateCompatibility.OLDER -> {
                tvCompat.text = "Status: Older than installed (Rollback requires uninstall)"
                tvCompat.setTextColor(ContextCompat.getColor(this, R.color.status_orange))
                btnAction.text = "Rollback"
            }
        }

        tvNotes.text = release.body.ifEmpty { "No release notes published for this release." }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnClose.setOnClickListener { dialog.dismiss() }
        btnAction.setOnClickListener {
            dialog.dismiss()
            handleReleaseAction(release)
        }

        dialog.show()
    }

    private fun downloadAndInstallRelease(release: GithubRelease) {
        btnCheckUpdate.isEnabled = false
        btnLatestAction.isEnabled = false
        pbDownload.visibility = View.VISIBLE
        pbDownload.isIndeterminate = false
        pbDownload.progress = 0
        tvDownloadProgress.visibility = View.VISIBLE
        tvDownloadProgress.text = "Starting download for ${release.tagName}..."
        tvUpdateStatus.text = "Downloading APK: ${release.tagName}..."

        lifecycleScope.launch(Dispatchers.Main) {
            val result = updateDownloader.downloadAndVerify(release) { percent, downloaded, total ->
                runOnUiThread {
                    pbDownload.progress = percent
                    val mbDownloaded = downloaded / (1024.0 * 1024.0)
                    val mbTotal = total / (1024.0 * 1024.0)
                    tvDownloadProgress.text = String.format(
                        Locale.US,
                        "Downloading: %d%% (%.1f MB / %.1f MB)",
                        percent,
                        mbDownloaded,
                        mbTotal
                    )
                }
            }

            pbDownload.visibility = View.GONE
            tvDownloadProgress.visibility = View.GONE
            btnCheckUpdate.isEnabled = true
            btnLatestAction.isEnabled = true

            result.onSuccess { downloadInfo ->
                tvUpdateStatus.text = "Download verified (SHA-256: ${if (downloadInfo.isChecksumVerified) "Valid" else "N/A"}). Opening installer..."
                pendingInstallApk = downloadInfo.apkFile

                val preCheck = updateInstaller.checkPreInstall(
                    targetVersionCode = downloadInfo.archiveVersionCode,
                    targetVersionName = downloadInfo.archiveVersionName,
                    installed = buildInfo
                )

                when (preCheck) {
                    is UpdateInstaller.PreInstallCheck.MissingInstallPermission -> {
                        Toast.makeText(this@SettingsActivity, "Please grant 'Install unknown apps' permission", Toast.LENGTH_LONG).show()
                        installPermissionLauncher.launch(updateInstaller.createPermissionIntent())
                    }
                    is UpdateInstaller.PreInstallCheck.DowngradeDetected -> {
                        updateInstaller.launchInstallIntent(downloadInfo.apkFile)
                    }
                    is UpdateInstaller.PreInstallCheck.Ready -> {
                        updateInstaller.launchInstallIntent(downloadInfo.apkFile)
                    }
                }
            }.onFailure { error ->
                tvUpdateStatus.text = "Download/Verification failed: ${error.localizedMessage}"
                Toast.makeText(this@SettingsActivity, "Error: ${error.message}", Toast.LENGTH_LONG).show()
            }
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
        val file = pendingInstallApk
        if (file != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (packageManager.canRequestPackageInstalls()) {
                pendingInstallApk = null
                if (file.exists()) {
                    updateInstaller.launchInstallIntent(file)
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
