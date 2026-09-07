package com.example.audiostreamer

import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer

object NetworkUtils {

    private const val TAG = "NetworkUtils"

    /**
     * Finds the primary non-loopback IPv4 address of the device.
     */
    fun getLocalIpAddress(): String? {
        val ips = getAllLocalIpAddresses()
        return ips.firstOrNull()
    }

    /**
     * Finds all non-loopback IPv4 addresses across all active interfaces (e.g. Wi-Fi and Hotspot).
     */
    fun getAllLocalIpAddresses(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses ?: continue
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (!host.startsWith("127.")) {
                            ips.add(host)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get all local IP addresses", e)
        }
        return ips
    }

    /**
     * Finds all broadcast addresses across all active interfaces (e.g. Wi-Fi, Hotspot, etc.)
     * including directed subnet broadcasts and the global broadcast 255.255.255.255.
     */
    fun getAllBroadcastAddresses(): List<String> {
        val broadcasts = mutableSetOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return listOf("255.255.255.255")
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (ifAddr in intf.interfaceAddresses) {
                    val addr = ifAddr.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val bcast = ifAddr.broadcast
                        if (bcast != null && bcast is Inet4Address) {
                            val host = bcast.hostAddress
                            if (!host.isNullOrEmpty() && !host.startsWith("127.")) {
                                broadcasts.add(host)
                            }
                        } else {
                            val calculated = calculateBroadcastAddress(addr, ifAddr.networkPrefixLength)
                            if (!calculated.isNullOrEmpty() && !calculated.startsWith("127.")) {
                                broadcasts.add(calculated)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed getting broadcast addresses", e)
        }

        // Always include global limited broadcast
        broadcasts.add("255.255.255.255")
        return broadcasts.toList()
    }

    /**
     * Calculates subnet broadcast IP from an IPv4 address and prefix length.
     * e.g., 10.164.116.1 with prefix 24 -> 10.164.116.255
     */
    fun calculateBroadcastAddress(ip: Inet4Address, prefixLength: Short): String? {
        if (prefixLength < 0 || prefixLength > 32) return null
        try {
            val ipInt = ByteBuffer.wrap(ip.address).int
            val mask = if (prefixLength == 0.toShort()) 0 else (-1 shl (32 - prefixLength.toInt()))
            val broadcastInt = (ipInt and mask) or mask.inv()
            val bytes = ByteBuffer.allocate(4).putInt(broadcastInt).array()
            return InetAddress.getByAddress(bytes).hostAddress
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Reads /proc/net/arp to discover any active client devices connected to the device
     * (e.g. m300 connected to phone's Wi-Fi hotspot).
     */
    fun getArpClientIps(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val arp = File("/proc/net/arp")
            if (arp.exists() && arp.canRead()) {
                arp.forEachLine { line ->
                    val tokens = line.trim().split(Regex("\\s+"))
                    if (tokens.size >= 4 && tokens[0] != "IP") {
                        val ip = tokens[0]
                        val flags = tokens[2]
                        // Flag 0x2 indicates resolved entry
                        if (flags == "0x2" && !ip.startsWith("127.")) {
                            ips.add(ip)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not read /proc/net/arp: ${e.message}")
        }
        return ips
    }

    /**
     * Calculates default directed subnet broadcast IP from the primary local IP.
     */
    fun getSuggestedBroadcastIp(): String {
        val bcasts = getAllBroadcastAddresses().filter { it != "255.255.255.255" }
        return bcasts.firstOrNull() ?: "192.168.43.255"
    }
}
