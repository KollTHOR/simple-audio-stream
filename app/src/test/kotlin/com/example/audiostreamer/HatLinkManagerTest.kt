package com.example.audiostreamer

import com.example.audiostreamer.node.AttemptState
import com.example.audiostreamer.node.CustomTransportPriority
import com.example.audiostreamer.node.DevicePlatformInfo
import com.example.audiostreamer.node.DiscoveredNodeLinkContext
import com.example.audiostreamer.node.HatLink
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
import com.example.audiostreamer.node.TransportPriority
import com.example.audiostreamer.node.discovery.DiscoveredEndpoint
import com.example.audiostreamer.node.discovery.DiscoveredNodeEntry
import com.example.audiostreamer.node.discovery.DiscoverySource
import com.example.audiostreamer.node.transport.HatTransportType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HatLinkManagerTest {

    private lateinit var localNode: NodeInfo
    private lateinit var remoteNodeA: NodeInfo
    private lateinit var remoteNodeB: NodeInfo

    @Before
    fun setUp() {
        HatLinkManager.clear()

        localNode = NodeInfo(
            identity = NodeIdentity("hat-node-local-001", "Local Pixel"),
            deviceInfo = DevicePlatformInfo(manufacturer = "Google", model = "Pixel 8"),
            capabilities = NodeCapabilities(
                supportedTransports = setOf(NodeTransportType.LOCAL_WIFI, NodeTransportType.WIFI_DIRECT, NodeTransportType.WIFI_AWARE)
            ),
            state = NodeState.AVAILABLE
        )

        remoteNodeA = NodeInfo(
            identity = NodeIdentity("hat-node-remote-aaa", "Remote Node A"),
            deviceInfo = DevicePlatformInfo(manufacturer = "Sony", model = "WH-1000XM5"),
            capabilities = NodeCapabilities(
                supportedTransports = setOf(NodeTransportType.LOCAL_WIFI, NodeTransportType.WIFI_DIRECT, NodeTransportType.WIFI_AWARE)
            ),
            state = NodeState.AVAILABLE,
            activeRole = StreamRole.RECEIVER
        )

        remoteNodeB = NodeInfo(
            identity = NodeIdentity("hat-node-remote-bbb", "Remote Node B"),
            deviceInfo = DevicePlatformInfo(manufacturer = "Shanling", model = "M300"),
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
        val awareCand = TransportCandidate(
            type = HatTransportType.WIFI_AWARE,
            availability = TransportAvailability.PROBING,
            isDirect = true
        )

        HatLinkManager.setNodeCandidates(remoteNodeA, listOf(lanCand, p2pCand, awareCand))
        val candidates = HatLinkManager.getCandidatesForNode(remoteNodeA.id)
        assertEquals(3, candidates.size)
        assertTrue(candidates.any { it.type == HatTransportType.LAN })
        assertTrue(candidates.any { it.type == HatTransportType.WIFI_DIRECT })
        assertTrue(candidates.any { it.type == HatTransportType.WIFI_AWARE })
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

    // ─── 2. Explicit Policy Transport Selection ───────────────────────────────

    @Test
    fun testExplicitPolicySelectionWithoutHardcodedArbitraryLogic() {
        val lanCand = TransportCandidate(
            type = HatTransportType.LAN,
            availability = TransportAvailability.AVAILABLE,
            endpointAddress = "192.168.1.100"
        )
        val directCand = TransportCandidate(
            type = HatTransportType.WIFI_DIRECT,
            availability = TransportAvailability.AVAILABLE,
            endpointAddress = "192.168.49.2",
            isDirect = true
        )
        val awareCand = TransportCandidate(
            type = HatTransportType.WIFI_AWARE,
            availability = TransportAvailability.AVAILABLE,
            endpointAddress = "fe80::1",
            isDirect = true
        )

        HatLinkManager.setNodeCandidates(remoteNodeA, listOf(lanCand, directCand, awareCand))

        // Policy: WIFI_AWARE_FIRST -> Wi-Fi Aware selected
        val awareFirst = HatLinkManager.selectTransport(remoteNodeA.id, TransportPriority.WIFI_AWARE_FIRST)
        assertNotNull(awareFirst)
        assertEquals(HatTransportType.WIFI_AWARE, awareFirst!!.type)

        // Policy: WIFI_DIRECT_FIRST -> Wi-Fi Direct selected
        val directFirst = HatLinkManager.selectTransport(remoteNodeA.id, TransportPriority.WIFI_DIRECT_FIRST)
        assertNotNull(directFirst)
        assertEquals(HatTransportType.WIFI_DIRECT, directFirst!!.type)

        // Policy: LAN_FIRST -> LAN selected
        val lanFirst = HatLinkManager.selectTransport(remoteNodeA.id, TransportPriority.LAN_FIRST)
        assertNotNull(lanFirst)
        assertEquals(HatTransportType.LAN, lanFirst!!.type)

        // Custom policy: [LAN, WIFI_DIRECT]
        val customPolicy = CustomTransportPriority("CUSTOM_ORDER", listOf(HatTransportType.LAN, HatTransportType.WIFI_DIRECT))
        val customSelected = HatLinkManager.selectTransport(remoteNodeA.id, customPolicy)
        assertNotNull(customSelected)
        assertEquals(HatTransportType.LAN, customSelected!!.type)
    }

    @Test
    fun testUnusableCandidatesIgnoredByPolicySelection() {
        val failedAware = TransportCandidate(
            type = HatTransportType.WIFI_AWARE,
            availability = TransportAvailability.FAILED,
            failureReason = "Attach timed out"
        )
        val unavailDirect = TransportCandidate(
            type = HatTransportType.WIFI_DIRECT,
            availability = TransportAvailability.UNAVAILABLE
        )
        val readyLan = TransportCandidate(
            type = HatTransportType.LAN,
            availability = TransportAvailability.AVAILABLE,
            endpointAddress = "192.168.1.50"
        )

        HatLinkManager.setNodeCandidates(remoteNodeA, listOf(failedAware, unavailDirect, readyLan))

        // Even with WIFI_AWARE_FIRST policy, unusable candidates are skipped and LAN is selected
        val selected = HatLinkManager.selectTransport(remoteNodeA.id, TransportPriority.WIFI_AWARE_FIRST)
        assertNotNull(selected)
        assertEquals(HatTransportType.LAN, selected!!.type)
    }

    // ─── 3. Avoid Duplicate Connections ───────────────────────────────────────

    @Test
    fun testAvoidDuplicateConnections() = runBlocking {
        val lanCand = TransportCandidate(
            type = HatTransportType.LAN,
            availability = TransportAvailability.AVAILABLE,
            endpointAddress = "127.0.0.1",
            endpointPort = 50005
        )
        HatLinkManager.setNodeCandidates(remoteNodeA, listOf(lanCand))

        // First connection attempt establishes the link
        val firstResult = HatLinkManager.connectToNode(remoteNodeA, TransportPriority.LAN_FIRST)
        assertTrue(firstResult.isSuccess)
        val firstLink = firstResult.getOrThrow()
        assertEquals(LinkState.CONNECTED, firstLink.state)
        assertEquals(1, HatLinkManager.activeLinks.value.size)

        // Second connection attempt to the same node returns the existing link
        val secondResult = HatLinkManager.connectToNode(remoteNodeA, TransportPriority.LAN_FIRST)
        assertTrue(secondResult.isSuccess)
        val secondLink = secondResult.getOrThrow()
        assertEquals(firstLink.id, secondLink.id)
        assertEquals(1, HatLinkManager.activeLinks.value.size)
    }

    // ─── 4. Serialize Competing Connection Attempts to Same Node ──────────────

    @Test
    fun testSerializeCompetingAttemptsToSameNode() = runBlocking {
        val lanCand = TransportCandidate(
            type = HatTransportType.LAN,
            availability = TransportAvailability.AVAILABLE,
            endpointAddress = "127.0.0.1",
            endpointPort = 50005
        )
        HatLinkManager.setNodeCandidates(remoteNodeA, listOf(lanCand))

        // Launch 5 competing coroutines connecting to the SAME node simultaneously
        val deferreds = (1..5).map {
            async {
                HatLinkManager.connectToNode(remoteNodeA, TransportPriority.LAN_FIRST)
            }
        }
        val results = deferreds.awaitAll()

        // All must succeed
        results.forEach { res ->
            assertTrue(res.isSuccess)
        }

        // All must have received the EXACT SAME link ID (serialized, no duplicates)
        val uniqueLinkIds = results.map { it.getOrThrow().id }.toSet()
        assertEquals(1, uniqueLinkIds.size)
        assertEquals(1, HatLinkManager.activeLinks.value.size)
    }

    // ─── 5. Stale Attempt Cancellation ────────────────────────────────────────

    @Test
    fun testCancelStaleAttempt() {
        val directCand = TransportCandidate(
            type = HatTransportType.WIFI_DIRECT,
            availability = TransportAvailability.AVAILABLE,
            endpointAddress = "192.168.49.2"
        )
        HatLinkManager.setNodeCandidates(remoteNodeA, listOf(directCand))

        val context = HatLinkManager.getNodeLinkContext(remoteNodeA.id)
        assertNotNull(context)

        // Inject an in-progress attempt
        val inProgressAttempt = com.example.audiostreamer.node.LinkAttempt(
            remoteNodeId = remoteNodeA.id,
            candidate = directCand,
            state = AttemptState.IN_PROGRESS
        )
        context!!.recordAttempt(inProgressAttempt)
        assertEquals(AttemptState.IN_PROGRESS, context.currentAttempt?.state)

        // Explicit cancellation
        val cancelled = HatLinkManager.cancelAttempt(remoteNodeA.id, "Explicit test cancellation")
        assertTrue(cancelled)
        assertEquals(AttemptState.CANCELLED, inProgressAttempt.state)
        assertEquals("Explicit test cancellation", inProgressAttempt.failureReason)
    }

    // ─── 6. Exact Failure Reasons ─────────────────────────────────────────────

    @Test
    fun testExactFailureReasonWhenNoCandidatesAvailable() = runBlocking {
        // Node with zero candidate transports
        val result = HatLinkManager.connectToNode(remoteNodeB, TransportPriority.DEFAULT)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex!!.message!!.contains("No transport candidates available for node ${remoteNodeB.id}"))

        val context = HatLinkManager.getNodeLinkContext(remoteNodeB.id)
        assertNotNull(context)
        assertNotNull(context!!.currentAttempt)
        assertEquals(AttemptState.FAILED, context.currentAttempt!!.state)
        assertEquals(ex.message, context.currentAttempt!!.failureReason)
    }

    @Test
    fun testExactFailureReasonWhenEndpointAddressMissing() = runBlocking {
        val missingAddressCand = TransportCandidate(
            type = HatTransportType.WIFI_DIRECT,
            availability = TransportAvailability.AVAILABLE,
            endpointAddress = null // missing!
        )
        HatLinkManager.setNodeCandidates(remoteNodeA, listOf(missingAddressCand))

        val result = HatLinkManager.connectToNode(remoteNodeA, TransportPriority.WIFI_DIRECT_FIRST, allowFallback = false)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex!!.message!!.contains("Endpoint address missing for transport candidate WIFI_DIRECT"))

        val context = HatLinkManager.getNodeLinkContext(remoteNodeA.id)
        assertEquals(AttemptState.FAILED, context!!.currentAttempt?.state)
    }

    @Test
    fun testExactFailureReasonWhenTransportConnectFails() = runBlocking {
        // Wi-Fi Aware connect returns UnsupportedOperationException("Wi-Fi Aware transport is not implemented yet")
        val awareCand = TransportCandidate(
            type = HatTransportType.WIFI_AWARE,
            availability = TransportAvailability.AVAILABLE,
            endpointAddress = "192.168.49.20"
        )
        HatLinkManager.setNodeCandidates(remoteNodeA, listOf(awareCand))

        val result = HatLinkManager.connectToNode(remoteNodeA, TransportPriority.WIFI_AWARE_FIRST, allowFallback = false)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex!!.message!!.contains("Wi-Fi Aware transport is not implemented yet"))

        val context = HatLinkManager.getNodeLinkContext(remoteNodeA.id)
        assertNotNull(context)
        val lastAttempt = context!!.currentAttempt
        assertNotNull(lastAttempt)
        assertEquals(AttemptState.FAILED, lastAttempt!!.state)
        assertTrue(lastAttempt.failureReason!!.contains("Wi-Fi Aware transport is not implemented yet"))

        // Candidate marked as FAILED in context
        val candidateInContext = context.candidateTransports.first { it.type == HatTransportType.WIFI_AWARE }
        assertEquals(TransportAvailability.FAILED, candidateInContext.availability)
    }

    // ─── 7. Candidate Fallback ────────────────────────────────────────────────

    @Test
    fun testCandidateFallbackWhenPreferredTransportFails() = runBlocking {
        // Preferred: Wi-Fi Aware (will fail because not implemented)
        // Fallback: LAN (will succeed connecting to 127.0.0.1)
        val awareCand = TransportCandidate(
            type = HatTransportType.WIFI_AWARE,
            availability = TransportAvailability.AVAILABLE,
            endpointAddress = "192.168.49.20"
        )
        val lanCand = TransportCandidate(
            type = HatTransportType.LAN,
            availability = TransportAvailability.AVAILABLE,
            endpointAddress = "127.0.0.1",
            endpointPort = 50005
        )

        HatLinkManager.setNodeCandidates(remoteNodeA, listOf(awareCand, lanCand))

        // Policy prefers WIFI_AWARE first, then LAN
        val result = HatLinkManager.connectToNode(remoteNodeA, TransportPriority.WIFI_AWARE_FIRST, allowFallback = true)
        assertTrue(result.isSuccess)
        val link = result.getOrThrow()
        assertEquals(HatTransportType.LAN, link.activeTransportType)

        val context = HatLinkManager.getNodeLinkContext(remoteNodeA.id)
        assertNotNull(context)
        // History should have 2 attempts: first FAILED (WIFI_AWARE), second SUCCESS (LAN)
        assertEquals(2, context!!.attemptHistory.size)
        assertEquals(HatTransportType.WIFI_AWARE, context.attemptHistory[0].candidate.type)
        assertEquals(AttemptState.FAILED, context.attemptHistory[0].state)
        assertEquals(HatTransportType.LAN, context.attemptHistory[1].candidate.type)
        assertEquals(AttemptState.SUCCESS, context.attemptHistory[1].state)
    }

    // ─── 8. Retain Discovered Node State Across Disconnections ────────────────

    @Test
    fun testRetainDiscoveredNodeStateAcrossCloseLink() = runBlocking {
        val lanCand = TransportCandidate(
            type = HatTransportType.LAN,
            availability = TransportAvailability.AVAILABLE,
            endpointAddress = "127.0.0.1",
            endpointPort = 50005
        )
        HatLinkManager.setNodeCandidates(remoteNodeA, listOf(lanCand))

        val connectResult = HatLinkManager.connectToNode(remoteNodeA, TransportPriority.LAN_FIRST)
        assertTrue(connectResult.isSuccess)
        val link = connectResult.getOrThrow()

        // Close link
        HatLinkManager.closeLink(link.id)
        assertEquals(0, HatLinkManager.activeLinks.value.size)

        // Discovered node link context and candidates remain intact
        val context = HatLinkManager.getNodeLinkContext(remoteNodeA.id)
        assertNotNull(context)
        assertEquals(remoteNodeA.id, context!!.remoteNode.id)
        assertEquals(1, context.candidateTransports.size)
        assertEquals(HatTransportType.LAN, context.lastSelectedTransport)
        assertEquals(LinkState.DISCONNECTED, context.activeLink?.state)
    }

    // ─── 9. Reconnect When Appropriate ────────────────────────────────────────

    @Test
    fun testReconnectAfterLinkClosedOrFailed() = runBlocking {
        val lanCand = TransportCandidate(
            type = HatTransportType.LAN,
            availability = TransportAvailability.AVAILABLE,
            endpointAddress = "127.0.0.1",
            endpointPort = 50005
        )
        HatLinkManager.setNodeCandidates(remoteNodeA, listOf(lanCand))

        val firstLink = HatLinkManager.connectToNode(remoteNodeA, TransportPriority.LAN_FIRST).getOrThrow()
        HatLinkManager.closeLink(firstLink.id)

        // Call reconnect
        val reconnectResult = HatLinkManager.reconnect(remoteNodeA.id, TransportPriority.LAN_FIRST)
        assertTrue(reconnectResult.isSuccess)
        val reconnectedLink = reconnectResult.getOrThrow()
        assertTrue(reconnectedLink.isAlive)
        assertEquals(LinkState.CONNECTED, reconnectedLink.state)
        assertEquals(1, HatLinkManager.activeLinks.value.size)
    }

    // ─── 10. Active Transport Exposure ────────────────────────────────────────

    @Test
    fun testActiveTransportExposure() = runBlocking {
        val lanCand = TransportCandidate(
            type = HatTransportType.LAN,
            availability = TransportAvailability.AVAILABLE,
            endpointAddress = "127.0.0.1",
            endpointPort = 50005
        )
        HatLinkManager.setNodeCandidates(remoteNodeA, listOf(lanCand))

        val link = HatLinkManager.connectToNode(remoteNodeA, TransportPriority.LAN_FIRST).getOrThrow()

        // Link level
        assertNotNull(link.activeTransport)
        assertEquals(HatTransportType.LAN, link.activeTransportType)

        // Manager level
        assertEquals(HatTransportType.LAN, HatLinkManager.getActiveTransportForNode(remoteNodeA.id))
        assertEquals(HatTransportType.LAN, HatLinkManager.getActiveTransport(link.id))

        // Application transparency: link.send() and link.receive() work through the active transport
        assertTrue(link.canSend)
        assertTrue(link.canReceive)
    }

    // ─── 11. DiscoveredNodeEntry Extraction ───────────────────────────────────

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

    // ─── 12. Diagnostics Snapshot ─────────────────────────────────────────────

    @Test
    fun testDiagnosticsSnapshotIncludesTrackedNodesAndAttempts() = runBlocking {
        val lanCand = TransportCandidate(
            type = HatTransportType.LAN,
            availability = TransportAvailability.AVAILABLE,
            endpointAddress = "127.0.0.1",
            endpointPort = 50005
        )
        HatLinkManager.setNodeCandidates(remoteNodeA, listOf(lanCand))
        HatLinkManager.connectToNode(remoteNodeA, TransportPriority.LAN_FIRST).getOrThrow()

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
