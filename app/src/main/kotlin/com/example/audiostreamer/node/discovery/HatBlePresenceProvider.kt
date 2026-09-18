package com.example.audiostreamer.node.discovery

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.example.audiostreamer.HatDiagnostics
import com.example.audiostreamer.HatPacket
import com.example.audiostreamer.node.DevicePlatformInfo
import com.example.audiostreamer.node.LocalNodeManager
import com.example.audiostreamer.node.NodeCapabilities
import com.example.audiostreamer.node.NodeIdentity
import com.example.audiostreamer.node.NodeInfo
import com.example.audiostreamer.node.NodeState
import com.example.audiostreamer.node.StreamRole
import com.example.audiostreamer.AppLogger as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// ─────────────────────────────────────────────────────────────────────────────
// Public data models
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A HAT Node discovered via BLE presence advertisement.
 *
 * **Identity rule:** [bleAddress] is the Bluetooth radio MAC — a transport endpoint only.
 * The stable HAT identity is [nodeInfo].[NodeInfo.id] (`hat-node-<UUID>`), recovered from
 * the BLE service data payload. These must never be confused.
 *
 * [rssi] exposes signal strength for ranging/prioritisation by upper layers.
 */
data class BlePresenceNode(
    val nodeInfo: NodeInfo,
    /** BLE radio MAC address — transport endpoint, NOT HAT identity. */
    val bleAddress: String,
    val rssi: Int,
    val protocolVersion: Int,
    val capsVersion: Int,  // compact capability summary byte
    val shortName: String, // short device name from advertisement
    val firstSeenEpochMs: Long = System.currentTimeMillis(),
    val lastSeenEpochMs: Long = System.currentTimeMillis(),
    val firstSeenElapsedMs: Long = SystemClock.elapsedRealtime(),
    val lastSeenElapsedMs: Long = SystemClock.elapsedRealtime()
)

/** Lifecycle state of [HatBlePresenceProvider]. */
enum class BlePresenceState {
    /** Bluetooth LE not supported or feature flag absent. */
    UNSUPPORTED,
    /** Bluetooth LE supported but not yet started. */
    IDLE,
    /** Bluetooth adapter is disabled. */
    BT_DISABLED,
    /** Advertising HAT presence. */
    ADVERTISING,
    /** Scanning for HAT peers. */
    SCANNING,
    /** Both advertising and scanning. */
    ACTIVE,
    /** Stopped cleanly. */
    STOPPED,
    /** Unrecoverable error. */
    ERROR
}

/** Timing record for a single BLE provider operation. */
data class BlePresenceOperationTiming(
    val operation: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val durationMs: Long,
    val success: Boolean,
    val errorCode: Int? = null,
    val details: String? = null
) {
    override fun toString(): String =
        "[$operation] ${if (success) "OK" else "FAIL(${errorCode ?: "?"})"} ${durationMs}ms" +
                (if (!details.isNullOrBlank()) " $details" else "")
}

// ─────────────────────────────────────────────────────────────────────────────
// Provider
// ─────────────────────────────────────────────────────────────────────────────

/**
 * HAT BLE Presence Discovery Provider.
 *
 * **BLE is NOT an audio transport.** Its sole purpose is "I am a nearby HAT Node."
 *
 * **Advertising** embeds in the BLE service data:
 * - Protocol version (1 byte)
 * - Capability summary version (1 byte)
 * - Compact Node ID suffix (up to 12 UTF-8 bytes of the last characters of `hat-node-<UUID>`)
 * - Short device name (up to 10 bytes)
 *
 * **Scanning** filters for [HAT_SERVICE_UUID] only. On each result:
 * - Decodes the payload and recovers the compact Node ID
 * - De-duplicates by Node ID (not by BLE MAC)
 * - Tracks RSSI, first/last seen timestamps
 * - Prunes stale nodes after [STALE_TIMEOUT_MS]
 *
 * **Architecture rules enforced:**
 * - BLE MAC address is stored as transport metadata; it is NEVER the HAT identity.
 * - Discovery does NOT automatically establish an audio connection or a [com.example.audiostreamer.node.HatLink].
 * - Permissions are only checked when the feature is actually enabled.
 * - Every failure is logged with the exact code or reason — nothing is swallowed silently.
 */
@Suppress("DEPRECATION")
object HatBlePresenceProvider {

    private const val TAG = "HatBlePresenceProvider"

    // ─── HAT BLE service UUID (same as BleDiscoveryManager for HAT filtering) ──
    val HAT_SERVICE_UUID: UUID = UUID.fromString("00001850-0000-1000-8000-00805f9b34fb")
    val HAT_PARCEL_UUID: ParcelUuid = ParcelUuid(HAT_SERVICE_UUID)

