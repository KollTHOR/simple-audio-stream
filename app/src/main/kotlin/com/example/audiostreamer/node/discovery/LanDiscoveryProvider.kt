package com.example.audiostreamer.node.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.example.audiostreamer.AudioConfig
import com.example.audiostreamer.HatDiagnostics
import com.example.audiostreamer.NetworkUtils
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resolved endpoint and metadata for a HAT Node discovered via LAN DNS-SD.
 */
data class LanDiscoveredNode(
    val nodeInfo: NodeInfo,
    val hostAddress: String,
    val port: Int,
    val serviceName: String,
    val serviceType: String = LanDiscoveryProvider.SERVICE_TYPE,
    val txtRecord: Map<String, String> = emptyMap(),
    val resolvedAtEpochMs: Long = System.currentTimeMillis()
)

enum class LanDiscoveryState {
    STOPPED,
    STARTING,
    DISCOVERING,
    STOPPING,
    ERROR
}

/**
 * Dedicated DNS-SD / mDNS LAN Discovery Provider for HAT Nodes using Android [NsdManager].
 *
 * Service: `_hats._tcp`
 *
 * Responsibilities:
 * - Advertises local HAT node with compact metadata (protocol version, node ID, node name, listen port, caps, availability)
 * - Discovers remote HAT nodes without establishing audio streams
 * - Resolves services sequentially to prevent Android `FAILURE_ALREADY_ACTIVE` errors
 * - De-duplicates nodes by stable Node ID
 * - Updates changed node metadata
 * - Handles service disappearance cleanly
 * - Handles Android 16 local-network access failures explicitly with diagnostics
 */
object LanDiscoveryProvider {
    private const val TAG = "LanDiscoveryProvider"

    const val SERVICE_TYPE = "_hats._tcp"
    const val KEY_PROTOCOL_VERSION = "pv"
    const val KEY_NODE_ID = "id"
    const val KEY_NODE_NAME = "name"
    const val KEY_PORT = "port"
    const val KEY_CAPABILITIES = "caps"
    const val KEY_AVAILABILITY = "avail"
    const val KEY_ROLE = "role"

    private val lock = Any()

    private val _state = MutableStateFlow(LanDiscoveryState.STOPPED)
    val state: StateFlow<LanDiscoveryState> = _state.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _discoveredNodes = MutableStateFlow<List<NodeInfo>>(emptyList())
    val discoveredNodes: StateFlow<List<NodeInfo>> = _discoveredNodes.asStateFlow()

    private val _discoveredEndpoints = MutableStateFlow<List<LanDiscoveredNode>>(emptyList())
    val discoveredEndpoints: StateFlow<List<LanDiscoveredNode>> = _discoveredEndpoints.asStateFlow()

    // Internal registry keyed by Node ID
    private val nodesByNodeId = ConcurrentHashMap<String, LanDiscoveredNode>()
    // Mapping from DNS-SD service name to Node ID for fast disappearance removal
    private val serviceNameToNodeId = ConcurrentHashMap<String, String>()

    @Volatile private var nsdManager: NsdManager? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var registeredServiceName: String? = null

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    private val isDiscoveryRegistered = AtomicBoolean(false)
    private val isRegistrationActive = AtomicBoolean(false)

    // Sequential resolution queue to prevent Android NsdManager.FAILURE_ALREADY_ACTIVE
    private var workerScope: CoroutineScope? = null
    private var resolveQueue: Channel<NsdServiceInfo>? = null
    private var resolveWorkerJob: Job? = null

