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
    private lateinit var layoutConnType: LinearLayout
    private lateinit var toggleConnType: MaterialButtonToggleGroup
    private lateinit var btnConnWifi: MaterialButton
    private lateinit var btnConnP2p: MaterialButton

    private lateinit var layoutActiveDevice: LinearLayout
    private lateinit var tvSelectedDeviceName: TextView
    private lateinit var tvSelectedDeviceType: TextView
    private lateinit var tvSelectedDeviceCaps: TextView
    private lateinit var btnSaveProfile: MaterialButton

    private lateinit var layoutDiscovery: LinearLayout
    private lateinit var btnScanReceivers: MaterialButton
    private lateinit var chipGroupReceivers: ChipGroup

    private lateinit var layoutReceiverP2p: LinearLayout
    private lateinit var switchReceiverP2p: SwitchMaterial
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
            if (currentMode == Mode.TRANSMITTER) {
                toggleConnType.check(R.id.btn_conn_wifi)
            }
        }
    }

    private fun checkAndRequestP2pPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        return if (permissions.isNotEmpty()) {
            p2pPermissionLauncher.launch(permissions.toTypedArray())
            false
        } else {
            true
        }
    }

    private fun startReceiverP2pGroup() {
        if (!checkAndRequestP2pPermissions()) return
        tvReceiverP2pStatus.text = "Initializing Autonomous Wi-Fi Direct Group..."
        WifiDirectManager.createAutonomousGroup(this) { success, ssid, goIp ->
            runOnUiThread {
                if (success) {
                    tvReceiverP2pStatus.text = "Group Active: $ssid\nDirect IP: $goIp (Port 50005)"
                    Toast.makeText(this, "Wi-Fi Direct Active: $ssid", Toast.LENGTH_SHORT).show()
                } else {
                    switchReceiverP2p.isChecked = false
                    tvReceiverP2pStatus.text = "Failed starting Wi-Fi Direct group"
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

        layoutConnType = findViewById(R.id.layout_conn_type)
        toggleConnType = findViewById(R.id.toggle_conn_type)
        btnConnWifi = findViewById(R.id.btn_conn_wifi)
        btnConnP2p = findViewById(R.id.btn_conn_p2p)

        layoutActiveDevice = findViewById(R.id.layout_active_device)
        tvSelectedDeviceName = findViewById(R.id.tv_selected_device_name)
        tvSelectedDeviceType = findViewById(R.id.tv_selected_device_type)
        tvSelectedDeviceCaps = findViewById(R.id.tv_selected_device_caps)
        btnSaveProfile = findViewById(R.id.btn_save_profile)

        layoutDiscovery = findViewById(R.id.layout_discovery)
        btnScanReceivers = findViewById(R.id.btn_scan_receivers)
        chipGroupReceivers = findViewById(R.id.chip_group_receivers)
        chipGroupReceivers.isSingleSelection = true

        layoutReceiverP2p = findViewById(R.id.layout_receiver_p2p)
        switchReceiverP2p = findViewById(R.id.switch_receiver_p2p)
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

        btnSaveProfile.setOnClickListener {
            showSaveProfileDialog()
        }

        toggleConnType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentConnType = if (checkedId == R.id.btn_conn_p2p) {
                    ConnectionType.WIFI_DIRECT
                } else {
                    ConnectionType.LOCAL_WIFI
                }
                if (currentConnType == ConnectionType.WIFI_DIRECT) {
                    if (checkAndRequestP2pPermissions()) {
                        WifiDirectManager.discoverPeers(this)
                        Toast.makeText(this, "Scanning for Wi-Fi Direct receivers...", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    DiscoveryManager.triggerScan(lifecycleScope)
                }
                updateAllDeviceChips()
            }
        }

        switchReceiverP2p.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startReceiverP2pGroup()
            } else {
                WifiDirectManager.removeGroup(this)
                tvReceiverP2pStatus.text = "Autonomous Wi-Fi Direct stopped"
            }
        }

        btnScanReceivers.setOnClickListener {
            if (currentConnType == ConnectionType.WIFI_DIRECT) {
                if (checkAndRequestP2pPermissions()) {
                    WifiDirectManager.discoverPeers(this)
                    Toast.makeText(this, "Scanning for Wi-Fi Direct receivers...", Toast.LENGTH_SHORT).show()
                }
            } else {
                DiscoveryManager.triggerScan(lifecycleScope)
                Toast.makeText(this, "Scanning local Wi-Fi devices...", Toast.LENGTH_SHORT).show()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ConnectionProfileManager.profilesFlow.collect {
                        updateAllDeviceChips()
                    }
                }
                launch {
                    ConnectionProfileManager.activeProfileFlow.collect { profile ->
                        updateSelectedDeviceUi(profile)
                    }
                }
                launch {
                    DiscoveryManager.discoveredDevices.collect {
                        updateAllDeviceChips()
                    }
                }
                launch {
                    WifiDirectManager.discoveredPeers.collect {
                        updateAllDeviceChips()
                    }
                }
                launch {
                    WifiDirectManager.groupOwnerIp.collect { goIp ->
                        if (goIp != null && currentMode == Mode.TRANSMITTER && currentConnType == ConnectionType.WIFI_DIRECT) {
                            val p2pProfile = ConnectionProfile(
                                id = "p2p_active",
                                name = "Wi-Fi Direct Receiver",
                                targetIp = goIp,
                                port = AudioConfig.DEFAULT_PORT,
                                connectionType = ConnectionType.WIFI_DIRECT
                            )
                            applyProfile(p2pProfile)
                        }
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
                currentMode = if (checkedId == R.id.btn_mode_transmitter) {
                    Mode.TRANSMITTER
                } else {
                    Mode.RECEIVER
                }
                syncDiscoveryMode()
                updateModeAndButtonUi()
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

    private fun updateAllDeviceChips() {
        chipGroupReceivers.removeAllViews()
        val activeProfile = ConnectionProfileManager.activeProfileFlow.value
        val currentIp = etTargetIp.text?.toString()?.trim().orEmpty()

        val savedProfiles = ConnectionProfileManager.profilesFlow.value
        val localDevices = DiscoveryManager.discoveredDevices.value
        val p2pPeers = WifiDirectManager.discoveredPeers.value

        // 1. Saved Profiles Chips
        for (profile in savedProfiles) {
            val isSelected = (activeProfile?.id == profile.id) || (currentIp == profile.targetIp)
            val chip = Chip(this).apply {
                val prefix = if (profile.connectionType == ConnectionType.WIFI_DIRECT) "[P2P]" else "[Saved]"
                text = "$prefix ${profile.name}"
                isCheckable = true
                isChecked = isSelected
                setOnClickListener {
                    applyProfile(profile)
                }
                setOnLongClickListener {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Delete Profile")
                        .setMessage("Remove saved profile '${profile.name}'?")
                        .setPositiveButton("Delete") { _, _ ->
                            ConnectionProfileManager.deleteProfile(this@MainActivity, profile.id)
                            Toast.makeText(this@MainActivity, "Deleted '${profile.name}'", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
            }
            chipGroupReceivers.addView(chip)
        }

        // 2. Discovered Devices (Local Wi-Fi)
        if (currentConnType == ConnectionType.LOCAL_WIFI) {
            for (dev in localDevices) {
                if (savedProfiles.any { it.targetIp == dev.ip }) continue

                val isSelected = currentIp == dev.ip
                val chip = Chip(this).apply {
                    text = "${dev.name} (${dev.ip})"
                    isCheckable = true
                    isChecked = isSelected
                    setOnClickListener {
                        val profile = ConnectionProfileManager.createOrUpdateFromDiscoveredDevice(
                            this@MainActivity,
                            dev,
                            ConnectionType.LOCAL_WIFI
                        )
                        applyProfile(profile)
                    }
                }
                chipGroupReceivers.addView(chip)
            }
        } else if (currentConnType == ConnectionType.WIFI_DIRECT) {
            // 3. Discovered Wi-Fi Direct Peers
            for (peer in p2pPeers) {
                val chip = Chip(this).apply {
                    text = "Direct: ${peer.deviceName}"
                    isCheckable = true
                    isChecked = false
                    setOnClickListener {
                        Toast.makeText(this@MainActivity, "Connecting to ${peer.deviceName}...", Toast.LENGTH_SHORT).show()
                        WifiDirectManager.connectToPeer(this@MainActivity, peer) { goIp ->
                            runOnUiThread {
                                val p2pProfile = ConnectionProfile(
                                    id = "p2p_${peer.deviceAddress}",
                                    name = peer.deviceName.ifEmpty { "Wi-Fi Direct Receiver" },
                                    targetIp = goIp,
                                    port = AudioConfig.DEFAULT_PORT,
                                    connectionType = ConnectionType.WIFI_DIRECT
                                )
                                ConnectionProfileManager.saveProfile(this@MainActivity, p2pProfile)
                                applyProfile(p2pProfile)
                                Toast.makeText(this@MainActivity, "Connected to ${peer.deviceName} ($goIp)", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                chipGroupReceivers.addView(chip)
            }
        }

        // Auto-select first profile or device if none currently targeted
        if (currentIp.isEmpty() || currentIp.endsWith(".255") || currentIp == "255.255.255.255") {
            if (savedProfiles.isNotEmpty()) {
                applyProfile(savedProfiles.first())
            } else if (localDevices.isNotEmpty() && currentConnType == ConnectionType.LOCAL_WIFI) {
                val firstDev = localDevices.first()
                val prof = ConnectionProfileManager.createOrUpdateFromDiscoveredDevice(
                    this,
                    firstDev,
                    ConnectionType.LOCAL_WIFI
                )
                applyProfile(prof)
            }
        }
    }

    private fun applyProfile(profile: ConnectionProfile) {
        ConnectionProfileManager.setActiveProfile(this, profile)
        etTargetIp.setText(profile.targetIp)
        etPort.setText(profile.port.toString())
        currentConnType = profile.connectionType
        if (profile.connectionType == ConnectionType.WIFI_DIRECT) {
            toggleConnType.check(R.id.btn_conn_p2p)
        } else {
            toggleConnType.check(R.id.btn_conn_wifi)
        }
        updateSelectedDeviceUi(profile)

        if (profile.preferredStreamingProfile.isNotEmpty()) {
            val prefs = getSharedPreferences("stream_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString(AudioConfig.PREF_KEY_PROFILE, profile.preferredStreamingProfile).apply()
        }
    }

    private fun updateSelectedDeviceUi(profile: ConnectionProfile?) {
        if (profile != null) {
            tvSelectedDeviceName.text = profile.name
            val typeDesc = when (profile.connectionType) {
                ConnectionType.WIFI_DIRECT -> "Direct Link (P2P)"
                ConnectionType.MULTI_UNICAST -> "Multi-Unicast"
                else -> "Local Wi-Fi"
            }
            tvSelectedDeviceType.text = "$typeDesc - ${profile.targetIp}"
            val capsDesc = if (profile.capabilitiesMask != 0) {
                "Hardware: Up to ${AudioCapabilities.getMaxSampleRate(profile.capabilitiesMask) / 1000}kHz, 24-bit"
            } else {
                "Hardware: Standard (up to 48kHz, 16-bit)"
            }
            tvSelectedDeviceCaps.text = capsDesc

            val isSaved = ConnectionProfileManager.findMatchingProfile(this, profile.targetIp) != null
            btnSaveProfile.visibility = if (isSaved) View.GONE else View.VISIBLE
        } else {
            val currentIp = etTargetIp.text?.toString()?.trim().orEmpty()
            if (currentIp.isNotEmpty() && !currentIp.endsWith(".255") && currentIp != "255.255.255.255") {
                tvSelectedDeviceName.text = "Custom Target"
                tvSelectedDeviceType.text = "Target IP: $currentIp"
                tvSelectedDeviceCaps.text = "Hardware: Unspecified"
                btnSaveProfile.visibility = View.VISIBLE
            } else {
                tvSelectedDeviceName.text = "Audio Receiver"
                tvSelectedDeviceType.text = "No device selected"
                tvSelectedDeviceCaps.text = "Tap a device below or scan"
                btnSaveProfile.visibility = View.GONE
            }
        }
    }

    private fun showSaveProfileDialog() {
        val currentIp = etTargetIp.text?.toString()?.trim().orEmpty()
        val currentPort = etPort.text?.toString()?.toIntOrNull() ?: AudioConfig.DEFAULT_PORT
        if (currentIp.isEmpty()) {
            Toast.makeText(this, "Enter or select a target IP first", Toast.LENGTH_SHORT).show()
            return
        }

        val input = EditText(this).apply {
            hint = "Profile Name (e.g. Living Room Receiver)"
            val defaultName = ConnectionProfileManager.activeProfileFlow.value?.name
                ?: if (currentConnType == ConnectionType.WIFI_DIRECT) "Direct Receiver" else "Receiver (${currentIp.takeLast(4)})"
            setText(defaultName)
            setSingleLine()
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 10)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Save Connection Profile")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Receiver $currentIp" }
                val profile = ConnectionProfile(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    targetIp = currentIp,
                    port = currentPort,
                    connectionType = currentConnType,
                    capabilitiesMask = DiscoveryManager.lastDiscoveredReceiverCapabilities
                )
                ConnectionProfileManager.saveProfile(this, profile)
                applyProfile(profile)
                Toast.makeText(this, "Saved profile '$name'", Toast.LENGTH_SHORT).show()
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
            val curVol = AudioCaptureService.remoteVolumePercent.get()
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
                layoutConnType.visibility = View.VISIBLE
                layoutActiveDevice.visibility = View.VISIBLE
                layoutDiscovery.visibility = View.VISIBLE
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
                layoutConnType.visibility = View.GONE
                layoutActiveDevice.visibility = View.GONE
                layoutDiscovery.visibility = View.GONE
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
        sendVolumeIntent(newVol)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (currentMode == Mode.TRANSMITTER && AudioCaptureService.isRunning.get()) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    sendVolumeDeltaIntent(5)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    sendVolumeDeltaIntent(-5)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (currentMode == Mode.TRANSMITTER && AudioCaptureService.isRunning.get()) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }
}
