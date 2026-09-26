package com.example.audiostreamer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectReceiverPolicyTest {
    @Test
    fun startsDirectGroupOnlyWhenOfflineAndWifiDirectIsSupported() {
        assertTrue(DirectReceiverPolicy.shouldHostWifiDirectGroup(localLanAvailable = false, wifiDirectSupported = true))
        assertFalse(DirectReceiverPolicy.shouldHostWifiDirectGroup(localLanAvailable = true, wifiDirectSupported = true))
        assertFalse(DirectReceiverPolicy.shouldHostWifiDirectGroup(localLanAvailable = false, wifiDirectSupported = false))
    }
}
