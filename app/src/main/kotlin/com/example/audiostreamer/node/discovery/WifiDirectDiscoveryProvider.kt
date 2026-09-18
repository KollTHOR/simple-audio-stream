package com.example.audiostreamer.node.discovery

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.audiostreamer.AudioConfig
import com.example.audiostreamer.HatDiagnostics
import com.example.audiostreamer.node.DevicePlatformInfo
import com.example.audiostreamer.node.LocalNodeManager
import com.example.audiostreamer.node.NodeCapabilities
import com.example.audiostreamer.node.NodeIdentity
import com.example.audiostreamer.node.NodeInfo
import com.example.audiostreamer.node.NodeState
import com.example.audiostreamer.node.StreamRole
import com.example.audiostreamer.AppLogger as Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Resolved endpoint and metadata for a HAT Node discovered via Wi-Fi Direct DNS-SD.
 */
data class WifiDirectDiscoveredNode(
    val nodeInfo: NodeInfo,
    val deviceAddress: String, // Wi-Fi Direct MAC address (transport/radio address, NOT node identity!)
    val deviceName: String,    // Raw P2P device name from radio beacon
    val serviceType: String = WifiDirectDiscoveryProvider.SERVICE_TYPE,
    val txtRecord: Map<String, String> = emptyMap(),
    val firstDiscoveredEpochMs: Long = System.currentTimeMillis(),
    val lastSeenEpochMs: Long = System.currentTimeMillis(),
    val serviceFoundLatencyMs: Long = 0L,  // time from discovery start to onDnsSdServiceAvailable
    val txtResolvedLatencyMs: Long = 0L    // time from serviceFound to onDnsSdTxtRecordAvailable
)

enum class WifiDirectDiscoveryState {
    STOPPED,
    INITIALIZING,
    ADVERTISING,
    DISCOVERING,
    STOPPING,
    ERROR
}

/**
 * Record of an individual Wi-Fi Direct P2P discovery or framework operation timing.
 */
data class P2pOperationTiming(
    val operation: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val durationMs: Long,
    val success: Boolean,
    val errorCode: Int? = null,
    val details: String? = null
)

/**
 * Dedicated Wi-Fi Direct DNS-SD Discovery Provider for HAT Nodes using Android [WifiP2pManager].
 *
 * Service: `_hats._tcp`
 *
 * Responsibilities:
 * - Advertises local HAT node via Wi-Fi Direct DNS-SD (`addLocalService`)
 * - Discovers remote HAT services via Wi-Fi Direct DNS-SD (`discoverServices` with `WifiP2pDnsSdServiceRequest`)
 * - Exchanges stable Node ID (`NodeIdentity.id`), port, and capability metadata in DNS-SD TXT records
 * - Tracks discovered peers and measures exact discovery & resolution timing
 * - Detects peer disappearance via `WIFI_P2P_PEERS_CHANGED_ACTION` radio scan updates and TTL
 * - Instruments all critical operations: discovery start, success, failure, BUSY, ERROR, P2P_UNSUPPORTED,
 *   service found, service resolved, peer lost, and connection attempt durations
 * - NEVER hides failures behind retries; surfaces raw codes and diagnostics immediately
 * - NEVER uses Wi-Fi Direct MAC address as HAT identity
 * - Does NOT start audio streaming automatically
 */
object WifiDirectDiscoveryProvider {
    private const val TAG = "WifiDirectDiscoveryProvider"

    const val SERVICE_TYPE = "_hats._tcp"
    const val KEY_PROTOCOL_VERSION = "pv"
    const val KEY_NODE_ID = "id"
    const val KEY_NODE_NAME = "name"
    const val KEY_PORT = "port"
    const val KEY_CAPABILITIES = "caps"
    const val KEY_AVAILABILITY = "avail"
    const val KEY_ROLE = "role"
    const val KEY_P2P_MAC = "p2p_mac"

    private val lock = Any()

    private val _state = MutableStateFlow(WifiDirectDiscoveryState.STOPPED)
    val state: StateFlow<WifiDirectDiscoveryState> = _state.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _discoveredNodes = MutableStateFlow<List<NodeInfo>>(emptyList())
    val discoveredNodes: StateFlow<List<NodeInfo>> = _discoveredNodes.asStateFlow()

    private val _discoveredEndpoints = MutableStateFlow<List<WifiDirectDiscoveredNode>>(emptyList())
    val discoveredEndpoints: StateFlow<List<WifiDirectDiscoveredNode>> = _discoveredEndpoints.asStateFlow()

    // Active nodes indexed by stable Node ID
    private val nodesByNodeId = ConcurrentHashMap<String, WifiDirectDiscoveredNode>()
    // Mapping from P2P MAC address to Node ID
    private val macToNodeId = ConcurrentHashMap<String, String>()

    // Pending partial announcements (service response received but waiting for TXT, or vice versa)
    private data class PendingServiceAnnouncement(
        val deviceAddress: String,
        val deviceName: String,
        val instanceName: String,
        val registrationType: String,
        val foundTimestampMs: Long
    )
    private val pendingServicesByDeviceAddress = ConcurrentHashMap<String, PendingServiceAnnouncement>()
    private val pendingTxtRecordsByDeviceAddress = ConcurrentHashMap<String, Map<String, String>>()

