package com.example.audiostreamer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer

/**
 * Information describing an active LAN interface suitable for HAT audio transport.
 */
data class LanInterfaceInfo(
    val name: String,
    val transportType: String,
    val addresses: List<Inet4Address>,
    val broadcastAddresses: List<String>
)

object NetworkUtils {

    private const val TAG = "NetworkUtils"

    @Volatile
    private var applicationContext: Context? = null

    /**
     * Visible for testing: override LAN availability in JVM unit tests.
     */
    @Volatile
    var testLanAvailableOverride: Boolean? = null

    /**
     * Initializes NetworkUtils with an Android application Context.
     */
    fun init(context: Context) {
        applicationContext = context.applicationContext
    }

    /**
     * Determines whether an actual local LAN network (Wi-Fi or Ethernet) is available.
     * Cellular mobile connections are explicitly excluded and never treated as LAN.
     */
    fun isLanAvailable(context: Context? = null): Boolean {
        testLanAvailableOverride?.let { return it }
        return getActiveLanInterfaces(context).isNotEmpty()
    }

    /**
     * Finds the primary LAN non-loopback IPv4 address of the device.
     * Returns null if no LAN-capable network is active (e.g. cellular only, or offline).
     */
    fun getLocalIpAddress(context: Context? = null): String? {
        return getAllLocalIpAddresses(context).firstOrNull()
    }

    /**
     * Returns all IPv4 addresses across active LAN interfaces (Wi-Fi, Ethernet, Hotspot).
     * Cellular addresses are strictly excluded.
     * Returns an empty list if no LAN-capable network is active.
     */
    fun getAllLocalIpAddresses(context: Context? = null): List<String> {
        if (!isLanAvailable(context)) {
            return emptyList()
        }
        val activeLan = getActiveLanInterfaces(context)
        return activeLan.flatMap { lan ->
            lan.addresses.mapNotNull { it.hostAddress }
        }.distinct()
    }

    /**
     * Discovers active LAN interfaces by inspecting Android [ConnectivityManager] and [NetworkCapabilities],
     * with support for local Wi-Fi Hotspot (SoftAP) and unit-test environments.
     *
     * Rules:
     * 1. Only accepts TRANSPORT_WIFI and TRANSPORT_ETHERNET as LAN transports.
     * 2. Interfaces with TRANSPORT_CELLULAR are strictly rejected and logged at debug level.
     * 3. Cellular interface name patterns (rmnet, ccmni, pdp, wwan, clat) are excluded.
     * 4. Wi-Fi Direct (P2P) is isolated from general LAN transport.
     */
    fun getActiveLanInterfaces(context: Context? = null): List<LanInterfaceInfo> {
        val ctx = context?.applicationContext ?: applicationContext
        val cm = ctx?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        val cellularInterfaceNames = mutableSetOf<String>()
        val cellularAddresses = mutableSetOf<String>()
        val lanInterfaces = mutableListOf<LanInterfaceInfo>()

        if (cm != null) {
            try {
                val networks = cm.allNetworks
                for (network in networks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    val linkProps = cm.getLinkProperties(network)
                    val ifName = linkProps?.interfaceName
                    val linkAddrs = linkProps?.linkAddresses?.mapNotNull { it.address as? Inet4Address } ?: emptyList()
                    val ipStrings = linkAddrs.mapNotNull { it.hostAddress }

                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        if (ifName != null) {
                            cellularInterfaceNames.add(ifName)
                        }
                        cellularAddresses.addAll(ipStrings)
                        Log.d(TAG, "Ignored cellular network: interface=$ifName, transport=TRANSPORT_CELLULAR, ips=$ipStrings")
                    } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                               caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        val transportName = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) "WIFI" else "ETHERNET"
                        val bcasts = mutableListOf<String>()
                        if (ifName != null) {
                            val intf = try { NetworkInterface.getByName(ifName) } catch (e: Exception) { null }
                            if (intf != null) {
                                bcasts.addAll(computeBroadcastsForInterface(intf))
                            }
                            lanInterfaces.add(
                                LanInterfaceInfo(
                                    name = ifName,
                                    transportType = transportName,
                                    addresses = linkAddrs,
                                    broadcastAddresses = bcasts.distinct()
                                )
                            )
                            Log.i(TAG, "Selected LAN interface: $ifName (transport: $transportName, ips: $ipStrings)")
                        }
                    } else {
                        Log.d(TAG, "Ignored non-LAN network: interface=$ifName, ips=$ipStrings")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error querying ConnectivityManager: ${e.message}", e)
            }
        }

