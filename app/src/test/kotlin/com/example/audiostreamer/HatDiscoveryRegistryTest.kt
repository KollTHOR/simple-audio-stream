package com.example.audiostreamer

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
import com.example.audiostreamer.node.discovery.BlePresenceNode
import com.example.audiostreamer.node.discovery.DiscoveredEndpoint
import com.example.audiostreamer.node.discovery.DiscoveredNodeEntry
import com.example.audiostreamer.node.discovery.DiscoverySource
import com.example.audiostreamer.node.discovery.HatBlePresenceProvider
import com.example.audiostreamer.node.discovery.HatDiscoveryRegistry
import com.example.audiostreamer.node.discovery.HatNfcBootstrapPayload
import com.example.audiostreamer.node.discovery.HatNfcBootstrapProvider
import com.example.audiostreamer.node.discovery.HatNfcDiscoveredNode
import com.example.audiostreamer.node.discovery.LanDiscoveredNode
import com.example.audiostreamer.node.discovery.LanDiscoveryProvider
import com.example.audiostreamer.node.discovery.WifiAwareDiscoveredNode
import com.example.audiostreamer.node.discovery.WifiAwareDiscoveryProvider
import com.example.audiostreamer.node.discovery.WifiDirectDiscoveredNode
import com.example.audiostreamer.node.discovery.WifiDirectDiscoveryProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HatDiscoveryRegistryTest {

    @Before
    fun setUp() {
        HatDiscoveryRegistry.clear()
        HatLinkManager.clear()
        HatBlePresenceProvider.clearDiscoveredNodes()
        HatNfcBootstrapProvider.clearDiscoveredNodes()
    }

    @After
    fun tearDown() {
        HatDiscoveryRegistry.stopAll()
        HatLinkManager.clear()
    }

    // ─── Deduplication: Single Node Discovered Across Multiple Mechanisms ─────

    @Test
    fun testSingleNodeDiscoveredAcrossMultipleSourcesMergesIntoOneEntry() {
        // Requirement example:
        // Node ABC discovered through LAN, BLE, and Wi-Fi Aware -> one Node entry with:
        // discoverySources: [LAN, BLE, WIFI_AWARE]
        val nodeId = "hat-node-abc-7788"
        val nodeName = "Studio Monitor ABC"
        val identity = NodeIdentity(nodeId, nodeName)

        val lanEntry = DiscoveredNodeEntry(
            identity = identity,
            nodeInfo = NodeInfo(
                identity = identity,
                capabilities = NodeCapabilities(supportedTransports = setOf(NodeTransportType.LOCAL_WIFI)),
                deviceInfo = DevicePlatformInfo(model = "Monitor M1"),
                state = NodeState.AVAILABLE,
                activeRole = StreamRole.RECEIVER
            ),
            discoverySources = setOf(DiscoverySource.LAN),
            firstSeenEpochMs = 1000L,
            lastSeenEpochMs = 1500L,
            transportCandidates = setOf(NodeTransportType.LOCAL_WIFI),
            resolvedEndpoints = listOf(
                DiscoveredEndpoint(NodeTransportType.LOCAL_WIFI, address = "192.168.1.150", port = 50005, description = "LAN NSD")
            )
        )

        val bleEntry = DiscoveredNodeEntry(
            identity = identity,
            nodeInfo = NodeInfo(
                identity = identity,
                capabilities = NodeCapabilities(supportedTransports = setOf(NodeTransportType.BLUETOOTH_LE)),
                deviceInfo = DevicePlatformInfo(),
                state = NodeState.AVAILABLE,
                activeRole = StreamRole.RECEIVER
            ),
            discoverySources = setOf(DiscoverySource.BLE),
            firstSeenEpochMs = 800L,
            lastSeenEpochMs = 2000L,
            transportCandidates = setOf(NodeTransportType.BLUETOOTH_LE),
            rssi = -62,
            resolvedEndpoints = listOf(
                DiscoveredEndpoint(NodeTransportType.BLUETOOTH_LE, address = "AA:BB:CC:DD:EE:11", description = "BLE Presence")
            )
        )

        val awareEntry = DiscoveredNodeEntry(
            identity = identity,
            nodeInfo = NodeInfo(
                identity = identity,
                capabilities = NodeCapabilities(supportedTransports = setOf(NodeTransportType.WIFI_AWARE)),
                deviceInfo = DevicePlatformInfo(),
                state = NodeState.AVAILABLE,
                activeRole = StreamRole.RECEIVER
            ),
            discoverySources = setOf(DiscoverySource.WIFI_AWARE),
            firstSeenEpochMs = 1200L,
            lastSeenEpochMs = 2500L,
            transportCandidates = setOf(NodeTransportType.WIFI_AWARE),
            resolvedEndpoints = listOf(
                DiscoveredEndpoint(NodeTransportType.WIFI_AWARE, address = "peer-handle-aware-42", description = "Wi-Fi Aware")
            )
        )

        // Register all three events for Node ABC
        HatDiscoveryRegistry.registerNode(lanEntry)
        HatDiscoveryRegistry.registerNode(bleEntry)
        HatDiscoveryRegistry.registerNode(awareEntry)

        val mergedList = HatDiscoveryRegistry.recomputeRegistry()

        // Strict assertion: EXACTLY ONE entry for Node ABC
        assertEquals(1, mergedList.size)
        val node = mergedList[0]

        assertEquals(nodeId, node.id)
        assertEquals(nodeName, node.name)

        // Verify discoverySources contains all three
        assertEquals(3, node.discoverySources.size)
        assertTrue(node.hasSource(DiscoverySource.LAN))
        assertTrue(node.hasSource(DiscoverySource.BLE))
        assertTrue(node.hasSource(DiscoverySource.WIFI_AWARE))

        // Verify transportCandidates combines all three
        assertTrue(node.hasTransport(NodeTransportType.LOCAL_WIFI))
        assertTrue(node.hasTransport(NodeTransportType.BLUETOOTH_LE))
        assertTrue(node.hasTransport(NodeTransportType.WIFI_AWARE))

        // Verify timestamps: earliest firstSeen (800L), latest lastSeen (2500L)
        assertEquals(800L, node.firstSeenEpochMs)
        assertEquals(2500L, node.lastSeenEpochMs)

        // Verify RSSI from BLE
        assertEquals(-62, node.rssi)

        // Verify resolved endpoints list has all 3 endpoints
        assertEquals(3, node.resolvedEndpoints.size)
        assertNotNull(node.getLanEndpoint())
        assertNotNull(node.getBleEndpoint())
        assertNotNull(node.getWifiAwareEndpoint())
        assertEquals("192.168.1.150", node.getLanEndpoint()?.address)
        assertEquals("AA:BB:CC:DD:EE:11", node.getBleEndpoint()?.address)
    }

    // ─── Identity Stability: Transport Identifiers Must Never Overwrite Node ID ──

    @Test
    fun testStableNodeIdIsNeverOverwrittenWithTransportIdentifier() {
        val stableNodeId = "hat-node-permanent-identity-99"
        val lanIp = "192.168.1.200"
        val p2pMac = "02:1A:2B:3C:4D:5E"

        // Attempting to register with IP or MAC as Node ID directly must fail
        try {
            HatDiscoveryRegistry.registerNode(
                DiscoveredNodeEntry(
                    identity = NodeIdentity(lanIp, "IP Fake"),
                    nodeInfo = NodeInfo(NodeIdentity(lanIp, "IP Fake"), NodeCapabilities()),
                    discoverySources = setOf(DiscoverySource.LAN),
                    firstSeenEpochMs = 100L,
                    lastSeenEpochMs = 100L,
                    transportCandidates = setOf(NodeTransportType.LOCAL_WIFI)
                )
            )
            fail("Expected IllegalArgumentException for IP address as Node ID")
        } catch (e: IllegalArgumentException) {
            // Success: IP address rejected as Node ID
        }

        try {
            HatDiscoveryRegistry.registerNode(
                DiscoveredNodeEntry(
                    identity = NodeIdentity(p2pMac, "MAC Fake"),
                    nodeInfo = NodeInfo(NodeIdentity(p2pMac, "MAC Fake"), NodeCapabilities()),
                    discoverySources = setOf(DiscoverySource.WIFI_DIRECT),
                    firstSeenEpochMs = 100L,
                    lastSeenEpochMs = 100L,
                    transportCandidates = setOf(NodeTransportType.WIFI_DIRECT)
                )
            )
            fail("Expected IllegalArgumentException for MAC address as Node ID")
        } catch (e: IllegalArgumentException) {
            // Success: MAC address rejected as Node ID
        }

        // Register valid node with IP and MAC recorded inside endpoints
        val validEntry = DiscoveredNodeEntry(
            identity = NodeIdentity(stableNodeId, "Valid Speaker"),
            nodeInfo = NodeInfo(NodeIdentity(stableNodeId, "Valid Speaker"), NodeCapabilities()),
            discoverySources = setOf(DiscoverySource.LAN, DiscoverySource.WIFI_DIRECT),
            firstSeenEpochMs = 1000L,
            lastSeenEpochMs = 2000L,
            transportCandidates = setOf(NodeTransportType.LOCAL_WIFI, NodeTransportType.WIFI_DIRECT),
            resolvedEndpoints = listOf(
                DiscoveredEndpoint(NodeTransportType.LOCAL_WIFI, address = lanIp, port = 50005),
                DiscoveredEndpoint(NodeTransportType.WIFI_DIRECT, address = p2pMac)
            )
        )
        HatDiscoveryRegistry.registerNode(validEntry)

        val retrieved = HatDiscoveryRegistry.getDiscoveredNode(stableNodeId)
        assertNotNull(retrieved)
        assertEquals(stableNodeId, retrieved?.id)
        assertFalse(NodeIdentity.isIp(retrieved?.id ?: ""))
        assertFalse(NodeIdentity.isMac(retrieved?.id ?: ""))

        // The IP and MAC exist only in the resolvedEndpoints list
        assertEquals(lanIp, retrieved?.getLanEndpoint()?.address)
        assertEquals(p2pMac, retrieved?.getWifiDirectEndpoint()?.address)
    }

    // ─── Merge All Five Discovery Sources ─────────────────────────────────────

    @Test
    fun testAllFiveDiscoverySourcesMergedCorrectly() {
        val nodeId = "hat-node-universal-001"
        val identity = NodeIdentity(nodeId, "Universal HAT Node")

        // Register 5 separate discoveries for the same node
        val sources = listOf(
            DiscoverySource.LAN to NodeTransportType.LOCAL_WIFI,
            DiscoverySource.WIFI_DIRECT to NodeTransportType.WIFI_DIRECT,
            DiscoverySource.WIFI_AWARE to NodeTransportType.WIFI_AWARE,
            DiscoverySource.BLE to NodeTransportType.BLUETOOTH_LE,
            DiscoverySource.NFC to NodeTransportType.NFC
        )

        for ((src, transport) in sources) {
            HatDiscoveryRegistry.registerNode(
                DiscoveredNodeEntry(
                    identity = identity,
                    nodeInfo = NodeInfo(
                        identity = identity,
                        capabilities = NodeCapabilities(supportedTransports = setOf(transport))
                    ),
                    discoverySources = setOf(src),
                    firstSeenEpochMs = 1000L,
                    lastSeenEpochMs = 3000L,
                    transportCandidates = setOf(transport),
                    rssi = if (src == DiscoverySource.BLE) -55 else null,
                    resolvedEndpoints = listOf(
                        DiscoveredEndpoint(transportType = transport, description = "$src endpoint")
                    )
                )
            )
        }

        val list = HatDiscoveryRegistry.recomputeRegistry()
        assertEquals(1, list.size)

        val node = list[0]
        assertEquals(nodeId, node.id)
        assertEquals(5, node.discoverySources.size)
        assertTrue(node.hasSource(DiscoverySource.LAN))
        assertTrue(node.hasSource(DiscoverySource.WIFI_DIRECT))
        assertTrue(node.hasSource(DiscoverySource.WIFI_AWARE))
        assertTrue(node.hasSource(DiscoverySource.BLE))
        assertTrue(node.hasSource(DiscoverySource.NFC))

        assertEquals(5, node.transportCandidates.size)
        assertEquals(5, node.resolvedEndpoints.size)
        assertEquals(-55, node.rssi)
    }

    // ─── Multiple Distinct Nodes ──────────────────────────────────────────────

    @Test
    fun testMultipleDistinctNodesKeptSeparate() {
        val node1 = DiscoveredNodeEntry(
            identity = NodeIdentity("hat-node-111", "Speaker 1"),
            nodeInfo = NodeInfo(NodeIdentity("hat-node-111", "Speaker 1"), NodeCapabilities()),
            discoverySources = setOf(DiscoverySource.LAN),
            firstSeenEpochMs = 1000L,
            lastSeenEpochMs = 1000L,
            transportCandidates = setOf(NodeTransportType.LOCAL_WIFI)
        )
        val node2 = DiscoveredNodeEntry(
            identity = NodeIdentity("hat-node-222", "Speaker 2"),
            nodeInfo = NodeInfo(NodeIdentity("hat-node-222", "Speaker 2"), NodeCapabilities()),
            discoverySources = setOf(DiscoverySource.BLE),
            firstSeenEpochMs = 1000L,
            lastSeenEpochMs = 1000L,
            transportCandidates = setOf(NodeTransportType.BLUETOOTH_LE)
        )

        HatDiscoveryRegistry.registerNode(node1)
        HatDiscoveryRegistry.registerNode(node2)

        val list = HatDiscoveryRegistry.recomputeRegistry()
        assertEquals(2, list.size)
        assertNotNull(HatDiscoveryRegistry.getDiscoveredNode("hat-node-111"))
        assertNotNull(HatDiscoveryRegistry.getDiscoveredNode("hat-node-222"))
    }

    // ─── Connection State & Duplicate Connection Prevention ───────────────────

    @Test
    fun testLiveConnectionStateReflected() {
        val nodeId = "hat-node-target-conn"
        val remoteInfo = NodeInfo(NodeIdentity(nodeId, "Target Peer"), NodeCapabilities())

        val entry = DiscoveredNodeEntry(
            identity = remoteInfo.identity,
            nodeInfo = remoteInfo,
            discoverySources = setOf(DiscoverySource.LAN),
            firstSeenEpochMs = 1000L,
            lastSeenEpochMs = 1000L,
            transportCandidates = setOf(NodeTransportType.LOCAL_WIFI)
        )
        HatDiscoveryRegistry.registerNode(entry)

        // Initially disconnected
        var current = HatDiscoveryRegistry.recomputeRegistry().first()
        assertEquals(LinkState.DISCONNECTED, current.connectionState)
        assertFalse(current.isConnected())
        assertTrue("canConnect must be true when disconnected", HatDiscoveryRegistry.canConnect(nodeId))

        // Create link in HatLinkManager
        HatLinkManager.getOrCreateLink(
            remoteNode = remoteInfo,
            transportType = NodeTransportType.LOCAL_WIFI,
            remoteAddress = "192.168.1.77",
            remotePort = 50005
        )

        // Live connection state must update to CONNECTED
        current = HatDiscoveryRegistry.recomputeRegistry().first()
        assertEquals(LinkState.CONNECTED, current.connectionState)
        assertTrue(current.isConnected())

        // Requirement: Do not automatically create multiple connections to the same Node
        assertFalse("canConnect must be false when already connected", HatDiscoveryRegistry.canConnect(nodeId))
    }

    // ─── Self-Node Filtering ──────────────────────────────────────────────────

    @Test
    fun testSelfNodeFilteredOut() {
        val localNode = LocalNodeManager.getLocalNode()
        val selfEntry = DiscoveredNodeEntry(
            identity = localNode.identity,
            nodeInfo = localNode,
            discoverySources = setOf(DiscoverySource.LAN),
            firstSeenEpochMs = 1000L,
            lastSeenEpochMs = 1000L,
            transportCandidates = setOf(NodeTransportType.LOCAL_WIFI)
        )
        HatDiscoveryRegistry.registerNode(selfEntry)

        val list = HatDiscoveryRegistry.recomputeRegistry()
        assertEquals(0, list.size)
        assertNull(HatDiscoveryRegistry.getDiscoveredNode(localNode.id))
    }

    // ─── Diagnostics Snapshot & HatDiagnostics Integration ────────────────────

    @Test
    fun testDiagnosticsSnapshotStructure() {
        val entry = DiscoveredNodeEntry(
            identity = NodeIdentity("hat-node-diag-test", "Diag Test Device"),
            nodeInfo = NodeInfo(NodeIdentity("hat-node-diag-test", "Diag Test Device"), NodeCapabilities()),
            discoverySources = setOf(DiscoverySource.LAN, DiscoverySource.BLE),
            firstSeenEpochMs = 500L,
            lastSeenEpochMs = 1500L,
            transportCandidates = setOf(NodeTransportType.LOCAL_WIFI, NodeTransportType.BLUETOOTH_LE),
            rssi = -70,
            resolvedEndpoints = listOf(
                DiscoveredEndpoint(NodeTransportType.LOCAL_WIFI, address = "192.168.1.10", port = 50005)
            )
        )
        HatDiscoveryRegistry.registerNode(entry)

        val snapshot = HatDiscoveryRegistry.getDiagnosticsSnapshot()

        assertTrue(snapshot.containsKey("totalNodesCount"))
        assertTrue(snapshot.containsKey("connectedNodesCount"))
        assertTrue(snapshot.containsKey("sourceBreakdown"))
        assertTrue(snapshot.containsKey("nodes"))

        assertEquals(1, snapshot["totalNodesCount"])
        assertEquals(0, snapshot["connectedNodesCount"])

        val breakdown = snapshot["sourceBreakdown"] as? Map<*, *>
        assertNotNull(breakdown)
        assertEquals(1, breakdown?.get("LAN"))
        assertEquals(1, breakdown?.get("BLE"))
        assertEquals(0, breakdown?.get("WIFI_DIRECT"))

        val nodesList = snapshot["nodes"] as? List<*>
        assertNotNull(nodesList)
        assertEquals(1, nodesList?.size)
        assertTrue(nodesList?.get(0).toString().contains("hat-node-diag-test"))
    }

    @Test
    fun testHatDiagnosticsIntegration() {
        val snapshot = HatDiagnostics.snapshot()
        assertTrue(
            "HatDiagnostics snapshot must contain [DISCOVERY_REGISTRY] section",
            snapshot.contains("[DISCOVERY_REGISTRY]")
        )
        assertTrue(
            "HatDiagnostics snapshot must contain totalNodesCount",
            snapshot.contains("totalNodesCount")
        )
    }

    // ─── Cleanup & StopAll ────────────────────────────────────────────────────

    @Test
    fun testClearAndStopAll() {
        HatDiscoveryRegistry.registerNode(
            DiscoveredNodeEntry(
                identity = NodeIdentity("hat-node-temp", "Temp"),
                nodeInfo = NodeInfo(NodeIdentity("hat-node-temp", "Temp"), NodeCapabilities()),
                discoverySources = setOf(DiscoverySource.LAN),
                firstSeenEpochMs = 100L,
                lastSeenEpochMs = 100L,
                transportCandidates = setOf(NodeTransportType.LOCAL_WIFI)
            )
        )
        assertEquals(1, HatDiscoveryRegistry.recomputeRegistry().size)

        HatDiscoveryRegistry.clear()
        assertEquals(0, HatDiscoveryRegistry.recomputeRegistry().size)

        HatDiscoveryRegistry.stopAll()
        assertEquals(0, HatDiscoveryRegistry.discoveredNodes.value.size)
    }
}
