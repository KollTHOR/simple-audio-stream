package com.example.audiostreamer

import com.example.audiostreamer.node.DevicePlatformInfo
import com.example.audiostreamer.node.LocalNodeManager
import com.example.audiostreamer.node.NodeCapabilities
import com.example.audiostreamer.node.NodeIdentity
import com.example.audiostreamer.node.NodeInfo
import com.example.audiostreamer.node.NodeState
import com.example.audiostreamer.node.NodeTransportType
import com.example.audiostreamer.node.StreamRole
import com.example.audiostreamer.node.discovery.DiscoveredEndpoint
import com.example.audiostreamer.node.discovery.DiscoveredNodeEntry
import com.example.audiostreamer.node.discovery.DiscoveryScanCoordinator
import com.example.audiostreamer.node.discovery.DiscoveryScanPhase
import com.example.audiostreamer.node.discovery.DiscoverySource
import com.example.audiostreamer.node.discovery.HatDiscoveryRegistry
import com.example.audiostreamer.node.discovery.PhaseReport
import com.example.audiostreamer.node.discovery.PhaseStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiscoveryScanCoordinatorTest {

    @Before
    fun setUp() {
        HatDiscoveryRegistry.clear()
    }

    @After
    fun tearDown() {
        HatDiscoveryRegistry.stopAll()
    }

    @Test
    fun testPhaseTimeoutConstants() {
        // LAN, Wi-Fi Direct, and BLE each have a bounded scan window.
        assertEquals(30000L, DiscoveryScanCoordinator.TIMEOUT_LAN_MS)
        assertEquals(30000L, DiscoveryScanCoordinator.TIMEOUT_WIFI_DIRECT_MS)
        assertEquals(30000L, DiscoveryScanCoordinator.TIMEOUT_BLE_MS)
    }

    @Test
    fun testSequentialPhaseOrdering() {
        val phases = listOf(
            DiscoveryScanPhase.LOCAL_WIFI,
            DiscoveryScanPhase.WIFI_DIRECT,
            DiscoveryScanPhase.BLE
        )
        assertEquals(DiscoveryScanPhase.LOCAL_WIFI, phases[0])
        assertEquals(DiscoveryScanPhase.WIFI_DIRECT, phases[1])
        assertEquals(DiscoveryScanPhase.BLE, phases[2])
        assertEquals(3, phases.size)
    }

    @Test
    fun testDeviceCountConsistencyBetweenRegistryAndUdpBroadcast() {
        // Discrepancy problem test:
        // When DiscoveryManager discovers a UDP broadcast device that corresponds to a LAN NSD node,
        // HatDiscoveryRegistry must deduplicate it into a single node entry so dashboard and device list match.
        val nodeId = "hat-node-rx-101"
        val ip = "192.168.1.88"
        val port = 50005

        val identity = NodeIdentity(nodeId, "Living Room Speaker")
        val nsdEntry = DiscoveredNodeEntry(
            identity = identity,
            nodeInfo = NodeInfo(
                identity = identity,
                capabilities = NodeCapabilities(supportedTransports = setOf(NodeTransportType.LOCAL_WIFI)),
                deviceInfo = DevicePlatformInfo(model = "Speaker v1"),
                state = NodeState.AVAILABLE,
                activeRole = StreamRole.RECEIVER
            ),
            discoverySources = setOf(DiscoverySource.LAN),
            firstSeenEpochMs = 1000L,
            lastSeenEpochMs = 2000L,
            transportCandidates = setOf(NodeTransportType.LOCAL_WIFI),
            resolvedEndpoints = listOf(
                DiscoveredEndpoint(NodeTransportType.LOCAL_WIFI, address = ip, port = port, description = "LAN NSD")
            )
        )
        HatDiscoveryRegistry.registerNode(nsdEntry)

        // Simulate UDP broadcast event for the same device (using DiscoveredDevice with matching nodeId or IP)
        val udpDevice = DiscoveredDevice(
            ip = ip,
            port = port,
            name = "Living Room Speaker",
            modelName = "Speaker v1",
            nodeId = nodeId
        )
        DiscoveryManager.addDiscoveredDevice(udpDevice)

        // Recompute registry
        val resultNodes = HatDiscoveryRegistry.recomputeRegistry()

        // Authoritative single source of truth must have exactly 1 entry for this device
        assertEquals(1, resultNodes.size)
        val singleNode = resultNodes.first()
        assertEquals(nodeId, singleNode.id)
        assertTrue(singleNode.hasSource(DiscoverySource.LAN))

        // Cleanup
        DiscoveryManager.clearDiscoveredDevices()
    }

    @Test
    fun testPhaseReportModel() {
        val waitingReport = PhaseReport(DiscoveryScanPhase.LOCAL_WIFI, PhaseStatus.WAITING)
        assertEquals(PhaseStatus.WAITING, waitingReport.status)
        assertEquals(0, waitingReport.discoveredCount)

        val foundReport = PhaseReport(DiscoveryScanPhase.LOCAL_WIFI, PhaseStatus.FOUND, durationMs = 1200L, discoveredCount = 2)
        assertEquals(PhaseStatus.FOUND, foundReport.status)
        assertEquals(2, foundReport.discoveredCount)
        assertEquals(1200L, foundReport.durationMs)

        val skippedReport = PhaseReport(DiscoveryScanPhase.BLE, PhaseStatus.SKIPPED, reason = "Unsupported on device")
        assertEquals(PhaseStatus.SKIPPED, skippedReport.status)
        assertEquals("Unsupported on device", skippedReport.reason)

        val timedOutReport = PhaseReport(DiscoveryScanPhase.WIFI_DIRECT, PhaseStatus.TIMED_OUT, durationMs = 4000L, discoveredCount = 0)
        assertEquals(PhaseStatus.TIMED_OUT, timedOutReport.status)
        assertEquals(0, timedOutReport.discoveredCount)
    }

    @Test
    fun testInitialScanStateIsIdle() {
        val state = DiscoveryScanCoordinator.scanState.value
        assertEquals(DiscoveryScanPhase.IDLE, state.currentPhase)
        assertFalse(state.isScanning)
        assertEquals(0, state.secondsRemaining)
    }

    @Test
    fun testAllPhasesRepresentedInScanReports() {
        val reports = mapOf(
            DiscoveryScanPhase.LOCAL_WIFI to PhaseReport(DiscoveryScanPhase.LOCAL_WIFI, PhaseStatus.FOUND, 1200L, 2),
            DiscoveryScanPhase.WIFI_DIRECT to PhaseReport(DiscoveryScanPhase.WIFI_DIRECT, PhaseStatus.WAITING),
            DiscoveryScanPhase.BLE to PhaseReport(DiscoveryScanPhase.BLE, PhaseStatus.WAITING)
        )

        assertEquals(3, reports.size)
        assertEquals(PhaseStatus.FOUND, reports[DiscoveryScanPhase.LOCAL_WIFI]?.status)
        assertEquals(2, reports[DiscoveryScanPhase.LOCAL_WIFI]?.discoveredCount)
    }

    @Test
    fun testDiscoveredNodeDeduplicationAcrossLanDirectAndBle() {
        val nodeId = "hat-node-universal-999"
        val identity = NodeIdentity(nodeId, "Universal Receiver")

        val nodeEntry = DiscoveredNodeEntry(
            identity = identity,
            nodeInfo = NodeInfo(
                identity = identity,
                capabilities = NodeCapabilities(
                    supportedTransports = setOf(
                        NodeTransportType.LOCAL_WIFI,
                        NodeTransportType.WIFI_DIRECT,
                        NodeTransportType.BLUETOOTH_LE
                    )
                ),
                deviceInfo = DevicePlatformInfo(),
                state = NodeState.AVAILABLE,
                activeRole = StreamRole.RECEIVER
            ),
            discoverySources = setOf(
                DiscoverySource.LAN,
                DiscoverySource.WIFI_DIRECT,
                DiscoverySource.BLE
            ),
            firstSeenEpochMs = 1000L,
            lastSeenEpochMs = 5000L,
            transportCandidates = setOf(
                NodeTransportType.LOCAL_WIFI,
                NodeTransportType.WIFI_DIRECT,
                NodeTransportType.BLUETOOTH_LE
            ),
            resolvedEndpoints = listOf(
                DiscoveredEndpoint(NodeTransportType.LOCAL_WIFI, address = "192.168.1.50", port = 50005),
                DiscoveredEndpoint(NodeTransportType.WIFI_DIRECT, address = "12:34:56:78:9A:BC"),
                DiscoveredEndpoint(NodeTransportType.BLUETOOTH_LE, address = "DE:AD:BE:EF:00:01")
            )
        )
        HatDiscoveryRegistry.registerNode(nodeEntry)

        val registryNodes = HatDiscoveryRegistry.recomputeRegistry()
        assertEquals(1, registryNodes.size)
        val single = registryNodes.first()
        assertEquals(3, single.discoverySources.size)
        assertTrue(single.hasSource(DiscoverySource.LAN))
        assertTrue(single.hasSource(DiscoverySource.WIFI_DIRECT))
        assertTrue(single.hasSource(DiscoverySource.BLE))
        assertNotNull(single.getLanEndpoint())
        assertNotNull(single.getWifiDirectEndpoint())
        assertNotNull(single.getBleEndpoint())
    }
}
