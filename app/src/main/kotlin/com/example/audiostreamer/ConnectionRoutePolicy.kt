package com.example.audiostreamer

import com.example.audiostreamer.node.discovery.DiscoveredNodeEntry

/** Determines whether a discovery result includes an endpoint the app can actually connect to. */
internal object ConnectionRoutePolicy {
    fun isConnectable(
        lanAddress: String?,
        hasWifiDirectPeer: Boolean,
        p2pSsid: String?,
        p2pPassphrase: String?
    ): Boolean {
        if (!lanAddress.isNullOrBlank() && !lanAddress.startsWith("192.168.49.")) return true
        if (hasWifiDirectPeer) return true
        return p2pSsid?.startsWith("DIRECT-") == true &&
            p2pSsid.length >= 10 &&
            (p2pPassphrase?.length ?: 0) >= 8
    }

    fun isConnectable(entry: DiscoveredNodeEntry): Boolean {
        val p2pEndpoint = entry.getBleEndpoint()
        return isConnectable(
            lanAddress = entry.getLanEndpoint()?.address,
            hasWifiDirectPeer = !entry.getWifiDirectEndpoint()?.address.isNullOrBlank(),
            p2pSsid = p2pEndpoint?.details?.get("p2pSsid") as? String,
            p2pPassphrase = p2pEndpoint?.details?.get("p2pPassphrase") as? String
        )
    }
}
