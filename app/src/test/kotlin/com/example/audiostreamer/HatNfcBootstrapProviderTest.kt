package com.example.audiostreamer

import com.example.audiostreamer.node.HatLinkManager
import com.example.audiostreamer.node.LocalNodeManager
import com.example.audiostreamer.node.NodeIdentity
import com.example.audiostreamer.node.NodeTransportType
import com.example.audiostreamer.node.discovery.HatNfcBootstrapPayload
import com.example.audiostreamer.node.discovery.HatNfcBootstrapProvider
import com.example.audiostreamer.node.discovery.NfcBootstrapState
import com.example.audiostreamer.node.transport.HatTransportType
import com.example.audiostreamer.node.transport.NfcBootstrapTransport
import com.example.audiostreamer.node.transport.TransportAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.UUID

class HatNfcBootstrapProviderTest {

    @Before
    fun setUp() {
        HatNfcBootstrapProvider.clearDiscoveredNodes()
        HatLinkManager.clear()
    }

    // ─── Payload Serialization & Deserialization ──────────────────────────────

    @Test
    fun testPayloadSerializationAndDeserialization() {
        val original = HatNfcBootstrapPayload(
            protocolVersion = HatPacket.PROTOCOL_VERSION.toInt(),
            nodeId = "hat-node-abc-123456",
            nodeName = "Studio Monitor",
            transportHints = setOf(NodeTransportType.WIFI_AWARE, NodeTransportType.WIFI_DIRECT, NodeTransportType.LOCAL_WIFI),
            sessionToken = "session-token-9988",
            portHint = 50005,
            extraHints = mapOf("zone" to "living_room")
        )

        val json = original.toJson()
        assertEquals(HatPacket.PROTOCOL_VERSION.toInt(), json.getInt("pv"))
        assertEquals("hat-node-abc-123456", json.getString("id"))
        assertEquals("Studio Monitor", json.getString("name"))
        assertEquals("session-token-9988", json.getString("token"))
        assertEquals(50005, json.getInt("port"))

        val bytes = original.toByteArray()
        val restored = HatNfcBootstrapPayload.fromByteArray(bytes)

        assertEquals(original.protocolVersion, restored.protocolVersion)
        assertEquals(original.nodeId, restored.nodeId)
        assertEquals(original.nodeName, restored.nodeName)
        assertEquals(original.transportHints, restored.transportHints)
        assertEquals(original.sessionToken, restored.sessionToken)
        assertEquals(original.portHint, restored.portHint)
        assertEquals(original.extraHints["zone"], restored.extraHints["zone"])
    }

    @Test
    fun testPayloadMimeTypeConstant() {
        assertEquals("application/vnd.hat.bootstrap", HatNfcBootstrapPayload.MIME_TYPE)
    }

    // ─── Identity & Validation Invariants ─────────────────────────────────────

