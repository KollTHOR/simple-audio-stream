package com.example.audiostreamer

import com.example.audiostreamer.node.HatLink
import com.example.audiostreamer.node.HatLinkManager
import com.example.audiostreamer.node.LinkMetadata
import com.example.audiostreamer.node.LinkState
import com.example.audiostreamer.node.NodeCapabilities
import com.example.audiostreamer.node.NodeIdentity
import com.example.audiostreamer.node.NodeInfo
import com.example.audiostreamer.node.NodeState
import com.example.audiostreamer.node.NodeTransportType
import com.example.audiostreamer.node.StreamRole
import com.example.audiostreamer.node.transport.BleTransport
import com.example.audiostreamer.node.transport.HatIpTransport
import com.example.audiostreamer.node.transport.HatTransportRegistry
import com.example.audiostreamer.node.transport.HatTransportType
import com.example.audiostreamer.node.transport.LanTransport
import com.example.audiostreamer.node.transport.NfcBootstrapTransport
import com.example.audiostreamer.node.transport.TransportAddress
import com.example.audiostreamer.node.transport.TransportState
import com.example.audiostreamer.node.transport.WifiAwareTransport
import com.example.audiostreamer.node.transport.WifiDirectTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class HatTransportTest {

    @Test
    fun testTransportTypesClassification() {
        // Requirement: LAN, WIFI_DIRECT, WIFI_AWARE are high-bandwidth IP audio transports
        assertTrue(HatTransportType.LAN.isAudioTransport)
        assertTrue(HatTransportType.LAN.isIpBased)
        assertFalse(HatTransportType.LAN.isBootstrapOnly)

        assertTrue(HatTransportType.WIFI_DIRECT.isAudioTransport)
        assertTrue(HatTransportType.WIFI_DIRECT.isIpBased)
        assertFalse(HatTransportType.WIFI_DIRECT.isBootstrapOnly)

        assertTrue(HatTransportType.WIFI_AWARE.isAudioTransport)
        assertTrue(HatTransportType.WIFI_AWARE.isIpBased)
        assertFalse(HatTransportType.WIFI_AWARE.isBootstrapOnly)

        // Requirement: BLE and NFC are NOT audio transports. They are discovery/bootstrap mechanisms.
        assertFalse("BLE is NOT an audio transport", HatTransportType.BLE.isAudioTransport)
        assertFalse("BLE is NOT IP-based", HatTransportType.BLE.isIpBased)
        assertTrue("BLE is a bootstrap/discovery mechanism", HatTransportType.BLE.isBootstrapOnly)

        assertFalse("NFC is NOT an audio transport", HatTransportType.NFC_BOOTSTRAP.isAudioTransport)
        assertFalse("NFC is NOT IP-based", HatTransportType.NFC_BOOTSTRAP.isIpBased)
        assertTrue("NFC is a bootstrap/discovery mechanism", HatTransportType.NFC_BOOTSTRAP.isBootstrapOnly)

        // Verify mapping to NodeTransportType
        assertEquals(NodeTransportType.LOCAL_WIFI, HatTransportType.LAN.toNodeTransportType())
        assertEquals(NodeTransportType.WIFI_DIRECT, HatTransportType.WIFI_DIRECT.toNodeTransportType())
        assertEquals(NodeTransportType.WIFI_AWARE, HatTransportType.WIFI_AWARE.toNodeTransportType())
        assertEquals(NodeTransportType.BLUETOOTH_LE, HatTransportType.BLE.toNodeTransportType())
        assertEquals(NodeTransportType.NFC, HatTransportType.NFC_BOOTSTRAP.toNodeTransportType())
    }

    @Test
    fun testTransportAddressParsingAndDirectP2p() {
        val lanAddr = TransportAddress.parse("192.168.1.150:19850")
        assertEquals("192.168.1.150", lanAddr.host)
        assertEquals(19850, lanAddr.port)
        assertFalse(lanAddr.isDirectP2p)
        assertEquals("192.168.1.150:19850", lanAddr.toString())

        val p2pAddr = TransportAddress.parse("192.168.49.1:19850")
        assertEquals("192.168.49.1", p2pAddr.host)
        assertEquals(19850, p2pAddr.port)
        assertTrue("Subnet 192.168.49.x should be recognized as Wi-Fi Direct P2P", p2pAddr.isDirectP2p)

        val hostOnly = TransportAddress.parse("10.0.0.5")
        assertEquals("10.0.0.5", hostOnly.host)
        assertEquals(0, hostOnly.port)
    }

    @Test
    fun testLanTransportConnectListenSendReceive() {
        val serverTransport = LanTransport()
        val clientTransport = LanTransport()

        try {
            // Server listens on dynamic port
            val serverListenResult = serverTransport.listen(0)
            assertTrue(serverListenResult.isSuccess)
            assertEquals(TransportState.LISTENING, serverTransport.state)
            val serverPort = serverTransport.socket!!.localPort
            assertTrue(serverPort > 0)

            // Client connects to server
            val connectResult = clientTransport.connect(TransportAddress("127.0.0.1", serverPort))
            assertTrue(connectResult.isSuccess)
            assertEquals(TransportState.CONNECTED, clientTransport.state)

            // Transmit packet from client to server
            val testMessage = "HAT-AUDIO-FRAME-TEST".toByteArray(Charsets.UTF_8)
            val sendPacket = DatagramPacket(testMessage, testMessage.size, InetAddress.getByName("127.0.0.1"), serverPort)
            clientTransport.send(sendPacket)

            // Receive packet on server
            val recvBuffer = ByteArray(256)
            val recvPacket = DatagramPacket(recvBuffer, recvBuffer.size)
            serverTransport.receive(recvPacket)

            val receivedMessage = String(recvPacket.data, recvPacket.offset, recvPacket.length, Charsets.UTF_8)
            assertEquals("HAT-AUDIO-FRAME-TEST", receivedMessage)

            // Verify diagnostics
            val clientDiag = clientTransport.getDiagnostics()
            assertEquals("LAN", clientDiag["type"])
            assertEquals(true, clientDiag["isAudioTransport"])
            assertEquals(1L, clientDiag["packetsSent"])

            val serverDiag = serverTransport.getDiagnostics()
            assertEquals("LAN", serverDiag["type"])
            assertEquals(1L, serverDiag["packetsReceived"])
        } finally {
            serverTransport.close()
            clientTransport.close()
            assertEquals(TransportState.CLOSED, serverTransport.state)
            assertEquals(TransportState.CLOSED, clientTransport.state)
        }
    }

    @Test
    fun testWifiDirectTransportLifecycleAndDiagnostics() {
        val wifiDirectTransport = WifiDirectTransport()
        assertEquals(HatTransportType.WIFI_DIRECT, wifiDirectTransport.type)
        assertTrue(wifiDirectTransport.type.isAudioTransport)
        assertTrue(wifiDirectTransport.type.isIpBased)

        val diag = wifiDirectTransport.getDiagnostics()
        assertEquals("WIFI_DIRECT", diag["type"])
        assertTrue(diag.containsKey("isGroupCreated"))
        assertTrue(diag.containsKey("groupOwnerIp"))

        // Connect to P2P endpoint
        val connectResult = wifiDirectTransport.connect(TransportAddress("192.168.49.1", 19850))
        assertTrue(connectResult.isSuccess)
        assertEquals(TransportState.CONNECTED, wifiDirectTransport.state)
        assertEquals("192.168.49.1:19850", wifiDirectTransport.remoteAddress.toString())

        wifiDirectTransport.close()
        assertEquals(TransportState.CLOSED, wifiDirectTransport.state)
    }

    @Test
    fun testWifiAwareTransportRejection() {
        val wifiAwareTransport = WifiAwareTransport()
        assertEquals(HatTransportType.WIFI_AWARE, wifiAwareTransport.type)
        assertFalse("Wi-Fi Aware not implemented yet", wifiAwareTransport.isAvailable)
        assertEquals(TransportState.UNAVAILABLE, wifiAwareTransport.state)

        val connectResult = wifiAwareTransport.connect(TransportAddress("192.168.1.1", 19850))
        assertTrue(connectResult.isFailure)
        assertTrue(connectResult.exceptionOrNull() is UnsupportedOperationException)

        val listenResult = wifiAwareTransport.listen(19850)
        assertTrue(listenResult.isFailure)
        assertTrue(listenResult.exceptionOrNull() is UnsupportedOperationException)

        val diag = wifiAwareTransport.getDiagnostics()
        assertEquals("NOT_IMPLEMENTED", diag["status"])
    }

    @Test
    fun testBleTransportIsBootstrapOnly() {
        val bleTransport = BleTransport()
        assertEquals(HatTransportType.BLE, bleTransport.type)
        assertFalse("BLE is NOT an audio transport", bleTransport.type.isAudioTransport)
        assertTrue("BLE is bootstrap/discovery only", bleTransport.type.isBootstrapOnly)

        // Calling connect for audio streaming MUST fail
        val connectResult = bleTransport.connect(TransportAddress("00:11:22:33:44:55", 0))
        assertTrue(connectResult.isFailure)
        assertTrue(connectResult.exceptionOrNull()!!.message!!.contains("discovery/bootstrap"))

        val diag = bleTransport.getDiagnostics()
        assertEquals("BLE", diag["type"])
        assertEquals("DISCOVERY_BOOTSTRAP", diag["role"])
    }

    @Test
    fun testNfcBootstrapTransportRejection() {
        val nfcTransport = NfcBootstrapTransport()
        assertEquals(HatTransportType.NFC_BOOTSTRAP, nfcTransport.type)
        assertFalse("NFC is NOT an audio transport", nfcTransport.type.isAudioTransport)
        assertTrue("NFC is bootstrap/discovery only", nfcTransport.type.isBootstrapOnly)
        assertFalse("NFC not implemented yet", nfcTransport.isAvailable)

        val connectResult = nfcTransport.connect(TransportAddress("nfc-tag", 0))
        assertTrue(connectResult.isFailure)
        assertTrue(connectResult.exceptionOrNull() is UnsupportedOperationException)

        val diag = nfcTransport.getDiagnostics()
        assertEquals("NOT_IMPLEMENTED", diag["status"])
    }

    @Test
    fun testLinkLayerTransportIndependence() {
        val localNode = NodeInfo(
            identity = NodeIdentity("hat-node-local", "Local Device"),
            capabilities = NodeCapabilities()
        )
        val remoteNode = NodeInfo(
            identity = NodeIdentity("hat-node-remote", "Remote Device"),
            capabilities = NodeCapabilities()
        )

        // 1. Link backed by LanTransport
        val lanTransport = LanTransport()
        lanTransport.listen(0)
        val serverPort = lanTransport.socket!!.localPort

        val lanLink = HatLink(
            localNode = localNode,
            remoteNode = remoteNode,
            transportType = NodeTransportType.LOCAL_WIFI,
            state = LinkState.CONNECTED,
            metadata = LinkMetadata("127.0.0.1", serverPort),
            transport = lanTransport
        )
        assertEquals(HatTransportType.LAN, lanLink.hatTransportType)
        assertNotNull(lanLink.ipTransport)

        // 2. Link backed by WifiDirectTransport
        val wifiDirectTransport = WifiDirectTransport()
        val p2pLink = HatLink(
            localNode = localNode,
            remoteNode = remoteNode,
            transportType = NodeTransportType.WIFI_DIRECT,
            state = LinkState.CONNECTED,
            metadata = LinkMetadata("192.168.49.1", 19850, isDirectP2p = true),
            transport = wifiDirectTransport
        )
        assertEquals(HatTransportType.WIFI_DIRECT, p2pLink.hatTransportType)
        assertNotNull(p2pLink.ipTransport)

        // The Link interface and data contract are identical regardless of LAN or Wi-Fi Direct
        val dummyPacket = DatagramPacket("TEST".toByteArray(), 4, InetAddress.getByName("127.0.0.1"), serverPort)
        lanLink.send(dummyPacket)
        assertEquals(1L, (lanTransport.getDiagnostics()["packetsSent"] as Long))

        lanTransport.close()
        wifiDirectTransport.close()
    }

    @Test
    fun testHatTransportRegistrySelectionAndDiagnostics() {
        // Direct P2P address selects Wi-Fi Direct transport
        val p2pTransport = HatTransportRegistry.selectBestAudioTransport("192.168.49.1")
        assertEquals(HatTransportType.WIFI_DIRECT, p2pTransport.type)

        // Regular LAN address selects LAN transport
        val lanTransport = HatTransportRegistry.selectBestAudioTransport("192.168.1.100")
        assertEquals(HatTransportType.LAN, lanTransport.type)

        // Fallback with no address selects LAN
        val defaultTransport = HatTransportRegistry.selectBestAudioTransport(null)
        assertEquals(HatTransportType.LAN, defaultTransport.type)

        // Verify registry aggregates diagnostics across all transports
        val diagSnapshot = HatTransportRegistry.getDiagnosticsSnapshot()
        assertTrue(diagSnapshot.containsKey("LAN"))
        assertTrue(diagSnapshot.containsKey("WIFI_DIRECT"))
        assertTrue(diagSnapshot.containsKey("WIFI_AWARE"))
        assertTrue(diagSnapshot.containsKey("BLE"))
        assertTrue(diagSnapshot.containsKey("NFC_BOOTSTRAP"))

        val bleDiag = diagSnapshot["BLE"] as Map<*, *>
        assertEquals("DISCOVERY_BOOTSTRAP", bleDiag["role"])
    }
}
