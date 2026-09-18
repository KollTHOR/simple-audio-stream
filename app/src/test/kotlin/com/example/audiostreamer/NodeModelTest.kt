package com.example.audiostreamer

import com.example.audiostreamer.node.DevicePlatformInfo
import com.example.audiostreamer.node.LocalNodeManager
import com.example.audiostreamer.node.NodeAdapters
import com.example.audiostreamer.node.NodeCapabilities
import com.example.audiostreamer.node.NodeIdentity
import com.example.audiostreamer.node.NodeInfo
import com.example.audiostreamer.node.NodeState
import com.example.audiostreamer.node.NodeTransportType
import com.example.audiostreamer.node.StreamRole
import com.example.audiostreamer.node.toNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NodeModelTest {

    @Test
    fun testNodeIdentityGenerationAndValidation() {
        val identity = NodeIdentity.generate("Living Room Speaker")
        assertTrue(identity.id.startsWith(NodeIdentity.ID_PREFIX))
        assertEquals("Living Room Speaker", identity.name)

        // Verify JSON roundtrip
        val json = identity.toJson()
        val restored = NodeIdentity.fromJson(json)
        assertEquals(identity.id, restored.id)
        assertEquals(identity.name, restored.name)
    }

    @Test
    fun testNodeIdentityRejectsIpAndMacAddresses() {
        // IP address must not be accepted as NodeIdentity
        try {
            NodeIdentity(id = "192.168.1.50", name = "Test Node")
            fail("Should have rejected IP address as NodeIdentity")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("IP addresses cannot be used"))
        }

        try {
            NodeIdentity(id = "192.168.49.1:19850", name = "Test Node")
            fail("Should have rejected IP:port as NodeIdentity")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("IP addresses cannot be used"))
        }

        // MAC address must not be accepted as NodeIdentity
        try {
            NodeIdentity(id = "02:15:b2:aa:bb:cc", name = "Test Node")
            fail("Should have rejected MAC address as NodeIdentity")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("MAC addresses cannot be used"))
        }
    }

    @Test
    fun testNodeCapabilitiesAndMaskConversion() {
        val caps = NodeCapabilities(
            hasAudioInput = true,
            hasAudioOutput = true,
            hasMicrophone = true,
            hasSpeaker = true,
            supportedCodecs = setOf(AudioCodec.PCM, AudioCodec.LOSSLESS, AudioCodec.OPUS),
            supportedSampleRates = setOf(44100, 48000, 96000),
            supportedChannelCounts = setOf(2),
            supportedPcmFormats = setOf(AudioBitDepth.BIT_16, AudioBitDepth.BIT_24),
            supportedTransports = setOf(NodeTransportType.LOCAL_WIFI, NodeTransportType.WIFI_DIRECT)
        )

        val mask = caps.toCapabilitiesMask()
        assertTrue((mask and AudioCapabilities.CAP_FLAG_44100) != 0)
        assertTrue((mask and AudioCapabilities.CAP_FLAG_48000) != 0)
        assertTrue((mask and AudioCapabilities.CAP_FLAG_96000) != 0)
        assertFalse((mask and AudioCapabilities.CAP_FLAG_192000) != 0)

        // Round-trip from mask
        val restoredFromMask = NodeCapabilities.fromCapabilitiesMask(mask)
        assertTrue(restoredFromMask.supportedSampleRates.contains(44100))
        assertTrue(restoredFromMask.supportedSampleRates.contains(48000))
        assertTrue(restoredFromMask.supportedSampleRates.contains(96000))

        // JSON serialization roundtrip
        val json = caps.toJson()
        val restoredFromJson = NodeCapabilities.fromJson(json)
        assertEquals(caps.hasAudioInput, restoredFromJson.hasAudioInput)
        assertEquals(caps.hasAudioOutput, restoredFromJson.hasAudioOutput)
        assertEquals(caps.hasMicrophone, restoredFromJson.hasMicrophone)
        assertEquals(caps.hasSpeaker, restoredFromJson.hasSpeaker)
        assertEquals(caps.supportedCodecs, restoredFromJson.supportedCodecs)
        assertEquals(caps.supportedSampleRates, restoredFromJson.supportedSampleRates)
        assertEquals(caps.supportedTransports, restoredFromJson.supportedTransports)
    }

    @Test
    fun testNodeRoleIsDynamicNotPermanentlyLocked() {
        val identity = NodeIdentity.generate("Flexible Node")
        val caps = NodeCapabilities(hasAudioInput = true, hasAudioOutput = true)

        // Node starts in idle / available state with no fixed transmitter/receiver role
        val node = NodeInfo(
            identity = identity,
            capabilities = caps,
            state = NodeState.AVAILABLE,
            activeRole = StreamRole.IDLE
        )
        assertTrue(node.canSend())
        assertTrue(node.canReceive())
        assertEquals(StreamRole.IDLE, node.activeRole)

        // When acting as sender in a stream
        val senderNode = node.copy(state = NodeState.ACTIVE_STREAMING, activeRole = StreamRole.SENDER, activeStreamGeneration = 1L)
        assertEquals(StreamRole.SENDER, senderNode.activeRole)
        assertTrue(senderNode.isStreaming())

        // Later, the same node acts as receiver in another stream session without changing identity
        val receiverNode = senderNode.copy(state = NodeState.ACTIVE_STREAMING, activeRole = StreamRole.RECEIVER, activeStreamGeneration = 2L)
        assertEquals(StreamRole.RECEIVER, receiverNode.activeRole)
        assertEquals(senderNode.id, receiverNode.id)
    }

    @Test
    fun testNodeAdaptersDiscoveredDevice() {
        val discovered = DiscoveredDevice(
            name = "Test Receiver",
            ip = "192.168.1.105",
            port = 19850,
            capabilitiesMask = AudioCapabilities.CAP_FLAG_48000 or AudioCapabilities.CAP_FLAG_44100,
            role = "receiver",
            nodeId = "hat-node-test-uuid-1234"
        )

        val nodeInfo = discovered.toNodeInfo()
        assertEquals("hat-node-test-uuid-1234", nodeInfo.id)
        assertEquals("Test Receiver", nodeInfo.name)
        assertEquals(StreamRole.RECEIVER, nodeInfo.activeRole)
        assertTrue(nodeInfo.capabilities.supportedSampleRates.contains(48000))
        assertTrue(nodeInfo.capabilities.supportedSampleRates.contains(44100))

        // Convert back to DiscoveredDevice
        val backToDiscovered = NodeAdapters.toDiscoveredDevice(nodeInfo, "192.168.1.105", 19850)
        assertEquals("hat-node-test-uuid-1234", backToDiscovered.nodeId)
        assertEquals("Test Receiver", backToDiscovered.name)
        assertEquals("192.168.1.105", backToDiscovered.ip)
        assertEquals("receiver", backToDiscovered.role)
    }

    @Test
    fun testNodeAdaptersConnectedDeviceAndProfile() {
        val connected = ConnectedDevice(
            ip = "192.168.49.1",
            port = 19850,
            name = "P2P Receiver",
            isDirectP2p = true,
            nodeId = "hat-node-p2p-client-99"
        )

        val nodeInfo = connected.toNodeInfo()
        assertEquals("hat-node-p2p-client-99", nodeInfo.id)
        assertEquals("P2P Receiver", nodeInfo.name)
        assertTrue(nodeInfo.capabilities.supportedTransports.contains(NodeTransportType.WIFI_DIRECT))

        val profile = ConnectionProfile(
            name = "Saved Hi-Fi Node",
            targetIp = "192.168.1.200",
            nodeId = "hat-node-saved-hifi-42"
        )
        val profileNode = profile.toNodeInfo()
        assertEquals("hat-node-saved-hifi-42", profileNode.id)
        assertEquals("Saved Hi-Fi Node", profileNode.name)
    }

    @Test
    fun testLocalNodeManagerLifecycleTransitions() {
        val initial = LocalNodeManager.getLocalNode()
        assertNotNull(initial)
        assertNotNull(initial.id)
        assertTrue(initial.id.isNotBlank())

        // Simulate capture start
        LocalNodeManager.updateState(NodeState.ACTIVE_STREAMING, StreamRole.SENDER, 5L)
        val streamingSender = LocalNodeManager.getLocalNode()
        assertEquals(NodeState.ACTIVE_STREAMING, streamingSender.state)
        assertEquals(StreamRole.SENDER, streamingSender.activeRole)
        assertEquals(5L, streamingSender.activeStreamGeneration)

        // Simulate capture stop
        LocalNodeManager.updateState(NodeState.AVAILABLE, StreamRole.IDLE)
        val idleNode = LocalNodeManager.getLocalNode()
        assertEquals(NodeState.AVAILABLE, idleNode.state)
        assertEquals(StreamRole.IDLE, idleNode.activeRole)

        // Simulate sink start
        LocalNodeManager.updateState(NodeState.ACTIVE_STREAMING, StreamRole.RECEIVER, 6L)
        val streamingReceiver = LocalNodeManager.getLocalNode()
        assertEquals(NodeState.ACTIVE_STREAMING, streamingReceiver.state)
        assertEquals(StreamRole.RECEIVER, streamingReceiver.activeRole)
        assertEquals(6L, streamingReceiver.activeStreamGeneration)

        // Reset to available
        LocalNodeManager.updateState(NodeState.AVAILABLE, StreamRole.IDLE)
    }
}
