package com.example.audiostreamer.node.discovery

import com.example.audiostreamer.HatDiagnostics
import com.example.audiostreamer.node.DevicePlatformInfo
import com.example.audiostreamer.node.HatLinkManager
import com.example.audiostreamer.node.LinkState
import com.example.audiostreamer.node.LocalNodeManager
import com.example.audiostreamer.node.NodeCapabilities
import com.example.audiostreamer.node.NodeIdentity
import com.example.audiostreamer.node.NodeInfo
import com.example.audiostreamer.node.NodeState
import com.example.audiostreamer.node.NodeTransportType
import com.example.audiostreamer.node.StreamRole
import com.example.audiostreamer.AppLogger as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

// ─────────────────────────────────────────────────────────────────────────────
// Public Enums & Data Models
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Originating discovery backend/mechanism.
 */
enum class DiscoverySource {
    LAN,
    WIFI_DIRECT,
    WIFI_AWARE,
    BLE,
    NFC
}

/**
 * Resolved network endpoint associated with a discovered HAT Node.
 */
data class DiscoveredEndpoint(
    val transportType: NodeTransportType,
    val address: String? = null,
    val port: Int? = null,
    val description: String = "",
    val details: Map<String, Any?> = emptyMap(),
    val lastSeenEpochMs: Long = System.currentTimeMillis()
)

/**
 * Unified, deduplicated entry representing a single discovered HAT Node in the central registry.
 *
 * **Architecture Invariants:**
 * - A physical Node appears exactly once in the registry, even if discovered through multiple mechanisms
 *   (LAN, BLE, Wi-Fi Direct, Wi-Fi Aware, NFC).
 * - [identity].[NodeIdentity.id] is the stable HAT Node ID (`hat-node-...`). It is NEVER overwritten with
 *   a transport-specific identifier (IP address, MAC address, PeerHandle, or NFC tag UID).
 * - Tracks [discoverySources] as a set of all mechanisms that have reported this node.
 * - [connectionState] reflects live link activity in [HatLinkManager], preventing duplicate connections.
 */
data class DiscoveredNodeEntry(
    val identity: NodeIdentity,
    val nodeInfo: NodeInfo,
    val discoverySources: Set<DiscoverySource>,
    val firstSeenEpochMs: Long,
    val lastSeenEpochMs: Long,
    val transportCandidates: Set<NodeTransportType>,
    val rssi: Int? = null,
    val resolvedEndpoints: List<DiscoveredEndpoint> = emptyList(),
    val availability: NodeState = NodeState.AVAILABLE,
    val connectionState: LinkState = LinkState.DISCONNECTED
) {
    val id: String get() = identity.id
    val name: String get() = identity.name

    fun hasSource(source: DiscoverySource): Boolean = discoverySources.contains(source)
    fun hasTransport(transport: NodeTransportType): Boolean = transportCandidates.contains(transport)
    fun isConnected(): Boolean = connectionState == LinkState.CONNECTED
    fun isConnecting(): Boolean = connectionState == LinkState.CONNECTING

    fun getLanEndpoint(): DiscoveredEndpoint? =
        resolvedEndpoints.firstOrNull { it.transportType == NodeTransportType.LOCAL_WIFI }

    fun getWifiDirectEndpoint(): DiscoveredEndpoint? =
        resolvedEndpoints.firstOrNull { it.transportType == NodeTransportType.WIFI_DIRECT }

    fun getWifiAwareEndpoint(): DiscoveredEndpoint? =
        resolvedEndpoints.firstOrNull { it.transportType == NodeTransportType.WIFI_AWARE }

    fun getBleEndpoint(): DiscoveredEndpoint? =
        resolvedEndpoints.firstOrNull { it.transportType == NodeTransportType.BLUETOOTH_LE }

    fun getNfcEndpoint(): DiscoveredEndpoint? =
        resolvedEndpoints.firstOrNull { it.transportType == NodeTransportType.NFC }
}

