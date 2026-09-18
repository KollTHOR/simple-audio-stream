package com.example.audiostreamer.node

import com.example.audiostreamer.BleDiscoveredDevice
import com.example.audiostreamer.ConnectedDevice
import com.example.audiostreamer.ConnectionProfile
import com.example.audiostreamer.DiscoveredDevice
import com.example.audiostreamer.MainActivity

/**
 * Bidirectional compatibility adapters between the new [NodeInfo] / [NodeIdentity] / [NodeCapabilities]
 * abstractions and existing legacy device constructs.
 */
object NodeAdapters {

    /**
     * Converts a UDP [DiscoveredDevice] to a [NodeInfo].
     */
    fun fromDiscoveredDevice(device: DiscoveredDevice): NodeInfo {
        val identity = if (!device.nodeId.isNullOrBlank()) {
            NodeIdentity(id = device.nodeId, name = device.name)
        } else {
            // Deterministic temporary identity if remote peer is legacy without a node ID.
            // In accordance with Node architecture rules, this is flagged as legacy adapter fallback.
            val fallbackId = "${NodeIdentity.ID_PREFIX}legacy-${(device.p2pMac ?: "${device.ip}:${device.port}").replace(":", "-")}"
            NodeIdentity(id = fallbackId, name = device.name)
        }

        val capabilities = device.nodeCapabilities ?: NodeCapabilities.fromCapabilitiesMask(
            mask = device.capabilitiesMask,
            hasAudioInput = device.role == "transmitter",
            hasAudioOutput = device.role == "receiver" || device.role.isBlank(),
            supportedTransports = if (device.isP2pActive) {
                setOf(NodeTransportType.LOCAL_WIFI, NodeTransportType.WIFI_DIRECT)
            } else {
                setOf(NodeTransportType.LOCAL_WIFI)
            }
        )

        val role = when (device.role.lowercase()) {
            "transmitter", "tx" -> StreamRole.SENDER
            "receiver", "rx" -> StreamRole.RECEIVER
            else -> StreamRole.IDLE
        }

        val state = if (device.isStreaming) NodeState.ACTIVE_STREAMING else NodeState.AVAILABLE

        return NodeInfo(
            identity = identity,
            capabilities = capabilities,
            deviceInfo = DevicePlatformInfo(model = device.modelName ?: device.name),
            state = state,
            activeRole = role,
            lastSeenEpochMs = System.currentTimeMillis()
        )
    }

    /**
     * Converts a [NodeInfo] back into a [DiscoveredDevice] for consumption by legacy discovery consumers.
     */
    fun toDiscoveredDevice(
        node: NodeInfo,
        ip: String,
        port: Int,
        isP2pActive: Boolean = false,
        p2pSsid: String? = null,
        p2pPassphrase: String? = null,
        p2pGoIp: String? = null,
        p2pMac: String? = null
    ): DiscoveredDevice {
        val legacyRole = when (node.activeRole) {
            StreamRole.SENDER -> "transmitter"
            StreamRole.RECEIVER -> "receiver"
            else -> if (node.canReceive()) "receiver" else "transmitter"
        }

        return DiscoveredDevice(
            name = node.name,
            ip = ip,
            port = port,
            capabilitiesMask = node.capabilities.toCapabilitiesMask(),
            isP2pActive = isP2pActive,
            p2pSsid = p2pSsid,
            p2pPassphrase = p2pPassphrase,
            p2pGoIp = p2pGoIp,
            p2pMac = p2pMac,
            modelName = node.deviceInfo.model,
            isStreaming = node.isStreaming(),
            role = legacyRole,
            nodeId = node.id
        )
    }