    /**
     * Compact BLE payload wire format (max 27 bytes, fits standard BLE advertisement):
     *
     * Byte 0:       Protocol version (e.g. 1)
     * Byte 1:       Capability summary/version byte (caps mask summary)
     * Byte 2:       Node ID suffix length (N, max 12)
     * Bytes 3..3+N: Node ID suffix (last N chars of hat-node-<UUID>)
     * Byte 3+N:     Short name length (M, max 10)
     * Bytes 4+N..:  Short name UTF-8 bytes
     *
     * Total: 3 + N + 1 + M ≤ 27 → N≤12, M≤10 (24 bytes max payload within BLE PDU).
     */
    private const val NODE_ID_SUFFIX_MAX = 12
    private const val NAME_MAX = 10
    private const val STALE_TIMEOUT_MS = 30_000L  // remove node after 30 s without advertisement

    // ─── State ────────────────────────────────────────────────────────────────

    private val lock = Any()

    private val _state = MutableStateFlow(BlePresenceState.IDLE)
    val state: StateFlow<BlePresenceState> = _state.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _statusMessage = MutableStateFlow("Not started")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _discoveredNodes = MutableStateFlow<List<BlePresenceNode>>(emptyList())
    val discoveredNodes: StateFlow<List<BlePresenceNode>> = _discoveredNodes.asStateFlow()

    // ─── Permission state (updated at runtime) ────────────────────────────────

    @Volatile var lastPermissionState: String = "not_checked"
        private set

    // ─── Failure tracking ─────────────────────────────────────────────────────

    @Volatile private var lastFailureReason: String? = null
    @Volatile private var lastAdvertiseErrorCode: Int? = null
    @Volatile private var lastScanErrorCode: Int? = null

    // ─── Metrics ──────────────────────────────────────────────────────────────

    private val metricAdvertiseAttempts = AtomicInteger(0)
    private val metricAdvertiseSuccesses = AtomicInteger(0)
    private val metricAdvertiseFailures = AtomicInteger(0)
    private val metricScanAttempts = AtomicInteger(0)
    private val metricScanSuccesses = AtomicInteger(0)
    private val metricScanFailures = AtomicInteger(0)
    private val metricResultsReceived = AtomicInteger(0)
    private val metricNodesDiscovered = AtomicInteger(0)
    private val metricNodesLost = AtomicInteger(0)
    private val metricSelfFiltered = AtomicInteger(0)
    private val metricDecodeErrors = AtomicInteger(0)

    private val scanStartMs = AtomicLong(0L)
    private val advertiseStartMs = AtomicLong(0L)
    private val lastAdvertiseDurationMs = AtomicLong(-1L)
    private val lastScanDurationMs = AtomicLong(-1L)

    private val recentOperations = ConcurrentLinkedDeque<BlePresenceOperationTiming>()

    // ─── Internal BLE objects ─────────────────────────────────────────────────

    @Volatile private var bluetoothAdapter: BluetoothAdapter? = null
    @Volatile private var leAdvertiser: BluetoothLeAdvertiser? = null
    @Volatile private var leScanner: BluetoothLeScanner? = null
    @Volatile private var activeAdvertiseCallback: AdvertiseCallback? = null
    @Volatile private var activeScanCallback: ScanCallback? = null
    @Volatile private var applicationContext: Context? = null

    private var pruneJob: Job? = null
    private var pruneScope: CoroutineScope? = null

    /** Node registry keyed by stable Node ID (not BLE MAC). */
    private val nodesByNodeId = ConcurrentHashMap<String, BlePresenceNode>()