// ─────────────────────────────────────────────────────────────────────────────
// Unified Registry Singleton
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Central HAT Discovery Registry.
 *
 * Merges discovery events from:
 * - LAN NSD ([LanDiscoveryProvider])
 * - Wi-Fi Direct DNS-SD ([WifiDirectDiscoveryProvider])
 * - Wi-Fi Aware ([WifiAwareDiscoveryProvider])
 * - BLE Presence ([HatBlePresenceProvider])
 * - NFC Bootstrap ([HatNfcBootstrapProvider])
 *
 * **Rules:**
 * - Single entry per Node ID regardless of how many mechanisms discovered it.
 * - Never overwrites permanent HAT Node ID with IP/MAC/PeerHandle.
 * - Prevents automatic creation of duplicate connections to the same Node.
 * - Exposes unified StateFlow to UI and diagnostics.
 */
object HatDiscoveryRegistry {

    private const val TAG = "HatDiscoveryRegistry"

    private val lock = Any()

    private val _discoveredNodes = MutableStateFlow<List<DiscoveredNodeEntry>>(emptyList())
    val discoveredNodes: StateFlow<List<DiscoveredNodeEntry>> = _discoveredNodes.asStateFlow()

    // Explicit manual/injected entries (e.g. for testing or direct registration)
    private val manualEntries = ConcurrentHashMap<String, DiscoveredNodeEntry>()

    private var monitoringJob: Job? = null
    private var monitoringScope: CoroutineScope? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle & Monitoring
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Starts active observation of all discovery providers and link state updates.
     * Automatically recomputes the unified registry whenever any provider emits.
     */
    fun startMonitoring(scope: CoroutineScope) {
        synchronized(lock) {
            stopMonitoring()
            monitoringScope = scope
            monitoringJob = scope.launch {
                launch {
                    LanDiscoveryProvider.discoveredEndpoints.collect { recomputeRegistry() }
                }
                launch {
                    com.example.audiostreamer.DiscoveryManager.discoveredDevices.collect { recomputeRegistry() }
                }
                launch {
                    WifiDirectDiscoveryProvider.discoveredEndpoints.collect { recomputeRegistry() }
                }
                launch {
                    com.example.audiostreamer.WifiDirectManager.discoveredPeers.collect { recomputeRegistry() }
                }
                launch {
                    WifiAwareDiscoveryProvider.discoveredNodes.collect { recomputeRegistry() }
                }
                launch {
                    HatBlePresenceProvider.discoveredNodes.collect { recomputeRegistry() }
                }
                launch {
                    com.example.audiostreamer.BleDiscoveryManager.bleDevices.collect { recomputeRegistry() }
                }
                launch {
                    HatNfcBootstrapProvider.discoveredNodes.collect { recomputeRegistry() }
                }
                launch {
                    HatLinkManager.activeLinks.collect { recomputeRegistry() }
                }
            }
            recomputeRegistry()
            Log.i(TAG, "Started monitoring all discovery providers and link updates")
        }
    }

