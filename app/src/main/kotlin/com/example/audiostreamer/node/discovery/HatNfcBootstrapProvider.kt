package com.example.audiostreamer.node.discovery

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import com.example.audiostreamer.HatDiagnostics
import com.example.audiostreamer.HatPacket
import com.example.audiostreamer.node.DevicePlatformInfo
import com.example.audiostreamer.node.HatLinkManager
import com.example.audiostreamer.node.LocalNodeManager
import com.example.audiostreamer.node.NodeCapabilities
import com.example.audiostreamer.node.NodeIdentity
import com.example.audiostreamer.node.NodeInfo
import com.example.audiostreamer.node.NodeState
import com.example.audiostreamer.node.NodeTransportType
import com.example.audiostreamer.node.StreamRole
import com.example.audiostreamer.AppLogger as Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

// ─────────────────────────────────────────────────────────────────────────────
// Public Data Models
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Compact, credential-free out-of-band bootstrap payload exchanged when two HAT devices tap via NFC.
 *
 * **Security & Invariants:**
 * - NFC is strictly a discovery/bootstrap mechanism, NOT an audio transport.
 * - [nodeId] is the permanent HAT Node ID (`hat-node-<UUID>`). Never use IP addresses, MAC addresses,
 *   or hardware NFC tag UIDs as HAT node identities.
 * - MUST NOT contain network passwords, Wi-Fi Direct passphrases, or sensitive device information.
 * - Unknown future JSON fields are safely ignored.
 */
