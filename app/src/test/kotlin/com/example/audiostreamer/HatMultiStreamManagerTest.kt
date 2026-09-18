package com.example.audiostreamer

import com.example.audiostreamer.node.DestinationHealth
import com.example.audiostreamer.node.DestinationStreamState
import com.example.audiostreamer.node.DevicePlatformInfo
import com.example.audiostreamer.node.HatLink
import com.example.audiostreamer.node.HatLinkManager
import com.example.audiostreamer.node.HatMultiStreamManager
import com.example.audiostreamer.node.HatSessionManager
import com.example.audiostreamer.node.HatStream
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.InetAddress

class HatMultiStreamManagerTest {

    private lateinit var phoneNode: NodeInfo
    private lateinit var m300Node: NodeInfo
    private lateinit var tabletNode: NodeInfo
    private lateinit var speakerNode: NodeInfo

    private lateinit var linkA: HatLink
    private lateinit var linkB: HatLink
    private lateinit var linkC: HatLink

    private lateinit var streamA: HatStream
    private lateinit var streamB: HatStream
    private lateinit var streamC: HatStream

    @Before
    fun setUp() {
        HatLinkManager.clear()
        HatSessionManager.clear()
        HatMultiStreamManager.clear()

        phoneNode = NodeInfo(
            identity = NodeIdentity("node-phone-001", "Pixel Phone"),
            deviceInfo = DevicePlatformInfo(manufacturer = "Google", model = "Pixel 8"),
            capabilities = NodeCapabilities(
                hasAudioInput = true,
                hasAudioOutput = true,
                supportedCodecs = setOf(AudioCodec.PCM, AudioCodec.OPUS),
                supportedSampleRates = setOf(44100, 48000),
                supportedTransports = setOf(NodeTransportType.LOCAL_WIFI, NodeTransportType.WIFI_DIRECT)
            ),
            state = NodeState.AVAILABLE,
            activeRole = StreamRole.SENDER
        )

        m300Node = NodeInfo(
            identity = NodeIdentity("node-m300-002", "Shanling M300"),
            deviceInfo = DevicePlatformInfo(manufacturer = "Shanling", model = "M300"),
            capabilities = NodeCapabilities(
                hasAudioOutput = true,
                supportedCodecs = setOf(AudioCodec.PCM, AudioCodec.OPUS),
                supportedSampleRates = setOf(48000),
                supportedTransports = setOf(NodeTransportType.WIFI_DIRECT, NodeTransportType.LOCAL_WIFI)
            ),
            state = NodeState.AVAILABLE,
            activeRole = StreamRole.RECEIVER
        )

        tabletNode = NodeInfo(
            identity = NodeIdentity("node-tablet-003", "Galaxy Tab"),
            deviceInfo = DevicePlatformInfo(manufacturer = "Samsung", model = "Tab S9"),
            capabilities = NodeCapabilities(
                hasAudioOutput = true,
                supportedCodecs = setOf(AudioCodec.PCM, AudioCodec.OPUS),
                supportedSampleRates = setOf(48000),
                supportedTransports = setOf(NodeTransportType.LOCAL_WIFI)
            ),
            state = NodeState.AVAILABLE,
            activeRole = StreamRole.RECEIVER
        )

        speakerNode = NodeInfo(
            identity = NodeIdentity("node-speaker-004", "HAT Speaker"),
            deviceInfo = DevicePlatformInfo(manufacturer = "AudioBrand", model = "SmartSpeaker"),
            capabilities = NodeCapabilities(
                hasAudioOutput = true,
                supportedCodecs = setOf(AudioCodec.PCM, AudioCodec.OPUS),
                supportedSampleRates = setOf(48000),
                supportedTransports = setOf(NodeTransportType.LOCAL_WIFI)
            ),
            state = NodeState.AVAILABLE,
            activeRole = StreamRole.RECEIVER
        )

        // Establish Link A: Phone <---> M300 via Wi-Fi Direct
        linkA = HatLinkManager.getOrCreateLink(
            remoteNode = m300Node,
            transportType = NodeTransportType.WIFI_DIRECT,
            remoteAddress = "192.168.49.2",
            remotePort = 50005,
            isDirectP2p = true,
            localNode = phoneNode
        )

        // Establish Link B: Phone <---> Tablet via LAN
        linkB = HatLinkManager.getOrCreateLink(
            remoteNode = tabletNode,
            transportType = NodeTransportType.LOCAL_WIFI,
            remoteAddress = "192.168.1.120",
            remotePort = 50005,
            isDirectP2p = false,
            localNode = phoneNode
        )

        // Establish Link C: Phone <---> Speaker via LAN
        linkC = HatLinkManager.getOrCreateLink(
            remoteNode = speakerNode,
            transportType = NodeTransportType.LOCAL_WIFI,
            remoteAddress = "192.168.1.130",
            remotePort = 50005,
            isDirectP2p = false,
            localNode = phoneNode
        )

        val format = AudioFormatConfig(sampleRate = AudioSampleRate.RATE_48000, bitDepth = AudioBitDepth.BIT_16)

        // Create Stream A: Phone -> M300
        val reqA = HatSessionManager.requestStream(
            linkId = linkA.id,
            streamType = StreamType.MUSIC,
            direction = StreamDirection.OUTBOUND,
            generation = 1L,
            sourceNode = phoneNode,
            destinationNode = m300Node,
            audioFormat = format,
            codec = AudioCodec.OPUS
        ).getOrThrow()
        HatSessionManager.acceptStream(reqA.id).getOrThrow()
        streamA = HatSessionManager.startStream(reqA.id).getOrThrow()

        // Create Stream B: Phone -> Tablet
        val reqB = HatSessionManager.requestStream(
            linkId = linkB.id,
            streamType = StreamType.MUSIC,
            direction = StreamDirection.OUTBOUND,
            generation = 1L,
            sourceNode = phoneNode,
            destinationNode = tabletNode,
            audioFormat = format,
            codec = AudioCodec.OPUS
        ).getOrThrow()
        HatSessionManager.acceptStream(reqB.id).getOrThrow()
        streamB = HatSessionManager.startStream(reqB.id).getOrThrow()

        // Create Stream C: Phone -> Speaker
        val reqC = HatSessionManager.requestStream(
            linkId = linkC.id,
            streamType = StreamType.MUSIC,
            direction = StreamDirection.OUTBOUND,
            generation = 1L,
            sourceNode = phoneNode,
            destinationNode = speakerNode,
            audioFormat = format,
            codec = AudioCodec.OPUS
        ).getOrThrow()
        HatSessionManager.acceptStream(reqC.id).getOrThrow()
        streamC = HatSessionManager.startStream(reqC.id).getOrThrow()
    }

