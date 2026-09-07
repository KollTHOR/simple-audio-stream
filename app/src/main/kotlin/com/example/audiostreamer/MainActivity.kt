package com.example.audiostreamer

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

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
    private lateinit var tilTargetIp: TextInputLayout
    private lateinit var etTargetIp: TextInputEditText
    private lateinit var tilPort: TextInputLayout
    private lateinit var etPort: TextInputEditText
    private lateinit var btnAction: MaterialButton

    // Telemetry views
    private lateinit var tvBadgeStatus: TextView
    private lateinit var tvEndpointInfo: TextView
    private lateinit var tvPacketsStat: TextView
    private lateinit var pbAudioLevel: ProgressBar
    private lateinit var tvAudioLevelVal: TextView
    private lateinit var tvDiagnosticTip: TextView

    private var currentMode: Mode = Mode.TRANSMITTER
    private var detectedLocalIp: String? = null

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
            ContextCompat.startForegroundService(this, serviceIntent)
            updateModeAndButtonUi()
        } else {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        layoutIpPill = findViewById(R.id.layout_ip_pill)
        tvHeaderIp = findViewById(R.id.tv_header_ip)
        toggleModeGroup = findViewById(R.id.toggle_mode_group)
        btnModeTransmitter = findViewById(R.id.btn_mode_transmitter)
        btnModeReceiver = findViewById(R.id.btn_mode_receiver)
        tvModeGuide = findViewById(R.id.tv_mode_guide)
        tilTargetIp = findViewById(R.id.til_target_ip)
        etTargetIp = findViewById(R.id.et_target_ip)
        tilPort = findViewById(R.id.til_port)
        etPort = findViewById(R.id.et_port)
        btnAction = findViewById(R.id.btn_action)

        tvBadgeStatus = findViewById(R.id.tv_badge_status)
        tvEndpointInfo = findViewById(R.id.tv_endpoint_info)
        tvPacketsStat = findViewById(R.id.tv_packets_stat)
        pbAudioLevel = findViewById(R.id.pb_audio_level)
        tvAudioLevelVal = findViewById(R.id.tv_audio_level_val)
        tvDiagnosticTip = findViewById(R.id.tv_diagnostic_tip)

        refreshLocalIp()

        layoutIpPill.setOnClickListener {
            refreshLocalIp()
            detectedLocalIp?.let { ip ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("IP Address", ip))
                Toast.makeText(this, "IP copied: $ip", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<ImageView>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        toggleModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentMode = if (checkedId == R.id.btn_mode_transmitter) {
                    Mode.TRANSMITTER
                } else {
                    Mode.RECEIVER
                }
                updateModeAndButtonUi()
            }
        }

        btnAction.setOnClickListener {
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

        if (AudioCaptureService.isRunning.get()) {
            currentMode = Mode.TRANSMITTER
            toggleModeGroup.check(R.id.btn_mode_transmitter)
        } else if (AudioSinkService.isRunning.get()) {
            currentMode = Mode.RECEIVER
            toggleModeGroup.check(R.id.btn_mode_receiver)
        }
        updateModeAndButtonUi()
    }

    private fun refreshLocalIp() {
        detectedLocalIp = NetworkUtils.getLocalIpAddress()
        if (detectedLocalIp != null) {
            tvHeaderIp.text = detectedLocalIp
            if (etTargetIp.text.isNullOrEmpty() ||
                etTargetIp.text.toString() == "192.168.1.255" ||
                etTargetIp.text.toString() == "192.168.43.255") {
                etTargetIp.setText(NetworkUtils.getSuggestedBroadcastIp())
            }
        } else {
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

        if (!isSenderRunning && !isSinkRunning) {
            tvBadgeStatus.text = "IDLE"
            tvBadgeStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_gray))
            tvEndpointInfo.text = "Not connected"
            tvPacketsStat.text = "Packets: 0 (0 pkts/s • 0 KB/s)"
            pbAudioLevel.progress = 0
            tvAudioLevelVal.text = "0%"

            tvDiagnosticTip.text = if (currentMode == Mode.RECEIVER) {
                "💡 On receiver: Tap 'Start Listening'. Then enter ${detectedLocalIp ?: "this IP"} on your transmitter phone."
            } else {
                "💡 On transmitter: Enter the receiver's IP (displayed on receiver screen) and tap 'Start Streaming'."
            }
            return
        }

        if (isSenderRunning) {
            tvBadgeStatus.text = "TRANSMITTING"
            tvBadgeStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
            tvEndpointInfo.text = "Target: ${t.remoteEndpoint ?: "Configuring..."}"
            val kbps = (t.bytesPerSec * 8) / 1000
            tvPacketsStat.text = "Sent: ${t.packetsTotal} pkts (${t.packetsPerSec} pkts/s • ${kbps} kbps)"
            pbAudioLevel.progress = t.audioPeakPercent
            tvAudioLevelVal.text = "${t.audioPeakPercent}%"

            tvDiagnosticTip.text = if (t.audioPeakPercent > 1) {
                "🟢 Audio signal detected! Sending live system audio to target."
            } else {
                "ℹ️ Capturing system audio, but signal is currently silent. Start playing music or media on this device."
            }
        } else if (isSinkRunning) {
            val hasReceivedPackets = t.packetsTotal > 0
            if (hasReceivedPackets) {
                tvBadgeStatus.text = "PLAYING"
                tvBadgeStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_green))
                tvEndpointInfo.text = "From: ${t.remoteEndpoint ?: "Unknown"}"
                val kbps = (t.bytesPerSec * 8) / 1000
                tvPacketsStat.text = "Received: ${t.packetsTotal} pkts (${t.packetsPerSec} pkts/s • ${kbps} kbps)"
                pbAudioLevel.progress = t.audioPeakPercent
                tvAudioLevelVal.text = "${t.audioPeakPercent}%"

                tvDiagnosticTip.text = if (t.audioPeakPercent > 1) {
                    "🟢 Audio playing through AudioTrack. Adjust device volume if needed."
                } else {
                    "ℹ️ Packets arriving, but audio data is silent. Ensure transmitter phone is playing media."
                }
            } else {
                tvBadgeStatus.text = "WAITING"
                tvBadgeStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_orange))
                tvEndpointInfo.text = "Listening on ${detectedLocalIp ?: "0.0.0.0"}:${etPort.text}"
                tvPacketsStat.text = "Packets: 0 (Waiting for transmitter...)"
                pbAudioLevel.progress = 0
                tvAudioLevelVal.text = "0%"

                tvDiagnosticTip.text = "⚠️ Waiting for audio packets!\n" +
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
        startService(stopIntent)
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
        ContextCompat.startForegroundService(this, serviceIntent)
        updateModeAndButtonUi()
    }

    private fun stopReceiverService() {
        val stopIntent = Intent(this, AudioSinkService::class.java).apply {
            action = AudioSinkService.ACTION_STOP
        }
        startService(stopIntent)
        updateModeAndButtonUi()
    }

    private fun updateModeAndButtonUi() {
        val isSenderActive = AudioCaptureService.isRunning.get()
        val isSinkActive = AudioSinkService.isRunning.get()
        val isAnyActive = isSenderActive || isSinkActive

        btnModeTransmitter.isEnabled = !isAnyActive
        btnModeReceiver.isEnabled = !isAnyActive

        when (currentMode) {
            Mode.TRANSMITTER -> {
                tvModeGuide.text = "Capture & stream system audio to a receiver device"
                tilTargetIp.visibility = View.VISIBLE
                etTargetIp.isEnabled = !isSenderActive
                etPort.isEnabled = !isSenderActive

                if (isSenderActive) {
                    btnAction.text = getString(R.string.stop_stream)
                    btnAction.setIconResource(R.drawable.ic_stop)
                    btnAction.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_red))
                } else {
                    btnAction.text = getString(R.string.start_stream)
                    btnAction.setIconResource(R.drawable.ic_play)
                    btnAction.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
                }
            }
            Mode.RECEIVER -> {
                tvModeGuide.text = "Play raw audio stream received from transmitter"
                tilTargetIp.visibility = View.GONE
                etPort.isEnabled = !isSinkActive

                if (isSinkActive) {
                    btnAction.text = getString(R.string.stop_receiver)
                    btnAction.setIconResource(R.drawable.ic_stop)
                    btnAction.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_red))
                } else {
                    btnAction.text = getString(R.string.start_receiver)
                    btnAction.setIconResource(R.drawable.ic_play)
                    btnAction.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_green))
                }
            }
        }
    }
}