    @Volatile private var wifiP2pManager: WifiP2pManager? = null
    @Volatile private var channel: WifiP2pManager.Channel? = null
    @Volatile private var receiver: BroadcastReceiver? = null
    @Volatile private var isReceiverRegistered = false
    @Volatile private var appContext: Context? = null

    @Volatile private var activeServiceRequest: WifiP2pDnsSdServiceRequest? = null
    @Volatile private var activeLocalServiceInfo: WifiP2pDnsSdServiceInfo? = null
    @Volatile var thisDeviceAddress: String? = null
    @Volatile var thisDeviceName: String? = null

    // ---------------------------------------------------------------------------------------------
    // Timing & Instrumentation Metrics
    // ---------------------------------------------------------------------------------------------
    private val discoveryStartsCount = AtomicLong(0L)
    private val discoverySuccessesCount = AtomicLong(0L)
    private val discoveryFailuresCount = AtomicLong(0L)
    private val busyFailuresCount = AtomicLong(0L)
    private val unsupportedFailuresCount = AtomicLong(0L)
    private val errorFailuresCount = AtomicLong(0L)
    private val servicesFoundCount = AtomicLong(0L)
    private val servicesResolvedCount = AtomicLong(0L)
    private val peersLostCount = AtomicLong(0L)

    private val lastDiscoveryStartTimeMs = AtomicLong(0L)
    private val lastDiscoveryAcceptanceDurationMs = AtomicLong(0L)
    private val lastFirstServiceFoundDurationMs = AtomicLong(0L)
    private val lastFirstServiceResolvedDurationMs = AtomicLong(0L)

    private val lastErrorCode = AtomicInteger(-1)
    @Volatile private var lastErrorReason: String? = null
    private val lastErrorTimestampMs = AtomicLong(0L)

    private val recentOperations = ConcurrentLinkedDeque<P2pOperationTiming>()
    private const val MAX_OPERATION_LOG_SIZE = 50

