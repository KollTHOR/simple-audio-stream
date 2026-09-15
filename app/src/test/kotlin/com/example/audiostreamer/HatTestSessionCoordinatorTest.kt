package com.example.audiostreamer

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class HatTestSessionCoordinatorTest {

    private val testAddress = InetAddress.getByName("127.0.0.1")
    private val testPort = 50005

    @Before
    fun setup() {
        HatTestSessionCoordinator.reset()
    }

    @After
    fun tearDown() {
        HatTestSessionCoordinator.reset()
    }

    @Test
    fun testSessionLifecycleAndJoin() = runBlocking {
        val sessionId = "HAT-SESSION-001"
        HatTestSessionCoordinator.initSessionForTest(sessionId)
        assertEquals(sessionId, HatTestSessionCoordinator.activeSessionId)

        // Simulate receiver joining
        val joined = HatTestControlMessage.SessionJoined(
            testSessionId = sessionId,
            device = "Pixel 8",
            appVersion = "1.8.9",
            generation = 1L
        )
        HatTestSessionCoordinator.onControlMessageReceived(joined, testAddress, testPort)

        val rxInfo = HatTestSessionCoordinator.awaitReceiverJoin(sessionId, 500L)
        assertNotNull(rxInfo)
        assertEquals("Pixel 8", rxInfo?.device)
        assertEquals("1.8.9", rxInfo?.appVersion)
        assertEquals(1L, rxInfo?.generation)
        assertEquals("127.0.0.1:50005", rxInfo?.endpoint)
    }

    @Test
    fun testGenerationAckDirectMessage() = runBlocking {
        val sessionId = "HAT-SESSION-002"
        HatTestSessionCoordinator.initSessionForTest(sessionId)

        // Receiver sends explicit GenerationAck
        val ack = HatTestControlMessage.GenerationAck(
            testSessionId = sessionId,
            generation = 4L,
            profile = "LOW_LATENCY",
            firstRxTimestamp = 1000L,
            firstDecodeTimestamp = 1010L,
            firstAudioWriteTimestamp = 1020L
        )
        HatTestSessionCoordinator.onControlMessageReceived(ack, testAddress, testPort)

        val receivedAck = HatTestSessionCoordinator.awaitGenerationAck(sessionId, 4L, 500L)
        assertNotNull(receivedAck)
        assertEquals(4L, receivedAck?.generation)
        assertEquals("LOW_LATENCY", receivedAck?.profile)
        assertEquals(1000L, receivedAck?.firstRxTimestamp)
        assertEquals(1020L, receivedAck?.firstAudioWriteTimestamp)
    }

    @Test
    fun testSyntheticGenerationAckFromRxStats() = runBlocking {
        val sessionId = "HAT-SESSION-003"
        HatTestSessionCoordinator.initSessionForTest(sessionId)

        // Receiver misses explicit GenerationAck or packet was dropped, but sends periodic RxStats for generation 5
        val stats = HatTestControlMessage.RxStats(
            testSessionId = sessionId,
            generation = 5L,
            timestamp = 5000L,
            packetsReceived = 200L,
            packetsLost = 0L,
            packetsLate = 0L,
            packetsOutOfOrder = 0L,
            packetsDuplicate = 0L,
            fecRecovered = 0L,
            decodeErrors = 0L,
            bufferPackets = 4,
            bufferFrames = 384,
            bufferMs = 20.0,
            targetLatencyMs = 35.0,
            jitterMs = 2.0,
            driftPpm = 0.0,
            audioTrackWrites = 100L,
            framesWritten = 96000L,
            underruns = 0L,
            writeErrors = 0L
        )
        HatTestSessionCoordinator.onControlMessageReceived(stats, testAddress, testPort)

        // Coordinator should synthesize generation ACK from the RxStats telemetry
        val receivedAck = HatTestSessionCoordinator.awaitGenerationAck(sessionId, 5L, 500L)
        assertNotNull(receivedAck)
        assertEquals(5L, receivedAck?.generation)
        assertEquals(5000L, receivedAck?.firstAudioWriteTimestamp)

        // Also check latestRxStats
        val latestStats = HatTestSessionCoordinator.latestRxStats(sessionId)
        assertNotNull(latestStats)
        assertEquals(200L, latestStats?.packetsReceived)
    }

    @Test
    fun testGenerationAckTimeoutForUnacknowledgedGen() = runBlocking {
        val sessionId = "HAT-SESSION-004"
        HatTestSessionCoordinator.initSessionForTest(sessionId)

        val ack = HatTestSessionCoordinator.awaitGenerationAck(sessionId, 99L, 100L)
        assertNull(ack)
    }
}