    @Test
    fun testPayloadRejectsBlankNodeId() {
        try {
            HatNfcBootstrapPayload(
                nodeId = "   ",
                nodeName = "Valid Name",
                transportHints = setOf(NodeTransportType.LOCAL_WIFI)
            )
            fail("Expected IllegalArgumentException for blank nodeId")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("blank") == true)
        }
    }

    @Test
    fun testPayloadRejectsBlankNodeName() {
        try {
            HatNfcBootstrapPayload(
                nodeId = "hat-node-valid-01",
                nodeName = "  ",
                transportHints = setOf(NodeTransportType.LOCAL_WIFI)
            )
            fail("Expected IllegalArgumentException for blank nodeName")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("blank") == true)
        }
    }

    @Test
    fun testPayloadRejectsIpAddressAsIdentity() {
        val testIps = listOf("192.168.1.100", "10.0.0.1", "192.168.49.1", "::1")
        for (ip in testIps) {
            try {
                HatNfcBootstrapPayload(
                    nodeId = ip,
                    nodeName = "Fake IP Device",
                    transportHints = setOf(NodeTransportType.LOCAL_WIFI)
                )
                fail("Expected IllegalArgumentException for IP address as nodeId: $ip")
            } catch (e: IllegalArgumentException) {
                assertTrue("Must mention IP address rejection", e.message?.contains("IP address") == true)
            }
        }
    }

    @Test
    fun testPayloadRejectsMacAddressAsIdentity() {
        val testMacs = listOf("00:11:22:33:44:55", "AA:BB:CC:DD:EE:FF", "02-1a-2b-3c-4d-5e")
        for (mac in testMacs) {
            try {
                HatNfcBootstrapPayload(
                    nodeId = mac,
                    nodeName = "Fake MAC Device",
                    transportHints = setOf(NodeTransportType.LOCAL_WIFI)
                )
                fail("Expected IllegalArgumentException for MAC address as nodeId: $mac")
            } catch (e: IllegalArgumentException) {
                assertTrue("Must mention MAC address rejection", e.message?.contains("MAC address") == true)
            }
        }
    }

    @Test
    fun testPayloadRejectsNfcHardwareTagId() {
        val testTags = listOf("nfc:04A23B4C5D6E7F", "tag:998811", "04:A2:3B:4C")
        for (tag in testTags) {
            try {
                HatNfcBootstrapPayload(
                    nodeId = tag,
                    nodeName = "NFC Tag Device",
                    transportHints = setOf(NodeTransportType.LOCAL_WIFI)
                )
                fail("Expected IllegalArgumentException for hardware NFC tag ID as nodeId: $tag")
            } catch (e: IllegalArgumentException) {
                assertTrue("Must mention NFC hardware tag ID rejection", e.message?.contains("NFC hardware tag") == true)
            }
        }
    }

    @Test
    fun testPayloadRejectsSensitiveCredentialsOrPasswords() {
        val forbiddenEntries = listOf(
            mapOf("wifi_password" to "SuperSecret123"),
            mapOf("p2p_passphrase" to "12345678"),
            mapOf("wpa_key" to "abcdefgh"),
            mapOf("secret" to "credential")
        )

        for (extra in forbiddenEntries) {
            try {
                HatNfcBootstrapPayload(
                    nodeId = "hat-node-safe-01",
                    nodeName = "Safe Device",
                    transportHints = setOf(NodeTransportType.LOCAL_WIFI),
                    extraHints = extra
                )
                fail("Expected IllegalArgumentException for sensitive credential entry: $extra")
            } catch (e: IllegalArgumentException) {
                assertTrue("Must reject credentials", e.message?.contains("credentials or passwords") == true)
            }
        }
    }

    // ─── High-Bandwidth Transport Preference Order ────────────────────────────

    @Test
    fun testTransportPreferenceWiFiAwareFirst() {
        // Requirement: Prefer Wi-Fi Aware -> then Wi-Fi Direct -> then LAN
        val allSupported = setOf(
            NodeTransportType.WIFI_AWARE,
            NodeTransportType.WIFI_DIRECT,
            NodeTransportType.LOCAL_WIFI
        )

        val selected = HatNfcBootstrapProvider.selectBestHighBandwidthTransport(
            remoteHints = allSupported,
            localSupported = allSupported
        )
        assertEquals(NodeTransportType.WIFI_AWARE, selected)
    }

    @Test
    fun testTransportPreferenceWiFiDirectSecond() {
        // Wi-Fi Direct preferred when Wi-Fi Aware is not available on both sides
        val directAndLan = setOf(
            NodeTransportType.WIFI_DIRECT,
            NodeTransportType.LOCAL_WIFI
        )

        val selected = HatNfcBootstrapProvider.selectBestHighBandwidthTransport(
            remoteHints = directAndLan,
            localSupported = directAndLan
        )
        assertEquals(NodeTransportType.WIFI_DIRECT, selected)
    }

    @Test
    fun testTransportPreferenceLanThird() {
        // LAN preferred when neither Aware nor Direct is common
        val lanOnly = setOf(NodeTransportType.LOCAL_WIFI)

        val selected = HatNfcBootstrapProvider.selectBestHighBandwidthTransport(
            remoteHints = lanOnly,
            localSupported = lanOnly
        )
        assertEquals(NodeTransportType.LOCAL_WIFI, selected)
    }

    @Test
    fun testTransportPreferenceNoMatchReturnsNull() {
        val remoteHints = setOf(NodeTransportType.CELLULAR, NodeTransportType.BLUETOOTH_LE)
        val localSupported = setOf(NodeTransportType.LOCAL_WIFI)

        val selected = HatNfcBootstrapProvider.selectBestHighBandwidthTransport(
            remoteHints = remoteHints,
            localSupported = localSupported
        )
        assertNull(selected)
    }

    // ─── Handoff to LinkManager ───────────────────────────────────────────────

    @Test
    fun testSuccessfulBootstrapHandoffToLinkManager() {
        val payload = HatNfcBootstrapPayload(
            protocolVersion = HatPacket.PROTOCOL_VERSION.toInt(),
            nodeId = "hat-node-remote-nfc-88",
            nodeName = "Remote Receiver",
            transportHints = setOf(NodeTransportType.WIFI_DIRECT, NodeTransportType.LOCAL_WIFI),
            sessionToken = "token-xyz-123",
            portHint = 50005
        )

        val result = HatNfcBootstrapProvider.handleReceivedBootstrapPayload(payload)
        assertTrue("Bootstrap handoff must succeed", result.success)
        assertNotNull(result.remoteNode)
        assertEquals("hat-node-remote-nfc-88", result.remoteNode?.id)
        assertEquals("Remote Receiver", result.remoteNode?.name)
        assertEquals(NodeTransportType.WIFI_DIRECT, result.selectedTransport)
        assertEquals("token-xyz-123", result.sessionToken)

        // Verify HatLinkManager recorded the bootstrapped peer
        val bootstrappedPeer = HatLinkManager.getBootstrappedPeer("hat-node-remote-nfc-88")
        assertNotNull("Bootstrapped peer must be present in HatLinkManager", bootstrappedPeer)
        assertEquals("hat-node-remote-nfc-88", bootstrappedPeer?.remoteNode?.id)
        assertEquals(NodeTransportType.WIFI_DIRECT, bootstrappedPeer?.preferredTransport)
        assertEquals("token-xyz-123", bootstrappedPeer?.sessionToken)
        assertEquals(50005, bootstrappedPeer?.remotePort)

        // Verify discoveredNodes flow is updated
        val discoveredList = HatNfcBootstrapProvider.discoveredNodes.value
        assertEquals(1, discoveredList.size)
        assertEquals("hat-node-remote-nfc-88", discoveredList[0].nodeInfo.id)
        assertEquals(NodeTransportType.WIFI_DIRECT, discoveredList[0].preferredTransport)
        assertEquals(50005, discoveredList[0].portHint)
    }

    @Test
    fun testSelfNodeBootstrapIgnored() {
        val localNode = LocalNodeManager.getLocalNode()
        val selfPayload = HatNfcBootstrapPayload(
            protocolVersion = HatPacket.PROTOCOL_VERSION.toInt(),
            nodeId = localNode.id,
            nodeName = localNode.name,
            transportHints = setOf(NodeTransportType.LOCAL_WIFI)
        )

        val result = HatNfcBootstrapProvider.handleReceivedBootstrapPayload(selfPayload)
        assertFalse("Self-node bootstrap must be rejected", result.success)
        assertTrue("Details must explain self-node ignore", result.details.contains("self"))
        assertEquals(0, HatNfcBootstrapProvider.discoveredNodes.value.size)
    }

    @Test
    fun testIncompatibleProtocolVersionRejected() {
        val wrongVersionPayload = HatNfcBootstrapPayload(
            protocolVersion = 999, // incompatible future version
            nodeId = "hat-node-future-node",
            nodeName = "Future Node",
            transportHints = setOf(NodeTransportType.LOCAL_WIFI)
        )

        val result = HatNfcBootstrapProvider.handleReceivedBootstrapPayload(wrongVersionPayload)
        assertFalse("Incompatible protocol version must be rejected", result.success)
        assertTrue("Details must mention protocol version", result.details.contains("protocol version"))
        assertEquals(0, HatNfcBootstrapProvider.discoveredNodes.value.size)
    }

    // ─── NFC Must NOT Be Treated As Audio Transport ───────────────────────────

    @Test
    fun testNfcIsNotAnAudioTransport() {
        // Requirement: NFC must NOT be treated as an audio transport.
        val nfcTransport = NfcBootstrapTransport()
        assertEquals(HatTransportType.NFC_BOOTSTRAP, nfcTransport.type)
        assertFalse("NFC is NOT an audio transport", nfcTransport.type.isAudioTransport)
        assertTrue("NFC is bootstrap/discovery only", nfcTransport.type.isBootstrapOnly)
        assertFalse("NFC is not IP-based", nfcTransport.type.isIpBased)

        val connectResult = nfcTransport.connect(TransportAddress("nfc-target", 0))
        assertTrue("NFC transport connect must fail", connectResult.isFailure)

        // Tapping NFC does not automatically create an active audio link or stream
        assertEquals(0, HatLinkManager.activeLinks.value.size)
        assertEquals(0, HatLinkManager.activeStreams.value.size)

        val payload = HatNfcBootstrapPayload(
            protocolVersion = HatPacket.PROTOCOL_VERSION.toInt(),
            nodeId = "hat-node-peer-42",
            nodeName = "Tapped Device",
            transportHints = setOf(NodeTransportType.WIFI_DIRECT)
        )
        HatNfcBootstrapProvider.handleReceivedBootstrapPayload(payload)

        // Strict assertion: No audio link or audio stream was established automatically
        assertEquals(0, HatLinkManager.activeLinks.value.size)
        assertEquals(0, HatLinkManager.activeStreams.value.size)
    }

    // ─── Local Bootstrap Payload Creation ─────────────────────────────────────

    @Test
    fun testCreateLocalBootstrapPayload() {
        val payload = HatNfcBootstrapProvider.createLocalBootstrapPayload(
            sessionToken = "my-session-token",
            portHint = 50005
        )

        val localNode = LocalNodeManager.getLocalNode()
        assertEquals(localNode.id, payload.nodeId)
        assertEquals(localNode.name, payload.nodeName)
        assertEquals("my-session-token", payload.sessionToken)
        assertEquals(50005, payload.portHint)
        assertFalse(payload.transportHints.isEmpty())
        assertTrue("Hints must contain at least one high-bandwidth transport",
            payload.transportHints.any { it == NodeTransportType.WIFI_AWARE || it == NodeTransportType.WIFI_DIRECT || it == NodeTransportType.LOCAL_WIFI })
    }

    // ─── Diagnostics Snapshot & HatDiagnostics Integration ────────────────────

    @Test
    fun testDiagnosticsSnapshotStructure() {
        val snapshot = HatNfcBootstrapProvider.getDiagnosticsSnapshot()

        assertTrue(snapshot.containsKey("isSupported"))
        assertTrue(snapshot.containsKey("isEnabled"))
        assertTrue(snapshot.containsKey("state"))
        assertTrue(snapshot.containsKey("statusMessage"))
        assertTrue(snapshot.containsKey("preferredTransportOrder"))
        assertTrue(snapshot.containsKey("discoveredNodesCount"))
        assertTrue(snapshot.containsKey("metrics"))
        assertTrue(snapshot.containsKey("recentOperations"))
        assertTrue(snapshot.containsKey("discoveredNodes"))

        val metrics = snapshot["metrics"] as? Map<*, *>
        assertNotNull("metrics map must be present", metrics)
        assertTrue(metrics?.containsKey("tapsProcessed") == true)
        assertTrue(metrics?.containsKey("payloadsGenerated") == true)
        assertTrue(metrics?.containsKey("payloadsReceived") == true)
        assertTrue(metrics?.containsKey("successfulHandoffs") == true)
        assertTrue(metrics?.containsKey("validationFailures") == true)
        assertTrue(metrics?.containsKey("unsupportedTransportFailures") == true)
    }

    @Test
    fun testHatDiagnosticsIntegration() {
        val snapshot = HatDiagnostics.snapshot()
        assertTrue(
            "HatDiagnostics snapshot must contain [NFC_BOOTSTRAP] section",
            snapshot.contains("[NFC_BOOTSTRAP]")
        )
        assertTrue(
            "HatDiagnostics snapshot must contain preferredTransportOrder",
            snapshot.contains("preferredTransportOrder")
        )
    }

    // ─── Unsupported Hardware Handling ────────────────────────────────────────

    @Test
    fun testUnsupportedWhenNoNfcHardware() {
        // In JVM test environment, NFC hardware is not initialized
        assertFalse("isSupported should be false when not probed or hardware absent", HatNfcBootstrapProvider.isSupported)
        assertFalse("isEnabled should be false when not probed or hardware absent", HatNfcBootstrapProvider.isEnabled)

        // Clean stop and clear do not throw
        HatNfcBootstrapProvider.clearDiscoveredNodes()
        HatNfcBootstrapProvider.stopAll()
        assertEquals(NfcBootstrapState.STOPPED, HatNfcBootstrapProvider.state.value)
    }
}
