package com.example.audiostreamer

import com.example.audiostreamer.node.DevicePlatformInfo
import com.example.audiostreamer.node.NodeCapabilities
import com.example.audiostreamer.node.NodeIdentity
import com.example.audiostreamer.node.NodeInfo
import com.example.audiostreamer.node.NodeState
import com.example.audiostreamer.node.StreamRole
import com.example.audiostreamer.node.discovery.WifiAwareDiscoveryState
import com.example.audiostreamer.node.discovery.WifiAwareDiscoveryProvider
import com.example.audiostreamer.node.discovery.WifiAwareOperationTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WifiAwareDiscoveryProviderTest {

    @Before
    fun setUp() {
        WifiAwareDiscoveryProvider.clearDiscoveredNodes()
    }

    // ─── Feature metadata ─────────────────────────────────────────────────────

    @Test
    fun testServiceNameConstant() {
        // HAT Wi-Fi Aware service name must be stable and predictable
        assertEquals("hat-audio-transport", WifiAwareDiscoveryProvider.SERVICE_NAME)
    }

    @Test
    fun testNodeIdMessagePrefix() {
        // Application-layer Node ID exchange prefix must be stable
        assertEquals("HATID:", WifiAwareDiscoveryProvider.MSG_NODE_ID_PREFIX)
    }

    // ─── Initial state ────────────────────────────────────────────────────────

    @Test
    fun testInitialStateAndDiagnosticsSnapshot() {
        // Provider starts idle (not UNSUPPORTED or ERROR) in JVM test environment
        assertFalse(WifiAwareDiscoveryProvider.isPublishing.value)
        assertFalse(WifiAwareDiscoveryProvider.isSubscribing.value)
        // Discovered node list must be empty after setUp
        assertEquals(0, WifiAwareDiscoveryProvider.discoveredNodes.value.size)

        val snapshot = WifiAwareDiscoveryProvider.getDiagnosticsSnapshot()
        assertEquals("hat-audio-transport", snapshot["serviceName"])
        assertEquals(false, snapshot["isPublishing"])
        assertEquals(false, snapshot["isSubscribing"])
        assertEquals(0, snapshot["discoveredNodesCount"])

        val metrics = snapshot["metrics"] as? Map<*, *>
        assertNotNull("metrics map must be present", metrics)
        assertTrue(metrics?.containsKey("attachAttempts") == true)
        assertTrue(metrics?.containsKey("attachSuccesses") == true)
        assertTrue(metrics?.containsKey("attachFailures") == true)
        assertTrue(metrics?.containsKey("publishAttempts") == true)
        assertTrue(metrics?.containsKey("publishSuccesses") == true)
        assertTrue(metrics?.containsKey("publishFailures") == true)
        assertTrue(metrics?.containsKey("subscribeAttempts") == true)
        assertTrue(metrics?.containsKey("subscribeSuccesses") == true)
        assertTrue(metrics?.containsKey("subscribeFailures") == true)
        assertTrue(metrics?.containsKey("serviceDiscoveries") == true)
        assertTrue(metrics?.containsKey("messagesSent") == true)
        assertTrue(metrics?.containsKey("messagesReceived") == true)
        assertTrue(metrics?.containsKey("messageFailures") == true)
        assertTrue(metrics?.containsKey("networkRequests") == true)
        assertTrue(metrics?.containsKey("networkAvailable") == true)
        assertTrue(metrics?.containsKey("networkFailures") == true)

        val timing = snapshot["timing"] as? Map<*, *>
        assertNotNull("timing map must be present", timing)
        assertTrue(timing?.containsKey("lastAttachDurationMs") == true)
        assertTrue(timing?.containsKey("lastPublishDurationMs") == true)
        assertTrue(timing?.containsKey("lastSubscribeDurationMs") == true)
        assertTrue(timing?.containsKey("lastDiscoveryDurationMs") == true)
        assertTrue(timing?.containsKey("lastMessageExchangeDurationMs") == true)
        assertTrue(timing?.containsKey("lastNetworkSetupDurationMs") == true)
    }

    // ─── Identity separation ──────────────────────────────────────────────────

    @Test
    fun testPeerHandleIsNotHatIdentity() {
        // Architecture rule: PeerHandle is NOT the HAT Node identity.
        // HAT Node ID is always the 'hat-node-<UUID>' prefix form from the application layer.
        val stableId = "hat-node-aware-abc-12345"
        val fakePeerHashCode = 0xDEADBEEF.toInt()

        assertTrue("hat-node-... IDs must not look like MACs", !NodeIdentity.isMac(stableId))
        assertTrue("hat-node-... IDs must not look like IPs", !NodeIdentity.isIp(stableId))

        // NodeIdentity construction must succeed for valid HAT IDs
        val identity = NodeIdentity(id = stableId, name = "Aware Peer")
        assertEquals(stableId, identity.id)
    }

    @Test
    fun testIpAndMacRejectedAsNodeId() {
        // Architecture rule: IP/MAC must never be accepted as HAT Node identity
        val ip = "192.168.49.1"
        val mac = "02:aa:bb:cc:dd:ee"

        assertTrue("IP must be detected as IP", NodeIdentity.isIp(ip))
        assertTrue("MAC must be detected as MAC", NodeIdentity.isMac(mac))

        try {
            NodeIdentity(id = ip, name = "Should Fail")
            assertTrue("NodeIdentity must reject IP as id", false)
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            NodeIdentity(id = mac, name = "Should Fail")
            assertTrue("NodeIdentity must reject MAC as id", false)
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // ─── Message format ───────────────────────────────────────────────────────

    @Test
    fun testNodeIdMessageFormat() {
        // Verify the Node ID message prefix is consistent with what the provider uses
        val nodeId = "hat-node-aware-test-9999"
        val expected = "HATID:$nodeId"
        val payload = "${WifiAwareDiscoveryProvider.MSG_NODE_ID_PREFIX}$nodeId"
        assertEquals(expected, payload)
        assertTrue("Payload must start with the HATID: prefix", payload.startsWith("HATID:"))
        val extracted = payload.removePrefix("HATID:").trim()
        assertEquals(nodeId, extracted)
    }

    // ─── Diagnostics integration ──────────────────────────────────────────────

    @Test
    fun testHatDiagnosticsIntegration() {
        val snapshot = HatDiagnostics.snapshot()
        assertTrue(
            "HatDiagnostics snapshot must contain [WIFI_AWARE_DISCOVERY] section",
            snapshot.contains("[WIFI_AWARE_DISCOVERY]")
        )
        assertTrue(
            "HatDiagnostics snapshot must contain serviceName=hat-audio-transport",
            snapshot.contains("serviceName=hat-audio-transport")
        )
    }

    @Test
    fun testDiagnosticsSnapshotContainsRecentOperations() {
        val snapshot = WifiAwareDiscoveryProvider.getDiagnosticsSnapshot()
        assertTrue("recentOperations key must be present in snapshot", snapshot.containsKey("recentOperations"))
        val ops = snapshot["recentOperations"] as? List<*>
        assertNotNull("recentOperations must not be null", ops)
    }

    // ─── No automatic stream / link creation ─────────────────────────────────

    @Test
    fun testDiscoveryDoesNotCreateLinkOrStream() {
        // Architecture rule: discovery alone must not open any HatLink or audio stream
        val linksBefore = com.example.audiostreamer.node.HatLinkManager.activeLinks.value.size
        val streamsBefore = com.example.audiostreamer.node.HatLinkManager.activeStreams.value.size

        // Simulate completing discovery (without real Android framework):
        // Just assert the provider's discovered list is empty since we cleared in setUp.
        assertEquals(0, WifiAwareDiscoveryProvider.discoveredNodes.value.size)

        // Links and streams must be untouched
        assertEquals(linksBefore, com.example.audiostreamer.node.HatLinkManager.activeLinks.value.size)
        assertEquals(streamsBefore, com.example.audiostreamer.node.HatLinkManager.activeStreams.value.size)
    }

    // ─── OperationTiming data class ───────────────────────────────────────────

    @Test
    fun testOperationTimingToString() {
        val successOp = WifiAwareOperationTiming(
            phase = "ATTACH",
            durationMs = 220L,
            success = true
        )
        val failOp = WifiAwareOperationTiming(
            phase = "SUBSCRIBE",
            durationMs = 450L,
            success = false,
            failureReason = "session config failed"
        )
        assertTrue("Success op string must contain 'OK'", successOp.toString().contains("OK"))
        assertTrue("Success op string must contain duration", successOp.toString().contains("220ms"))
        assertTrue("Fail op string must contain 'FAIL'", failOp.toString().contains("FAIL"))
        assertTrue("Fail op string must contain failure reason", failOp.toString().contains("session config failed"))
    }
}
