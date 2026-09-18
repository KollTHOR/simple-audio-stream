package com.example.audiostreamer.node

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.audiostreamer.AudioBitDepth
import com.example.audiostreamer.AudioCapabilities
import com.example.audiostreamer.AudioCaptureService
import com.example.audiostreamer.AudioCodec
import com.example.audiostreamer.DiscoveryManager
import com.example.audiostreamer.HatPacket
import com.example.audiostreamer.WifiDirectManager
import com.example.audiostreamer.AppLogger as Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * Manages the local HAT Node instance:
 * 1. Generates and safely persists a stable [NodeIdentity] across app restarts/reinstalls.
 * 2. Probes local hardware and codec capabilities to construct [NodeCapabilities].
 * 3. Maintains live [NodeInfo] and dynamic [StreamRole] state transitions.
 */
object LocalNodeManager {
    private const val TAG = "LocalNodeManager"
    private const val PREFS_NAME = "hat_node_identity_prefs"
    private const val KEY_NODE_ID = "hat_node_id"
    private const val KEY_NODE_NAME = "hat_node_name"
    private const val BACKUP_FILE_NAME = "hat_node_identity.json"

    private val lock = Any()
    @Volatile
    private var isInitialized = false

    private val _localNode = MutableStateFlow(createDefaultLocalNode())
    val localNode: StateFlow<NodeInfo> = _localNode.asStateFlow()

    fun getLocalNode(): NodeInfo = _localNode.value

    /**
     * Initializes the local node using Android [Context].
     * Loads or generates the persistent [NodeIdentity], probes system capabilities,
     * and publishes the initial [NodeInfo].
     */
    fun init(context: Context) {
        synchronized(lock) {
            val appContext = context.applicationContext
            val identity = loadOrGenerateIdentity(appContext)
            val capabilities = probeCapabilities(appContext)
            val platformInfo = DevicePlatformInfo(
                manufacturer = Build.MANUFACTURER ?: "Unknown",
                model = Build.MODEL ?: "Unknown",
                osName = "Android",
                osVersion = Build.VERSION.RELEASE ?: "",
                apiLevel = Build.VERSION.SDK_INT
            )

            val node = NodeInfo(
                identity = identity,
                capabilities = capabilities,
                deviceInfo = platformInfo,
                state = NodeState.AVAILABLE,
                activeRole = StreamRole.IDLE,
                activeStreamGeneration = 0L,
                lastSeenEpochMs = System.currentTimeMillis()
            )

            _localNode.value = node
            isInitialized = true
            Log.i(TAG, "Local HAT Node initialized: id=${identity.id}, name='${identity.name}'")
        }
    }

    /**
     * Dynamically updates the runtime state and stream role of the local node.
     * Note: Roles are transient to the active stream session.
     */
    fun updateState(newState: NodeState, role: StreamRole = StreamRole.IDLE, generation: Long = 0L) {
        synchronized(lock) {
            val current = _localNode.value
            _localNode.value = current.copy(
                state = newState,
                activeRole = role,
                activeStreamGeneration = generation,
                lastSeenEpochMs = System.currentTimeMillis()
            )
            Log.d(TAG, "Local node state updated: state=$newState, role=$role, gen=$generation")
        }
    }

    /**
     * Updates the human-readable device name of the local node and persists it.
     */
    fun updateCustomName(context: Context, newName: String) {
        synchronized(lock) {
            val trimmed = newName.trim()
            if (trimmed.isBlank()) return
            val current = _localNode.value
            val updatedIdentity = current.identity.copy(name = trimmed)
            persistIdentity(context.applicationContext, updatedIdentity)
            _localNode.value = current.copy(identity = updatedIdentity)
            Log.i(TAG, "Updated local node name to: '$trimmed'")
        }
    }

    /**
     * Probes Android hardware, audio system features, and codec support.
     */
    fun probeCapabilities(context: Context): NodeCapabilities {
        val pm = context.packageManager
        val hasMic = pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        val hasSpeaker = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT)

        val supportedRates = mutableSetOf<Int>()
        for (rate in AudioCapabilities.ANDROID_NATIVE_RATES) {
            if (AudioCapabilities.isPlaybackSupported(rate, is24Bit = true) ||
                AudioCapabilities.isPlaybackSupported(rate, is24Bit = false) ||
                AudioCapabilities.isCaptureSupported(rate, is24Bit = true) ||
                AudioCapabilities.isCaptureSupported(rate, is24Bit = false)
            ) {
                supportedRates.add(rate)
            }
        }
        if (supportedRates.isEmpty()) supportedRates.add(48000)