    @After
    fun tearDown() {
        HatLinkManager.clear()
        HatSessionManager.clear()
        HatMultiStreamManager.clear()
    }

    // ─── 1. Multi-Destination Registration & Shared Encoder Configuration ───

    @Test
    fun testRegisterMultipleDestinations() {
        val format = AudioFormatConfig(sampleRate = AudioSampleRate.RATE_48000, bitDepth = AudioBitDepth.BIT_16)
        HatMultiStreamManager.configureSharedEncoder(AudioCodec.OPUS, format, generation = 1L)

        val stateA = HatMultiStreamManager.registerDestination(m300Node, linkA, streamA)
        val stateB = HatMultiStreamManager.registerDestination(tabletNode, linkB, streamB)
        val stateC = HatMultiStreamManager.registerDestination(speakerNode, linkC, streamC)

        assertEquals(3, HatMultiStreamManager.getAllDestinations().size)
        assertEquals(3, HatMultiStreamManager.countActiveDestinations())
        assertTrue(HatMultiStreamManager.hasActiveDestinations())

        assertEquals(m300Node.id, stateA.nodeId)
        assertEquals(tabletNode.id, stateB.nodeId)
        assertEquals(speakerNode.id, stateC.nodeId)

        assertEquals(com.example.audiostreamer.node.transport.HatTransportType.WIFI_DIRECT, stateA.transportType)
        assertEquals(com.example.audiostreamer.node.transport.HatTransportType.LAN, stateB.transportType)
        assertEquals(com.example.audiostreamer.node.transport.HatTransportType.LAN, stateC.transportType)
    }

    // ─── 2. Single Encoder & Format Compatibility ───────────────────────────

