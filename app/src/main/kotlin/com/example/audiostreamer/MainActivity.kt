package com.example.audiostreamer

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.net.wifi.p2p.WifiP2pDevice
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.RadioButton
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import com.example.audiostreamer.node.LocalNodeManager
import com.example.audiostreamer.node.NodeTransportType
import com.example.audiostreamer.node.discovery.LanDiscoveryProvider
import com.example.audiostreamer.node.discovery.WifiDirectDiscoveryProvider
import com.example.audiostreamer.node.discovery.HatNfcBootstrapProvider
import com.example.audiostreamer.node.discovery.HatDiscoveryRegistry
import com.example.audiostreamer.node.discovery.NfcBootstrapState

class MainActivity : AppCompatActivity() {

    private enum class Mode {
        TRANSMITTER,
        RECEIVER
    }

    private lateinit var layoutIpPill: LinearLayout
    private lateinit var viewHeaderNodeDot: View
    private lateinit var tvHeaderNodeName: TextView
    private lateinit var tvHeaderIp: TextView

    // Active Transport Badges
    private lateinit var layoutTransportBadges: ChipGroup
    private lateinit var badgeTransportLan: Chip
    private lateinit var badgeTransportP2p: Chip
    private lateinit var badgeTransportNan: Chip
    private lateinit var badgeTransportBle: Chip
    private lateinit var badgeTransportNfc: Chip

    // Interactive NFC Tap-to-Pair Card
    private lateinit var cardNfcTap: MaterialCardView
    private lateinit var ivNfcIcon: ImageView
    private lateinit var tvNfcTitle: TextView
    private lateinit var tvNfcSubtitle: TextView

    private lateinit var toggleModeGroup: MaterialButtonToggleGroup
    private lateinit var btnModeTransmitter: MaterialButton
    private lateinit var btnModeReceiver: MaterialButton
    private var tvModeGuide: TextView? = null

    // Redesigned Connection Setup & Profiles
    private lateinit var layoutSavedProfilesSection: LinearLayout
    private lateinit var tvSavedProfilesTitle: TextView
    private lateinit var btnAddProfile: MaterialButton
    private lateinit var layoutSavedProfilesContainer: LinearLayout
    private lateinit var layoutSavedEmpty: TextView

    // Modern Connection Manager & Available Devices
    private lateinit var layoutTuneInBanner: MaterialCardView
    private lateinit var tvTuneInMessage: TextView
    private lateinit var btnTuneIn: MaterialButton
    private lateinit var btnDismissTuneIn: TextView

    private lateinit var layoutConnectedDevicesSection: View
    private lateinit var tvConnectedDevicesTitle: TextView
    private lateinit var tvConnectedCountBadge: TextView
    private lateinit var layoutConnectedDevicesContainer: LinearLayout
    private var ivConnectedSectionChevron: ImageView? = null
    private var isConnectedSectionExpanded: Boolean = true

    private lateinit var layoutDiscoverySection: View
    /** Tracks previous sender-active state. */
    private var wasSenderActive: Boolean = false

    private lateinit var tvDiscoveryTitle: TextView
    private lateinit var btnScanReceivers: MaterialButton
    private lateinit var tvDiscoveryStatusDot: TextView
    private lateinit var tvDiscoveryCompactStatus: TextView
    private lateinit var btnDiscoveryRetry: TextView
    private lateinit var cardReceiverDiscoverable: MaterialCardView
    private lateinit var tvReceiverHeadline: TextView
    private var tvReceiverTransportsList: TextView? = null
    private lateinit var tvReceiverEndpointPill: TextView
    private var activeScanJob: Job? = null
    private lateinit var layoutDiscoveredDevicesContainer: LinearLayout

    private lateinit var layoutReceiverP2p: LinearLayout
    private lateinit var switchReceiverP2p: MaterialSwitch
    private lateinit var layoutReceiverP2pInfo: LinearLayout
    private lateinit var tvP2pSsid: TextView
    private lateinit var tvP2pPassphrase: TextView
    private lateinit var tvP2pIp: TextView
    private lateinit var tvReceiverP2pStatus: TextView

    private lateinit var layoutAdvancedHeader: LinearLayout
    private lateinit var tvAdvancedToggle: TextView
    private lateinit var layoutAdvancedContent: LinearLayout

    private lateinit var tilTargetIp: TextInputLayout
    private lateinit var etTargetIp: TextInputEditText
    private lateinit var tilPort: TextInputLayout
    private lateinit var etPort: TextInputEditText
    private lateinit var btnAction: MaterialButton
    private lateinit var fabSettings: FloatingActionButton

    private var currentConnType: ConnectionType = ConnectionType.LOCAL_WIFI
    private var isAdvancedExpanded: Boolean = false

    // Volume controls
    private lateinit var layoutVolumeControl: LinearLayout
    private lateinit var tvRemoteVolLabel: TextView
    private lateinit var sliderRemoteVol: Slider

    // Equalizer & Media Playback Controls
    private lateinit var fabEqualizer: FloatingActionButton
    private lateinit var cardMediaPlayback: MaterialCardView
    private lateinit var ivMediaCardBg: ImageView
    private lateinit var viewMediaCardScrim: View
    private lateinit var ivMediaArt: ImageView
    private lateinit var tvMediaTitle: TextView
    private lateinit var tvMediaArtist: TextView
    private lateinit var btnSyncMediaPermission: TextView
    private lateinit var btnMediaPrev: ImageView
    private lateinit var btnMediaPlayPause: ImageView
    private lateinit var btnMediaNext: ImageView

    private var currentMode: Mode = Mode.TRANSMITTER
    private var detectedLocalIp: String? = null
    private var isStarting: Boolean = false
    private var isStopping: Boolean = false

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val ip = etTargetIp.text?.toString()?.trim().orEmpty()
            val port = etPort.text?.toString()?.toIntOrNull() ?: AudioConfig.DEFAULT_PORT

            stopReceiverService()

