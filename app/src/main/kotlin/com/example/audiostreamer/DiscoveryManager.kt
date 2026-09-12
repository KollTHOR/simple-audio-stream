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
    val p2pMac: String? = null,
    val modelName: String? = null,
    val lastSeenMs: Long = SystemClock.elapsedRealtime()
)

object DiscoveryManager {
    private const val TAG = "DiscoveryManager"
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _discoveredStreams = MutableStateFlow<List<DiscoveredStream>>(emptyList())
    val discoveredStreams: StateFlow<List<DiscoveredStream>> = _discoveredStreams.asStateFlow()

    @Volatile
    var activePublishedStream: PublishedStream? = null
        private set

    fun publishStream(stream: PublishedStream) {
        activePublishedStream = stream
        Log.i(TAG, "Published stream: ${stream.name} (${stream.id}) at ${stream.endpoint}")
        val sock = activeSocket ?: receiverResponderSocket
        if (sock != null && !sock.isClosed) {
            sendStreamAnnouncement(sock, stream)
        }
    }

    fun unpublishStream(id: StreamId? = null) {
        if (id == null || activePublishedStream?.id == id) {
            val prev = activePublishedStream
            activePublishedStream = null
            Log.i(TAG, "Unpublished stream: ${prev?.name} (${prev?.id})")
        }
    }

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

                val buffer = ByteArray(1500)
                val packet = DatagramPacket(buffer, buffer.size)

                var lastProbeTime = SystemClock.elapsedRealtime()

