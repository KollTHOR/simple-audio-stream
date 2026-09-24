package com.example.audiostreamer

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import com.google.android.material.switchmaterial.SwitchMaterial
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
    private lateinit var layoutTransportBadges: LinearLayout
    private lateinit var badgeTransportLan: TextView
    private lateinit var badgeTransportP2p: TextView
    private lateinit var badgeTransportNan: TextView
    private lateinit var badgeTransportBle: TextView
    private lateinit var badgeTransportNfc: TextView

    // Interactive NFC Tap-to-Pair Card
    private lateinit var cardNfcTap: MaterialCardView
    private lateinit var ivNfcIcon: ImageView
    private lateinit var tvNfcTitle: TextView
    private lateinit var tvNfcSubtitle: TextView

    private lateinit var toggleModeGroup: MaterialButtonToggleGroup
    private lateinit var btnModeTransmitter: MaterialButton
    private lateinit var btnModeReceiver: MaterialButton
    private lateinit var tvModeGuide: TextView

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

    private lateinit var layoutConnectedDevicesSection: LinearLayout
    private lateinit var tvConnectedDevicesTitle: TextView
    private lateinit var tvConnectedCountBadge: TextView
    private lateinit var layoutConnectedDevicesContainer: LinearLayout

    private lateinit var layoutDiscoverySection: LinearLayout
    private lateinit var tvDiscoveryTitle: TextView
    private lateinit var pbDiscoveryScanning: ProgressBar
    private lateinit var tvDiscoveryScanningText: TextView
    private lateinit var btnScanReceivers: MaterialButton
    private lateinit var layoutDiscoveredDevicesContainer: LinearLayout
    private lateinit var tvAvailableEmpty: TextView

    private lateinit var layoutReceiverP2p: LinearLayout
    private lateinit var switchReceiverP2p: SwitchMaterial
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

    private lateinit var tvBadgeStatus: TextView
    private lateinit var tvEndpointInfo: TextView
    private lateinit var tvPacketsStat: TextView
    private lateinit var pbAudioLevel: ProgressBar
    private lateinit var tvAudioLevelVal: TextView
    private lateinit var tvDiagnosticTip: TextView
    private lateinit var layoutPipelineDetails: LinearLayout
    private lateinit var tvPipelineProfile: TextView
    private lateinit var tvPipelineFormat: TextView
    private lateinit var tvPipelineNodeInfo: TextView
    private lateinit var tvBufferHealthVal: TextView
    private lateinit var pbBufferHealth: ProgressBar

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
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
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
        tvModeGuide = findViewById(R.id.tv_mode_guide)

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

        layoutDiscoverySection = findViewById(R.id.layout_discovery_section)
        tvDiscoveryTitle = findViewById(R.id.tv_discovery_title)
        pbDiscoveryScanning = findViewById(R.id.pb_discovery_scanning)
        tvDiscoveryScanningText = findViewById(R.id.tv_discovery_scanning_text)
        btnScanReceivers = findViewById(R.id.btn_scan_receivers)
        layoutDiscoveredDevicesContainer = findViewById(R.id.layout_discovered_devices_container)
        tvAvailableEmpty = findViewById(R.id.tv_available_empty)

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
            DiscoveryManager.triggerScan(lifecycleScope)
            if (currentMode == Mode.TRANSMITTER && !AudioCaptureService.isRunning.get()) {
                LanDiscoveryProvider.stopDiscovery()
                LanDiscoveryProvider.startDiscovery(this)
                if (checkAndRequestP2pPermissions()) {
                    WifiDirectDiscoveryProvider.stopDiscovery()
                    WifiDirectDiscoveryProvider.startDiscovery(this)
                }
            }
            if (checkAndRequestP2pPermissions()) {
                WifiDirectManager.discoverPeers(this)
            }
            if (checkAndRequestBlePermissions()) {
                BleDiscoveryManager.startScanning(this)
                com.example.audiostreamer.node.discovery.HatBlePresenceProvider.startScanning(this)
            }
            com.example.audiostreamer.node.discovery.WifiAwareDiscoveryProvider.startSubscribing(this)
            HatDiscoveryRegistry.recomputeRegistry()
            Toast.makeText(this, "Scanning for nearby devices...", Toast.LENGTH_SHORT).show()
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

        tvBadgeStatus = findViewById(R.id.tv_badge_status)
        tvEndpointInfo = findViewById(R.id.tv_endpoint_info)
        tvPacketsStat = findViewById(R.id.tv_packets_stat)
        pbAudioLevel = findViewById(R.id.pb_audio_level)
        tvAudioLevelVal = findViewById(R.id.tv_audio_level_val)
        tvDiagnosticTip = findViewById(R.id.tv_diagnostic_tip)
        layoutPipelineDetails = findViewById(R.id.layout_pipeline_details)
        tvPipelineProfile = findViewById(R.id.tv_pipeline_profile)
        tvPipelineFormat = findViewById(R.id.tv_pipeline_format)
        tvPipelineNodeInfo = findViewById(R.id.tv_pipeline_node_info)
        tvBufferHealthVal = findViewById(R.id.tv_buffer_health_val)
        pbBufferHealth = findViewById(R.id.pb_buffer_health)

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

        layoutPipelineDetails.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java).apply {
                putExtra(SettingsActivity.EXTRA_CATEGORY, SettingsActivity.CATEGORY_DIAGNOSTICS)
            }
            startActivity(intent)
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_main_view_logs)?.setOnClickListener {
            AppLogger.showLogViewerDialog(this)
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_main_copy_logs)?.setOnClickListener {
            AppLogger.copyToClipboard(this)
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
        DiscoveryManager.stopDiscovery()
        DiscoveryManager.stopReceiverResponder()
        LanDiscoveryProvider.stopAll()
        WifiDirectDiscoveryProvider.stopAll()
        HatNfcBootstrapProvider.stopAll()
        HatDiscoveryRegistry.stopAll()
        WifiDirectManager.cleanup(this)
    }

    private fun syncDiscoveryMode() {
        when (currentMode) {
            Mode.TRANSMITTER -> {
                DiscoveryManager.stopReceiverResponder()
                LanDiscoveryProvider.stopAdvertisement()
                WifiDirectDiscoveryProvider.stopAdvertisement()
                BleDiscoveryManager.stopAdvertising()
                if (!AudioCaptureService.isRunning.get()) {
                    DiscoveryManager.startDiscovery(lifecycleScope)
                    LanDiscoveryProvider.startDiscovery(this)
                    if (checkAndRequestP2pPermissions()) {
                        WifiDirectDiscoveryProvider.startDiscovery(this)
                    }
                } else {
                    DiscoveryManager.stopDiscovery()
                    LanDiscoveryProvider.stopDiscovery()
                    WifiDirectDiscoveryProvider.stopDiscovery()
                }
                if (BleDiscoveryManager.hasPermissions(this)) {
                    BleDiscoveryManager.startScanning(this)
                }
            }
            Mode.RECEIVER -> {
                DiscoveryManager.stopDiscovery()
                LanDiscoveryProvider.stopDiscovery()
                WifiDirectDiscoveryProvider.stopDiscovery()
                BleDiscoveryManager.stopScanning()
                DiscoveryManager.startReceiverResponder(this, lifecycleScope)
                LanDiscoveryProvider.advertiseNode(this, LocalNodeManager.getLocalNode(), AudioConfig.DEFAULT_PORT)
                if (checkAndRequestP2pPermissions()) {
                    WifiDirectDiscoveryProvider.advertiseNode(this, LocalNodeManager.getLocalNode(), AudioConfig.DEFAULT_PORT)
                }
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
                }
            }
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun updateScanningIndicator() {
        val isScanning = DiscoveryManager.isScanning.value ||
            WifiDirectManager.isScanningPeers.value ||
            BleDiscoveryManager.isScanning.value ||
            LanDiscoveryProvider.isScanning.value ||
            WifiDirectDiscoveryProvider.isScanning.value ||
            com.example.audiostreamer.node.discovery.HatBlePresenceProvider.isScanning.value ||
            com.example.audiostreamer.node.discovery.WifiAwareDiscoveryProvider.isSubscribing.value
        pbDiscoveryScanning.visibility = if (isScanning) View.VISIBLE else View.GONE
        tvDiscoveryScanningText.visibility = if (isScanning) View.VISIBLE else View.GONE
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
        BleDiscoveryManager.pruneStaleDevices()
        val localDevices = DiscoveryManager.discoveredDevices.value
        val p2pPeers = WifiDirectManager.discoveredPeers.value.toMutableList()
        val blePeers = BleDiscoveryManager.bleDevices.value.toMutableList()
        val localIps = (NetworkUtils.getAllLocalIpAddresses() + NetworkUtils.getP2pIpAddresses()).toSet()
        val unified = mutableListOf<UnifiedDevice>()

        for (dev in localDevices) {
            // Exclude self/local interfaces from being shown as available receiver
            if (dev.ip in localIps || (dev.p2pGoIp != null && dev.p2pGoIp in localIps)) {
                continue
            }

            // Find matching P2P peer by deviceAddress / p2pMac or by name match
            val matchingPeerIndex = p2pPeers.indexOfFirst { peer ->
                (dev.p2pMac != null && peer.deviceAddress.equals(dev.p2pMac, ignoreCase = true)) ||
                (peer.deviceName.isNotEmpty() && (
                    peer.deviceName.equals(dev.name, ignoreCase = true) ||
                    dev.name.contains(peer.deviceName, ignoreCase = true) ||
                    peer.deviceName.contains(dev.name, ignoreCase = true)
                ))
            }
            val matchingPeer = if (matchingPeerIndex >= 0) p2pPeers.removeAt(matchingPeerIndex) else null

            // Find matching BLE peer by name or bluetooth address
            val matchingBleIndex = blePeers.indexOfFirst { ble ->
                (dev.p2pMac != null && ble.bluetoothAddress.equals(dev.p2pMac, ignoreCase = true)) ||
                (ble.name.isNotEmpty() && (
                    ble.name.equals(dev.name, ignoreCase = true) ||
                    dev.name.contains(ble.name, ignoreCase = true) ||
                    ble.name.contains(dev.name, ignoreCase = true)
                ))
            }
            val matchingBle = if (matchingBleIndex >= 0) blePeers.removeAt(matchingBleIndex) else null

            val p2pSsid = dev.p2pSsid ?: matchingBle?.p2pSsid
            val p2pPassphrase = dev.p2pPassphrase ?: matchingBle?.p2pPassphrase
            val p2pGoIp = dev.p2pGoIp ?: matchingBle?.p2pGoIp ?: if (dev.ip.startsWith("192.168.49.")) dev.ip else null
            val isDirect = dev.isP2pActive || matchingPeer != null || !p2pSsid.isNullOrEmpty()

            val displayName = matchingPeer?.deviceName?.takeIf { it.isNotEmpty() }
                ?: matchingBle?.name?.takeIf { it.isNotEmpty() && it != "Nearby receiver" }
                ?: dev.name.takeIf { it != "Audio Receiver" && it.isNotEmpty() }
                ?: "Audio Receiver"

            unified.add(
                UnifiedDevice(
                    id = dev.nodeId ?: dev.p2pMac ?: matchingBle?.bluetoothAddress ?: "ip_${dev.ip}",
                    displayName = displayName,
                    modelName = dev.modelName ?: if (dev.name != displayName && dev.name != "Audio Receiver") dev.name else null,
                    lanIp = if (dev.ip.startsWith("192.168.49.")) null else dev.ip,
                    port = dev.port,
                    p2pPeer = matchingPeer,
                    p2pSsid = p2pSsid,
                    p2pPassphrase = p2pPassphrase,
                    p2pGoIp = p2pGoIp,
                    isDirectAvailable = isDirect,
                    useDirect = !isDirect && dev.ip.startsWith("192.168.49."),
                    capabilitiesMask = dev.capabilitiesMask,
                    nodeId = dev.nodeId
                )
            )
        }

        // Add remaining verified BLE peers (filtered by HAT service UUID)
        // Any raw, unmatched P2P peers (printers, smart TVs, PCs) are intentionally discarded.
        for (ble in blePeers) {
            if (ble.role == "receiver" && currentMode == Mode.TRANSMITTER) {
                val matchingPeerIndex = p2pPeers.indexOfFirst { peer ->
                    peer.deviceAddress.equals(ble.bluetoothAddress, ignoreCase = true) ||
                    (peer.deviceName.isNotEmpty() && peer.deviceName.equals(ble.name, ignoreCase = true))
                }
                val matchingPeer = if (matchingPeerIndex >= 0) p2pPeers.removeAt(matchingPeerIndex) else null

                unified.add(
                    UnifiedDevice(
                        id = "ble_${ble.bluetoothAddress}",
                        displayName = matchingPeer?.deviceName?.takeIf { it.isNotEmpty() } ?: ble.name,
                        modelName = "Nearby BLE",
                        lanIp = null,
                        port = AudioConfig.DEFAULT_PORT,
                        p2pPeer = matchingPeer,
                        p2pSsid = ble.p2pSsid,
                        p2pPassphrase = ble.p2pPassphrase,
                        p2pGoIp = ble.p2pGoIp,
                        isDirectAvailable = matchingPeer != null || !ble.p2pSsid.isNullOrEmpty(),
                        useDirect = true,
                        capabilitiesMask = 0,
                        nodeId = null
                    )
                )
            }
        }

        // Ingest deduplicated nodes from central HatDiscoveryRegistry
        // (merges LAN NSD, Wi-Fi Direct, Wi-Fi Aware, BLE, and NFC Bootstrap)
        val registryNodes = HatDiscoveryRegistry.recomputeRegistry()
        for (regNode in registryNodes) {
            val lanEp = regNode.getLanEndpoint()
            val wdEp = regNode.getWifiDirectEndpoint()
            val nfcEp = regNode.getNfcEndpoint()

            val ip = lanEp?.address?.takeIf { it !in localIps }
            val mac = wdEp?.address

            val matchingPeerIndex = if (mac != null) {
                p2pPeers.indexOfFirst { it.deviceAddress.equals(mac, ignoreCase = true) }
            } else -1
            val matchingPeer = if (matchingPeerIndex >= 0) p2pPeers.removeAt(matchingPeerIndex) else null

            val isDirect = regNode.hasTransport(NodeTransportType.WIFI_DIRECT) || matchingPeer != null
            val sourcesDesc = regNode.discoverySources.joinToString(", ") { it.name }
            val sourcesList = regNode.discoverySources.map { it.name }
            val candidateTransportsList = regNode.transportCandidates.map { it.name }
            val capsSummary = regNode.nodeInfo.capabilities.describe()

            val existingIndex = unified.indexOfFirst {
                it.nodeId == regNode.id || (ip != null && it.lanIp == ip) || (mac != null && it.id.contains(mac))
            }

            if (existingIndex >= 0) {
                val existing = unified[existingIndex]
                unified[existingIndex] = existing.copy(
                    nodeId = regNode.id,
                    capabilitiesMask = if (existing.capabilitiesMask != 0) existing.capabilitiesMask else regNode.nodeInfo.capabilities.toCapabilitiesMask(),
                    displayName = if (existing.displayName == "Audio Receiver" && regNode.name.isNotEmpty()) regNode.name else existing.displayName,
                    modelName = existing.modelName ?: sourcesDesc,
                    lanIp = existing.lanIp ?: ip,
                    port = if (existing.port != AudioConfig.DEFAULT_PORT) existing.port else (lanEp?.port ?: nfcEp?.port ?: AudioConfig.DEFAULT_PORT),
                    isDirectAvailable = existing.isDirectAvailable || isDirect,
                    p2pPeer = existing.p2pPeer ?: matchingPeer,
                    discoverySources = (existing.discoverySources + sourcesList).distinct(),
                    transportCandidates = (existing.transportCandidates + candidateTransportsList).distinct(),
                    rssi = existing.rssi ?: regNode.rssi,
                    capabilitiesSummary = existing.capabilitiesSummary ?: capsSummary
                )
            } else {
                unified.add(
                    UnifiedDevice(
                        id = regNode.id,
                        displayName = regNode.name.ifEmpty { matchingPeer?.deviceName ?: "HAT Node" },
                        modelName = sourcesDesc,
                        lanIp = ip,
                        port = lanEp?.port ?: nfcEp?.port ?: AudioConfig.DEFAULT_PORT,
                        p2pPeer = matchingPeer,
                        p2pSsid = null,
                        p2pPassphrase = null,
                        p2pGoIp = null,
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
        }

        return unified
    }

    private fun updateConnectedDevicesUi(t: Telemetry) {
        if (currentMode == Mode.TRANSMITTER) {
            val receivers = t.connectedReceivers
            if (receivers.isNotEmpty()) {
                layoutConnectedDevicesSection.visibility = View.VISIBLE
                tvConnectedDevicesTitle.text = "CONNECTED RECEIVERS"
                tvConnectedCountBadge.text = "${receivers.size} active"

                val activeIps = receivers.map { it.ip }.toSet()
                val toRemove = mutableListOf<View>()
                for (i in 0 until layoutConnectedDevicesContainer.childCount) {
                    val child = layoutConnectedDevicesContainer.getChildAt(i)
                    val tagIp = child.tag as? String
                    if (tagIp == null || tagIp !in activeIps) {
                        toRemove.add(child)
                    }
                }
                toRemove.forEach { layoutConnectedDevicesContainer.removeView(it) }

                for (rec in receivers) {
                    var itemView = layoutConnectedDevicesContainer.findViewWithTag<View>(rec.ip)
                    val isNew = (itemView == null)
                    if (isNew) {
                        itemView = layoutInflater.inflate(R.layout.item_connection_device, layoutConnectedDevicesContainer, false)
                        itemView.tag = rec.ip
                    }

                    val tvName = itemView!!.findViewById<TextView>(R.id.tv_device_name)
                    val tvDetails = itemView.findViewById<TextView>(R.id.tv_device_details)
                    val dot = itemView.findViewById<View>(R.id.view_active_dot)
                    val tvTransportBadge = itemView.findViewById<TextView>(R.id.tv_device_transport_badge)
                    val layoutDeviceVolume = itemView.findViewById<LinearLayout>(R.id.layout_device_volume)
                    val btnDeviceMute = itemView.findViewById<ImageView>(R.id.btn_device_mute)
                    val sliderDeviceVol = itemView.findViewById<Slider>(R.id.slider_device_vol)
                    val tvDeviceVolVal = itemView.findViewById<TextView>(R.id.tv_device_vol_val)
                    val btnDisconnect = itemView.findViewById<MaterialButton>(R.id.btn_device_disconnect)
                    val btnConnect = itemView.findViewById<MaterialButton>(R.id.btn_device_connect)

                    val dest = com.example.audiostreamer.node.HatMultiStreamManager.getDestination(rec.nodeId ?: "")
                        ?: com.example.audiostreamer.node.HatMultiStreamManager.getAllDestinations().firstOrNull { it.link.metadata.remoteAddress == rec.ip }

                    if (dest != null) {
                        tvName.text = if (dest.nodeName.isNotEmpty() && dest.nodeName != "Unknown") dest.nodeName else rec.name
                        val dotColor = when (dest.health) {
                            com.example.audiostreamer.node.DestinationHealth.HEALTHY -> ContextCompat.getColor(this, R.color.status_green)
                            com.example.audiostreamer.node.DestinationHealth.DEGRADED -> ContextCompat.getColor(this, R.color.status_orange)
                            com.example.audiostreamer.node.DestinationHealth.UNREACHABLE -> ContextCompat.getColor(this, R.color.status_red)
                            else -> ContextCompat.getColor(this, R.color.status_gray)
                        }
                        dot.backgroundTintList = ColorStateList.valueOf(dotColor)

                        val detailsSb = StringBuilder()
                        detailsSb.append("${rec.ip}:${rec.port} • ${dest.transportType.name} • ${dest.stream.codec.name}")
                        detailsSb.append(" • ").append(dest.health.name)
                        val sentKBytes = dest.stats.bytesSent.get() / 1024
                        detailsSb.append("\nTx: ${dest.stats.packetsSent.get()} pkts (${sentKBytes} KB)")
                        if (dest.stats.rttMs > 0) {
                            detailsSb.append(" • RTT: ${dest.stats.rttMs.toInt()}ms")
                        }
                        detailsSb.append(" • Target: ${dest.bufferStats.targetWatermarkMs.toInt()}ms")
                        if (dest.stats.sendErrors.get() > 0) {
                            detailsSb.append(" • Errors: ${dest.stats.sendErrors.get()}")
                        }
                        tvDetails.text = detailsSb.toString()
                        tvTransportBadge.text = dest.transportType.name
                    } else {
                        tvName.text = rec.name.ifEmpty { "Audio Receiver" }
                        val latencyStr = if (rec.latencyMs > 0) " • ${rec.latencyMs}ms" else ""
                        tvDetails.text = "${rec.ip}:${rec.port} • ${rec.transportType}$latencyStr"
                        dot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_green))
                        tvTransportBadge.text = rec.transportType
                    }

                    tvTransportBadge.visibility = View.VISIBLE
                    dot.visibility = View.VISIBLE
                    btnConnect.visibility = View.GONE
                    btnDisconnect.visibility = View.VISIBLE

                    // Multi-receiver Volume Control Row
                    layoutDeviceVolume.visibility = View.VISIBLE
                    val vol = rec.volumePercent.coerceIn(0, 100)
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
                        sliderDeviceVol.clearOnChangeListeners()
                        sliderDeviceVol.addOnChangeListener { _, value, fromUser ->
                            if (fromUser) {
                                val newVol = value.toInt()
                                tvDeviceVolVal.text = "$newVol%"
                                val volIntent = Intent(this, AudioCaptureService::class.java).apply {
                                    action = AudioCaptureService.ACTION_SET_RECEIVER_VOLUME
                                    putExtra(AudioCaptureService.EXTRA_RECEIVER_IP, rec.ip)
                                    putExtra(AudioCaptureService.EXTRA_RECEIVER_NODE_ID, rec.nodeId)
                                    putExtra(AudioCaptureService.EXTRA_VOLUME_PERCENT, newVol)
                                }
                                startService(volIntent)
                            }
                        }

                        btnDeviceMute.setOnClickListener {
                            val newVol = if (rec.isMuted || vol == 0) 100 else 0
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
                            }
                            startService(removeIntent)
                            Toast.makeText(this, "Disconnected ${rec.name.ifEmpty { rec.ip }}", Toast.LENGTH_SHORT).show()
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

                val itemView = layoutInflater.inflate(R.layout.item_connection_device, layoutConnectedDevicesContainer, false)
                val tvName = itemView.findViewById<TextView>(R.id.tv_device_name)
                val tvDetails = itemView.findViewById<TextView>(R.id.tv_device_details)
                val dot = itemView.findViewById<View>(R.id.view_active_dot)
                val tvTransportBadge = itemView.findViewById<TextView>(R.id.tv_device_transport_badge)
                val layoutDeviceVolume = itemView.findViewById<LinearLayout>(R.id.layout_device_volume)
                val btnDisconnect = itemView.findViewById<MaterialButton>(R.id.btn_device_disconnect)
                val btnConnect = itemView.findViewById<MaterialButton>(R.id.btn_device_connect)

                tvName.text = transmitter.name.ifEmpty { "Audio Transmitter" }

                val activeLink = t.activeLinks.firstOrNull()
                val activeStream = t.activeStreams.firstOrNull()
                val transportStr = activeLink?.hatTransportType?.name ?: transmitter.transportType
                val streamStr = activeStream?.let { " • ${it.codec.name} (Gen ${it.generation})" } ?: ""
                val negStr = t.lastNegotiatedCapabilities?.let { "\nNegotiated: ${it.summary()}" } ?: ""

                tvDetails.text = "${transmitter.ip}:${transmitter.port} • $transportStr$streamStr • Playing$negStr"
                tvTransportBadge.visibility = View.VISIBLE
                tvTransportBadge.text = transportStr
                layoutDeviceVolume.visibility = View.GONE

                dot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_green))
                dot.visibility = View.VISIBLE
                btnConnect.visibility = View.GONE
                btnDisconnect.visibility = View.VISIBLE

                btnDisconnect.setOnClickListener {
                    stopReceiverService()
                    Toast.makeText(this, "Stopped receiver playback", Toast.LENGTH_SHORT).show()
                }

                layoutConnectedDevicesContainer.addView(itemView)
            } else {
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

        // Filter out currently connected devices
        val connectedIps = if (currentMode == Mode.TRANSMITTER) {
            StreamState.telemetry.value.connectedReceivers.map { it.ip }.toSet()
        } else {
            setOfNotNull(StreamState.telemetry.value.connectedTransmitter?.ip)
        }

        val availableDevices = devices.filter { dev ->
            (dev.lanIp == null || dev.lanIp !in connectedIps) &&
            (dev.p2pGoIp == null || dev.p2pGoIp !in connectedIps)
        }

        for (dev in availableDevices) {
            val itemView = layoutInflater.inflate(R.layout.item_connection_device, layoutDiscoveredDevicesContainer, false)
            val card = itemView.findViewById<MaterialCardView>(R.id.card_connection_device)
            val tvName = itemView.findViewById<TextView>(R.id.tv_device_name)
            val tvDetails = itemView.findViewById<TextView>(R.id.tv_device_details)
            val dot = itemView.findViewById<View>(R.id.view_active_dot)
            val btnDisconnect = itemView.findViewById<MaterialButton>(R.id.btn_device_disconnect)
            val btnConnect = itemView.findViewById<MaterialButton>(R.id.btn_device_connect)

            tvName.text = if (!dev.modelName.isNullOrEmpty() && dev.modelName != dev.displayName && !dev.discoverySources.contains(dev.modelName)) {
                "${dev.displayName} (${dev.modelName})"
            } else {
                dev.displayName
            }

            val detailsSb = StringBuilder()
            val transportLabel = when {
                dev.useDirect || dev.lanIp == null -> "Wi-Fi Direct"
                else -> "Local Wi-Fi (${dev.lanIp}:${dev.port})"
            }
            detailsSb.append(transportLabel)

            if (dev.discoverySources.isNotEmpty()) {
                detailsSb.append(" • Via: ").append(dev.discoverySources.joinToString(", "))
            }
            if (dev.rssi != null) {
                detailsSb.append(" (").append(dev.rssi).append(" dBm)")
            }
            if (!dev.capabilitiesSummary.isNullOrEmpty()) {
                detailsSb.append("\n").append(dev.capabilitiesSummary)
            }
            tvDetails.text = detailsSb.toString()
            dot.visibility = View.GONE
            btnDisconnect.visibility = View.GONE

            val isAlreadyConnected = dev.nodeId != null && !HatDiscoveryRegistry.canConnect(dev.nodeId)
            btnConnect.visibility = View.VISIBLE
            if (isAlreadyConnected) {
                btnConnect.text = "Connected"
                btnConnect.isEnabled = false
                btnConnect.alpha = 0.6f
            } else {
                btnConnect.text = "Connect"
                btnConnect.isEnabled = true
                btnConnect.alpha = 1.0f
            }

            fun performConnect() {
                if (dev.nodeId != null && !HatDiscoveryRegistry.canConnect(dev.nodeId)) {
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
                val isP2p = dev.useDirect || dev.lanIp == null
                val targetIp = if (isP2p) (dev.p2pGoIp ?: "192.168.49.1") else dev.lanIp ?: "192.168.1.1"
                val profile = ConnectionProfile(
                    id = if (isP2p) "p2p_${dev.id}" else "wifi_${dev.id}",
                    name = dev.displayName,
                    targetIp = targetIp,
                    port = dev.port,
                    connectionType = if (isP2p) ConnectionType.WIFI_DIRECT else ConnectionType.LOCAL_WIFI,
                    preferredStreamingProfile = AudioConfig.PROFILE_MUSIC,
                    capabilitiesMask = dev.capabilitiesMask,
                    p2pSsid = dev.p2pSsid,
                    p2pPassphrase = dev.p2pPassphrase,
                    nodeId = dev.nodeId
                )
                ConnectionProfileManager.saveProfile(this@MainActivity, profile)
                Toast.makeText(this@MainActivity, "Saved profile: ${profile.name}", Toast.LENGTH_SHORT).show()
            }

            btnConnect.setOnClickListener { performConnect() }
            card.setOnClickListener { performConnect() }
            card.setOnLongClickListener {
                performSaveProfile()
                true
            }

            layoutDiscoveredDevicesContainer.addView(itemView)
        }

        if (availableDevices.isEmpty()) {
            tvAvailableEmpty.visibility = View.VISIBLE
            layoutDiscoveredDevicesContainer.visibility = View.GONE
        } else {
            tvAvailableEmpty.visibility = View.GONE
            layoutDiscoveredDevicesContainer.visibility = View.VISIBLE
        }

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

        val prefs = getSharedPreferences("stream_prefs", Context.MODE_PRIVATE)
        val savedProfile = prefs.getString(AudioConfig.PREF_KEY_PROFILE, AudioConfig.PROFILE_AUTO) ?: AudioConfig.PROFILE_AUTO
        val activeProfileName = if (isSinkRunning || isSenderRunning) {
            t.streamProfileName
        } else {
            when (savedProfile) {
                AudioConfig.PROFILE_VIDEO, AudioConfig.PROFILE_LOW_LATENCY -> "Low Latency (Opus/AAC)"
                AudioConfig.PROFILE_AUTO -> "Auto Adaptive (35-400ms)"
                else -> "Uncapped Music Mode"
            }
        }
        tvPipelineProfile.text = activeProfileName
        if (isSinkRunning || isSenderRunning) {
            tvPipelineFormat.text = "${t.negotiatedFormatDesc} • ${t.bitrateKbps} kbps"
        } else {
            val bitStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "24-bit" else "16-bit"
            val defaultBitrate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 2304 else 1536
            tvPipelineFormat.text = "48.0 kHz • $bitStr Stereo PCM • $defaultBitrate kbps"
        }

        val localNode = com.example.audiostreamer.node.LocalNodeManager.getLocalNode()
        if (isSenderRunning || isSinkRunning) {
            val linkCount = t.activeLinks.size
            val streamCount = t.activeStreams.size
            val fanOutCount = com.example.audiostreamer.node.HatMultiStreamManager.countActiveDestinations()
            val fanOutStr = if (isSenderRunning && fanOutCount > 0) " • Fan-Out: $fanOutCount" else ""
            val negStr = t.lastNegotiatedCapabilities?.let { " • ${it.summary()}" } ?: ""
            tvPipelineNodeInfo.text = "Node: ${localNode.name} • Links: $linkCount • Streams: $streamCount$fanOutStr$negStr"
        } else {
            tvPipelineNodeInfo.text = "Node: ${localNode.name} (${localNode.id}) • Ready"
        }

        if (!isSenderRunning && !isSinkRunning) {
            tvBadgeStatus.text = "IDLE"
            tvBadgeStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_gray))
            tvEndpointInfo.text = "Not connected"
            tvPacketsStat.text = "Packets: 0 (0 pkts/s • 0 KB/s)"
            pbAudioLevel.progress = 0
            tvAudioLevelVal.text = "0%"
            pbBufferHealth.progress = 0
            tvBufferHealthVal.text = "0% (0/${AudioConfig.getJitterBufferSlots(savedProfile)} slots)"

            tvDiagnosticTip.text = if (currentMode == Mode.RECEIVER) {
                "On receiver: Tap 'Start Listening'. Then enter ${detectedLocalIp ?: "this IP"} on your transmitter phone."
            } else {
                "On transmitter: Enter the receiver's IP (displayed on receiver screen) and tap 'Start Streaming'."
            }
            return
        }

        if (isSinkRunning) {
            pbBufferHealth.progress = t.bufferFillPercent
            val bufferMs = t.bufferSlotsUsed * AudioConfig.FRAME_SIZE_MS
            tvBufferHealthVal.text = "${t.bufferFillPercent}% (${t.bufferSlotsUsed}/${t.bufferSlotsTotal} slots • ~${bufferMs}ms)"
        } else {
            pbBufferHealth.progress = if (t.audioPeakPercent > 0) 100 else 0
            tvBufferHealthVal.text = if (t.audioPeakPercent > 0) "Capture active (500ms buffer)" else "Idle"
        }

        if (isSenderRunning) {
            val activeDests = com.example.audiostreamer.node.HatMultiStreamManager.countActiveDestinations()
            if (t.isSilenceSuppressed) {
                tvBadgeStatus.text = "STANDBY"
                tvBadgeStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_gray))
                tvDiagnosticTip.text = "Silence suppression active (Battery saver: 2 pkts/s). Instant wake-up (<5ms) when audio resumes."
            } else {
                if (activeDests > 1) {
                    tvBadgeStatus.text = "FAN-OUT ($activeDests)"
                    tvBadgeStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
                    tvDiagnosticTip.text = "Fan-out active to $activeDests receivers. Synchronous playback with per-destination telemetry."
                } else if (t.activeReceiversCount > 1) {
                    tvBadgeStatus.text = "MULTI-CAST"
                    tvBadgeStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
                    tvDiagnosticTip.text = "Multi-unicast active to ${t.activeReceiversCount} receivers. Synchronous silent disco listening."
                } else {
                    tvBadgeStatus.text = "TRANSMITTING"
                    tvBadgeStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
                    tvDiagnosticTip.text = if (t.audioPeakPercent > 1) {
                        "Audio signal detected. Sending live system audio to target."
                    } else {
                        "Capturing system audio, but signal is currently silent. Start playing media on this device."
                    }
                }
            }
            tvEndpointInfo.text = if (activeDests > 1) {
                "Fan-Out: $activeDests destinations (${t.streamProfileName})"
            } else if (t.activeReceiversCount > 1) {
                "Targets: Multi-Unicast (${t.activeReceiversCount} devices)"
            } else {
                "Target: ${t.remoteEndpoint ?: "Configuring..."}"
            }
            tvPacketsStat.text = "Sent: ${t.packetsTotal} pkts (${t.packetsPerSec} pkts/s • ${t.bitrateKbps} kbps)"
            pbAudioLevel.progress = t.audioPeakPercent
            tvAudioLevelVal.text = "${t.audioPeakPercent}%"
        } else if (isSinkRunning) {
            val hasReceivedPackets = t.packetsTotal > 0
            if (hasReceivedPackets) {
                if (t.isSilenceSuppressed) {
                    tvBadgeStatus.text = "STANDBY"
                    tvBadgeStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_gray))
                    tvDiagnosticTip.text = "Transmitter in silence standby mode. Ready to play instantly."
                } else {
                    tvBadgeStatus.text = "PLAYING"
                    tvBadgeStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_green))
                    tvDiagnosticTip.text = if (t.audioPeakPercent > 1) {
                        "Audio playing through AudioTrack. Adjust device volume if needed."
                    } else {
                        "Packets arriving, but audio data is silent. Ensure transmitter phone is playing media."
                    }
                }
                tvEndpointInfo.text = "From: ${t.remoteEndpoint ?: "Unknown"}"
                val fecText = if (t.fecRecoveredTotal > 0) " • FEC: ${t.fecRecoveredTotal} recovered" else ""
                tvPacketsStat.text = "Received: ${t.packetsTotal} pkts (${t.packetsPerSec} pkts/s • ${t.bitrateKbps} kbps)$fecText"
                pbAudioLevel.progress = t.audioPeakPercent
                tvAudioLevelVal.text = "${t.audioPeakPercent}%"
            } else {
                tvBadgeStatus.text = "WAITING"
                tvBadgeStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_orange))
                tvEndpointInfo.text = "Listening on ${detectedLocalIp ?: "0.0.0.0"}:${etPort.text}"
                tvPacketsStat.text = "Packets: 0 (Waiting for transmitter...)"
                pbAudioLevel.progress = 0
                tvAudioLevelVal.text = "0%"

                tvDiagnosticTip.text = "Waiting for audio packets...\n" +
                        "1. Ensure both devices are on the same Wi-Fi.\n" +
                        "2. On your phone, set Target IP to: ${detectedLocalIp ?: "this device IP"}\n" +
                        "3. Port: ${etPort.text}"
            }
        }
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

                tvModeGuide.text = "Broadcast audio to nearby speakers, receivers, or devices"
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
                    }
                    else -> {
                        btnAction.isEnabled = true
                        btnAction.text = getString(R.string.start_stream)
                        btnAction.setIconResource(R.drawable.ic_play)
                        btnAction.backgroundTintList = ColorStateList.valueOf(colorPrimary)
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

                tvModeGuide.text = "Accept and play audio streams from nearby transmitters"
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