data class HatNfcBootstrapPayload(
    val protocolVersion: Int = HatPacket.PROTOCOL_VERSION.toInt(),
    val nodeId: String,
    val nodeName: String,
    val transportHints: Set<NodeTransportType>,
    val sessionToken: String? = null,
    val portHint: Int? = null,
    val extraHints: Map<String, String> = emptyMap()
) {
    init {
        require(nodeId.isNotBlank()) { "Node ID cannot be blank" }
        require(nodeName.isNotBlank()) { "Node name cannot be blank" }
        require(!NodeIdentity.isMac(nodeId)) { "Node ID cannot be a MAC address: $nodeId" }
        require(!isNfcHardwareTagId(nodeId)) { "Node ID cannot be an NFC hardware tag ID: $nodeId" }
        require(!NodeIdentity.isIp(nodeId)) { "Node ID cannot be an IP address: $nodeId" }
        require(!containsSensitiveData()) { "NFC payload must not contain sensitive credentials or passwords" }
    }

    private fun containsSensitiveData(): Boolean {
        val forbiddenKeys = listOf("password", "passphrase", "pass", "ssid", "secret", "key", "wpa", "credential")
        val extraKeys = extraHints.keys.map { it.lowercase() }
        return extraKeys.any { k -> forbiddenKeys.any { f -> k.contains(f) } }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_PROTOCOL_VERSION, protocolVersion)
        put(KEY_NODE_ID, nodeId)
        put(KEY_NODE_NAME, nodeName)
        put(KEY_TRANSPORT_HINTS, JSONArray(transportHints.map { it.name }))
        sessionToken?.let { put(KEY_SESSION_TOKEN, it) }
        portHint?.let { put(KEY_PORT_HINT, it) }
        if (extraHints.isNotEmpty()) {
            val extraObj = JSONObject()
            extraHints.forEach { (k, v) -> extraObj.put(k, v) }
            put(KEY_EXTRA_HINTS, extraObj)
        }
    }

    fun toByteArray(): ByteArray =
        toJson().toString().toByteArray(StandardCharsets.UTF_8)

    /**
     * Encodes this bootstrap payload into an Android NDEF MIME record.
     */
    fun toNdefRecord(): NdefRecord =
        NdefRecord.createMime(MIME_TYPE, toByteArray())

    /**
     * Encodes this bootstrap payload into an Android NDEF message, accompanied by an Android
     * Application Record (AAR) to guarantee app launch on NFC tap.
     */
    fun toNdefMessage(): NdefMessage {
        val mimeRecord = toNdefRecord()
        val aarRecord = NdefRecord.createApplicationRecord(APPLICATION_PACKAGE)
        return NdefMessage(arrayOf(mimeRecord, aarRecord))
    }

    companion object {
        const val MIME_TYPE = "application/vnd.hat.bootstrap"
        const val APPLICATION_PACKAGE = "com.example.audiostreamer"

        const val KEY_PROTOCOL_VERSION = "pv"
        const val KEY_NODE_ID = "id"
        const val KEY_NODE_NAME = "name"
        const val KEY_TRANSPORT_HINTS = "hints"
        const val KEY_SESSION_TOKEN = "token"
        const val KEY_PORT_HINT = "port"
        const val KEY_EXTRA_HINTS = "extra"

        fun isNfcHardwareTagId(id: String): Boolean {
            if (id.startsWith("nfc:", ignoreCase = true) || id.startsWith("tag:", ignoreCase = true)) {
                return true
            }
            val parts = id.split(":", "-")
            if (parts.size in 4..10 && parts.size != 6 && parts.all { it.length == 2 && it.all { c -> c.isDigit() || (c in 'a'..'f') || (c in 'A'..'F') } }) {
                return true
            }
            return false
        }

        fun fromJson(json: JSONObject): HatNfcBootstrapPayload {
            val pv = json.optInt(KEY_PROTOCOL_VERSION, HatPacket.PROTOCOL_VERSION.toInt())
            val id = json.getString(KEY_NODE_ID)
            val name = json.optString(KEY_NODE_NAME, "HAT Node")

            val hints = mutableSetOf<NodeTransportType>()
            val hintsArray = json.optJSONArray(KEY_TRANSPORT_HINTS)
            if (hintsArray != null) {
                for (i in 0 until hintsArray.length()) {
                    val hintName = hintsArray.optString(i)
                    try {
                        hints.add(NodeTransportType.valueOf(hintName))
                    } catch (ignored: Exception) {}
                }
            }
            if (hints.isEmpty()) {
                // Fallback default transports if omitted
                hints.add(NodeTransportType.LOCAL_WIFI)
                hints.add(NodeTransportType.WIFI_DIRECT)
            }

            val token = json.optString(KEY_SESSION_TOKEN).takeIf { it.isNotBlank() }
            val port = if (json.has(KEY_PORT_HINT)) json.optInt(KEY_PORT_HINT) else null

            val extra = mutableMapOf<String, String>()
            val extraObj = json.optJSONObject(KEY_EXTRA_HINTS)
            if (extraObj != null) {
                val keys = extraObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    extra[k] = extraObj.optString(k)
                }
            }

            return HatNfcBootstrapPayload(
                protocolVersion = pv,
                nodeId = id,
                nodeName = name,
                transportHints = hints,
                sessionToken = token,
                portHint = port,
                extraHints = extra
            )
        }

        fun fromByteArray(bytes: ByteArray): HatNfcBootstrapPayload {
            val jsonString = String(bytes, StandardCharsets.UTF_8).trim()
            return fromJson(JSONObject(jsonString))
        }

        fun fromNdefRecord(record: NdefRecord): HatNfcBootstrapPayload {
            val payloadBytes = record.payload ?: throw IllegalArgumentException("NDEF record payload is null")
            return fromByteArray(payloadBytes)
        }

        fun fromNdefMessage(message: NdefMessage): HatNfcBootstrapPayload {
            val records = message.records ?: throw IllegalArgumentException("NDEF message has no records")
            for (rec in records) {
                try {
                    val typeStr = rec.type?.let { String(it, StandardCharsets.US_ASCII) }
                    if (typeStr == null || typeStr == MIME_TYPE || typeStr.contains("bootstrap")) {
                        return fromNdefRecord(rec)
                    }
                } catch (ignored: Exception) {}
            }
            if (records.isNotEmpty()) {
                return fromNdefRecord(records[0])
            }
            throw IllegalArgumentException("No valid HAT bootstrap record found in NDEF message")
        }
    }
}

