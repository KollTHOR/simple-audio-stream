package com.example.audiostreamer

import android.os.Build
import android.os.SystemClock
import android.util.Log
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
    val lastSeenMs: Long = SystemClock.elapsedRealtime()
)

object DiscoveryManager {
    private const val TAG = "DiscoveryManager"
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private var discoveryJob: Job? = null
    @Volatile
    private var activeSocket: DatagramSocket? = null

    private var receiverResponderJob: Job? = null
    @Volatile
    private var receiverResponderSocket: DatagramSocket? = null

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
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 2000
                    bind(InetSocketAddress(0))
                }
                activeSocket = socket

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
                                val payloadLen = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
                                val nameBytesLen = minOf(payloadLen, packet.length - AudioConfig.HEADER_SIZE)
                                val deviceName = if (nameBytesLen > 0) {
                                    String(data, AudioConfig.HEADER_SIZE, nameBytesLen, Charsets.UTF_8).trim()
                                } else {
                                    "Audio Receiver"
                                }

                                val senderIp = packet.address.hostAddress
                                if (senderIp != null) {
                                    addDiscoveredDevice(DiscoveredDevice(deviceName, senderIp, packet.port))
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
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        activeSocket?.close()
        activeSocket = null
    }

    fun triggerScan(scope: CoroutineScope) {
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
    }

    fun startReceiverResponder(scope: CoroutineScope) {
        if (receiverResponderJob?.isActive == true) return

        receiverResponderJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 2000
                    bind(InetSocketAddress(AudioConfig.DEFAULT_PORT))
                }
                receiverResponderSocket = socket
                Log.i(TAG, "Receiver standby responder listening on port ${AudioConfig.DEFAULT_PORT}")

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
                                // Reply with announce directly to the probing transmitter
                                val deviceName = getLocalDeviceName()
                                val nameBytes = deviceName.toByteArray(Charsets.UTF_8).take(64).toByteArray()
                                val replyBuf = ByteArray(AudioConfig.HEADER_SIZE + nameBytes.size)
                                replyBuf[0] = (AudioConfig.MAGIC_HEADER.toInt() shr 8).toByte()
                                replyBuf[1] = (AudioConfig.MAGIC_HEADER.toInt() and 0xFF).toByte()
                                replyBuf[4] = 100
                                replyBuf[5] = AudioConfig.FLAG_DISCOVERY_ANNOUNCE
                                replyBuf[6] = (nameBytes.size shr 8).toByte()
                                replyBuf[7] = (nameBytes.size and 0xFF).toByte()
                                System.arraycopy(nameBytes, 0, replyBuf, AudioConfig.HEADER_SIZE, nameBytes.size)

                                val replyPacket = DatagramPacket(replyBuf, replyBuf.size, packet.address, packet.port)
                                socket.send(replyPacket)
                                Log.d(TAG, "Sent discovery announce reply to ${packet.address}:${packet.port}")
                            }
                        }
                    } catch (ignored: Exception) {
                        // SocketTimeoutException expected
                    }

                    // Periodically re-announce every 3 seconds while in standby
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
    }

    fun sendAnnouncement(socket: DatagramSocket) {
        try {
            val deviceName = getLocalDeviceName()
            val nameBytes = deviceName.toByteArray(Charsets.UTF_8).take(64).toByteArray()
            val announceBuf = ByteArray(AudioConfig.HEADER_SIZE + nameBytes.size)
            announceBuf[0] = (AudioConfig.MAGIC_HEADER.toInt() shr 8).toByte()
            announceBuf[1] = (AudioConfig.MAGIC_HEADER.toInt() and 0xFF).toByte()
            announceBuf[4] = 100
            announceBuf[5] = AudioConfig.FLAG_DISCOVERY_ANNOUNCE
            announceBuf[6] = (nameBytes.size shr 8).toByte()
            announceBuf[7] = (nameBytes.size and 0xFF).toByte()
            System.arraycopy(nameBytes, 0, announceBuf, AudioConfig.HEADER_SIZE, nameBytes.size)

            val targets = NetworkUtils.getAllBroadcastAddresses()
            for (bcastIp in targets) {
                try {
                    val bcastPacket = DatagramPacket(announceBuf, announceBuf.size, InetAddress.getByName(bcastIp), AudioConfig.DEFAULT_PORT)
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

        // 1. Send through default socket
        for (targetIp in targets) {
            try {
                val address = InetAddress.getByName(targetIp)
                val packet = DatagramPacket(buffer, buffer.size, address, AudioConfig.DEFAULT_PORT)
                socket.send(packet)
                Log.d(TAG, "Sent discovery probe to $targetIp:${AudioConfig.DEFAULT_PORT}")
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
                            val packet = DatagramPacket(buffer, buffer.size, address, AudioConfig.DEFAULT_PORT)
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
