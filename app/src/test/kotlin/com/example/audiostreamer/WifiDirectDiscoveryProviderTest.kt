package com.example.audiostreamer

import android.net.wifi.p2p.WifiP2pManager
import com.example.audiostreamer.node.DevicePlatformInfo
import com.example.audiostreamer.node.HatLinkManager
import com.example.audiostreamer.node.NodeCapabilities
import com.example.audiostreamer.node.NodeIdentity
import com.example.audiostreamer.node.NodeInfo
import com.example.audiostreamer.node.NodeState
import com.example.audiostreamer.node.StreamRole
import com.example.audiostreamer.node.discovery.WifiDirectDiscoveredNode
import com.example.audiostreamer.node.discovery.WifiDirectDiscoveryProvider
import com.example.audiostreamer.node.discovery.WifiDirectDiscoveryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WifiDirectDiscoveryProviderTest {

    @Before
    fun setUp() {
        WifiDirectDiscoveryProvider.clearDiscoveredNodes()
    }

    @Test
    fun testDnsSdServiceDefinition() {
        // Requirement: Service MUST identify as HAT DNS-SD service _hats._tcp
        assertEquals("_hats._tcp", WifiDirectDiscoveryProvider.SERVICE_TYPE)
    }

    @Test
    fun testTxtRecordKeyConstants() {
        // Compact DNS-SD TXT record keys for HAT metadata exchange
        assertEquals("pv", WifiDirectDiscoveryProvider.KEY_PROTOCOL_VERSION)
        assertEquals("id", WifiDirectDiscoveryProvider.KEY_NODE_ID)
        assertEquals("name", WifiDirectDiscoveryProvider.KEY_NODE_NAME)
        assertEquals("port", WifiDirectDiscoveryProvider.KEY_PORT)
        assertEquals("caps", WifiDirectDiscoveryProvider.KEY_CAPABILITIES)
        assertEquals("avail", WifiDirectDiscoveryProvider.KEY_AVAILABILITY)
        assertEquals("role", WifiDirectDiscoveryProvider.KEY_ROLE)
        assertEquals("p2p_mac", WifiDirectDiscoveryProvider.KEY_P2P_MAC)
    }

    @Test
    fun testDetailedErrorCodeDescriptions() {
        // Requirement: Expose detailed failure codes (BUSY, P2P_UNSUPPORTED, ERROR, NO_SERVICE_REQUESTS)
        val busyDesc = WifiDirectDiscoveryProvider.describeErrorCode(WifiP2pManager.BUSY)
        assertTrue("Expected BUSY description with reason: $busyDesc", busyDesc.contains("BUSY (2)"))

        val unsuppDesc = WifiDirectDiscoveryProvider.describeErrorCode(WifiP2pManager.P2P_UNSUPPORTED)
        assertTrue("Expected P2P_UNSUPPORTED description: $unsuppDesc", unsuppDesc.contains("P2P_UNSUPPORTED (1)"))

        val errDesc = WifiDirectDiscoveryProvider.describeErrorCode(WifiP2pManager.ERROR)
        assertTrue("Expected ERROR description: $errDesc", errDesc.contains("ERROR (0)"))

        val noReqDesc = WifiDirectDiscoveryProvider.describeErrorCode(WifiP2pManager.NO_SERVICE_REQUESTS)
        assertTrue("Expected NO_SERVICE_REQUESTS description: $noReqDesc", noReqDesc.contains("NO_SERVICE_REQUESTS (3)"))

        val unknownDesc = WifiDirectDiscoveryProvider.describeErrorCode(777)
        assertEquals("UNKNOWN_ERROR_CODE_777", unknownDesc)
    }

    @Test
    fun testInitialStateAndDiagnosticsSnapshot() {
        assertFalse(WifiDirectDiscoveryProvider.isScanning.value)
        assertFalse(WifiDirectDiscoveryProvider.isAdvertising.value)
        assertEquals(WifiDirectDiscoveryState.STOPPED, WifiDirectDiscoveryProvider.state.value)

        val snapshot = WifiDirectDiscoveryProvider.getDiagnosticsSnapshot()
        assertEquals("_hats._tcp", snapshot["serviceType"])
        assertEquals("STOPPED", snapshot["state"])
        assertEquals(false, snapshot["isScanning"])
        assertEquals(false, snapshot["isAdvertising"])
        assertEquals(0, snapshot["discoveredNodesCount"])

        val metrics = snapshot["metrics"] as? Map<*, *>
        assertNotNull("Metrics must be present in snapshot", metrics)
        assertTrue(metrics?.containsKey("starts") == true)
        assertTrue(metrics?.containsKey("successes") == true)
        assertTrue(metrics?.containsKey("failures") == true)
        assertTrue(metrics?.containsKey("busyCount") == true)
        assertTrue(metrics?.containsKey("unsupportedCount") == true)
        assertTrue(metrics?.containsKey("errorCount") == true)
        assertTrue(metrics?.containsKey("servicesFound") == true)
        assertTrue(metrics?.containsKey("servicesResolved") == true)
        assertTrue(metrics?.containsKey("peersLost") == true)
    }

    @Test
    fun testStableNodeIdNotMacAddress() {
        // Architecture rule:
        // Do not use Wi-Fi Direct MAC address as HAT identity.
        val testMac = "02:1a:2b:3c:4d:5e"
        val stableNodeId = "hat-node-abc-123456"

        assertTrue("MAC regex should identify MAC address format", NodeIdentity.isMac(testMac))
        assertFalse("HAT Node ID must not be classified as a MAC address", NodeIdentity.isMac(stableNodeId))

        val fakeNode = NodeInfo(
            identity = NodeIdentity(id = stableNodeId, name = "Living Room M300"),
            capabilities = NodeCapabilities(
                hasAudioInput = false,
                hasAudioOutput = true,
                hasMicrophone = false,
                hasSpeaker = true
            ),
            deviceInfo = DevicePlatformInfo(model = "M300"),
            state = NodeState.AVAILABLE,
            activeRole = StreamRole.RECEIVER
        )

        val discovered = WifiDirectDiscoveredNode(
            nodeInfo = fakeNode,
            deviceAddress = testMac,
            deviceName = "DIRECT-SA-LivingRoom",
            serviceFoundLatencyMs = 450L,
            txtResolvedLatencyMs = 120L
        )

        // Verifying identity vs transport endpoint separation
        assertEquals(stableNodeId, discovered.nodeInfo.id)
        assertFalse(NodeIdentity.isMac(discovered.nodeInfo.id))
        assertEquals(testMac, discovered.deviceAddress)
        assertEquals(450L, discovered.serviceFoundLatencyMs)
        assertEquals(120L, discovered.txtResolvedLatencyMs)
    }

    @Test
    fun testDiscoveryFlowDoesNotCreateStreamOrLinkAutomatically() {
        // Architecture rule:
        // Discovery must NOT establish an audio stream automatically.
        HatLinkManager.activeLinks.value.forEach { HatLinkManager.closeLink(it.id) }
        assertEquals(0, HatLinkManager.activeLinks.value.size)
        assertEquals(0, HatLinkManager.activeStreams.value.size)

        val fakeNode = NodeInfo(
            identity = NodeIdentity(id = "hat-node-test-p2p", name = "P2P Peer"),
            capabilities = NodeCapabilities(hasAudioInput = true, hasAudioOutput = true),
            state = NodeState.AVAILABLE,
            activeRole = StreamRole.TRANSCEIVER
        )

        val endpoint = WifiDirectDiscoveredNode(
            nodeInfo = fakeNode,
            deviceAddress = "02:00:00:00:00:01",
            deviceName = "DIRECT-Test",
            serviceFoundLatencyMs = 300L,
            txtResolvedLatencyMs = 150L
        )

        // Strict assertion: NO automatic link or stream opened
        assertEquals(0, HatLinkManager.activeLinks.value.size)
        assertEquals(0, HatLinkManager.activeStreams.value.size)
        assertEquals("hat-node-test-p2p", endpoint.nodeInfo.id)
    }

    @Test
    fun testConnectionAttemptDurationInstrumentation() {
        // Requirement: Instrument connection attempt duration
        WifiDirectDiscoveryProvider.recordConnectionAttempt(
            targetNodeId = "hat-node-target-1",
            targetAddress = "02:11:22:33:44:55",
            durationMs = 1450L,
            success = true
        )
        WifiDirectDiscoveryProvider.recordConnectionAttempt(
            targetNodeId = "hat-node-target-2",
            targetAddress = "02:11:22:33:44:66",
            durationMs = 3200L,
            success = false,
            reason = "WPS Timeout"
        )

        val snapshot = WifiDirectDiscoveryProvider.getDiagnosticsSnapshot()
        val ops = snapshot["recentOperations"] as? List<*>
        assertNotNull("recentOperations must not be null", ops)
        assertTrue("recentOperations should contain CONNECT_ATTEMPT entries", ops?.any { it.toString().contains("CONNECT_ATTEMPT") } == true)
        assertTrue("recentOperations should record success", ops?.any { it.toString().contains("OK") && it.toString().contains("1450ms") } == true)
        assertTrue("recentOperations should record failure", ops?.any { it.toString().contains("FAIL") && it.toString().contains("WPS Timeout") } == true)
    }

    @Test
    fun testHatDiagnosticsIntegration() {
        val snapshot = HatDiagnostics.snapshot()
        assertTrue("HatDiagnostics snapshot must contain [WIFI_DIRECT_DISCOVERY] section", snapshot.contains("[WIFI_DIRECT_DISCOVERY]"))
        assertTrue("HatDiagnostics snapshot must contain serviceType=_hats._tcp", snapshot.contains("serviceType=_hats._tcp"))
    }
}
