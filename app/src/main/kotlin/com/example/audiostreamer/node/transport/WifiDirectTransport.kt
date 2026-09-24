package com.example.audiostreamer.node.transport

import com.example.audiostreamer.AudioConfig
import com.example.audiostreamer.NetworkUtils
import com.example.audiostreamer.WifiDirectManager
import com.example.audiostreamer.AppLogger as Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

/**
 * Wi-Fi Direct (P2P) IP transport backend for HAT.
 */
class WifiDirectTransport(
    private var customSocket: DatagramSocket? = null
) : HatIpTransport {

    companion object {
        private const val TAG = "WifiDirectTransport"
    }

    private val lock = Any()
    @Volatile private var _socket: DatagramSocket? = customSocket
    @Volatile private var _state: TransportState = TransportState.AVAILABLE
    @Volatile private var _localAddress: TransportAddress? = null
    @Volatile private var _remoteAddress: TransportAddress? = null

    private val packetsSent = AtomicLong(0L)
    private val packetsReceived = AtomicLong(0L)
    private val bytesSent = AtomicLong(0L)
    private val bytesReceived = AtomicLong(0L)

    override val type: HatTransportType = HatTransportType.WIFI_DIRECT

    override val isAvailable: Boolean
        get() = WifiDirectManager.isP2pSupported.value && WifiDirectManager.isP2pEnabled.value

    override val state: TransportState
        get() = _state

    override val localAddress: TransportAddress?
        get() = _localAddress

    override val remoteAddress: TransportAddress?
        get() = _remoteAddress

    override val socket: DatagramSocket?
        get() = _socket

    override fun connect(remote: TransportAddress): Result<Unit> = synchronized(lock) {
        try {
            _remoteAddress = remote
            _state = TransportState.CONNECTING

            var sock = _socket
            if (sock == null || sock.isClosed) {
                val targetIp = remote.host
                val matchingLocalIp = NetworkUtils.findMatchingLocalIp(targetIp) ?: NetworkUtils.getLocalIpAddress()
                sock = try {
                    if (matchingLocalIp != null) {
                        DatagramSocket(InetSocketAddress(InetAddress.getByName(matchingLocalIp), 0)).apply {
                            sendBufferSize = AudioConfig.SOCKET_SEND_BUFFER_BYTES
                            broadcast = true
                            try { trafficClass = 0xB8 } catch (ignored: Exception) {}
                        }
                    } else {
                        DatagramSocket().apply {
                            sendBufferSize = AudioConfig.SOCKET_SEND_BUFFER_BYTES
                            broadcast = true
                            try { trafficClass = 0xB8 } catch (ignored: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed binding Wi-Fi Direct socket to interface $matchingLocalIp, fallback: ${e.message}")
                    DatagramSocket().apply {
                        sendBufferSize = AudioConfig.SOCKET_SEND_BUFFER_BYTES
                        broadcast = true
                        try { trafficClass = 0xB8 } catch (ignored: Exception) {}
                    }
                }
                _socket = sock
            }

            val localIp = NetworkUtils.findMatchingLocalIp("192.168.49.1") ?: NetworkUtils.getLocalIpAddress() ?: WifiDirectManager.DEFAULT_GO_IP
            _localAddress = TransportAddress(localIp, sock.localPort)
            _state = TransportState.CONNECTED
            Log.i(TAG, "Wi-Fi Direct transport connected: local=$_localAddress, remote=$_remoteAddress")
            Result.success(Unit)
        } catch (e: Exception) {
            _state = TransportState.FAILED
            Log.e(TAG, "Failed connecting Wi-Fi Direct transport: ${e.message}", e)
            Result.failure(e)
        }
    }

    override fun listen(port: Int): Result<Unit> = synchronized(lock) {
        try {
            _state = TransportState.CONNECTING
            var sock = _socket
            if (sock == null || sock.isClosed) {
                sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    receiveBufferSize = AudioConfig.SOCKET_RECEIVE_BUFFER_BYTES
                    try { trafficClass = 0xB8 } catch (ignored: Exception) {}
                    bind(InetSocketAddress(port))
                }
                _socket = sock
            }
            val localIp = WifiDirectManager.groupOwnerIp.value ?: NetworkUtils.findMatchingLocalIp("192.168.49.1") ?: WifiDirectManager.DEFAULT_GO_IP
            _localAddress = TransportAddress(localIp, port)
            _state = TransportState.LISTENING
            Log.i(TAG, "Wi-Fi Direct transport listening on $_localAddress")
            Result.success(Unit)
        } catch (e: Exception) {
            _state = TransportState.FAILED
            Log.e(TAG, "Failed listening on Wi-Fi Direct transport: ${e.message}", e)
            Result.failure(e)
        }
    }

    override fun send(packet: DatagramPacket) {
        val s = _socket ?: throw IllegalStateException("Wi-Fi Direct transport socket not active")
        s.send(packet)
        packetsSent.incrementAndGet()
        bytesSent.addAndGet(packet.length.toLong())
    }

    override fun receive(packet: DatagramPacket) {
        val s = _socket ?: throw IllegalStateException("Wi-Fi Direct transport socket not active")
        s.receive(packet)
        packetsReceived.incrementAndGet()
        bytesReceived.addAndGet(packet.length.toLong())
    }

    override fun broadcast(packet: DatagramPacket) {
        val s = _socket ?: throw IllegalStateException("Wi-Fi Direct transport socket not active")
        val originalAddr = packet.address
        try {
            packet.address = InetAddress.getByName("192.168.49.255")
            s.send(packet)
            packetsSent.incrementAndGet()
            bytesSent.addAndGet(packet.length.toLong())
        } finally {
            packet.address = originalAddr
        }
    }

    override fun close() = synchronized(lock) {
        try {
            _socket?.close()
        } catch (ignored: Exception) {}
        _socket = null
        _state = TransportState.CLOSED
        Log.i(TAG, "Wi-Fi Direct transport closed")
    }

    override fun getDiagnostics(): Map<String, Any?> = linkedMapOf(
        "type" to type.name,
        "isAudioTransport" to type.isAudioTransport,
        "isAvailable" to isAvailable,
        "state" to state.name,
        "localAddress" to (_localAddress?.toString() ?: "None"),
        "remoteAddress" to (_remoteAddress?.toString() ?: "None"),
        "isGroupCreated" to WifiDirectManager.isGroupCreated.value,
        "isConnected" to WifiDirectManager.isConnected.value,
        "groupOwnerIp" to (WifiDirectManager.groupOwnerIp.value ?: "None"),
        "networkSsid" to (WifiDirectManager.networkSsid.value ?: "None"),
        "discoveredPeersCount" to WifiDirectManager.discoveredPeers.value.size,
        "packetsSent" to packetsSent.get(),
        "packetsReceived" to packetsReceived.get(),
        "bytesSent" to bytesSent.get(),
        "bytesReceived" to bytesReceived.get()
    )
}
