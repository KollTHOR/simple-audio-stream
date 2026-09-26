package com.example.audiostreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleDirectHandshakeTest {
    @Test
    fun codecRoundTripsStandardWifiDirectCredentials() {
        val expected = BleDirectHandshake(
            deviceName = "Living Room Receiver",
            ssid = "DIRECT-ab-AudioReceiver",
            passphrase = "wifi-direct-pass-123",
            groupOwnerIp = "192.168.49.1",
            port = 50005
        )

        val actual = BleDirectHandshakeCodec.decode(BleDirectHandshakeCodec.encode(expected))

        assertEquals(expected, actual)
    }

    @Test
    fun codecRejectsUnsupportedRolesAndIncompleteCredentials() {
        assertNull(
            BleDirectHandshakeCodec.decode(
                "{\"v\":1,\"r\":\"tx\",\"s\":\"DIRECT-ab-Receiver\",\"p\":\"receiver123\",\"g\":\"192.168.49.1\"}".toByteArray()
            )
        )
        assertNull(
            BleDirectHandshakeCodec.decode(
                "{\"v\":1,\"r\":\"rx\",\"s\":\"DIRECT-ab-Receiver\",\"p\":\"short\",\"g\":\"192.168.49.1\"}".toByteArray()
            )
        )
    }

    @Test
    fun encodedHandshakeFitsGattAttributeLimit() {
        val payload = BleDirectHandshakeCodec.encode(
            BleDirectHandshake(
                deviceName = "A fairly long receiver device name",
                ssid = "DIRECT-xy-LongAndroidDeviceName",
                passphrase = "012345678901234567890123456789",
                groupOwnerIp = "192.168.49.1",
                port = 50005
            )
        )

        assertNotNull(BleDirectHandshakeCodec.decode(payload))
        assertTrue(payload.size <= 512)
    }
}
