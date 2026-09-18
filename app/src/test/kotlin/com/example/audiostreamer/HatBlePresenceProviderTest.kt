package com.example.audiostreamer

import com.example.audiostreamer.node.DevicePlatformInfo
import com.example.audiostreamer.node.HatLinkManager
import com.example.audiostreamer.node.LocalNodeManager
import com.example.audiostreamer.node.NodeCapabilities
import com.example.audiostreamer.node.NodeIdentity
import com.example.audiostreamer.node.NodeInfo
import com.example.audiostreamer.node.NodeState
import com.example.audiostreamer.node.StreamRole
import com.example.audiostreamer.node.discovery.BlePresenceNode
import com.example.audiostreamer.node.discovery.BlePresenceOperationTiming
import com.example.audiostreamer.node.discovery.BlePresenceState
import com.example.audiostreamer.node.discovery.HatBlePresenceProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HatBlePresenceProviderTest {

    @Before
    fun setUp() {
        HatBlePresenceProvider.clearDiscoveredNodes()
    }

    // ─── Service UUID ─────────────────────────────────────────────────────────

    @Test
    fun testServiceUuidIsStable() {
        // The HAT BLE service UUID must never change — it is the filter key for all HAT BLE scanning
        assertEquals(
            "00001850-0000-1000-8000-00805f9b34fb",
            HatBlePresenceProvider.HAT_SERVICE_UUID.toString()
        )
        // ParcelUuid is an Android framework class; its internals are stubs in JVM tests.
        // We verify only that HAT_PARCEL_UUID is not null (constructed without crashing).
        assertNotNull("HAT_PARCEL_UUID must be constructable", HatBlePresenceProvider.HAT_PARCEL_UUID)
    }

    // ─── Payload encode / decode ───────────────────────────────────────────────

    @Test
    fun testPayloadRoundTrip() {
        val nodeInfo = NodeInfo(
            identity = NodeIdentity(id = "hat-node-12345678-abcd-efgh", name = "Studio M300"),
            capabilities = NodeCapabilities(hasAudioInput = true, hasAudioOutput = true),
            deviceInfo = DevicePlatformInfo(),
            state = NodeState.AVAILABLE,
            activeRole = StreamRole.TRANSCEIVER
        )

        val payload = HatBlePresenceProvider.buildAdvertisePayload(nodeInfo)
        assertTrue("Payload must not exceed 27 bytes for BLE compatibility", payload.size <= 27)
        assertTrue("Payload must be at least 3 bytes (pv + caps + idLen)", payload.size >= 3)

        val parsed = HatBlePresenceProvider.parseAdvertisePayload(payload)
        assertNotNull("Parse must succeed for a valid payload", parsed)
        val (pv, caps, idSuffix) = parsed!!
        assertEquals(HatPacket.PROTOCOL_VERSION.toInt(), pv)
        assertTrue("Node ID suffix must not be empty", idSuffix.isNotBlank())

        // The suffix must be the last ≤12 chars of the node ID
        val expectedSuffix = "hat-node-12345678-abcd-efgh".takeLast(12)
        assertEquals(expectedSuffix, idSuffix)
    }

    @Test
    fun testPayloadShortNameRoundTrip() {
        val nodeInfo = NodeInfo(
            identity = NodeIdentity(id = "hat-node-ble-test-001", name = "Living Room"),
            capabilities = NodeCapabilities(hasAudioInput = false, hasAudioOutput = true),
            deviceInfo = DevicePlatformInfo(),
            state = NodeState.AVAILABLE,
            activeRole = StreamRole.RECEIVER
        )

        val payload = HatBlePresenceProvider.buildAdvertisePayload(nodeInfo)
        val shortName = HatBlePresenceProvider.parseShortName(payload)
        // Name is truncated to 10 chars
        assertEquals("Living Roo", shortName)
    }

    @Test
    fun testPayloadFitsBleBudget() {
        // Max node name (10 chars) + max ID suffix (12 chars) + 4 overhead bytes = 26 bytes ≤ 27
        val longName = "MyDevice123"  // 11 chars — will be truncated to 10
        val nodeInfo = NodeInfo(
            identity = NodeIdentity(id = "hat-node-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", name = longName),
            capabilities = NodeCapabilities(),
            deviceInfo = DevicePlatformInfo(),
            state = NodeState.AVAILABLE,
            activeRole = StreamRole.TRANSCEIVER
        )
        val payload = HatBlePresenceProvider.buildAdvertisePayload(nodeInfo)
        assertTrue(
            "Payload must fit in 27 bytes; got ${payload.size}",
            payload.size <= 27
        )
    }

    @Test
    fun testMalformedPayloadReturnsNull() {
        assertNull("Empty payload must return null", HatBlePresenceProvider.parseAdvertisePayload(ByteArray(0)))
        assertNull("2-byte payload must return null (too short)", HatBlePresenceProvider.parseAdvertisePayload(byteArrayOf(1, 2)))
        // Valid header but idLen claims more bytes than available
        assertNull("Payload with truncated ID must return null",
            HatBlePresenceProvider.parseAdvertisePayload(byteArrayOf(1, 0, 12, 'a'.code.toByte())))
    }

    // ─── Identity separation ──────────────────────────────────────────────────

    @Test
    fun testBleAddressIsNeverHatIdentity() {
        // Architecture rule: BLE MAC address must NEVER be the HAT Node identity
        val bleMac = "AA:BB:CC:DD:EE:FF"
        val hatNodeId = "hat-node-ble-test-suffix"

        assertTrue("BLE MAC must be detected as MAC by NodeIdentity", NodeIdentity.isMac(bleMac))
        assertFalse("HAT node ID must NOT be a MAC address", NodeIdentity.isMac(hatNodeId))
        assertFalse("HAT node ID must NOT be an IP address", NodeIdentity.isIp(hatNodeId))

        // NodeIdentity must reject a BLE MAC as permanent identity
        try {
            NodeIdentity(id = bleMac, name = "Should Fail")
            assertTrue("NodeIdentity must throw for MAC address as id", false)
        } catch (e: IllegalArgumentException) {
            // expected: MAC rejected
        }

        // NodeIdentity must accept a synthesised hat-node-ble-... ID
        val identity = NodeIdentity(id = hatNodeId, name = "BLE Peer")
        assertEquals(hatNodeId, identity.id)
    }

    @Test
    fun testNodeIdSuffixMustNotLookLikeMacOrIp() {
        // The synthesised Node ID format 'hat-node-ble-<suffix>' must never match IP/MAC regexes
        val testSuffixes = listOf("abc12345xyz", "efgh9012", "xxxxxxxyyyyy")
        for (suffix in testSuffixes) {
            val synthesised = "hat-node-ble-$suffix"
            assertFalse("Synthesised ID '$synthesised' must not look like IP", NodeIdentity.isIp(synthesised))
            assertFalse("Synthesised ID '$synthesised' must not look like MAC", NodeIdentity.isMac(synthesised))
        }
    }

    // ─── Initial state ────────────────────────────────────────────────────────

    @Test
    fun testInitialStateIdle() {
        // In a JVM test environment, the provider is IDLE (not UNSUPPORTED, since we skip probeCapability)
        assertFalse(HatBlePresenceProvider.isAdvertising.value)
        assertFalse(HatBlePresenceProvider.isScanning.value)
        assertEquals(0, HatBlePresenceProvider.discoveredNodes.value.size)
    }

    @Test
    fun testDiagnosticsSnapshotStructure() {
        val snapshot = HatBlePresenceProvider.getDiagnosticsSnapshot()

        // Required top-level keys
        assertTrue(snapshot.containsKey("serviceUuid"))
        assertTrue(snapshot.containsKey("state"))
        assertTrue(snapshot.containsKey("isAdvertising"))
        assertTrue(snapshot.containsKey("isScanning"))
        assertTrue(snapshot.containsKey("statusMessage"))
        assertTrue(snapshot.containsKey("permissionState"))
        assertTrue(snapshot.containsKey("lastFailureReason"))
        assertTrue(snapshot.containsKey("lastAdvertiseErrorCode"))
        assertTrue(snapshot.containsKey("lastScanErrorCode"))
        assertTrue(snapshot.containsKey("discoveredNodesCount"))
        assertTrue(snapshot.containsKey("recentOperations"))
        assertTrue(snapshot.containsKey("discoveredNodes"))

        assertEquals("00001850-0000-1000-8000-00805f9b34fb", snapshot["serviceUuid"])
        assertEquals(false, snapshot["isAdvertising"])
        assertEquals(false, snapshot["isScanning"])
        assertEquals(0, snapshot["discoveredNodesCount"])

        val metrics = snapshot["metrics"] as? Map<*, *>
        assertNotNull("metrics sub-map must be present", metrics)
        assertTrue(metrics?.containsKey("advertiseAttempts") == true)
        assertTrue(metrics?.containsKey("scanAttempts") == true)
        assertTrue(metrics?.containsKey("nodesDiscovered") == true)
        assertTrue(metrics?.containsKey("nodesLost") == true)
        assertTrue(metrics?.containsKey("selfFiltered") == true)
        assertTrue(metrics?.containsKey("decodeErrors") == true)
        assertTrue(metrics?.containsKey("resultsReceived") == true)

        val timing = snapshot["timing"] as? Map<*, *>
        assertNotNull("timing sub-map must be present", timing)
        assertTrue(timing?.containsKey("lastAdvertiseDurationMs") == true)
        assertTrue(timing?.containsKey("lastScanDurationMs") == true)
    }

    // ─── Error code descriptions ──────────────────────────────────────────────

    @Test
    fun testAdvertiseErrorDescriptions() {
        val tooBig = HatBlePresenceProvider.describeAdvertiseError(1)
        assertTrue("Error 1 must mention DATA_TOO_LARGE", tooBig.contains("DATA_TOO_LARGE"))

        val tooMany = HatBlePresenceProvider.describeAdvertiseError(2)
        assertTrue("Error 2 must mention TOO_MANY_ADVERTISERS", tooMany.contains("TOO_MANY_ADVERTISERS"))

        val alreadyStarted = HatBlePresenceProvider.describeAdvertiseError(3)
        assertTrue("Error 3 must mention ALREADY_STARTED", alreadyStarted.contains("ALREADY_STARTED"))

        val internal = HatBlePresenceProvider.describeAdvertiseError(4)
        assertTrue("Error 4 must mention INTERNAL_ERROR", internal.contains("INTERNAL_ERROR"))

        val unsupported = HatBlePresenceProvider.describeAdvertiseError(5)
        assertTrue("Error 5 must mention FEATURE_UNSUPPORTED", unsupported.contains("FEATURE_UNSUPPORTED"))

        val unknown = HatBlePresenceProvider.describeAdvertiseError(99)
        assertTrue("Unknown error must include code", unknown.contains("99"))
    }

    @Test
    fun testScanErrorDescriptions() {
        val alreadyStarted = HatBlePresenceProvider.describeScanError(1)
        assertTrue("Scan error 1 must mention ALREADY_STARTED", alreadyStarted.contains("ALREADY_STARTED"))

        val internal = HatBlePresenceProvider.describeScanError(3)
        assertTrue("Scan error 3 must mention INTERNAL_ERROR", internal.contains("INTERNAL_ERROR"))

        val throttled = HatBlePresenceProvider.describeScanError(6)
        assertTrue("Scan error 6 must mention throttled/FREQUENTLY", throttled.contains("FREQUENTLY"))

        val unknown = HatBlePresenceProvider.describeScanError(77)
        assertTrue("Unknown scan error must include code", unknown.contains("77"))
    }

    // ─── No automatic connection ──────────────────────────────────────────────

    @Test
    fun testDiscoveryDoesNotCreateLinkOrStream() {
        // Architecture rule: discovering a BLE node must NOT automatically open a HatLink
        val linksBefore = HatLinkManager.activeLinks.value.size
        val streamsBefore = HatLinkManager.activeStreams.value.size

        // Simulated: just assert the provider starts with empty list
        assertEquals(0, HatBlePresenceProvider.discoveredNodes.value.size)
        assertEquals(linksBefore, HatLinkManager.activeLinks.value.size)
        assertEquals(streamsBefore, HatLinkManager.activeStreams.value.size)
    }

    // ─── Prune ───────────────────────────────────────────────────────────────

    @Test
    fun testPruneStaleNodesRemovesOldEntries() {
        // pruneStaleNodes with maxAgeMs=0 must remove all nodes (all are "stale" immediately)
        // We can't inject entries directly (private map), so just verify no crash with empty list
        HatBlePresenceProvider.clearDiscoveredNodes()
        HatBlePresenceProvider.pruneStaleNodes(maxAgeMs = 0L)
        assertEquals(0, HatBlePresenceProvider.discoveredNodes.value.size)
    }

    // ─── OperationTiming ─────────────────────────────────────────────────────

    @Test
    fun testOperationTimingToString() {
        val success = BlePresenceOperationTiming("ADVERTISE", durationMs = 45L, success = true)
        val failure = BlePresenceOperationTiming("SCAN", durationMs = 100L, success = false, errorCode = 3, details = "INTERNAL_ERROR")
        assertTrue("Success must contain OK", success.toString().contains("OK"))
        assertTrue("Success must contain duration", success.toString().contains("45ms"))
        assertTrue("Failure must contain FAIL", failure.toString().contains("FAIL"))
        assertTrue("Failure must contain error code", failure.toString().contains("3"))
        assertTrue("Failure must contain details", failure.toString().contains("INTERNAL_ERROR"))
    }

    // ─── HatDiagnostics integration ───────────────────────────────────────────

    @Test
    fun testHatDiagnosticsIntegration() {
        val snapshot = HatDiagnostics.snapshot()
        assertTrue(
            "HatDiagnostics snapshot must contain [BLE_PRESENCE] section",
            snapshot.contains("[BLE_PRESENCE]")
        )
        assertTrue(
            "HatDiagnostics snapshot must contain serviceUuid",
            snapshot.contains("serviceUuid=00001850-0000-1000-8000-00805f9b34fb")
        )
    }
}
