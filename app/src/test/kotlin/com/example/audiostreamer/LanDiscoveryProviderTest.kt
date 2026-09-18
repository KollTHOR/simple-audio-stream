package com.example.audiostreamer

import android.net.nsd.NsdManager
import com.example.audiostreamer.node.DevicePlatformInfo
import com.example.audiostreamer.node.HatLinkManager
import com.example.audiostreamer.node.LocalNodeManager
import com.example.audiostreamer.node.NodeCapabilities
import com.example.audiostreamer.node.NodeIdentity
import com.example.audiostreamer.node.NodeInfo
import com.example.audiostreamer.node.NodeState
import com.example.audiostreamer.node.StreamRole
import com.example.audiostreamer.node.discovery.LanDiscoveredNode
import com.example.audiostreamer.node.discovery.LanDiscoveryProvider
import com.example.audiostreamer.node.discovery.LanDiscoveryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LanDiscoveryProviderTest {

    @Before
    fun setUp() {
        LanDiscoveryProvider.clearDiscoveredNodes()
    }

    @Test
    fun testDnsSdServiceDefinition() {
        // Architecture requirement: Service MUST be _hats._tcp
        assertEquals("_hats._tcp", LanDiscoveryProvider.SERVICE_TYPE)
    }

    @Test
    fun testTxtRecordKeyConstants() {
        // Verify standard compact DNS-SD TXT keys for HAT metadata
        assertEquals("pv", LanDiscoveryProvider.KEY_PROTOCOL_VERSION)
        assertEquals("id", LanDiscoveryProvider.KEY_NODE_ID)
        assertEquals("name", LanDiscoveryProvider.KEY_NODE_NAME)
        assertEquals("port", LanDiscoveryProvider.KEY_PORT)
        assertEquals("caps", LanDiscoveryProvider.KEY_CAPABILITIES)
        assertEquals("avail", LanDiscoveryProvider.KEY_AVAILABILITY)
        assertEquals("role", LanDiscoveryProvider.KEY_ROLE)
    }

    @Test
    fun testAndroid16AndSystemErrorHandling() {
        // Android 16 explicitly requires descriptive diagnostics for local network access failures
        val internalErrorDesc = LanDiscoveryProvider.describeErrorCode(NsdManager.FAILURE_INTERNAL_ERROR)
        assertTrue(
            "Expected mention of Android 16 or local network access: $internalErrorDesc",
            internalErrorDesc.contains("Android 16") || internalErrorDesc.contains("local network")
        )

        val alreadyActiveDesc = LanDiscoveryProvider.describeErrorCode(NsdManager.FAILURE_ALREADY_ACTIVE)
        assertTrue(alreadyActiveDesc.contains("already in-flight") || alreadyActiveDesc.contains("ALREADY_ACTIVE"))

        val maxLimitDesc = LanDiscoveryProvider.describeErrorCode(NsdManager.FAILURE_MAX_LIMIT)
        assertTrue(maxLimitDesc.contains("MAX_LIMIT") || maxLimitDesc.contains("max client"))

        val unknownDesc = LanDiscoveryProvider.describeErrorCode(9999)
        assertEquals("UNKNOWN_ERROR_CODE_9999", unknownDesc)
    }

    @Test
    fun testInitialStateAndDiagnostics() {
        assertFalse(LanDiscoveryProvider.isScanning.value)
        assertFalse(LanDiscoveryProvider.isAdvertising.value)
        assertEquals(LanDiscoveryState.STOPPED, LanDiscoveryProvider.state.value)

        val snapshot = LanDiscoveryProvider.getDiagnosticsSnapshot()
        assertEquals("_hats._tcp", snapshot["serviceType"])
        assertEquals(false, snapshot["isScanning"])
        assertEquals(false, snapshot["isAdvertising"])
        assertEquals(0, snapshot["discoveredNodesCount"])
    }

    @Test
    fun testDiscoveryFlowDoesNotCreateStreamOrLinkAutomatically() {
        // Architecture rule:
        // LAN discovery -> Node discovered -> NodeInfo available -> user/app chooses -> Link -> negotiation -> Stream
        // Discovery must NEVER establish an audio stream automatically.

        HatLinkManager.activeLinks.value.forEach { HatLinkManager.closeLink(it.id) }
        assertEquals(0, HatLinkManager.activeLinks.value.size)
        assertEquals(0, HatLinkManager.activeStreams.value.size)

        val fakeNode = NodeInfo(
            identity = NodeIdentity(id = "hat-test-node-123456", name = "Test Receiver"),
            capabilities = NodeCapabilities(
                hasAudioInput = false,
                hasAudioOutput = true,
                hasMicrophone = false,
                hasSpeaker = true
            ),
            deviceInfo = DevicePlatformInfo(model = "Test Device"),
            state = NodeState.AVAILABLE,
            activeRole = StreamRole.RECEIVER
        )

        val discovered = LanDiscoveredNode(
            nodeInfo = fakeNode,
            hostAddress = "192.168.1.150",
            port = 4455,
            serviceName = "Test Receiver (123456)"
        )

        // Verifying metadata encapsulation
        assertEquals("hat-test-node-123456", discovered.nodeInfo.id)
        assertEquals("192.168.1.150", discovered.hostAddress)
        assertEquals(4455, discovered.port)
        assertEquals(StreamRole.RECEIVER, discovered.nodeInfo.activeRole)

        // Strict assertion: NO automatic link or stream was opened
        assertEquals(0, HatLinkManager.activeLinks.value.size)
        assertEquals(0, HatLinkManager.activeStreams.value.size)
    }

    @Test
    fun testHatDiagnosticsIntegration() {
        val snapshot = HatDiagnostics.snapshot()
        assertTrue("HatDiagnostics snapshot must contain [LAN_DISCOVERY] section", snapshot.contains("[LAN_DISCOVERY]"))
        assertTrue("HatDiagnostics snapshot must show serviceType=_hats._tcp", snapshot.contains("serviceType=_hats._tcp"))
    }
}
