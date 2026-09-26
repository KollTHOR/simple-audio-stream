package com.example.audiostreamer

/** Policy for automatically making a receiver reachable without a local Wi-Fi network. */
internal object DirectReceiverPolicy {
    fun shouldHostWifiDirectGroup(localLanAvailable: Boolean, wifiDirectSupported: Boolean): Boolean =
        !localLanAvailable && wifiDirectSupported
}