    /**
     * Stops active observation job.
     */
    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        monitoringScope = null
    }

    /**
     * Clears all discovered entries and manual registrations.
     */
    fun clear() {
        synchronized(lock) {
            manualEntries.clear()
            _discoveredNodes.value = emptyList()
            Log.i(TAG, "Cleared HatDiscoveryRegistry")
        }
    }

    /**
     * Full teardown: stops monitoring and clears entries.
     */
    fun stopAll() {
        stopMonitoring()
        clear()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Registration & Queries
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Directly registers or updates an entry in the registry.
     */
    fun registerNode(entry: DiscoveredNodeEntry) {
        synchronized(lock) {
            validateNodeId(entry.id)
            val existing = manualEntries[entry.id]
            if (existing != null) {
                val mergedSources = existing.discoverySources + entry.discoverySources
                val mergedTransports = existing.transportCandidates + entry.transportCandidates
                val mergedEndpoints = (existing.resolvedEndpoints + entry.resolvedEndpoints).distinctBy {
                    "${it.transportType}:${it.address}:${it.port}"
                }
                manualEntries[entry.id] = existing.copy(
                    discoverySources = mergedSources,
                    transportCandidates = mergedTransports,
                    resolvedEndpoints = mergedEndpoints,
                    firstSeenEpochMs = minOf(existing.firstSeenEpochMs, entry.firstSeenEpochMs),
                    lastSeenEpochMs = maxOf(existing.lastSeenEpochMs, entry.lastSeenEpochMs),
                    rssi = entry.rssi ?: existing.rssi
                )
            } else {
                manualEntries[entry.id] = entry
            }
            recomputeRegistry()
        }
    }

    /**
     * Removes an entry by Node ID.
     */
    fun unregisterNode(nodeId: String) {
        synchronized(lock) {
            manualEntries.remove(nodeId)
            recomputeRegistry()
        }
    }

    /**
     * Retrieves a discovered node entry by its stable Node ID.
     */
    fun getDiscoveredNode(nodeId: String): DiscoveredNodeEntry? =
        _discoveredNodes.value.firstOrNull { it.id == nodeId }

    /**
     * Returns true if a connection to this Node ID can be initiated.
     * Enforces: **Do not automatically create multiple connections to the same Node.**
     */
    fun canConnect(nodeId: String): Boolean {
        if (nodeId.isBlank()) return false
        val activeLinks = HatLinkManager.activeLinks.value
        val hasActiveLink = activeLinks.any { link ->
            link.remoteNode.id == nodeId &&
            (link.state == LinkState.CONNECTED || link.state == LinkState.CONNECTING || link.state == LinkState.DEGRADED)
        }
        return !hasActiveLink
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Merging Engine
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Synchronously computes and returns the merged list of discovered nodes from all providers.
     * Guarantees each physical Node appears exactly once.
     */
    fun recomputeRegistry(): List<DiscoveredNodeEntry> {
        synchronized(lock) {
            val localNodeId = try { LocalNodeManager.getLocalNode().id } catch (e: Exception) { "" }

            // Keyed by permanent, stable Node ID
            val nodeMap = mutableMapOf<String, NodeBuilder>()

            // 1. Ingest manual/test entries
            for ((id, entry) in manualEntries) {
                if (id == localNodeId) continue
                getOrCreateBuilder(nodeMap, id, entry.name).apply {
                    nodeInfo = entry.nodeInfo
                    discoverySources.addAll(entry.discoverySources)
                    transportCandidates.addAll(entry.transportCandidates)
                    endpoints.addAll(entry.resolvedEndpoints)
                    firstSeenEpochMs = minOf(firstSeenEpochMs, entry.firstSeenEpochMs)
                    lastSeenEpochMs = maxOf(lastSeenEpochMs, entry.lastSeenEpochMs)
                    if (entry.rssi != null) rssi = entry.rssi
                    availability = entry.availability
                }
            }

            // 2. Ingest LAN NSD endpoints
            val lanEndpoints = LanDiscoveryProvider.discoveredEndpoints.value
            for (lan in lanEndpoints) {
                val id = lan.nodeInfo.id
                if (id == localNodeId || isInvalidIdentity(id)) continue
                val builder = getOrCreateBuilder(nodeMap, id, lan.nodeInfo.name)
                builder.discoverySources.add(DiscoverySource.LAN)
                builder.transportCandidates.add(NodeTransportType.LOCAL_WIFI)
                builder.nodeInfo = mergeNodeInfo(builder.nodeInfo, lan.nodeInfo)
                builder.firstSeenEpochMs = minOf(builder.firstSeenEpochMs, lan.resolvedAtEpochMs)
                builder.lastSeenEpochMs = maxOf(builder.lastSeenEpochMs, lan.resolvedAtEpochMs)
                builder.endpoints.add(
                    DiscoveredEndpoint(
                        transportType = NodeTransportType.LOCAL_WIFI,
                        address = lan.hostAddress,
                        port = lan.port,
                        description = "LAN NSD (${lan.hostAddress}:${lan.port})",
                        details = lan.txtRecord,
                        lastSeenEpochMs = lan.resolvedAtEpochMs
                    )
                )
            }

            // 2b. Ingest UDP broadcast LAN endpoints from DiscoveryManager (deduplicated by Node ID and IP)
            val localIps = try {
                (com.example.audiostreamer.NetworkUtils.getAllLocalIpAddresses() + 
                 com.example.audiostreamer.NetworkUtils.getP2pIpAddresses()).toSet()
            } catch (e: Exception) { emptySet() }

            val udpDevices = com.example.audiostreamer.DiscoveryManager.discoveredDevices.value
            for (udp in udpDevices) {
                if (udp.ip in localIps || (udp.p2pGoIp != null && udp.p2pGoIp in localIps)) continue
                val matchedBuilder = if (!udp.nodeId.isNullOrBlank() && !isInvalidIdentity(udp.nodeId)) {
                    nodeMap[udp.nodeId]
                } else {
                    nodeMap.values.firstOrNull { b -> b.endpoints.any { it.address == udp.ip } }
                }

                val id = udp.nodeId?.takeIf { !isInvalidIdentity(it) }
                    ?: matchedBuilder?.id
                    ?: "hat-node-lan-${udp.ip.replace('.', '-').replace(':', '-')}"

                if (id == localNodeId) continue

                val builder = getOrCreateBuilder(nodeMap, id, udp.name)
                builder.discoverySources.add(DiscoverySource.LAN)
                builder.transportCandidates.add(NodeTransportType.LOCAL_WIFI)
                if (udp.isP2pActive || !udp.p2pSsid.isNullOrEmpty()) {
                    builder.discoverySources.add(DiscoverySource.WIFI_DIRECT)
                    builder.transportCandidates.add(NodeTransportType.WIFI_DIRECT)
                }
                val now = System.currentTimeMillis()
                builder.firstSeenEpochMs = minOf(builder.firstSeenEpochMs, now)
                builder.lastSeenEpochMs = maxOf(builder.lastSeenEpochMs, now)
                builder.endpoints.add(
                    DiscoveredEndpoint(
                        transportType = NodeTransportType.LOCAL_WIFI,
                        address = udp.ip,
                        port = udp.port,
                        description = "LAN Broadcast (${udp.ip}:${udp.port})",
                        details = mapOf("p2pSsid" to udp.p2pSsid, "p2pGoIp" to udp.p2pGoIp, "p2pMac" to udp.p2pMac),
                        lastSeenEpochMs = now
                    )
                )
            }

            // 3. Ingest Wi-Fi Direct endpoints
            val wdEndpoints = WifiDirectDiscoveryProvider.discoveredEndpoints.value
            for (wd in wdEndpoints) {
                val id = wd.nodeInfo.id
                if (id == localNodeId || isInvalidIdentity(id)) continue
                val builder = getOrCreateBuilder(nodeMap, id, wd.nodeInfo.name)
                builder.discoverySources.add(DiscoverySource.WIFI_DIRECT)
                builder.transportCandidates.add(NodeTransportType.WIFI_DIRECT)
                builder.nodeInfo = mergeNodeInfo(builder.nodeInfo, wd.nodeInfo)
                builder.firstSeenEpochMs = minOf(builder.firstSeenEpochMs, wd.firstDiscoveredEpochMs)
                builder.lastSeenEpochMs = maxOf(builder.lastSeenEpochMs, wd.lastSeenEpochMs)
                builder.endpoints.add(
                    DiscoveredEndpoint(
                        transportType = NodeTransportType.WIFI_DIRECT,
                        address = wd.deviceAddress,
                        description = "Wi-Fi Direct (${wd.deviceAddress})",
                        details = wd.txtRecord,
                        lastSeenEpochMs = wd.lastSeenEpochMs
                    )
                )
            }

            // 3b. Correlate raw P2P peers from WifiDirectManager
            val p2pPeers = try { com.example.audiostreamer.WifiDirectManager.discoveredPeers.value } catch (e: Exception) { emptyList() }
            val thisP2pMac = try { com.example.audiostreamer.WifiDirectManager.thisDeviceAddress } catch (_: Exception) { null }
            val thisP2pName = try { com.example.audiostreamer.WifiDirectManager.thisDeviceName } catch (_: Exception) { null }
            for (peer in p2pPeers) {
                val mac = peer.deviceAddress
                val isSelf = (thisP2pMac != null && mac.equals(thisP2pMac, ignoreCase = true)) ||
                             (thisP2pName != null && peer.deviceName.isNotBlank() && peer.deviceName.equals(thisP2pName, ignoreCase = true))
                if (isSelf) continue

                val matched = nodeMap.values.firstOrNull { b ->
                    b.endpoints.any { it.address.equals(mac, ignoreCase = true) } ||
                    (peer.deviceName.isNotBlank() && b.name.equals(peer.deviceName, ignoreCase = true))
                }
                if (matched != null) {
                    matched.discoverySources.add(DiscoverySource.WIFI_DIRECT)
                    matched.transportCandidates.add(NodeTransportType.WIFI_DIRECT)
                } else {
                    val id = "hat-node-p2p-${mac.replace(':', '-')}"
                    val b = getOrCreateBuilder(nodeMap, id, peer.deviceName.ifEmpty { "Wi-Fi Direct Peer" })
                    b.discoverySources.add(DiscoverySource.WIFI_DIRECT)
                    b.transportCandidates.add(NodeTransportType.WIFI_DIRECT)
                    val now = System.currentTimeMillis()
                    b.firstSeenEpochMs = minOf(b.firstSeenEpochMs, now)
                    b.lastSeenEpochMs = maxOf(b.lastSeenEpochMs, now)
                    b.endpoints.add(
                        DiscoveredEndpoint(
                            transportType = NodeTransportType.WIFI_DIRECT,
                            address = mac,
                            description = "Wi-Fi Direct ($mac)",
                            lastSeenEpochMs = now
                        )
                    )
                }
            }

            // 4. Ingest Wi-Fi Aware endpoints
            val awareEndpoints = WifiAwareDiscoveryProvider.discoveredNodes.value
            for (aware in awareEndpoints) {
                val id = aware.nodeInfo.id
                if (id == localNodeId || isInvalidIdentity(id)) continue
                val builder = getOrCreateBuilder(nodeMap, id, aware.nodeInfo.name)
                builder.discoverySources.add(DiscoverySource.WIFI_AWARE)
                builder.transportCandidates.add(NodeTransportType.WIFI_AWARE)
                builder.nodeInfo = mergeNodeInfo(builder.nodeInfo, aware.nodeInfo)
                builder.firstSeenEpochMs = minOf(builder.firstSeenEpochMs, aware.firstSeenEpochMs)
                builder.lastSeenEpochMs = maxOf(builder.lastSeenEpochMs, aware.lastSeenEpochMs)
                builder.endpoints.add(
                    DiscoveredEndpoint(
                        transportType = NodeTransportType.WIFI_AWARE,
                        address = aware.peerHandle.toString(),
                        description = "Wi-Fi Aware (${aware.serviceName})",
                        details = mapOf("peerHandle" to aware.peerHandle.toString()),
                        lastSeenEpochMs = aware.lastSeenEpochMs
                    )
                )
            }

            // 5. Ingest BLE Presence endpoints (with suffix-based matching)
            val bleEndpoints = HatBlePresenceProvider.discoveredNodes.value
            for (ble in bleEndpoints) {
                val bleNodeId = ble.nodeInfo.id
                if (bleNodeId == localNodeId || isInvalidIdentity(bleNodeId)) continue

                // Check if this BLE node matches an existing full Node ID (by suffix match)
                val suffix = bleNodeId.removePrefix("hat-node-ble-")
                val existingKey = nodeMap.keys.firstOrNull { it == bleNodeId || (suffix.isNotEmpty() && it.endsWith(suffix)) }
                val targetKey = existingKey ?: bleNodeId

                val builder = getOrCreateBuilder(nodeMap, targetKey, ble.shortName)
                builder.discoverySources.add(DiscoverySource.BLE)
                builder.transportCandidates.add(NodeTransportType.BLUETOOTH_LE)
                builder.nodeInfo = mergeNodeInfo(builder.nodeInfo, ble.nodeInfo)
                builder.firstSeenEpochMs = minOf(builder.firstSeenEpochMs, ble.firstSeenEpochMs)
                builder.lastSeenEpochMs = maxOf(builder.lastSeenEpochMs, ble.lastSeenEpochMs)
                builder.rssi = ble.rssi
                builder.endpoints.add(
                    DiscoveredEndpoint(
                        transportType = NodeTransportType.BLUETOOTH_LE,
                        address = ble.bleAddress,
                        description = "BLE Presence (${ble.bleAddress}, ${ble.rssi} dBm)",
                        details = mapOf("rssi" to ble.rssi, "mac" to ble.bleAddress),
                        lastSeenEpochMs = ble.lastSeenEpochMs
                    )
                )
            }

            // 5b. Correlate legacy BLE peers from BleDiscoveryManager
            val bleLegacy = try { com.example.audiostreamer.BleDiscoveryManager.bleDevices.value } catch (e: Exception) { emptyList() }
            for (ble in bleLegacy) {
                if (ble.role != "receiver") continue
                val addr = ble.bluetoothAddress
                val matched = nodeMap.values.firstOrNull { b ->
                    b.endpoints.any { it.address.equals(addr, ignoreCase = true) } ||
                    (ble.name.isNotBlank() && (b.name.equals(ble.name, ignoreCase = true) || b.name.contains(ble.name, ignoreCase = true)))
                }
                if (matched != null) {
                    matched.discoverySources.add(DiscoverySource.BLE)
                    matched.transportCandidates.add(NodeTransportType.BLUETOOTH_LE)
                } else {
                    val id = "hat-node-ble-${addr.replace(':', '-')}"
                    val b = getOrCreateBuilder(nodeMap, id, ble.name.ifEmpty { "Nearby Receiver" })
                    b.discoverySources.add(DiscoverySource.BLE)
                    b.transportCandidates.add(NodeTransportType.BLUETOOTH_LE)
                    val now = System.currentTimeMillis()
                    b.firstSeenEpochMs = minOf(b.firstSeenEpochMs, now)
                    b.lastSeenEpochMs = maxOf(b.lastSeenEpochMs, now)
                    b.endpoints.add(
                        DiscoveredEndpoint(
                            transportType = NodeTransportType.BLUETOOTH_LE,
                            address = addr,
                            description = "BLE (${ble.name})",
                            details = mapOf("p2pSsid" to ble.p2pSsid, "p2pPassphrase" to ble.p2pPassphrase, "p2pGoIp" to ble.p2pGoIp),
                            lastSeenEpochMs = now
                        )
                    )
                }
            }

            // 6. Ingest NFC Bootstrap endpoints
            val nfcEndpoints = HatNfcBootstrapProvider.discoveredNodes.value
            for (nfc in nfcEndpoints) {
                val id = nfc.nodeInfo.id
                if (id == localNodeId || isInvalidIdentity(id)) continue
                val builder = getOrCreateBuilder(nodeMap, id, nfc.nodeInfo.name)
                builder.discoverySources.add(DiscoverySource.NFC)
                builder.transportCandidates.addAll(nfc.transportHints)
                builder.nodeInfo = mergeNodeInfo(builder.nodeInfo, nfc.nodeInfo)
                builder.firstSeenEpochMs = minOf(builder.firstSeenEpochMs, nfc.discoveredAtEpochMs)
                builder.lastSeenEpochMs = maxOf(builder.lastSeenEpochMs, nfc.discoveredAtEpochMs)
                builder.endpoints.add(
                    DiscoveredEndpoint(
                        transportType = NodeTransportType.NFC,
                        port = nfc.portHint,
                        description = "NFC Bootstrap (${nfc.preferredTransport?.name ?: "Pending"})",
                        details = mapOf(
                            "token" to (nfc.sessionToken ?: "none"),
                            "preferredTransport" to (nfc.preferredTransport?.name ?: "None")
                        ),
                        lastSeenEpochMs = nfc.discoveredAtEpochMs
                    )
                )
            }

            // 7. Resolve live connection states from HatLinkManager
            val activeLinks = HatLinkManager.activeLinks.value
            val resultList = nodeMap.values.map { builder ->
                val link = activeLinks.firstOrNull { it.remoteNode.id == builder.id }
                val connState = link?.state ?: LinkState.DISCONNECTED
                builder.build(connState)
            }.sortedByDescending { it.lastSeenEpochMs }

            _discoveredNodes.value = resultList
            resultList.forEach { entry ->
                HatLinkManager.updateCandidatesFromDiscoveredNode(entry)
            }
            return resultList
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Diagnostics
    // ─────────────────────────────────────────────────────────────────────────

    fun getDiagnosticsSnapshot(): Map<String, Any?> = synchronized(lock) {
        val currentNodes = _discoveredNodes.value
        val sourceCounts = mutableMapOf<String, Int>()
        DiscoverySource.values().forEach { s ->
            sourceCounts[s.name] = currentNodes.count { it.hasSource(s) }
        }

        linkedMapOf(
            "totalNodesCount" to currentNodes.size,
            "connectedNodesCount" to currentNodes.count { it.isConnected() },
            "sourceBreakdown" to sourceCounts,
            "nodes" to currentNodes.map { n ->
                "${n.id} (${n.name}) sources=${n.discoverySources.map { it.name }} " +
                "transports=${n.transportCandidates.map { it.name }} " +
                "rssi=${n.rssi?.let { "$it dBm" } ?: "N/A"} " +
                "endpoints=${n.resolvedEndpoints.size} " +
                "state=${n.availability.name} conn=${n.connectionState.name}"
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal Helper Builder
    // ─────────────────────────────────────────────────────────────────────────

    private fun getOrCreateBuilder(
        map: MutableMap<String, NodeBuilder>,
        nodeId: String,
        suggestedName: String
    ): NodeBuilder = map.getOrPut(nodeId) {
        NodeBuilder(
            id = nodeId,
            name = suggestedName.ifBlank { "HAT Node" }
        )
    }

    private fun mergeNodeInfo(current: NodeInfo?, incoming: NodeInfo): NodeInfo {
        if (current == null) return incoming
        // Prefer longer / more descriptive device name
        val bestName = if (incoming.name.length > current.name.length && incoming.name != "HAT Node" && incoming.name != "Audio Receiver") {
            incoming.name
        } else {
            current.name
        }
        val combinedTransports = current.capabilities.supportedTransports + incoming.capabilities.supportedTransports
        val combinedCodecs = current.capabilities.supportedCodecs + incoming.capabilities.supportedCodecs
        val combinedSampleRates = current.capabilities.supportedSampleRates + incoming.capabilities.supportedSampleRates

        return current.copy(
            identity = NodeIdentity(id = current.id, name = bestName),
            capabilities = current.capabilities.copy(
                supportedTransports = combinedTransports,
                supportedCodecs = combinedCodecs,
                supportedSampleRates = combinedSampleRates
            )
        )
    }

    private fun validateNodeId(id: String) {
        require(id.isNotBlank()) { "Node ID cannot be blank" }
        require(!NodeIdentity.isIp(id)) { "Transport IP address '$id' cannot be used as Node ID" }
        require(!NodeIdentity.isMac(id)) { "Transport MAC address '$id' cannot be used as Node ID" }
    }

    private fun isInvalidIdentity(id: String): Boolean =
        id.isBlank() || NodeIdentity.isIp(id) || NodeIdentity.isMac(id)

    private class NodeBuilder(
        val id: String,
        var name: String
    ) {
        var nodeInfo: NodeInfo? = null
        val discoverySources = mutableSetOf<DiscoverySource>()
        val transportCandidates = mutableSetOf<NodeTransportType>()
        val endpoints = mutableListOf<DiscoveredEndpoint>()
        var firstSeenEpochMs: Long = Long.MAX_VALUE
        var lastSeenEpochMs: Long = 0L
        var rssi: Int? = null
        var availability: NodeState = NodeState.AVAILABLE

        fun build(connState: LinkState): DiscoveredNodeEntry {
            val identity = NodeIdentity(id = id, name = name.ifBlank { "HAT Node" })
            val effectiveNodeInfo = nodeInfo?.copy(identity = identity) ?: NodeInfo(
                identity = identity,
                capabilities = NodeCapabilities(supportedTransports = transportCandidates),
                deviceInfo = DevicePlatformInfo(),
                state = availability,
                activeRole = StreamRole.TRANSCEIVER
            )

            // Deduplicate endpoints by (transportType, address, port)
            val uniqueEndpoints = endpoints.distinctBy {
                "${it.transportType}:${it.address}:${it.port}"
            }

            val effectiveFirstSeen = if (firstSeenEpochMs == Long.MAX_VALUE) System.currentTimeMillis() else firstSeenEpochMs
            val effectiveLastSeen = if (lastSeenEpochMs == 0L) effectiveFirstSeen else lastSeenEpochMs

            return DiscoveredNodeEntry(
                identity = identity,
                nodeInfo = effectiveNodeInfo,
                discoverySources = discoverySources.toSet(),
                firstSeenEpochMs = effectiveFirstSeen,
                lastSeenEpochMs = effectiveLastSeen,
                transportCandidates = transportCandidates.toSet(),
                rssi = rssi,
                resolvedEndpoints = uniqueEndpoints,
                availability = availability,
                connectionState = connState
            )
        }
    }
}
