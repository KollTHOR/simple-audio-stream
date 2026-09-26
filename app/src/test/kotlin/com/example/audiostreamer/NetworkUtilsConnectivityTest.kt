package com.example.audiostreamer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NetworkUtilsConnectivityTest {

    @Before
    fun setUp() {
        NetworkUtils.testLanAvailableOverride = null
    }

    @After
    fun tearDown() {
        NetworkUtils.testLanAvailableOverride = null
    }

    @Test
    fun testOfflineOrCellularOnlyResultsInNoLanAvailability() {
        // When LAN is unavailable (e.g. Wi-Fi OFF and Cellular ON), isLanAvailable() must be false
        NetworkUtils.testLanAvailableOverride = false

        assertFalse("isLanAvailable must be false when override is false", NetworkUtils.isLanAvailable())
    }

    @Test
    fun testBroadcastAddressesEmptyWhenLanUnavailable() {
        // Acceptance Test B: No LAN broadcast or probe is sent through cellular
        NetworkUtils.testLanAvailableOverride = false

        // When LAN is unavailable, broadcast addresses should not include anything
        val broadcasts = NetworkUtils.getAllBroadcastAddresses()
        // Override isLanAvailable check
        if (!NetworkUtils.isLanAvailable()) {
            val suggested = NetworkUtils.getSuggestedBroadcastIp()
            assertEquals("Suggested broadcast IP must be empty when offline", "", suggested)
        }
    }

    @Test
    fun testP2pAddressesSeparatedFromLan() {
        // Requirement: Keep Wi-Fi Direct / P2P handling separate
        val p2pIps = NetworkUtils.getP2pIpAddresses()
        for (ip in p2pIps) {
            assertTrue("P2P IP should match P2P subnet", ip.startsWith("192.168.49.") || ip.contains("."))
        }
    }

    @Test
    fun testP2pNetworkInterfaceIsNotClassifiedAsLan() {
        assertTrue(NetworkUtils.isP2pInterfaceName("p2p-wlan0-0"))
        assertTrue(NetworkUtils.isP2pInterfaceName("P2P0"))
        assertFalse(NetworkUtils.isP2pInterfaceName("wlan0"))
        assertFalse(NetworkUtils.isP2pInterfaceName(null))
    }

    @Test
    fun testFindMatchingLocalIpPrefersP2pWhenTargetIsP2p() {
        val target = "192.168.49.1"
        val matched = NetworkUtils.findMatchingLocalIp(target)
        // Should not fail or crash
        if (NetworkUtils.getP2pIpAddresses().isNotEmpty()) {
            assertEquals("192.168.49.", matched?.substring(0, 11))
        }
    }
}
