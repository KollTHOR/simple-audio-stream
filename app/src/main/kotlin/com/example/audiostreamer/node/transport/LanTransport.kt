package com.example.audiostreamer.node.transport

import com.example.audiostreamer.AudioConfig
import com.example.audiostreamer.NetworkUtils
import com.example.audiostreamer.AppLogger as Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

/**
 * Normal Wi-Fi LAN / Ethernet IP transport backend for HAT.
 */
class LanTransport(
    private var customSocket: DatagramSocket? = null
) : HatIpTransport {

    companion object {
        private const val TAG = "LanTransport"
    }

    private val lock = Any()
    @Volatile private var _socket: DatagramSocket? = customSocket
    @Volatile private var _state: TransportState = if (customSocket != null && !customSocket!!.isClosed) TransportState.AVAILABLE else TransportState.AVAILABLE
    @Volatile private var _localAddress: TransportAddress? = null
    @Volatile private var _remoteAddress: TransportAddress? = null

    private val packetsSent = AtomicLong(0L)
    private val packetsReceived = AtomicLong(0L)
    private val bytesSent = AtomicLong(0L)
    private val bytesReceived = AtomicLong(0L)

    override val type: HatTransportType = HatTransportType.LAN

    override val isAvailable: Boolean
        get() = NetworkUtils.isLanAvailable()

    override val state: TransportState
        get() = _state

    override val localAddress: TransportAddress?
        get() = _localAddress

    override val remoteAddress: TransportAddress?
        get() = _remoteAddress

    override val socket: DatagramSocket?
        get() = _socket

    override fun connect(remote: TransportAddress): Result<Unit> = synchronized(lock) {
        if (!NetworkUtils.isLanAvailable()) {
            _state = TransportState.FAILED
            val err = IllegalStateException("LAN transport unavailable: no active Wi-Fi or Ethernet network")
            Log.e(TAG, err.message ?: "")
            return Result.failure(err)
        }
        try {
            _remoteAddress = remote
            _state = TransportState.CONNECTING

            var sock = _socket
            if (sock == null || sock.isClosed) {
                val matchingLocalIp = NetworkUtils.findMatchingLocalIp(remote.host)
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
                    Log.w(TAG, "Failed binding socket to interface IP $matchingLocalIp, fallback to unbound: ${e.message}")
                    DatagramSocket().apply {
                        sendBufferSize = AudioConfig.SOCKET_SEND_BUFFER_BYTES
                        broadcast = true
                        try { trafficClass = 0xB8 } catch (ignored: Exception) {}
                    }
                }
                _socket = sock
            }

            val localIp = NetworkUtils.getLocalIpAddress() ?: "0.0.0.0"
            _localAddress = TransportAddress(localIp, sock.localPort)
            _state = TransportState.CONNECTED
            Log.i(TAG, "LAN transport connected: local=$_localAddress, remote=$_remoteAddress")
            Result.success(Unit)
        } catch (e: Exception) {
            _state = TransportState.FAILED
            Log.e(TAG, "Failed connecting LAN transport: ${e.message}", e)
            Result.failure(e)
        }
    }

    override fun listen(port: Int): Result<Unit> = synchronized(lock) {
        if (!NetworkUtils.isLanAvailable()) {
            _state = TransportState.FAILED
            val err = IllegalStateException("LAN transport unavailable: no active Wi-Fi or Ethernet network")
            Log.e(TAG, err.message ?: "")
            return Result.failure(err)
        }
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
            val localIp = NetworkUtils.getLocalIpAddress() ?: "0.0.0.0"
            _localAddress = TransportAddress(localIp, port)
            _state = TransportState.LISTENING
            Log.i(TAG, "LAN transport listening on $_localAddress")
            Result.success(Unit)
        } catch (e: Exception) {
            _state = TransportState.FAILED
            Log.e(TAG, "Failed listening on LAN transport: ${e.message}", e)
            Result.failure(e)
        }
    }

    override fun send(packet: DatagramPacket) {
        val s = _socket ?: throw IllegalStateException("LAN transport socket not active")
        s.send(packet)
        packetsSent.incrementAndGet()
        bytesSent.addAndGet(packet.length.toLong())
    }

    override fun receive(packet: DatagramPacket) {
        val s = _socket ?: throw IllegalStateException("LAN transport socket not active")
        s.receive(packet)
        packetsReceived.incrementAndGet()
        bytesReceived.addAndGet(packet.length.toLong())
    }

    override fun broadcast(packet: DatagramPacket) {
        if (!NetworkUtils.isLanAvailable()) {
            Log.w(TAG, "Cannot broadcast on LAN: LAN unavailable")
            return
        }
        val s = _socket ?: throw IllegalStateException("LAN transport socket not active")
        val originalAddr = packet.address
        try {
            val bcastIp = NetworkUtils.getSuggestedBroadcastIp()
            if (bcastIp.isEmpty()) {
                Log.w(TAG, "No LAN broadcast IP available")
                return
            }
            packet.address = InetAddress.getByName(bcastIp)
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
        Log.i(TAG, "LAN transport closed")
    }

    override fun getDiagnostics(): Map<String, Any?> = linkedMapOf(
        "type" to type.name,
        "isAudioTransport" to type.isAudioTransport,
        "isAvailable" to isAvailable,
        "state" to state.name,
        "localAddress" to (_localAddress?.toString() ?: "None"),
        "remoteAddress" to (_remoteAddress?.toString() ?: "None"),
        "packetsSent" to packetsSent.get(),
        "packetsReceived" to packetsReceived.get(),
        "bytesSent" to bytesSent.get(),
        "bytesReceived" to bytesReceived.get(),
        "isBound" to (_socket?.isBound == true),
        "isClosed" to (_socket?.isClosed == true)
    )
}