        val codecs = mutableSetOf(AudioCodec.PCM, AudioCodec.LOSSLESS, AudioCodec.AAC)
        if (AudioCaptureService.isOpusEncoderAvailable()) {
            codecs.add(AudioCodec.OPUS)
        }

        val transports = mutableSetOf(NodeTransportType.LOCAL_WIFI)
        if (pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            transports.add(NodeTransportType.WIFI_DIRECT)
        }
        if (pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            transports.add(NodeTransportType.BLUETOOTH_LE)
        }

        return NodeCapabilities(
            hasAudioInput = true, // MediaProjection / AudioRecord supported on Android
            hasAudioOutput = true, // AudioTrack supported on Android
            hasMicrophone = hasMic,
            hasSpeaker = hasSpeaker,
            supportedCodecs = codecs,
            supportedSampleRates = supportedRates,
            supportedChannelCounts = setOf(2),
            supportedPcmFormats = setOf(AudioBitDepth.BIT_16, AudioBitDepth.BIT_24),
            supportedTransports = transports,
            protocolVersion = HatPacket.PROTOCOL_VERSION.toInt()
        )
    }

    private fun loadOrGenerateIdentity(context: Context): NodeIdentity {
        // 1. Try reading from SharedPreferences
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefId = prefs.getString(KEY_NODE_ID, null)
        val prefName = prefs.getString(KEY_NODE_NAME, null)

        if (!prefId.isNullOrBlank() && !prefName.isNullOrBlank()) {
            val identity = NodeIdentity(id = prefId, name = prefName)
            ensureBackupFile(context, identity)
            return identity
        }

        // 2. Try recovering from internal backup file in filesDir
        val backupIdentity = readFromBackupFile(File(context.filesDir, BACKUP_FILE_NAME))
            ?: tryExternalBackup(context)

        if (backupIdentity != null) {
            // Restore to SharedPreferences
            prefs.edit()
                .putString(KEY_NODE_ID, backupIdentity.id)
                .putString(KEY_NODE_NAME, backupIdentity.name)
                .apply()
            return backupIdentity
        }

        // 3. First-time initialization: generate a clean stable identity
        val defaultName = DiscoveryManager.getLocalDeviceName()
        val newIdentity = NodeIdentity.generate(defaultName)
        persistIdentity(context, newIdentity)
        return newIdentity
    }

    private fun persistIdentity(context: Context, identity: NodeIdentity) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_NODE_ID, identity.id)
            .putString(KEY_NODE_NAME, identity.name)
            .apply()

        // Write internal durable backup file
        try {
            val internalFile = File(context.filesDir, BACKUP_FILE_NAME)
            internalFile.writeText(identity.toJson().toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed writing internal backup identity file: ${e.message}")
        }

        // Write external app-storage backup file (if available)
        try {
            val extDir = context.getExternalFilesDir(null)
            if (extDir != null && extDir.canWrite()) {
                val extFile = File(extDir, BACKUP_FILE_NAME)
                extFile.writeText(identity.toJson().toString())
            }
        } catch (ignored: Exception) {}
    }

    private fun ensureBackupFile(context: Context, identity: NodeIdentity) {
        try {
            val internalFile = File(context.filesDir, BACKUP_FILE_NAME)
            if (!internalFile.exists()) {
                internalFile.writeText(identity.toJson().toString())
            }
        } catch (ignored: Exception) {}
    }

    private fun readFromBackupFile(file: File): NodeIdentity? {
        return try {
            if (file.exists() && file.canRead()) {
                val content = file.readText()
                if (content.isNotBlank()) {
                    NodeIdentity.fromJson(JSONObject(content))
                } else null
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading backup file ${file.name}: ${e.message}")
            null
        }
    }

    private fun tryExternalBackup(context: Context): NodeIdentity? {
        return try {
            val extDir = context.getExternalFilesDir(null)
            if (extDir != null) {
                readFromBackupFile(File(extDir, BACKUP_FILE_NAME))
            } else null
        } catch (ignored: Exception) {
            null
        }
    }

    private fun createDefaultLocalNode(): NodeInfo {
        val fallbackName = try {
            DiscoveryManager.getLocalDeviceName()
        } catch (e: Exception) {
            "HAT Node"
        }
        return NodeInfo(
            identity = NodeIdentity.generate(fallbackName),
            capabilities = NodeCapabilities(),
            deviceInfo = DevicePlatformInfo(),
            state = NodeState.AVAILABLE,
            activeRole = StreamRole.IDLE
        )
    }
}