        // Secondary check: Wi-Fi Hotspot (SoftAP) interfaces or JVM test environment fallback
        try {
            val allSysInterfaces = NetworkInterface.getNetworkInterfaces()
            if (allSysInterfaces != null) {
                for (intf in allSysInterfaces) {
                    val ifName = intf.name ?: continue
                    try {
                        if (intf.isLoopback) continue
                    } catch (ignored: Exception) {}

                    // Exclude known cellular interfaces or patterns
                    if (ifName in cellularInterfaceNames || isCellularInterfaceName(ifName)) {
                        if (ifName !in cellularInterfaceNames) {
                            Log.d(TAG, "Ignored cellular interface by name pattern: $ifName")
                        }
                        continue
                    }

                    // Exclude P2P virtual interfaces from general LAN
                    if (ifName.contains("p2p", ignoreCase = true)) {
                        continue
                    }

                    // Collect valid IPv4 addresses
                    val addrs = try {
                        intf.inetAddresses.toList().filterIsInstance<Inet4Address>().filter {
                            !it.isLoopbackAddress && it.hostAddress?.startsWith("127.") == false && it.hostAddress?.startsWith("192.168.49.") == false
                        }
                    } catch (e: Exception) {
                        emptyList()
                    }

                    if (addrs.isEmpty()) continue

                    // If already added via ConnectivityManager, enrich if necessary
                    val existing = lanInterfaces.indexOfFirst { it.name == ifName }
                    if (existing >= 0) {
                        if (lanInterfaces[existing].addresses.isEmpty()) {
                            val bcasts = computeBroadcastsForInterface(intf)
                            lanInterfaces[existing] = lanInterfaces[existing].copy(
                                addresses = addrs,
                                broadcastAddresses = bcasts
                            )
                        }
                        continue
                    }

                    // If ConnectivityManager is available, only accept known Wi-Fi SoftAP / Ethernet patterns
                    if (cm != null) {
                        val isSoftApOrEth = ifName.startsWith("wlan", ignoreCase = true) ||
                                            ifName.startsWith("ap", ignoreCase = true) ||
                                            ifName.startsWith("softap", ignoreCase = true) ||
                                            ifName.startsWith("swlan", ignoreCase = true) ||
                                            ifName.startsWith("eth", ignoreCase = true)
                        if (isSoftApOrEth) {
                            val bcasts = computeBroadcastsForInterface(intf)
                            val ipStrings = addrs.mapNotNull { it.hostAddress }
                            Log.i(TAG, "Selected LAN interface (Hotspot/Local): $ifName (transport: HOTSPOT_WIFI, ips: $ipStrings)")
                            lanInterfaces.add(
                                LanInterfaceInfo(
                                    name = ifName,
                                    transportType = "HOTSPOT_WIFI",
                                    addresses = addrs,
                                    broadcastAddresses = bcasts
                                )
                            )
                        }
                    } else {
                        // Fallback for JVM unit tests without Android Context:
                        // include non-loopback, non-cellular interfaces (e.g. eth0, wlan0, en0)
                        val bcasts = computeBroadcastsForInterface(intf)
                        lanInterfaces.add(
                            LanInterfaceInfo(
                                name = ifName,
                                transportType = "LAN_FALLBACK",
                                addresses = addrs,
                                broadcastAddresses = bcasts
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error enumerating system interfaces: ${e.message}", e)
        }

        return lanInterfaces
    }

    private fun isCellularInterfaceName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("rmnet") ||
               lower.startsWith("ccmni") ||
               lower.startsWith("pdp") ||
               lower.startsWith("wwan") ||
               lower.startsWith("clat") ||
               lower.startsWith("dummy") ||
               lower.startsWith("cellular") ||
               lower.startsWith("mobile")
    }

    /**
     * Finds all active Wi-Fi Direct (P2P) IPv4 addresses.
     */
    fun getP2pIpAddresses(): List<String> {
        val p2pIps = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (intf in interfaces) {
                try {
                    if (intf.isLoopback) continue
                } catch (ignored: Exception) {}

                val isP2pInterface = intf.name.contains("p2p", ignoreCase = true)
                val addrs = try { intf.inetAddresses } catch (e: Exception) { null } ?: continue
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (!host.startsWith("127.")) {
                            if (isP2pInterface || host.startsWith("192.168.49.")) {
                                p2pIps.add(host)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed getting P2P IP addresses: ${e.message}")
        }
        return p2pIps.distinct()
    }

    /**
     * Finds a local IP address on the same subnet as targetIp (e.g. matching /24 prefix).
     * Inspects both active LAN interfaces and P2P interfaces, but never cellular interfaces.
     */
    fun findMatchingLocalIp(targetIp: String, context: Context? = null): String? {
        val targetParts = targetIp.split(".")
        if (targetParts.size != 4) return null
        val allIps = getAllLocalIpAddresses(context) + getP2pIpAddresses()
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

        // 3. Fallback: prefer P2P IP if target is P2P, otherwise primary LAN IP
        return if (targetIp.startsWith("192.168.49.")) {
            getP2pIpAddresses().firstOrNull() ?: allIps.firstOrNull()
        } else {
            getAllLocalIpAddresses(context).firstOrNull() ?: allIps.firstOrNull()
        }
    }

    /**
     * Computes broadcast addresses across all active LAN interfaces.
     * Returns an empty list if LAN is not available (ensuring no broadcasts are sent through cellular).
     */
    fun getAllBroadcastAddresses(context: Context? = null): List<String> {
        if (!isLanAvailable(context)) {
            return emptyList()
        }
        val activeLan = getActiveLanInterfaces(context)
        if (activeLan.isEmpty()) {
            return emptyList()
        }
        val broadcasts = mutableSetOf<String>()
        for (lan in activeLan) {
            broadcasts.addAll(lan.broadcastAddresses)
        }
        broadcasts.add("255.255.255.255")
        return broadcasts.toList()
    }

    private fun computeBroadcastsForInterface(intf: NetworkInterface): List<String> {
        val broadcasts = mutableSetOf<String>()
        try {
            val ifAddresses = intf.interfaceAddresses ?: return emptyList()
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

                    // 3. Guaranteed /24 broadcast fallback
                    val parts = host.split(".")
                    if (parts.size == 4) {
                        broadcasts.add("${parts[0]}.${parts[1]}.${parts[2]}.255")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed getting interface broadcast addresses for ${intf.name}: ${e.message}")
        }
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
     * Returns empty string if LAN is unavailable.
     */
    fun getSuggestedBroadcastIp(context: Context? = null): String {
        if (!isLanAvailable(context)) {
            return ""
        }
        val bcasts = getAllBroadcastAddresses(context).filter { it != "255.255.255.255" }
        return bcasts.firstOrNull() ?: ""
    }
}