    @Test
    fun testSingleEncoderFormatCompatibility() {
        val format48k = AudioFormatConfig(sampleRate = AudioSampleRate.RATE_48000, bitDepth = AudioBitDepth.BIT_16)
        val format44k = AudioFormatConfig(sampleRate = AudioSampleRate.RATE_44100, bitDepth = AudioBitDepth.BIT_16)

        HatMultiStreamManager.configureSharedEncoder(AudioCodec.OPUS, format48k, generation = 1L)

        // Same codec and sample rate is compatible
        assertTrue(HatMultiStreamManager.isFormatCompatible(AudioCodec.OPUS, format48k))

        // Different sample rate is incompatible
        assertFalse(HatMultiStreamManager.isFormatCompatible(AudioCodec.OPUS, format44k))

        // Different codec is incompatible
        assertFalse(HatMultiStreamManager.isFormatCompatible(AudioCodec.PCM, format48k))
    }

    // ─── 3. Independent Packet Statistics & Fan-Out Recording ───────────────

    @Test
    fun testIndependentPacketStatistics() {
        val format = AudioFormatConfig(sampleRate = AudioSampleRate.RATE_48000, bitDepth = AudioBitDepth.BIT_16)
        HatMultiStreamManager.configureSharedEncoder(AudioCodec.OPUS, format, generation = 1L)

        HatMultiStreamManager.registerDestination(m300Node, linkA, streamA)
        HatMultiStreamManager.registerDestination(tabletNode, linkB, streamB)
        HatMultiStreamManager.registerDestination(speakerNode, linkC, streamC)

        // Simulate 100 packets sent to M300 (1000 bytes each)
        repeat(100) {
            HatMultiStreamManager.recordFanOut(m300Node.id, packetBytes = 1000, isSuccess = true)
        }

        // Simulate 50 packets sent to Tablet (1000 bytes each)
        repeat(50) {
            HatMultiStreamManager.recordFanOut(tabletNode.id, packetBytes = 1000, isSuccess = true)
        }

        // Simulate 10 packets sent to Speaker (1000 bytes each)
        repeat(10) {
            HatMultiStreamManager.recordFanOut(speakerNode.id, packetBytes = 1000, isSuccess = true)
        }

        val destA = HatMultiStreamManager.getDestination(m300Node.id)
        val destB = HatMultiStreamManager.getDestination(tabletNode.id)
        val destC = HatMultiStreamManager.getDestination(speakerNode.id)

        assertNotNull(destA)
        assertNotNull(destB)
        assertNotNull(destC)

        // Each destination maintains independent packet statistics
        assertEquals(100L, destA!!.stats.packetsSent.get())
        assertEquals(100000L, destA.stats.bytesSent.get())
        assertEquals(0L, destA.stats.sendErrors.get())
        assertEquals(DestinationHealth.HEALTHY, destA.health)

        assertEquals(50L, destB!!.stats.packetsSent.get())
        assertEquals(50000L, destB.stats.bytesSent.get())
        assertEquals(0L, destB.stats.sendErrors.get())
        assertEquals(DestinationHealth.HEALTHY, destB.health)

        assertEquals(10L, destC!!.stats.packetsSent.get())
        assertEquals(10000L, destC.stats.bytesSent.get())
        assertEquals(0L, destC.stats.sendErrors.get())
        assertEquals(DestinationHealth.HEALTHY, destC.health)
    }

    // ─── 4. Error Isolation: One Receiver Failing Does Not Stop Others ───────

    @Test
    fun testErrorIsolationBetweenDestinations() {
        val format = AudioFormatConfig(sampleRate = AudioSampleRate.RATE_48000, bitDepth = AudioBitDepth.BIT_16)
        HatMultiStreamManager.configureSharedEncoder(AudioCodec.OPUS, format, generation = 1L)

        HatMultiStreamManager.registerDestination(m300Node, linkA, streamA)
        HatMultiStreamManager.registerDestination(tabletNode, linkB, streamB)

        // Tablet experiences consecutive network errors
        val socketException = java.net.SocketException("Broken pipe")
        repeat(6) {
            HatMultiStreamManager.recordFanOut(tabletNode.id, packetBytes = 500, isSuccess = false, error = socketException)
        }

        // M300 continues receiving packets normally
        repeat(6) {
            HatMultiStreamManager.recordFanOut(m300Node.id, packetBytes = 500, isSuccess = true)
        }

        val destTablet = HatMultiStreamManager.getDestination(tabletNode.id)
        val destM300 = HatMultiStreamManager.getDestination(m300Node.id)

        assertNotNull(destTablet)
        assertNotNull(destM300)

        // Tablet is marked UNREACHABLE due to > 5 consecutive errors
        assertEquals(DestinationHealth.UNREACHABLE, destTablet!!.health)
        assertEquals(6L, destTablet.stats.sendErrors.get())
        assertEquals(6, destTablet.stats.consecutiveErrors.get())

        // M300 is completely unaffected, remaining HEALTHY with 0 errors
        assertEquals(DestinationHealth.HEALTHY, destM300!!.health)
        assertEquals(0L, destM300.stats.sendErrors.get())
        assertEquals(6L, destM300.stats.packetsSent.get())
    }