    /**
     * Starts DNS-SD discovery for remote `_hats._tcp` services.
     */
    fun startDiscovery(context: Context) {
        synchronized(lock) {
            if (!NetworkUtils.isLanAvailable(context)) {
                _state.value = LanDiscoveryState.STOPPED
                _statusMessage.value = "LAN unavailable (Offline)"
                Log.d(TAG, "Cannot start LAN discovery: LAN unavailable")
                return
            }
            if (_isScanning.value || isDiscoveryRegistered.get()) {
                Log.d(TAG, "Discovery already active or registered")
                return
            }

            _state.value = LanDiscoveryState.STARTING
            _statusMessage.value = "Starting LAN discovery..."

            val appContext = context.applicationContext
            val mgr = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (mgr == null) {
                val errorMsg = "NsdManager not available on this device"
                _state.value = LanDiscoveryState.ERROR
                _statusMessage.value = errorMsg
                HatDiagnostics.error("LAN_DISCOVERY_ERROR", mapOf("stage" to "init", "message" to errorMsg))
                return
            }
            nsdManager = mgr

            // Acquire MulticastLock safely (required on some hardware to receive mDNS packets)
            acquireMulticastLock(appContext)

            // Start sequential resolve worker
            startResolveWorker()

            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    _isScanning.value = true
                    _state.value = LanDiscoveryState.DISCOVERING
                    _statusMessage.value = "Scanning for $regType"
                    Log.i(TAG, "LAN discovery started for $regType")
                    HatDiagnostics.info("LAN_DISCOVERY_STARTED", mapOf(
                        "serviceType" to regType,
                        "timestamp" to System.currentTimeMillis()
                    ))
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    val foundName = serviceInfo.serviceName
                    val foundType = serviceInfo.serviceType
                    Log.i(TAG, "LAN service found: $foundName (type=$foundType)")
                    HatDiagnostics.info("LAN_SERVICE_FOUND", mapOf(
                        "serviceName" to foundName,
                        "serviceType" to foundType
                    ))

                    // Ignore own advertised service
                    val myRegistered = registeredServiceName
                    val localNode = LocalNodeManager.getLocalNode()
                    if (foundName == myRegistered || (foundName.contains(localNode.id.takeLast(6)) && localNode.id.length >= 6)) {
                        Log.d(TAG, "Ignoring self service announcement: $foundName")
                        return
                    }

                    // Enqueue for serial resolution
                    resolveQueue?.trySend(serviceInfo)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    val lostName = serviceInfo.serviceName
                    Log.i(TAG, "LAN service lost: $lostName")
                    val removed = removeNodeByServiceName(lostName)
                    HatDiagnostics.info("LAN_SERVICE_LOST", mapOf(
                        "serviceName" to lostName,
                        "nodeId" to (removed?.nodeInfo?.id ?: "unknown")
                    ))
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    _isScanning.value = false
                    _state.value = LanDiscoveryState.STOPPED
                    _statusMessage.value = "Discovery stopped"
                    isDiscoveryRegistered.set(false)
                    Log.i(TAG, "LAN discovery stopped for $serviceType")
                    HatDiagnostics.info("LAN_DISCOVERY_STOPPED", mapOf("serviceType" to serviceType))
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    _isScanning.value = false
                    _state.value = LanDiscoveryState.ERROR
                    isDiscoveryRegistered.set(false)
                    val errorDesc = describeErrorCode(errorCode)
                    _statusMessage.value = "Start discovery failed: $errorDesc"
                    Log.e(TAG, "Start discovery failed ($errorCode): $errorDesc")
                    HatDiagnostics.error("LAN_DISCOVERY_ERROR", mapOf(
                        "stage" to "start_discovery",
                        "errorCode" to errorCode,
                        "message" to errorDesc
                    ))
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    _state.value = LanDiscoveryState.ERROR
                    val errorDesc = describeErrorCode(errorCode)
                    _statusMessage.value = "Stop discovery failed: $errorDesc"
                    Log.e(TAG, "Stop discovery failed ($errorCode): $errorDesc")
                    HatDiagnostics.error("LAN_DISCOVERY_ERROR", mapOf(
                        "stage" to "stop_discovery",
                        "errorCode" to errorCode,
                        "message" to errorDesc
                    ))
                }
            }

            discoveryListener = listener