    // ─── Bluetooth state broadcast receiver ──────────────────────────────────

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                val name = bluetoothStateName(state)
                Log.i(TAG, "Bluetooth adapter state changed: $name")
                HatDiagnostics.info(
                    "BLE_PRESENCE_BT_STATE_CHANGED",
                    mapOf("state" to name, "code" to state)
                )
                when (state) {
                    BluetoothAdapter.STATE_OFF -> {
                        // Bluetooth turned off — all BLE operations cease
                        val reason = "Bluetooth adapter turned off"
                        HatDiagnostics.warn("BLE_PRESENCE_BT_OFF", mapOf("reason" to reason))
                        onBluetoothDisabled(reason)
                    }
                    BluetoothAdapter.STATE_ON -> {
                        HatDiagnostics.info("BLE_PRESENCE_BT_ON")
                        _state.value = BlePresenceState.IDLE
                        _statusMessage.value = "Bluetooth enabled"
                    }
                }
            }
        }
    }
    @Volatile private var btReceiverRegistered = false

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Checks BLE support, adapter state, and permission state.
     * Safe to call without requesting permissions — returns a summary only.
     */
    fun probeCapability(context: Context): Boolean {
        val appCtx = context.applicationContext
        applicationContext = appCtx

        if (!appCtx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            val reason = "FEATURE_BLUETOOTH_LE not present on device"
            Log.e(TAG, reason)
            HatDiagnostics.error("BLE_PRESENCE_UNSUPPORTED", mapOf("reason" to reason))
            lastFailureReason = reason
            _state.value = BlePresenceState.UNSUPPORTED
            _statusMessage.value = reason
            return false
        }

        val mgr = appCtx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = mgr?.adapter
        if (adapter == null) {
            val reason = "BluetoothAdapter is null — getSystemService returned null or no adapter"
            Log.e(TAG, reason)
            HatDiagnostics.error("BLE_PRESENCE_NO_ADAPTER", mapOf("reason" to reason))
            lastFailureReason = reason
            _state.value = BlePresenceState.UNSUPPORTED
            _statusMessage.value = reason
            return false
        }
        bluetoothAdapter = adapter

        val permState = describePermissions(appCtx)
        lastPermissionState = permState
        Log.i(TAG, "BLE probe: FEATURE_BLUETOOTH_LE present, adapter available, permissions: $permState")
        HatDiagnostics.info(
            "BLE_PRESENCE_PROBE",
            mapOf(
                "featurePresent" to true,
                "adapterEnabled" to adapter.isEnabled,
                "permissionState" to permState
            )
        )

        if (!adapter.isEnabled) {
            _state.value = BlePresenceState.BT_DISABLED
            _statusMessage.value = "Bluetooth adapter is disabled (permState=$permState)"
            HatDiagnostics.warn("BLE_PRESENCE_BT_DISABLED")
            return false
        }

        registerBluetoothStateReceiver(appCtx)
        _state.value = BlePresenceState.IDLE
        _statusMessage.value = "BLE ready (permState=$permState)"
        return true
    }

    /**
     * Starts BLE advertising with HAT presence payload.
     * Permissions are checked at call time — returns false and logs the exact reason if denied.
     */
    fun startAdvertising(context: Context): Boolean {
        val appCtx = context.applicationContext
        applicationContext = appCtx

        val permState = describePermissions(appCtx)
        lastPermissionState = permState

        if (!hasAdvertisePermission(appCtx)) {
            val reason = "Cannot advertise: missing BLUETOOTH_ADVERTISE permission (state=$permState)"
            Log.e(TAG, reason)
            HatDiagnostics.error("BLE_PRESENCE_ADV_PERMISSION_DENIED", mapOf("reason" to reason, "permState" to permState))
            lastFailureReason = reason
            return false
        }

        val adapter = bluetoothAdapter ?: run {
            val reason = "Cannot advertise: BluetoothAdapter not available (call probeCapability first)"
            Log.e(TAG, reason)
            HatDiagnostics.error("BLE_PRESENCE_NO_ADAPTER", mapOf("reason" to reason))
            lastFailureReason = reason
            return false
        }
        if (!adapter.isEnabled) {
            val reason = "Cannot advertise: Bluetooth adapter is disabled"
            Log.e(TAG, reason)
            HatDiagnostics.error("BLE_PRESENCE_BT_DISABLED", mapOf("reason" to reason))
            lastFailureReason = reason
            _state.value = BlePresenceState.BT_DISABLED
            return false
        }

        if (_isAdvertising.value) {
            Log.d(TAG, "startAdvertising: already advertising, skipping")
            return true
        }

        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            val reason = "BluetoothLeAdvertiser is null — device may not support peripheral mode"
            Log.e(TAG, reason)
            HatDiagnostics.error("BLE_PRESENCE_NO_ADVERTISER", mapOf("reason" to reason))
            lastFailureReason = reason
            return false
        }
        leAdvertiser = advertiser

        val localNode = try { LocalNodeManager.getLocalNode() } catch (e: Exception) {
            Log.e(TAG, "Cannot get local node for advertising: ${e.message}")
            HatDiagnostics.error("BLE_PRESENCE_NO_LOCAL_NODE", mapOf("reason" to e.message))
            return false
        }

        val payload = buildAdvertisePayload(localNode)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .setTimeout(0)  // advertise indefinitely
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)  // names burn advertising budget; use payload instead
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(HAT_PARCEL_UUID)
            .addServiceData(HAT_PARCEL_UUID, payload)
            .build()

        metricAdvertiseAttempts.incrementAndGet()
        val t0 = System.currentTimeMillis()
        advertiseStartMs.set(t0)
        Log.i(TAG, "startAdvertising: node='${localNode.name}' (${localNode.id}) payloadBytes=${payload.size}")
        HatDiagnostics.info(
            "BLE_PRESENCE_ADV_START",
            mapOf("nodeId" to localNode.id, "payloadBytes" to payload.size)
        )

        val callback = object : AdvertiseCallback() {
            @SuppressLint("MissingPermission")
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                val durationMs = System.currentTimeMillis() - advertiseStartMs.get()
                lastAdvertiseDurationMs.set(durationMs)
                metricAdvertiseSuccesses.incrementAndGet()
                _isAdvertising.value = true
                updateCompositeState()
                val msg = "BLE advertising started in ${durationMs}ms"
                Log.i(TAG, msg)
                HatDiagnostics.info("BLE_PRESENCE_ADV_STARTED", mapOf("durationMs" to durationMs))
                recordOp(BlePresenceOperationTiming("ADVERTISE", durationMs = durationMs, success = true))
                _statusMessage.value = msg
            }

            override fun onStartFailure(errorCode: Int) {
                val durationMs = System.currentTimeMillis() - advertiseStartMs.get()
                lastAdvertiseDurationMs.set(durationMs)
                metricAdvertiseFailures.incrementAndGet()
                lastAdvertiseErrorCode = errorCode
                _isAdvertising.value = false
                val reason = "BLE advertising failed: ${describeAdvertiseError(errorCode)}"
                lastFailureReason = reason
                Log.e(TAG, reason)
                HatDiagnostics.error(
                    "BLE_PRESENCE_ADV_FAILED",
                    mapOf("errorCode" to errorCode, "reason" to describeAdvertiseError(errorCode), "durationMs" to durationMs)
                )
                recordOp(BlePresenceOperationTiming("ADVERTISE", durationMs = durationMs, success = false, errorCode = errorCode, details = describeAdvertiseError(errorCode)))
                _statusMessage.value = reason
                updateCompositeState()
            }
        }

        return try {
            @Suppress("MissingPermission")
            advertiser.startAdvertising(settings, data, callback)
            activeAdvertiseCallback = callback
            true
        } catch (e: SecurityException) {
            val reason = "SecurityException starting BLE advertising: ${e.message}"
            Log.e(TAG, reason)
            HatDiagnostics.error("BLE_PRESENCE_ADV_SECURITY", mapOf("reason" to reason))
            lastFailureReason = reason
            false
        } catch (e: Exception) {
            val reason = "Exception starting BLE advertising: ${e.message}"
            Log.e(TAG, reason)
            HatDiagnostics.error("BLE_PRESENCE_ADV_EXCEPTION", mapOf("reason" to reason))
            lastFailureReason = reason
            false
        }
    }

    /**
     * Stops BLE advertising.
     */
    fun stopAdvertising() {
        val callback = activeAdvertiseCallback ?: return
        try {
            @Suppress("MissingPermission")
            leAdvertiser?.stopAdvertising(callback)
        } catch (e: Exception) {
            Log.e(TAG, "Exception stopping advertising: ${e.message}")
            HatDiagnostics.error("BLE_PRESENCE_ADV_STOP_ERROR", mapOf("reason" to e.message))
        }
        activeAdvertiseCallback = null
        leAdvertiser = null
        _isAdvertising.value = false
        updateCompositeState()
        Log.i(TAG, "BLE advertising stopped")
        HatDiagnostics.info("BLE_PRESENCE_ADV_STOPPED")
    }

    /**
     * Starts BLE scanning, filtered specifically for [HAT_SERVICE_UUID].
     * Permissions are checked at call time.
     */
    fun startScanning(context: Context): Boolean {
        val appCtx = context.applicationContext
        applicationContext = appCtx

        val permState = describePermissions(appCtx)
        lastPermissionState = permState

        if (!hasScanPermission(appCtx)) {
            val reason = "Cannot scan: missing BLUETOOTH_SCAN permission (state=$permState)"
            Log.e(TAG, reason)
            HatDiagnostics.error("BLE_PRESENCE_SCAN_PERMISSION_DENIED", mapOf("reason" to reason, "permState" to permState))
            lastFailureReason = reason
            return false
        }

        val adapter = bluetoothAdapter ?: run {
            val reason = "Cannot scan: BluetoothAdapter not available"
            Log.e(TAG, reason)
            HatDiagnostics.error("BLE_PRESENCE_NO_ADAPTER", mapOf("reason" to reason))
            lastFailureReason = reason
            return false
        }
        if (!adapter.isEnabled) {
            val reason = "Cannot scan: Bluetooth adapter is disabled"
            Log.e(TAG, reason)
            HatDiagnostics.error("BLE_PRESENCE_BT_DISABLED", mapOf("reason" to reason))
            lastFailureReason = reason
            _state.value = BlePresenceState.BT_DISABLED
            return false
        }

        if (_isScanning.value) {
            Log.d(TAG, "startScanning: already scanning, skipping")
            return true
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            val reason = "BluetoothLeScanner is null — adapter may be turning off"
            Log.e(TAG, reason)
            HatDiagnostics.error("BLE_PRESENCE_NO_SCANNER", mapOf("reason" to reason))
            lastFailureReason = reason
            return false
        }
        leScanner = scanner

        val filter = ScanFilter.Builder()
            .setServiceUuid(HAT_PARCEL_UUID)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)  // immediate reporting
            .build()

        metricScanAttempts.incrementAndGet()
        val t0 = System.currentTimeMillis()
        scanStartMs.set(t0)

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { handleScanResult(it) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { handleScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                val durationMs = System.currentTimeMillis() - scanStartMs.get()
                lastScanDurationMs.set(durationMs)
                metricScanFailures.incrementAndGet()
                lastScanErrorCode = errorCode
                _isScanning.value = false
                val reason = "BLE scan failed: ${describeScanError(errorCode)}"
                lastFailureReason = reason
                Log.e(TAG, reason)
                HatDiagnostics.error(
                    "BLE_PRESENCE_SCAN_FAILED",
                    mapOf("errorCode" to errorCode, "reason" to describeScanError(errorCode), "durationMs" to durationMs)
                )
                recordOp(BlePresenceOperationTiming("SCAN", durationMs = durationMs, success = false, errorCode = errorCode, details = describeScanError(errorCode)))
                updateCompositeState()
            }
        }

        return try {
            @Suppress("MissingPermission")
            scanner.startScan(listOf(filter), settings, callback)
            activeScanCallback = callback
            metricScanSuccesses.incrementAndGet()
            val durationMs = System.currentTimeMillis() - t0
            lastScanDurationMs.set(durationMs)
            _isScanning.value = true
            updateCompositeState()
            Log.i(TAG, "BLE scanning started in ${durationMs}ms")
            HatDiagnostics.info("BLE_PRESENCE_SCAN_STARTED", mapOf("durationMs" to durationMs))
            recordOp(BlePresenceOperationTiming("SCAN", durationMs = durationMs, success = true))
            _statusMessage.value = "BLE scanning for HAT nodes"
            startPruneLoop()
            true
        } catch (e: SecurityException) {
            val reason = "SecurityException starting BLE scan: ${e.message}"
            Log.e(TAG, reason)
            HatDiagnostics.error("BLE_PRESENCE_SCAN_SECURITY", mapOf("reason" to reason))
            lastFailureReason = reason
            false
        } catch (e: Exception) {
            val reason = "Exception starting BLE scan: ${e.message}"
            Log.e(TAG, reason)
            HatDiagnostics.error("BLE_PRESENCE_SCAN_EXCEPTION", mapOf("reason" to reason))
            lastFailureReason = reason
            false
        }
    }

    /**
     * Stops BLE scanning and the stale-node prune loop.
     */
    fun stopScanning() {
        val callback = activeScanCallback ?: return
        try {
            @Suppress("MissingPermission")
            leScanner?.stopScan(callback)
        } catch (e: Exception) {
            Log.e(TAG, "Exception stopping scan: ${e.message}")
            HatDiagnostics.error("BLE_PRESENCE_SCAN_STOP_ERROR", mapOf("reason" to e.message))
        }
        activeScanCallback = null
        leScanner = null
        _isScanning.value = false
        stopPruneLoop()
        updateCompositeState()
        Log.i(TAG, "BLE scanning stopped")
        HatDiagnostics.info("BLE_PRESENCE_SCAN_STOPPED")
    }

    /**
     * Stops both advertising and scanning, unregisters receivers, and resets state.
     */
    fun stopAll() {
        Log.i(TAG, "stopAll() called")
        HatDiagnostics.info("BLE_PRESENCE_STOP_ALL")
        stopAdvertising()
        stopScanning()
        unregisterBluetoothStateReceiver()
        _state.value = BlePresenceState.STOPPED
        _statusMessage.value = "Stopped"
    }

    /**
     * Clears the discovered node list. Safe to call from tests or UI reset.
     */
    fun clearDiscoveredNodes() {
        nodesByNodeId.clear()
        _discoveredNodes.value = emptyList()
    }

    /**
     * Prunes nodes not seen within [STALE_TIMEOUT_MS]. Called automatically in the scan loop,
     * but also exposed for testing.
     */
    fun pruneStaleNodes(maxAgeMs: Long = STALE_TIMEOUT_MS) {
        val now = SystemClock.elapsedRealtime()
        val before = nodesByNodeId.size
        val staleKeys = nodesByNodeId.entries
            .filter { now - it.value.lastSeenElapsedMs > maxAgeMs }
            .map { it.key }
        staleKeys.forEach { key ->
            val lost = nodesByNodeId.remove(key)
            if (lost != null) {
                metricNodesLost.incrementAndGet()
                Log.i(TAG, "BLE node lost (stale): ${lost.nodeInfo.id} (${lost.shortName}) lastRssi=${lost.rssi}dBm")
                HatDiagnostics.info(
                    "BLE_PRESENCE_NODE_LOST",
                    mapOf(
                        "nodeId" to lost.nodeInfo.id,
                        "shortName" to lost.shortName,
                        "bleAddress" to lost.bleAddress,
                        "lastRssi" to lost.rssi,
                        "ageMs" to (now - lost.lastSeenElapsedMs)
                    )
                )
            }
        }
        if (nodesByNodeId.size != before) publishNodeList()
    }

    /**
     * Returns a compact diagnostics snapshot for [HatDiagnostics.registerSection].
     */
    fun getDiagnosticsSnapshot(): Map<String, Any?> = synchronized(lock) {
        val recentOps = recentOperations.toList().takeLast(10)
        linkedMapOf(
            "serviceUuid" to HAT_SERVICE_UUID.toString(),
            "state" to _state.value.name,
            "isAdvertising" to _isAdvertising.value,
            "isScanning" to _isScanning.value,
            "statusMessage" to _statusMessage.value,
            "permissionState" to lastPermissionState,
            "lastFailureReason" to lastFailureReason,
            "lastAdvertiseErrorCode" to lastAdvertiseErrorCode,
            "lastScanErrorCode" to lastScanErrorCode,
            "discoveredNodesCount" to nodesByNodeId.size,
            "metrics" to linkedMapOf(
                "advertiseAttempts" to metricAdvertiseAttempts.get(),
                "advertiseSuccesses" to metricAdvertiseSuccesses.get(),
                "advertiseFailures" to metricAdvertiseFailures.get(),
                "scanAttempts" to metricScanAttempts.get(),
                "scanSuccesses" to metricScanSuccesses.get(),
                "scanFailures" to metricScanFailures.get(),
                "resultsReceived" to metricResultsReceived.get(),
                "nodesDiscovered" to metricNodesDiscovered.get(),
                "nodesLost" to metricNodesLost.get(),
                "selfFiltered" to metricSelfFiltered.get(),
                "decodeErrors" to metricDecodeErrors.get()
            ),
            "timing" to linkedMapOf(
                "lastAdvertiseDurationMs" to lastAdvertiseDurationMs.get(),
                "lastScanDurationMs" to lastScanDurationMs.get()
            ),
            "recentOperations" to recentOps.map { it.toString() },
            "discoveredNodes" to nodesByNodeId.values.map { n ->
                "${n.nodeInfo.id} (${n.shortName}) rssi=${n.rssi}dBm mac=${n.bleAddress} " +
                        "pv=${n.protocolVersion} caps=0x${n.capsVersion.and(0xFF).toString(16)} " +
                        "seen=${System.currentTimeMillis() - n.firstSeenEpochMs}ms ago"
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal: payload encoding / decoding
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the compact BLE service data payload for advertising.
     * Total size ≤ 27 bytes for BLE advertisement compatibility.
     *
     * Wire format:
     * [pv:1][caps:1][idSuffixLen:1][idSuffix:N][nameLen:1][name:M]
     */
    internal fun buildAdvertisePayload(nodeInfo: NodeInfo): ByteArray {
        val pv = HatPacket.PROTOCOL_VERSION.toInt().and(0xFF).toByte()
        val caps = nodeInfo.capabilities.toCapabilitiesMask().and(0xFF).toByte()

        // Use the last 12 characters of the node ID as a compact suffix
        val nodeId = nodeInfo.id
        val idSuffix = if (nodeId.length > NODE_ID_SUFFIX_MAX) nodeId.takeLast(NODE_ID_SUFFIX_MAX) else nodeId
        val idBytes = idSuffix.toByteArray(StandardCharsets.UTF_8)

        val name = nodeInfo.name.take(NAME_MAX)
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)

        val buf = ByteBuffer.allocate(3 + idBytes.size + 1 + nameBytes.size)
        buf.put(pv)
        buf.put(caps)
        buf.put(idBytes.size.toByte())
        buf.put(idBytes)
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        return buf.array()
    }

    /**
     * Parses the compact BLE service data payload.
     * Returns null if the payload is malformed; caller logs the failure.
     */
    internal fun parseAdvertisePayload(data: ByteArray): Triple<Int, Int, String>? {
        // Returns: (protocolVersion, capsVersion, nodeIdSuffix)
        // Short name is parsed separately but not returned from this triple
        if (data.size < 3) return null
        val pv = data[0].toInt().and(0xFF)
        val caps = data[1].toInt().and(0xFF)
        val idLen = data[2].toInt().and(0xFF)
        if (idLen == 0 || 3 + idLen > data.size) return null
        val idSuffix = String(data, 3, idLen, StandardCharsets.UTF_8)
        return Triple(pv, caps, idSuffix)
    }

    /**
     * Parses the short name from the payload (after the ID suffix).
     */
    internal fun parseShortName(data: ByteArray): String {
        if (data.size < 3) return ""
        val idLen = data[2].toInt().and(0xFF)
        val nameOffset = 3 + idLen
        if (nameOffset >= data.size) return ""
        val nameLen = data[nameOffset].toInt().and(0xFF)
        val nameStart = nameOffset + 1
        if (nameLen == 0 || nameStart + nameLen > data.size) return ""
        return String(data, nameStart, nameLen, StandardCharsets.UTF_8)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal: scan result handling
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        metricResultsReceived.incrementAndGet()

        val record = result.scanRecord
        if (record == null) {
            Log.d(TAG, "Ignoring scan result with null ScanRecord")
            return
        }

        val serviceData = record.getServiceData(HAT_PARCEL_UUID)
        if (serviceData == null || serviceData.size < 3) {
            // BLE device has our UUID but no HAT payload — might be the old BleDiscoveryManager format
            Log.d(TAG, "Scan result has no/short HAT service data (${serviceData?.size ?: 0} bytes) — skipping")
            return
        }

        val parsed = parseAdvertisePayload(serviceData)
        if (parsed == null) {
            metricDecodeErrors.incrementAndGet()
            Log.w(TAG, "Failed to decode HAT BLE payload (${serviceData.size} bytes)")
            HatDiagnostics.warn(
                "BLE_PRESENCE_DECODE_ERROR",
                mapOf("sizeBytes" to serviceData.size)
            )
            return
        }
        val (pv, caps, idSuffix) = parsed
        val shortName = parseShortName(serviceData)

        // Self-filter: ignore our own advertisement
        val localNode = try { LocalNodeManager.getLocalNode() } catch (e: Exception) { null }
        if (localNode != null && localNode.id.endsWith(idSuffix)) {
            metricSelfFiltered.incrementAndGet()
            return
        }

        val bleAddress = result.device?.address ?: "unknown"
        val rssi = result.rssi
        val now = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()

        // Reconstruct a best-effort full Node ID from the suffix.
        // The suffix uniquely identifies the node for deduplication.
        // Format: "ble-hat-<suffix>" — this is a discoverable peer key, NOT a permanent identity.
        // When a HAT Link is established, the full node ID is exchanged at the application layer.
        val nodeKey = idSuffix  // use suffix as dedup key
        val stableNodeId = "hat-node-ble-$idSuffix"  // safe synthesised ID for discovery phase

        // Verify the synthesised ID is acceptable to NodeIdentity
        if (NodeIdentity.isIpOrMac(stableNodeId)) {
            metricDecodeErrors.incrementAndGet()
            Log.e(TAG, "Synthesised Node ID '$stableNodeId' failed architecture check — BLE MAC? Bug.")
            HatDiagnostics.error(
                "BLE_PRESENCE_IDENTITY_REJECTED",
                mapOf("synthesisedId" to stableNodeId, "idSuffix" to idSuffix)
            )
            return
        }

        val existingNode = nodesByNodeId[nodeKey]
        if (existingNode != null) {
            // Update presence: RSSI and last-seen timestamp
            val updated = existingNode.copy(
                rssi = rssi,
                lastSeenEpochMs = now,
                lastSeenElapsedMs = nowElapsed
            )
            nodesByNodeId[nodeKey] = updated
        } else {
            // New node discovered
            metricNodesDiscovered.incrementAndGet()
            val firstSeenMs = System.currentTimeMillis() - (lastScanDurationMs.get().takeIf { it >= 0 } ?: 0L)
            val identity = try {
                NodeIdentity(id = stableNodeId, name = shortName.ifBlank { "HAT BLE Node" })
            } catch (e: IllegalArgumentException) {
                metricDecodeErrors.incrementAndGet()
                Log.e(TAG, "NodeIdentity rejected BLE node ID: '$stableNodeId' — ${e.message}")
                HatDiagnostics.error(
                    "BLE_PRESENCE_IDENTITY_INVALID",
                    mapOf("id" to stableNodeId, "reason" to (e.message ?: "unknown"))
                )
                return
            }
            val nodeInfo = NodeInfo(
                identity = identity,
                capabilities = NodeCapabilities.fromCapabilitiesMask(
                    mask = caps,
                    protocolVersion = pv
                ),
                deviceInfo = DevicePlatformInfo(),
                state = NodeState.AVAILABLE,
                activeRole = StreamRole.TRANSCEIVER
            )
            val discovered = BlePresenceNode(
                nodeInfo = nodeInfo,
                bleAddress = bleAddress,
                rssi = rssi,
                protocolVersion = pv,
                capsVersion = caps,
                shortName = shortName.ifBlank { "HAT BLE Node" },
                firstSeenEpochMs = now,
                lastSeenEpochMs = now,
                firstSeenElapsedMs = nowElapsed,
                lastSeenElapsedMs = nowElapsed
            )
            nodesByNodeId[nodeKey] = discovered
            Log.i(TAG, "New HAT BLE node: suffix='$idSuffix' shortName='$shortName' mac=$bleAddress rssi=${rssi}dBm pv=$pv caps=0x${caps.and(0xFF).toString(16)}")
            HatDiagnostics.info(
                "BLE_PRESENCE_NODE_FOUND",
                mapOf(
                    "nodeIdSuffix" to idSuffix,
                    "shortName" to shortName,
                    "bleAddress" to bleAddress,
                    "rssi" to rssi,
                    "protocolVersion" to pv,
                    "capsVersion" to caps
                )
            )
            recordOp(
                BlePresenceOperationTiming(
                    "NODE_FOUND",
                    durationMs = nowElapsed - scanStartMs.get().coerceAtLeast(nowElapsed - 60_000L),
                    success = true,
                    details = "suffix=$idSuffix rssi=${rssi}dBm"
                )
            )
        }
        publishNodeList()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal: prune loop
    // ─────────────────────────────────────────────────────────────────────────

    private fun startPruneLoop() {
        stopPruneLoop()
        val scope = CoroutineScope(Dispatchers.Default)
        pruneScope = scope
        pruneJob = scope.launch {
            while (isActive) {
                delay(10_000L)
                pruneStaleNodes()
            }
        }
    }

    private fun stopPruneLoop() {
        pruneJob?.cancel()
        pruneJob = null
        pruneScope = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal: Bluetooth state receiver
    // ─────────────────────────────────────────────────────────────────────────

    private fun registerBluetoothStateReceiver(context: Context) {
        if (!btReceiverRegistered) {
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            context.registerReceiver(bluetoothStateReceiver, filter)
            btReceiverRegistered = true
        }
    }

    private fun unregisterBluetoothStateReceiver() {
        if (btReceiverRegistered) {
            try {
                applicationContext?.unregisterReceiver(bluetoothStateReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering BT state receiver: ${e.message}")
            }
            btReceiverRegistered = false
        }
    }

    private fun onBluetoothDisabled(reason: String) {
        lastFailureReason = reason
        _isAdvertising.value = false
        _isScanning.value = false
        activeAdvertiseCallback = null
        activeScanCallback = null
        leAdvertiser = null
        leScanner = null
        stopPruneLoop()
        _state.value = BlePresenceState.BT_DISABLED
        _statusMessage.value = reason
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal: helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun publishNodeList() {
        _discoveredNodes.value = nodesByNodeId.values.toList()
    }

    private fun updateCompositeState() {
        _state.value = when {
            _isAdvertising.value && _isScanning.value -> BlePresenceState.ACTIVE
            _isAdvertising.value -> BlePresenceState.ADVERTISING
            _isScanning.value -> BlePresenceState.SCANNING
            else -> BlePresenceState.IDLE
        }
    }

    private fun recordOp(op: BlePresenceOperationTiming) {
        while (recentOperations.size >= 50) recentOperations.pollFirst()
        recentOperations.addLast(op)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permission helpers (Android 12+ split permissions)
    // ─────────────────────────────────────────────────────────────────────────

    fun hasAdvertisePermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                    PackageManager.PERMISSION_GRANTED
        }

    fun hasScanPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            val bt = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                    PackageManager.PERMISSION_GRANTED
            val loc = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            bt && loc
        }

    fun hasConnectPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                    PackageManager.PERMISSION_GRANTED
        }

    fun hasAllPermissions(context: Context): Boolean =
        hasAdvertisePermission(context) && hasScanPermission(context)

    fun describePermissions(context: Context): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scan = hasScanPermission(context)
            val adv = hasAdvertisePermission(context)
            val conn = hasConnectPermission(context)
            "SCAN=$scan ADVERTISE=$adv CONNECT=$conn"
        } else {
            val bt = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                    PackageManager.PERMISSION_GRANTED
            val loc = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            "BLUETOOTH=$bt FINE_LOCATION=$loc"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Error code descriptions
    // ─────────────────────────────────────────────────────────────────────────

    fun describeAdvertiseError(errorCode: Int): String = when (errorCode) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE ->
            "ADVERTISE_FAILED_DATA_TOO_LARGE (1) — payload exceeds 31-byte BLE advertisement limit"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
            "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS (2) — device has reached concurrent advertiser limit"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED ->
            "ADVERTISE_FAILED_ALREADY_STARTED (3) — advertising already active"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR ->
            "ADVERTISE_FAILED_INTERNAL_ERROR (4) — BLE stack internal error"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
            "ADVERTISE_FAILED_FEATURE_UNSUPPORTED (5) — BLE peripheral mode not supported"
        else -> "ADVERTISE_UNKNOWN_ERROR ($errorCode)"
    }

    fun describeScanError(errorCode: Int): String = when (errorCode) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED ->
            "SCAN_FAILED_ALREADY_STARTED (1) — scan already active"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
            "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED (2) — registration failed (BLE stack issue)"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR ->
            "SCAN_FAILED_INTERNAL_ERROR (3) — BLE stack internal error"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED ->
            "SCAN_FAILED_FEATURE_UNSUPPORTED (4) — BLE scan not supported on this device"
        5 -> "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES (5) — too many scan clients"
        6 -> "SCAN_FAILED_SCANNING_TOO_FREQUENTLY (6) — throttled by Android scan rate limiter"
        else -> "SCAN_UNKNOWN_ERROR ($errorCode)"
    }

    private fun bluetoothStateName(state: Int): String = when (state) {
        BluetoothAdapter.STATE_OFF -> "STATE_OFF"
        BluetoothAdapter.STATE_TURNING_OFF -> "STATE_TURNING_OFF"
        BluetoothAdapter.STATE_ON -> "STATE_ON"
        BluetoothAdapter.STATE_TURNING_ON -> "STATE_TURNING_ON"
        else -> "STATE_UNKNOWN($state)"
    }
}