    // ─── 5. Receiver Latency & Buffering Independence ───────────────────────

    @Test
    fun testReceiverLatencyIndependence() {
        val format = AudioFormatConfig(sampleRate = AudioSampleRate.RATE_48000, bitDepth = AudioBitDepth.BIT_16)
        HatMultiStreamManager.configureSharedEncoder(AudioCodec.OPUS, format, generation = 1L)

        HatMultiStreamManager.registerDestination(m300Node, linkA, streamA)
        HatMultiStreamManager.registerDestination(tabletNode, linkB, streamB)

        // Tablet experiences high network jitter and increases its target watermark to 200ms
        HatMultiStreamManager.recordHeartbeat(
            nodeId = tabletNode.id,
            rttMs = 85f,
            bufferFillSlots = 40,
            targetWatermarkMs = 200f,
            underruns = 2L
        )
        HatMultiStreamManager.updatePlayoutState(
            nodeId = tabletNode.id,
            playoutState = "PLAYING",
            latencyTargetMs = 200f,
            pps = 200f,
            bps = 192000f
        )

        // M300 maintains ultra-low latency target (40ms) over Wi-Fi Direct
        HatMultiStreamManager.recordHeartbeat(
            nodeId = m300Node.id,
            rttMs = 4f,
            bufferFillSlots = 8,
            targetWatermarkMs = 40f,
            underruns = 0L
        )
        HatMultiStreamManager.updatePlayoutState(
            nodeId = m300Node.id,
            playoutState = "PLAYING",
            latencyTargetMs = 40f,
            pps = 200f,
            bps = 192000f
        )

        val destTablet = HatMultiStreamManager.getDestination(tabletNode.id)
        val destM300 = HatMultiStreamManager.getDestination(m300Node.id)

        // Verify independent target watermarks: Tablet slowing down does NOT force M300 to increase latency!
        assertEquals(200f, destTablet!!.bufferStats.targetWatermarkMs, 0.01f)
        assertEquals(200f, destTablet.playoutState.latencyTargetMs, 0.01f)
        assertEquals(85f, destTablet.stats.rttMs, 0.01f)

        assertEquals(40f, destM300!!.bufferStats.targetWatermarkMs, 0.01f)
        assertEquals(40f, destM300.playoutState.latencyTargetMs, 0.01f)
        assertEquals(4f, destM300.stats.rttMs, 0.01f)
    }

    // ─── 6. Receiver Disconnection Isolation ────────────────────────────────

    @Test
    fun testReceiverDisconnectDoesNotAffectOthers() {
        val format = AudioFormatConfig(sampleRate = AudioSampleRate.RATE_48000, bitDepth = AudioBitDepth.BIT_16)
        HatMultiStreamManager.configureSharedEncoder(AudioCodec.OPUS, format, generation = 1L)

        HatMultiStreamManager.registerDestination(m300Node, linkA, streamA)
        HatMultiStreamManager.registerDestination(tabletNode, linkB, streamB)
        HatMultiStreamManager.registerDestination(speakerNode, linkC, streamC)

        assertEquals(3, HatMultiStreamManager.getAllDestinations().size)

        // Tablet disconnects and leaves
        val removed = HatMultiStreamManager.removeDestination(tabletNode.id, reason = "user_disconnect")
        assertNotNull(removed)
        assertEquals(tabletNode.id, removed!!.nodeId)
        assertEquals(DestinationHealth.DISCONNECTED, removed.health)
        assertEquals(LinkState.DISCONNECTED, removed.connectionState)

        // Tablet stream should be stopped
        val tabletStream = HatSessionManager.getStream(streamB.id)
        assertEquals(StreamLifecycleState.STOP, tabletStream?.state)

        // Remaining destinations (M300 and Speaker) must NOT be stopped or affected!
        assertEquals(2, HatMultiStreamManager.getAllDestinations().size)
        assertEquals(2, HatMultiStreamManager.countActiveDestinations())

        val destM300 = HatMultiStreamManager.getDestination(m300Node.id)
        val destSpeaker = HatMultiStreamManager.getDestination(speakerNode.id)

        assertNotNull(destM300)
        assertNotNull(destSpeaker)
        assertTrue(destM300!!.isStreaming)
        assertTrue(destSpeaker!!.isStreaming)

        val streamM300 = HatSessionManager.getStream(streamA.id)
        val streamSpeaker = HatSessionManager.getStream(streamC.id)
        assertEquals(StreamLifecycleState.START, streamM300?.state)
        assertEquals(StreamLifecycleState.START, streamSpeaker?.state)
    }