/**
 * Result of an out-of-band NFC bootstrap exchange.
 */
data class HatNfcBootstrapResult(
    val success: Boolean,
    val remoteNode: NodeInfo? = null,
    val selectedTransport: NodeTransportType? = null,
    val sessionToken: String? = null,
    val details: String,
    val timestampEpochMs: Long = System.currentTimeMillis()
)

/**
 * A remote HAT Node discovered and identified through NFC tap bootstrap.
 */
data class HatNfcDiscoveredNode(
    val nodeInfo: NodeInfo,
    val preferredTransport: NodeTransportType?,
    val sessionToken: String?,
    val transportHints: Set<NodeTransportType>,
    val portHint: Int?,
    val discoveredAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Lifecycle state of the NFC bootstrap layer.
 */
enum class NfcBootstrapState {
    /** NFC hardware not present on device. */
    UNSUPPORTED,
    /** NFC hardware present but disabled in system settings. */
    DISABLED,
    /** NFC is enabled and ready for tap exchange. */
    READY,
    /** Actively processing an NFC tap. */
    PROCESSING_TAP,
    /** Provider stopped or cleaned up. */
    STOPPED,
    /** Error encountered during initialization or processing. */
    ERROR
}

/**
 * Record of an individual NFC bootstrap operation for timing analysis and diagnostics.
 */
data class NfcOperationTiming(
    val phase: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val durationMs: Long,
    val success: Boolean,
    val details: String? = null
) {
    override fun toString(): String =
        "[$phase] ${if (success) "OK" else "FAIL"} ${durationMs}ms" +
                (if (!details.isNullOrBlank()) " details=$details" else "")
}

// ─────────────────────────────────────────────────────────────────────────────
// Provider Object
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Dedicated NFC Out-Of-Band Bootstrap Provider for HAT.
 *
 * **Architecture Rules Enforced:**
 * 1. NFC is NOT an audio transport. It is exclusively an out-of-band bootstrap mechanism.
 * 2. Tapping two devices exchanges compact bootstrap payloads (Node ID, name, transport hints, session token).
 * 3. Identifies the remote node without relying on IP, MAC, or NFC tag hardware IDs.
 * 4. Hands the identified peer to [HatLinkManager], selecting the preferred high-bandwidth transport in order:
 *    **Wi-Fi Aware -> then Wi-Fi Direct -> then LAN if applicable**.
 * 5. Does NOT automatically connect or start audio; discovery informs the Link layer.
 * 6. If NFC hardware is absent or disabled, reports unsupported cleanly without crashing.
 */
object HatNfcBootstrapProvider {

    private const val TAG = "HatNfcBootstrapProvider"

    /** Preference priority for high-bandwidth transports negotiated via NFC. */
    val PREFERRED_TRANSPORT_PRIORITY = listOf(
        NodeTransportType.WIFI_AWARE,
        NodeTransportType.WIFI_DIRECT,
        NodeTransportType.LOCAL_WIFI
    )

    private val lock = Any()

    private val _state = MutableStateFlow(NfcBootstrapState.UNSUPPORTED)
    val state: StateFlow<NfcBootstrapState> = _state.asStateFlow()

    private val _statusMessage = MutableStateFlow("NFC not probed")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _discoveredNodes = MutableStateFlow<List<HatNfcDiscoveredNode>>(emptyList())
    val discoveredNodes: StateFlow<List<HatNfcDiscoveredNode>> = _discoveredNodes.asStateFlow()

    private val _lastResult = MutableStateFlow<HatNfcBootstrapResult?>(null)
    val lastResult: StateFlow<HatNfcBootstrapResult?> = _lastResult.asStateFlow()

    @Volatile private var featurePresent: Boolean? = null
    @Volatile private var nfcAdapter: NfcAdapter? = null
    @Volatile private var applicationContext: Context? = null
    @Volatile private var isReceiverRegistered = false

    val isSupported: Boolean
        get() = featurePresent == true && nfcAdapter != null

    val isEnabled: Boolean
        get() = isSupported && nfcAdapter?.isEnabled == true

    // ─── Metrics ──────────────────────────────────────────────────────────────

    private val metricTapsProcessed = AtomicInteger(0)
    private val metricPayloadsGenerated = AtomicInteger(0)
    private val metricPayloadsReceived = AtomicInteger(0)
    private val metricSuccessfulHandoffs = AtomicInteger(0)
    private val metricValidationFailures = AtomicInteger(0)
    private val metricUnsupportedTransportFailures = AtomicInteger(0)

    private val recentOperations = ConcurrentLinkedDeque<NfcOperationTiming>()
    private val discoveredMap = ConcurrentHashMap<String, HatNfcDiscoveredNode>()

    // ─── Broadcast Receiver for NFC Adapter state changes ─────────────────────

    private val nfcStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) {
                val adapter = nfcAdapter ?: return
                val enabled = adapter.isEnabled
                Log.i(TAG, "NFC adapter state changed: isEnabled=$enabled")
                HatDiagnostics.info("NFC_ADAPTER_STATE_CHANGED", mapOf("isEnabled" to enabled))
                if (enabled) {
                    _state.value = NfcBootstrapState.READY
                    _statusMessage.value = "NFC ready for tap bootstrap"
                } else {
                    _state.value = NfcBootstrapState.DISABLED
                    _statusMessage.value = "NFC is disabled in system settings"
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public Lifecycle & Capability APIs
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Probes device NFC capability.
     * Returns true if NFC hardware is present and enabled. If unavailable, reports unsupported cleanly.
     */
    fun probeCapability(context: Context): Boolean {
        val appCtx = context.applicationContext
        applicationContext = appCtx

        val hasFeature = appCtx.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)
        featurePresent = hasFeature
        if (!hasFeature) {
            val reason = "NFC hardware is not available on this device"
            Log.i(TAG, reason)
            HatDiagnostics.info("NFC_UNSUPPORTED", mapOf("reason" to reason))
            _state.value = NfcBootstrapState.UNSUPPORTED
            _statusMessage.value = reason
            return false
        }

        val adapter = try {
            NfcAdapter.getDefaultAdapter(appCtx)
        } catch (e: Exception) {
            Log.e(TAG, "Exception getting default NfcAdapter: ${e.message}")
            null
        }

        nfcAdapter = adapter
        if (adapter == null) {
            val reason = "NfcAdapter is null — device does not support NFC"
            Log.i(TAG, reason)
            HatDiagnostics.info("NFC_ADAPTER_NULL", mapOf("reason" to reason))
            _state.value = NfcBootstrapState.UNSUPPORTED
            _statusMessage.value = reason
            return false
        }

        if (!isReceiverRegistered) {
            val filter = IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
            appCtx.registerReceiver(nfcStateReceiver, filter)
            isReceiverRegistered = true
        }

        val enabled = adapter.isEnabled
        return if (enabled) {
            _state.value = NfcBootstrapState.READY
            _statusMessage.value = "NFC ready for tap bootstrap"
            HatDiagnostics.info("NFC_PROBE_OK", mapOf("isEnabled" to true))
            true
        } else {
            _state.value = NfcBootstrapState.DISABLED
            _statusMessage.value = "NFC is disabled in system settings"
            HatDiagnostics.warn("NFC_DISABLED", mapOf("reason" to "NFC disabled in settings"))
            false
        }
    }

    /**
     * Creates a local bootstrap payload advertising this node's identity and high-bandwidth hints.
     * Does NOT include passwords, Wi-Fi credentials, or sensitive secrets.
     */
    fun createLocalBootstrapPayload(
        sessionToken: String? = null,
        portHint: Int? = null
    ): HatNfcBootstrapPayload {
        val localNode = LocalNodeManager.getLocalNode()
        val effectiveToken = sessionToken ?: UUID.randomUUID().toString()

        // Filter local node's supported transports for high-bandwidth hints
        val highBandwidthHints = localNode.capabilities.supportedTransports.filter {
            it == NodeTransportType.WIFI_AWARE ||
            it == NodeTransportType.WIFI_DIRECT ||
            it == NodeTransportType.LOCAL_WIFI
        }.toSet().ifEmpty {
            setOf(NodeTransportType.WIFI_DIRECT, NodeTransportType.LOCAL_WIFI)
        }

        metricPayloadsGenerated.incrementAndGet()
        val payload = HatNfcBootstrapPayload(
            protocolVersion = HatPacket.PROTOCOL_VERSION.toInt(),
            nodeId = localNode.id,
            nodeName = localNode.name,
            transportHints = highBandwidthHints,
            sessionToken = effectiveToken,
            portHint = portHint
        )
        HatDiagnostics.info(
            "NFC_BOOTSTRAP_PAYLOAD_CREATED",
            mapOf("nodeId" to payload.nodeId, "hints" to payload.transportHints.map { it.name })
        )
        return payload
    }

    /**
     * Creates an [NdefMessage] wrapping this device's local bootstrap payload.
     */
    fun createLocalNdefMessage(
        sessionToken: String? = null,
        portHint: Int? = null
    ): NdefMessage =
        createLocalBootstrapPayload(sessionToken, portHint).toNdefMessage()

    /**
     * Evaluates high-bandwidth transport hints according to the strict priority:
     * **1. Wi-Fi Aware -> 2. Wi-Fi Direct -> 3. LAN (LOCAL_WIFI)**.
     */
    fun selectBestHighBandwidthTransport(
        remoteHints: Set<NodeTransportType>,
        localSupported: Set<NodeTransportType> = LocalNodeManager.getLocalNode().capabilities.supportedTransports
    ): NodeTransportType? {
        for (candidate in PREFERRED_TRANSPORT_PRIORITY) {
            if (remoteHints.contains(candidate) && localSupported.contains(candidate)) {
                return candidate
            }
        }
        // If localSupported doesn't match, check if candidate is present in remoteHints
        for (candidate in PREFERRED_TRANSPORT_PRIORITY) {
            if (remoteHints.contains(candidate)) {
                return candidate
            }
        }
        return null
    }

    /**
     * Processes a received [HatNfcBootstrapPayload], validates it, creates a [NodeInfo] representation,
     * selects the best high-bandwidth transport, and hands the information to [HatLinkManager].
     */
    fun handleReceivedBootstrapPayload(
        payload: HatNfcBootstrapPayload,
        context: Context? = null
    ): HatNfcBootstrapResult {
        val t0 = System.currentTimeMillis()
        metricPayloadsReceived.incrementAndGet()
        _state.value = NfcBootstrapState.PROCESSING_TAP

        // 1. Compatibility check
        if (payload.protocolVersion != HatPacket.PROTOCOL_VERSION.toInt()) {
            metricValidationFailures.incrementAndGet()
            val reason = "Incompatible protocol version: expected ${HatPacket.PROTOCOL_VERSION}, got ${payload.protocolVersion}"
            Log.w(TAG, reason)
            HatDiagnostics.warn("NFC_PROTOCOL_MISMATCH", mapOf("remotePv" to payload.protocolVersion))
            val result = HatNfcBootstrapResult(success = false, details = reason)
            _lastResult.value = result
            _state.value = NfcBootstrapState.READY
            recordOp(NfcOperationTiming("HANDLE_PAYLOAD", durationMs = System.currentTimeMillis() - t0, success = false, details = reason))
            return result
        }

        // 2. Reject self-node bootstrap
        val localNode = LocalNodeManager.getLocalNode()
        if (payload.nodeId == localNode.id) {
            val reason = "Ignoring bootstrap payload from self (nodeId=${payload.nodeId})"
            Log.i(TAG, reason)
            HatDiagnostics.info("NFC_SELF_BOOTSTRAP_IGNORED", mapOf("nodeId" to payload.nodeId))
            val result = HatNfcBootstrapResult(success = false, details = reason)
            _lastResult.value = result
            _state.value = NfcBootstrapState.READY
            return result
        }

        // 3. Form remote NodeInfo from the validated stable Node ID (NEVER an IP or MAC)
        val remoteIdentity = try {
            NodeIdentity(id = payload.nodeId, name = payload.nodeName)
        } catch (e: IllegalArgumentException) {
            metricValidationFailures.incrementAndGet()
            val reason = "Invalid remote NodeIdentity in bootstrap payload: ${e.message}"
            Log.e(TAG, reason)
            HatDiagnostics.error("NFC_INVALID_IDENTITY", mapOf("error" to (e.message ?: "")))
            val result = HatNfcBootstrapResult(success = false, details = reason)
            _lastResult.value = result
            _state.value = NfcBootstrapState.READY
            recordOp(NfcOperationTiming("HANDLE_PAYLOAD", durationMs = System.currentTimeMillis() - t0, success = false, details = reason))
            return result
        }

        val remoteNode = NodeInfo(
            identity = remoteIdentity,
            capabilities = NodeCapabilities(
                hasAudioInput = true,
                hasAudioOutput = true,
                supportedTransports = payload.transportHints
            ),
            deviceInfo = DevicePlatformInfo(),
            state = NodeState.AVAILABLE,
            activeRole = StreamRole.TRANSCEIVER
        )

        // 4. Select preferred high-bandwidth transport
        val selectedTransport = selectBestHighBandwidthTransport(payload.transportHints)
        if (selectedTransport == null) {
            metricUnsupportedTransportFailures.incrementAndGet()
            val reason = "No compatible high-bandwidth transport found in remote hints: ${payload.transportHints}"
            Log.w(TAG, reason)
            HatDiagnostics.warn("NFC_NO_COMMON_TRANSPORT", mapOf("hints" to payload.transportHints.map { it.name }))
            val result = HatNfcBootstrapResult(
                success = false,
                remoteNode = remoteNode,
                sessionToken = payload.sessionToken,
                details = reason
            )
            _lastResult.value = result
            _state.value = NfcBootstrapState.READY
            recordOp(NfcOperationTiming("HANDLE_PAYLOAD", durationMs = System.currentTimeMillis() - t0, success = false, details = reason))
            return result
        }

        // 5. Hand information to LinkManager
        HatLinkManager.recordBootstrappedNode(
            remoteNode = remoteNode,
            preferredTransport = selectedTransport,
            sessionToken = payload.sessionToken,
            remoteAddress = null,
            remotePort = payload.portHint
        )

        // 6. Record in local discovered nodes map
        val discoveredNode = HatNfcDiscoveredNode(
            nodeInfo = remoteNode,
            preferredTransport = selectedTransport,
            sessionToken = payload.sessionToken,
            transportHints = payload.transportHints,
            portHint = payload.portHint
        )
        discoveredMap[remoteNode.id] = discoveredNode
        publishDiscoveredNodes()

        metricSuccessfulHandoffs.incrementAndGet()
        val durationMs = System.currentTimeMillis() - t0
        val successMsg = "Successfully bootstrapped node '${remoteNode.name}' (${remoteNode.id}) -> preferred transport: $selectedTransport"
        Log.i(TAG, successMsg)
        HatDiagnostics.info(
            "NFC_BOOTSTRAP_SUCCESS",
            mapOf(
                "remoteNodeId" to remoteNode.id,
                "remoteNodeName" to remoteNode.name,
                "preferredTransport" to selectedTransport.name,
                "sessionToken" to (payload.sessionToken ?: "none"),
                "durationMs" to durationMs
            )
        )
        recordOp(NfcOperationTiming("HANDLE_PAYLOAD", durationMs = durationMs, success = true, details = "transport=${selectedTransport.name}"))

        val result = HatNfcBootstrapResult(
            success = true,
            remoteNode = remoteNode,
            selectedTransport = selectedTransport,
            sessionToken = payload.sessionToken,
            details = successMsg
        )
        _lastResult.value = result
        _state.value = NfcBootstrapState.READY
        _statusMessage.value = "Bootstrapped: ${remoteNode.name} ($selectedTransport)"
        return result
    }

    /**
     * Extracts and processes an NDEF bootstrap payload from an incoming Android [Intent].
     * Compatible with [NfcAdapter.ACTION_NDEF_DISCOVERED], [NfcAdapter.ACTION_TAG_DISCOVERED],
     * and [NfcAdapter.ACTION_TECH_DISCOVERED].
     */
    fun processNfcIntent(intent: Intent, context: Context? = null): HatNfcBootstrapResult? {
        val action = intent.action ?: return null
        if (action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            action != NfcAdapter.ACTION_TAG_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED
        ) {
            return null
        }

        metricTapsProcessed.incrementAndGet()
        Log.i(TAG, "Processing NFC intent with action: $action")
        HatDiagnostics.info("NFC_INTENT_RECEIVED", mapOf("action" to action))

        val rawMessages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        }

        if (rawMessages != null && rawMessages.isNotEmpty()) {
            for (raw in rawMessages) {
                if (raw is NdefMessage) {
                    try {
                        val payload = HatNfcBootstrapPayload.fromNdefMessage(raw)
                        return handleReceivedBootstrapPayload(payload, context)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse NdefMessage from intent: ${e.message}")
                    }
                }
            }
        }

        // If no direct NdefMessage was in the intent extras, try reading from the Tag extra
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }

        if (tag != null) {
            val payload = readBootstrapFromTag(tag)
            if (payload != null) {
                return handleReceivedBootstrapPayload(payload, context)
            }
        }

        val reason = "No valid HAT bootstrap NDEF payload found in NFC intent"
        Log.w(TAG, reason)
        HatDiagnostics.warn("NFC_NO_PAYLOAD", mapOf("action" to action))
        return HatNfcBootstrapResult(success = false, details = reason)
    }

    /**
     * Reads a HAT bootstrap payload from an NFC [Tag].
     */
    fun readBootstrapFromTag(tag: Tag): HatNfcBootstrapPayload? {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            return try {
                ndef.connect()
                val msg = ndef.ndefMessage ?: ndef.cachedNdefMessage
                ndef.close()
                msg?.let { HatNfcBootstrapPayload.fromNdefMessage(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Exception reading NDEF tag: ${e.message}")
                HatDiagnostics.error("NFC_TAG_READ_ERROR", mapOf("error" to (e.message ?: "")))
                null
            }
        }
        return null
    }

    /**
     * Writes a HAT bootstrap payload to an NFC [Tag].
     */
    fun writeBootstrapToTag(tag: Tag, payload: HatNfcBootstrapPayload): Boolean {
        val ndefMessage = payload.toNdefMessage()
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            return try {
                ndef.connect()
                if (!ndef.isWritable) {
                    Log.w(TAG, "NDEF tag is not writable")
                    ndef.close()
                    return false
                }
                if (ndef.maxSize < ndefMessage.toByteArray().size) {
                    Log.w(TAG, "NDEF tag capacity too small")
                    ndef.close()
                    return false
                }
                ndef.writeNdefMessage(ndefMessage)
                ndef.close()
                Log.i(TAG, "Successfully wrote HAT bootstrap payload to NDEF tag")
                HatDiagnostics.info("NFC_TAG_WRITE_SUCCESS", mapOf("bytes" to ndefMessage.toByteArray().size))
                true
            } catch (e: Exception) {
                Log.e(TAG, "Exception writing NDEF tag: ${e.message}")
                HatDiagnostics.error("NFC_TAG_WRITE_ERROR", mapOf("error" to (e.message ?: "")))
                false
            }
        }

        val formatable = NdefFormatable.get(tag)
        if (formatable != null) {
            return try {
                formatable.connect()
                formatable.format(ndefMessage)
                formatable.close()
                Log.i(TAG, "Successfully formatted and wrote HAT bootstrap payload to tag")
                HatDiagnostics.info("NFC_TAG_FORMAT_SUCCESS", mapOf("bytes" to ndefMessage.toByteArray().size))
                true
            } catch (e: Exception) {
                Log.e(TAG, "Exception formatting NDEF tag: ${e.message}")
                HatDiagnostics.error("NFC_TAG_FORMAT_ERROR", mapOf("error" to (e.message ?: "")))
                false
            }
        }

        return false
    }

    /**
     * Enables foreground dispatch or reader mode for an [Activity] while in the foreground.
     */
    fun enableNfcDispatch(activity: Activity) {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) return

        try {
            val flags = NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

            adapter.enableReaderMode(activity, { tag ->
                val payload = readBootstrapFromTag(tag)
                if (payload != null) {
                    activity.runOnUiThread {
                        handleReceivedBootstrapPayload(payload, activity)
                    }
                }
            }, flags, null)
            Log.d(TAG, "Enabled NFC reader mode for ${activity.localClassName}")
        } catch (e: Exception) {
            Log.w(TAG, "enableReaderMode failed, falling back to foreground dispatch: ${e.message}")
            try {
                val intent = Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val pendingIntent = PendingIntent.getActivity(
                    activity, 0, intent,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                )
                val filter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
                    addDataType(HatNfcBootstrapPayload.MIME_TYPE)
                }
                adapter.enableForegroundDispatch(activity, pendingIntent, arrayOf(filter), null)
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "enableForegroundDispatch also failed: ${fallbackEx.message}")
            }
        }
    }

    /**
     * Disables foreground dispatch / reader mode for an [Activity] when leaving foreground.
     */
    fun disableNfcDispatch(activity: Activity) {
        val adapter = nfcAdapter ?: return
        try {
            adapter.disableReaderMode(activity)
        } catch (ignored: Exception) {}
        try {
            adapter.disableForegroundDispatch(activity)
        } catch (ignored: Exception) {}
    }

    fun clearDiscoveredNodes() {
        discoveredMap.clear()
        _discoveredNodes.value = emptyList()
        _lastResult.value = null
    }

    fun stopAll() {
        Log.i(TAG, "stopAll() called")
        if (isReceiverRegistered) {
            try {
                applicationContext?.unregisterReceiver(nfcStateReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering nfcStateReceiver: ${e.message}")
            }
            isReceiverRegistered = false
        }
        clearDiscoveredNodes()
        _state.value = NfcBootstrapState.STOPPED
        _statusMessage.value = "Stopped"
    }

    /**
     * Diagnostics snapshot for [HatDiagnostics.registerSection].
     */
    fun getDiagnosticsSnapshot(): Map<String, Any?> = synchronized(lock) {
        val recentOps = recentOperations.toList().takeLast(10)
        linkedMapOf(
            "isSupported" to isSupported,
            "isEnabled" to isEnabled,
            "state" to _state.value.name,
            "statusMessage" to _statusMessage.value,
            "preferredTransportOrder" to PREFERRED_TRANSPORT_PRIORITY.map { it.name },
            "discoveredNodesCount" to discoveredMap.size,
            "lastBootstrapResult" to (_lastResult.value?.details ?: "None"),
            "metrics" to linkedMapOf(
                "tapsProcessed" to metricTapsProcessed.get(),
                "payloadsGenerated" to metricPayloadsGenerated.get(),
                "payloadsReceived" to metricPayloadsReceived.get(),
                "successfulHandoffs" to metricSuccessfulHandoffs.get(),
                "validationFailures" to metricValidationFailures.get(),
                "unsupportedTransportFailures" to metricUnsupportedTransportFailures.get()
            ),
            "recentOperations" to recentOps.map { it.toString() },
            "discoveredNodes" to discoveredMap.values.map { n ->
                "${n.nodeInfo.id} (${n.nodeInfo.name}) prefTransport=${n.preferredTransport?.name ?: "None"} " +
                        "token=${n.sessionToken ?: "none"} hints=${n.transportHints.map { it.name }}"
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun publishDiscoveredNodes() {
        _discoveredNodes.value = discoveredMap.values.toList()
    }

    private fun recordOp(op: NfcOperationTiming) {
        while (recentOperations.size >= 50) recentOperations.pollFirst()
        recentOperations.addLast(op)
    }
}
