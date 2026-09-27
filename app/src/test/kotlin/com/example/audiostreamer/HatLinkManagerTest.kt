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
import com.example.audiostreamer.node.TransportAvailability
import com.example.audiostreamer.node.TransportCandidate
import com.example.audiostreamer.node.discovery.DiscoveredEndpoint
import com.example.audiostreamer.node.discovery.DiscoveredNodeEntry
import com.example.audiostreamer.node.discovery.DiscoverySource
import com.example.audiostreamer.node.transport.HatTransportType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Covers the parts of [HatLinkManager] that are on the live path: the candidate ledger
 * (populated from discovery), link create/close via [HatLinkManager.getOrCreateLink], and the
 * diagnostics snapshot. The removed policy-based connect/reconnect/attempt engine had no
 * production caller and is no longer tested here.
 */
class HatLinkManagerTest {

    private lateinit var remoteNodeA: NodeInfo

    @Before
    fun setUp() {
        HatLinkManager.clear()
        remoteNodeA = NodeInfo(
            identity = NodeIdentity("hat-node-remote-aaa", "Remote Node A"),
            deviceInfo = DevicePlatformInfo(manufacturer = "Sony", model = "WH-1000XM5"),
            capabilities = NodeCapabilities(
                supportedTransports = setOf(NodeTransportType.LOCAL_WIFI, NodeTransportType.WIFI_DIRECT)
            ),
            state = NodeState.AVAILABLE,
            activeRole = StreamRole.RECEIVER
        )
    }

    @After
    fun tearDown() {
        HatLinkManager.clear()
    }

    // ─── 1. Candidate Transport Validation ────────────────────────────────────

    @Test
    fun testAudioTransportsAllowedAsCandidates() {
        val lanCand = TransportCandidate(
            type = HatTransportType.LAN,
            availability = TransportAvailability.AVAILABLE,
            endpointAddress = "192.168.1.100",
            endpointPort = 50005
        )
        val p2pCand = TransportCandidate(
            type = HatTransportType.WIFI_DIRECT,
            availability = TransportAvailability.AVAILABLE,
            endpointAddress = "192.168.49.2",
            endpointPort = 50005,
            isDirect = true
        )
        HatLinkManager.setNodeCandidates(remoteNodeA, listOf(lanCand, p2pCand))
        val candidates = HatLinkManager.getCandidatesForNode(remoteNodeA.id)
        assertEquals(2, candidates.size)
        assertTrue(candidates.any { it.type == HatTransportType.LAN })
        assertTrue(candidates.any { it.type == HatTransportType.WIFI_DIRECT })
    }

    @Test
    fun testNonAudioTransportsRejectedAsCandidates() {
        try {
            TransportCandidate(
                type = HatTransportType.BLE,
                availability = TransportAvailability.AVAILABLE
            )
            fail("BLE must be rejected as an audio TransportCandidate")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("high-bandwidth audio transport"))
        }