    /**
     * Checks if required Wi-Fi Direct permissions are granted.
     */
    fun hasPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNearby = ContextCompat.checkSelfPermission(context, android.Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            val hasFine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            hasNearby && hasFine
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Initializes the Wi-Fi Direct manager and channel if not already done.
     */
    fun init(context: Context): Boolean {
        synchronized(lock) {
            if (wifiP2pManager != null && channel != null) return true
            val appCtx = context.applicationContext
            appContext = appCtx
            val mgr = appCtx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            if (mgr == null) {
                val msg = "Wi-Fi Direct (P2P) hardware not supported on this device"
                Log.w(TAG, msg)
                _state.value = WifiDirectDiscoveryState.ERROR
                _statusMessage.value = msg
                recordTiming("INIT", 0L, false, WifiP2pManager.P2P_UNSUPPORTED, msg)
                HatDiagnostics.error("P2P_UNSUPPORTED", mapOf("stage" to "init", "message" to msg))
                return false
            }
            wifiP2pManager = mgr
            channel = mgr.initialize(appCtx, appCtx.mainLooper) {
                Log.w(TAG, "Wi-Fi Direct channel disconnected, reinitializing...")
                channel = wifiP2pManager?.initialize(appCtx, appCtx.mainLooper, null)
            }
            registerReceiver(appCtx)
            Log.i(TAG, "Wi-Fi Direct Discovery Provider initialized")
            return true
        }
    }

    private fun registerReceiver(context: Context) {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        val p2pReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val p2pState = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        val enabled = p2pState == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        Log.d(TAG, "WIFI_P2P_STATE_CHANGED: enabled=$enabled")
                        if (!enabled) {
                            handleP2pDisabled()
                        }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        Log.d(TAG, "WIFI_P2P_PEERS_CHANGED: querying active peers for disappearance check")
                        checkPeerDisappearance()
                    }
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        val dev = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        }
                        if (dev != null) {
                            thisDeviceAddress = dev.deviceAddress
                            thisDeviceName = dev.deviceName
                            Log.d(TAG, "Local P2P device: ${dev.deviceName} (${dev.deviceAddress})")
                        }
                    }
                }
            }
        }
        try {
            context.registerReceiver(p2pReceiver, filter)
            receiver = p2pReceiver
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register P2P broadcast receiver: ${e.message}")
        }
    }

    /**
     * Starts Wi-Fi Direct DNS-SD service discovery for remote HAT nodes.
     */
    @SuppressLint("MissingPermission")
    fun startDiscovery(context: Context) {
        synchronized(lock) {
            if (_isScanning.value) {
                Log.d(TAG, "Wi-Fi Direct discovery already scanning")
                return
            }
            if (!init(context)) return

            if (!hasPermissions(context)) {
                val permMsg = "Wi-Fi Direct permissions missing (NEARBY_WIFI_DEVICES / ACCESS_FINE_LOCATION)"
                _state.value = WifiDirectDiscoveryState.ERROR
                _statusMessage.value = permMsg
                Log.w(TAG, permMsg)
                recordTiming("START_DISCOVERY", 0L, false, WifiP2pManager.ERROR, permMsg)
                HatDiagnostics.error("P2P_DISCOVERY_FAILURE", mapOf("stage" to "permissions", "reason" to permMsg))
                return
            }

            val mgr = wifiP2pManager ?: return
            val ch = channel ?: return

            _state.value = WifiDirectDiscoveryState.INITIALIZING
            _statusMessage.value = "Configuring DNS-SD service discovery..."
            val startMs = System.currentTimeMillis()
            lastDiscoveryStartTimeMs.set(startMs)
            discoveryStartsCount.incrementAndGet()

            HatDiagnostics.info("P2P_DISCOVERY_START", mapOf(
                "serviceType" to SERVICE_TYPE,
                "timestamp" to startMs
            ))

            // 1. Setup DNS-SD response listeners
            val serviceListener = WifiP2pManager.DnsSdServiceResponseListener { instanceName, registrationType, srcDevice ->
                handleDnsSdServiceAvailable(instanceName, registrationType, srcDevice)
            }
            val txtListener = WifiP2pManager.DnsSdTxtRecordListener { fullDomainName, record, srcDevice ->
                handleDnsSdTxtRecordAvailable(fullDomainName, record, srcDevice)
            }

            try {
                mgr.setDnsSdResponseListeners(ch, serviceListener, txtListener)
            } catch (e: Exception) {
                val msg = "Failed to set DNS-SD response listeners: ${e.message}"
                Log.e(TAG, msg, e)
                _state.value = WifiDirectDiscoveryState.ERROR
                _statusMessage.value = msg
                recordTiming("SET_LISTENERS", System.currentTimeMillis() - startMs, false, WifiP2pManager.ERROR, msg)
                HatDiagnostics.error("P2P_DISCOVERY_FAILURE", mapOf("stage" to "setDnsSdResponseListeners", "message" to msg), e)
                return
            }

            // 2. Clear previous service requests and add request for _hats._tcp
            val clearReqStart = System.currentTimeMillis()
            mgr.clearServiceRequests(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    recordTiming("CLEAR_SERVICE_REQUESTS", System.currentTimeMillis() - clearReqStart, true)
                    addServiceRequestAndDiscover(mgr, ch, startMs)
                }

                override fun onFailure(reason: Int) {
                    val desc = describeErrorCode(reason)
                    Log.w(TAG, "clearServiceRequests failed ($reason): $desc - proceeding with addServiceRequest anyway")
                    recordTiming("CLEAR_SERVICE_REQUESTS", System.currentTimeMillis() - clearReqStart, false, reason, desc)
                    addServiceRequestAndDiscover(mgr, ch, startMs)
                }
            })
        }
    }

    private fun addServiceRequestAndDiscover(mgr: WifiP2pManager, ch: WifiP2pManager.Channel, overallStartMs: Long) {
        val reqStart = System.currentTimeMillis()
        val request = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE)
        activeServiceRequest = request

        mgr.addServiceRequest(ch, request, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                val reqDuration = System.currentTimeMillis() - reqStart
                recordTiming("ADD_SERVICE_REQUEST", reqDuration, true)
                Log.d(TAG, "Added DNS-SD service request in ${reqDuration}ms. Initiating discoverServices()...")

                initiateDiscoverServices(mgr, ch, overallStartMs)
            }

            override fun onFailure(reason: Int) {
                val reqDuration = System.currentTimeMillis() - reqStart
                val desc = describeErrorCode(reason)
                Log.e(TAG, "addServiceRequest failed ($reason): $desc")
                recordTiming("ADD_SERVICE_REQUEST", reqDuration, false, reason, desc)
                handleDiscoveryFailure("addServiceRequest", reason, reqDuration)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun initiateDiscoverServices(mgr: WifiP2pManager, ch: WifiP2pManager.Channel, overallStartMs: Long) {
        val discStart = System.currentTimeMillis()
        mgr.discoverServices(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                val acceptanceDuration = System.currentTimeMillis() - discStart
                val totalDuration = System.currentTimeMillis() - overallStartMs
                lastDiscoveryAcceptanceDurationMs.set(acceptanceDuration)
                discoverySuccessesCount.incrementAndGet()

                _isScanning.value = true
                _state.value = WifiDirectDiscoveryState.DISCOVERING
                _statusMessage.value = "Scanning Wi-Fi Direct HAT services (accepted in ${acceptanceDuration}ms)"

                recordTiming("DISCOVER_SERVICES", acceptanceDuration, true)
                Log.i(TAG, "Wi-Fi Direct discoverServices() accepted in ${acceptanceDuration}ms (total init: ${totalDuration}ms)")

                HatDiagnostics.info("P2P_DISCOVERY_SUCCESS", mapOf(
                    "acceptanceDurationMs" to acceptanceDuration,
                    "totalInitDurationMs" to totalDuration
                ))
            }

            override fun onFailure(reason: Int) {
                val discDuration = System.currentTimeMillis() - discStart
                handleDiscoveryFailure("discoverServices", reason, discDuration)
            }
        })
    }

    private fun handleDiscoveryFailure(stage: String, reason: Int, durationMs: Long) {
        _isScanning.value = false
        _state.value = WifiDirectDiscoveryState.ERROR
        discoveryFailuresCount.incrementAndGet()

        val reasonDesc = describeErrorCode(reason)
        _statusMessage.value = "Discovery failed at $stage: $reasonDesc"
        lastErrorCode.set(reason)
        lastErrorReason = reasonDesc
        lastErrorTimestampMs.set(System.currentTimeMillis())

        recordTiming(stage.uppercase(), durationMs, false, reason, reasonDesc)
        Log.e(TAG, "Wi-Fi Direct discovery failed at $stage ($reason): $reasonDesc")

        when (reason) {
            WifiP2pManager.BUSY -> {
                busyFailuresCount.incrementAndGet()
                HatDiagnostics.error("P2P_BUSY", mapOf(
                    "stage" to stage,
                    "durationMs" to durationMs,
                    "errorCode" to reason,
                    "message" to reasonDesc
                ))
            }
            WifiP2pManager.P2P_UNSUPPORTED -> {
                unsupportedFailuresCount.incrementAndGet()
                HatDiagnostics.error("P2P_UNSUPPORTED", mapOf(
                    "stage" to stage,
                    "durationMs" to durationMs,
                    "errorCode" to reason,
                    "message" to reasonDesc
                ))
            }
            WifiP2pManager.ERROR -> {
                errorFailuresCount.incrementAndGet()
                HatDiagnostics.error("P2P_ERROR", mapOf(
                    "stage" to stage,
                    "durationMs" to durationMs,
                    "errorCode" to reason,
                    "message" to reasonDesc
                ))
            }
            else -> {
                HatDiagnostics.error("P2P_DISCOVERY_FAILURE", mapOf(
                    "stage" to stage,
                    "durationMs" to durationMs,
                    "errorCode" to reason,
                    "message" to reasonDesc
                ))
            }
        }
    }

    /**
     * Advertises the local HAT Node via Wi-Fi Direct DNS-SD.
     */
    @SuppressLint("MissingPermission")
    fun advertiseNode(
        context: Context,
        node: NodeInfo = LocalNodeManager.getLocalNode(),
        port: Int = AudioConfig.DEFAULT_PORT
    ) {
        synchronized(lock) {
            if (_isAdvertising.value) {
                Log.d(TAG, "Wi-Fi Direct advertisement already active")
                return
            }
            if (!init(context)) return

            if (!hasPermissions(context)) {
                val permMsg = "Wi-Fi Direct permissions missing for service advertisement"
                Log.w(TAG, permMsg)
                recordTiming("ADVERTISE_NODE", 0L, false, WifiP2pManager.ERROR, permMsg)
                HatDiagnostics.error("P2P_ADVERTISE_FAILURE", mapOf("stage" to "permissions", "reason" to permMsg))
                return
            }

            val mgr = wifiP2pManager ?: return
            val ch = channel ?: return

            val startMs = System.currentTimeMillis()
            _state.value = WifiDirectDiscoveryState.INITIALIZING
            _statusMessage.value = "Registering local HAT DNS-SD service..."

            val suffix = if (node.id.length >= 6) node.id.takeLast(6) else "000000"
            val instanceName = "${node.name} ($suffix)"

            val txtRecord = mutableMapOf<String, String>()
            txtRecord[KEY_PROTOCOL_VERSION] = node.capabilities.protocolVersion.toString()
            txtRecord[KEY_NODE_ID] = node.id
            txtRecord[KEY_NODE_NAME] = node.name
            txtRecord[KEY_PORT] = port.toString()
            txtRecord[KEY_CAPABILITIES] = node.capabilities.toCapabilitiesMask().toString()
            txtRecord[KEY_AVAILABILITY] = node.state.name
            txtRecord[KEY_ROLE] = node.activeRole.name
            thisDeviceAddress?.let { txtRecord[KEY_P2P_MAC] = it }

            val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(instanceName, SERVICE_TYPE, txtRecord)
            activeLocalServiceInfo = serviceInfo

            HatDiagnostics.info("P2P_ADVERTISE_START", mapOf(
                "instanceName" to instanceName,
                "nodeId" to node.id,
                "port" to port
            ))

            // Clear previous local services first, then add the new one
            val clearStart = System.currentTimeMillis()
            mgr.clearLocalServices(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    recordTiming("CLEAR_LOCAL_SERVICES", System.currentTimeMillis() - clearStart, true)
                    addLocalService(mgr, ch, serviceInfo, instanceName, node.id, port, startMs)
                }

                override fun onFailure(reason: Int) {
                    val desc = describeErrorCode(reason)
                    recordTiming("CLEAR_LOCAL_SERVICES", System.currentTimeMillis() - clearStart, false, reason, desc)
                    addLocalService(mgr, ch, serviceInfo, instanceName, node.id, port, startMs)
                }
            })
        }
    }

    private fun addLocalService(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        serviceInfo: WifiP2pDnsSdServiceInfo,
        instanceName: String,
        nodeId: String,
        port: Int,
        overallStartMs: Long
    ) {
        val addStart = System.currentTimeMillis()
        mgr.addLocalService(ch, serviceInfo, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                val duration = System.currentTimeMillis() - addStart
                val totalDuration = System.currentTimeMillis() - overallStartMs
                _isAdvertising.value = true
                _state.value = WifiDirectDiscoveryState.ADVERTISING
                _statusMessage.value = "Advertised as $instanceName on port $port"
                recordTiming("ADD_LOCAL_SERVICE", duration, true)
                Log.i(TAG, "Wi-Fi Direct DNS-SD service registered in ${duration}ms: $instanceName")

                HatDiagnostics.info("P2P_ADVERTISE_SUCCESS", mapOf(
                    "instanceName" to instanceName,
                    "nodeId" to nodeId,
                    "port" to port,
                    "durationMs" to duration,
                    "totalDurationMs" to totalDuration
                ))
            }

            override fun onFailure(reason: Int) {
                val duration = System.currentTimeMillis() - addStart
                val desc = describeErrorCode(reason)
                _isAdvertising.value = false
                _state.value = WifiDirectDiscoveryState.ERROR
                _statusMessage.value = "Service advertisement failed ($reason): $desc"
                recordTiming("ADD_LOCAL_SERVICE", duration, false, reason, desc)
                Log.e(TAG, "Failed registering Wi-Fi Direct DNS-SD service ($reason): $desc")

                HatDiagnostics.error("P2P_ADVERTISE_FAILURE", mapOf(
                    "stage" to "addLocalService",
                    "errorCode" to reason,
                    "message" to desc,
                    "durationMs" to duration
                ))
            }
        })
    }

    /**
     * Stops Wi-Fi Direct service discovery and clears active service requests.
     */
    fun stopDiscovery() {
        synchronized(lock) {
            val mgr = wifiP2pManager
            val ch = channel
            if (mgr != null && ch != null && _isScanning.value) {
                val stopStart = System.currentTimeMillis()
                try {
                    mgr.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            recordTiming("STOP_PEER_DISCOVERY", System.currentTimeMillis() - stopStart, true)
                        }
                        override fun onFailure(reason: Int) {
                            recordTiming("STOP_PEER_DISCOVERY", System.currentTimeMillis() - stopStart, false, reason, describeErrorCode(reason))
                        }
                    })
                    mgr.clearServiceRequests(ch, null)
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping Wi-Fi Direct service discovery: ${e.message}")
                }
            }
            activeServiceRequest = null
            _isScanning.value = false
            if (_state.value == WifiDirectDiscoveryState.DISCOVERING) {
                _state.value = WifiDirectDiscoveryState.STOPPED
                _statusMessage.value = "Discovery stopped"
            }
        }
    }

    /**
     * Stops Wi-Fi Direct DNS-SD service advertisement and clears local services.
     */
    fun stopAdvertisement() {
        synchronized(lock) {
            val mgr = wifiP2pManager
            val ch = channel
            if (mgr != null && ch != null && _isAdvertising.value) {
                val clearStart = System.currentTimeMillis()
                try {
                    mgr.clearLocalServices(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            recordTiming("CLEAR_LOCAL_SERVICES", System.currentTimeMillis() - clearStart, true)
                        }
                        override fun onFailure(reason: Int) {
                            recordTiming("CLEAR_LOCAL_SERVICES", System.currentTimeMillis() - clearStart, false, reason, describeErrorCode(reason))
                        }
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "Error clearing local Wi-Fi Direct services: ${e.message}")
                }
            }
            activeLocalServiceInfo = null
            _isAdvertising.value = false
            if (_state.value == WifiDirectDiscoveryState.ADVERTISING) {
                _state.value = WifiDirectDiscoveryState.STOPPED
                _statusMessage.value = "Advertisement stopped"
            }
        }
    }

    /**
     * Stops all active discovery and advertisement activities cleanly.
     */
    fun stopAll() {
        stopDiscovery()
        stopAdvertisement()
    }

    /**
     * Cleans up all resources and unregisters the broadcast receiver.
     */
    fun cleanup(context: Context) {
        stopAll()
        synchronized(lock) {
            if (isReceiverRegistered && receiver != null) {
                try {
                    context.applicationContext.unregisterReceiver(receiver)
                } catch (ignored: Exception) {}
                isReceiverRegistered = false
                receiver = null
            }
            clearDiscoveredNodes()
            wifiP2pManager = null
            channel = null
            appContext = null
        }
    }

    /**
     * Clears all cached discovered nodes.
     */
    fun clearDiscoveredNodes() {
        nodesByNodeId.clear()
        macToNodeId.clear()
        pendingServicesByDeviceAddress.clear()
        pendingTxtRecordsByDeviceAddress.clear()
        publishDiscoveredNodes()
    }

    // ---------------------------------------------------------------------------------------------
    // DNS-SD Response Handling
    // ---------------------------------------------------------------------------------------------

    private fun handleDnsSdServiceAvailable(
        instanceName: String?,
        registrationType: String?,
        srcDevice: WifiP2pDevice?
    ) {
        if (srcDevice == null) return
        val deviceAddress = srcDevice.deviceAddress ?: return
        val inst = instanceName ?: "Unknown"
        val reg = registrationType ?: ""

        // Filter out self-discovery
        val localNode = LocalNodeManager.getLocalNode()
        val localMac = thisDeviceAddress
        if (deviceAddress.equals(localMac, ignoreCase = true) ||
            (inst.contains(localNode.id.takeLast(6)) && localNode.id.length >= 6)) {
            Log.d(TAG, "Ignoring self P2P DNS-SD service: $inst ($deviceAddress)")
            return
        }

        val now = System.currentTimeMillis()
        val scanStart = lastDiscoveryStartTimeMs.get()
        val latencyFromStart = if (scanStart > 0) now - scanStart else 0L

        if (servicesFoundCount.get() == 0L && scanStart > 0) {
            lastFirstServiceFoundDurationMs.set(latencyFromStart)
        }
        servicesFoundCount.incrementAndGet()

        Log.i(TAG, "P2P DNS-SD service found: '$inst' type='$reg' from ${srcDevice.deviceName} ($deviceAddress) in ${latencyFromStart}ms")

        HatDiagnostics.info("P2P_SERVICE_FOUND", mapOf(
            "instanceName" to inst,
            "registrationType" to reg,
            "deviceName" to srcDevice.deviceName,
            "deviceAddress" to deviceAddress,
            "latencyMs" to latencyFromStart
        ))

        val announcement = PendingServiceAnnouncement(
            deviceAddress = deviceAddress,
            deviceName = srcDevice.deviceName.ifBlank { inst },
            instanceName = inst,
            registrationType = reg,
            foundTimestampMs = now
        )
        pendingServicesByDeviceAddress[deviceAddress] = announcement

        // If TXT record for this device address was already cached, resolve immediately
        val cachedTxt = pendingTxtRecordsByDeviceAddress[deviceAddress]
        if (cachedTxt != null) {
            completeResolution(announcement, cachedTxt, srcDevice)
        }
    }

    private fun handleDnsSdTxtRecordAvailable(
        fullDomainName: String?,
        txtRecord: Map<String, String>?,
        srcDevice: WifiP2pDevice?
    ) {
        if (srcDevice == null || txtRecord == null) return
        val deviceAddress = srcDevice.deviceAddress ?: return

        // Filter out self-discovery
        val localNode = LocalNodeManager.getLocalNode()
        val localMac = thisDeviceAddress
        val recordNodeId = txtRecord[KEY_NODE_ID]
        if (deviceAddress.equals(localMac, ignoreCase = true) || recordNodeId == localNode.id) {
            Log.d(TAG, "Ignoring self P2P DNS-SD TXT record: $deviceAddress ($recordNodeId)")
            return
        }

        pendingTxtRecordsByDeviceAddress[deviceAddress] = txtRecord

        val pending = pendingServicesByDeviceAddress[deviceAddress]
        if (pending != null) {
            completeResolution(pending, txtRecord, srcDevice)
        } else {
            // Service listener hasn't fired yet; create provisional announcement
            val provisional = PendingServiceAnnouncement(
                deviceAddress = deviceAddress,
                deviceName = srcDevice.deviceName.ifBlank { "P2P Node" },
                instanceName = fullDomainName ?: "Unknown",
                registrationType = SERVICE_TYPE,
                foundTimestampMs = System.currentTimeMillis()
            )
            completeResolution(provisional, txtRecord, srcDevice)
        }
    }

    private fun completeResolution(
        announcement: PendingServiceAnnouncement,
        txtRecord: Map<String, String>,
        srcDevice: WifiP2pDevice
    ) {
        val now = System.currentTimeMillis()
        val scanStart = lastDiscoveryStartTimeMs.get()
        val totalLatencyMs = if (scanStart > 0) now - scanStart else 0L
        val txtLatencyMs = now - announcement.foundTimestampMs

        if (servicesResolvedCount.get() == 0L && scanStart > 0) {
            lastFirstServiceResolvedDurationMs.set(totalLatencyMs)
        }
        servicesResolvedCount.incrementAndGet()

        // Extract stable Node ID from TXT record.
        // NEVER use Wi-Fi Direct MAC address as Node ID.
        val nodeId = txtRecord[KEY_NODE_ID]
            ?: "${NodeIdentity.ID_PREFIX}p2p-${announcement.deviceAddress.replace(":", "-").lowercase()}"

        val nodeName = txtRecord[KEY_NODE_NAME]?.ifBlank { null }
            ?: announcement.deviceName.ifBlank { announcement.instanceName.replace(Regex("\\s*\\([a-f0-9]{6}\\)$"), "") }

        val protoVer = txtRecord[KEY_PROTOCOL_VERSION]?.toIntOrNull() ?: 1
        val capsMask = txtRecord[KEY_CAPABILITIES]?.toIntOrNull() ?: 0
        val port = txtRecord[KEY_PORT]?.toIntOrNull() ?: AudioConfig.DEFAULT_PORT

        val caps = if (capsMask != 0) {
            NodeCapabilities.fromCapabilitiesMask(capsMask, protocolVersion = protoVer)
        } else {
            NodeCapabilities(protocolVersion = protoVer)
        }

        val state = when (txtRecord[KEY_AVAILABILITY]?.uppercase()) {
            "ACTIVE_STREAMING", "BUSY" -> NodeState.ACTIVE_STREAMING
            "IDLE" -> NodeState.IDLE
            "OFFLINE" -> NodeState.OFFLINE
            else -> NodeState.AVAILABLE
        }

        val role = when (txtRecord[KEY_ROLE]?.uppercase()) {
            "SENDER" -> StreamRole.SENDER
            "RECEIVER" -> StreamRole.RECEIVER
            "TRANSCEIVER" -> StreamRole.TRANSCEIVER
            else -> StreamRole.IDLE
        }

        val nodeInfo = NodeInfo(
            identity = NodeIdentity(id = nodeId, name = nodeName),
            capabilities = caps,
            deviceInfo = DevicePlatformInfo(model = nodeName),
            state = state,
            activeRole = role,
            lastSeenEpochMs = now
        )

        val discoveredNode = WifiDirectDiscoveredNode(
            nodeInfo = nodeInfo,
            deviceAddress = announcement.deviceAddress,
            deviceName = announcement.deviceName,
            serviceType = announcement.registrationType,
            txtRecord = txtRecord,
            firstDiscoveredEpochMs = nodesByNodeId[nodeId]?.firstDiscoveredEpochMs ?: now,
            lastSeenEpochMs = now,
            serviceFoundLatencyMs = announcement.foundTimestampMs - scanStart,
            txtResolvedLatencyMs = txtLatencyMs
        )

        nodesByNodeId[nodeId] = discoveredNode
        macToNodeId[announcement.deviceAddress] = nodeId
        publishDiscoveredNodes()

        Log.i(TAG, "Wi-Fi Direct HAT Node resolved: '$nodeName' ($nodeId) MAC=${announcement.deviceAddress} (found=${discoveredNode.serviceFoundLatencyMs}ms, txt=${txtLatencyMs}ms, total=${totalLatencyMs}ms)")

        HatDiagnostics.info("P2P_SERVICE_RESOLVED", mapOf(
            "nodeId" to nodeId,
            "nodeName" to nodeName,
            "deviceAddress" to announcement.deviceAddress,
            "port" to port,
            "capabilities" to caps.describe(),
            "totalLatencyMs" to totalLatencyMs,
            "serviceFoundLatencyMs" to discoveredNode.serviceFoundLatencyMs,
            "txtResolvedLatencyMs" to txtLatencyMs
        ))
    }

    // ---------------------------------------------------------------------------------------------
    // Peer Disappearance Detection
    // ---------------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun checkPeerDisappearance() {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        try {
            mgr.requestPeers(ch) { peers: WifiP2pDeviceList? ->
                if (peers == null) return@requestPeers
                val activeMacs = peers.deviceList.filter { dev ->
                    dev.status != WifiP2pDevice.FAILED && dev.status != WifiP2pDevice.UNAVAILABLE
                }.map { it.deviceAddress }.toSet()

                val toRemove = mutableListOf<WifiDirectDiscoveredNode>()
                for (node in nodesByNodeId.values) {
                    if (node.deviceAddress !in activeMacs) {
                        toRemove.add(node)
                    }
                }

                for (removed in toRemove) {
                    removePeer(removed, "Disappeared from radio scan results")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking peer disappearance: ${e.message}")
        }
    }

    private fun handleP2pDisabled() {
        Log.w(TAG, "Wi-Fi Direct disabled by system; purging all discovered peers")
        val toRemove = nodesByNodeId.values.toList()
        for (node in toRemove) {
            removePeer(node, "Wi-Fi P2P disabled")
        }
    }

    /**
     * Prunes peers that have not been refreshed within [maxAgeMs].
     */
    fun pruneStalePeers(maxAgeMs: Long = 45_000L) {
        val now = System.currentTimeMillis()
        val toRemove = nodesByNodeId.values.filter { now - it.lastSeenEpochMs > maxAgeMs }
        for (stale in toRemove) {
            removePeer(stale, "Peer TTL expired (${maxAgeMs}ms timeout)")
        }
    }

    private fun removePeer(peer: WifiDirectDiscoveredNode, reason: String) {
        nodesByNodeId.remove(peer.nodeInfo.id)
        macToNodeId.remove(peer.deviceAddress)
        pendingServicesByDeviceAddress.remove(peer.deviceAddress)
        pendingTxtRecordsByDeviceAddress.remove(peer.deviceAddress)
        peersLostCount.incrementAndGet()

        val activeDurationMs = System.currentTimeMillis() - peer.firstDiscoveredEpochMs
        Log.i(TAG, "P2P Peer lost: ${peer.nodeInfo.name} (${peer.nodeInfo.id}) - $reason (active: ${activeDurationMs}ms)")

        HatDiagnostics.info("P2P_PEER_LOST", mapOf(
            "nodeId" to peer.nodeInfo.id,
            "deviceAddress" to peer.deviceAddress,
            "reason" to reason,
            "activeDurationMs" to activeDurationMs
        ))

        publishDiscoveredNodes()
    }

    private fun publishDiscoveredNodes() {
        val endpoints = nodesByNodeId.values.toList()
        val nodes = endpoints.map { it.nodeInfo }
        _discoveredEndpoints.value = endpoints
        _discoveredNodes.value = nodes
    }

    // ---------------------------------------------------------------------------------------------
    // Connection Attempt Instrumentation
    // ---------------------------------------------------------------------------------------------

    /**
     * Records the duration and result of a Wi-Fi Direct connection attempt for diagnostics.
     */
    fun recordConnectionAttempt(
        targetNodeId: String,
        targetAddress: String,
        durationMs: Long,
        success: Boolean,
        reason: String? = null
    ) {
        val detail = "$targetNodeId ($targetAddress) ${reason ?: if (success) "SUCCESS" else "FAILED"}"
        recordTiming("CONNECT_ATTEMPT", durationMs, success, if (success) null else 0, detail)
        Log.i(TAG, "Connection attempt to $detail took ${durationMs}ms (success=$success)")

        HatDiagnostics.info("P2P_CONNECTION_DURATION", mapOf(
            "targetNodeId" to targetNodeId,
            "targetAddress" to targetAddress,
            "durationMs" to durationMs,
            "success" to success,
            "reason" to (reason ?: if (success) "SUCCESS" else "FAILED")
        ))
    }

    // ---------------------------------------------------------------------------------------------
    // Operation Timing & Diagnostics
    // ---------------------------------------------------------------------------------------------

    private fun recordTiming(
        operation: String,
        durationMs: Long,
        success: Boolean,
        errorCode: Int? = null,
        details: String? = null
    ) {
        val timing = P2pOperationTiming(
            operation = operation,
            durationMs = durationMs,
            success = success,
            errorCode = errorCode,
            details = details
        )
        recentOperations.add(timing)
        while (recentOperations.size > MAX_OPERATION_LOG_SIZE) {
            recentOperations.poll()
        }
    }

    /**
     * Detailed human-readable description for Android [WifiP2pManager] failure error codes.
     */
    fun describeErrorCode(errorCode: Int): String = when (errorCode) {
        WifiP2pManager.ERROR -> "ERROR (0): Internal framework error (verify Wi-Fi is ON, Location is ON, and permissions are granted)"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED (1): Wi-Fi Direct hardware or driver unsupported or disabled on device"
        WifiP2pManager.BUSY -> "BUSY (2): Supplicant framework is busy (channel hopping, scanning, or group state transition in progress)"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS (3): No service discovery requests registered with framework"
        else -> "UNKNOWN_ERROR_CODE_$errorCode"
    }

    /**
     * Returns a structured diagnostic snapshot of Wi-Fi Direct discovery state and metrics.
     */
    fun getDiagnosticsSnapshot(): Map<String, Any?> = linkedMapOf(
        "serviceType" to SERVICE_TYPE,
        "state" to state.value.name,
        "isScanning" to isScanning.value,
        "isAdvertising" to isAdvertising.value,
        "statusMessage" to statusMessage.value,
        "localDeviceAddress" to (thisDeviceAddress ?: "Unknown"),
        "discoveredNodesCount" to nodesByNodeId.size,
        "nodes" to nodesByNodeId.values.map {
            "${it.nodeInfo.name} (${it.nodeInfo.id}) MAC=${it.deviceAddress} [${it.nodeInfo.state}] latency: found=${it.serviceFoundLatencyMs}ms, txt=${it.txtResolvedLatencyMs}ms"
        },
        "metrics" to linkedMapOf(
            "starts" to discoveryStartsCount.get(),
            "successes" to discoverySuccessesCount.get(),
            "failures" to discoveryFailuresCount.get(),
            "busyCount" to busyFailuresCount.get(),
            "unsupportedCount" to unsupportedFailuresCount.get(),
            "errorCount" to errorFailuresCount.get(),
            "servicesFound" to servicesFoundCount.get(),
            "servicesResolved" to servicesResolvedCount.get(),
            "peersLost" to peersLostCount.get(),
            "lastAcceptanceDurationMs" to lastDiscoveryAcceptanceDurationMs.get(),
            "lastFirstFoundDurationMs" to lastFirstServiceFoundDurationMs.get(),
            "lastFirstResolvedDurationMs" to lastFirstServiceResolvedDurationMs.get()
        ),
        "lastError" to if (lastErrorCode.get() != -1) {
            "code=${lastErrorCode.get()}, reason=${lastErrorReason}, at=${lastErrorTimestampMs.get()}ms"
        } else "None",
        "recentOperations" to recentOperations.toList().takeLast(10).map {
            "${it.operation} (${if (it.success) "OK" else "FAIL"}${it.errorCode?.let { c -> " code=$c" } ?: ""}) in ${it.durationMs}ms - ${it.details ?: ""}"
        }
    )

    fun getNodeForDeviceAddress(deviceAddress: String): NodeInfo? =
        nodesByNodeId.values.firstOrNull { it.deviceAddress.equals(deviceAddress, ignoreCase = true) }?.nodeInfo

    fun getNodeForId(nodeId: String): NodeInfo? = nodesByNodeId[nodeId]?.nodeInfo

    fun getDiscoveredEndpointForNodeId(nodeId: String): WifiDirectDiscoveredNode? = nodesByNodeId[nodeId]

    fun getDiscoveredEndpointForDeviceAddress(deviceAddress: String): WifiDirectDiscoveredNode? =
        nodesByNodeId.values.firstOrNull { it.deviceAddress.equals(deviceAddress, ignoreCase = true) }
}