                while (isActive) {
                    try {
                        packet.length = buffer.size
                        socket.receive(packet)

                        val data = packet.data
                        val header = HatPacket.parseHeader(data, 0, packet.length)
                        if (header != null) {
                            when (header.packetType) {
                                HatPacket.TYPE_DISCOVERY_ANNOUNCE -> {
                                    val rxCaps = header.volumeOrCaps.toInt() and 0xFF
                                    if (rxCaps != 0) {
                                        lastDiscoveredReceiverCapabilities = rxCaps
                                    }
                                    val isP2pFlag = (header.flags.toInt() and HatPacket.FLAG_P2P_ACTIVE.toInt()) != 0
                                    val rawPayload = if (header.payloadLength > 0) {
                                        String(data, HatPacket.HEADER_SIZE, header.payloadLength, Charsets.UTF_8).trim()
                                    } else {
                                        "Audio Receiver"
                                    }

                                    var devName = rawPayload
                                    var isP2pActive = isP2pFlag
                                    var p2pSsid: String? = null
                                    var p2pPass: String? = null
                                    var p2pGoIp: String? = null
                                    var p2pMac: String? = null
                                    var modelName: String? = null

                                    if (rawPayload.startsWith("{") && rawPayload.endsWith("}")) {
                                        try {
                                            val json = org.json.JSONObject(rawPayload)
                                            devName = json.optString("name", devName)
                                            isP2pActive = json.optBoolean("p2p", isP2pFlag)
                                            p2pSsid = json.optString("ssid").takeIf { it.isNotEmpty() }
                                            p2pPass = json.optString("pass").takeIf { it.isNotEmpty() }
                                            p2pGoIp = json.optString("goIp").takeIf { it.isNotEmpty() }
                                            p2pMac = json.optString("p2pMac").takeIf { it.isNotEmpty() }
                                            modelName = json.optString("model").takeIf { it.isNotEmpty() }
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
                                                p2pGoIp = p2pGoIp,
                                                p2pMac = p2pMac,
                                                modelName = modelName
                                            )
                                        )
                                    }
                                }
                                HatPacket.TYPE_STREAM_ANNOUNCE -> {
                                    if (header.payloadLength > 0) {
                                        val rawPayload = String(data, HatPacket.HEADER_SIZE, header.payloadLength, Charsets.UTF_8).trim()
                                        val stream = PublishedStream.fromJson(rawPayload)
                                        if (stream != null) {
                                            val senderIp = packet.address.hostAddress ?: stream.endpoint.host
                                            val effectiveHost = if (stream.endpoint.host.isBlank() || stream.endpoint.host == "0.0.0.0") {
                                                senderIp
                                            } else {
                                                stream.endpoint.host
                                            }
                                            addDiscoveredStream(stream.copy(endpoint = stream.endpoint.copy(host = effectiveHost)))
                                        }
                                    }
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
                        pruneStaleStreams()
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
        val sock = activeSocket ?: receiverResponderSocket
        if (sock != null && !sock.isClosed) {
            scope.launch(Dispatchers.IO) {
                sendProbe(sock)
            }
        } else if (discoveryJob?.isActive != true) {
            startDiscovery(scope)
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

                val buffer = ByteArray(1500)
                val packet = DatagramPacket(buffer, buffer.size)
                var lastAnnounceTime = SystemClock.elapsedRealtime()

                while (isActive) {
                    try {
                        packet.length = buffer.size
                        socket.receive(packet)

                        val data = packet.data
                        val header = HatPacket.parseHeader(data, 0, packet.length)
                        if (header != null) {
                            when (header.packetType) {
                                HatPacket.TYPE_DISCOVERY_PROBE -> {
                                    // Reply with announce directly to probing transmitter
                                    val announceBuf = buildAnnouncePacket()
                                    val replyPacket1 = DatagramPacket(announceBuf, announceBuf.size, packet.address, AudioConfig.DISCOVERY_PORT)
                                    socket.send(replyPacket1)
                                    if (packet.port != AudioConfig.DISCOVERY_PORT) {
                                        val replyPacket2 = DatagramPacket(announceBuf, announceBuf.size, packet.address, packet.port)
                                        try { socket.send(replyPacket2) } catch (ignored: Exception) {}
                                    }
                                    activePublishedStream?.let { pubStream ->
                                        val streamBuf = buildStreamAnnouncePacket(pubStream)
                                        val replyStream = DatagramPacket(streamBuf, streamBuf.size, packet.address, packet.port)
                                        try { socket.send(replyStream) } catch (ignored: Exception) {}
                                    }
                                    Log.d(TAG, "Sent discovery announce reply to ${packet.address}")
                                }
                                HatPacket.TYPE_STREAM_ANNOUNCE -> {
                                    if (header.payloadLength > 0) {
                                        val rawPayload = String(data, HatPacket.HEADER_SIZE, header.payloadLength, Charsets.UTF_8).trim()
                                        val stream = PublishedStream.fromJson(rawPayload)
                                        if (stream != null) {
                                            val senderIp = packet.address.hostAddress ?: stream.endpoint.host
                                            val effectiveHost = if (stream.endpoint.host.isBlank() || stream.endpoint.host == "0.0.0.0") {
                                                senderIp
                                            } else {
                                                stream.endpoint.host
                                            }
                                            addDiscoveredStream(stream.copy(endpoint = stream.endpoint.copy(host = effectiveHost)))
                                        }
                                    }
                                }
                            }
                        }
                    } catch (ignored: Exception) {
                        // SocketTimeoutException expected
                    }

                    // Periodically re-announce every 3 seconds
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastAnnounceTime >= 3000) {
                        sendAnnouncement(socket)
                        activePublishedStream?.let { pubStream ->
                            sendStreamAnnouncement(socket, pubStream)
                        }
                        lastAnnounceTime = now
                        pruneStaleStreams()
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
        val modelName = getLocalDeviceName()
        val p2pName = WifiDirectManager.thisDeviceName
        val effectiveName = if (!p2pName.isNullOrEmpty()) p2pName else modelName
        val isP2p = WifiDirectManager.isGroupCreated.value
        val p2pSsid = WifiDirectManager.networkSsid.value
        val p2pPass = WifiDirectManager.networkPassphrase.value
        val p2pGoIp = WifiDirectManager.groupOwnerIp.value ?: WifiDirectManager.DEFAULT_GO_IP
        val p2pMac = WifiDirectManager.thisDeviceAddress

        val json = org.json.JSONObject().apply {
            put("name", effectiveName)
            put("model", modelName)
            if (p2pMac != null) put("p2pMac", p2pMac)
            if (isP2p && !p2pSsid.isNullOrEmpty()) {
                put("p2p", true)
                put("ssid", p2pSsid)
                put("pass", if (!p2pPass.isNullOrEmpty()) p2pPass else WifiDirectManager.P2P_DEFAULT_PASSPHRASE)
                put("goIp", p2pGoIp)
            }
        }

        val payloadString = json.toString()
        val nameBytes = payloadString.toByteArray(Charsets.UTF_8).take(220).toByteArray()
        val announceBuf = ByteArray(HatPacket.HEADER_SIZE + nameBytes.size)
        HatPacket.writeHeader(
            buffer = announceBuf,
            offset = 0,
            header = HatPacket.Header(
                packetType = HatPacket.TYPE_DISCOVERY_ANNOUNCE,
                volumeOrCaps = AudioCapabilities.getLocalPlaybackCapabilitiesMask().toByte(),
                flags = if (isP2p) HatPacket.FLAG_P2P_ACTIVE else HatPacket.FLAG_NONE,
                payloadLength = nameBytes.size
            )
        )
        System.arraycopy(nameBytes, 0, announceBuf, HatPacket.HEADER_SIZE, nameBytes.size)
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

            activePublishedStream?.let { pubStream ->
                sendStreamAnnouncement(socket, pubStream)
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

        val buffer = ByteArray(HatPacket.HEADER_SIZE)
        HatPacket.writeHeader(
            buffer = buffer,
            offset = 0,
            header = HatPacket.Header(
                packetType = HatPacket.TYPE_DISCOVERY_PROBE,
                payloadLength = 0
            )
        )

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
        val existingIndex = current.indexOfFirst {
            (it.p2pMac != null && device.p2pMac != null && it.p2pMac.equals(device.p2pMac, ignoreCase = true)) ||
            (it.ip == device.ip) ||
            (it.name.equals(device.name, ignoreCase = true) && !it.name.equals("Audio Receiver", ignoreCase = true)) ||
            (it.modelName != null && device.modelName != null && it.modelName.equals(device.modelName, ignoreCase = true))
        }

        if (existingIndex >= 0) {
            val existing = current[existingIndex]
            val bestIp = if (existing.ip.startsWith("192.168.49.") && !device.ip.startsWith("192.168.49.")) {
                device.ip
            } else if (!existing.ip.startsWith("192.168.49.")) {
                existing.ip
            } else {
                device.ip
            }

            val updated = existing.copy(
                name = if (device.name != "Audio Receiver") device.name else existing.name,
                ip = bestIp,
                port = device.port,
                capabilitiesMask = if (device.capabilitiesMask != 0) device.capabilitiesMask else existing.capabilitiesMask,
                isP2pActive = device.isP2pActive || existing.isP2pActive,
                p2pSsid = device.p2pSsid ?: existing.p2pSsid,
                p2pPassphrase = device.p2pPassphrase ?: existing.p2pPassphrase,
                p2pGoIp = device.p2pGoIp ?: existing.p2pGoIp,
                p2pMac = device.p2pMac ?: existing.p2pMac,
                modelName = device.modelName ?: existing.modelName,
                lastSeenMs = SystemClock.elapsedRealtime()
            )
            current[existingIndex] = updated
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

    fun buildStreamAnnouncePacket(stream: PublishedStream): ByteArray {
        val jsonStr = stream.toJson()
        val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8).take(1000).toByteArray()
        val announceBuf = ByteArray(HatPacket.HEADER_SIZE + jsonBytes.size)
        HatPacket.writeHeader(
            buffer = announceBuf,
            offset = 0,
            header = HatPacket.Header(
                packetType = HatPacket.TYPE_STREAM_ANNOUNCE,
                codec = stream.codec.wireCode,
                profile = stream.transportProfile.latencyTarget.wireCode,
                sampleRateCode = stream.audioFormat.sampleRate.wireCode,
                bitDepth = stream.audioFormat.bitDepth.wireCode,
                channels = stream.audioFormat.channelLayout.wireCode,
                volumeOrCaps = 0,
                flags = if (WifiDirectManager.isGroupCreated.value) HatPacket.FLAG_P2P_ACTIVE else HatPacket.FLAG_NONE,
                payloadLength = jsonBytes.size
            )
        )
        System.arraycopy(jsonBytes, 0, announceBuf, HatPacket.HEADER_SIZE, jsonBytes.size)
        return announceBuf
    }

    fun sendStreamAnnouncement(socket: DatagramSocket, stream: PublishedStream? = null) {
        val targetStream = stream ?: activePublishedStream ?: return
        try {
            val announceBuf = buildStreamAnnouncePacket(targetStream)
            val targets = mutableSetOf<String>()
            targets.addAll(NetworkUtils.getAllBroadcastAddresses())
            for (localIp in NetworkUtils.getAllLocalIpAddresses()) {
                val parts = localIp.split(".")
                if (parts.size == 4 && parts[3] != "1") {
                    targets.add("${parts[0]}.${parts[1]}.${parts[2]}.1")
                }
            }
            targets.addAll(NetworkUtils.getArpClientIps())
            targets.add("255.255.255.255")

            for (targetIp in targets) {
                try {
                    val bcastPacket = DatagramPacket(announceBuf, announceBuf.size, InetAddress.getByName(targetIp), AudioConfig.DISCOVERY_PORT)
                    socket.send(bcastPacket)
                } catch (ignored: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed sending stream announcements: ${e.message}")
        }
    }

    @Synchronized
    fun addDiscoveredStream(stream: PublishedStream) {
        val current = _discoveredStreams.value.toMutableList()
        val existingIndex = current.indexOfFirst {
            it.stream.id == stream.id || (it.stream.endpoint.host == stream.endpoint.host && it.stream.endpoint.port == stream.endpoint.port)
        }
        val now = SystemClock.elapsedRealtime()
        if (existingIndex >= 0) {
            current[existingIndex] = DiscoveredStream(stream, now)
        } else {
            current.add(DiscoveredStream(stream, now))
            Log.i(TAG, "Discovered published stream: ${stream.name} (${stream.id}) at ${stream.endpoint}")
        }
        _discoveredStreams.value = current
    }

    @Synchronized
    fun pruneStaleStreams() {
        val now = SystemClock.elapsedRealtime()
        val filtered = _discoveredStreams.value.filter { now - it.lastSeenMs < 16000 }
        if (filtered.size != _discoveredStreams.value.size) {
            _discoveredStreams.value = filtered
        }
    }
}