    /**
     * Converts a [BleDiscoveredDevice] to a [NodeInfo].
     */
    fun fromBleDevice(bleDevice: BleDiscoveredDevice): NodeInfo {
        val fallbackId = "${NodeIdentity.ID_PREFIX}ble-${bleDevice.bluetoothAddress.replace(":", "-")}"
        val identity = NodeIdentity(id = fallbackId, name = bleDevice.name)

        val role = when (bleDevice.role.lowercase()) {
            "tx" -> StreamRole.SENDER
            "rx" -> StreamRole.RECEIVER
            else -> StreamRole.IDLE
        }

        val transports = mutableSetOf(NodeTransportType.BLUETOOTH_LE)
        if (!bleDevice.p2pSsid.isNullOrEmpty()) {
            transports.add(NodeTransportType.WIFI_DIRECT)
        }

        return NodeInfo(
            identity = identity,
            capabilities = NodeCapabilities(
                hasAudioInput = role == StreamRole.SENDER,
                hasAudioOutput = role == StreamRole.RECEIVER,
                supportedTransports = transports
            ),
            state = NodeState.AVAILABLE,
            activeRole = role,
            lastSeenEpochMs = System.currentTimeMillis()
        )
    }

    /**
     * Converts a UI [MainActivity.UnifiedDevice] to a [NodeInfo].
     */
    fun fromUnifiedDevice(unified: MainActivity.UnifiedDevice): NodeInfo {
        val identity = if (!unified.nodeId.isNullOrBlank()) {
            NodeIdentity(id = unified.nodeId, name = unified.displayName)
        } else {
            NodeIdentity(id = "${NodeIdentity.ID_PREFIX}${unified.id.replace(":", "-")}", name = unified.displayName)
        }

        val transports = mutableSetOf<NodeTransportType>()
        if (unified.lanIp != null) transports.add(NodeTransportType.LOCAL_WIFI)
        if (unified.isDirectAvailable) transports.add(NodeTransportType.WIFI_DIRECT)

        val capabilities = NodeCapabilities.fromCapabilitiesMask(
            mask = unified.capabilitiesMask,
            supportedTransports = transports
        )

        return NodeInfo(
            identity = identity,
            capabilities = capabilities,
            deviceInfo = DevicePlatformInfo(model = unified.modelName ?: unified.displayName),
            state = NodeState.AVAILABLE
        )
    }

    /**
     * Converts a [ConnectedDevice] telemetry model to a [NodeInfo].
     */
    fun fromConnectedDevice(connected: ConnectedDevice, capabilitiesMask: Int = 0): NodeInfo {
        val identity = if (!connected.nodeId.isNullOrBlank()) {
            NodeIdentity(id = connected.nodeId, name = connected.name)
        } else {
            NodeIdentity(id = "${NodeIdentity.ID_PREFIX}conn-${connected.ip.replace(".", "-")}", name = connected.name)
        }

        return NodeInfo(
            identity = identity,
            capabilities = NodeCapabilities.fromCapabilitiesMask(
                mask = capabilitiesMask,
                supportedTransports = if (connected.isDirectP2p) {
                    setOf(NodeTransportType.WIFI_DIRECT)
                } else {
                    setOf(NodeTransportType.LOCAL_WIFI)
                }
            ),
            state = NodeState.ACTIVE_STREAMING,
            activeRole = StreamRole.RECEIVER
        )
    }

    /**
     * Converts a saved [ConnectionProfile] to a [NodeInfo].
     */
    fun fromConnectionProfile(profile: ConnectionProfile): NodeInfo {
        val identity = if (!profile.nodeId.isNullOrBlank()) {
            NodeIdentity(id = profile.nodeId, name = profile.name)
        } else {
            NodeIdentity(id = "${NodeIdentity.ID_PREFIX}prof-${profile.id}", name = profile.name)
        }

        return NodeInfo(
            identity = identity,
            capabilities = NodeCapabilities.fromCapabilitiesMask(mask = profile.capabilitiesMask),
            state = NodeState.AVAILABLE
        )
    }
}

// Convenient extension functions
fun DiscoveredDevice.toNodeInfo(): NodeInfo = NodeAdapters.fromDiscoveredDevice(this)
fun BleDiscoveredDevice.toNodeInfo(): NodeInfo = NodeAdapters.fromBleDevice(this)
fun MainActivity.UnifiedDevice.toNodeInfo(): NodeInfo = NodeAdapters.fromUnifiedDevice(this)
fun ConnectedDevice.toNodeInfo(capabilitiesMask: Int = 0): NodeInfo = NodeAdapters.fromConnectedDevice(this, capabilitiesMask)
fun ConnectionProfile.toNodeInfo(): NodeInfo = NodeAdapters.fromConnectionProfile(this)
