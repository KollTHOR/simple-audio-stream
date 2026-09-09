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
import android.widget.RadioButton
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private enum class Mode {
        TRANSMITTER,
        RECEIVER
    }

    private lateinit var layoutIpPill: LinearLayout
    private lateinit var tvHeaderIp: TextView
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

    private lateinit var layoutDiscoverySection: LinearLayout
    private lateinit var tvDiscoveryTitle: TextView
    private lateinit var pbDiscoveryScanning: ProgressBar
    private lateinit var tvDiscoveryScanningText: TextView
    private lateinit var btnScanReceivers: MaterialButton
    private lateinit var layoutDiscoveredDevicesContainer: LinearLayout

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
            ContextCompat.startForegroundService(this, serviceIntent)
            updateModeAndButtonUi()
        } else {
            isStarting = false
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            updateModeAndButtonUi()
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
                    AppLogger.i("MainActivity", "Autonomous Wi-Fi Direct Active: SSID=$ssid, Passphrase=$pass, IP=$goIp")
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

        ConnectionProfileManager.init(this)
        WifiDirectManager.init(this)

        layoutIpPill = findViewById(R.id.layout_ip_pill)
        tvHeaderIp = findViewById(R.id.tv_header_ip)
        toggleModeGroup = findViewById(R.id.toggle_mode_group)
        btnModeTransmitter = findViewById(R.id.btn_mode_transmitter)
        btnModeReceiver = findViewById(R.id.btn_mode_receiver)
        tvModeGuide = findViewById(R.id.tv_mode_guide)

        layoutSavedProfilesSection = findViewById(R.id.layout_saved_profiles_section)
        tvSavedProfilesTitle = findViewById(R.id.tv_saved_profiles_title)
        btnAddProfile = findViewById(R.id.btn_add_profile)
        layoutSavedProfilesContainer = findViewById(R.id.layout_saved_profiles_container)
        layoutSavedEmpty = findViewById(R.id.layout_saved_empty)

        layoutDiscoverySection = findViewById(R.id.layout_discovery_section)
        tvDiscoveryTitle = findViewById(R.id.tv_discovery_title)
        pbDiscoveryScanning = findViewById(R.id.pb_discovery_scanning)
        tvDiscoveryScanningText = findViewById(R.id.tv_discovery_scanning_text)
        btnScanReceivers = findViewById(R.id.btn_scan_receivers)
        layoutDiscoveredDevicesContainer = findViewById(R.id.layout_discovered_devices_container)

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
                "Advanced Settings (Manual IP & Port) -"
            } else {
                "Advanced Settings (Manual IP & Port) +"
            }
        }

        btnAddProfile.setOnClickListener {
            showEditProfileDialog(null)
        }

        switchReceiverP2p.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startReceiverP2pGroup()
            } else {
                WifiDirectManager.removeGroup(this)
                layoutReceiverP2pInfo.visibility = View.GONE
                tvReceiverP2pStatus.text = "Autonomous Wi-Fi Direct stopped"
            }
        }

        btnScanReceivers.setOnClickListener {
            DiscoveryManager.triggerScan(lifecycleScope)
            if (checkAndRequestP2pPermissions()) {
                WifiDirectManager.discoverPeers(this)
            }
            Toast.makeText(this, "Scanning for nearby receivers...", Toast.LENGTH_SHORT).show()
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
                    DiscoveryManager.isScanning.collect {
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
        tvBufferHealthVal = findViewById(R.id.tv_buffer_health_val)
        pbBufferHealth = findViewById(R.id.pb_buffer_health)

        refreshLocalIp()

        layoutIpPill.setOnClickListener {
            refreshLocalIp()
            val allIps = NetworkUtils.getAllLocalIpAddresses()
            if (allIps.size > 1) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Active Network Addresses")
                    .setItems(allIps.toTypedArray()) { _, which ->
                        val selectedIp = allIps[which]
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("IP Address", selectedIp))
                        Toast.makeText(this, "IP copied: $selectedIp", Toast.LENGTH_SHORT).show()
                    }
                    .setPositiveButton("Close", null)
                    .show()
            } else {
                detectedLocalIp?.let { ip ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("IP Address", ip))
                    Toast.makeText(this, "IP copied: $ip", Toast.LENGTH_SHORT).show()
                }
            }
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
    }

    override fun onResume() {
        super.onResume()
        refreshLocalIp()

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

    override fun onDestroy() {
        super.onDestroy()
        DiscoveryManager.stopDiscovery()
        DiscoveryManager.stopReceiverResponder()
        WifiDirectManager.cleanup(this)
    }

    private fun syncDiscoveryMode() {
        when (currentMode) {
            Mode.TRANSMITTER -> {
                DiscoveryManager.stopReceiverResponder()
                if (!AudioCaptureService.isRunning.get()) {
                    DiscoveryManager.startDiscovery(lifecycleScope)
                } else {
                    DiscoveryManager.stopDiscovery()
                }
            }
            Mode.RECEIVER -> {
                DiscoveryManager.stopDiscovery()
                DiscoveryManager.startReceiverResponder(this, lifecycleScope)
            }
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun updateScanningIndicator() {
        val isScanning = DiscoveryManager.isScanning.value || WifiDirectManager.isScanningPeers.value
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
        val capabilitiesMask: Int = 0
    )

    private val deviceDirectPreferences = mutableMapOf<String, Boolean>()

    private fun getUnifiedDiscoveredDevices(): List<UnifiedDevice> {
        val localDevices = DiscoveryManager.discoveredDevices.value
        val p2pPeers = WifiDirectManager.discoveredPeers.value.toMutableList()
        val unified = mutableListOf<UnifiedDevice>()

        for (dev in localDevices) {
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
            val isDirect = dev.isP2pActive || matchingPeer != null || !dev.p2pSsid.isNullOrEmpty()

            val displayName = matchingPeer?.deviceName?.takeIf { it.isNotEmpty() }
                ?: dev.name.takeIf { it != "Audio Receiver" && it.isNotEmpty() }
                ?: "Audio Receiver"

            unified.add(
                UnifiedDevice(
                    id = dev.p2pMac ?: "ip_${dev.ip}",
                    displayName = displayName,
                    modelName = dev.modelName ?: if (dev.name != displayName && dev.name != "Audio Receiver") dev.name else null,
                    lanIp = if (dev.ip.startsWith("192.168.49.")) null else dev.ip,
                    port = dev.port,
                    p2pPeer = matchingPeer,
                    p2pSsid = dev.p2pSsid,
                    p2pPassphrase = dev.p2pPassphrase,
                    p2pGoIp = dev.p2pGoIp ?: if (dev.ip.startsWith("192.168.49.")) dev.ip else null,
                    isDirectAvailable = isDirect,
                    useDirect = !isDirect && dev.ip.startsWith("192.168.49."),
                    capabilitiesMask = dev.capabilitiesMask
                )
            )
        }

        // Add any remaining P2P peers that were not matched with a UDP broadcast
        for (peer in p2pPeers) {
            val peerName = peer.deviceName.ifEmpty { "Wi-Fi Direct Receiver" }
            unified.add(
                UnifiedDevice(
                    id = "p2p_${peer.deviceAddress}",
                    displayName = peerName,
                    modelName = null,
                    lanIp = null,
                    port = AudioConfig.DEFAULT_PORT,
                    p2pPeer = peer,
                    p2pSsid = null,
                    p2pPassphrase = null,
                    p2pGoIp = null,
                    isDirectAvailable = true,
                    useDirect = true,
                    capabilitiesMask = 0
                )
            )
        }

        return unified
    }

    private fun updateDiscoveredDevicesUi() {
        layoutDiscoveredDevicesContainer.removeAllViews()
        val devices = getUnifiedDiscoveredDevices()

        for (dev in devices) {
            val itemView = layoutInflater.inflate(R.layout.item_discovered_device, layoutDiscoveredDevicesContainer, false)
            val card = itemView.findViewById<MaterialCardView>(R.id.card_discovered_device)
            val tvName = itemView.findViewById<TextView>(R.id.tv_discovered_name)
            val tvDetails = itemView.findViewById<TextView>(R.id.tv_discovered_details)
            val switchDirect = itemView.findViewById<SwitchMaterial>(R.id.switch_discovered_direct)
            val btnSave = itemView.findViewById<MaterialButton>(R.id.btn_discovered_save)
            val btnUse = itemView.findViewById<MaterialButton>(R.id.btn_discovered_use)

            tvName.text = if (!dev.modelName.isNullOrEmpty() && dev.modelName != dev.displayName) {
                "${dev.displayName} (${dev.modelName})"
            } else {
                dev.displayName
            }

            // Restore user's Direct switch preference for this device
            val directPref = deviceDirectPreferences[dev.id] ?: (dev.lanIp == null && dev.isDirectAvailable)
            dev.useDirect = directPref

            switchDirect.visibility = if (dev.isDirectAvailable) View.VISIBLE else View.GONE
            switchDirect.isEnabled = dev.lanIp != null // If no LAN IP, Direct is mandatory
            switchDirect.isChecked = dev.useDirect

            fun updateDetailsText() {
                val modeDesc = if (switchDirect.isChecked) {
                    "Wi-Fi Direct (High Priority Link)"
                } else {
                    "Local Wi-Fi (${dev.lanIp ?: "No LAN IP"})"
                }
                tvDetails.text = modeDesc
            }
            updateDetailsText()

            switchDirect.setOnCheckedChangeListener { _, isChecked ->
                dev.useDirect = isChecked
                deviceDirectPreferences[dev.id] = isChecked
                updateDetailsText()
            }

            fun performUseDevice() {
                if (switchDirect.isChecked) {
                    connectToUnifiedDeviceDirect(dev)
                } else {
                    if (dev.lanIp != null) {
                        etTargetIp.setText(dev.lanIp)
                        etPort.setText(dev.port.toString())
                        currentConnType = ConnectionType.LOCAL_WIFI
                        ConnectionProfileManager.setActiveProfile(this@MainActivity, null)
                        updateSavedProfilesUi()
                        Toast.makeText(this@MainActivity, "Target set to ${dev.displayName} (${dev.lanIp})", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "No LAN IP found, connecting via Wi-Fi Direct...", Toast.LENGTH_SHORT).show()
                        connectToUnifiedDeviceDirect(dev)
                    }
                }
            }

            fun performSaveProfile() {
                val isP2p = switchDirect.isChecked || dev.lanIp == null
                val targetIp = if (isP2p) (dev.p2pGoIp ?: "192.168.49.1") else dev.lanIp!!
                val profile = ConnectionProfile(
                    id = if (isP2p) "p2p_${dev.id}" else "wifi_${dev.id}",
                    name = dev.displayName,
                    targetIp = targetIp,
                    port = dev.port,
                    connectionType = if (isP2p) ConnectionType.WIFI_DIRECT else ConnectionType.LOCAL_WIFI,
                    preferredStreamingProfile = AudioConfig.PROFILE_MUSIC,
                    capabilitiesMask = dev.capabilitiesMask,
                    p2pSsid = dev.p2pSsid,
                    p2pPassphrase = dev.p2pPassphrase
                )
                ConnectionProfileManager.saveProfile(this@MainActivity, profile)
                applyProfile(profile)
                Toast.makeText(this@MainActivity, "Profile saved and selected: ${profile.name}", Toast.LENGTH_SHORT).show()
            }

            btnUse.setOnClickListener { performUseDevice() }
            btnSave.setOnClickListener { performSaveProfile() }
            card.setOnClickListener { performUseDevice() }

            layoutDiscoveredDevicesContainer.addView(itemView)
        }

        val hasDevices = devices.isNotEmpty()
        layoutDiscoverySection.visibility = if (currentMode == Mode.TRANSMITTER && hasDevices) View.VISIBLE else View.GONE
    }

    private fun connectToUnifiedDeviceDirect(dev: UnifiedDevice) {
        if (!checkAndRequestP2pPermissions()) return
        currentConnType = ConnectionType.WIFI_DIRECT

        if (!dev.p2pSsid.isNullOrEmpty()) {
            val pass = dev.p2pPassphrase ?: WifiDirectManager.P2P_DEFAULT_PASSPHRASE
            AppLogger.i("MainActivity", "Direct-linking to '${dev.displayName}' SSID=${dev.p2pSsid}...")
            Toast.makeText(this, "Direct-linking to ${dev.displayName}...", Toast.LENGTH_SHORT).show()
            WifiDirectManager.connectWithCredentials(this, dev.p2pSsid, pass) { goIp ->
                runOnUiThread {
                    etTargetIp.setText(goIp)
                    etPort.setText(dev.port.toString())
                    Toast.makeText(this@MainActivity, "Direct connected to ${dev.displayName} ($goIp)", Toast.LENGTH_SHORT).show()
                }
            }
        } else if (dev.p2pPeer != null) {
            AppLogger.i("MainActivity", "Connecting to P2P peer '${dev.displayName}'...")
            Toast.makeText(this, "Connecting to ${dev.displayName}...", Toast.LENGTH_SHORT).show()
            WifiDirectManager.connectToPeer(this, dev.p2pPeer) { goIp ->
                runOnUiThread {
                    etTargetIp.setText(goIp)
                    etPort.setText(dev.port.toString())
                    Toast.makeText(this@MainActivity, "Direct connected to ${dev.displayName} ($goIp)", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(this, "No Wi-Fi Direct credentials found for ${dev.displayName}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onModeSwitched(oldMode: Mode, newMode: Mode) {
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
        val allIps = NetworkUtils.getAllLocalIpAddresses()
        if (allIps.isNotEmpty()) {
            detectedLocalIp = allIps.first()
            val extra = allIps.size - 1
            tvHeaderIp.text = if (extra > 0) "${allIps.first()} (+$extra)" else allIps.first()
            if (etTargetIp.text.isNullOrEmpty() ||
                etTargetIp.text.toString() == "192.168.1.255" ||
                etTargetIp.text.toString() == "192.168.43.255") {
                val activeProf = ConnectionProfileManager.activeProfileFlow.value
                if (activeProf != null) {
                    etTargetIp.setText(activeProf.targetIp)
                } else {
                    etTargetIp.setText(NetworkUtils.getSuggestedBroadcastIp())
                }
            }
        } else {
            detectedLocalIp = null
            tvHeaderIp.text = "Offline"
        }
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

        if (currentMode == Mode.TRANSMITTER) {
            val curVol = t.remoteVolumePercent
            if (!sliderRemoteVol.isPressed && sliderRemoteVol.value.toInt() != curVol) {
                sliderRemoteVol.value = curVol.toFloat()
                tvRemoteVolLabel.text = "$curVol%"
            }
        }

        val prefs = getSharedPreferences("stream_prefs", Context.MODE_PRIVATE)
        val savedProfile = prefs.getString(AudioConfig.PREF_KEY_PROFILE, AudioConfig.PROFILE_MUSIC) ?: AudioConfig.PROFILE_MUSIC
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
        val codecDesc = if (activeProfileName.contains("Opus")) "Opus VBR" else if (activeProfileName.contains("AAC")) "AAC" else "${t.bitDepth}-bit Stereo PCM"
        tvPipelineFormat.text = "${t.sampleRate / 1000.0} kHz • $codecDesc • ${t.bitrateKbps} kbps"

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
            if (t.isSilenceSuppressed) {
                tvBadgeStatus.text = "STANDBY"
                tvBadgeStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_gray))
                tvDiagnosticTip.text = "Silence suppression active (Battery saver: 2 pkts/s). Instant wake-up (<5ms) when audio resumes."
            } else {
                tvBadgeStatus.text = if (t.activeReceiversCount > 1) "MULTI-CAST" else "TRANSMITTING"
                tvBadgeStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
                tvDiagnosticTip.text = if (t.activeReceiversCount > 1) {
                    "Multi-unicast active to ${t.activeReceiversCount} receivers. Synchronous silent disco listening."
                } else if (t.audioPeakPercent > 1) {
                    "Audio signal detected. Sending live system audio to target."
                } else {
                    "Capturing system audio, but signal is currently silent. Start playing media on this device."
                }
            }
            tvEndpointInfo.text = if (t.activeReceiversCount > 1) {
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

                tvModeGuide.text = "Capture & stream system audio to a receiver device"
                layoutSavedProfilesSection.visibility = View.VISIBLE
                val hasDiscovered = DiscoveryManager.discoveredDevices.value.isNotEmpty() || WifiDirectManager.discoveredPeers.value.isNotEmpty()
                layoutDiscoverySection.visibility = if (hasDiscovered) View.VISIBLE else View.GONE
                layoutReceiverP2p.visibility = View.GONE
                layoutVolumeControl.visibility = View.VISIBLE
                layoutAdvancedHeader.visibility = View.VISIBLE
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

                tvModeGuide.text = "Play raw audio stream received from transmitter"
                layoutDiscoverySection.visibility = View.GONE
                layoutSavedProfilesSection.visibility = View.GONE
                layoutReceiverP2p.visibility = View.VISIBLE
                layoutVolumeControl.visibility = View.GONE
                layoutAdvancedHeader.visibility = View.VISIBLE
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
    }

    private fun sendVolumeIntent(volumePercent: Int) {
        val clamped = volumePercent.coerceIn(0, 100)
        sliderRemoteVol.value = clamped.toFloat()
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
