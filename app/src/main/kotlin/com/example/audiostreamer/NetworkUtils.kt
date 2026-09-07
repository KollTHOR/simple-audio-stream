package com.example.audiostreamer

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    private const val TAG = "NetworkUtils"

    /**
     * Finds the primary non-loopback IPv4 address of the device (typically Wi-Fi or Hotspot).
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                // Prefer Wi-Fi or tethering interfaces (wlan, ap, eth)
                val addrs = intf.inetAddresses ?: continue
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (!host.startsWith("127.")) {
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get local IP address", e)
        }
        return null
    }

    /**
     * Calculates default directed subnet broadcast IP from the local IP.
     * e.g., 192.168.1.45 -> 192.168.1.255
     */
    fun getSuggestedBroadcastIp(): String {
        val ip = getLocalIpAddress() ?: return "192.168.43.255"
        val parts = ip.split(".")
        return if (parts.size == 4) {
            "${parts[0]}.${parts[1]}.${parts[2]}.255"
        } else {
            "192.168.43.255"
        }
    }
}