            try {
                mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                isDiscoveryRegistered.set(true)
            } catch (e: SecurityException) {
                _state.value = LanDiscoveryState.ERROR
                val permMsg = "Local-network access permission denied (Android 16): ${e.message}"
                _statusMessage.value = permMsg
                Log.e(TAG, permMsg, e)
                HatDiagnostics.error("LAN_DISCOVERY_ERROR", mapOf("stage" to "discoverServices", "reason" to "SecurityException", "message" to (e.message ?: "")))
            } catch (e: Exception) {
                _state.value = LanDiscoveryState.ERROR
                val errMsg = "Failed starting discovery: ${e.message}"
                _statusMessage.value = errMsg
                Log.e(TAG, errMsg, e)
                HatDiagnostics.error("LAN_DISCOVERY_ERROR", mapOf("stage" to "discoverServices", "message" to errMsg), e)
            }
        }
    }

    /**
     * Advertises the local HAT Node via DNS-SD.
     */
    fun advertiseNode(
        context: Context,
        node: NodeInfo = LocalNodeManager.getLocalNode(),
        port: Int = AudioConfig.DEFAULT_PORT
    ) {
        synchronized(lock) {
            if (!NetworkUtils.isLanAvailable(context)) {
                Log.d(TAG, "Skipping DNS-SD node advertisement: LAN unavailable")
                return
            }
            if (_isAdvertising.value || isRegistrationActive.get()) {
                Log.d(TAG, "Advertisement already active, skipping re-registration")
                return
            }

            val appContext = context.applicationContext
            val mgr = nsdManager ?: (appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager)?.also {
                nsdManager = it
            }
            if (mgr == null) {
                Log.w(TAG, "NsdManager not available for service advertisement")
                return
            }

            val suffix = if (node.id.length >= 6) node.id.takeLast(6) else "000000"
            val initialServiceName = "${node.name} ($suffix)"

            val serviceInfo = NsdServiceInfo().apply {
                serviceType = SERVICE_TYPE
                serviceName = initialServiceName
                this.port = port
                setAttribute(KEY_PROTOCOL_VERSION, node.capabilities.protocolVersion.toString())
                setAttribute(KEY_NODE_ID, node.id)
                setAttribute(KEY_NODE_NAME, node.name)
                setAttribute(KEY_PORT, port.toString())
                setAttribute(KEY_CAPABILITIES, node.capabilities.toCapabilitiesMask().toString())
                setAttribute(KEY_AVAILABILITY, node.state.name)
                setAttribute(KEY_ROLE, node.activeRole.name)
            }

            val regListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(regInfo: NsdServiceInfo) {
                    registeredServiceName = regInfo.serviceName
                    _isAdvertising.value = true
                    isRegistrationActive.set(true)
                    Log.i(TAG, "Local node advertised via DNS-SD: ${regInfo.serviceName} on port ${regInfo.port}")
                    HatDiagnostics.info("LAN_ADVERTISE_REGISTERED", mapOf(
                        "serviceName" to regInfo.serviceName,
                        "nodeId" to node.id,
                        "port" to port
                    ))
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    _isAdvertising.value = false
                    isRegistrationActive.set(false)
                    val errorDesc = describeErrorCode(errorCode)
                    Log.e(TAG, "Service registration failed ($errorCode): $errorDesc")
                    HatDiagnostics.error("LAN_DISCOVERY_ERROR", mapOf(
                        "stage" to "registerService",
                        "errorCode" to errorCode,
                        "message" to errorDesc
                    ))
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                    _isAdvertising.value = false
                    isRegistrationActive.set(false)
                    registeredServiceName = null
                    Log.i(TAG, "Local node service unregistered: ${serviceInfo.serviceName}")
                    HatDiagnostics.info("LAN_ADVERTISE_UNREGISTERED", mapOf(
                        "serviceName" to serviceInfo.serviceName
                    ))
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    val errorDesc = describeErrorCode(errorCode)
                    Log.e(TAG, "Service unregistration failed ($errorCode): $errorDesc")
                    HatDiagnostics.error("LAN_DISCOVERY_ERROR", mapOf(
                        "stage" to "unregisterService",
                        "errorCode" to errorCode,
                        "message" to errorDesc
                    ))
                }
            }

            registrationListener = regListener

            try {
                mgr.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, regListener)
            } catch (e: SecurityException) {
                val permMsg = "Local network access denied during service advertisement (Android 16): ${e.message}"
                Log.e(TAG, permMsg, e)
                HatDiagnostics.error("LAN_DISCOVERY_ERROR", mapOf("stage" to "registerService", "reason" to "SecurityException", "message" to (e.message ?: "")))
            } catch (e: Exception) {
                Log.e(TAG, "Failed advertising service: ${e.message}", e)
                HatDiagnostics.error("LAN_DISCOVERY_ERROR", mapOf("stage" to "registerService", "message" to (e.message ?: "")), e)
            }
        }
    }

    /**
     * Stops DNS-SD service advertisement.
     */
    fun stopAdvertisement() {
        synchronized(lock) {
            val mgr = nsdManager
            val listener = registrationListener
            if (mgr != null && listener != null && isRegistrationActive.get()) {
                try {
                    mgr.unregisterService(listener)
                } catch (e: Exception) {
                    Log.w(TAG, "Error unregistering service: ${e.message}")
                }
            }
            registrationListener = null
            isRegistrationActive.set(false)
            _isAdvertising.value = false
            registeredServiceName = null
        }
    }

    /**
     * Stops DNS-SD discovery cleanly and releases system resources.
     */
    fun stopDiscovery() {
        synchronized(lock) {
            val mgr = nsdManager
            val listener = discoveryListener
            if (mgr != null && listener != null && isDiscoveryRegistered.get()) {
                try {
                    mgr.stopServiceDiscovery(listener)
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping service discovery: ${e.message}")
                }
            }
            discoveryListener = null
            isDiscoveryRegistered.set(false)
            _isScanning.value = false
            _state.value = LanDiscoveryState.STOPPED

            stopResolveWorker()
            releaseMulticastLock()
        }
    }

    /**
     * Stops all discovery and advertisement activities.
     */
    fun stopAll() {
        stopDiscovery()
        stopAdvertisement()
    }

    /**
     * Clears cached discovered nodes.
     */
    fun clearDiscoveredNodes() {
        nodesByNodeId.clear()
        serviceNameToNodeId.clear()
        publishDiscoveredNodes()
    }

    private fun startResolveWorker() {
        stopResolveWorker()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        workerScope = scope
        val channel = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        resolveQueue = channel

        resolveWorkerJob = scope.launch {
            for (serviceInfo in channel) {
                if (!isActive) break
                resolveServiceSequentially(serviceInfo)
            }
        }
    }

    private fun stopResolveWorker() {
        resolveWorkerJob?.cancel()
        resolveWorkerJob = null
        resolveQueue?.close()
        resolveQueue = null
        workerScope = null
    }

    private suspend fun resolveServiceSequentially(serviceInfo: NsdServiceInfo) {
        val mgr = nsdManager ?: return
        val lockResolve = kotlinx.coroutines.CompletableDeferred<Boolean>()

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(failedInfo: NsdServiceInfo, errorCode: Int) {
                val errorDesc = describeErrorCode(errorCode)
                Log.w(TAG, "Resolve failed for ${failedInfo.serviceName} ($errorCode): $errorDesc")
                HatDiagnostics.error("LAN_DISCOVERY_ERROR", mapOf(
                    "stage" to "resolveService",
                    "serviceName" to failedInfo.serviceName,
                    "errorCode" to errorCode,
                    "message" to errorDesc
                ))
                lockResolve.complete(false)
            }

            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                try {
                    handleServiceResolved(resolvedInfo)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing resolved service ${resolvedInfo.serviceName}: ${e.message}", e)
                }
                lockResolve.complete(true)
            }
        }

        try {
            mgr.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Exception initiating resolveService for ${serviceInfo.serviceName}: ${e.message}", e)
            lockResolve.complete(false)
        }

        // Wait up to 4 seconds for response; prevent hanging the queue
        withTimeoutOrNull(4000L) {
            lockResolve.await()
        }
    }

    private fun handleServiceResolved(resolvedInfo: NsdServiceInfo) {
        val host = resolvedInfo.host
        val hostAddress = host?.hostAddress ?: return
        val port = if (resolvedInfo.port > 0) resolvedInfo.port else AudioConfig.DEFAULT_PORT

        val localNode = LocalNodeManager.getLocalNode()
        val localIps = NetworkUtils.getAllLocalIpAddresses().toSet()

        val txtAttributes = mutableMapOf<String, String>()
        resolvedInfo.attributes?.forEach { (k, v) ->
            txtAttributes[k.lowercase()] = String(v, StandardCharsets.UTF_8)
        }

        val nodeId = txtAttributes[KEY_NODE_ID]
            ?: "${NodeIdentity.ID_PREFIX}nsd-${hostAddress.replace(".", "-")}"

        // Filter out self-discovery
        if (nodeId == localNode.id || (hostAddress in localIps && nodeId.startsWith(localNode.id))) {
            Log.d(TAG, "Ignoring self node resolution: $nodeId ($hostAddress)")
            return
        }

        val nodeName = txtAttributes[KEY_NODE_NAME]?.ifBlank { null }
            ?: resolvedInfo.serviceName.replace(Regex("\\s*\\([a-f0-9]{6}\\)$"), "")
        val protoVer = txtAttributes[KEY_PROTOCOL_VERSION]?.toIntOrNull() ?: 1
        val capsMask = txtAttributes[KEY_CAPABILITIES]?.toIntOrNull() ?: 0

        val caps = if (capsMask != 0) {
            NodeCapabilities.fromCapabilitiesMask(capsMask, protocolVersion = protoVer)
        } else {
            NodeCapabilities(protocolVersion = protoVer)
        }

        val state = when (txtAttributes[KEY_AVAILABILITY]?.uppercase()) {
            "ACTIVE_STREAMING", "BUSY" -> NodeState.ACTIVE_STREAMING
            "IDLE" -> NodeState.IDLE
            "OFFLINE" -> NodeState.OFFLINE
            else -> NodeState.AVAILABLE
        }

        val role = when (txtAttributes[KEY_ROLE]?.uppercase()) {
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
            lastSeenEpochMs = System.currentTimeMillis()
        )

        val discoveredNode = LanDiscoveredNode(
            nodeInfo = nodeInfo,
            hostAddress = hostAddress,
            port = port,
            serviceName = resolvedInfo.serviceName,
            txtRecord = txtAttributes,
            resolvedAtEpochMs = System.currentTimeMillis()
        )

        // De-duplicate and update existing entry
        nodesByNodeId[nodeId] = discoveredNode
        serviceNameToNodeId[resolvedInfo.serviceName] = nodeId
        publishDiscoveredNodes()

        Log.i(TAG, "LAN Node resolved: $nodeName ($nodeId) at $hostAddress:$port [state=${state.name}]")
        HatDiagnostics.info("LAN_NODE_RESOLVED", mapOf(
            "serviceName" to resolvedInfo.serviceName,
            "nodeId" to nodeId,
            "host" to hostAddress,
            "port" to port,
            "capabilities" to caps.describe(),
            "state" to state.name
        ))
    }

    private fun removeNodeByServiceName(serviceName: String): LanDiscoveredNode? {
        val nodeId = serviceNameToNodeId.remove(serviceName)
        val removed = if (nodeId != null) {
            nodesByNodeId.remove(nodeId)
        } else {
            val entry = nodesByNodeId.entries.firstOrNull { it.value.serviceName == serviceName }
            if (entry != null) {
                nodesByNodeId.remove(entry.key)
            } else null
        }

        if (removed != null) {
            publishDiscoveredNodes()
        }
        return removed
    }

    private fun publishDiscoveredNodes() {
        val endpoints = nodesByNodeId.values.toList()
        val nodes = endpoints.map { it.nodeInfo }
        _discoveredEndpoints.value = endpoints
        _discoveredNodes.value = nodes
    }

    private fun acquireMulticastLock(context: Context) {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("HatLanDiscovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "Acquired Wi-Fi MulticastLock for DNS-SD discovery")
        } catch (e: Exception) {
            Log.w(TAG, "Failed acquiring MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed releasing MulticastLock: ${e.message}")
        }
        multicastLock = null
    }

    fun describeErrorCode(errorCode: Int): String = when (errorCode) {
        NsdManager.FAILURE_INTERNAL_ERROR -> "FAILURE_INTERNAL_ERROR (check Android 16 local network access permissions and Wi-Fi state)"
        NsdManager.FAILURE_ALREADY_ACTIVE -> "FAILURE_ALREADY_ACTIVE (operation already in-flight)"
        NsdManager.FAILURE_MAX_LIMIT -> "FAILURE_MAX_LIMIT (system max client listeners reached)"
        else -> "UNKNOWN_ERROR_CODE_$errorCode"
    }

    fun getDiagnosticsSnapshot(): Map<String, Any?> = linkedMapOf(
        "serviceType" to SERVICE_TYPE,
        "isScanning" to isScanning.value,
        "isAdvertising" to isAdvertising.value,
        "registeredServiceName" to (registeredServiceName ?: "None"),
        "discoveredNodesCount" to nodesByNodeId.size,
        "state" to state.value.name,
        "statusMessage" to statusMessage.value,
        "nodes" to nodesByNodeId.values.map {
            "${it.nodeInfo.name} (${it.nodeInfo.id}) at ${it.hostAddress}:${it.port} [${it.nodeInfo.state}]"
        }
    )
}