    // ─── 7. Late Joiner: Joining Already-Running Stream ─────────────────────

    @Test
    fun testLateJoinerWithoutRestartingEncoder() {
        val format = AudioFormatConfig(sampleRate = AudioSampleRate.RATE_48000, bitDepth = AudioBitDepth.BIT_16)
        HatMultiStreamManager.configureSharedEncoder(AudioCodec.OPUS, format, generation = 5L)

        // Initially only M300 is streaming
        HatMultiStreamManager.registerDestination(m300Node, linkA, streamA)
        assertEquals(1, HatMultiStreamManager.countActiveDestinations())

        // Transmit some packets to M300
        repeat(20) {
            HatMultiStreamManager.recordFanOut(m300Node.id, packetBytes = 600, isSuccess = true)
        }

        // Now Tablet late-joins the already-running stream
        val lateState = HatMultiStreamManager.registerDestination(tabletNode, linkB, streamB)
        assertNotNull(lateState)
        assertEquals(2, HatMultiStreamManager.countActiveDestinations())
        assertTrue(lateState.isStreaming)

        // Send packets to both
        repeat(10) {
            HatMultiStreamManager.recordFanOut(m300Node.id, packetBytes = 600, isSuccess = true)
            HatMultiStreamManager.recordFanOut(tabletNode.id, packetBytes = 600, isSuccess = true)
        }

        val destM300 = HatMultiStreamManager.getDestination(m300Node.id)
        val destTablet = HatMultiStreamManager.getDestination(tabletNode.id)

        // M300 received all 30 packets, Tablet received 10 packets
        assertEquals(30L, destM300!!.stats.packetsSent.get())
        assertEquals(10L, destTablet!!.stats.packetsSent.get())

        // Generation unchanged (no restart occurred)
        assertEquals(5L, HatMultiStreamManager.activeGeneration)
    }

    // ─── 8. Diagnostics Snapshot ────────────────────────────────────────────

    @Test
    fun testDiagnosticsSnapshot() {
        val format = AudioFormatConfig(sampleRate = AudioSampleRate.RATE_48000, bitDepth = AudioBitDepth.BIT_16)
        HatMultiStreamManager.configureSharedEncoder(AudioCodec.OPUS, format, generation = 3L)

        HatMultiStreamManager.registerDestination(m300Node, linkA, streamA)
        HatMultiStreamManager.registerDestination(tabletNode, linkB, streamB)

        HatMultiStreamManager.recordFanOut(m300Node.id, 960, true)
        HatMultiStreamManager.recordHeartbeat(m300Node.id, rttMs = 5.2f, bufferFillSlots = 10, targetWatermarkMs = 45f)
        HatMultiStreamManager.updatePlayoutState(m300Node.id, "PLAYING", 45f, 200f, 192000f)

        val snapshot = HatMultiStreamManager.getDiagnosticsSnapshot()
        assertEquals(2, snapshot["totalDestinations"])
        assertEquals(2, snapshot["activeStreamingDestinations"])
        assertEquals("OPUS", snapshot["sharedEncoderCodec"])
        assertEquals(3L, snapshot["sharedGeneration"])

        @Suppress("UNCHECKED_CAST")
        val destList = snapshot["destinations"] as? List<Map<String, Any?>>
        assertNotNull(destList)
        assertEquals(2, destList!!.size)

        val m300Entry = destList.firstOrNull { it["nodeId"] == m300Node.id }
        assertNotNull(m300Entry)
        assertEquals("Shanling M300", m300Entry!!["nodeName"])
        assertEquals("WIFI_DIRECT", m300Entry["transport"])
        assertEquals("HEALTHY", m300Entry["health"])
        assertEquals(1L, m300Entry["packetsSent"])
        assertEquals(45f, m300Entry["targetWatermarkMs"])
        assertEquals(45f, m300Entry["latencyTargetMs"])
    }
}
