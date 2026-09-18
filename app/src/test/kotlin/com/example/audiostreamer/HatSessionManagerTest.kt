package com.example.audiostreamer

import com.example.audiostreamer.node.DevicePlatformInfo
import com.example.audiostreamer.node.HatLink
import com.example.audiostreamer.node.HatLinkManager
import com.example.audiostreamer.node.HatSessionManager
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HatSessionManagerTest {

    private lateinit var phoneNode: NodeInfo
    private lateinit var m300Node: NodeInfo
    private lateinit var link: HatLink

    @Before
    fun setUp() {
        HatLinkManager.clear()
        HatSessionManager.clear()

        phoneNode = NodeInfo(
            identity = NodeIdentity("hat-node-phone-001", "Pixel Phone"),
            deviceInfo = DevicePlatformInfo(manufacturer = "Google", model = "Pixel 8"),
            capabilities = NodeCapabilities(
                hasAudioInput = true,
                hasAudioOutput = true,
                hasMicrophone = true,
                hasSpeaker = true,
                supportedCodecs = setOf(AudioCodec.PCM, AudioCodec.OPUS),
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
                supportedSampleRates = setOf(48000, 96000),
                supportedTransports = setOf(NodeTransportType.LOCAL_WIFI, NodeTransportType.WIFI_DIRECT)
            ),
            state = NodeState.AVAILABLE,
            activeRole = StreamRole.IDLE
        )

        // Establish persistent bidirectional link Phone <----> M300
        link = HatLinkManager.getOrCreateLink(
            remoteNode = m300Node,
            transportType = NodeTransportType.LOCAL_WIFI,
            remoteAddress = "192.168.1.150",
            remotePort = 50005,
            localNode = phoneNode
        )
    }

    @After
    fun tearDown() {
        HatLinkManager.clear()
        HatSessionManager.clear()
    }

    // ─── 1. Stream Lifecycle: REQUEST -> ACCEPT -> START -> PAUSE -> STOP ─────

    @Test
    fun testCompleteStreamLifecycle() {
        // Step 1: REQUEST
        val reqResult = HatSessionManager.requestStream(
            linkId = link.id,
            streamType = StreamType.MUSIC,
            direction = StreamDirection.OUTBOUND,
            generation = 1L,
            sourceNode = phoneNode,
            destinationNode = m300Node,
            audioFormat = AudioFormatConfig(sampleRate = AudioSampleRate.RATE_48000, bitDepth = AudioBitDepth.BIT_16),
            codec = AudioCodec.PCM
        )
        assertTrue(reqResult.isSuccess)
        val stream = reqResult.getOrThrow()
        assertEquals(StreamLifecycleState.REQUEST, stream.state)
        assertEquals(link.id, stream.linkId)
        assertEquals(phoneNode.id, stream.sourceNode.id)
        assertEquals(m300Node.id, stream.destinationNode.id)
        assertEquals(StreamDirection.OUTBOUND, stream.direction)
        assertEquals(StreamType.MUSIC, stream.streamType)
        assertEquals(AudioCodec.PCM, stream.codec)
        assertEquals(48000, stream.negotiatedFormat.sampleRateHz)
        assertFalse(stream.isActive)

        // Step 2: ACCEPT (with negotiated format adjustment to OPUS)
        val acceptResult = HatSessionManager.acceptStream(
            streamId = stream.id,
            negotiatedCodec = AudioCodec.OPUS
        )
        assertTrue(acceptResult.isSuccess)
        val acceptedStream = acceptResult.getOrThrow()
        assertEquals(StreamLifecycleState.ACCEPT, acceptedStream.state)
        assertEquals(AudioCodec.OPUS, acceptedStream.codec)
        assertFalse(acceptedStream.isActive)

        // Step 3: START (streaming active)
        val startResult = HatSessionManager.startStream(stream.id)
        assertTrue(startResult.isSuccess)
        val activeStream = startResult.getOrThrow()
        assertEquals(StreamLifecycleState.START, activeStream.state)
        assertTrue(activeStream.isActive)
        assertFalse(activeStream.isPaused)
        assertFalse(activeStream.isStopped)

        // Step 4: PAUSE (temporarily suspended)
        val pauseResult = HatSessionManager.pauseStream(stream.id)
        assertTrue(pauseResult.isSuccess)
        val pausedStream = pauseResult.getOrThrow()
        assertEquals(StreamLifecycleState.PAUSE, pausedStream.state)
        assertFalse(pausedStream.isActive)
        assertTrue(pausedStream.isPaused)

        // Step 5: RESUME (PAUSE -> START)
        val resumeResult = HatSessionManager.startStream(stream.id)
        assertTrue(resumeResult.isSuccess)
        assertEquals(StreamLifecycleState.START, resumeResult.getOrThrow().state)

        // Step 6: STOP (graceful termination)
        val stopResult = HatSessionManager.stopStream(stream.id)
        assertTrue(stopResult.isSuccess)
        val stoppedStream = stopResult.getOrThrow()
        assertEquals(StreamLifecycleState.STOP, stoppedStream.state)
        assertTrue(stoppedStream.isStopped)
        assertFalse(stoppedStream.isActive)

        // The parent link remains alive and connected!
        assertTrue(link.isAlive)
        assertEquals(LinkState.CONNECTED, link.state)
    }

    // ─── 2. Stream Lifecycle: REJECT ──────────────────────────────────────────

    @Test
    fun testStreamRejectionLifecycle() {
        val reqResult = HatSessionManager.requestStream(
            linkId = link.id,
            streamType = StreamType.MUSIC,
            direction = StreamDirection.OUTBOUND,
            sourceNode = phoneNode,
            destinationNode = m300Node,
            audioFormat = AudioFormatConfig(sampleRate = AudioSampleRate.RATE_96000),
            codec = AudioCodec.LOSSLESS
        )
        val stream = reqResult.getOrThrow()
        assertEquals(StreamLifecycleState.REQUEST, stream.state)

        // Destination rejects due to unsupported lossless codec
        val rejectResult = HatSessionManager.rejectStream(stream.id, "Unsupported codec: LOSSLESS")
        assertTrue(rejectResult.isSuccess)
        val rejectedStream = rejectResult.getOrThrow()
        assertEquals(StreamLifecycleState.REJECT, rejectedStream.state)
        assertEquals("Unsupported codec: LOSSLESS", rejectedStream.rejectionReason)
        assertFalse(rejectedStream.isActive)

        // Cannot start a rejected stream
        val startResult = HatSessionManager.startStream(stream.id)
        assertTrue(startResult.isFailure)

        // Underlying link remains unaffected and connected
        assertTrue(link.isAlive)
    }

    // ─── 3. Invalid State Transitions ─────────────────────────────────────────

    @Test
    fun testInvalidStateTransitionsRejected() {
        val reqResult = HatSessionManager.requestStream(
            linkId = link.id,
            streamType = StreamType.MUSIC,
            direction = StreamDirection.OUTBOUND,
            sourceNode = phoneNode,
            destinationNode = m300Node
        )
        val stream = reqResult.getOrThrow()

        // Cannot pause a stream that is in REQUEST state
        val pauseResult = HatSessionManager.pauseStream(stream.id)
        assertTrue(pauseResult.isFailure)

        // Transition to STOP
        HatSessionManager.stopStream(stream.id)

        // Cannot pause or restart a stopped stream
        val pauseStopped = HatSessionManager.pauseStream(stream.id)
        assertTrue(pauseStopped.isFailure)
        val startStopped = HatSessionManager.startStream(stream.id)
        assertTrue(startStopped.isFailure)
    }

    // ─── 4. Multiple Independent Streams over Same HatLink (Prompt Example) ───

    @Test
    fun testMultipleIndependentStreamsOverSameLink() {
        // Prompt Example:
        // Phone <---- HatLink ----> M300
        // Stream 1: Phone -> M300 (audio playback)
        // Stream 2: M300 -> Phone (microphone)
        // Both streams use the same connection.

        // Stream 1: Phone -> M300 (Music Playback)
        val stream1 = HatSessionManager.requestStream(
            linkId = link.id,
            streamType = StreamType.MUSIC,
            direction = StreamDirection.OUTBOUND,
            generation = 1L,
            sourceNode = phoneNode,
            destinationNode = m300Node,
            audioFormat = AudioFormatConfig(sampleRate = AudioSampleRate.RATE_48000, bitDepth = AudioBitDepth.BIT_16),
            codec = AudioCodec.PCM
        ).getOrThrow()
        HatSessionManager.acceptStream(stream1.id).getOrThrow()
        HatSessionManager.startStream(stream1.id).getOrThrow()

        // Stream 2: M300 -> Phone (Microphone Return)
        val stream2 = HatSessionManager.requestStream(
            linkId = link.id,
            streamType = StreamType.MICROPHONE,
            direction = StreamDirection.INBOUND,
            generation = 1L,
            sourceNode = m300Node,
            destinationNode = phoneNode,
            audioFormat = AudioFormatConfig(sampleRate = AudioSampleRate.RATE_48000, bitDepth = AudioBitDepth.BIT_16),
            codec = AudioCodec.OPUS
        ).getOrThrow()
        HatSessionManager.acceptStream(stream2.id).getOrThrow()
        HatSessionManager.startStream(stream2.id).getOrThrow()

        // Verify both streams share the exact same HatLink
        assertEquals(link.id, stream1.linkId)
        assertEquals(link.id, stream2.linkId)

        val streamsOnLink = HatSessionManager.getStreamsForLink(link.id)
        assertEquals(2, streamsOnLink.size)
        assertTrue(streamsOnLink.any { it.id == stream1.id && it.streamType == StreamType.MUSIC && it.direction == StreamDirection.OUTBOUND })
        assertTrue(streamsOnLink.any { it.id == stream2.id && it.streamType == StreamType.MICROPHONE && it.direction == StreamDirection.INBOUND })

        // Stream Independence: Pausing Stream 1 does NOT affect Stream 2 or the link
        HatSessionManager.pauseStream(stream1.id).getOrThrow()
        val stream1Refreshed = HatSessionManager.getStream(stream1.id)!!
        val stream2Refreshed = HatSessionManager.getStream(stream2.id)!!
        assertEquals(StreamLifecycleState.PAUSE, stream1Refreshed.state)
        assertEquals(StreamLifecycleState.START, stream2Refreshed.state)
        assertTrue(link.isAlive)

        // Stream Independence: Stopping Stream 1 leaves Stream 2 running and link alive
        HatSessionManager.stopStream(stream1.id).getOrThrow()
        assertEquals(StreamLifecycleState.STOP, HatSessionManager.getStream(stream1.id)!!.state)
        assertEquals(StreamLifecycleState.START, HatSessionManager.getStream(stream2.id)!!.state)
        assertTrue(link.isAlive)

        // Stopping Stream 2 also leaves the underlying persistent HatLink alive
        HatSessionManager.stopStream(stream2.id).getOrThrow()
        assertEquals(StreamLifecycleState.STOP, HatSessionManager.getStream(stream2.id)!!.state)
        assertTrue(link.isAlive)
        assertEquals(LinkState.CONNECTED, link.state)
    }

    // ─── 5. Closing HatLink Closes All Associated Streams ─────────────────────

    @Test
    fun testClosingLinkClosesAllAssociatedStreams() {
        val stream1 = HatSessionManager.createOrStartStream(
            linkId = link.id,
            streamType = StreamType.MUSIC,
            direction = StreamDirection.OUTBOUND,
            sourceNode = phoneNode,
            destinationNode = m300Node
        )
        val stream2 = HatSessionManager.createOrStartStream(
            linkId = link.id,
            streamType = StreamType.MICROPHONE,
            direction = StreamDirection.INBOUND,
            sourceNode = m300Node,
            destinationNode = phoneNode
        )

        assertEquals(2, HatSessionManager.getStreamsForLink(link.id).size)

        // Close the parent link
        HatLinkManager.closeLink(link.id)

        // All streams on that link are closed and cleaned up
        assertEquals(0, HatSessionManager.getStreamsForLink(link.id).size)
    }

    // ─── 6. Existing Audio Stream Integration via LinkAdapters ────────────────

    @Test
    fun testExistingAudioStreamOperatesAsStreamOverNewArchitecture() {
        // Outbound playback registration (as in AudioCaptureService)
        val (outLink, outStream) = LinkAdapters.registerOutboundStream(
            remoteAddress = "192.168.1.150",
            remotePort = 50005,
            generation = 5L,
            codec = AudioCodec.OPUS,
            format = AudioFormatConfig(sampleRate = AudioSampleRate.RATE_48000, bitDepth = AudioBitDepth.BIT_16)
        )

        assertNotNull(outLink)
        assertNotNull(outStream)
        assertEquals(5L, outStream.generation)
        assertEquals(AudioCodec.OPUS, outStream.codec)
        assertEquals(StreamDirection.OUTBOUND, outStream.direction)
        assertEquals(StreamLifecycleState.START, outStream.state)
        assertTrue(outStream.isActive)

        // Verify it is registered in HatSessionManager
        val sessionStream = HatSessionManager.getStream(outStream.id)
        assertNotNull(sessionStream)
        assertEquals(outStream.id, sessionStream!!.id)

        // Inbound playback registration (as in AudioSinkService)
        val (inLink, inStream) = LinkAdapters.registerInboundStream(
            remoteAddress = "192.168.1.180",
            remotePort = 50005,
            generation = 6L,
            codec = AudioCodec.PCM,
            format = AudioFormatConfig(sampleRate = AudioSampleRate.RATE_96000, bitDepth = AudioBitDepth.BIT_24)
        )

        assertNotNull(inLink)
        assertNotNull(inStream)
        assertEquals(6L, inStream.generation)
        assertEquals(AudioCodec.PCM, inStream.codec)
        assertEquals(StreamDirection.INBOUND, inStream.direction)
        assertEquals(StreamLifecycleState.START, inStream.state)
        assertTrue(inStream.isActive)
    }

    // ─── 7. Stream Activity Metrics Tracking ──────────────────────────────────

    @Test
    fun testStreamActivityMetricsTracking() {
        val stream = HatSessionManager.createOrStartStream(
            linkId = link.id,
            streamType = StreamType.MUSIC,
            direction = StreamDirection.OUTBOUND,
            sourceNode = phoneNode,
            destinationNode = m300Node
        )

        assertEquals(0L, stream.packetsTransferred)
        assertEquals(0L, stream.bytesTransferred)

        // Record link activity (simulating packets transmitted)
        HatLinkManager.recordLinkActivity(
            remoteAddress = link.metadata.remoteAddress,
            remotePort = link.metadata.remotePort,
            packetsIncrement = 100L,
            bytesIncrement = 48000L,
            isRx = false // Outbound Tx
        )

        val updatedStream = HatSessionManager.getStream(stream.id)!!
        assertEquals(100L, updatedStream.packetsTransferred)
        assertEquals(48000L, updatedStream.bytesTransferred)
    }

    // ─── 8. Diagnostics Snapshot ─────────────────────────────────────────────

    @Test
    fun testDiagnosticsSnapshot() {
        HatSessionManager.createOrStartStream(
            linkId = link.id,
            streamType = StreamType.MUSIC,
            direction = StreamDirection.OUTBOUND,
            sourceNode = phoneNode,
            destinationNode = m300Node
        )

        val diag = HatSessionManager.getDiagnosticsSnapshot()
        assertEquals(1, diag["totalStreamsCount"])
        assertEquals(1, diag["activeStreamingCount"])
        assertEquals(0, diag["pausedStreamsCount"])
        assertEquals(1, diag["totalSessionsCount"])

        @Suppress("UNCHECKED_CAST")
        val streamsList = diag["streams"] as List<Map<String, Any?>>
        assertEquals(1, streamsList.size)
        assertEquals("MUSIC", streamsList[0]["streamType"])
        assertEquals("OUTBOUND", streamsList[0]["direction"])
        assertEquals("START", streamsList[0]["state"])
    }
}
