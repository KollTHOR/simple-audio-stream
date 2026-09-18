package com.example.audiostreamer

import com.example.audiostreamer.node.DevicePlatformInfo
import com.example.audiostreamer.node.HatLink
import com.example.audiostreamer.node.HatLinkManager
import com.example.audiostreamer.node.HatStream
import com.example.audiostreamer.node.LinkAdapters
import com.example.audiostreamer.node.LinkMetadata
import com.example.audiostreamer.node.LinkState
import com.example.audiostreamer.node.LocalNodeManager
import com.example.audiostreamer.node.NodeCapabilities
import com.example.audiostreamer.node.NodeIdentity
import com.example.audiostreamer.node.NodeInfo
import com.example.audiostreamer.node.NodeState
import com.example.audiostreamer.node.NodeTransportType
import com.example.audiostreamer.node.StreamDirection
import com.example.audiostreamer.node.StreamLifecycleState
import com.example.audiostreamer.node.StreamRole
import com.example.audiostreamer.node.StreamType
import com.example.audiostreamer.node.toConnectedDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HatLinkStreamTest {

    private lateinit var phoneNode: NodeInfo
    private lateinit var m300Node: NodeInfo
    private lateinit var kitchenNode: NodeInfo

    @Before
    fun setUp() {
        HatLinkManager.clear()

        phoneNode = NodeInfo(
            identity = NodeIdentity("hat-node-phone-001", "Pixel Phone"),
            deviceInfo = DevicePlatformInfo(manufacturer = "Google", model = "Pixel 8"),
            capabilities = NodeCapabilities(
                hasAudioInput = true,
                hasAudioOutput = true,
                hasMicrophone = true,
                hasSpeaker = true,
                supportedCodecs = setOf(AudioCodec.PCM, AudioCodec.OPUS, AudioCodec.LOSSLESS),
                supportedSampleRates = setOf(44100, 48000, 96000),
                supportedTransports = setOf(NodeTransportType.LOCAL_WIFI, NodeTransportType.WIFI_DIRECT)
            ),
            state = NodeState.AVAILABLE,
            activeRole = StreamRole.IDLE
        )

        m300Node = NodeInfo(
            identity = NodeIdentity("hat-node-m300-002", "M300 Audio Player"),
            deviceInfo = DevicePlatformInfo(manufacturer = "Shanling", model = "M300"),
            capabilities = NodeCapabilities(
                hasAudioInput = true,
                hasAudioOutput = true,
                hasMicrophone = true,
                hasSpeaker = true,
                supportedCodecs = setOf(AudioCodec.PCM, AudioCodec.OPUS),
                supportedSampleRates = setOf(48000, 96000, 192000),
                supportedTransports = setOf(NodeTransportType.LOCAL_WIFI, NodeTransportType.WIFI_DIRECT)
            ),
            state = NodeState.AVAILABLE,
            activeRole = StreamRole.IDLE
        )

        kitchenNode = NodeInfo(
            identity = NodeIdentity("hat-node-kitchen-003", "Kitchen Speaker"),
            deviceInfo = DevicePlatformInfo(manufacturer = "Generic", model = "Speaker"),
            capabilities = NodeCapabilities(
                hasAudioInput = false,
                hasAudioOutput = true,
                hasMicrophone = false,
                hasSpeaker = true,
                supportedCodecs = setOf(AudioCodec.PCM),
                supportedSampleRates = setOf(44100, 48000),
                supportedTransports = setOf(NodeTransportType.LOCAL_WIFI)
            ),
            state = NodeState.AVAILABLE,
            activeRole = StreamRole.IDLE
        )
    }

    @After
    fun tearDown() {
        HatLinkManager.clear()
    }

    @Test
    fun testLinkIsInherentlyBidirectionalAndSymmetric() {
        // Architecture Rule 1 & 3: A Link is a communication relationship, and is inherently bidirectional.
        val link = HatLinkManager.getOrCreateLink(
            remoteNode = m300Node,
            transportType = NodeTransportType.LOCAL_WIFI,
            remoteAddress = "192.168.1.50",
            remotePort = 19850,
            localNode = phoneNode
        )

        assertNotNull(link)
        assertTrue(link.isAlive)
        assertTrue(link.canSend)
        assertTrue(link.canReceive)
        assertEquals(LinkState.CONNECTED, link.state)
        assertEquals(phoneNode.id, link.localNode.id)
        assertEquals(m300Node.id, link.remoteNode.id)
        assertEquals("192.168.1.50", link.metadata.remoteAddress)
        assertEquals(19850, link.metadata.remotePort)

        // JSON serialization roundtrip
        val json = link.toJson()
        val restored = HatLink.fromJson(json)
        assertEquals(link.id, restored.id)
        assertEquals(link.canSend, restored.canSend)
        assertEquals(link.canReceive, restored.canReceive)
        assertEquals(link.metadata.remoteAddress, restored.metadata.remoteAddress)
        assertEquals(link.metadata.remotePort, restored.metadata.remotePort)
    }

    @Test
    fun testMultiplexedStreamsOverSingleLink() {
        // Architecture Rule 4 & 5: Either Node can create a stream over an existing Link,
        // and multiple streams can multiplex over the same Link concurrently.
        // Example: Phone <---> M300
        // Stream A: Phone -> M300 (Music)
        // Stream B: M300 -> Phone (Microphone)

        val link = HatLinkManager.getOrCreateLink(
            remoteNode = m300Node,
            transportType = NodeTransportType.WIFI_DIRECT,
            remoteAddress = "192.168.49.2",
            remotePort = 19850,
            isDirectP2p = true,
            localNode = phoneNode
        )

        // Stream A: Phone sends high-res music to M300
        val streamA = HatLinkManager.createStream(
            linkId = link.id,
            streamType = StreamType.MUSIC,
            direction = StreamDirection.OUTBOUND,
            generation = 1L,
            sourceNode = phoneNode,
            destinationNode = m300Node,
            audioFormat = AudioFormatConfig(sampleRate = AudioSampleRate.RATE_96000, bitDepth = AudioBitDepth.BIT_24, channelLayout = AudioChannelLayout.STEREO),
            codec = AudioCodec.PCM
        )

        // Stream B: M300 captures voice / microphone and sends back to Phone over the same Link
        val streamB = HatLinkManager.createStream(
            linkId = link.id,
            streamType = StreamType.MICROPHONE,
            direction = StreamDirection.INBOUND,
            generation = 1L,
            sourceNode = m300Node,
            destinationNode = phoneNode,
            audioFormat = AudioFormatConfig(sampleRate = AudioSampleRate.RATE_48000, bitDepth = AudioBitDepth.BIT_16, channelLayout = AudioChannelLayout.STEREO),
            codec = AudioCodec.OPUS
        )

        val linkStreams = HatLinkManager.getStreamsForLink(link.id)
        assertEquals(2, linkStreams.size)
        assertTrue(linkStreams.any { it.id == streamA.id && it.streamType == StreamType.MUSIC && it.direction == StreamDirection.OUTBOUND })
        assertTrue(linkStreams.any { it.id == streamB.id && it.streamType == StreamType.MICROPHONE && it.direction == StreamDirection.INBOUND })

        // Stream JSON roundtrip
        val streamJson = streamA.toJson()
        assertEquals(streamA.id, streamJson.getString("id"))
        assertEquals("MUSIC", streamJson.getString("streamType"))
        assertEquals("OUTBOUND", streamJson.getString("direction"))
        assertEquals(96000, streamJson.getInt("sampleRate"))

        // Closing one stream preserves the other stream and the parent link
        HatLinkManager.closeStream(streamA.id)
        val remainingStreams = HatLinkManager.getStreamsForLink(link.id)
        assertEquals(1, remainingStreams.size)
        assertEquals(streamB.id, remainingStreams[0].id)
        assertEquals(1, HatLinkManager.activeLinks.value.size)
    }

    @Test
    fun testMultipleRemoteNodesSimultaneouslySupported() {
        // Architecture Rule 8: Multiple remote Nodes must be supported.
        val linkM300 = HatLinkManager.getOrCreateLink(
            remoteNode = m300Node,
            remoteAddress = "192.168.1.50",
            remotePort = 19850,
            localNode = phoneNode
        )

        val linkKitchen = HatLinkManager.getOrCreateLink(
            remoteNode = kitchenNode,
            remoteAddress = "192.168.1.60",
            remotePort = 19850,
            localNode = phoneNode
        )

        assertEquals(2, HatLinkManager.activeLinks.value.size)

        // Stream 1 to M300
        val stream1 = HatLinkManager.createStream(
            linkId = linkM300.id,
            streamType = StreamType.MUSIC,
            direction = StreamDirection.OUTBOUND,
            sourceNode = phoneNode,
            destinationNode = m300Node
        )

        // Stream 2 to Kitchen Speaker
        val stream2 = HatLinkManager.createStream(
            linkId = linkKitchen.id,
            streamType = StreamType.MUSIC,
            direction = StreamDirection.OUTBOUND,
            sourceNode = phoneNode,
            destinationNode = kitchenNode
        )

        assertEquals(2, HatLinkManager.activeStreams.value.size)

        // Terminating linkKitchen leaves linkM300 and its stream intact
        HatLinkManager.closeLink(linkKitchen.id)
        assertEquals(1, HatLinkManager.activeLinks.value.size)
        assertEquals(linkM300.id, HatLinkManager.activeLinks.value[0].id)
        assertEquals(1, HatLinkManager.activeStreams.value.size)
        assertEquals(stream1.id, HatLinkManager.activeStreams.value[0].id)
    }

    @Test
    fun testRecordLinkActivityAndPruning() {
        val link = HatLinkManager.getOrCreateLink(
            remoteNode = m300Node,
            remoteAddress = "192.168.1.50",
            remotePort = 19850,
            localNode = phoneNode
        )

        HatLinkManager.recordLinkActivity(
            remoteAddress = "192.168.1.50",
            remotePort = 19850,
            packetsIncrement = 100L,
            bytesIncrement = 40000L,
            isRx = false
        )

        HatLinkManager.recordLinkActivity(
            remoteAddress = "192.168.1.50",
            remotePort = 19850,
            packetsIncrement = 20L,
            bytesIncrement = 8000L,
            isRx = true
        )

        val updatedLink = HatLinkManager.activeLinks.value.first { it.id == link.id }
        assertEquals(100L, updatedLink.metadata.packetsSent)
        assertEquals(40000L, updatedLink.metadata.bytesSent)
        assertEquals(20L, updatedLink.metadata.packetsReceived)
        assertEquals(8000L, updatedLink.metadata.bytesReceived)

        // Pruning with negative timeout removes active link
        val pruned = HatLinkManager.pruneInactiveLinks(timeoutMs = -1L)
        assertEquals(1, pruned.size)
        assertTrue(HatLinkManager.activeLinks.value.isEmpty())
        assertTrue(HatLinkManager.activeStreams.value.isEmpty())
    }

    @Test
    fun testLinkAdaptersOutboundAndInbound() {
        // Test registerOutboundStream adapter
        val (outLink, outStream) = LinkAdapters.registerOutboundStream(
            remoteAddress = "192.168.1.75",
            remotePort = 19850,
            remoteCaps = AudioCapabilities.CAP_FLAG_48000,
            generation = 3L,
            codec = AudioCodec.OPUS
        )
        assertNotNull(outLink)
        assertEquals(StreamDirection.OUTBOUND, outStream.direction)
        assertEquals(AudioCodec.OPUS, outStream.codec)
        assertEquals(3L, outStream.generation)
        assertTrue(outLink.canSend)
        assertTrue(outLink.canReceive)

        // Convert to legacy ConnectedDevice
        val connectedDevice = outLink.toConnectedDevice(outStream)
        assertEquals("192.168.1.75", connectedDevice.ip)
        assertEquals(19850, connectedDevice.port)
        assertNotNull(connectedDevice.nodeId)
        assertTrue(connectedDevice.nodeId!!.startsWith(NodeIdentity.ID_PREFIX))

        // Test registerInboundStream adapter
        val (inLink, inStream) = LinkAdapters.registerInboundStream(
            remoteAddress = "192.168.1.80",
            remotePort = 19850,
            generation = 4L,
            codec = AudioCodec.PCM
        )
        assertNotNull(inLink)
        assertEquals(StreamDirection.INBOUND, inStream.direction)
        assertEquals(AudioCodec.PCM, inStream.codec)
        assertEquals(4L, inStream.generation)
        assertTrue(inLink.canSend)
        assertTrue(inLink.canReceive)
    }
}
