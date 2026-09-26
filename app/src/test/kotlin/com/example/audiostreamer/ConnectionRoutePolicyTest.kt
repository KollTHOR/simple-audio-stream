package com.example.audiostreamer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRoutePolicyTest {
    @Test
    fun connectableDiscoveryRequiresLanP2pPeerOrCompleteDirectCredentials() {
        assertTrue(ConnectionRoutePolicy.isConnectable("192.168.1.20", false, null, null))
        assertTrue(ConnectionRoutePolicy.isConnectable(null, true, null, null))
        assertTrue(ConnectionRoutePolicy.isConnectable(null, false, "DIRECT-ab-Receiver", "receiver123"))

        assertFalse(ConnectionRoutePolicy.isConnectable(null, false, null, null))
        assertFalse(ConnectionRoutePolicy.isConnectable("192.168.49.1", false, null, null))
        assertFalse(ConnectionRoutePolicy.isConnectable(null, false, "DIRECT-ab-Receiver", null))
        assertFalse(ConnectionRoutePolicy.isConnectable(null, false, "not-direct", "receiver123"))
    }
}
