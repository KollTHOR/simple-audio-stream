package com.example.audiostreamer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionManagerTest {

    @Test
    fun testStreamInvitePacketRoundtrip() {
        val payloadJson = JSONObject().apply {
            put("name", "Host Phone")
            put("port", 50005)
        }.toString()
        val payloadBytes = payloadJson.toByteArray(Charsets.UTF_8)

        val header = HatPacket.Header(
            version = 1,
            packetType = HatPacket.TYPE_STREAM_INVITE,
            sequenceNumber = 1,
            payloadLength = payloadBytes.size
        )

        val buffer = ByteArray(HatPacket.HEADER_SIZE + payloadBytes.size)
        HatPacket.writeHeader(buffer, 0, header)
        System.arraycopy(payloadBytes, 0, buffer, HatPacket.HEADER_SIZE, payloadBytes.size)

        val parsed = HatPacket.parseHeader(buffer, 0, buffer.size)
        assertNotNull(parsed)
        assertEquals(HatPacket.TYPE_STREAM_INVITE, parsed?.packetType)
        assertEquals(payloadBytes.size, parsed?.payloadLength)

        val readPayload = String(buffer, HatPacket.HEADER_SIZE, parsed!!.payloadLength, Charsets.UTF_8)
        val json = JSONObject(readPayload)
        assertEquals("Host Phone", json.getString("name"))
        assertEquals(50005, json.getInt("port"))
    }

    @Test
    fun testTransmitterAnnouncePacketRoundtrip() {
        val payloadJson = JSONObject().apply {
            put("name", "Google Pixel")
            put("port", 50005)
            put("role", "transmitter")
            put("streaming", true)
        }.toString()
        val payloadBytes = payloadJson.toByteArray(Charsets.UTF_8)

        val header = HatPacket.Header(
            version = 1,
            packetType = HatPacket.TYPE_TRANSMITTER_ANNOUNCE,
            sequenceNumber = 2,
            payloadLength = payloadBytes.size
        )

        val buffer = ByteArray(HatPacket.HEADER_SIZE + payloadBytes.size)
        HatPacket.writeHeader(buffer, 0, header)
        System.arraycopy(payloadBytes, 0, buffer, HatPacket.HEADER_SIZE, payloadBytes.size)

        val parsed = HatPacket.parseHeader(buffer, 0, buffer.size)
        assertNotNull(parsed)
        assertEquals(HatPacket.TYPE_TRANSMITTER_ANNOUNCE, parsed?.packetType)
        assertEquals(payloadBytes.size, parsed?.payloadLength)

        val readPayload = String(buffer, HatPacket.HEADER_SIZE, parsed!!.payloadLength, Charsets.UTF_8)
        val json = JSONObject(readPayload)
        assertEquals("Google Pixel", json.getString("name"))
        assertTrue(json.getBoolean("streaming"))
    }

    @Test
    fun testConnectedDeviceProperties() {
        val wifiDev = ConnectedDevice(
            ip = "192.168.1.100",
            port = 50005,
            name = "Kitchen Speaker",
            isDirectP2p = false,
            latencyMs = 28
        )
        assertEquals("Local Wi-Fi", wifiDev.transportType)
        assertEquals(28, wifiDev.latencyMs)

        val p2pDev = ConnectedDevice(
            ip = "192.168.49.2",
            port = 50005,
            name = "Direct Receiver",
            isDirectP2p = true,
            latencyMs = 15
        )
        assertEquals("Wi-Fi Direct", p2pDev.transportType)
        assertEquals(15, p2pDev.latencyMs)
    }

    @Test
    fun testStreamStateConnectedReceiversTelemetry() {
        StreamState.reset()
        val r1 = ConnectedDevice(ip = "192.168.1.10", name = "Room 1")
        val r2 = ConnectedDevice(ip = "192.168.1.20", name = "Room 2")

        StreamState.update { t ->
            t.copy(
                connectedReceivers = listOf(r1, r2),
                activeReceiversCount = 2
            )
        }

        val telemetry = StreamState.telemetry.value
        assertEquals(2, telemetry.connectedReceivers.size)
        assertEquals("Room 1", telemetry.connectedReceivers[0].name)
        assertEquals("Room 2", telemetry.connectedReceivers[1].name)
        assertEquals(2, telemetry.activeReceiversCount)

        // Dynamic disconnect of 1 client
        StreamState.update { t ->
            t.copy(
                connectedReceivers = t.connectedReceivers.filter { it.ip != "192.168.1.10" },
                activeReceiversCount = 1
            )
        }

        val updated = StreamState.telemetry.value
        assertEquals(1, updated.connectedReceivers.size)
        assertEquals("Room 2", updated.connectedReceivers[0].name)
    }

    @Test
    fun testReverseVolumeSyncTelemetryUpdate() {
        StreamState.reset()
        assertEquals(100, StreamState.telemetry.value.remoteVolumePercent)

        // Simulate incoming reverse volume sync from receiver
        val incomingVol = 65
        StreamState.update { it.copy(remoteVolumePercent = incomingVol) }

        assertEquals(65, StreamState.telemetry.value.remoteVolumePercent)
    }

    @Test
    fun testDeviceFilteringRules() {
        // Discovered devices: 1 verified HAT receiver on LAN, 1 self-loopback IP, 1 verified BLE receiver
        val selfIp = "192.168.1.50"
        val receiverIp = "192.168.1.100"
        val localIps = setOf(selfIp, "127.0.0.1")

        val localDevices = listOf(
            DiscoveredDevice(name = "Living Room", ip = receiverIp, port = 50005, capabilitiesMask = 0x01),
            DiscoveredDevice(name = "Self Transmitter", ip = selfIp, port = 50005, capabilitiesMask = 0x01)
        )

        // Filter out self IPs
        val filteredLocal = localDevices.filter { it.ip !in localIps }
        assertEquals(1, filteredLocal.size)
        assertEquals(receiverIp, filteredLocal[0].ip)

        // Connected devices filtering
        val connectedIps = setOf(receiverIp)
        val available = filteredLocal.filter { it.ip !in connectedIps }
        assertTrue(available.isEmpty())
    }
}

