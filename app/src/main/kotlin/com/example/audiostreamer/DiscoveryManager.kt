package com.example.audiostreamer

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.example.audiostreamer.AppLogger as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

data class DiscoveredDevice(
    val name: String,
    val ip: String,
    val port: Int,
    val capabilitiesMask: Int = 0,
    val isP2pActive: Boolean = false,
    val p2pSsid: String? = null,
    val p2pPassphrase: String? = null,
    val p2pGoIp: String? = null,
    val lastSeenMs: Long = SystemClock.elapsedRealtime()
)

object DiscoveryManager {
    private const val TAG = "DiscoveryManager"
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _lastScanTimeMs = MutableStateFlow(0L)
    val lastScanTimeMs: StateFlow<Long> = _lastScanTimeMs.asStateFlow()

    @Volatile
    var lastDiscoveredReceiverCapabilities: Int = 0
        private set

    private var discoveryJob: Job? = null
    @Volatile
    private var activeSocket: DatagramSocket? = null

    private var receiverResponderJob: Job? = null
    @Volatile
    private var receiverResponderSocket: DatagramSocket? = null
    private var receiverMulticastLock: WifiManager.MulticastLock? = null

    fun getLocalDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    }

    fun startDiscovery(scope: CoroutineScope) {
        if (discoveryJob?.isActive == true) return

        discoveryJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = try {
                    DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        soTimeout = 2000
                        bind(InetSocketAddress(AudioConfig.DISCOVERY_PORT))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Port ${AudioConfig.DISCOVERY_PORT} busy, binding ephemeral port: ${e.message}")
                    DatagramSocket().apply {
                        broadcast = true
                        soTimeout = 2000
                    }
                }
                activeSocket = socket
                Log.i(TAG, "Transmitter discovery listening on port ${socket.localPort}")

                // Send initial probe
                sendProbe(socket)

                val buffer = ByteArray(256)
                val packet = DatagramPacket(buffer, buffer.size)

                var lastProbeTime = SystemClock.elapsedRealtime()

                while (isActive) {
                    try {
                        packet.length = buffer.size
                        socket.receive(packet)

                        if (packet.length >= AudioConfig.HEADER_SIZE) {
                            val data = packet.data
                            val magic = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                            val flags = data[5]

                            if (magic == AudioConfig.MAGIC_HEADER.toInt() &&
                                (flags.toInt() and AudioConfig.FLAG_DISCOVERY_ANNOUNCE.toInt()) != 0
                            ) {
                                val rxCaps = data[4].toInt() and 0xFF
                                if (rxCaps != 0) {
                                    lastDiscoveredReceiverCapabilities = rxCaps
                                }
                                val isP2pFlag = (flags.toInt() and AudioConfig.FLAG_DISCOVERY_P2P_ACTIVE.toInt()) != 0
                                val payloadLen = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
                                val nameBytesLen = minOf(payloadLen, packet.length - AudioConfig.HEADER_SIZE)
                                val rawPayload = if (nameBytesLen > 0) {
                                    String(data, AudioConfig.HEADER_SIZE, nameBytesLen, Charsets.UTF_8).trim()
                                } else {
                                    "Audio Receiver"
                                }

                                var devName = rawPayload
                                var isP2pActive = isP2pFlag
                                var p2pSsid: String? = null
                                var p2pPass: String? = null
                                var p2pGoIp: String? = null

                                if (rawPayload.startsWith("{") && rawPayload.endsWith("}")) {
                                    try {
                                        val json = org.json.JSONObject(rawPayload)
                                        devName = json.optString("name", devName)
                                        isP2pActive = json.optBoolean("p2p", isP2pFlag)
                                        p2pSsid = json.optString("ssid").takeIf { it.isNotEmpty() }
                                        p2pPass = json.optString("pass").takeIf { it.isNotEmpty() }
                                        p2pGoIp = json.optString("goIp").takeIf { it.isNotEmpty() }
                                    } catch (ignored: Exception) {}
                                }

                                val senderIp = packet.address.hostAddress
                                if (senderIp != null) {
                                    addDiscoveredDevice(
                                        DiscoveredDevice(
                                            name = devName,
                                            ip = senderIp,
                                            port = AudioConfig.DEFAULT_PORT,
                                            capabilitiesMask = rxCaps,
                                            isP2pActive = isP2pActive,
                                            p2pSsid = p2pSsid,
                                            p2pPassphrase = p2pPass,
                                            p2pGoIp = p2pGoIp
                                        )
                                    )
                                }
                            }
                        }
                    } catch (ignored: Exception) {
                        // SocketTimeoutException expected
                    }

                    // Re-probe every 4 seconds
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProbeTime >= 4000) {
                        sendProbe(socket)
                        lastProbeTime = now
                        pruneStaleDevices()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Discovery loop exception: ${e.message}")
            } finally {
                activeSocket = null
                socket?.close()
                _isScanning.value = false
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        activeSocket?.close()
        activeSocket = null
        _isScanning.value = false
    }

    fun triggerScan(scope: CoroutineScope) {
        _isScanning.value = true
        _lastScanTimeMs.value = System.currentTimeMillis()
        if (discoveryJob?.isActive != true) {
            startDiscovery(scope)
        } else {
            scope.launch(Dispatchers.IO) {
                val sock = activeSocket
                if (sock != null && !sock.isClosed) {
                    sendProbe(sock)
                }
            }
        }
        scope.launch {
            kotlinx.coroutines.delay(2500)
            _isScanning.value = false
        }
    }

    fun startReceiverResponder(context: Context, scope: CoroutineScope) {
        if (receiverResponderJob?.isActive == true) return

        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            receiverMulticastLock = wifiManager?.createMulticastLock("DiscoveryManager:ReceiverMulticastLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (ignored: Exception) {}

        receiverResponderJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 2000
                    bind(InetSocketAddress(AudioConfig.DISCOVERY_PORT))
                }
                receiverResponderSocket = socket
                Log.i(TAG, "Receiver responder listening on port ${AudioConfig.DISCOVERY_PORT}")

                // Broadcast initial announcement across all active interfaces
                sendAnnouncement(socket)

                val buffer = ByteArray(256)
                val packet = DatagramPacket(buffer, buffer.size)
                var lastAnnounceTime = SystemClock.elapsedRealtime()

                while (isActive) {
                    try {
                        packet.length = buffer.size
                        socket.receive(packet)

                        if (packet.length >= AudioConfig.HEADER_SIZE) {
                            val data = packet.data
                            val magic = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                            val flags = data[5]

                            if (magic == AudioConfig.MAGIC_HEADER.toInt() &&
                                (flags.toInt() and AudioConfig.FLAG_DISCOVERY_PROBE.toInt()) != 0
                            ) {
                                // Reply with announce directly to probing transmitter
                                val announceBuf = buildAnnouncePacket()
                                val replyPacket1 = DatagramPacket(announceBuf, announceBuf.size, packet.address, AudioConfig.DISCOVERY_PORT)
                                socket.send(replyPacket1)
                                if (packet.port != AudioConfig.DISCOVERY_PORT) {
                                    val replyPacket2 = DatagramPacket(announceBuf, announceBuf.size, packet.address, packet.port)
                                    try { socket.send(replyPacket2) } catch (ignored: Exception) {}
                                }
                                Log.d(TAG, "Sent discovery announce reply to ${packet.address}")
                            }
                        }
                    } catch (ignored: Exception) {
                        // SocketTimeoutException expected
                    }

                    // Periodically re-announce every 3 seconds
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastAnnounceTime >= 3000) {
                        sendAnnouncement(socket)
                        lastAnnounceTime = now
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Receiver responder exception: ${e.message}")
            } finally {
                receiverResponderSocket = null
                socket?.close()
            }
        }
    }

    fun stopReceiverResponder() {
        receiverResponderJob?.cancel()
        receiverResponderJob = null
        receiverResponderSocket?.close()
        receiverResponderSocket = null
        try {
            receiverMulticastLock?.let { if (it.isHeld) it.release() }
        } catch (ignored: Exception) {}
        receiverMulticastLock = null
    }

    private fun buildAnnouncePacket(): ByteArray {
        val deviceName = getLocalDeviceName()
        val isP2p = WifiDirectManager.isGroupCreated.value
        val p2pSsid = WifiDirectManager.networkSsid.value
        val p2pPass = WifiDirectManager.networkPassphrase.value
        val p2pGoIp = WifiDirectManager.groupOwnerIp.value ?: WifiDirectManager.DEFAULT_GO_IP

        val payloadString = if (isP2p && !p2pSsid.isNullOrEmpty()) {
            val json = org.json.JSONObject().apply {
                put("name", deviceName)
                put("p2p", true)
                put("ssid", p2pSsid)
                put("pass", if (!p2pPass.isNullOrEmpty()) p2pPass else WifiDirectManager.P2P_DEFAULT_PASSPHRASE)
                put("goIp", p2pGoIp)
            }
            json.toString()
        } else {
            deviceName
        }

        val nameBytes = payloadString.toByteArray(Charsets.UTF_8).take(128).toByteArray()
        val announceBuf = ByteArray(AudioConfig.HEADER_SIZE + nameBytes.size)
        announceBuf[0] = (AudioConfig.MAGIC_HEADER.toInt() shr 8).toByte()
        announceBuf[1] = (AudioConfig.MAGIC_HEADER.toInt() and 0xFF).toByte()
        announceBuf[4] = AudioCapabilities.getLocalPlaybackCapabilitiesMask().toByte()
        val flagByte = if (isP2p) {
            (AudioConfig.FLAG_DISCOVERY_ANNOUNCE.toInt() or AudioConfig.FLAG_DISCOVERY_P2P_ACTIVE.toInt()).toByte()
        } else {
            AudioConfig.FLAG_DISCOVERY_ANNOUNCE
        }
        announceBuf[5] = flagByte
        announceBuf[6] = (nameBytes.size shr 8).toByte()
        announceBuf[7] = (nameBytes.size and 0xFF).toByte()
        System.arraycopy(nameBytes, 0, announceBuf, AudioConfig.HEADER_SIZE, nameBytes.size)
        return announceBuf
    }

    fun sendAnnouncement(socket: DatagramSocket) {
        try {
            val announceBuf = buildAnnouncePacket()
            val targets = mutableSetOf<String>()
            targets.addAll(NetworkUtils.getAllBroadcastAddresses())
            for (localIp in NetworkUtils.getAllLocalIpAddresses()) {
                val parts = localIp.split(".")
                if (parts.size == 4 && parts[3] != "1") {
                    targets.add("${parts[0]}.${parts[1]}.${parts[2]}.1")
                }
            }

            for (targetIp in targets) {
                try {
                    val bcastPacket = DatagramPacket(announceBuf, announceBuf.size, InetAddress.getByName(targetIp), AudioConfig.DISCOVERY_PORT)
                    socket.send(bcastPacket)
                } catch (ignored: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed sending announcements: ${e.message}")
        }
    }

    private fun sendProbe(socket: DatagramSocket) {
        val targets = mutableSetOf<String>()
        targets.addAll(NetworkUtils.getAllBroadcastAddresses())
        targets.addAll(NetworkUtils.getArpClientIps())
        targets.add("255.255.255.255")

        val buffer = ByteArray(AudioConfig.HEADER_SIZE)
        buffer[0] = (AudioConfig.MAGIC_HEADER.toInt() shr 8).toByte()
        buffer[1] = (AudioConfig.MAGIC_HEADER.toInt() and 0xFF).toByte()
        buffer[2] = 0
        buffer[3] = 0
        buffer[4] = 0
        buffer[5] = AudioConfig.FLAG_DISCOVERY_PROBE
        buffer[6] = 0
        buffer[7] = 0

        // 1. Send through main discovery socket
        for (targetIp in targets) {
            try {
                val address = InetAddress.getByName(targetIp)
                val packet = DatagramPacket(buffer, buffer.size, address, AudioConfig.DISCOVERY_PORT)
                socket.send(packet)
                Log.d(TAG, "Sent discovery probe to $targetIp:${AudioConfig.DISCOVERY_PORT}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed sending probe to $targetIp: ${e.message}")
            }
        }

        // 2. Also send from sockets bound to each local IP (forces routing out secondary interfaces e.g. ap0)
        val localIps = NetworkUtils.getAllLocalIpAddresses()
        if (localIps.size > 1) {
            for (localIp in localIps) {
                var ifSocket: DatagramSocket? = null
                try {
                    ifSocket = DatagramSocket(InetSocketAddress(InetAddress.getByName(localIp), 0)).apply {
                        broadcast = true
                        soTimeout = 1000
                    }
                    for (targetIp in targets) {
                        try {
                            val address = InetAddress.getByName(targetIp)
                            val packet = DatagramPacket(buffer, buffer.size, address, AudioConfig.DISCOVERY_PORT)
                            ifSocket.send(packet)
                        } catch (ignored: Exception) {}
                    }
                } catch (ignored: Exception) {
                } finally {
                    try { ifSocket?.close() } catch (ignored: Exception) {}
                }
            }
        }
    }

    @Synchronized
    private fun addDiscoveredDevice(device: DiscoveredDevice) {
        val current = _discoveredDevices.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.ip == device.ip }
        if (existingIndex >= 0) {
            current[existingIndex] = device
        } else {
            current.add(device)
            Log.i(TAG, "Discovered new receiver: ${device.name} at ${device.ip}")
        }
        _discoveredDevices.value = current
    }

    @Synchronized
    private fun pruneStaleDevices() {
        val now = SystemClock.elapsedRealtime()
        val filtered = _discoveredDevices.value.filter { now - it.lastSeenMs < 16000 }
        if (filtered.size != _discoveredDevices.value.size) {
            _discoveredDevices.value = filtered
        }
    }
}
