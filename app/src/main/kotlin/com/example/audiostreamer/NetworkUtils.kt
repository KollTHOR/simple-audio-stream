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
        val lanIps = mutableListOf<String>()
        val p2pIps = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (intf in interfaces) {
                try {
                    if (intf.isLoopback) continue
                } catch (ignored: Exception) {}

                val addrs = try {
                    intf.inetAddresses
                } catch (e: Exception) {
                    null
                } ?: continue

                val isP2pInterface = intf.name.contains("p2p", ignoreCase = true)

                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (!host.startsWith("127.")) {
                            if (isP2pInterface || host.startsWith("192.168.49.")) {
                                p2pIps.add(host)
                            } else {
                                lanIps.add(host)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get all local IP addresses", e)
        }
        // Always prioritize real Wi-Fi/Ethernet LAN IPs ahead of P2P virtual interfaces
        return (lanIps + p2pIps).distinct()
    }

    /**
     * Finds a local IP address on the same subnet as targetIp (e.g. matching /24 prefix).
     * If targetIp is a subnet broadcast or unicast IP, finds the corresponding local interface IP.
     */
    fun findMatchingLocalIp(targetIp: String): String? {
        val targetParts = targetIp.split(".")
        if (targetParts.size != 4) return null
        val allIps = getAllLocalIpAddresses()
        if (allIps.isEmpty()) return null

        // 1. Exact /24 prefix match (first 3 octets)
        val subnetMatch = allIps.firstOrNull { local ->
            val localParts = local.split(".")
            localParts.size == 4 &&
                    localParts[0] == targetParts[0] &&
                    localParts[1] == targetParts[1] &&
                    localParts[2] == targetParts[2]
        }
        if (subnetMatch != null) return subnetMatch

        // 2. /16 prefix match (first 2 octets)
        val prefix16Match = allIps.firstOrNull { local ->
            val localParts = local.split(".")
            localParts.size == 4 &&
                    localParts[0] == targetParts[0] &&
                    localParts[1] == targetParts[1]
        }
        if (prefix16Match != null) return prefix16Match

        // 3. Fallback to first available local IP
        return allIps.firstOrNull()
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
                try {
                    if (intf.isLoopback) continue
                } catch (ignored: Exception) {}

                val ifAddresses = try {
                    intf.interfaceAddresses
                } catch (e: Exception) {
                    null
                } ?: continue

                for (ifAddr in ifAddresses) {
                    val addr = ifAddr.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("127.")) continue

                        // 1. Direct interface broadcast if provided by OS
                        try {
                            val bcast = ifAddr.broadcast
                            if (bcast != null && bcast is Inet4Address) {
                                val bcastHost = bcast.hostAddress
                                if (!bcastHost.isNullOrEmpty() && !bcastHost.startsWith("127.")) {
                                    broadcasts.add(bcastHost)
                                }
                            }
                        } catch (ignored: Exception) {}

                        // 2. Prefix-calculated broadcast
                        val calculated = calculateBroadcastAddress(addr, ifAddr.networkPrefixLength)
                        if (!calculated.isNullOrEmpty() && !calculated.startsWith("127.")) {
                            broadcasts.add(calculated)
                        }

                        // 3. Guaranteed /24 broadcast fallback (e.g. 10.164.116.1 -> 10.164.116.255)
                        val parts = host.split(".")
                        if (parts.size == 4) {
                            broadcasts.add("${parts[0]}.${parts[1]}.${parts[2]}.255")
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