            val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_START
                putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(AudioCaptureService.EXTRA_TARGET_IP, ip)
                putExtra(AudioCaptureService.EXTRA_TARGET_PORT, port)
            }
            isStarting = true
            isStopping = false
            DiscoveryManager.stopDiscovery()
            if (ip.isNotEmpty()) {
                DiscoveryManager.sendStreamInvite(ip, port, DiscoveryManager.getLocalDeviceName())
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            updateModeAndButtonUi()
        } else {
            isStarting = false
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            updateModeAndButtonUi()
        }
    }

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (allGranted) {
            AppLogger.i("MainActivity", "Bluetooth permissions granted")
            syncDiscoveryMode()
        } else {
            AppLogger.w("MainActivity", "Some Bluetooth permissions denied: $grants")
        }
    }

    private fun checkAndRequestBlePermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        return if (permissions.isNotEmpty()) {
            blePermissionLauncher.launch(permissions.toTypedArray())
            false
        } else {
            true
        }
    }

    private val transmitterPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else {
            true
        }

        if (recordAudioGranted && notificationGranted) {
            launchMediaProjectionConsent()
        } else {
            Toast.makeText(this, "Permissions required for audio transmission", Toast.LENGTH_SHORT).show()
        }
    }

    private val receiverPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startReceiverService()
        } else {
            Toast.makeText(this, "Notification permission needed for playback service", Toast.LENGTH_SHORT).show()
        }
    }

    private val p2pPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            if (currentMode == Mode.RECEIVER && switchReceiverP2p.isChecked) {
                startReceiverP2pGroup()
            } else if (currentMode == Mode.TRANSMITTER && currentConnType == ConnectionType.WIFI_DIRECT) {
                WifiDirectManager.discoverPeers(this)
                Toast.makeText(this, "Scanning for Wi-Fi Direct receivers...", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Nearby Wi-Fi / Location permission required for Wi-Fi Direct", Toast.LENGTH_SHORT).show()
            if (currentMode == Mode.RECEIVER) {
                switchReceiverP2p.isChecked = false
            }
        }
    }

    private fun isLocationServiceEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        return lm != null && (lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER))
    }

    private fun checkAndRequestP2pPermissions(): Boolean {
        if (!isLocationServiceEnabled()) {
            AppLogger.w("MainActivity", "Location Service (GPS) is DISABLED. Android requires Location to be ON for Wi-Fi Direct peer discovery.")
            Toast.makeText(this, "Please turn ON Location (GPS) in phone settings for Wi-Fi Direct", Toast.LENGTH_LONG).show()
        }

        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
        return if (permissions.isNotEmpty()) {
            AppLogger.i("MainActivity", "Requesting permissions for Wi-Fi Direct: $permissions")
            p2pPermissionLauncher.launch(permissions.toTypedArray())
            false
        } else {
            true
        }
    }

    private fun startReceiverP2pGroup() {
        if (!checkAndRequestP2pPermissions()) return
        layoutReceiverP2pInfo.visibility = View.VISIBLE
        tvReceiverP2pStatus.text = "Initializing Autonomous Wi-Fi Direct Group..."
        AppLogger.i("MainActivity", "Starting Autonomous Wi-Fi Direct Group...")
        WifiDirectManager.createAutonomousGroup(this) { success, ssid, goIp ->
            runOnUiThread {
                if (success) {
                    layoutReceiverP2pInfo.visibility = View.VISIBLE
                    tvP2pSsid.text = "Group SSID: ${ssid ?: WifiDirectManager.P2P_DEFAULT_SSID}"
                    val pass = WifiDirectManager.networkPassphrase.value ?: WifiDirectManager.P2P_DEFAULT_PASSPHRASE
                    tvP2pPassphrase.text = "Passphrase: $pass"
                    tvP2pIp.text = "Direct IP: $goIp (Port 50005)"
                    tvReceiverP2pStatus.text = "Broadcasting & Listening for Transmitter Connections"
                    AppLogger.i("MainActivity", "Autonomous Wi-Fi Direct Active: SSID=$ssid, Passphrase=******, IP=$goIp")
                    Toast.makeText(this, "Wi-Fi Direct Active: $ssid", Toast.LENGTH_SHORT).show()
                } else {
                    switchReceiverP2p.isChecked = false
                    layoutReceiverP2pInfo.visibility = View.GONE
                    tvReceiverP2pStatus.text = "Failed starting Wi-Fi Direct group"
                    AppLogger.w("MainActivity", "Failed starting Wi-Fi Direct group")
                    Toast.makeText(this, "Failed to start Wi-Fi Direct group", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("stream_prefs", android.content.Context.MODE_PRIVATE)
        val themeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_YES) // default: dark
        AppCompatDelegate.setDefaultNightMode(themeMode)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Apply system window insets so content is pushed below the status/notification bar
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root_layout)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

        com.example.audiostreamer.node.LocalNodeManager.init(this)
        ConnectionProfileManager.init(this)
        WifiDirectManager.init(this)

        layoutIpPill = findViewById(R.id.layout_ip_pill)
        viewHeaderNodeDot = findViewById(R.id.view_header_node_dot)
        tvHeaderNodeName = findViewById(R.id.tv_header_node_name)
        tvHeaderIp = findViewById(R.id.tv_header_ip)

        layoutTransportBadges = findViewById(R.id.layout_transport_badges)
        badgeTransportLan = findViewById(R.id.badge_transport_lan)
        badgeTransportP2p = findViewById(R.id.badge_transport_p2p)
        badgeTransportNan = findViewById(R.id.badge_transport_nan)
        badgeTransportBle = findViewById(R.id.badge_transport_ble)
        badgeTransportNfc = findViewById(R.id.badge_transport_nfc)

        layoutIpPill.setOnClickListener {
            showNodeDetailsDialog()
        }
        layoutTransportBadges.setOnClickListener {
            showNodeDetailsDialog()
        }

        fabEqualizer = findViewById(R.id.fab_equalizer)
        fabEqualizer.setOnClickListener {
            EqualizerBottomSheetDialogFragment().show(supportFragmentManager, "EqualizerBottomSheet")
        }

        cardMediaPlayback = findViewById(R.id.card_media_playback)
        cardMediaPlayback.clipToOutline = true
        ivMediaCardBg = findViewById(R.id.iv_media_card_bg)
        viewMediaCardScrim = findViewById(R.id.view_media_card_scrim)
        ivMediaArt = findViewById(R.id.iv_media_art)
        tvMediaTitle = findViewById(R.id.tv_media_title)
        tvMediaArtist = findViewById(R.id.tv_media_artist)
        btnSyncMediaPermission = findViewById(R.id.btn_sync_media_permission)
        btnMediaPrev = findViewById(R.id.btn_media_prev)
        btnMediaPlayPause = findViewById(R.id.btn_media_play_pause)
        btnMediaNext = findViewById(R.id.btn_media_next)

        AudioSinkService.onMediaMetadataChanged = { _, _, _, _, _ ->
            runOnUiThread { updateMediaPlaybackUi() }
        }
        MediaSessionTracker.addOnStateChangedListener {
            runOnUiThread { updateMediaPlaybackUi() }
        }

        btnMediaPlayPause.setOnClickListener {
            if (AudioCaptureService.isRunning.get()) {
                AudioCaptureService.currentInstance?.handleMediaControlCommand(HatPacket.MEDIA_CMD_PLAY_PAUSE)
            } else if (AudioSinkService.isRunning.get()) {
                AudioSinkService.currentInstance?.sendMediaControl(HatPacket.MEDIA_CMD_PLAY_PAUSE)
            }
        }
        btnMediaPrev.setOnClickListener {
            if (AudioCaptureService.isRunning.get()) {
                AudioCaptureService.currentInstance?.handleMediaControlCommand(HatPacket.MEDIA_CMD_PREVIOUS)
            } else if (AudioSinkService.isRunning.get()) {
                AudioSinkService.currentInstance?.sendMediaControl(HatPacket.MEDIA_CMD_PREVIOUS)
            }
        }
        btnMediaNext.setOnClickListener {
            if (AudioCaptureService.isRunning.get()) {
                AudioCaptureService.currentInstance?.handleMediaControlCommand(HatPacket.MEDIA_CMD_NEXT)
            } else if (AudioSinkService.isRunning.get()) {
                AudioSinkService.currentInstance?.sendMediaControl(HatPacket.MEDIA_CMD_NEXT)
            }
        }
        btnSyncMediaPermission.setOnClickListener {
            try {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                        putExtra(
                            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            ComponentName(this@MainActivity, MediaNotificationListenerService::class.java).flattenToString()
                        )
                    }
                } else {
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (ignored: Exception) {}
            }
        }

        cardNfcTap = findViewById(R.id.card_nfc_tap)
        ivNfcIcon = findViewById(R.id.iv_nfc_icon)
        tvNfcTitle = findViewById(R.id.tv_nfc_title)
        tvNfcSubtitle = findViewById(R.id.tv_nfc_subtitle)
        cardNfcTap.setOnClickListener {
            showNfcInteractionDialog()
        }

        toggleModeGroup = findViewById(R.id.toggle_mode_group)
        btnModeTransmitter = findViewById(R.id.btn_mode_transmitter)
        btnModeReceiver = findViewById(R.id.btn_mode_receiver)

        layoutSavedProfilesSection = findViewById(R.id.layout_saved_profiles_section)
        tvSavedProfilesTitle = findViewById(R.id.tv_saved_profiles_title)
        btnAddProfile = findViewById(R.id.btn_add_profile)
        layoutSavedProfilesContainer = findViewById(R.id.layout_saved_profiles_container)
        layoutSavedEmpty = findViewById(R.id.layout_saved_empty)

        layoutTuneInBanner = findViewById(R.id.layout_tune_in_banner)
        tvTuneInMessage = findViewById(R.id.tv_tune_in_message)
        btnTuneIn = findViewById(R.id.btn_tune_in)
        btnDismissTuneIn = findViewById(R.id.btn_dismiss_tune_in)

        layoutConnectedDevicesSection = findViewById(R.id.layout_connected_devices_section)
        tvConnectedDevicesTitle = findViewById(R.id.tv_connected_devices_title)
        tvConnectedCountBadge = findViewById(R.id.tv_connected_count_badge)
        layoutConnectedDevicesContainer = findViewById(R.id.layout_connected_devices_container)
        ivConnectedSectionChevron = findViewById(R.id.iv_connected_chevron)

        findViewById<View>(R.id.header_connected_devices)?.setOnClickListener {
            isConnectedSectionExpanded = !isConnectedSectionExpanded
            layoutConnectedDevicesContainer.visibility = if (isConnectedSectionExpanded) View.VISIBLE else View.GONE
            ivConnectedSectionChevron?.animate()?.rotation(if (isConnectedSectionExpanded) 0f else 180f)?.setDuration(150)?.start()
        }

        layoutDiscoverySection = findViewById(R.id.layout_discovery_section)
        tvDiscoveryTitle = findViewById(R.id.tv_discovery_title)
        btnScanReceivers = findViewById(R.id.btn_scan_receivers)
        layoutDiscoveredDevicesContainer = findViewById(R.id.layout_discovered_devices_container)
        tvDiscoveryStatusDot = findViewById(R.id.tv_discovery_status_dot)
        tvDiscoveryCompactStatus = findViewById(R.id.tv_discovery_compact_status)
        btnDiscoveryRetry = findViewById(R.id.btn_discovery_retry)

        btnDiscoveryRetry.setOnClickListener {
            startUnifiedScan()
        }

        cardReceiverDiscoverable = findViewById(R.id.card_receiver_discoverable)
        tvReceiverHeadline = findViewById(R.id.tv_receiver_headline)
        tvReceiverEndpointPill = findViewById(R.id.tv_receiver_endpoint_pill)

        layoutReceiverP2p = findViewById(R.id.layout_receiver_p2p)
        switchReceiverP2p = findViewById(R.id.switch_receiver_p2p)
        layoutReceiverP2pInfo = findViewById(R.id.layout_receiver_p2p_info)
        tvP2pSsid = findViewById(R.id.tv_p2p_ssid)
        tvP2pPassphrase = findViewById(R.id.tv_p2p_passphrase)
        tvP2pIp = findViewById(R.id.tv_p2p_ip)
        tvReceiverP2pStatus = findViewById(R.id.tv_receiver_p2p_status)

        layoutAdvancedHeader = findViewById(R.id.layout_advanced_header)
        tvAdvancedToggle = findViewById(R.id.tv_advanced_toggle)
        layoutAdvancedContent = findViewById(R.id.layout_advanced_content)

        tilTargetIp = findViewById(R.id.til_target_ip)
        etTargetIp = findViewById(R.id.et_target_ip)
        tilPort = findViewById(R.id.til_port)
        etPort = findViewById(R.id.et_port)
        btnAction = findViewById(R.id.btn_action)
        fabSettings = findViewById(R.id.fab_settings)

        layoutAdvancedHeader.setOnClickListener {
            isAdvancedExpanded = !isAdvancedExpanded
            layoutAdvancedContent.visibility = if (isAdvancedExpanded) View.VISIBLE else View.GONE
            tvAdvancedToggle.text = if (isAdvancedExpanded) {
                "Legacy Direct IP Fallback (Manual IP & Port) ▴"
            } else {
                "Legacy Direct IP Fallback (Manual IP & Port) ▾"
            }
        }

        btnAddProfile.setOnClickListener {
            showEditProfileDialog(null)
        }

        btnDismissTuneIn.setOnClickListener {
            layoutTuneInBanner.visibility = View.GONE
        }

        btnTuneIn.setOnClickListener {
            val transmitter = DiscoveryManager.discoveredTransmitters.value.firstOrNull()
            if (transmitter != null) {
                etTargetIp.setText(transmitter.ip)
                etPort.setText(transmitter.port.toString())
                startReceiverWorkflow()
                layoutTuneInBanner.visibility = View.GONE
            }
        }

        switchReceiverP2p.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startReceiverP2pGroup()
            } else {
                WifiDirectManager.removeGroup(this)
                BleDiscoveryManager.stopAdvertising()
                if (BleDiscoveryManager.hasPermissions(this)) {
                    BleDiscoveryManager.startAdvertising(this, role = "receiver")
                }
                layoutReceiverP2pInfo.visibility = View.GONE
                tvReceiverP2pStatus.text = "Autonomous Wi-Fi Direct stopped"
            }
        }

        btnScanReceivers.setOnClickListener {
            if (com.example.audiostreamer.node.discovery.DiscoveryScanCoordinator.scanState.value.isScanning) {
                stopUnifiedScan()
            } else {
                startUnifiedScan()
            }
        }

        val initialProfile = ConnectionProfileManager.activeProfileFlow.value
        if (initialProfile != null) {
            etTargetIp.setText(initialProfile.targetIp)
            etPort.setText(initialProfile.port.toString())
            currentConnType = initialProfile.connectionType
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    com.example.audiostreamer.node.discovery.DiscoveryScanCoordinator.scanState.collect { state ->
                        renderDiscoveryScanState(state)
                    }
                }
                launch {
                    ConnectionProfileManager.profilesFlow.collect {
                        updateSavedProfilesUi()
                    }
                }
                launch {
                    ConnectionProfileManager.activeProfileFlow.collect {
                        updateSavedProfilesUi()
                    }
                }
                launch {
                    DiscoveryManager.discoveredDevices.collect {
                        updateDiscoveredDevicesUi()
                    }
                }
                launch {
                    LanDiscoveryProvider.discoveredEndpoints.collect {
                        updateDiscoveredDevicesUi()
                    }
                }
                launch {
                    WifiDirectDiscoveryProvider.discoveredEndpoints.collect {
                        updateDiscoveredDevicesUi()
                    }
                }
                launch {
                    HatDiscoveryRegistry.discoveredNodes.collect {
                        updateDiscoveredDevicesUi()
                    }
                }
                launch {
                    com.example.audiostreamer.node.HatMultiStreamManager.activeDestinations.collect {
                        updateConnectedDevicesUi(StreamState.telemetry.value)
                    }
                }
                launch {
                    com.example.audiostreamer.node.HatLinkManager.activeLinks.collect {
                        updateConnectedDevicesUi(StreamState.telemetry.value)
                        updateTransportBadges()
                    }
                }
                launch {
                    LocalNodeManager.localNode.collect { node ->
                        updateLocalNodeUi(node)
                    }
                }
                launch {
                    HatNfcBootstrapProvider.state.collect { nfcState ->
                        updateNfcUi(nfcState)
                    }
                }
                launch {
                    DiscoveryManager.discoveredTransmitters.collect {
                        updateTuneInBanner()
                    }
                }
                launch {
                    BleDiscoveryManager.bleDevices.collect {
                        updateDiscoveredDevicesUi()
                    }
                }
                launch {
                    DiscoveryManager.isScanning.collect {
                        updateScanningIndicator()
                    }
                }
                launch {
                    LanDiscoveryProvider.isScanning.collect {
                        updateScanningIndicator()
                    }
                }
                launch {
                    WifiDirectDiscoveryProvider.isScanning.collect {
                        updateScanningIndicator()
                    }
                }
                launch {
                    com.example.audiostreamer.node.discovery.HatBlePresenceProvider.isScanning.collect {
                        updateScanningIndicator()
                    }
                }
                launch {
                    com.example.audiostreamer.node.discovery.WifiAwareDiscoveryProvider.isSubscribing.collect {
                        updateScanningIndicator()
                    }
                }
                launch {
                    WifiDirectManager.discoveredPeers.collect {
                        updateDiscoveredDevicesUi()
                    }
                }
                launch {
                    WifiDirectManager.isScanningPeers.collect {
                        updateScanningIndicator()
                    }
                }
                launch {
                    BleDiscoveryManager.isScanning.collect {
                        updateScanningIndicator()
                    }
                }
                launch {
                    WifiDirectManager.statusMessage.collect { status ->
                        if (currentMode == Mode.RECEIVER && switchReceiverP2p.isChecked) {
                            tvReceiverP2pStatus.text = status
                        }
                    }
                }
            }
        }

        layoutVolumeControl = findViewById(R.id.layout_volume_control)
        tvRemoteVolLabel = findViewById(R.id.tv_remote_vol_label)
        sliderRemoteVol = findViewById(R.id.slider_remote_vol)

        val initialVol = AudioCaptureService.remoteVolumePercent.get()
        sliderRemoteVol.value = initialVol.toFloat()
        tvRemoteVolLabel.text = "$initialVol%"

        sliderRemoteVol.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val vol = value.toInt()
                tvRemoteVolLabel.text = "$vol%"
                sendVolumeIntent(vol)
            }
        }

        refreshLocalIp()

        layoutIpPill.setOnClickListener {
            refreshLocalIp()
            val node = LocalNodeManager.getLocalNode()
            val allIps = NetworkUtils.getAllLocalIpAddresses()
            val details = StringBuilder()
            details.append("HAT Node: ${node.name}\n")
            details.append("Node ID: ${node.id}\n")
            details.append("State: ${node.state.name} (${node.activeRole.name})\n")
            details.append("Transports: ${node.capabilities.supportedTransports.joinToString { it.name }}\n")
            details.append("Codecs: ${node.capabilities.supportedCodecs.joinToString { it.name }}\n\n")
            details.append("Network IP Addresses:\n")
            if (allIps.isNotEmpty()) {
                allIps.forEach { details.append(" • $it\n") }
            } else {
                details.append(" • Offline / No active Wi-Fi\n")
            }

            MaterialAlertDialogBuilder(this)
                .setTitle("Local HAT Node")
                .setMessage(details.toString().trimEnd())
                .setPositiveButton("Copy Node ID") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Node ID", node.id))
                    Toast.makeText(this, "Node ID copied: ${node.id}", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Copy IP") { _, _ ->
                    val ipToCopy = detectedLocalIp ?: allIps.firstOrNull() ?: "Unavailable (Offline)"
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("IP Address", ipToCopy))
                    Toast.makeText(this, "IP copied: $ipToCopy", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Close", null)
                .show()
        }

        fabSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        toggleModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val newMode = if (checkedId == R.id.btn_mode_transmitter) {
                    Mode.TRANSMITTER
                } else {
                    Mode.RECEIVER
                }
                if (currentMode != newMode) {
                    onModeSwitched(currentMode, newMode)
                    currentMode = newMode
                    syncDiscoveryMode()
                    updateModeAndButtonUi()
                }
            }
        }

        btnAction.setOnClickListener {
            if (isStarting || isStopping) return@setOnClickListener

            when (currentMode) {
                Mode.TRANSMITTER -> {
                    if (AudioCaptureService.isRunning.get()) {
                        stopTransmitterService()
                    } else {
                        startTransmitterWorkflow()
                    }
                }
                Mode.RECEIVER -> {
                    if (AudioSinkService.isRunning.get()) {
                        stopReceiverService()
                    } else {
                        startReceiverWorkflow()
                    }
                }
            }
        }

        observeTelemetry()
        updateModeAndButtonUi()

        HatNfcBootstrapProvider.probeCapability(this)
        intent?.let { handleNfcIntent(it) }
        HatDiscoveryRegistry.startMonitoring(lifecycleScope)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    private fun handleNfcIntent(intent: Intent) {
        val result = HatNfcBootstrapProvider.processNfcIntent(intent, this)
        if (result != null && result.success) {
            Toast.makeText(
                this,
                "NFC tap: Bootstrapped ${result.remoteNode?.name} (${result.selectedTransport?.name ?: "Pending"})",
                Toast.LENGTH_LONG
            ).show()
            updateDiscoveredDevicesUi()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLocalIp()
        HatNfcBootstrapProvider.enableNfcDispatch(this)

        val currentRemoteVol = AudioCaptureService.remoteVolumePercent.get()
        sliderRemoteVol.value = currentRemoteVol.toFloat()
        tvRemoteVolLabel.text = "$currentRemoteVol%"

        if (AudioCaptureService.isRunning.get()) {
            currentMode = Mode.TRANSMITTER
            toggleModeGroup.check(R.id.btn_mode_transmitter)
        } else if (AudioSinkService.isRunning.get()) {
            currentMode = Mode.RECEIVER
            toggleModeGroup.check(R.id.btn_mode_receiver)
        }
        updateModeAndButtonUi()
        updateDiscoveredDevicesUi()
        updateSavedProfilesUi()
        syncDiscoveryMode()
    }

    override fun onPause() {
        super.onPause()
        HatNfcBootstrapProvider.disableNfcDispatch(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        activeScanJob?.cancel()
        activeScanJob = null
        com.example.audiostreamer.node.discovery.DiscoveryScanCoordinator.stopScan(this)
        DiscoveryManager.stopDiscovery()
        DiscoveryManager.stopReceiverResponder()
        LanDiscoveryProvider.stopAll()
        WifiDirectDiscoveryProvider.stopAll()
        com.example.audiostreamer.node.discovery.WifiAwareDiscoveryProvider.stopAll()
        com.example.audiostreamer.node.discovery.HatBlePresenceProvider.stopAll()
        BleDiscoveryManager.stopScanning()
        BleDiscoveryManager.stopAdvertising()
        HatNfcBootstrapProvider.stopAll()
        HatDiscoveryRegistry.stopAll()
        WifiDirectManager.cleanup(this)
    }

    private fun syncDiscoveryMode() {
        when (currentMode) {
            Mode.TRANSMITTER -> {
                // 1. Stop all receiver advertisements & responders
                DiscoveryManager.stopReceiverResponder()
                LanDiscoveryProvider.stopAdvertisement()
                WifiDirectDiscoveryProvider.stopAdvertisement()
                com.example.audiostreamer.node.discovery.WifiAwareDiscoveryProvider.stopPublishing()
                BleDiscoveryManager.stopAdvertising()
                com.example.audiostreamer.node.discovery.HatBlePresenceProvider.stopAdvertising()

                // 2. Stop any active scan
                com.example.audiostreamer.node.discovery.DiscoveryScanCoordinator.stopScan(this)
            }
            Mode.RECEIVER -> {
                // 1. Stop any active scanner from transmitter
                com.example.audiostreamer.node.discovery.DiscoveryScanCoordinator.stopScan(this)
                DiscoveryManager.stopDiscovery()
                LanDiscoveryProvider.stopDiscovery()
                WifiDirectDiscoveryProvider.stopDiscovery()
                WifiDirectManager.stopPeerDiscovery(this)
                com.example.audiostreamer.node.discovery.WifiAwareDiscoveryProvider.stopSubscribing()
                BleDiscoveryManager.stopScanning()
                com.example.audiostreamer.node.discovery.HatBlePresenceProvider.stopScanning()

                // 2. Start continuous advertisements on ALL supported transports simultaneously
                val localNode = LocalNodeManager.getLocalNode()
                val port = etPort.text.toString().toIntOrNull() ?: AudioConfig.DEFAULT_PORT

                // LAN advertisement & responder
                DiscoveryManager.startReceiverResponder(this, lifecycleScope)
                LanDiscoveryProvider.advertiseNode(this, localNode, port)

                // Wi-Fi Direct DNS-SD advertisement
                if (checkAndRequestP2pPermissions()) {
                    WifiDirectDiscoveryProvider.advertiseNode(this, localNode, port)
                    if (!WifiDirectManager.isGroupCreated.value) {
                        WifiDirectManager.discoverPeers(this)
                    }
                }

                // Wi-Fi Aware publish
                com.example.audiostreamer.node.discovery.WifiAwareDiscoveryProvider.probeCapability(this)
                com.example.audiostreamer.node.discovery.WifiAwareDiscoveryProvider.startPublishing(this)

                // BLE HAT presence & legacy advertising
                if (BleDiscoveryManager.hasPermissions(this)) {
                    val ssid = WifiDirectManager.networkSsid.value
                    val pass = WifiDirectManager.networkPassphrase.value
                    val ip = WifiDirectManager.groupOwnerIp.value
                    BleDiscoveryManager.startAdvertising(
                        context = this,
                        role = "receiver",
                        p2pSsid = ssid,
                        p2pPassphrase = pass,
                        p2pGoIp = ip
                    )
                    com.example.audiostreamer.node.discovery.HatBlePresenceProvider.startAdvertising(this)
                }
            }
        }
        updateReceiverDiscoverableBanner()
    }

    private fun updateReceiverDiscoverableBanner() {
        if (currentMode != Mode.RECEIVER) {
            cardReceiverDiscoverable.visibility = View.GONE
            return
        }

        cardReceiverDiscoverable.visibility = View.VISIBLE
        tvReceiverHeadline.text = "● Ready to receive"

        val availableTransports = mutableListOf<String>()
        if (NetworkUtils.isLanAvailable(this)) {
            availableTransports.add("Local Wi-Fi")
        }
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            availableTransports.add("Wi-Fi Direct")
        }
        if (com.example.audiostreamer.node.discovery.WifiAwareDiscoveryProvider.isSupported(this)) {
            availableTransports.add("Aware")
        }
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) && BleDiscoveryManager.hasPermissions(this)) {
            availableTransports.add("Bluetooth")
        }

        val transportsStr = if (availableTransports.isNotEmpty()) {
            "Discoverable on " + when (availableTransports.size) {
                1 -> availableTransports[0]
                2 -> "${availableTransports[0]} and ${availableTransports[1]}"
                else -> availableTransports.dropLast(1).joinToString(", ") + ", and " + availableTransports.last()
            }
        } else {
            "No active discovery transports"
        }
        tvReceiverTransportsList?.text = transportsStr

        val localIp = NetworkUtils.getLocalIpAddress() ?: "0.0.0.0"
        val port = etPort.text.toString().toIntOrNull() ?: AudioConfig.DEFAULT_PORT
        val localNodeId = try { LocalNodeManager.getLocalNode().id } catch (_: Exception) { "" }
        val idShort = if (localNodeId.isNotBlank()) " • ${localNodeId.takeLast(6)}" else ""
        tvReceiverEndpointPill.text = "$localIp:$port$idShort"
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun startUnifiedScan() {
        if (currentMode == Mode.RECEIVER) return
        com.example.audiostreamer.node.discovery.DiscoveryScanCoordinator.startScan(this, lifecycleScope)
    }

    private fun stopUnifiedScan() {
        com.example.audiostreamer.node.discovery.DiscoveryScanCoordinator.stopScan(this)
    }

    private fun renderDiscoveryScanState(state: com.example.audiostreamer.node.discovery.DiscoveryScanState) {
        val isScanning = state.isScanning
        val availableCount = layoutDiscoveredDevicesContainer.childCount

        btnScanReceivers.text = if (isScanning) "STOP" else "SCAN"
        btnScanReceivers.setTextColor(
            ContextCompat.getColor(
                this,
                if (isScanning) R.color.status_red else R.color.primary
            )
        )

        when {
            isScanning -> {
                tvDiscoveryStatusDot.visibility = View.VISIBLE
                tvDiscoveryStatusDot.text = "●"
                tvDiscoveryStatusDot.setTextColor(ContextCompat.getColor(this, R.color.primary))
                val phaseName = when (state.currentPhase) {
                    com.example.audiostreamer.node.discovery.DiscoveryScanPhase.LOCAL_WIFI -> "Local Wi-Fi"
                    com.example.audiostreamer.node.discovery.DiscoveryScanPhase.WIFI_DIRECT -> "Wi-Fi Direct"
                    com.example.audiostreamer.node.discovery.DiscoveryScanPhase.WIFI_AWARE -> "Wi-Fi Aware"
                    com.example.audiostreamer.node.discovery.DiscoveryScanPhase.BLE -> "Bluetooth"
                    else -> "Local Wi-Fi"
                }
                tvDiscoveryCompactStatus.text = "Searching nearby devices · $phaseName"
                tvDiscoveryCompactStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                btnDiscoveryRetry.visibility = View.GONE
            }
            state.phaseStatus == com.example.audiostreamer.node.discovery.PhaseStatus.FAILED -> {
                tvDiscoveryStatusDot.visibility = View.VISIBLE
                tvDiscoveryStatusDot.text = "✕"
                tvDiscoveryStatusDot.setTextColor(ContextCompat.getColor(this, R.color.status_red))
                tvDiscoveryCompactStatus.text = "Couldn't complete discovery · "
                tvDiscoveryCompactStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red))
                btnDiscoveryRetry.visibility = View.VISIBLE
            }
            availableCount > 0 -> {
                tvDiscoveryStatusDot.visibility = View.VISIBLE
                tvDiscoveryStatusDot.text = "✓"
                tvDiscoveryStatusDot.setTextColor(ContextCompat.getColor(this, R.color.status_green))
                val devWord = if (availableCount == 1) "device" else "devices"
                tvDiscoveryCompactStatus.text = "$availableCount $devWord found"
                tvDiscoveryCompactStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
                btnDiscoveryRetry.visibility = View.GONE
            }
            else -> {
                tvDiscoveryStatusDot.visibility = View.GONE
                tvDiscoveryCompactStatus.text = "No nearby devices found"
                tvDiscoveryCompactStatus.setTextColor(ContextCompat.getColor(this, R.color.text_hint))
                btnDiscoveryRetry.visibility = View.GONE
            }
        }
    }

    private fun updateScanDashboardMetrics() {
        renderDiscoveryScanState(com.example.audiostreamer.node.discovery.DiscoveryScanCoordinator.scanState.value)
    }

    private fun updateScanningIndicator() {
        renderDiscoveryScanState(com.example.audiostreamer.node.discovery.DiscoveryScanCoordinator.scanState.value)
    }

    data class UnifiedDevice(
        val id: String,
        val displayName: String,
        val modelName: String?,
        val lanIp: String?,
        val port: Int = AudioConfig.DEFAULT_PORT,
        val p2pPeer: WifiP2pDevice?,
        val p2pSsid: String?,
        val p2pPassphrase: String?,
        val p2pGoIp: String?,
        val isDirectAvailable: Boolean,
        var useDirect: Boolean = false,
        val capabilitiesMask: Int = 0,
        val nodeId: String? = null,
        val discoverySources: List<String> = emptyList(),
        val transportCandidates: List<String> = emptyList(),
        val rssi: Int? = null,
        val capabilitiesSummary: String? = null
    )

    private val deviceDirectPreferences = mutableMapOf<String, Boolean>()

    private fun getUnifiedDiscoveredDevices(): List<UnifiedDevice> {
        val registryNodes = HatDiscoveryRegistry.discoveredNodes.value
        val localIps = try {
            (NetworkUtils.getAllLocalIpAddresses() + NetworkUtils.getP2pIpAddresses()).toSet()
        } catch (e: Exception) { emptySet() }

        val p2pPeers = try { WifiDirectManager.discoveredPeers.value } catch (e: Exception) { emptyList() }
        val blePeers = try { BleDiscoveryManager.bleDevices.value } catch (e: Exception) { emptyList() }

        val unified = mutableListOf<UnifiedDevice>()
        val seenNodeIds = mutableSetOf<String>()
        val seenEndpoints = mutableSetOf<String>()

        for (regNode in registryNodes) {
            if (regNode.id in seenNodeIds) {
                Log.d("HatDiscovery", "UI_DEDUP nodeId=${regNode.id} source=REGISTRY endpoint=${regNode.getLanEndpoint()?.address ?: "none"}")
                continue
            }
            seenNodeIds.add(regNode.id)

            val lanEp = regNode.getLanEndpoint()
            val wdEp = regNode.getWifiDirectEndpoint()
            val awareEp = regNode.getWifiAwareEndpoint()
            val bleEp = regNode.getBleEndpoint()
            val nfcEp = regNode.getNfcEndpoint()

            regNode.resolvedEndpoints.forEach { ep ->
                if (!ep.address.isNullOrBlank()) {
                    seenEndpoints.add(ep.address)
                }
            }

            val ip = lanEp?.address?.takeIf { it !in localIps }
            val mac = wdEp?.address ?: bleEp?.details?.get("mac") as? String
            mac?.let { seenEndpoints.add(it) }

            // Find matching P2P peer by deviceAddress or by name match
            val matchingPeer = if (mac != null) {
                p2pPeers.firstOrNull { it.deviceAddress.equals(mac, ignoreCase = true) }
            } else {
                p2pPeers.firstOrNull { peer ->
                    peer.deviceName.isNotEmpty() && (
                        peer.deviceName.equals(regNode.name, ignoreCase = true) ||
                        regNode.name.contains(peer.deviceName, ignoreCase = true)
                    )
                }
            }

            // Find matching BLE peer by address or name
            val matchingBle = blePeers.firstOrNull { ble ->
                (mac != null && ble.bluetoothAddress.equals(mac, ignoreCase = true)) ||
                (ble.name.isNotEmpty() && ble.name.equals(regNode.name, ignoreCase = true))
            }

            val p2pSsid = (lanEp?.details?.get("p2pSsid") as? String)
                ?: (wdEp?.details?.get("p2pSsid") as? String)
                ?: matchingBle?.p2pSsid
            val p2pPassphrase = (lanEp?.details?.get("p2pPassphrase") as? String)
                ?: (wdEp?.details?.get("p2pPassphrase") as? String)
                ?: matchingBle?.p2pPassphrase
            val p2pGoIp = (lanEp?.details?.get("p2pGoIp") as? String)
                ?: (wdEp?.details?.get("p2pGoIp") as? String)
                ?: matchingBle?.p2pGoIp
                ?: if (ip != null && ip.startsWith("192.168.49.")) ip else null
            p2pGoIp?.let { seenEndpoints.add(it) }

            val isDirect = regNode.hasTransport(NodeTransportType.WIFI_DIRECT) ||
                matchingPeer != null ||
                !p2pSsid.isNullOrEmpty() ||
                (ip != null && ip.startsWith("192.168.49."))

            val sourcesList = regNode.discoverySources.map { it.name }
            val candidateTransportsList = regNode.transportCandidates.map { it.name }
            val capsSummary = regNode.nodeInfo.capabilities.describe()

            val displayName = when {
                regNode.name.isNotEmpty() && regNode.name != "HAT Node" && regNode.name != "Audio Receiver" -> regNode.name
                matchingPeer?.deviceName?.isNotEmpty() == true -> matchingPeer.deviceName
                matchingBle?.name?.isNotEmpty() == true && matchingBle.name != "Nearby receiver" -> matchingBle.name
                else -> regNode.name.ifEmpty { "Audio Receiver" }
            }

            val port = lanEp?.port ?: wdEp?.port ?: nfcEp?.port ?: AudioConfig.DEFAULT_PORT

            unified.add(
                UnifiedDevice(
                    id = regNode.id,
                    displayName = displayName,
                    modelName = if (regNode.discoverySources.isNotEmpty()) regNode.discoverySources.joinToString(", ") { it.name } else null,
                    lanIp = if (ip != null && ip.startsWith("192.168.49.")) null else ip,
                    port = port,
                    p2pPeer = matchingPeer,
                    p2pSsid = p2pSsid,
                    p2pPassphrase = p2pPassphrase,
                    p2pGoIp = p2pGoIp,
                    isDirectAvailable = isDirect,
                    useDirect = isDirect && ip == null,
                    capabilitiesMask = regNode.nodeInfo.capabilities.toCapabilitiesMask(),
                    nodeId = regNode.id,
                    discoverySources = sourcesList,
                    transportCandidates = candidateTransportsList,
                    rssi = regNode.rssi,
                    capabilitiesSummary = capsSummary
                )
            )
        }

        // Legacy provider fallback: only for entries that have no registry representation
        val legacyUdpDevices = try { DiscoveryManager.discoveredDevices.value } catch (e: Exception) { emptyList() }
        for (udp in legacyUdpDevices) {
            if (udp.ip in localIps || (udp.p2pGoIp != null && udp.p2pGoIp in localIps)) continue
            if (!udp.nodeId.isNullOrBlank() && udp.nodeId in seenNodeIds) {
                Log.d("HatDiscovery", "UI_DEDUP nodeId=${udp.nodeId} source=UDP endpoint=${udp.ip}")
                continue
            }
            if (udp.ip in seenEndpoints || (udp.p2pGoIp != null && udp.p2pGoIp in seenEndpoints)) {
                Log.d("HatDiscovery", "UI_DEDUP nodeId=${udp.nodeId ?: "legacy"} source=UDP endpoint=${udp.ip}")
                continue
            }

            val fallbackId = udp.nodeId ?: "hat-legacy-udp-${udp.ip.replace('.', '-')}"
            seenNodeIds.add(fallbackId)
            seenEndpoints.add(udp.ip)
            udp.p2pGoIp?.let { seenEndpoints.add(it) }

            unified.add(
                UnifiedDevice(
                    id = fallbackId,
                    displayName = udp.name.ifEmpty { "Audio Receiver" },
                    modelName = udp.modelName,
                    lanIp = if (udp.ip.startsWith("192.168.49.")) null else udp.ip,
                    port = udp.port,
                    p2pPeer = null,
                    p2pSsid = udp.p2pSsid,
                    p2pPassphrase = udp.p2pPassphrase,
                    p2pGoIp = udp.p2pGoIp,
                    isDirectAvailable = udp.isP2pActive,
                    useDirect = udp.isP2pActive && !udp.ip.startsWith("192.168."),
                    capabilitiesMask = udp.capabilitiesMask,
                    nodeId = udp.nodeId,
                    discoverySources = listOf("LAN"),
                    transportCandidates = listOf("LOCAL_WIFI"),
                    rssi = null,
                    capabilitiesSummary = null
                )
            )
        }

        return unified
    }

    private fun updateConnectedDevicesUi(t: Telemetry) {
        if (currentMode == Mode.TRANSMITTER) {
            val receivers = t.connectedReceivers
            if (receivers.isNotEmpty()) {
                layoutConnectedDevicesSection.visibility = View.VISIBLE
                tvConnectedDevicesTitle.text = "CONNECTED RECEIVERS"

                // Deduplicate receivers by stable Node ID first, then fallback to endpoint IP
                val uniqueReceivers = mutableListOf<ConnectedDevice>()
                val seenConnIds = mutableSetOf<String>()
                for (rec in receivers) {
                    val key = rec.nodeId?.takeIf { it.isNotBlank() && !it.startsWith("hat-node-ep-") } ?: rec.ip
                    if (seenConnIds.add(key)) {
                        uniqueReceivers.add(rec)
                    }
                }

                tvConnectedCountBadge.text = "${uniqueReceivers.size} active"

                val activeTags = uniqueReceivers.map { rec ->
                    rec.nodeId?.takeIf { it.isNotBlank() && !it.startsWith("hat-node-ep-") } ?: rec.ip
                }.toSet()

                val toRemove = mutableListOf<View>()
                for (i in 0 until layoutConnectedDevicesContainer.childCount) {
                    val child = layoutConnectedDevicesContainer.getChildAt(i)
                    val tagStr = child.tag as? String
                    if (tagStr == null || tagStr !in activeTags) {
                        toRemove.add(child)
                    }
                }
                toRemove.forEach { layoutConnectedDevicesContainer.removeView(it) }

                for (rec in uniqueReceivers) {
                    val stableTag = rec.nodeId?.takeIf { it.isNotBlank() && !it.startsWith("hat-node-ep-") } ?: rec.ip
                    var itemView = layoutConnectedDevicesContainer.findViewWithTag<View>(stableTag)
                    val isNew = (itemView == null)
                    if (isNew) {
                        itemView = layoutInflater.inflate(R.layout.item_connected_device, layoutConnectedDevicesContainer, false)
                        itemView.tag = stableTag
                    }

                    val tvName = itemView!!.findViewById<TextView>(R.id.tv_device_name)
                    val tvStatus = itemView.findViewById<TextView>(R.id.tv_device_status)
                    val dot = itemView.findViewById<View>(R.id.view_active_dot)
                    val layoutDeviceVolume = itemView.findViewById<LinearLayout>(R.id.layout_device_volume)
                    val btnDeviceMute = itemView.findViewById<ImageView>(R.id.btn_device_mute)
                    val sliderDeviceVol = itemView.findViewById<Slider>(R.id.slider_device_vol)
                    val tvDeviceVolVal = itemView.findViewById<TextView>(R.id.tv_device_vol_val)
                    val btnDisconnect = itemView.findViewById<MaterialButton>(R.id.btn_device_disconnect)

                    val dest = com.example.audiostreamer.node.HatMultiStreamManager.getDestination(rec.nodeId ?: "")
                        ?: com.example.audiostreamer.node.HatMultiStreamManager.getAllDestinations().firstOrNull { it.link.metadata.remoteAddress == rec.ip }

                    val devName = dest?.nodeName?.takeIf { it.isNotEmpty() && it != "Unknown" } ?: rec.name.ifEmpty { "Audio Receiver" }
                    tvName.text = devName

                    val transportStr = dest?.transportType?.name ?: rec.transportType
                    val statusText = "Connected • $transportStr"
                    tvStatus.text = statusText

                    val dotColor = when (dest?.health) {
                        com.example.audiostreamer.node.DestinationHealth.HEALTHY -> ContextCompat.getColor(this, R.color.status_green)
                        com.example.audiostreamer.node.DestinationHealth.DEGRADED -> ContextCompat.getColor(this, R.color.status_orange)
                        com.example.audiostreamer.node.DestinationHealth.UNREACHABLE -> ContextCompat.getColor(this, R.color.status_red)
                        else -> ContextCompat.getColor(this, R.color.status_green)
                    }
                    dot.backgroundTintList = ColorStateList.valueOf(dotColor)

                    // Multi-receiver Volume Control Row
                    layoutDeviceVolume.visibility = View.VISIBLE
                    val masterVol = AudioCaptureService.remoteVolumePercent.get()
                    val vol = rec.volumePercent.coerceIn(0, masterVol)
                    if (!sliderDeviceVol.isPressed && sliderDeviceVol.value.toInt() != vol) {
                        sliderDeviceVol.value = vol.toFloat()
                        tvDeviceVolVal.text = "$vol%"
                    }

                    if (rec.isMuted || vol == 0) {
                        btnDeviceMute.setImageResource(R.drawable.ic_volume_mute)
                        btnDeviceMute.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_red))
                    } else {
                        btnDeviceMute.setImageResource(R.drawable.ic_volume_up)
                        btnDeviceMute.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
                    }

                    if (isNew) {
                        val layoutConnectedHeader = itemView.findViewById<View>(R.id.layout_connected_header)
                        val layoutExpandedDetails = itemView.findViewById<View>(R.id.layout_connected_expanded_details)
                        val ivChevron = itemView.findViewById<ImageView>(R.id.iv_connected_chevron)

                        layoutConnectedHeader?.setOnClickListener {
                            val isExp = layoutExpandedDetails?.visibility == View.VISIBLE
                            layoutExpandedDetails?.visibility = if (isExp) View.GONE else View.VISIBLE
                            ivChevron?.animate()?.rotation(if (isExp) 0f else 90f)?.setDuration(150)?.start()
                        }

                        sliderDeviceVol.clearOnChangeListeners()
                        sliderDeviceVol.addOnChangeListener { _, value, fromUser ->
                            if (fromUser) {
                                val curMaster = AudioCaptureService.remoteVolumePercent.get()
                                val clampedVol = value.toInt().coerceIn(0, curMaster)
                                if (value.toInt() > curMaster) {
                                    sliderDeviceVol.value = curMaster.toFloat()
                                }
                                tvDeviceVolVal.text = "$clampedVol%"
                                val volIntent = Intent(this, AudioCaptureService::class.java).apply {
                                    action = AudioCaptureService.ACTION_SET_RECEIVER_VOLUME
                                    putExtra(AudioCaptureService.EXTRA_RECEIVER_IP, rec.ip)
                                    putExtra(AudioCaptureService.EXTRA_RECEIVER_NODE_ID, rec.nodeId)
                                    putExtra(AudioCaptureService.EXTRA_VOLUME_PERCENT, clampedVol)
                                }
                                startService(volIntent)
                            }
                        }

                        btnDeviceMute.setOnClickListener {
                            val curMaster = AudioCaptureService.remoteVolumePercent.get()
                            val newVol = if (rec.isMuted || vol == 0) curMaster else 0
                            val volIntent = Intent(this, AudioCaptureService::class.java).apply {
                                action = AudioCaptureService.ACTION_SET_RECEIVER_VOLUME
                                putExtra(AudioCaptureService.EXTRA_RECEIVER_IP, rec.ip)
                                putExtra(AudioCaptureService.EXTRA_RECEIVER_NODE_ID, rec.nodeId)
                                putExtra(AudioCaptureService.EXTRA_VOLUME_PERCENT, newVol)
                            }
                            startService(volIntent)
                        }

                        btnDisconnect.setOnClickListener {
                            val removeIntent = Intent(this, AudioCaptureService::class.java).apply {
                                action = AudioCaptureService.ACTION_REMOVE_CLIENT
                                putExtra(AudioCaptureService.EXTRA_TARGET_IP, rec.ip)
                                putExtra(AudioCaptureService.EXTRA_RECEIVER_NODE_ID, rec.nodeId)
                            }
                            startService(removeIntent)
                            Toast.makeText(this, "Disconnected $devName", Toast.LENGTH_SHORT).show()
                        }

                        layoutConnectedDevicesContainer.addView(itemView)
                    }
                }
            } else {
                layoutConnectedDevicesContainer.removeAllViews()
                layoutConnectedDevicesSection.visibility = View.GONE
            }
        } else {
            // RECEIVER mode
            val isSinkActive = AudioSinkService.isRunning.get()
            val transmitter = t.connectedTransmitter
            if (isSinkActive && transmitter != null) {
                layoutConnectedDevicesSection.visibility = View.VISIBLE
                tvConnectedDevicesTitle.text = "CONNECTED TRANSMITTER"
                tvConnectedCountBadge.text = "1 active"

                // Remove any non-receiver views if present
                val toRemove = mutableListOf<View>()
                for (i in 0 until layoutConnectedDevicesContainer.childCount) {
                    val child = layoutConnectedDevicesContainer.getChildAt(i)
                    if (child.tag != "connected_transmitter") {
                        toRemove.add(child)
                    }
                }
                toRemove.forEach { layoutConnectedDevicesContainer.removeView(it) }

                var itemView = layoutConnectedDevicesContainer.findViewWithTag<View>("connected_transmitter")
                val isNew = (itemView == null)
                if (isNew) {
                    itemView = layoutInflater.inflate(R.layout.item_connected_device, layoutConnectedDevicesContainer, false)
                    itemView.tag = "connected_transmitter"
                }

                val tvName = itemView!!.findViewById<TextView>(R.id.tv_device_name)
                val tvStatus = itemView.findViewById<TextView>(R.id.tv_device_status)
                val dot = itemView.findViewById<View>(R.id.view_active_dot)
                val layoutDeviceVolume = itemView.findViewById<LinearLayout>(R.id.layout_device_volume)
                val btnDisconnect = itemView.findViewById<MaterialButton>(R.id.btn_device_disconnect)

                val activeLink = t.activeLinks.firstOrNull()
                val transportStr = activeLink?.hatTransportType?.name ?: transmitter.transportType
                tvName.text = transmitter.name.ifEmpty { "Audio Transmitter" }
                tvStatus.text = "Connected • $transportStr"
                dot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_green))
                layoutDeviceVolume.visibility = View.GONE
                btnDisconnect.visibility = View.VISIBLE

                if (isNew) {
                    btnDisconnect.setOnClickListener {
                        stopReceiverService()
                        Toast.makeText(this, "Stopped receiver playback", Toast.LENGTH_SHORT).show()
                    }
                    layoutConnectedDevicesContainer.addView(itemView)
                }
            } else {
                layoutConnectedDevicesContainer.removeAllViews()
                layoutConnectedDevicesSection.visibility = View.GONE
            }
        }
        updateDiscoveredDevicesUi()
    }

    private fun updateTuneInBanner() {
        if (currentMode == Mode.RECEIVER && !AudioSinkService.isRunning.get()) {
            val transmitters = DiscoveryManager.discoveredTransmitters.value
            val firstTx = transmitters.firstOrNull()
            if (firstTx != null) {
                tvTuneInMessage.text = "${firstTx.name} is broadcasting on ${firstTx.ip}. Tap below to tune in instantly."
                layoutTuneInBanner.visibility = View.VISIBLE
                return
            }
        }
        layoutTuneInBanner.visibility = View.GONE
    }

    private fun updateDiscoveredDevicesUi() {
        layoutDiscoveredDevicesContainer.removeAllViews()
        val devices = getUnifiedDiscoveredDevices()

        // Filter out currently connected / connecting / degraded devices using Node ID as primary identity
        val activeLinks = com.example.audiostreamer.node.HatLinkManager.activeLinks.value
        val connectedDestinations = com.example.audiostreamer.node.HatMultiStreamManager.getAllDestinations()
        val connectedReceivers = StreamState.telemetry.value.connectedReceivers
        val connectedTransmitter = StreamState.telemetry.value.connectedTransmitter

        val connectedNodeIds = mutableSetOf<String>()
        val connectedIps = mutableSetOf<String>()

        if (currentMode == Mode.TRANSMITTER) {
            activeLinks.forEach { link ->
                if (link.state in setOf(
                        com.example.audiostreamer.node.LinkState.CONNECTED,
                        com.example.audiostreamer.node.LinkState.CONNECTING,
                        com.example.audiostreamer.node.LinkState.DEGRADED
                    )) {
                    connectedNodeIds.add(link.remoteNode.id)
                    connectedIps.add(link.metadata.remoteAddress)
                }
            }
            connectedDestinations.forEach { dest ->
                if (dest.health != com.example.audiostreamer.node.DestinationHealth.UNREACHABLE) {
                    connectedNodeIds.add(dest.nodeId)
                    dest.link.metadata.remoteAddress.takeIf { it.isNotBlank() }?.let { connectedIps.add(it) }
                }
            }
            connectedReceivers.forEach { rec ->
                rec.nodeId?.takeIf { it.isNotBlank() }?.let { connectedNodeIds.add(it) }
                rec.ip.takeIf { it.isNotBlank() }?.let { connectedIps.add(it) }
            }
        } else {
            connectedTransmitter?.nodeId?.takeIf { it.isNotBlank() }?.let { connectedNodeIds.add(it) }
            connectedTransmitter?.ip?.takeIf { it.isNotBlank() }?.let { connectedIps.add(it) }
            activeLinks.forEach { link ->
                if (link.state in setOf(
                        com.example.audiostreamer.node.LinkState.CONNECTED,
                        com.example.audiostreamer.node.LinkState.CONNECTING,
                        com.example.audiostreamer.node.LinkState.DEGRADED
                    )) {
                    connectedNodeIds.add(link.remoteNode.id)
                    connectedIps.add(link.metadata.remoteAddress)
                }
            }
        }

        val availableDevices = devices.filter { dev ->
            // Rule: CONNECTED / CONNECTING / DEGRADED Node must NOT appear in Available Devices.
            // Available: only DISCONNECTED nodes.

            // 1. Primary matching: dev.nodeId
            if (dev.nodeId != null && dev.nodeId in connectedNodeIds) {
                return@filter false
            }

            // Check registry connectionState if available
            val regNode = com.example.audiostreamer.node.discovery.HatDiscoveryRegistry.getDiscoveredNode(dev.id)
                ?: (dev.nodeId?.let { com.example.audiostreamer.node.discovery.HatDiscoveryRegistry.getDiscoveredNode(it) })
            if (regNode != null && regNode.connectionState in setOf(
                    com.example.audiostreamer.node.LinkState.CONNECTED,
                    com.example.audiostreamer.node.LinkState.CONNECTING,
                    com.example.audiostreamer.node.LinkState.DEGRADED
                )) {
                return@filter false
            }

            // 2. Fallback matching for legacy devices without Node ID: match endpoint/IP
            val isLegacy = dev.nodeId == null || dev.nodeId.startsWith("hat-node-ep-") || dev.nodeId.startsWith("hat-node-lan-")
            if (isLegacy) {
                val matchesIp = (dev.lanIp != null && dev.lanIp in connectedIps) ||
                                (dev.p2pGoIp != null && dev.p2pGoIp in connectedIps)
                if (matchesIp) {
                    return@filter false
                }
            }

            true
        }

        for (dev in availableDevices) {
            val itemView = layoutInflater.inflate(R.layout.item_available_device, layoutDiscoveredDevicesContainer, false)
            val card = itemView.findViewById<MaterialCardView>(R.id.card_available_device)
            val tvName = itemView.findViewById<TextView>(R.id.tv_device_name)
            val tvTransport = itemView.findViewById<TextView>(R.id.tv_device_transport)
            val btnConnect = itemView.findViewById<MaterialButton>(R.id.btn_device_connect)
            val ivIcon = itemView.findViewById<ImageView>(R.id.iv_device_icon)

            tvName.text = dev.displayName

            val sources = dev.discoverySources.toMutableList()
            val isP2p = dev.useDirect || dev.lanIp == null || dev.isDirectAvailable
            if (sources.isEmpty()) {
                if (isP2p) sources.add("DIRECT") else sources.add("LAN")
            }

            val primarySource = sources.firstOrNull() ?: if (isP2p) "DIRECT" else "LAN"
            val transportLabel = when (primarySource.uppercase()) {
                "WIFI_DIRECT", "DIRECT" -> "Wi-Fi Direct"
                "BLE" -> "Bluetooth"
                "WIFI_AWARE", "AWARE" -> "Wi-Fi Aware"
                else -> "Local Wi-Fi"
            }
            tvTransport.text = transportLabel

            val isAlreadyConnected = dev.nodeId != null && !com.example.audiostreamer.node.discovery.HatDiscoveryRegistry.canConnect(dev.nodeId)
            btnConnect.visibility = View.VISIBLE
            if (isAlreadyConnected) {
                btnConnect.text = "Connected"
                btnConnect.isEnabled = false
                btnConnect.alpha = 0.6f
            } else {
                btnConnect.text = "CONNECT"
                btnConnect.isEnabled = true
                btnConnect.alpha = 1.0f
            }

            fun performConnect() {
                if (dev.nodeId != null && !com.example.audiostreamer.node.discovery.HatDiscoveryRegistry.canConnect(dev.nodeId)) {
                    Toast.makeText(this@MainActivity, "Already connected to ${dev.displayName}", Toast.LENGTH_SHORT).show()
                    return
                }
                if (currentMode == Mode.TRANSMITTER) {
                    val isRunning = AudioCaptureService.isRunning.get()
                    if (dev.useDirect || dev.lanIp == null) {
                        connectToUnifiedDeviceDirect(dev) { goIp ->
                            if (isRunning) {
                                DiscoveryManager.sendStreamInvite(goIp, dev.port, DiscoveryManager.getLocalDeviceName())
                                val intent = Intent(this@MainActivity, AudioCaptureService::class.java).apply {
                                    action = AudioCaptureService.ACTION_ADD_CLIENT
                                    putExtra(AudioCaptureService.EXTRA_TARGET_IP, goIp)
                                    putExtra(AudioCaptureService.EXTRA_TARGET_PORT, dev.port)
                                    putExtra(AudioCaptureService.EXTRA_RECEIVER_NODE_ID, dev.nodeId)
                                }
                                startService(intent)
                                Toast.makeText(this@MainActivity, "Added ${dev.displayName} to stream", Toast.LENGTH_SHORT).show()
                            } else {
                                etTargetIp.setText(goIp)
                                etPort.setText(dev.port.toString())
                                DiscoveryManager.sendStreamInvite(goIp, dev.port, DiscoveryManager.getLocalDeviceName())
                                startTransmitterWorkflow()
                            }
                        }
                    } else {
                        val ip = dev.lanIp
                        if (isRunning) {
                            DiscoveryManager.sendStreamInvite(ip, dev.port, DiscoveryManager.getLocalDeviceName())
                            val intent = Intent(this@MainActivity, AudioCaptureService::class.java).apply {
                                action = AudioCaptureService.ACTION_ADD_CLIENT
                                putExtra(AudioCaptureService.EXTRA_TARGET_IP, ip)
                                putExtra(AudioCaptureService.EXTRA_TARGET_PORT, dev.port)
                                putExtra(AudioCaptureService.EXTRA_RECEIVER_NODE_ID, dev.nodeId)
                            }
                            startService(intent)
                            Toast.makeText(this@MainActivity, "Added ${dev.displayName} to stream", Toast.LENGTH_SHORT).show()
                        } else {
                            etTargetIp.setText(ip)
                            etPort.setText(dev.port.toString())
                            DiscoveryManager.sendStreamInvite(ip, dev.port, DiscoveryManager.getLocalDeviceName())
                            startTransmitterWorkflow()
                        }
                    }
                } else {
                    // Receiver mode
                    val targetIp = dev.lanIp ?: dev.p2pGoIp ?: "0.0.0.0"
                    etTargetIp.setText(targetIp)
                    etPort.setText(dev.port.toString())
                    startReceiverWorkflow()
                }
            }

            fun performSaveProfile() {
                val isP2pDev = dev.useDirect || dev.lanIp == null
                val targetIp = if (isP2pDev) (dev.p2pGoIp ?: "192.168.49.1") else dev.lanIp ?: "192.168.1.1"
                val profile = ConnectionProfile(
                    id = if (isP2pDev) "p2p_${dev.id}" else "wifi_${dev.id}",
                    name = dev.displayName,
                    targetIp = targetIp,
                    port = dev.port,
                    connectionType = if (isP2pDev) ConnectionType.WIFI_DIRECT else ConnectionType.LOCAL_WIFI,
                    preferredStreamingProfile = AudioConfig.PROFILE_MUSIC,
                    capabilitiesMask = dev.capabilitiesMask,
                    p2pSsid = dev.p2pSsid,
                    p2pPassphrase = dev.p2pPassphrase,
                    nodeId = dev.nodeId
                )
                ConnectionProfileManager.saveProfile(this@MainActivity, profile)
                Toast.makeText(this@MainActivity, "Saved profile: ${profile.name}", Toast.LENGTH_SHORT).show()
            }

            fun showDeviceDetailsDialog() {
                val ipStr = dev.lanIp ?: dev.p2pGoIp ?: "Not available"
                val sourceList = if (dev.discoverySources.isNotEmpty()) dev.discoverySources.joinToString(", ") else primarySource
                val message = StringBuilder()
                    .append("Transport: ").append(transportLabel).append("\n")
                    .append("Address: ").append(ipStr).append(":").append(dev.port).append("\n")
                if (!dev.nodeId.isNullOrEmpty()) {
                    message.append("Node ID: ").append(dev.nodeId).append("\n")
                }
                if (!dev.modelName.isNullOrEmpty() && dev.modelName != dev.displayName) {
                    message.append("Model: ").append(dev.modelName).append("\n")
                }
                message.append("Discovered Via: ").append(sourceList)

                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(dev.displayName)
                    .setMessage(message.toString())
                    .setPositiveButton("Connect") { _, _ -> performConnect() }
                    .setNeutralButton("Save Profile") { _, _ -> performSaveProfile() }
                    .setNegativeButton("Close", null)
                    .show()
            }

            btnConnect.setOnClickListener { performConnect() }
            card.setOnClickListener { performConnect() }
            card.setOnLongClickListener {
                showDeviceDetailsDialog()
                true
            }
            ivIcon?.setOnClickListener {
                showDeviceDetailsDialog()
            }

            layoutDiscoveredDevicesContainer.addView(itemView)
        }

        layoutDiscoveredDevicesContainer.visibility = if (availableDevices.isEmpty()) View.GONE else View.VISIBLE
        renderDiscoveryScanState(com.example.audiostreamer.node.discovery.DiscoveryScanCoordinator.scanState.value)
        layoutDiscoverySection.visibility = if (currentMode == Mode.TRANSMITTER) View.VISIBLE else View.GONE
    }

    private fun connectToUnifiedDeviceDirect(dev: UnifiedDevice, onConnected: ((String) -> Unit)? = null) {
        if (!checkAndRequestP2pPermissions()) return
        currentConnType = ConnectionType.WIFI_DIRECT
        val connStartMs = System.currentTimeMillis()
        val targetNodeId = dev.nodeId ?: dev.id

        if (!dev.p2pSsid.isNullOrEmpty()) {
            val pass = dev.p2pPassphrase ?: WifiDirectManager.P2P_DEFAULT_PASSPHRASE
            AppLogger.i("MainActivity", "Direct-linking to '${dev.displayName}' SSID=${dev.p2pSsid}...")
            Toast.makeText(this, "Direct-linking to ${dev.displayName}...", Toast.LENGTH_SHORT).show()
            WifiDirectManager.connectWithCredentials(this, dev.p2pSsid, pass) { goIp ->
                val durationMs = System.currentTimeMillis() - connStartMs
                WifiDirectDiscoveryProvider.recordConnectionAttempt(targetNodeId, dev.p2pSsid, durationMs, true)
                runOnUiThread {
                    etTargetIp.setText(goIp)
                    etPort.setText(dev.port.toString())
                    Toast.makeText(this@MainActivity, "Direct connected to ${dev.displayName} ($goIp) in ${durationMs}ms", Toast.LENGTH_SHORT).show()
                    onConnected?.invoke(goIp)
                }
            }
        } else if (dev.p2pPeer != null) {
            val peerAddr = dev.p2pPeer.deviceAddress
            AppLogger.i("MainActivity", "Connecting to P2P peer '${dev.displayName}' ($peerAddr)...")
            Toast.makeText(this, "Connecting to ${dev.displayName}...", Toast.LENGTH_SHORT).show()
            WifiDirectManager.connectToPeer(this, dev.p2pPeer) { goIp ->
                val durationMs = System.currentTimeMillis() - connStartMs
                WifiDirectDiscoveryProvider.recordConnectionAttempt(targetNodeId, peerAddr, durationMs, true)
                runOnUiThread {
                    etTargetIp.setText(goIp)
                    etPort.setText(dev.port.toString())
                    Toast.makeText(this@MainActivity, "Direct connected to ${dev.displayName} ($goIp) in ${durationMs}ms", Toast.LENGTH_SHORT).show()
                    onConnected?.invoke(goIp)
                }
            }
        } else {
            val durationMs = System.currentTimeMillis() - connStartMs
            WifiDirectDiscoveryProvider.recordConnectionAttempt(targetNodeId, "None", durationMs, false, "Missing credentials or P2P peer")
            Toast.makeText(this, "No Wi-Fi Direct credentials found for ${dev.displayName}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onModeSwitched(oldMode: Mode, newMode: Mode) {
        layoutConnectedDevicesContainer.removeAllViews()
        if (oldMode == Mode.RECEIVER && newMode == Mode.TRANSMITTER) {
            com.example.audiostreamer.node.discovery.WifiAwareDiscoveryProvider.stopPublishing()
            com.example.audiostreamer.node.discovery.HatBlePresenceProvider.stopAdvertising()
            BleDiscoveryManager.stopAdvertising()

            // 1. If receiver autonomous P2P group was active, tear it down
            if (switchReceiverP2p.isChecked || WifiDirectManager.isGroupCreated.value) {
                switchReceiverP2p.isChecked = false
                WifiDirectManager.removeGroup(this)
                layoutReceiverP2pInfo.visibility = View.GONE
                AppLogger.i("MainActivity", "Torn down autonomous P2P group on mode switch to Transmitter")
            }

            // 2. Clear any stale P2P IP (e.g. 192.168.49.1) from target IP field
            val targetText = etTargetIp.text?.toString()?.trim().orEmpty()
            if (targetText.startsWith("192.168.49.") || targetText == WifiDirectManager.groupOwnerIp.value) {
                val activeProf = ConnectionProfileManager.activeProfileFlow.value
                if (activeProf != null && !activeProf.targetIp.startsWith("192.168.49.")) {
                    etTargetIp.setText(activeProf.targetIp)
                    etPort.setText(activeProf.port.toString())
                    currentConnType = activeProf.connectionType
                } else {
                    val broadcastIp = NetworkUtils.getSuggestedBroadcastIp()
                    etTargetIp.setText(broadcastIp)
                    etPort.setText(AudioConfig.DEFAULT_PORT.toString())
                    currentConnType = ConnectionType.LOCAL_WIFI
                }
            }

            // 3. Refresh local IP to make sure detectedLocalIp is not stuck on p2p0
            refreshLocalIp()
        }
    }

    private fun updateSavedProfilesUi() {
        layoutSavedProfilesContainer.removeAllViews()
        val profiles = ConnectionProfileManager.profilesFlow.value
        val activeProfile = ConnectionProfileManager.activeProfileFlow.value
        val currentIp = etTargetIp.text?.toString()?.trim().orEmpty()

        tvSavedProfilesTitle.text = "TARGET PROFILES (${profiles.size})"
        layoutSavedEmpty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE

        for (profile in profiles) {
            val itemView = layoutInflater.inflate(R.layout.item_saved_profile, layoutSavedProfilesContainer, false)
            val card = itemView.findViewById<MaterialCardView>(R.id.card_saved_profile)
            val rbActive = itemView.findViewById<RadioButton>(R.id.rb_profile_active)
            val tvName = itemView.findViewById<TextView>(R.id.tv_profile_name)
            val tvBadge = itemView.findViewById<TextView>(R.id.tv_profile_conn_badge)
            val tvDetails = itemView.findViewById<TextView>(R.id.tv_profile_details)
            val btnOverflow = itemView.findViewById<ImageButton>(R.id.btn_profile_overflow)

            tvName.text = profile.name
            val isP2p = profile.connectionType == ConnectionType.WIFI_DIRECT
            tvBadge.text = if (isP2p) "P2P" else "WI-FI"

            val prefModeDesc = when (profile.preferredStreamingProfile) {
                AudioConfig.PROFILE_AUTO -> "Auto Adaptive"
                AudioConfig.PROFILE_LOW_LATENCY -> "Low Latency"
                else -> "Uncapped 24-bit"
            }
            tvDetails.text = "${profile.targetIp}:${profile.port} • $prefModeDesc"

            val isSelected = (activeProfile?.id == profile.id) || (activeProfile == null && currentIp.isNotEmpty() && currentIp == profile.targetIp)
            rbActive.isChecked = isSelected
            if (isSelected) {
                card.strokeColor = ContextCompat.getColor(this, R.color.primary)
                card.strokeWidth = 2.dpToPx()
            } else {
                card.strokeColor = ContextCompat.getColor(this, R.color.card_stroke)
                card.strokeWidth = 1.dpToPx()
            }

            card.setOnClickListener {
                applyProfile(profile)
                Toast.makeText(this, "Active: ${profile.name}", Toast.LENGTH_SHORT).show()
            }

            btnOverflow.setOnClickListener { v ->
                val popup = PopupMenu(this, v)
                popup.menu.add("Edit Profile")
                popup.menu.add("Delete Profile")
                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.title) {
                        "Edit Profile" -> {
                            showEditProfileDialog(profile)
                            true
                        }
                        "Delete Profile" -> {
                            MaterialAlertDialogBuilder(this)
                                .setTitle("Delete Profile")
                                .setMessage("Remove '${profile.name}' from saved profiles?")
                                .setPositiveButton("Delete") { _, _ ->
                                    ConnectionProfileManager.deleteProfile(this, profile.id)
                                    Toast.makeText(this, "Deleted '${profile.name}'", Toast.LENGTH_SHORT).show()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                            true
                        }
                        else -> false
                    }
                }
                popup.show()
            }

            layoutSavedProfilesContainer.addView(itemView)
        }
    }

    private fun applyProfile(profile: ConnectionProfile) {
        ConnectionProfileManager.setActiveProfile(this, profile)
        etTargetIp.setText(profile.targetIp)
        etPort.setText(profile.port.toString())
        currentConnType = profile.connectionType

        if (profile.preferredStreamingProfile.isNotEmpty()) {
            val prefs = getSharedPreferences("stream_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString(AudioConfig.PREF_KEY_PROFILE, profile.preferredStreamingProfile).apply()
        }
        updateSavedProfilesUi()
    }

    private fun switchToP2pMode(discoveredDev: DiscoveredDevice? = null) {
        if (!checkAndRequestP2pPermissions()) return
        currentConnType = ConnectionType.WIFI_DIRECT

        val targetDev = discoveredDev ?: DiscoveryManager.discoveredDevices.value.find {
            it.ip == etTargetIp.text?.toString()?.trim() && it.isP2pActive
        }

        val ssid = targetDev?.p2pSsid
        val pass = targetDev?.p2pPassphrase ?: WifiDirectManager.P2P_DEFAULT_PASSPHRASE

        if (targetDev != null && !ssid.isNullOrEmpty()) {
            AppLogger.i("MainActivity", "Direct-linking to '${targetDev.name}' SSID=$ssid...")
            Toast.makeText(this, "Direct-linking to ${targetDev.name}...", Toast.LENGTH_SHORT).show()
            WifiDirectManager.connectWithCredentials(
                this,
                ssid,
                pass
            ) { goIp ->
                runOnUiThread {
                    etTargetIp.setText(goIp)
                    val p2pProfile = ConnectionProfile(
                        id = "p2p_${targetDev.ip}",
                        name = "${targetDev.name} (Direct)",
                        targetIp = goIp,
                        port = targetDev.port,
                        connectionType = ConnectionType.WIFI_DIRECT,
                        preferredStreamingProfile = AudioConfig.PROFILE_MUSIC,
                        capabilitiesMask = targetDev.capabilitiesMask,
                        p2pSsid = ssid,
                        p2pPassphrase = pass
                    )
                    ConnectionProfileManager.saveProfile(this, p2pProfile)
                    applyProfile(p2pProfile)
                    AppLogger.i("MainActivity", "Direct Link Established! Target IP set to $goIp")
                    Toast.makeText(this, "Direct Link Established ($goIp)", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            AppLogger.i("MainActivity", "No targeted P2P device credentials. Starting Wi-Fi Direct peer scan...")
            WifiDirectManager.discoverPeers(this)
            Toast.makeText(this, "Scanning for Wi-Fi Direct receivers...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEditProfileDialog(profile: ConnectionProfile?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_profile, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_title)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.et_profile_name)
        val etIp = dialogView.findViewById<TextInputEditText>(R.id.et_profile_ip)
        val etPort = dialogView.findViewById<TextInputEditText>(R.id.et_profile_port)
        val toggleMode = dialogView.findViewById<MaterialButtonToggleGroup>(R.id.toggle_profile_mode)

        if (profile != null) {
            tvTitle.text = "Edit Profile: ${profile.name}"
            etName.setText(profile.name)
            etIp.setText(profile.targetIp)
            etPort.setText(profile.port.toString())
            when (profile.preferredStreamingProfile) {
                AudioConfig.PROFILE_AUTO -> toggleMode.check(R.id.btn_mode_pref_auto)
                AudioConfig.PROFILE_LOW_LATENCY -> toggleMode.check(R.id.btn_mode_pref_low_latency)
                else -> toggleMode.check(R.id.btn_mode_pref_music)
            }
        } else {
            tvTitle.text = "Add Target Profile"
            etName.setText("")
            etIp.setText("")
            etPort.setText("${AudioConfig.DEFAULT_PORT}")
            toggleMode.check(R.id.btn_mode_pref_music)
        }

        MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val name = etName.text?.toString()?.trim().orEmpty().ifEmpty { "Receiver" }
                val targetIp = etIp.text?.toString()?.trim().orEmpty()
                val port = etPort.text?.toString()?.toIntOrNull() ?: AudioConfig.DEFAULT_PORT
                val chosenMode = when (toggleMode.checkedButtonId) {
                    R.id.btn_mode_pref_auto -> AudioConfig.PROFILE_AUTO
                    R.id.btn_mode_pref_low_latency -> AudioConfig.PROFILE_LOW_LATENCY
                    else -> AudioConfig.PROFILE_MUSIC
                }

                if (targetIp.isEmpty()) {
                    Toast.makeText(this, "Please enter a valid IP address", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (profile != null) {
                    ConnectionProfileManager.updateProfilePreferences(this, profile.id, name, targetIp, port, chosenMode)
                    val active = ConnectionProfileManager.activeProfileFlow.value
                    if (active?.id == profile.id) {
                        applyProfile(active.copy(name = name, targetIp = targetIp, port = port, preferredStreamingProfile = chosenMode))
                    }
                    Toast.makeText(this, "Profile updated: $name", Toast.LENGTH_SHORT).show()
                } else {
                    val newProfile = ConnectionProfile(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        targetIp = targetIp,
                        port = port,
                        connectionType = currentConnType,
                        preferredStreamingProfile = chosenMode,
                        capabilitiesMask = DiscoveryManager.lastDiscoveredReceiverCapabilities
                    )
                    ConnectionProfileManager.saveProfile(this, newProfile)
                    applyProfile(newProfile)
                    Toast.makeText(this, "Profile saved: $name", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshLocalIp() {
        val node = com.example.audiostreamer.node.LocalNodeManager.getLocalNode()
        updateLocalNodeUi(node)

        val allIps = NetworkUtils.getAllLocalIpAddresses()
        if (allIps.isNotEmpty()) {
            if (etTargetIp.text.isNullOrEmpty() ||
                etTargetIp.text.toString() == "192.168.1.255" ||
                etTargetIp.text.toString() == "192.168.43.255") {
                val activeProf = ConnectionProfileManager.activeProfileFlow.value
                if (activeProf != null) {
                    etTargetIp.setText(activeProf.targetIp)
                } else {
                    val suggested = NetworkUtils.getSuggestedBroadcastIp()
                    if (suggested.isNotEmpty()) {
                        etTargetIp.setText(suggested)
                    }
                }
            }
        }
    }

    private fun updateLocalNodeUi(node: com.example.audiostreamer.node.NodeInfo) {
        tvHeaderNodeName.text = node.name
        val allIps = NetworkUtils.getAllLocalIpAddresses()
        if (allIps.isNotEmpty()) {
            detectedLocalIp = allIps.first()
            val extra = allIps.size - 1
            val ipStr = if (extra > 0) "${allIps.first()} (+$extra)" else allIps.first()
            tvHeaderIp.text = " • $ipStr"
            viewHeaderNodeDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_green))
        } else {
            detectedLocalIp = null
            tvHeaderIp.text = " • Offline"
            viewHeaderNodeDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_gray))
        }
        updateTransportBadges()
    }

    private fun updateTransportBadges() {
        val hasLan = NetworkUtils.getAllLocalIpAddresses().isNotEmpty()
        val hasP2p = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        val hasNan = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
        val hasBle = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val nfcState = HatNfcBootstrapProvider.state.value

        // LAN
        if (hasLan) {
            badgeTransportLan.visibility = View.VISIBLE
            badgeTransportLan.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            badgeTransportLan.visibility = View.VISIBLE
            badgeTransportLan.setTextColor(ContextCompat.getColor(this, R.color.text_hint))
        }

        // P2P (Wi-Fi Direct)
        if (hasP2p) {
            badgeTransportP2p.visibility = View.VISIBLE
            val isP2pActive = WifiDirectManager.isGroupCreated.value ||
                com.example.audiostreamer.node.HatLinkManager.activeLinks.value.any { it.hatTransportType == com.example.audiostreamer.node.transport.HatTransportType.WIFI_DIRECT }
            badgeTransportP2p.setTextColor(
                ContextCompat.getColor(this, if (isP2pActive) R.color.status_green else R.color.pill_text)
            )
        } else {
            badgeTransportP2p.visibility = View.GONE
        }

        // Wi-Fi Aware (NAN)
        if (hasNan) {
            badgeTransportNan.visibility = View.VISIBLE
            val isNanActive = com.example.audiostreamer.node.HatLinkManager.activeLinks.value.any { it.hatTransportType == com.example.audiostreamer.node.transport.HatTransportType.WIFI_AWARE }
            badgeTransportNan.setTextColor(
                ContextCompat.getColor(this, if (isNanActive) R.color.status_green else R.color.pill_text)
            )
        } else {
            badgeTransportNan.visibility = View.GONE
        }

        // BLE
        if (hasBle) {
            badgeTransportBle.visibility = View.VISIBLE
            val isBleScanning = com.example.audiostreamer.node.discovery.HatBlePresenceProvider.isScanning.value
            badgeTransportBle.setTextColor(
                ContextCompat.getColor(this, if (isBleScanning) R.color.status_green else R.color.pill_text)
            )
        } else {
            badgeTransportBle.visibility = View.GONE
        }

        // NFC
        updateNfcUi(nfcState)
    }

    private fun updateNfcUi(state: NfcBootstrapState) {
        cardNfcTap.visibility = View.GONE
        when (state) {
            NfcBootstrapState.UNSUPPORTED -> {
                badgeTransportNfc.visibility = View.GONE
            }
            NfcBootstrapState.DISABLED -> {
                badgeTransportNfc.visibility = View.VISIBLE
                badgeTransportNfc.setTextColor(ContextCompat.getColor(this, R.color.status_orange))
                ivNfcIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_orange))
                tvNfcTitle.text = "NFC is Disabled"
                tvNfcSubtitle.text = "Tap to open settings and enable NFC tap-to-pair"
            }
            NfcBootstrapState.READY -> {
                badgeTransportNfc.visibility = View.VISIBLE
                badgeTransportNfc.setTextColor(ContextCompat.getColor(this, R.color.status_green))
                ivNfcIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
                tvNfcTitle.text = "NFC Tap-to-Pair Ready"
                tvNfcSubtitle.text = if (currentMode == Mode.TRANSMITTER) {
                    "Touch devices back-to-back to send stream invitation"
                } else {
                    "Touch devices back-to-back to tune in and receive"
                }
            }
            NfcBootstrapState.PROCESSING_TAP -> {
                badgeTransportNfc.visibility = View.VISIBLE
                ivNfcIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_green))
                tvNfcTitle.text = "Pairing via NFC..."
                tvNfcSubtitle.text = "Exchanging node connection parameters"
            }
            NfcBootstrapState.STOPPED, NfcBootstrapState.ERROR -> {
                badgeTransportNfc.visibility = View.VISIBLE
                badgeTransportNfc.setTextColor(ContextCompat.getColor(this, R.color.status_red))
                ivNfcIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_red))
                tvNfcTitle.text = "NFC Offline"
                tvNfcSubtitle.text = "Tap to view NFC diagnostics"
            }
        }
    }

    private fun showNodeDetailsDialog() {
        val node = com.example.audiostreamer.node.LocalNodeManager.getLocalNode()
        val allIps = NetworkUtils.getAllLocalIpAddresses()
        val ipListStr = if (allIps.isNotEmpty()) allIps.joinToString(", ") else "Offline"
        val transports = mutableListOf<String>()
        if (allIps.isNotEmpty()) transports.add("LAN (Local Network)")
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) transports.add("Wi-Fi Direct (P2P)")
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) transports.add("Wi-Fi Aware (NAN)")
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) transports.add("BLE Proximity")
        if (HatNfcBootstrapProvider.isSupported) transports.add("NFC Out-of-Band Bootstrap")

        val supportedCodecs = node.capabilities.supportedCodecs.joinToString(", ") { it.name }
        val supportedRates = node.capabilities.supportedSampleRates.joinToString(", ") { "${it / 1000}kHz" }
        val maxChannels = node.capabilities.supportedChannelCounts.maxOrNull() ?: 2

        val message = """
            Device Name: ${node.name}
            Node ID: ${node.id}
            Current Role: ${if (currentMode == Mode.TRANSMITTER) "Broadcast Hub (Send)" else "Audio Sink (Listen)"}
            Local IP(s): $ipListStr

            Active / Available Transports:
            ${transports.joinToString("\n") { "• $it" }}

            Hardware Audio Capabilities:
            • Codecs: $supportedCodecs
            • Sample Rates: $supportedRates
            • Max Channels: $maxChannels
        """.trimIndent()

        MaterialAlertDialogBuilder(this)
            .setTitle("Local HAT Node Identity")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showNfcInteractionDialog() {
        val nfcState = HatNfcBootstrapProvider.state.value
        if (nfcState == NfcBootstrapState.DISABLED) {
            MaterialAlertDialogBuilder(this)
                .setTitle("NFC is Disabled")
                .setMessage("Near Field Communication (NFC) is turned off. Would you like to open Android Settings to enable it?")
                .setPositiveButton("Open Settings") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                    } catch (e: Exception) {
                        Toast.makeText(this, "Could not open NFC settings: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val roleStr = if (currentMode == Mode.TRANSMITTER) "Broadcast (Send)" else "Receive (Listen)"
        val actionStr = if (currentMode == Mode.TRANSMITTER) {
            "Touching another device back-to-back will transmit an invitation to stream audio from this device."
        } else {
            "Touching another device back-to-back will accept an incoming stream invitation and begin playback."
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("NFC Tap-to-Pair")
            .setMessage("Current Role: $roleStr\n\n$actionStr\n\nEnsure both devices have NFC enabled and touch their back panels together.")
            .setPositiveButton("Got It", null)
            .show()
    }

    private fun observeTelemetry() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                StreamState.telemetry.collect { t ->
                    renderTelemetry(t)
                }
            }
        }
    }

    private fun renderTelemetry(t: Telemetry) {
        val isSenderRunning = AudioCaptureService.isRunning.get()
        val isSinkRunning = AudioSinkService.isRunning.get()

        if (isSenderRunning || isSinkRunning) {
            isStarting = false
        }
        if (!isSenderRunning && !isSinkRunning) {
            isStopping = false
        }

        if (isSenderRunning && currentMode != Mode.TRANSMITTER) {
            currentMode = Mode.TRANSMITTER
            toggleModeGroup.check(R.id.btn_mode_transmitter)
        } else if (isSinkRunning && currentMode != Mode.RECEIVER) {
            currentMode = Mode.RECEIVER
            toggleModeGroup.check(R.id.btn_mode_receiver)
        }

        updateModeAndButtonUi()
        updateConnectedDevicesUi(t)

        if (currentMode == Mode.TRANSMITTER) {
            val curVol = t.remoteVolumePercent
            if (!sliderRemoteVol.isPressed && sliderRemoteVol.value.toInt() != curVol) {
                sliderRemoteVol.value = curVol.toFloat()
                tvRemoteVolLabel.text = "$curVol%"
            }
        }

        updateMediaPlaybackUi()
    }

    private fun updateMediaPlaybackUi() {
        val isSenderRunning = AudioCaptureService.isRunning.get()
        val isSinkRunning = AudioSinkService.isRunning.get()

        if (!isSenderRunning && !isSinkRunning) {
            cardMediaPlayback.visibility = View.GONE
            return
        }

        cardMediaPlayback.visibility = View.VISIBLE

        val art = if (isSenderRunning) {
            MediaSessionTracker.currentState?.artwork
        } else {
            AudioSinkService.currentInstance?.currentTrackArtwork
        }

        if (art != null) {
            ivMediaArt.setImageBitmap(art)
            ivMediaArt.setPadding(0, 0, 0, 0)
            ivMediaArt.imageTintList = null

            val blurred = createBlurredArtwork(art)
            ivMediaCardBg.setImageBitmap(blurred)
            ivMediaCardBg.visibility = View.VISIBLE
            viewMediaCardScrim.visibility = View.VISIBLE
        } else {
            ivMediaArt.setImageResource(R.drawable.ic_category_audio)
            val pad = (8 * resources.displayMetrics.density).toInt()
            ivMediaArt.setPadding(pad, pad, pad, pad)
            ivMediaArt.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_primary))

            ivMediaCardBg.visibility = View.GONE
            viewMediaCardScrim.visibility = View.GONE
        }

        if (isSenderRunning) {
            val meta = AudioCaptureService.currentTrackMetadata
            tvMediaTitle.text = meta?.title?.ifBlank { "Streaming Audio" } ?: "Streaming Audio"
            tvMediaArtist.text = meta?.artist?.ifBlank { "Transmitter" } ?: "Transmitter"
            val isPlaying = meta?.isPlaying ?: true
            btnMediaPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            btnSyncMediaPermission.visibility = if (MediaNotificationListenerService.isServiceConnected) View.GONE else View.VISIBLE
        } else {
            val sink = AudioSinkService.currentInstance
            tvMediaTitle.text = sink?.currentTrackTitle?.ifBlank { "Receiving Audio" } ?: "Receiving Audio"
            tvMediaArtist.text = sink?.currentTrackArtist?.ifBlank { "Transmitter" } ?: "Transmitter"
            val isPlaying = sink?.isTrackPlaying ?: true
            btnMediaPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            btnSyncMediaPermission.visibility = View.GONE
        }
    }

    private fun createBlurredArtwork(source: Bitmap): Bitmap {
        return try {
            val width = 36
            val height = 36
            val small = Bitmap.createScaledBitmap(source, width, height, true)
            fastBoxBlur(small, 6)
        } catch (e: Exception) {
            source
        }
    }

    private fun fastBoxBlur(bitmap: Bitmap, radius: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val r = radius.coerceAtLeast(1)
        val div = 2 * r + 1

        val temp = IntArray(w * h)
        for (y in 0 until h) {
            var rSum = 0; var gSum = 0; var bSum = 0
            for (i in -r..r) {
                val p = pix[y * w + i.coerceIn(0, w - 1)]
                rSum += (p shr 16) and 0xFF
                gSum += (p shr 8) and 0xFF
                bSum += p and 0xFF
            }
            for (x in 0 until w) {
                temp[y * w + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                val pOut = pix[y * w + (x - r).coerceIn(0, w - 1)]
                val pIn = pix[y * w + (x + r + 1).coerceIn(0, w - 1)]
                rSum += ((pIn shr 16) and 0xFF) - ((pOut shr 16) and 0xFF)
                gSum += ((pIn shr 8) and 0xFF) - ((pOut shr 8) and 0xFF)
                bSum += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val finalPix = IntArray(w * h)
        for (x in 0 until w) {
            var rSum = 0; var gSum = 0; var bSum = 0
            for (i in -r..r) {
                val p = temp[i.coerceIn(0, h - 1) * w + x]
                rSum += (p shr 16) and 0xFF
                gSum += (p shr 8) and 0xFF
                bSum += p and 0xFF
            }
            for (y in 0 until h) {
                finalPix[y * w + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                val pOut = temp[(y - r).coerceIn(0, h - 1) * w + x]
                val pIn = temp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                rSum += ((pIn shr 16) and 0xFF) - ((pOut shr 16) and 0xFF)
                gSum += ((pIn shr 8) and 0xFF) - ((pOut shr 8) and 0xFF)
                bSum += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }
        result.setPixels(finalPix, 0, w, 0, 0, w, h)
        return result
    }

    private fun startTransmitterWorkflow() {
        val ip = etTargetIp.text?.toString()?.trim().orEmpty()
        val port = etPort.text?.toString()?.toIntOrNull()

        if (ip.isEmpty()) {
            etTargetIp.error = "Target IP address is required"
            return
        }
        if (port == null || port !in 1024..65535) {
            etPort.error = "Enter a valid port (1024-65535)"
            return
        }

        if (AudioSinkService.isRunning.get()) {
            stopReceiverService()
        }

        val neededPermissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (neededPermissions.isNotEmpty()) {
            transmitterPermissionLauncher.launch(neededPermissions.toTypedArray())
        } else {
            launchMediaProjectionConsent()
        }
    }

    private fun launchMediaProjectionConsent() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun stopTransmitterService() {
        val stopIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_STOP
        }
        isStopping = true
        isStarting = false
        startService(stopIntent)
        syncDiscoveryMode()
        updateModeAndButtonUi()
    }

    private fun startReceiverWorkflow() {
        val port = etPort.text?.toString()?.toIntOrNull()
        if (port == null || port !in 1024..65535) {
            etPort.error = "Enter a valid port (1024-65535)"
            return
        }

        if (AudioCaptureService.isRunning.get()) {
            stopTransmitterService()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            receiverPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startReceiverService()
        }
    }

    private fun startReceiverService() {
        val port = etPort.text?.toString()?.toIntOrNull() ?: AudioConfig.DEFAULT_PORT
        val serviceIntent = Intent(this, AudioSinkService::class.java).apply {
            action = AudioSinkService.ACTION_START
            putExtra(AudioSinkService.EXTRA_PORT, port)
        }
        isStarting = true
        isStopping = false
        ContextCompat.startForegroundService(this, serviceIntent)
        updateModeAndButtonUi()
    }

    private fun stopReceiverService() {
        val stopIntent = Intent(this, AudioSinkService::class.java).apply {
            action = AudioSinkService.ACTION_STOP
        }
        isStopping = true
        isStarting = false
        startService(stopIntent)
        syncDiscoveryMode()
        updateModeAndButtonUi()
    }

    private fun updateModeAndButtonUi() {
        val isSenderActive = AudioCaptureService.isRunning.get()
        val isSinkActive = AudioSinkService.isRunning.get()
        val isAnyActive = isSenderActive || isSinkActive || isStarting || isStopping

        btnModeTransmitter.isEnabled = !isAnyActive
        btnModeReceiver.isEnabled = !isAnyActive

        // Update Toggle buttons styling for dark mode clarity
        val colorPrimary = ContextCompat.getColor(this, R.color.primary)
        val colorGreen = ContextCompat.getColor(this, R.color.status_green)
        val colorRed = ContextCompat.getColor(this, R.color.status_red)
        val colorCard = ContextCompat.getColor(this, R.color.card_bg)
        val colorTextSecondary = ContextCompat.getColor(this, R.color.text_secondary)

        when (currentMode) {
            Mode.TRANSMITTER -> {
                btnModeTransmitter.backgroundTintList = ColorStateList.valueOf(colorPrimary)
                btnModeTransmitter.setTextColor(Color.WHITE)
                btnModeTransmitter.iconTint = ColorStateList.valueOf(Color.WHITE)

                btnModeReceiver.backgroundTintList = ColorStateList.valueOf(colorCard)
                btnModeReceiver.setTextColor(colorTextSecondary)
                btnModeReceiver.iconTint = ColorStateList.valueOf(colorTextSecondary)

                tvModeGuide?.text = "Broadcast audio to nearby speakers, receivers, or devices"
                cardReceiverDiscoverable.visibility = View.GONE
                layoutSavedProfilesSection.visibility = View.GONE
                layoutDiscoverySection.visibility = View.VISIBLE
                layoutReceiverP2p.visibility = View.GONE
                layoutVolumeControl.visibility = View.VISIBLE
                layoutAdvancedHeader.visibility = View.GONE
                layoutAdvancedContent.visibility = View.GONE
                etTargetIp.isEnabled = !isSenderActive && !isStarting && !isStopping
                etPort.isEnabled = !isSenderActive && !isStarting && !isStopping

                when {
                    isStarting -> {
                        btnAction.isEnabled = false
                        btnAction.text = "Starting..."
                        btnAction.setIconResource(R.drawable.ic_play)
                        btnAction.backgroundTintList = ColorStateList.valueOf(colorPrimary)
                    }
                    isStopping -> {
                        btnAction.isEnabled = false
                        btnAction.text = "Stopping..."
                        btnAction.setIconResource(R.drawable.ic_stop)
                        btnAction.backgroundTintList = ColorStateList.valueOf(colorRed)
                    }
                    isSenderActive -> {
                        btnAction.isEnabled = true
                        btnAction.text = getString(R.string.stop_stream)
                        btnAction.setIconResource(R.drawable.ic_stop)
                        btnAction.backgroundTintList = ColorStateList.valueOf(colorRed)
                        wasSenderActive = true
                    }

                    else -> {
                        btnAction.isEnabled = true
                        btnAction.text = getString(R.string.start_stream)
                        btnAction.setIconResource(R.drawable.ic_play)
                        btnAction.backgroundTintList = ColorStateList.valueOf(colorPrimary)
                        wasSenderActive = false
                    }
                }
            }
            Mode.RECEIVER -> {
                btnModeReceiver.backgroundTintList = ColorStateList.valueOf(colorGreen)
                btnModeReceiver.setTextColor(Color.WHITE)
                btnModeReceiver.iconTint = ColorStateList.valueOf(Color.WHITE)

                btnModeTransmitter.backgroundTintList = ColorStateList.valueOf(colorCard)
                btnModeTransmitter.setTextColor(colorTextSecondary)
                btnModeTransmitter.iconTint = ColorStateList.valueOf(colorTextSecondary)

                tvModeGuide?.text = "Accept and play audio streams from nearby transmitters"
                cardReceiverDiscoverable.visibility = View.VISIBLE
                updateReceiverDiscoverableBanner()
                layoutDiscoverySection.visibility = View.GONE
                layoutSavedProfilesSection.visibility = View.GONE
                layoutReceiverP2p.visibility = View.GONE
                layoutVolumeControl.visibility = View.GONE
                layoutAdvancedHeader.visibility = View.GONE
                layoutAdvancedContent.visibility = View.GONE
                etPort.isEnabled = !isSinkActive && !isStarting && !isStopping

                when {
                    isStarting -> {
                        btnAction.isEnabled = false
                        btnAction.text = "Starting..."
                        btnAction.setIconResource(R.drawable.ic_play)
                        btnAction.backgroundTintList = ColorStateList.valueOf(colorGreen)
                    }
                    isStopping -> {
                        btnAction.isEnabled = false
                        btnAction.text = "Stopping..."
                        btnAction.setIconResource(R.drawable.ic_stop)
                        btnAction.backgroundTintList = ColorStateList.valueOf(colorRed)
                    }
                    isSinkActive -> {
                        btnAction.isEnabled = true
                        btnAction.text = getString(R.string.stop_receiver)
                        btnAction.setIconResource(R.drawable.ic_stop)
                        btnAction.backgroundTintList = ColorStateList.valueOf(colorRed)
                    }
                    else -> {
                        btnAction.isEnabled = true
                        btnAction.text = getString(R.string.start_receiver)
                        btnAction.setIconResource(R.drawable.ic_play)
                        btnAction.backgroundTintList = ColorStateList.valueOf(colorGreen)
                    }
                }
            }
        }
        updateNfcUi(HatNfcBootstrapProvider.state.value)
    }

    private fun sendVolumeIntent(volumePercent: Int) {
        val clamped = volumePercent.coerceIn(0, 100)
        if (!sliderRemoteVol.isPressed && sliderRemoteVol.value.toInt() != clamped) {
            sliderRemoteVol.value = clamped.toFloat()
        }
        tvRemoteVolLabel.text = "$clamped%"
        val intent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_SET_VOLUME
            putExtra(AudioCaptureService.EXTRA_VOLUME_PERCENT, clamped)
        }
        startService(intent)
    }

    private fun sendVolumeDeltaIntent(delta: Int) {
        val cur = AudioCaptureService.remoteVolumePercent.get()
        val newVol = (cur + delta).coerceIn(0, 100)
        sliderRemoteVol.value = newVol.toFloat()
        tvRemoteVolLabel.text = "$newVol%"
        sendVolumeIntent(newVol)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (currentMode == Mode.TRANSMITTER && AudioCaptureService.isRunning.get()) {
            val keyCode = event.keyCode
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0 || event.repeatCount % 3 == 0) {
                        val delta = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 5 else -5
                        sendVolumeDeltaIntent(delta)
                    }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