        try {
            TransportCandidate(
                type = HatTransportType.NFC_BOOTSTRAP,
                availability = TransportAvailability.AVAILABLE
            )
            fail("NFC must be rejected as an audio TransportCandidate")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("high-bandwidth audio transport"))
        }
    }

    // ─── 2. DiscoveredNodeEntry Extraction ────────────────────────────────────

    @Test
    fun testUpdateCandidatesFromDiscoveredNodeEntry() {
        val identity = NodeIdentity("hat-node-entry-123", "Discovered Speaker")
        val nodeInfo = NodeInfo(
            identity = identity,
            capabilities = NodeCapabilities(
                supportedTransports = setOf(NodeTransportType.LOCAL_WIFI, NodeTransportType.WIFI_DIRECT, NodeTransportType.BLUETOOTH_LE)
            ),
            state = NodeState.AVAILABLE
        )

        val entry = DiscoveredNodeEntry(
            identity = identity,
            nodeInfo = nodeInfo,
            discoverySources = setOf(DiscoverySource.LAN, DiscoverySource.WIFI_DIRECT, DiscoverySource.BLE),
            firstSeenEpochMs = 1000L,
            lastSeenEpochMs = 2000L,
            transportCandidates = setOf(NodeTransportType.LOCAL_WIFI, NodeTransportType.WIFI_DIRECT, NodeTransportType.BLUETOOTH_LE),
            resolvedEndpoints = listOf(
                DiscoveredEndpoint(NodeTransportType.LOCAL_WIFI, address = "192.168.1.188", port = 50005),
                DiscoveredEndpoint(NodeTransportType.WIFI_DIRECT, address = "192.168.49.10", port = 50005),
                DiscoveredEndpoint(NodeTransportType.BLUETOOTH_LE, address = "AA:BB:CC:11:22:33")
            )
        )

        HatLinkManager.updateCandidatesFromDiscoveredNode(entry)
        val candidates = HatLinkManager.getCandidatesForNode(entry.id)

        // LAN and WIFI_DIRECT must be added, BLE must NOT be added
        assertEquals(2, candidates.size)
        assertTrue(candidates.any { it.type == HatTransportType.LAN && it.endpointAddress == "192.168.1.188" })
        assertTrue(candidates.any { it.type == HatTransportType.WIFI_DIRECT && it.endpointAddress == "192.168.49.10" })
        assertFalse(candidates.any { it.type == HatTransportType.BLE })
    }

    // ─── 3. Link create/close retains the candidate ledger ────────────────────

    @Test
    fun testRetainDiscoveredNodeStateAcrossCloseLink() {
        val lanCand = TransportCandidate(
            type = HatTransportType.LAN,
            availability = TransportAvailability.AVAILABLE,
            endpointAddress = "127.0.0.1",
            endpointPort = 50005
        )
        HatLinkManager.setNodeCandidates(remoteNodeA, listOf(lanCand))

        val link = HatLinkManager.getOrCreateLink(
            remoteNode = remoteNodeA,
            transportType = NodeTransportType.LOCAL_WIFI,
            remoteAddress = "127.0.0.1",
            remotePort = 50005
        )
        HatLinkManager.closeLink(link.id)
        assertEquals(0, HatLinkManager.activeLinks.value.size)

        // Discovered node link context and candidates survive the disconnect
        val context = HatLinkManager.getNodeLinkContext(remoteNodeA.id)
        assertNotNull(context)
        assertEquals(remoteNodeA.id, context!!.remoteNode.id)
        assertEquals(1, context.candidateTransports.size)
        assertEquals(HatTransportType.LAN, context.lastSelectedTransport)
        assertEquals(LinkState.DISCONNECTED, context.activeLink?.state)
    }

    // ─── 4. Diagnostics Snapshot ──────────────────────────────────────────────

    @Test
    fun testDiagnosticsSnapshotIncludesTrackedNodes() {
        val lanCand = TransportCandidate(
            type = HatTransportType.LAN,
            availability = TransportAvailability.AVAILABLE,
            endpointAddress = "127.0.0.1",
            endpointPort = 50005
        )
        HatLinkManager.setNodeCandidates(remoteNodeA, listOf(lanCand))
        HatLinkManager.getOrCreateLink(
            remoteNode = remoteNodeA,
            transportType = NodeTransportType.LOCAL_WIFI,
            remoteAddress = "127.0.0.1",
            remotePort = 50005
        )

        val diag = HatLinkManager.getDiagnosticsSnapshot()
        assertEquals(1, diag["activeLinksCount"])
        assertEquals(1, diag["trackedNodesCount"])

        @Suppress("UNCHECKED_CAST")
        val tracked = diag["trackedNodes"] as List<Map<String, Any?>>
        assertEquals(1, tracked.size)
        assertEquals(remoteNodeA.id, tracked[0]["nodeId"])
        assertEquals(true, tracked[0]["hasActiveLink"])
        assertEquals("LAN", tracked[0]["lastSelectedTransport"])
    }
}
