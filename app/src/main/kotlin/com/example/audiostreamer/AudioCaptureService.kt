package com.example.audiostreamer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.example.audiostreamer.AppLogger as Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class AudioCaptureService : Service() {

    data class ClientEndpoint(val address: InetAddress, val port: Int) {
        override fun toString(): String = "${address.hostAddress}:$port"
    }

    companion object {
        private const val TAG = "AudioCaptureService"
        const val ACTION_START = "com.example.audiostreamer.ACTION_START_CAPTURE"
        const val ACTION_STOP = "com.example.audiostreamer.ACTION_STOP_CAPTURE"
        const val ACTION_SET_VOLUME = "com.example.audiostreamer.ACTION_SET_VOLUME"
        const val ACTION_STEP_VOLUME = "com.example.audiostreamer.ACTION_STEP_VOLUME"
        const val ACTION_ADD_CLIENT = "com.example.audiostreamer.ACTION_ADD_CLIENT"
        const val ACTION_REMOVE_CLIENT = "com.example.audiostreamer.ACTION_REMOVE_CLIENT"
        const val ACTION_SET_RECEIVER_VOLUME = "com.example.audiostreamer.ACTION_SET_RECEIVER_VOLUME"

        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val EXTRA_TARGET_IP = "EXTRA_TARGET_IP"
        const val EXTRA_TARGET_PORT = "EXTRA_TARGET_PORT"
        const val EXTRA_VOLUME_PERCENT = "EXTRA_VOLUME_PERCENT"
        const val EXTRA_VOLUME_DELTA = "EXTRA_VOLUME_DELTA"
        const val EXTRA_RECEIVER_IP = "EXTRA_RECEIVER_IP"
        const val EXTRA_RECEIVER_NODE_ID = "EXTRA_RECEIVER_NODE_ID"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "AudioCaptureChannel"

        val isRunning = AtomicBoolean(false)
        val remoteVolumePercent = AtomicInteger(100)
        val receiverVolumes = ConcurrentHashMap<String, Int>()
        val currentStreamGeneration = java.util.concurrent.atomic.AtomicLong(0L)
        val masterEqualizer = Equalizer12Band()
        @Volatile var currentTrackMetadata: HatPacket.MediaMetadataPayload? = null

        fun getReceiverVolume(ip: String, nodeId: String? = null): Int {
            val master = remoteVolumePercent.get()
            val custom = if (nodeId != null && receiverVolumes.containsKey(nodeId)) {
                receiverVolumes[nodeId]
            } else {
                receiverVolumes[ip]
            }
            return (custom ?: master).coerceIn(0, master)
        }

        fun setReceiverVolume(ip: String?, nodeId: String?, volume: Int) {
            val master = remoteVolumePercent.get()
            val clamped = volume.coerceIn(0, master)
            if (!ip.isNullOrBlank()) receiverVolumes[ip] = clamped
            if (!nodeId.isNullOrBlank()) receiverVolumes[nodeId] = clamped
        }

        @Volatile
        var currentInstance: AudioCaptureService? = null
            private set

        fun isOpusEncoderAvailable(): Boolean {
            return try {
                val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                codecList.codecInfos.any { info ->
                    info.isEncoder && info.supportedTypes.any { it.equals(AudioConfig.OPUS_MIME_TYPE, ignoreCase = true) }
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var udpSocket: DatagramSocket? = null
    @Volatile private var activeTransport: com.example.audiostreamer.node.transport.HatIpTransport? = null
    val profileTransactionManager = StreamProfileTransactionManager(currentStreamGeneration)
    val activeNegotiatedConfig: NegotiatedStreamConfig?
        get() = profileTransactionManager.activeTransmitterConfig
    private val socketSendLock = Any()
    @Volatile private var streamThread: Thread? = null
    private var controlListenerThread: Thread? = null
    private var previousPhoneVolume: Int? = null
    private var currentTargetIp = "192.168.43.255"
    private var currentTargetPort = AudioConfig.DEFAULT_PORT
    private val clientRegistry = ConcurrentHashMap<ClientEndpoint, Long>()
    private val configuredEndpoints = ConcurrentHashMap.newKeySet<ClientEndpoint>()
    private val clientCapabilities = ConcurrentHashMap<ClientEndpoint, Int>()
    private val clientNodeInfo = ConcurrentHashMap<ClientEndpoint, com.example.audiostreamer.node.NodeInfo>()
    @Volatile private var lastClientPruneTime = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var aacEncoder: AacEncoder? = null
    private var opusEncoder: OpusEncoder? = null
    private var volumeReceiver: BroadcastReceiver? = null
    private var volumeObserver: ContentObserver? = null
    private var activeCaptureSampleRate = AudioConfig.SAMPLE_RATE_48000
    @Volatile private var lastLiveAdaptTime = 0L

    // --- HAT runtime diagnostics ------------------------------------------------------------------
    internal val diagPacketsGenerated = java.util.concurrent.atomic.AtomicLong(0L)
    internal val diagTxPackets = java.util.concurrent.atomic.AtomicLong(0L)
    internal val diagTxBytes = java.util.concurrent.atomic.AtomicLong(0L)
    internal val diagTxErrors = java.util.concurrent.atomic.AtomicLong(0L)
    internal val diagCaptureFrames = java.util.concurrent.atomic.AtomicLong(0L)
    internal val diagCaptureFramesDropped = java.util.concurrent.atomic.AtomicLong(0L)
    internal val diagCapturePcmBytes = java.util.concurrent.atomic.AtomicLong(0L)
    internal val diagCaptureReadCalls = java.util.concurrent.atomic.AtomicLong(0L)
    internal val diagCaptureReadErrors = java.util.concurrent.atomic.AtomicLong(0L)
    internal val consecutiveCaptureErrors = java.util.concurrent.atomic.AtomicLong(0L)
    private val clientDiag = ConcurrentHashMap<ClientEndpoint, TxReceiverStats>()
    private var diagStatsLastNs = 0L
    private var diagStatsLastPackets = 0L
    private var diagStatsLastBytes = 0L
    @Volatile private var txFirstPacketGeneration: Long = -1L

    /** Per-receiver transmit counters sampled once per diagnostics interval. */
    private class TxReceiverStats(val label: String) {
        val packets = java.util.concurrent.atomic.AtomicLong(0L)
        val bytes = java.util.concurrent.atomic.AtomicLong(0L)
        val sendErrors = java.util.concurrent.atomic.AtomicLong(0L)
        val unreachable = java.util.concurrent.atomic.AtomicLong(0L)
        val timeouts = java.util.concurrent.atomic.AtomicLong(0L)
        val otherErrors = java.util.concurrent.atomic.AtomicLong(0L)
        var lastPackets = 0L
        var lastBytes = 0L
        var lastSampleNs = 0L
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection revoked or stopped by system")
            stopStreaming()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Received ACTION_STOP")
                stopStreaming()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SET_VOLUME -> {
                val newVol = intent.getIntExtra(EXTRA_VOLUME_PERCENT, remoteVolumePercent.get())
                updateRemoteVolume(newVol)
                return START_NOT_STICKY
            }
            ACTION_SET_RECEIVER_VOLUME -> {
                val ip = intent.getStringExtra(EXTRA_RECEIVER_IP) ?: intent.getStringExtra(EXTRA_TARGET_IP)
                val nodeId = intent.getStringExtra(EXTRA_RECEIVER_NODE_ID)
                val master = remoteVolumePercent.get()
                val newVol = intent.getIntExtra(EXTRA_VOLUME_PERCENT, master).coerceIn(0, master)
                setReceiverVolume(ip, nodeId, newVol)
                Log.d(TAG, "Set receiver volume: ip=$ip, nodeId=$nodeId, vol=$newVol% (master ceiling=$master%)")
                publishConnectedReceivers()
                if (!ip.isNullOrBlank()) {
                    val targetEp = clientRegistry.keys.firstOrNull { it.address.hostAddress == ip }
                    if (targetEp != null) {
                        sendControlPacket(newVol, targetEp)
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_STEP_VOLUME -> {
                val delta = intent.getIntExtra(EXTRA_VOLUME_DELTA, 0)
                val newVol = (remoteVolumePercent.get() + delta).coerceIn(0, 100)
                updateRemoteVolume(newVol)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                }
                val targetIp = intent.getStringExtra(EXTRA_TARGET_IP) ?: "192.168.43.255"
                val targetPort = intent.getIntExtra(EXTRA_TARGET_PORT, AudioConfig.DEFAULT_PORT)

                if (resultCode == 0 || resultData == null) {
                    Log.e(TAG, "Missing valid MediaProjection credentials")
                    stopSelf()
                    return START_NOT_STICKY
                }

                currentTargetIp = targetIp
                currentTargetPort = targetPort

                silenceTransmitterSpeakers()
                registerVolumeClampGuard()
                startServiceForeground()
                startStreaming(resultCode, resultData, targetIp, targetPort)
            }
            AudioConfig.ACTION_RESTART_CAPTURE -> {
                Log.i(TAG, "Received ACTION_RESTART_CAPTURE. Live reinitializing capture pipeline.")
                restartStreaming()
            }
            ACTION_ADD_CLIENT -> {
                val ip = intent.getStringExtra(EXTRA_TARGET_IP)
                val port = intent.getIntExtra(EXTRA_TARGET_PORT, AudioConfig.DEFAULT_PORT)
                if (!ip.isNullOrBlank()) {
                    addClientDynamically(ip, port)
                }
                return START_NOT_STICKY
            }
            ACTION_REMOVE_CLIENT -> {
                val ip = intent.getStringExtra(EXTRA_TARGET_IP)
                val nodeId = intent.getStringExtra(EXTRA_RECEIVER_NODE_ID)
                if (!ip.isNullOrBlank() || !nodeId.isNullOrBlank()) {
                    removeClientDynamically(ip, nodeId)
                }
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun addClientDynamically(ip: String, port: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val addr = InetAddress.getByName(ip)
                val ep = ClientEndpoint(addr, port)
                clientRegistry[ep] = SystemClock.elapsedRealtime()
                val knownCaps = DiscoveryManager.lastDiscoveredReceiverCapabilities
                if (knownCaps != 0) {
                    clientCapabilities[ep] = knownCaps
                }
                Log.i(TAG, "Dynamically added multi-unicast client: $ep")
                val currentConfig = activeNegotiatedConfig
                if (currentConfig != null) {
                    val targetVol = getReceiverVolume(ip)
                    sendStreamAnnouncement(currentConfig, targetVol, ep)
                }
                publishConnectedReceivers()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to dynamically add client $ip:$port - ${e.message}")
            }
        }
    }

    private fun removeClientDynamically(ip: String?, nodeId: String? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val matching = clientRegistry.keys.filter { ep ->
                    (ip != null && ep.address.hostAddress == ip) ||
                    (nodeId != null && clientNodeInfo[ep]?.id == nodeId)
                }
                for (ep in matching) {
                    try {
                        val discBuf = ByteArray(AudioConfig.HEADER_SIZE)
                        val header = HatPacket.Header(
                            packetType = HatPacket.TYPE_DISCONNECT,
                            payloadLength = 0
                        )
                        HatPacket.writeHeader(discBuf, 0, header)
                        val discPkt = DatagramPacket(discBuf, discBuf.size, ep.address, ep.port)
                        repeat(3) {
                            udpSocket?.send(discPkt)
                        }
                    } catch (ignored: Exception) {}
                    clientRegistry.remove(ep)
                    clientCapabilities.remove(ep)
                    clientDiag.remove(ep)
                    configuredEndpoints.remove(ep)
                    val removedNode = clientNodeInfo.remove(ep)
                    val destId = removedNode?.id ?: "${com.example.audiostreamer.node.NodeIdentity.ID_PREFIX}ep-${(ep.address.hostAddress ?: "").replace(".", "-")}"
                    com.example.audiostreamer.node.HatMultiStreamManager.removeDestination(destId, "dynamic_removal")
                    com.example.audiostreamer.node.HatLinkManager.closeLinkByRemoteAddress(ep.address.hostAddress ?: "")
                    Log.i(TAG, "Dynamically removed client: $ep (nodeId=$nodeId)")
                }
                publishConnectedReceivers()
                if (clientRegistry.isEmpty()) {
                    pauseSystemMediaPlayback()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove client $ip ($nodeId) - ${e.message}")
            }
        }
    }

    private fun publishConnectedReceivers() {
        val list = clientRegistry.keys.map { ep ->
            val hostIp = ep.address.hostAddress ?: ""
            val friendlyName = DiscoveryManager.getDeviceNameForIp(hostIp) ?: hostIp
            val stats = clientDiag[ep]
            val isP2p = hostIp.startsWith("192.168.49.")
            val knownCaps = clientCapabilities[ep] ?: 0
            val config = activeNegotiatedConfig
            val knownNode = clientNodeInfo[ep]
            if (hostIp.isNotEmpty()) {
                com.example.audiostreamer.node.LinkAdapters.registerOutboundStream(
                    remoteAddress = hostIp,
                    remotePort = ep.port,
                    remoteCaps = knownCaps,
                    generation = currentStreamGeneration.get(),
                    codec = config?.codec ?: AudioCodec.PCM,
                    format = config?.audioFormat ?: AudioFormatConfig(),
                    remoteNode = knownNode
                )
            }
            val recVol = getReceiverVolume(hostIp, knownNode?.id)
            ConnectedDevice(
                ip = hostIp,
                port = ep.port,
                name = knownNode?.name ?: friendlyName,
                isDirectP2p = isP2p,
                packetsTransferred = stats?.packets?.get() ?: 0L,
                nodeId = knownNode?.id ?: "${com.example.audiostreamer.node.NodeIdentity.ID_PREFIX}ep-${hostIp.replace(".", "-")}",
                volumePercent = recVol,
                isMuted = recVol == 0
            )
        }
        val links = com.example.audiostreamer.node.HatLinkManager.activeLinks.value
        val streams = com.example.audiostreamer.node.HatLinkManager.activeStreams.value
        StreamState.update {
            it.copy(
                connectedReceivers = list,
                activeReceiversCount = list.size,
                activeLinks = links,
                activeStreams = streams
            )
        }
    }

    private fun updateRemoteVolume(newVolume: Int) {
        val clamped = newVolume.coerceIn(0, 100)
        remoteVolumePercent.set(clamped)
        val clampedReceivers = mutableListOf<ClientEndpoint>()
        for (entry in receiverVolumes.entries) {
            if (entry.value > clamped) {
                entry.setValue(clamped)
            }
        }
        for (ep in clientRegistry.keys) {
            val host = ep.address.hostAddress ?: ""
            val knownNode = clientNodeInfo[ep]
            val currentRecVol = getReceiverVolume(host, knownNode?.id)
            if (currentRecVol > clamped) {
                setReceiverVolume(host, knownNode?.id, clamped)
                clampedReceivers.add(ep)
            }
        }
        StreamState.update { it.copy(remoteVolumePercent = clamped) }
        publishConnectedReceivers()
        Log.d(TAG, "Remote volume updated: $clamped% (clamped ${clampedReceivers.size} receivers)")

        // If streaming is actively running, streamThread transmits destVol in the next 5ms audio packet.
        // If streaming is idle, broadcast master volume or notify clamped receivers.
        if (streamThread == null || !streamThread!!.isAlive) {
            sendControlPacket(clamped)
        } else {
            for (ep in clampedReceivers) {
                sendControlPacket(clamped, ep)
            }
        }
    }

    private fun parseTargetAddresses(targetIpString: String): List<InetAddress> {
        val list = targetIpString.split(",", ";", " ", "\n", "\t")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { ip ->
                try {
                    InetAddress.getByName(ip)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed resolving target IP $ip: ${e.message}")
                    null
                }
            }
            .distinct()
        return if (list.isNotEmpty()) list else {
            try {
                listOf(InetAddress.getByName("192.168.43.255"))
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    private fun broadcastDatagram(socket: DatagramSocket, packet: DatagramPacket) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastClientPruneTime > 2500L) {
            lastClientPruneTime = now
            val iter = clientRegistry.entries.iterator()
            var anyPruned = false
            while (iter.hasNext()) {
                val entry = iter.next()
                if (now - entry.value > 10_000L) {
                    Log.i(TAG, "Receiver removed: ${entry.key} (reason: stale_timeout, idleMs=${now - entry.value})")
                    HatDiagnostics.info("RECEIVER_STALE", mapOf("receiver" to entry.key.toString(), "idleMs" to (now - entry.value)))
                    clientDiag.remove(entry.key)
                    val nodeInfo = clientNodeInfo.remove(entry.key)
                    val destId = nodeInfo?.id ?: "${com.example.audiostreamer.node.NodeIdentity.ID_PREFIX}ep-${(entry.key.address.hostAddress ?: "").replace(".", "-")}"
                    com.example.audiostreamer.node.HatMultiStreamManager.removeDestination(destId, "stale_timeout")
                    com.example.audiostreamer.node.HatLinkManager.closeLinkByRemoteAddress(entry.key.address.hostAddress ?: "")
                    iter.remove()
                    anyPruned = true
                }
            }
            if (anyPruned) {
                publishConnectedReceivers()
                if (clientRegistry.isEmpty()) {
                    pauseSystemMediaPlayback()
                }
            }
        }

        // One produced packet per broadcast: counts FEC parity/heartbeat datagrams too, unlike per-socket sends.
        diagPacketsGenerated.incrementAndGet()
        val pendingFirstTx = txFirstPacketGeneration
        if (pendingFirstTx >= 0L) {
            txFirstPacketGeneration = -1L
            HatDiagnostics.lifecycle("GENERATION_FIRST_TX", pendingFirstTx)
        }
        val sendStartNs = SystemClock.elapsedRealtimeNanos()
        try {
            synchronized(socketSendLock) {
                val targets = if (clientRegistry.isNotEmpty()) clientRegistry.keys else configuredEndpoints
                for (client in targets) {
                    packet.address = client.address
                    packet.port = client.port
                    val stats = clientDiag.getOrPut(client) { TxReceiverStats(client.toString()) }
                    val knownNode = clientNodeInfo[client]
                    val destId = knownNode?.id ?: "${com.example.audiostreamer.node.NodeIdentity.ID_PREFIX}ep-${(client.address.hostAddress ?: "").replace(".", "-")}"
                    val clientIp = client.address.hostAddress ?: ""
                    val destVol = getReceiverVolume(clientIp, knownNode?.id)
                    if (packet.length >= HatPacket.HEADER_SIZE) {
                        packet.data[packet.offset + 18] = destVol.toByte()
                    }
                    try {
                        socket.send(packet)
                        stats.packets.incrementAndGet()
                        stats.bytes.addAndGet(packet.length.toLong())
                        diagTxPackets.incrementAndGet()
                        diagTxBytes.addAndGet(packet.length.toLong())
                        com.example.audiostreamer.node.HatMultiStreamManager.recordFanOut(
                            destinationNodeId = destId,
                            packetBytes = packet.length,
                            isSuccess = true
                        )
                    } catch (e: Exception) {
                        stats.sendErrors.incrementAndGet()
                        diagTxErrors.incrementAndGet()
                        com.example.audiostreamer.node.HatMultiStreamManager.recordFanOut(
                            destinationNodeId = destId,
                            packetBytes = packet.length,
                            isSuccess = false,
                            error = e
                        )
                        when {
                            e is java.net.SocketTimeoutException -> stats.timeouts.incrementAndGet()
                            e.message?.contains("unreachable", ignoreCase = true) == true -> stats.unreachable.incrementAndGet()
                            else -> stats.otherErrors.incrementAndGet()
                        }
                        // Counted for every failure, but only reported (with structured context) on the first
                        // and then sparsely: a persistently unreachable receiver must not produce per-packet spam.
                        val sendErrorCount = HatDiagnostics.increment("tx_send_errors")
                        if (sendErrorCount == 1L || sendErrorCount % 100L == 0L) {
                            HatDiagnostics.warn(
                                "RECEIVER_SEND_FAILURE",
                                mapOf(
                                    "receiver" to client.toString(),
                                    "error" to (e.message ?: e.javaClass.simpleName),
                                    "count" to sendErrorCount
                                )
                            )
                        }
                        Log.w(TAG, "Failed sending datagram to $client: ${e.message}")
                    }
                }
            }
        } finally {
            HatDiagnostics.recordTime("send", SystemClock.elapsedRealtimeNanos() - sendStartNs)
        }
    }

    private fun sendControlPacket(volume: Int, targetEndpoint: ClientEndpoint? = null) {
        val socket = udpSocket ?: return
        Thread({
            try {
                val config = activeNegotiatedConfig
                val buffer = ByteArray(HatPacket.HEADER_SIZE)
                val prefs = getSharedPreferences("stream_prefs", Context.MODE_PRIVATE)
                val profileStr = prefs.getString(AudioConfig.PREF_KEY_PROFILE, AudioConfig.PROFILE_AUTO) ?: AudioConfig.PROFILE_AUTO
                val profileCode = HatPacket.profileStringToCode(profileStr)

                val header = if (config != null) {
                    config.createHeader(
                        packetType = HatPacket.TYPE_CONTROL,
                        sequenceNumber = 0,
                        payloadLength = 0,
                        timestamp = config.generation,
                        volumeOrCaps = volume.coerceIn(0, 100).toByte(),
                        generation = config.generation
                    )
                } else {
                    HatPacket.Header(
                        packetType = HatPacket.TYPE_CONTROL,
                        timestamp = currentStreamGeneration.get(),
                        profile = profileCode,
                        volumeOrCaps = volume.coerceIn(0, 100).toByte(),
                        payloadLength = 0,
                        generation = currentStreamGeneration.get()
                    )
                }
                HatPacket.writeHeader(buffer, 0, header)

                val packet = DatagramPacket(buffer, buffer.size)
                if (targetEndpoint != null) {
                    packet.address = targetEndpoint.address
                    packet.port = targetEndpoint.port
                    socket.send(packet)
                } else {
                    broadcastDatagram(socket, packet)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending volume control packet", e)
            }
        }, "AudioCaptureControlSender").apply {
            isDaemon = true
            start()
        }
    }

    private fun sendStreamAnnouncement(config: NegotiatedStreamConfig, volume: Int, targetEndpoint: ClientEndpoint? = null) {
        val socket = udpSocket ?: return
        try {
            val localNode = com.example.audiostreamer.node.LocalNodeManager.getLocalNode()
            val exchangePayload = com.example.audiostreamer.node.NodeCapabilityExchange.fromNode(localNode).toByteArray()
            val buffer = ByteArray(HatPacket.HEADER_SIZE + exchangePayload.size)
            val header = config.createHeader(
                packetType = HatPacket.TYPE_CONTROL,
                sequenceNumber = 0,
                payloadLength = exchangePayload.size,
                timestamp = config.generation,
                volumeOrCaps = volume.coerceIn(0, 100).toByte(),
                generation = config.generation
            )
            HatPacket.writeHeader(buffer, 0, header)
            System.arraycopy(exchangePayload, 0, buffer, HatPacket.HEADER_SIZE, exchangePayload.size)
            val packet = DatagramPacket(buffer, buffer.size)
            if (targetEndpoint != null) {
                packet.address = targetEndpoint.address
                packet.port = targetEndpoint.port
                synchronized(socketSendLock) {
                    try {
                        socket.send(packet)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed sending announcement to $targetEndpoint: ${e.message}")
                    }
                }
            } else {
                broadcastDatagram(socket, packet)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed sending stream announcement: ${e.message}")
        }
    }

    private fun startServiceForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val pendingActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Simple Audio Stream")
            .setContentText("Transmitting audio")
            .setSmallIcon(R.drawable.ic_transmitter)
            .setContentIntent(pendingActivityIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Capture Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Captures system audio and streams via UDP"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startStreaming(
        resultCode: Int,
        resultData: Intent,
        targetIp: String,
        targetPort: Int
    ) {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Streaming session already running")
            return
        }

        acquireLocks()
        registerVolumeClampGuard()
        startDiagnosticsSession()

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            Log.e(TAG, "Unable to obtain MediaProjection")
            isRunning.set(false)
            releaseLocks()
            stopSelf()
            return
        }
        this.mediaProjection = projection
        projection.registerCallback(projectionCallback, null)

        ensureSocketAndControlListener(targetIp, targetPort)

        val prefs = getSharedPreferences("stream_prefs", Context.MODE_PRIVATE)
        masterEqualizer.loadFromPreferences(prefs)
        val profileStr = prefs.getString(AudioConfig.PREF_KEY_PROFILE, AudioConfig.PROFILE_AUTO) ?: AudioConfig.PROFILE_AUTO
        val initialTarget = LatencyTarget.fromString(profileStr)

        performProfileChange(initialTarget)
        activeNegotiatedConfig?.sampleRateHz?.let { rate ->
            masterEqualizer.sampleRate = rate
        }

        // Wire into MediaSessionTracker — the single source of truth for media metadata.
        // Immediately snapshot current state if tracking is already running.
        val initialState = MediaSessionTracker.currentState
        if (initialState != null) {
            currentTrackMetadata = HatPacket.MediaMetadataPayload(
                isPlaying = initialState.isPlaying,
                title = initialState.title,
                artist = initialState.artist,
                album = initialState.album,
                mediaStateSequence = initialState.sequence,
                packageName = initialState.packageName
            )
        }
        MediaSessionTracker.onStateChangedListener = { state ->
            if (state != null) {
                // New or updated session — build payload carrying sequence + packageName
                currentTrackMetadata = HatPacket.MediaMetadataPayload(
                    isPlaying = state.isPlaying,
                    title = state.title,
                    artist = state.artist,
                    album = state.album,
                    mediaStateSequence = state.sequence,
                    packageName = state.packageName
                )
                Log.i(TAG, "MEDIA_METADATA_TX seq=${state.sequence} package=${state.packageName} title=\"${state.title}\"")
            } else {
                // No active session — transmit an explicit clear packet so receivers reset
                val clearSeq = MediaSessionTracker.currentState?.sequence?.plus(1) ?: 0L
                currentTrackMetadata = HatPacket.MediaMetadataPayload(
                    isPlaying = false,
                    title = "",
                    artist = "",
                    album = "",
                    mediaStateSequence = clearSeq,
                    packageName = ""
                )
                Log.i(TAG, "MEDIA_METADATA_TX seq=$clearSeq [CLEAR — no active session]")
            }
            sendMediaMetadata()
        }

        // AudioPlaybackDetector: fallback ONLY when Notification Access is not granted.
        // It provides audio-format info / app name, NOT song metadata — per spec §13.
        val rawRatePref = prefs.getString(AudioConfig.PREF_KEY_SAMPLE_RATE, AudioConfig.SAMPLE_RATE_AUTO) ?: AudioConfig.SAMPLE_RATE_AUTO
        val rawBitPref = prefs.getString(AudioConfig.PREF_KEY_BIT_DEPTH, AudioConfig.BIT_DEPTH_AUTO) ?: AudioConfig.BIT_DEPTH_AUTO
        if (profileStr == AudioConfig.PROFILE_AUTO || (profileStr == AudioConfig.PROFILE_MUSIC && (rawRatePref == AudioConfig.SAMPLE_RATE_AUTO || rawBitPref == AudioConfig.BIT_DEPTH_AUTO))) {
            AudioPlaybackDetector.startMonitoring(this) { newFormat ->
                Log.d(TAG, "AudioPlaybackDetector active media format: ${newFormat.description}")
                if (!MediaNotificationListenerService.isServiceConnected) {
                    val appTitle = if (newFormat.appName != "None") newFormat.appName else "Simple Audio Stream"
                    currentTrackMetadata = HatPacket.MediaMetadataPayload(
                        isPlaying = newFormat.isPlaying,
                        title = appTitle,
                        artist = "Transmitter",
                        album = newFormat.description
                    )
                    sendMediaMetadata()
                }
            }
        }
    }

    private fun restartStreaming() {
        val proj = mediaProjection
        if (proj == null) {
            Log.w(TAG, "Cannot restart streaming: mediaProjection is null")
            return
        }

        val prefs = getSharedPreferences("stream_prefs", Context.MODE_PRIVATE)
        val requestedProfileStr = prefs.getString(AudioConfig.PREF_KEY_PROFILE, AudioConfig.PROFILE_AUTO) ?: AudioConfig.PROFILE_AUTO
        val requestedTarget = LatencyTarget.fromString(requestedProfileStr)

        performProfileChange(requestedTarget)
    }

    fun performProfileChange(requestedTarget: LatencyTarget): ProfileChangeResult {
        val proj = mediaProjection
        if (proj == null) {
            Log.w(TAG, "Cannot perform profile change: mediaProjection is null")
            return ProfileChangeResult.IgnoredSameProfile(
                activeProfile = requestedTarget,
                generation = currentStreamGeneration.get()
            )
        }

        val prefs = getSharedPreferences("stream_prefs", Context.MODE_PRIVATE)
        val isLowLatency = (requestedTarget == LatencyTarget.LOW_LATENCY)
        val isMusic = (requestedTarget == LatencyTarget.RELIABLE)
        val isAuto = (requestedTarget == LatencyTarget.BALANCED)

        val isOpusSupported = isLowLatency && isOpusEncoderAvailable()
        val isOpusActive = isLowLatency && isOpusSupported
        val isAacActive = isLowLatency && !isOpusSupported
        val isCompressedActive = isLowLatency

        val rawRatePref = prefs.getString(AudioConfig.PREF_KEY_SAMPLE_RATE, AudioConfig.SAMPLE_RATE_AUTO) ?: AudioConfig.SAMPLE_RATE_AUTO
        val rawBitPref = prefs.getString(AudioConfig.PREF_KEY_BIT_DEPTH, AudioConfig.BIT_DEPTH_AUTO) ?: AudioConfig.BIT_DEPTH_AUTO

        val detectedMedia = AudioPlaybackDetector.getActiveMediaFormat(this)
        val targetRate: Int
        val target24Bit: Boolean

        if (isLowLatency) {
            // Low Latency: Strictly locked to 16-bit / 48 kHz Opus (RFC 6716 native 48kHz framing)
            targetRate = AudioConfig.SAMPLE_RATE_48000
            target24Bit = false
        } else if (isAuto) {
            // Auto Adaptive Mode: 24-bit / 48 kHz stereo is the primary Android operating point.
            val hwOutputRate = AudioPlaybackDetector.getHardwareOutputRate(this)
            targetRate = if (detectedMedia.sampleRate == AudioConfig.SAMPLE_RATE_44100 && hwOutputRate == AudioConfig.SAMPLE_RATE_44100) {
                AudioConfig.SAMPLE_RATE_44100
            } else {
                AudioConfig.SAMPLE_RATE_48000
            }
            target24Bit = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) && (rawBitPref != AudioConfig.BIT_DEPTH_16)
        } else {
            // Unlocked Music Mode: 24-bit / 48 kHz stereo primary operating point on Android.
            val requestedRate = if (rawRatePref == AudioConfig.SAMPLE_RATE_AUTO) {
                if (detectedMedia.sampleRate == AudioConfig.SAMPLE_RATE_44100) AudioConfig.SAMPLE_RATE_44100 else AudioConfig.SAMPLE_RATE_48000
            } else {
                rawRatePref.toIntOrNull() ?: AudioConfig.SAMPLE_RATE_48000
            }
            targetRate = when (requestedRate) {
                AudioConfig.SAMPLE_RATE_44100 -> AudioConfig.SAMPLE_RATE_44100
                else -> AudioConfig.SAMPLE_RATE_48000
            }
            target24Bit = when (rawBitPref) {
                AudioConfig.BIT_DEPTH_16 -> false
                AudioConfig.BIT_DEPTH_24 -> (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                else -> (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            }
        }

        val txCaps = AudioCapabilities.getLocalCaptureCapabilitiesMask()
        val rxCapsList = clientCapabilities.values.filter { it != 0 }.toList()
        val rxCaps = when {
            rxCapsList.isNotEmpty() -> StreamNegotiator.resolveMutuallySupportedCapabilities(rxCapsList)
            DiscoveryManager.lastDiscoveredReceiverCapabilities != 0 -> DiscoveryManager.lastDiscoveredReceiverCapabilities
            else -> prefs.getInt(AudioConfig.PREF_KEY_RECEIVER_CAPS, 0)
        }

        val fecEnabled = prefs.getBoolean(AudioConfig.PREF_KEY_FEC_ENABLED, true)

        ensureSocketAndControlListener(currentTargetIp, currentTargetPort)

        val result = profileTransactionManager.changeProfile(
            targetProfile = requestedTarget,
            preferredCodec = when {
                isOpusActive -> AudioCodec.OPUS
                isAacActive -> AudioCodec.AAC
                else -> AudioCodec.PCM
            },
            preferredSampleRateHz = targetRate,
            preferred24Bit = target24Bit,
            txCapabilitiesMask = txCaps,
            rxCapabilitiesMask = rxCaps,
            rxCapabilitiesList = rxCapsList,
            fecEnabled = fecEnabled,
            isOpusEncoderAvailable = isOpusSupported,
            onStopTransmission = {
                stopProducerSynchronously()
            },
            onPublishAnnouncement = { config ->
                publishAnnouncementBurst(config)
            },
            onReconfigureCapture = { config ->
                reconfigureCapturePipeline(config, proj)
            },
            onResumeTransmission = { config ->
                resumeTransmissionPipeline(config)
            }
        )

        when (result) {
            is ProfileChangeResult.InitializationFailed -> {
                // Nothing was committed or announced. The old producer has been stopped and no new producer
                // exists: the service is in a clean stopped state, so the UI must not claim an active stream.
                Log.e(
                    TAG,
                    "Profile change to ${requestedTarget.name} aborted: capture pipeline initialization failed. " +
                        "Previous generation ${result.previousConfig?.generation ?: 0L} remains authoritative; nothing announced."
                )
                StreamState.update {
                    it.copy(
                        isActive = false,
                        statusDetail = "Profile change failed - capture unavailable",
                        packetsPerSec = 0,
                        bytesPerSec = 0
                    )
                }
            }
            is ProfileChangeResult.AnnouncementFailed -> {
                // The generation was consumed but never became usable: the pipeline has been released and the
                // transmitter stopped, so the UI must not claim an active stream either.
                Log.e(
                    TAG,
                    "Profile change to ${requestedTarget.name} aborted after commit: generation " +
                        "${result.abandonedGeneration} stays consumed and is not transmitting."
                )
                StreamState.update {
                    it.copy(
                        isActive = false,
                        statusDetail = "Profile change failed - announcement error",
                        packetsPerSec = 0,
                        bytesPerSec = 0
                    )
                }
            }
            else -> Unit
        }

        return result
    }

    private fun stopProducerSynchronously() {
        val sThread = streamThread
        streamThread = null

        // Release capture resources before joining the worker: stopping/releasing the AudioRecord unblocks
        // a pending blocking read() so the join below cannot stall on a silent capture session.
        releaseActiveCaptureResources()

        sThread?.interrupt()
        try {
            sThread?.join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Releases and clears the capture pipeline resources currently owned by the service. */
    private fun releaseActiveCaptureResources() {
        val record = audioRecord
        val opus = opusEncoder
        val aac = aacEncoder
        audioRecord = null
        opusEncoder = null
        aacEncoder = null
        releaseCaptureResources(record, opus, aac)
    }

    /** Releases any combination of capture resources. Every argument may be null. */
    private fun releaseCaptureResources(record: AudioRecord?, opus: OpusEncoder?, aac: AacEncoder?) {
        try {
            if (record != null) {
                if (record.state == AudioRecord.STATE_INITIALIZED &&
                    record.recordingState == AudioRecord.RECORDSTATE_RECORDING
                ) {
                    record.stop()
                }
                record.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }

        try {
            opus?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing Opus encoder: ${e.message}")
        }

        try {
            aac?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AAC encoder: ${e.message}")
        }
    }

    private fun publishAnnouncementBurst(config: NegotiatedStreamConfig) {
        repeat(3) {
            sendStreamAnnouncement(config, remoteVolumePercent.get())
            try { Thread.sleep(10L) } catch (ignored: Exception) {}
        }
    }

    private fun ensureSocketAndControlListener(targetIp: String, targetPort: Int) {
        if (udpSocket != null && !udpSocket!!.isClosed) {
            return
        }
        val targetAddresses = parseTargetAddresses(targetIp)
        clientRegistry.clear()
        clientCapabilities.clear()
        configuredEndpoints.clear()
        for (addr in targetAddresses) {
            val ep = ClientEndpoint(addr, targetPort)
            configuredEndpoints.add(ep)
            val knownCaps = DiscoveryManager.lastDiscoveredReceiverCapabilities
            if (knownCaps != 0) {
                clientCapabilities[ep] = knownCaps
            }
        }
        val firstTargetIp = targetAddresses.firstOrNull()?.hostAddress ?: targetIp
        val transport = com.example.audiostreamer.node.transport.HatTransportRegistry.selectBestAudioTransport(firstTargetIp)
        transport.connect(com.example.audiostreamer.node.transport.TransportAddress(firstTargetIp, targetPort))
        val socket: DatagramSocket = transport.socket ?: run {
            val matchingLocalIp = NetworkUtils.findMatchingLocalIp(firstTargetIp)
            try {
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
                Log.w(TAG, "Failed binding socket to interface IP $matchingLocalIp, falling back to unbound socket: ${e.message}")
                DatagramSocket().apply {
                    sendBufferSize = AudioConfig.SOCKET_SEND_BUFFER_BYTES
                    broadcast = true
                    try { trafficClass = 0xB8 } catch (ignored: Exception) {}
                }
            }
        }
        try { socket.trafficClass = 0xB8 } catch (ignored: Exception) {}
        udpSocket = socket
        activeTransport = transport

        val listenerSocket = socket
        controlListenerThread = Thread({
            val recvBuf = ByteArray(4096)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                try {
                    recvPacket.length = recvBuf.size
                    listenerSocket.receive(recvPacket)
                    val endpoint = ClientEndpoint(recvPacket.address, recvPacket.port)

                    if (recvPacket.length < HatPacket.HEADER_SIZE) {
                        Log.d(TAG, "Rejected packet from $endpoint: too short (${recvPacket.length} bytes < ${HatPacket.HEADER_SIZE})")
                        continue
                    }

                    val header = HatPacket.parseHeader(recvBuf, 0, recvPacket.length)
                    if (header == null) {
                        Log.d(TAG, "Rejected packet from $endpoint: invalid HAT header or magic mismatch")
                        continue
                    }

                    val typeName = HatPacket.describePacketType(header.packetType)
                    Log.d(TAG, "Received control packet type $typeName (${header.packetType}) from $endpoint")

                    val byteVal = header.volumeOrCaps.toInt() and 0xFF

                    when (header.packetType) {
                        HatPacket.TYPE_RECEIVER_HEARTBEAT -> {
                            val isNew = !clientRegistry.containsKey(endpoint)
                            val currentConfig = activeNegotiatedConfig
                            val payloadLen = header.payloadLength
                            val rxExchange = if (payloadLen > 0 && recvPacket.length >= HatPacket.HEADER_SIZE + payloadLen) {
                                com.example.audiostreamer.node.NodeCapabilityExchange.parseOrNull(recvBuf, HatPacket.HEADER_SIZE, payloadLen)
                            } else null

                            val localNode = com.example.audiostreamer.node.LocalNodeManager.getLocalNode()
                            val hostIp = endpoint.address.hostAddress ?: ""
                            val remoteNodeInfo = rxExchange?.toNodeInfo(role = com.example.audiostreamer.node.StreamRole.RECEIVER)
                                ?: com.example.audiostreamer.node.NodeInfo(
                                    identity = com.example.audiostreamer.node.NodeIdentity(
                                        "${com.example.audiostreamer.node.NodeIdentity.ID_PREFIX}ep-${hostIp.replace(".", "-")}",
                                        DiscoveryManager.getDeviceNameForIp(hostIp) ?: "Receiver $hostIp"
                                    ),
                                    capabilities = com.example.audiostreamer.node.NodeCapabilities.fromCapabilitiesMask(byteVal),
                                    activeRole = com.example.audiostreamer.node.StreamRole.RECEIVER
                                )

                            val negotiated = com.example.audiostreamer.node.NodeCapabilityNegotiator.negotiate(localNode, remoteNodeInfo)
                            clientNodeInfo[endpoint] = remoteNodeInfo
                            HatDiagnostics.setLastNegotiatedCapabilities(negotiated)
                            StreamState.update { it.copy(lastNegotiatedCapabilities = negotiated) }
                            com.example.audiostreamer.node.HatLinkManager.recordNegotiatedCapabilitiesForRemote(hostIp, negotiated)
                            com.example.audiostreamer.node.HatLinkManager.recordNegotiatedCapabilitiesForRemote(remoteNodeInfo.id, negotiated)

                            if (isNew && currentConfig != null) {
                                val isLegacyIncompatible = !currentConfig.canReceiverConsume(byteVal)
                                val isIncompatible = !negotiated.isCompatible && isLegacyIncompatible
                                if (isIncompatible) {
                                    val capsDesc = AudioCapabilities.describeCapabilitiesMask(byteVal)
                                    Log.w(TAG, "Rejected receiver $endpoint: incompatible ($capsDesc / ${negotiated.details})")
                                    try {
                                        val declineHeader = currentConfig.createHeader(
                                            packetType = HatPacket.TYPE_DISCONNECT,
                                            sequenceNumber = 0,
                                            payloadLength = 0,
                                            timestamp = 0L
                                        )
                                        val declineBuf = ByteArray(AudioConfig.HEADER_SIZE)
                                        HatPacket.writeHeader(declineBuf, 0, declineHeader)
                                        val declinePkt = DatagramPacket(declineBuf, AudioConfig.HEADER_SIZE, endpoint.address, endpoint.port)
                                        listenerSocket.send(declinePkt)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to send disconnect to incompatible receiver $endpoint: ${e.message}")
                                    }
                                } else {
                                    if (byteVal != 0) {
                                        clientCapabilities[endpoint] = byteVal
                                        val prefs = getSharedPreferences("stream_prefs", Context.MODE_PRIVATE)
                                        prefs.edit().putInt(AudioConfig.PREF_KEY_RECEIVER_CAPS, byteVal).apply()
                                    }
                                    clientRegistry[endpoint] = SystemClock.elapsedRealtime()
                                    val capsDesc = if (byteVal != 0) AudioCapabilities.describeCapabilitiesMask(byteVal) else "default"
                                    Log.i(TAG, "Receiver registered: $endpoint (reason: valid TYPE_RECEIVER_HEARTBEAT, caps: $capsDesc, summary: ${negotiated.summary()})")
                                    HatDiagnostics.info(
                                        "RECEIVER_JOIN",
                                        mapOf(
                                            "receiver" to endpoint.toString(),
                                            "nodeId" to remoteNodeInfo.id,
                                            "negotiated" to negotiated.summary(),
                                            "capabilities" to byteVal,
                                            "capabilityDesc" to capsDesc,
                                            "generation" to currentConfig.generation,
                                            "activeReceivers" to clientRegistry.size
                                        )
                                    )
                                    val targetVol = getReceiverVolume(endpoint.address.hostAddress ?: "", remoteNodeInfo.id)
                                    sendStreamAnnouncement(currentConfig, targetVol, endpoint)
                                    publishConnectedReceivers()
                                    sendMediaMetadata(endpoint)
                                }
                            } else {
                                if (byteVal != 0) {
                                    clientCapabilities[endpoint] = byteVal
                                    val prefs = getSharedPreferences("stream_prefs", Context.MODE_PRIVATE)
                                    prefs.edit().putInt(AudioConfig.PREF_KEY_RECEIVER_CAPS, byteVal).apply()
                                }
                                clientRegistry[endpoint] = SystemClock.elapsedRealtime()
                                Log.d(TAG, "Receiver heartbeat refreshed: $endpoint")
                                com.example.audiostreamer.node.HatLinkManager.recordLinkActivity(
                                    endpoint.address.hostAddress ?: "",
                                    endpoint.port,
                                    1L,
                                    0L,
                                    isRx = true
                                )
                                if (isNew) {
                                    val capsDesc = if (byteVal != 0) AudioCapabilities.describeCapabilitiesMask(byteVal) else "default"
                                    Log.i(TAG, "Receiver registered: $endpoint (pre-config, caps: $capsDesc)")
                                    publishConnectedReceivers()
                                    sendMediaMetadata(endpoint)
                                }
                            }
                        }
                        HatPacket.TYPE_REVERSE_VOLUME_SYNC -> {
                            val incomingVol = byteVal.coerceIn(0, 100)
                            val clientIp = endpoint.address.hostAddress ?: ""
                            val knownNode = clientNodeInfo[endpoint]
                            val masterVol = remoteVolumePercent.get()
                            val effectiveVol = minOf(incomingVol, masterVol)
                            setReceiverVolume(clientIp, knownNode?.id, effectiveVol)
                            Log.i(TAG, "Received reverse volume sync from $endpoint: raw=$incomingVol%, effective=$effectiveVol% (master ceiling=$masterVol%)")
                            publishConnectedReceivers()
                            if (clientRegistry.containsKey(endpoint)) {
                                clientRegistry[endpoint] = SystemClock.elapsedRealtime()
                                Log.d(TAG, "Receiver heartbeat refreshed: $endpoint (via volume sync)")
                            } else {
                                Log.d(TAG, "Reverse volume sync from non-admitted endpoint $endpoint - receiver volume saved, receiver NOT admitted")
                            }
                            if (incomingVol > masterVol) {
                                sendControlPacket(effectiveVol, endpoint)
                            }
                        }
                        HatPacket.TYPE_DISCONNECT -> {
                            if (clientRegistry.containsKey(endpoint)) {
                                Log.i(TAG, "Receiver removed: $endpoint (reason: received TYPE_DISCONNECT)")
                                clientRegistry.remove(endpoint)
                                clientCapabilities.remove(endpoint)
                                clientDiag.remove(endpoint)
                                val removedNode = clientNodeInfo.remove(endpoint)
                                val destId = removedNode?.id ?: "${com.example.audiostreamer.node.NodeIdentity.ID_PREFIX}ep-${(endpoint.address.hostAddress ?: "").replace(".", "-")}"
                                com.example.audiostreamer.node.HatMultiStreamManager.removeDestination(destId, "client_disconnect")
                                com.example.audiostreamer.node.HatLinkManager.closeLinkByRemoteAddress(endpoint.address.hostAddress ?: "")
                                publishConnectedReceivers()
                                HatDiagnostics.info(
                                    "RECEIVER_LEAVE",
                                    mapOf(
                                        "receiver" to endpoint.toString(),
                                        "reason" to "client_disconnect",
                                        "activeReceivers" to clientRegistry.size
                                    )
                                )
                                if (clientRegistry.isEmpty()) {
                                    pauseSystemMediaPlayback()
                                }
                            } else {
                                Log.d(TAG, "Rejected packet from $endpoint: TYPE_DISCONNECT from non-connected client")
                            }
                        }
                        HatPacket.TYPE_MEDIA_CONTROL -> {
                            val cmd = header.volumeOrCaps
                            Log.i(TAG, "Received TYPE_MEDIA_CONTROL from $endpoint: cmd=$cmd")
                            handleMediaControlCommand(cmd)
                        }
                        else -> {
                            Log.d(TAG, "Rejected packet from $endpoint: type $typeName (${header.packetType}) is not a receiver admission control packet")
                        }
                    }
                } catch (e: SocketException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Error in control listener loop: ${e.message}")
                }
            }
            Log.d(TAG, "Control listener thread exited")
        }, "AudioCaptureControlListener").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * PROFILE-CHANGE INITIALIZATION STEP.
     *
     * Runs BEFORE the new generation/config is committed or announced. Initializes the COMPLETE capture
     * pipeline for [config] (AudioRecord + every encoder the config requires), arms capture, and only then
     * publishes the resources onto the service fields.
     *
     * Returns true only when the pipeline is fully initialized AND recording. On any failure every resource
     * created by the attempt is released and the service fields are left untouched/cleared, so the caller can
     * abort the transaction without leaking an AudioRecord, an encoder/MediaCodec or leaving a stale field.
     */
    private fun reconfigureCapturePipeline(
        config: NegotiatedStreamConfig,
        projection: MediaProjection
    ): Boolean {
        // Build the whole pipeline locally first: no service field is touched until it is complete.
        val pipeline = initializeCapturePipeline(config, projection) ?: return false

        this.audioRecord = pipeline.audioRecord
        this.opusEncoder = pipeline.opusEncoder
        this.aacEncoder = pipeline.aacEncoder
        activeCaptureSampleRate = pipeline.audioRecord.sampleRate

        // Arm capture while still inside the initialization phase, so the transaction can only be committed
        // once capture is initialized and recording. Producer startup remains the final activation step.
        try {
            pipeline.audioRecord.startRecording()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start AudioRecord for ${config.toSummaryString()}", t)
            releaseActiveCaptureResources()
            return false
        }

        if (pipeline.audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.e(TAG, "AudioRecord did not enter RECORDING state for ${config.toSummaryString()}")
            releaseActiveCaptureResources()
            return false
        }

        Log.i(
            TAG,
            "Capture pipeline initialized and recording for ${config.toSummaryString()} at ${activeCaptureSampleRate} Hz"
        )
        silenceTransmitterSpeakers()
        return true
    }

    /**
     * Smallest initialization boundary directly used by the profile-change transaction: creates and
     * validates every resource [config] requires, releasing everything it created on ANY failure so the
     * caller never observes a partially initialized pipeline.
     */
    private fun initializeCapturePipeline(
        config: NegotiatedStreamConfig,
        projection: MediaProjection
    ): CapturePipeline<AudioRecord, OpusEncoder, AacEncoder>? {
        val initializer = CapturePipelineInitializer(
            createAudioRecord = { cfg -> createAudioRecordForConfig(cfg, projection) },
            createOpusEncoder = { cfg -> createOpusEncoderOrNull(cfg) },
            createAacEncoder = { cfg -> createAacEncoderOrNull(cfg) },
            releaseAudioRecord = { rec -> releaseCaptureResources(rec, null, null) },
            releaseOpusEncoder = { enc -> releaseCaptureResources(null, enc, null) },
            releaseAacEncoder = { enc -> releaseCaptureResources(null, null, enc) }
        )
        return initializer.initialize(config)
    }

    /** Creates an Opus encoder, or null when the underlying MediaCodec could not be initialized. */
    private fun createOpusEncoderOrNull(config: NegotiatedStreamConfig): OpusEncoder? {
        val encoder = OpusEncoder(config.sampleRateHz)
        if (encoder.isInitialized) return encoder
        Log.e(TAG, "Opus encoder unavailable; aborting capture pipeline for ${config.toSummaryString()}")
        encoder.release()
        return null
    }

    /** Creates an AAC encoder, or null when the underlying MediaCodec could not be initialized. */
    private fun createAacEncoderOrNull(config: NegotiatedStreamConfig): AacEncoder? {
        val encoder = AacEncoder(config.sampleRateHz)
        if (encoder.isInitialized) return encoder
        Log.e(TAG, "AAC encoder unavailable; aborting capture pipeline for ${config.toSummaryString()}")
        encoder.release()
        return null
    }

    /**
     * Probes and creates an initialized AudioRecord for [config].
     * Candidates that do not reach STATE_INITIALIZED are released here, so this never leaks a half-built
     * AudioRecord nor returns an unusable one.
     */
    @SuppressLint("MissingPermission")
    private fun createAudioRecordForConfig(
        config: NegotiatedStreamConfig,
        projection: MediaProjection
    ): AudioRecord? {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val isCompressedActive = config.isCompressed
        val targetRate = config.sampleRateHz
        val target24Bit = config.is24Bit
        var record: AudioRecord? = null

        val candidateRates = if (isCompressedActive) {
            mutableListOf(AudioConfig.SAMPLE_RATE_48000)
        } else {
            val list = mutableListOf(targetRate)
            if (!list.contains(AudioConfig.SAMPLE_RATE_48000)) list.add(AudioConfig.SAMPLE_RATE_48000)
            if (!list.contains(AudioConfig.SAMPLE_RATE_44100)) list.add(AudioConfig.SAMPLE_RATE_44100)
            list
        }

        if (isCompressedActive) {
            for (rate in candidateRates) {
                val audioFormat16 = AudioFormat.Builder()
                    .setEncoding(AudioConfig.ENCODING)
                    .setSampleRate(rate)
                    .setChannelMask(AudioConfig.CHANNEL_IN_MASK)
                    .build()
                val minBuf16 = AudioRecord.getMinBufferSize(rate, AudioConfig.CHANNEL_IN_MASK, AudioConfig.ENCODING)
                if (minBuf16 > 0) {
                    val bufSize16 = maxOf(minBuf16 * 2, 4096)
                    var candidate: AudioRecord? = null
                    try {
                        candidate = AudioRecord.Builder()
                            .setAudioPlaybackCaptureConfig(captureConfig)
                            .setAudioFormat(audioFormat16)
                            .setBufferSizeInBytes(bufSize16)
                            .build()
                        if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                            record = candidate
                            Log.i(TAG, "Initialized compressed capture AudioRecord at $rate Hz")
                            break
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Probing compressed AudioRecord at $rate Hz failed: ${e.message}")
                    }
                    // Never abandon a candidate that this attempt did not adopt.
                    if (record !== candidate) {
                        try {
                            candidate?.release()
                        } catch (ignored: Exception) {}
                    }
                }
            }
        } else {
            if (target24Bit && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                for (rate in candidateRates) {
                    try {
                        val minBuf24 = AudioRecord.getMinBufferSize(
                            rate,
                            AudioConfig.CHANNEL_IN_MASK,
                            AudioFormat.ENCODING_PCM_24BIT_PACKED
                        )
                        if (minBuf24 > 0) {
                            val bufSize24 = maxOf(minBuf24 * 4, AudioConfig.CAPTURE_BUFFER_BYTES_24BIT)
                            val candidateRecord = AudioRecord.Builder()
                                .setAudioPlaybackCaptureConfig(captureConfig)
                                .setAudioFormat(
                                    AudioFormat.Builder()
                                        .setEncoding(AudioFormat.ENCODING_PCM_24BIT_PACKED)
                                        .setSampleRate(rate)
                                        .setChannelMask(AudioConfig.CHANNEL_IN_MASK)
                                        .build()
                                )
                                .setBufferSizeInBytes(bufSize24)
                                .build()
                            if (candidateRecord.state == AudioRecord.STATE_INITIALIZED) {
                                record = candidateRecord
                                Log.i(TAG, "Probed and initialized 24-bit packed PCM AudioRecord at $rate Hz")
                                break
                            } else {
                                candidateRecord.release()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Probing 24-bit AudioRecord at $rate Hz failed: ${e.message}")
                    }
                }
            }

            if (record == null) {
                for (rate in candidateRates) {
                    try {
                        val minBuf16 = AudioRecord.getMinBufferSize(
                            rate,
                            AudioConfig.CHANNEL_IN_MASK,
                            AudioConfig.ENCODING
                        )
                        if (minBuf16 > 0) {
                            val bufSize16 = maxOf(minBuf16 * 4, AudioConfig.CAPTURE_BUFFER_BYTES_16BIT)
                            val candidateRecord = AudioRecord.Builder()
                                .setAudioPlaybackCaptureConfig(captureConfig)
                                .setAudioFormat(
                                    AudioFormat.Builder()
                                        .setEncoding(AudioConfig.ENCODING)
                                        .setSampleRate(rate)
                                        .setChannelMask(AudioConfig.CHANNEL_IN_MASK)
                                        .build()
                                )
                                .setBufferSizeInBytes(bufSize16)
                                .build()
                            if (candidateRecord.state == AudioRecord.STATE_INITIALIZED) {
                                record = candidateRecord
                                Log.i(TAG, "Probed and initialized 16-bit PCM AudioRecord at $rate Hz")
                                break
                            } else {
                                candidateRecord.release()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Probing 16-bit AudioRecord at $rate Hz failed: ${e.message}")
                    }
                }
            }
        }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed across all probed rates")
            return null
        }
        return record
    }

    /**
     * PROFILE-CHANGE PRODUCER START STEP.
     *
     * Runs after the new generation/config has been committed and announced. The capture pipeline was
     * already fully initialized (and is recording) by [reconfigureCapturePipeline]; this only starts the
     * producer that consumes it.
     *
     * Returns true only when the producer is actually running. Every failure mode (missing/unarmed capture,
     * missing socket, worker construction or start failure) either returns false or propagates to the
     * transaction, which converts it into a failed producer start and releases the initialized pipeline.
     */
    private fun resumeTransmissionPipeline(config: NegotiatedStreamConfig): Boolean {
        val record = this.audioRecord ?: run {
            Log.e(TAG, "Cannot resume transmission: audioRecord is null")
            return false
        }
        val socket = this.udpSocket ?: run {
            Log.e(TAG, "Cannot resume transmission: udpSocket is null")
            return false
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.e(TAG, "Cannot resume transmission: AudioRecord is not recording for ${config.toSummaryString()}")
            return false
        }

        val isOpusActive = config.codec == AudioCodec.OPUS
        val isAacActive = config.codec == AudioCodec.AAC
        val isCompressedActive = config.isCompressed
        val is24BitActive = config.is24Bit
        val captureSampleRate = config.sampleRateHz
        val activePayloadSize = config.getNominalPcmPayloadSize()
        val opusEnc = this.opusEncoder
        val aacEnc = this.aacEncoder
        val isFecEnabled = config.transportProfile.fec.enabled
        val negotiatedStreamConfig = config

        com.example.audiostreamer.node.HatMultiStreamManager.configureSharedEncoder(
            codec = config.codec,
            format = config.audioFormat,
            generation = config.generation
        )

        val initialProfileDisplayName = when {
            isOpusActive -> "Low Latency (Opus 320k)"
            isAacActive -> "Low Latency (AAC 192k)"
            config.transportProfile.latencyTarget == LatencyTarget.BALANCED -> if (is24BitActive) "Auto Adaptive (24-bit, ${captureSampleRate / 1000}kHz)" else "Auto Adaptive (${captureSampleRate / 1000}kHz)"
            is24BitActive -> "Studio 24-bit Music (${captureSampleRate / 1000}kHz)"
            else -> "Music (${captureSampleRate / 1000}kHz)"
        }

        val initialBitDepth = if (isCompressedActive) 16 else if (is24BitActive) 24 else 16
        val initialBitrate = when {
            isOpusActive -> 320
            isAacActive -> 192
            captureSampleRate == AudioConfig.SAMPLE_RATE_44100 -> if (initialBitDepth == 24) 2117 else 1411
            else -> if (initialBitDepth == 24) 2304 else 1536
        }

        val initialEndpointCount = clientRegistry.size
        val initialEndpointLabel = if (initialEndpointCount > 1) {
            "$initialEndpointCount receivers"
        } else if (initialEndpointCount == 1) {
            val single = clientRegistry.keys.first()
            "${single.address.hostAddress}:${single.port}"
        } else {
            "$currentTargetIp:$currentTargetPort"
        }
        val initialStatus = if (initialEndpointCount > 1) {
            "Multi-Unicast ($initialEndpointCount receivers)"
        } else {
            "Transmitting to $initialEndpointLabel"
        }

        val initialNegotiatedFormat = if (isCompressedActive) {
            "${if (isOpusActive) "Opus" else "AAC"} • ${initialBitrate} kbps • ${captureSampleRate / 1000.0} kHz"
        } else {
            "${captureSampleRate / 1000.0} kHz • ${initialBitDepth}-bit Stereo PCM"
        }

        StreamState.update {
            it.copy(
                isActive = true,
                isTransmitter = true,
                remoteEndpoint = initialEndpointLabel,
                statusDetail = initialStatus,
                streamProfileName = initialProfileDisplayName,
                sampleRate = captureSampleRate,
                bitDepth = initialBitDepth,
                bitrateKbps = initialBitrate,
                isSilenceSuppressed = false,
                activeReceiversCount = initialEndpointCount,
                sourceCapabilityDesc = "Android HAL",
                receiverCapabilityDesc = "Active",
                negotiatedFormatDesc = initialNegotiatedFormat
            )
        }
        publishConnectedReceivers()

        Log.i(TAG, "Starting producer for generation ${config.generation} at $captureSampleRate Hz (Profile: $initialProfileDisplayName, Payload: $activePayloadSize bytes, FEC: $isFecEnabled)")

        val worker = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

            val sendBuffer = ByteArray(AudioConfig.HEADER_SIZE + AudioConfig.MAX_PACKET_SIZE)
            val packet = DatagramPacket(sendBuffer, sendBuffer.size)

            val fecEncoder = FecEncoder(AudioConfig.FEC_BLOCK_SIZE)
            val fecDatagramPacket = DatagramPacket(ByteArray(AudioConfig.HEADER_SIZE + AudioConfig.MAX_PACKET_SIZE), 0)

            var sequence = 0
            var streamTimelineFrames = 0L
            var totalPackets = 0L
            var totalBytes = 0L
            var intervalPackets = 0
            var intervalBytes = 0
            var lastStatsTime = SystemClock.elapsedRealtime()
            var lastVuTime = SystemClock.elapsedRealtime()
            val audioMeter = AudioLevelMeter(AudioConfig.CHANNELS)
            var silentPacketsCount = 0
            var isSilenceSuppressed = false
            var lastHeartbeatTime = 0L
            var smoothPps = 0f
            var smoothBps = 0f
            var lastLatencyLogTime = 0L
            var lastReadDurationNs = 0L
            var lastEncodeDurationNs = 0L

            val pcmReadBuffer = ByteArray(4096)
            val rawPcmBuffer = ByteArray(AudioConfig.MAX_PACKET_SIZE)
            val losslessCodec = LosslessAudioCodec(1024)

            try {
                while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                    if (isCompressedActive && (opusEnc != null || aacEnc != null)) {
                        val targetReadBytes = if (isOpusActive) {
                            3840 // 20ms Opus frame @ 48kHz (960 stereo samples)
                        } else if (isAacActive) {
                            4096 // ~21.3ms AAC-LC frame (1024 samples)
                        } else {
                            activePayloadSize
                        }
                        val tRead0 = SystemClock.elapsedRealtimeNanos()
                        val pcmBytesRead = record.read(pcmReadBuffer, 0, targetReadBytes, AudioRecord.READ_BLOCKING)
                        lastReadDurationNs = SystemClock.elapsedRealtimeNanos() - tRead0
                        diagCaptureReadCalls.incrementAndGet()
                        HatDiagnostics.recordTime("captureRead", lastReadDurationNs)
                        if (pcmBytesRead > 0) {
                            consecutiveCaptureErrors.set(0L)
                            diagCaptureFrames.addAndGet((pcmBytesRead / 4).toLong())
                            diagCapturePcmBytes.addAndGet(pcmBytesRead.toLong())
                            masterEqualizer.process16BitStereo(pcmReadBuffer, 0, pcmBytesRead)
                            val metrics = audioMeter.analyze(pcmReadBuffer, 0, pcmBytesRead, is24Bit = false)
                            val chunkPeak = metrics.peak

                            val isChunkSilent = (chunkPeak <= AudioConfig.SILENCE_AMPLITUDE_THRESHOLD_16BIT)
                            if (isChunkSilent) {
                                silentPacketsCount++
                                if (silentPacketsCount >= 25) isSilenceSuppressed = true
                            } else {
                                silentPacketsCount = 0
                                isSilenceSuppressed = false
                            }

                            val now = SystemClock.elapsedRealtime()
                            if (now - lastVuTime >= 200L) {
                                val intervalPeak = audioMeter.getAndResetIntervalPeak()
                                val peakPercent = AudioLevelMeter.calculatePeakPercent(intervalPeak, is24Bit = false)
                                StreamState.update {
                                    it.copy(audioPeakPercent = if (isSilenceSuppressed) 0 else peakPercent)
                                }
                                lastVuTime = now
                            }
                            val shouldSendHeartbeat = isSilenceSuppressed && (now - lastHeartbeatTime >= AudioConfig.SILENCE_HEARTBEAT_INTERVAL_MS)

                            val framesInChunk = if (isOpusActive) 960 else if (isAacActive) 1024 else (activePayloadSize / 4)

                            if (!isSilenceSuppressed) {
                                val tEnc0 = SystemClock.elapsedRealtimeNanos()
                                val encodedFrames = if (isOpusActive) {
                                    opusEnc?.encode(pcmReadBuffer, 0, pcmBytesRead) ?: emptyList()
                                } else {
                                    aacEnc?.encode(pcmReadBuffer, 0, pcmBytesRead) ?: emptyList()
                                }
                                lastEncodeDurationNs = SystemClock.elapsedRealtimeNanos() - tEnc0
                                HatDiagnostics.recordTime("encode", lastEncodeDurationNs)
                                for (frame in encodedFrames) {
                                    val frameLen = frame.size
                                    if (frameLen > 0 && frameLen <= AudioConfig.MAX_PACKET_SIZE) {
                                        val currentSeq = sequence
                                        sequence = (sequence + 1) and 0xFFFF
                                        val currentTimestamp = streamTimelineFrames
                                        streamTimelineFrames += framesInChunk

                                        val volByte = remoteVolumePercent.get().coerceIn(0, 100).toByte()

                                        val header = negotiatedStreamConfig.createHeader(
                                            packetType = HatPacket.TYPE_AUDIO,
                                            sequenceNumber = currentSeq,
                                            payloadLength = frameLen,
                                            timestamp = currentTimestamp,
                                            volumeOrCaps = volByte
                                        )
                                        HatPacket.writeHeader(sendBuffer, 0, header)
                                        System.arraycopy(frame, 0, sendBuffer, HatPacket.HEADER_SIZE, frameLen)
                                        packet.length = HatPacket.HEADER_SIZE + frameLen

                                        broadcastDatagram(socket, packet)
                                        totalPackets++
                                        totalBytes += packet.length
                                        intervalPackets++
                                        intervalBytes += packet.length

                                        if (isFecEnabled) {
                                            val parityBytes = fecEncoder.encode(
                                                seq = currentSeq,
                                                timestamp = currentTimestamp,
                                                payload = sendBuffer,
                                                offset = HatPacket.HEADER_SIZE,
                                                len = frameLen,
                                                codec = negotiatedStreamConfig.codec.wireCode,
                                                profile = negotiatedStreamConfig.transportProfile.latencyTarget.wireCode,
                                                sampleRateCode = negotiatedStreamConfig.audioFormat.sampleRate.wireCode,
                                                bitDepth = negotiatedStreamConfig.audioFormat.bitDepth.wireCode,
                                                volume = volByte.toInt() and 0xFF,
                                                generation = negotiatedStreamConfig.generation
                                            )
                                            if (parityBytes != null) {
                                                fecDatagramPacket.setData(parityBytes, 0, parityBytes.size)
                                                broadcastDatagram(socket, fecDatagramPacket)
                                                totalPackets++
                                                intervalPackets++
                                                totalBytes += parityBytes.size
                                                intervalBytes += parityBytes.size
                                            }
                                        }
                                    }
                                }
                            } else {
                                val currentTimestamp = streamTimelineFrames
                                streamTimelineFrames += framesInChunk
                                if (shouldSendHeartbeat) {
                                    fecEncoder.reset()
                                    val currentSeq = sequence
                                    sequence = (sequence + 1) and 0xFFFF
                                    val volByte = remoteVolumePercent.get().coerceIn(0, 100).toByte()

                                    val header = negotiatedStreamConfig.createHeader(
                                        packetType = HatPacket.TYPE_SILENCE_HEARTBEAT,
                                        sequenceNumber = currentSeq,
                                        payloadLength = 0,
                                        timestamp = currentTimestamp,
                                        volumeOrCaps = volByte
                                    )
                                    HatPacket.writeHeader(sendBuffer, 0, header)
                                    packet.length = HatPacket.HEADER_SIZE
                                    broadcastDatagram(socket, packet)
                                    totalPackets++
                                    totalBytes += packet.length
                                    intervalPackets++
                                    intervalBytes += packet.length
                                    lastHeartbeatTime = now
                                }
                            }

                            val nowStats = SystemClock.elapsedRealtime()
                            if (nowStats - lastStatsTime >= 1000L) {
                                val elapsedSec = (nowStats - lastStatsTime) / 1000.0f
                                val currentPps = intervalPackets / elapsedSec
                                val currentBps = intervalBytes / elapsedSec
                                smoothPps = if (smoothPps == 0f) currentPps else (smoothPps * 0.7f + currentPps * 0.3f)
                                smoothBps = if (smoothBps == 0f) currentBps else (smoothBps * 0.7f + currentBps * 0.3f)
                                val pps = smoothPps.toInt()
                                val bps = smoothBps.toInt()

                                val activeEndpointsCount = clientRegistry.size
                                val intervalPeak = audioMeter.getAndResetIntervalPeak()
                                val peakPercent = AudioLevelMeter.calculatePeakPercent(intervalPeak, is24Bit = false)
                                StreamState.update {
                                    it.copy(
                                        packetsTotal = totalPackets,
                                        packetsPerSec = pps,
                                        bytesPerSec = bps,
                                        isSilenceSuppressed = isSilenceSuppressed,
                                        activeReceiversCount = activeEndpointsCount,
                                        audioPeakPercent = if (isSilenceSuppressed) 0 else peakPercent
                                    )
                                }
                                lastVuTime = nowStats

                                intervalPackets = 0
                                intervalBytes = 0
                                lastStatsTime = nowStats
                            }
                        } else if (pcmBytesRead == 0) {
                            Thread.sleep(2)
                        } else if (pcmBytesRead < 0) {
                            val consec = consecutiveCaptureErrors.incrementAndGet()
                            diagCaptureReadErrors.incrementAndGet()
                            HatDiagnostics.increment("capture_read_errors")
                            HatDiagnostics.warn(
                                "AUDIO_RECORD_READ_ERROR",
                                mapOf(
                                    "pcmBytesRead" to pcmBytesRead,
                                    "consecutiveErrors" to consec,
                                    "recordingState" to record.recordingState
                                )
                            )
                            if (consec >= 5 || record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                                Log.e(TAG, "Fatal AudioRecord read error (compressed) after $consec consecutive errors (recordingState=${record.recordingState}): $pcmBytesRead")
                                HatDiagnostics.error(
                                    "AUDIO_RECORD_ERROR",
                                    mapOf(
                                        "pcmBytesRead" to pcmBytesRead,
                                        "consecutiveErrors" to consec,
                                        "sampleRate" to captureSampleRate,
                                        "codec" to negotiatedStreamConfig.codec.name,
                                        "recordingState" to record.recordingState
                                    )
                                )
                                break
                            }
                            Thread.sleep(5)
                            continue
                        }
                    } else {
                        val tRead0 = SystemClock.elapsedRealtimeNanos()
                        val bytesRead = record.read(rawPcmBuffer, 0, activePayloadSize, AudioRecord.READ_BLOCKING)
                        lastReadDurationNs = SystemClock.elapsedRealtimeNanos() - tRead0
                        diagCaptureReadCalls.incrementAndGet()
                        HatDiagnostics.recordTime("captureRead", lastReadDurationNs)

                        if (bytesRead > 0) {
                            consecutiveCaptureErrors.set(0L)
                            diagCapturePcmBytes.addAndGet(bytesRead.toLong())
                            val isEffective24 = is24BitActive
                            if (isEffective24) {
                                masterEqualizer.process24BitStereo(rawPcmBuffer, 0, bytesRead)
                            } else {
                                masterEqualizer.process16BitStereo(rawPcmBuffer, 0, bytesRead)
                            }
                            val bytesPerFrame = if (isEffective24) 6 else 4
                            val framesRead = bytesRead / bytesPerFrame
                            val currentTimestamp = streamTimelineFrames
                            streamTimelineFrames += framesRead

                            val metrics = audioMeter.analyze(rawPcmBuffer, 0, bytesRead, is24Bit = isEffective24)
                            val chunkPeak = metrics.peak

                            val isChunkSilent = if (isEffective24) {
                                chunkPeak <= AudioConfig.SILENCE_AMPLITUDE_THRESHOLD_24BIT
                            } else {
                                chunkPeak <= AudioConfig.SILENCE_AMPLITUDE_THRESHOLD_16BIT
                            }

                            if (isChunkSilent) {
                                silentPacketsCount++
                                if (silentPacketsCount >= AudioConfig.SILENCE_PACKETS_THRESHOLD) {
                                    isSilenceSuppressed = true
                                }
                            } else {
                                if (isSilenceSuppressed && (config.transportProfile.latencyTarget == LatencyTarget.LOW_LATENCY)) {
                                    val drainBuf = ByteArray(activePayloadSize)
                                    var drained = record.read(drainBuf, 0, drainBuf.size, AudioRecord.READ_NON_BLOCKING)
                                    while (drained > 0) {
                                        // Drain stale backlog frames
                                        diagCaptureFramesDropped.addAndGet((drained / bytesPerFrame).toLong())
                                        drained = record.read(drainBuf, 0, drainBuf.size, AudioRecord.READ_NON_BLOCKING)
                                    }
                                }
                                silentPacketsCount = 0
                                isSilenceSuppressed = false
                            }

                            val now = SystemClock.elapsedRealtime()
                            if (now - lastVuTime >= 200L) {
                                val intervalPeak = audioMeter.getAndResetIntervalPeak()
                                val peakPercent = AudioLevelMeter.calculatePeakPercent(intervalPeak, is24Bit = isEffective24)
                                StreamState.update {
                                    it.copy(audioPeakPercent = if (isSilenceSuppressed) 0 else peakPercent)
                                }
                                lastVuTime = now
                            }
                            val shouldSendHeartbeat = isSilenceSuppressed && (now - lastHeartbeatTime >= AudioConfig.SILENCE_HEARTBEAT_INTERVAL_MS)

                            if (!isSilenceSuppressed || shouldSendHeartbeat) {
                                val currentSeq = sequence
                                sequence = (sequence + 1) and 0xFFFF

                                val volByte = remoteVolumePercent.get().coerceIn(0, 100).toByte()
                                var effectivePayloadLen: Int
                                var activeCodec = AudioCodec.PCM

                                if (isSilenceSuppressed) {
                                    val header = negotiatedStreamConfig.createHeader(
                                        packetType = HatPacket.TYPE_SILENCE_HEARTBEAT,
                                        sequenceNumber = currentSeq,
                                        payloadLength = 0,
                                        timestamp = currentTimestamp,
                                        volumeOrCaps = volByte
                                    )
                                    HatPacket.writeHeader(sendBuffer, 0, header)
                                    packet.length = HatPacket.HEADER_SIZE
                                    lastHeartbeatTime = now
                                    effectivePayloadLen = 0
                                } else {
                                    val isLosslessRequested = negotiatedStreamConfig.codec == AudioCodec.LOSSLESS
                                    if (isLosslessRequested) {
                                        val compBytes = losslessCodec.encode(
                                            pcm = rawPcmBuffer,
                                            offset = 0,
                                            length = bytesRead,
                                            is24Bit = isEffective24,
                                            out = sendBuffer,
                                            outOffset = HatPacket.HEADER_SIZE
                                        )
                                        val isLossless = (compBytes < bytesRead) && (sendBuffer[HatPacket.HEADER_SIZE] != LosslessAudioCodec.MODE_RAW)
                                        if (isLossless) {
                                            activeCodec = AudioCodec.LOSSLESS
                                            effectivePayloadLen = compBytes
                                        } else {
                                            activeCodec = AudioCodec.PCM
                                            System.arraycopy(rawPcmBuffer, 0, sendBuffer, HatPacket.HEADER_SIZE, bytesRead)
                                            effectivePayloadLen = bytesRead
                                        }
                                    } else {
                                        activeCodec = AudioCodec.PCM
                                        System.arraycopy(rawPcmBuffer, 0, sendBuffer, HatPacket.HEADER_SIZE, bytesRead)
                                        effectivePayloadLen = bytesRead
                                    }

                                    val header = negotiatedStreamConfig.copy(codec = activeCodec).createHeader(
                                        packetType = HatPacket.TYPE_AUDIO,
                                        sequenceNumber = currentSeq,
                                        payloadLength = effectivePayloadLen,
                                        timestamp = currentTimestamp,
                                        volumeOrCaps = volByte
                                    )
                                    HatPacket.writeHeader(sendBuffer, 0, header)
                                    packet.length = HatPacket.HEADER_SIZE + effectivePayloadLen
                                }

                                broadcastDatagram(socket, packet)
                                totalPackets++
                                totalBytes += packet.length
                                intervalPackets++
                                intervalBytes += packet.length

                                if (isFecEnabled) {
                                    if (isSilenceSuppressed) {
                                        fecEncoder.reset()
                                    } else {
                                        val parityBytes = fecEncoder.encode(
                                            seq = currentSeq,
                                            timestamp = currentTimestamp,
                                            payload = sendBuffer,
                                            offset = HatPacket.HEADER_SIZE,
                                            len = effectivePayloadLen,
                                            codec = activeCodec.wireCode,
                                            profile = negotiatedStreamConfig.transportProfile.latencyTarget.wireCode,
                                            sampleRateCode = negotiatedStreamConfig.audioFormat.sampleRate.wireCode,
                                            bitDepth = negotiatedStreamConfig.audioFormat.bitDepth.wireCode,
                                            volume = volByte.toInt() and 0xFF,
                                            generation = negotiatedStreamConfig.generation
                                        )
                                        if (parityBytes != null) {
                                            fecDatagramPacket.setData(parityBytes, 0, parityBytes.size)
                                            broadcastDatagram(socket, fecDatagramPacket)
                                            totalPackets++
                                            intervalPackets++
                                            totalBytes += parityBytes.size
                                            intervalBytes += parityBytes.size
                                        }
                                    }
                                }
                            }

                            val nowStats = SystemClock.elapsedRealtime()
                            if (nowStats - lastStatsTime >= 1000L) {
                                val elapsedSec = (nowStats - lastStatsTime) / 1000.0f
                                val currentPps = intervalPackets / elapsedSec
                                val currentBps = intervalBytes / elapsedSec
                                smoothPps = if (smoothPps == 0f) currentPps else (smoothPps * 0.7f + currentPps * 0.3f)
                                smoothBps = if (smoothBps == 0f) currentBps else (smoothBps * 0.7f + currentBps * 0.3f)
                                val pps = smoothPps.toInt()
                                val bps = smoothBps.toInt()

                                val activeEndpointsCount = clientRegistry.size
                                val intervalPeak = audioMeter.getAndResetIntervalPeak()
                                val peakPercent = AudioLevelMeter.calculatePeakPercent(intervalPeak, is24Bit = isEffective24)
                                StreamState.update {
                                    it.copy(
                                        packetsTotal = totalPackets,
                                        packetsPerSec = pps,
                                        bytesPerSec = bps,
                                        isSilenceSuppressed = isSilenceSuppressed,
                                        activeReceiversCount = activeEndpointsCount,
                                        audioPeakPercent = if (isSilenceSuppressed) 0 else peakPercent
                                    )
                                }
                                lastVuTime = nowStats

                                intervalPackets = 0
                                intervalBytes = 0
                                lastStatsTime = nowStats
                            }
                        } else if (bytesRead == 0) {
                            Thread.sleep(2)
                        } else if (bytesRead < 0) {
                            val consec = consecutiveCaptureErrors.incrementAndGet()
                            diagCaptureReadErrors.incrementAndGet()
                            HatDiagnostics.increment("capture_read_errors")
                            HatDiagnostics.warn(
                                "AUDIO_RECORD_READ_ERROR",
                                mapOf(
                                    "bytesRead" to bytesRead,
                                    "consecutiveErrors" to consec,
                                    "recordingState" to record.recordingState
                                )
                            )
                            if (consec >= 5 || record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                                Log.e(TAG, "Fatal AudioRecord read error after $consec consecutive errors (recordingState=${record.recordingState}): $bytesRead")
                                HatDiagnostics.error(
                                    "AUDIO_RECORD_ERROR",
                                    mapOf(
                                        "bytesRead" to bytesRead,
                                        "consecutiveErrors" to consec,
                                        "sampleRate" to captureSampleRate,
                                        "recordingState" to record.recordingState
                                    )
                                )
                                break
                            }
                            Thread.sleep(5)
                            continue
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio streaming exception", e)
                HatDiagnostics.error(
                    "THREAD_FAILURE",
                    mapOf("thread" to "AudioCaptureStreamer", "generation" to negotiatedStreamConfig.generation),
                    e
                )
                StreamState.update { it.copy(statusDetail = "Error: ${e.message}") }
            } finally {
                Log.i(TAG, "Audio streaming thread stopped")
                // Only while this worker is still the active producer: a newer transaction may already have
                // published a replacement, and nulling its reference would be wrong. This keeps streamThread
                // null once the producer has exited, including termination immediately after startup, and keeps
                // isTransmissionActive an accurate statement about a running producer. Capture resources stay
                // owned by the service and are released by the next stop/transaction.
                if (streamThread === Thread.currentThread()) {
                    streamThread = null
                    profileTransactionManager.markProducerStopped()
                }
            }
        }, "AudioCaptureStreamer")

        streamThread = worker
        worker.isDaemon = true
        txFirstPacketGeneration = config.generation
        try {
            worker.start()
        } catch (t: Throwable) {
            streamThread = null
            Log.e(TAG, "Failed to start stream thread for generation ${config.generation}", t)
            HatDiagnostics.error(
                "THREAD_FAILURE",
                mapOf("thread" to "AudioCaptureStreamer", "stage" to "Thread.start", "generation" to config.generation),
                t
            )
            return false
        }
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // HAT runtime diagnostics wiring
    // ---------------------------------------------------------------------------------------------

    /** Opens the diagnostics run session: metadata, run id, snapshot sections and the periodic stats task. */
    private fun startDiagnosticsSession() {
        val localNode = com.example.audiostreamer.node.LocalNodeManager.getLocalNode()
        com.example.audiostreamer.node.LocalNodeManager.updateState(
            com.example.audiostreamer.node.NodeState.ACTIVE_STREAMING,
            com.example.audiostreamer.node.StreamRole.SENDER,
            currentStreamGeneration.get()
        )
        HatDiagnostics.setMetadata(
            HatDiagnostics.Metadata(
                appVersion = BuildConfig.VERSION_NAME,
                deviceModel = Build.MODEL,
                manufacturer = Build.MANUFACTURER,
                androidVersion = Build.VERSION.RELEASE,
                apiLevel = Build.VERSION.SDK_INT,
                audioOutputDevice = "MediaProjection playback capture",
                audioSampleRate = activeCaptureSampleRate,
                audioChannelConfig = if (AudioConfig.CHANNELS == 2) "stereo" else AudioConfig.CHANNELS.toString(),
                networkTransport = "UDP (unicast/broadcast)",
                nodeId = localNode.id,
                nodeName = localNode.name,
                nodeRole = com.example.audiostreamer.node.StreamRole.SENDER.name,
                nodeState = com.example.audiostreamer.node.NodeState.ACTIVE_STREAMING.name,
                nodeCapabilitiesSummary = localNode.capabilities.describe()
            )
        )
        HatDiagnostics.startRun(
            streamId = "tx:${currentTargetIp}:$currentTargetPort",
            runId = HatDiagnostics.newRunId(Build.MODEL)
        )
        HatDiagnostics.registerSection("CONFIG") { activeNegotiatedConfig?.diagnosticFields(clientRegistry.size) ?: emptyMap() }
        HatDiagnostics.registerSection("GENERATION") {
            mapOf(
                "generation" to currentStreamGeneration.get(),
                "isTransmissionActive" to profileTransactionManager.isTransmissionActive,
                "producerThreadAlive" to (streamThread?.isAlive ?: false)
            )
        }
        HatDiagnostics.registerSection("CAPTURE") { captureDiagnosticsSnapshot() }
        HatDiagnostics.registerSection("TX") { txDiagnosticsSnapshot() }
        HatDiagnostics.registerSection("RECEIVERS") {
            clientDiag.entries.associate { entry ->
                entry.key.toString() to ("packets=${entry.value.packets.get()} bytes=${entry.value.bytes.get()} " +
                    "errors=${entry.value.sendErrors.get()} port=${entry.key.port}")
            }
        }
        HatDiagnostics.registerPeriodicTask("captureTxStats") { emitCaptureTxStats() }
        diagStatsLastNs = System.nanoTime()
        diagStatsLastPackets = diagPacketsGenerated.get()
        diagStatsLastBytes = diagTxBytes.get()
        HatDiagnostics.startPeriodicStats()
        HatDiagnostics.info(
            "NETWORK_CHANGED",
            mapOf(
                "targetIp" to currentTargetIp,
                "targetPort" to currentTargetPort,
                "transport" to "UDP",
                "localIp" to (NetworkUtils.getLocalIpAddress() ?: "unknown"),
                "interface" to NetworkUtils.findMatchingLocalIp(currentTargetIp).orEmpty()
            )
        )
    }

    /** Detaches this service from the diagnostics facility and closes the run session. */
    private fun stopDiagnosticsSession() {
        HatDiagnostics.unregisterPeriodicTask("captureTxStats")
        HatDiagnostics.unregisterSection("CONFIG")
        HatDiagnostics.unregisterSection("GENERATION")
        HatDiagnostics.unregisterSection("TX")
        HatDiagnostics.unregisterSection("CAPTURE")
        HatDiagnostics.unregisterSection("RECEIVERS")
        HatDiagnostics.stopPeriodicStats()
        clientDiag.clear()
    }

    private fun captureDiagnosticsSnapshot(): Map<String, Any?> {
        val record = audioRecord
        val config = activeNegotiatedConfig
        val audioFormatCode = try {
            record?.audioFormat ?: 0
        } catch (e: Exception) {
            0
        }
        val is24 = audioFormatCode == AudioFormat.ENCODING_PCM_24BIT_PACKED
        val bytesPerFrame = if (is24) 6 else 4
        val bufferFrames = try {
            record?.bufferSizeInFrames ?: 0
        } catch (e: Exception) {
            0
        }
        val read = HatDiagnostics.timing("captureRead")?.snapshot()
        return linkedMapOf(
            "sampleRate" to (try { record?.sampleRate ?: activeCaptureSampleRate } catch (e: Exception) { activeCaptureSampleRate }),
            "channels" to AudioConfig.CHANNELS,
            "bitDepth" to (if (is24) 24 else (config?.bitDepthBits ?: 16)),
            "audioRecordState" to (try { record?.state ?: 0 } catch (e: Exception) { 0 }),
            "recordingState" to (try { record?.recordingState ?: 0 } catch (e: Exception) { 0 }),
            "bufferSizeBytes" to bufferFrames * bytesPerFrame,
            "bufferSizeFrames" to bufferFrames,
            "framesCaptured" to diagCaptureFrames.get(),
            "framesDropped" to diagCaptureFramesDropped.get(),
            "readCalls" to diagCaptureReadCalls.get(),
            "readErrors" to diagCaptureReadErrors.get(),
            "consecutiveReadErrors" to consecutiveCaptureErrors.get(),
            "avgReadMs" to ((read?.avgNs ?: 0L) / 1_000_000.0),
            "maxReadMs" to ((read?.maxNs ?: 0L) / 1_000_000.0),
            "packetsGenerated" to diagPacketsGenerated.get(),
            "packetsSent" to diagTxPackets.get(),
            "bytesSent" to diagTxBytes.get(),
            "compressionRatio" to compressionRatio()
        )
    }

    private fun compressionRatio(): Double {
        val pcm = diagCapturePcmBytes.get()
        if (pcm <= 0L) return 0.0
        return diagTxBytes.get().toDouble() / pcm.toDouble()
    }

    private fun txDiagnosticsSnapshot(): Map<String, Any?> {
        val send = HatDiagnostics.timing("send")?.snapshot()
        return linkedMapOf(
            "packetsGenerated" to diagPacketsGenerated.get(),
            "packetsSent" to diagTxPackets.get(),
            "bytesSent" to diagTxBytes.get(),
            "sendErrors" to diagTxErrors.get(),
            "sendCalls" to (send?.count ?: 0L),
            "avgSendMs" to ((send?.avgNs ?: 0L) / 1_000_000.0),
            "maxSendMs" to ((send?.maxNs ?: 0L) / 1_000_000.0),
            "receivers" to clientRegistry.size
        )
    }

    /**
     * Periodic transport/capture statistics. Runs on the diagnostics thread (never on the audio loop) and
     * emits one CAPTURE_STATS plus one TX_STATS per active receiver per interval.
     */
    private fun emitCaptureTxStats() {
        val nowNs = System.nanoTime()
        val elapsedNs = (nowNs - diagStatsLastNs).coerceAtLeast(1L)
        val elapsedSec = elapsedNs / 1_000_000_000.0
        val packetsGenerated = diagPacketsGenerated.get()
        val bytesSent = diagTxBytes.get()

        val captureFields = captureDiagnosticsSnapshot().toMutableMap()
        captureFields["packetsPerSecond"] = ((packetsGenerated - diagStatsLastPackets) / elapsedSec).toInt()
        captureFields["bytesPerSecond"] = ((bytesSent - diagStatsLastBytes) / elapsedSec).toInt()
        HatDiagnostics.stats("CAPTURE_STATS", captureFields)

        val config = activeNegotiatedConfig
        for ((endpoint, stats) in clientDiag) {
            val packets = stats.packets.get()
            val bytes = stats.bytes.get()
            if (packets == 0L && stats.lastPackets == 0L && stats.lastSampleNs == 0L) {
                continue
            }
            val receiverElapsedSec = if (stats.lastSampleNs == 0L) elapsedSec else (nowNs - stats.lastSampleNs) / 1_000_000_000.0
            val safeElapsed = receiverElapsedSec.coerceAtLeast(0.001)
            val packetsDelta = packets - stats.lastPackets
            val bytesDelta = bytes - stats.lastBytes
            HatDiagnostics.stats(
                "TX_STATS",
                linkedMapOf(
                    "receiver" to stats.label,
                    "packets" to packets,
                    "bytes" to bytes,
                    "packetsPerSecond" to (packetsDelta / safeElapsed).toInt(),
                    "bytesPerSecond" to (bytesDelta / safeElapsed).toInt(),
                    "sendErrors" to stats.sendErrors.get(),
                    "enetUnreach" to stats.unreachable.get(),
                    "timeouts" to stats.timeouts.get(),
                    "otherErrors" to stats.otherErrors.get(),
                    "avgPayloadBytes" to (if (packetsDelta > 0) bytesDelta / packetsDelta else 0),
                    "generation" to (config?.generation ?: 0L),
                    "port" to endpoint.port
                )
            )
            stats.lastPackets = packets
            stats.lastBytes = bytes
            stats.lastSampleNs = nowNs
        }

        diagStatsLastNs = nowNs
        diagStatsLastPackets = packetsGenerated
        diagStatsLastBytes = bytesSent
    }

    private fun stopStreaming() {
        stopStreamingInternal(keepProjection = false)
    }

    private fun stopStreamingInternal(keepProjection: Boolean) {
        if (!isRunning.getAndSet(false)) {
            return
        }
        Log.i(TAG, "Stopping audio capture service (keepProjection=$keepProjection)")

        MediaSessionTracker.onStateChangedListener = null
        AudioPlaybackDetector.stopMonitoring(this)

        // Broadcast 3x TYPE_DISCONNECT burst to all connected receivers so they immediately terminate
        val sock = udpSocket
        if (sock != null && !sock.isClosed) {
            val discBuf = ByteArray(HatPacket.HEADER_SIZE)
            val header = HatPacket.Header(
                packetType = HatPacket.TYPE_DISCONNECT,
                payloadLength = 0
            )
            HatPacket.writeHeader(discBuf, 0, header)
            for (ep in clientRegistry.keys) {
                try {
                    val discPkt = DatagramPacket(discBuf, discBuf.size, ep.address, ep.port)
                    repeat(3) {
                        sock.send(discPkt)
                    }
                } catch (ignored: Exception) {}
            }
        }
        clientRegistry.clear()
        clientCapabilities.clear()
        clientDiag.clear()
        configuredEndpoints.clear()

        stopProducerSynchronously()
        stopDiagnosticsSession()
        com.example.audiostreamer.node.LocalNodeManager.updateState(
            com.example.audiostreamer.node.NodeState.AVAILABLE,
            com.example.audiostreamer.node.StreamRole.IDLE
        )

        val cThread = controlListenerThread
        controlListenerThread = null
        cThread?.interrupt()

        try {
            activeTransport?.close()
            udpSocket?.close()
        } catch (ignored: Exception) {}
        udpSocket = null
        activeTransport = null
        profileTransactionManager.reset()
        HatDiagnostics.lifecycle(
            "GENERATION_STOPPED",
            currentStreamGeneration.get(),
            mapOf("reason" to "service_stopped")
        )

        try {
            cThread?.join(300)
        } catch (ignored: InterruptedException) {}

        if (!keepProjection) {
            try {
                mediaProjection?.unregisterCallback(projectionCallback)
                mediaProjection?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping MediaProjection", e)
            }
            mediaProjection = null

            unregisterVolumeClampGuard()

            // Automatically restore phone media volume
            try {
                previousPhoneVolume?.let { savedVol ->
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedVol, 0)
                    Log.i(TAG, "Restored phone media volume to $savedVol")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore phone volume: ${e.message}")
            }
            previousPhoneVolume = null
            clientRegistry.clear()
            clientCapabilities.clear()
            clientNodeInfo.clear()
            com.example.audiostreamer.node.HatMultiStreamManager.clear()
            com.example.audiostreamer.node.HatLinkManager.clear()

            StreamState.update {
                it.copy(
                    isActive = false,
                    isTransmitter = false,
                    audioPeakPercent = 0,
                    packetsPerSec = 0,
                    bytesPerSec = 0,
                    statusDetail = "Stopped",
                    activeReceiversCount = 0,
                    connectedReceivers = emptyList(),
                    activeLinks = emptyList(),
                    activeStreams = emptyList()
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            try {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                notificationManager?.cancel(NOTIFICATION_ID)
            } catch (ignored: Exception) {}

            releaseLocks()
        }
    }

    private fun silenceTransmitterSpeakers() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (currentVol > 0) {
                previousPhoneVolume = currentVol
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE)
                Log.i(TAG, "Automatically silenced transmitter phone media volume (saved previous: $currentVol)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not silence transmitter volume: ${e.message}")
        }
    }

    private fun registerVolumeClampGuard() {
        // Only activate background clamp guard if the user has enabled the Accessibility Service (Pocket Mode)
        if (!VolumeKeyInterceptorService.isRunning.get()) return
        if (volumeReceiver != null || volumeObserver != null) return
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                        val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                        if (streamType == AudioManager.STREAM_MUSIC) {
                            checkAndClampTransmitterVolume()
                        }
                    }
                }
            }
            volumeReceiver = receiver
            registerReceiver(receiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        } catch (e: Exception) {
            Log.w(TAG, "Could not register VOLUME_CHANGED_ACTION receiver: ${e.message}")
        }

        try {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    checkAndClampTransmitterVolume()
                }
            }
            volumeObserver = observer
            contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register volume ContentObserver: ${e.message}")
        }
    }

    private fun checkAndClampTransmitterVolume() {
        if (!isRunning.get() || !VolumeKeyInterceptorService.isRunning.get()) return
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (currentVol > 0) {
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    0,
                    AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE
                )
                Log.d(TAG, "Pocket mode: maintained transmitter STREAM_MUSIC at 0")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in pocket mode volume clamp: ${e.message}")
        }
    }

    private fun unregisterVolumeClampGuard() {
        try {
            volumeReceiver?.let {
                unregisterReceiver(it)
                volumeReceiver = null
            }
        } catch (ignored: Exception) {}

        try {
            volumeObserver?.let {
                contentResolver.unregisterContentObserver(it)
                volumeObserver = null
            }
        } catch (ignored: Exception) {}
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AudioStreamer:CaptureWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WakeLock", e)
        }

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val wifiLockMode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifiManager.createWifiLock(wifiLockMode, "AudioStreamer:CaptureWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WifiLock", e)
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }
        wakeLock = null

        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WifiLock", e)
        }
        wifiLock = null
    }

    private fun pauseSystemMediaPlayback() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            Log.i(TAG, "Paused system media playback via KEYCODE_MEDIA_PAUSE")
            StreamState.update {
                it.copy(
                    statusDetail = "Client disconnected - Media paused",
                    audioPeakPercent = 0
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed pausing media playback: ${e.message}")
        }
    }

    fun sendMediaMetadata(endpoint: ClientEndpoint? = null) {
        val meta = currentTrackMetadata ?: return
        val sock = udpSocket ?: return
        if (sock.isClosed) return

        try {
            val payload = HatPacket.serializeMediaMetadata(
                isPlaying = meta.isPlaying,
                title = meta.title,
                artist = meta.artist,
                album = meta.album,
                mediaStateSequence = meta.mediaStateSequence,
                packageName = meta.packageName
            )
            val header = HatPacket.Header(
                packetType = HatPacket.TYPE_MEDIA_METADATA,
                payloadLength = payload.size
            )
            val sendBuf = ByteArray(HatPacket.HEADER_SIZE + payload.size)
            HatPacket.writeHeader(sendBuf, 0, header)
            System.arraycopy(payload, 0, sendBuf, HatPacket.HEADER_SIZE, payload.size)

            val targets = if (endpoint != null) listOf(endpoint) else clientRegistry.keys.toList()
            for (target in targets) {
                val packet = DatagramPacket(sendBuf, sendBuf.size, target.address, target.port)
                sock.send(packet)
            }
            Log.d(TAG, "MEDIA_METADATA_TX seq=${meta.mediaStateSequence} package=${meta.packageName} title='${meta.title}' → ${targets.size} endpoints")
        } catch (e: Exception) {
            Log.w(TAG, "Failed sending media metadata: ${e.message}")
        }
    }

    fun handleMediaControlCommand(command: Byte) {
        val targetPkg = MediaSessionTracker.currentState?.packageName ?: "unknown"
        Log.i(TAG, "MEDIA_COMMAND command=$command targetPackage=$targetPkg")

        // Primary: dispatch via MediaSessionTracker (always targets current controller)
        val handled = MediaSessionTracker.dispatchCommand(command)
        if (handled) return

        // Fallback: inject system media key event (no Notification Access or no controller)
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val keyCode = when (command) {
                HatPacket.MEDIA_CMD_PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                HatPacket.MEDIA_CMD_PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
                HatPacket.MEDIA_CMD_PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
                HatPacket.MEDIA_CMD_NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                HatPacket.MEDIA_CMD_PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                else -> return
            }
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            Log.i(TAG, "MEDIA_COMMAND fallback keyEvent=$keyCode for cmd=$command")
        } catch (e: Exception) {
            Log.w(TAG, "Failed dispatching media key event: ${e.message}")
        }
    }

    override fun onDestroy() {
        if (currentInstance === this) {
            currentInstance = null
        }
        stopStreaming()
        super.onDestroy()
        Log.d(TAG, "AudioCaptureService destroyed")
    }

    private class AacEncoder(val sampleRate: Int, val channelCount: Int = 2, val bitRate: Int = AudioConfig.AAC_BIT_RATE) {
        private var codec: MediaCodec? = null
        private val bufferInfo = MediaCodec.BufferInfo()

        /** True only when the underlying MediaCodec was created, configured and started. */
        val isInitialized: Boolean get() = codec != null

        init {
            // The MediaCodec is created locally and only published to [codec] once it is fully started, so a
            // configuration/start failure can never leave a half-configured codec referenced by this encoder.
            var encoder: MediaCodec? = null
            try {
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()
                codec = encoder
                Log.i(TAG, "Initialized AAC MediaCodec encoder: rate=$sampleRate, channels=$channelCount, bitRate=$bitRate")
            } catch (e: Exception) {
                Log.e(TAG, "Failed initializing AAC encoder", e)
                try {
                    encoder?.release()
                } catch (ignored: Exception) {}
                codec = null
            }
        }

        private var presentationTimeUs = 0L

        fun encode(pcmData: ByteArray, offset: Int, length: Int): List<ByteArray> {
            val encoder = codec ?: return emptyList()
            val results = mutableListOf<ByteArray>()

            try {
                val inIndex = encoder.dequeueInputBuffer(2000L)
                if (inIndex >= 0) {
                    val inBuf = encoder.getInputBuffer(inIndex)
                    inBuf?.clear()
                    inBuf?.put(pcmData, offset, length)
                    val pts = presentationTimeUs
                    val durationUs = (length.toLong() * 1_000_000L) / (sampleRate * channelCount * 2)
                    presentationTimeUs += durationUs
                    encoder.queueInputBuffer(inIndex, 0, length, pts, 0)
                }

                var outIndex = encoder.dequeueOutputBuffer(bufferInfo, 1000L)
                while (outIndex >= 0 || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(TAG, "AAC encoder output format changed: ${encoder.outputFormat}")
                    } else {
                        val outBuf = encoder.getOutputBuffer(outIndex)
                        val outSize = bufferInfo.size
                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (!isConfig && outBuf != null && outSize > 0) {
                            val frameLen = 7 + outSize
                            val frame = ByteArray(frameLen)
                            addAdtsHeader(frame, frameLen, sampleRate, channelCount)
                            outBuf.position(bufferInfo.offset)
                            outBuf.get(frame, 7, outSize)
                            results.add(frame)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                    }
                    outIndex = encoder.dequeueOutputBuffer(bufferInfo, 0L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "AAC encode error: ${e.message}")
            }
            return results
        }

        private fun addAdtsHeader(packet: ByteArray, packetLen: Int, sampleRate: Int, channels: Int) {
            val profile = 2 // AAC LC
            val freqIdx = when (sampleRate) {
                96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3
                44100 -> 4; 32000 -> 5; 24000 -> 6; 22050 -> 7
                16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
                else -> 3
            }
            packet[0] = 0xFF.toByte()
            packet[1] = 0xF1.toByte() // MPEG-4, Layer 0, No CRC (0xF1)
            packet[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (channels shr 2)).toByte()
            packet[3] = (((channels and 3) shl 6) + (packetLen shr 11)).toByte()
            packet[4] = ((packetLen and 0x7FF) shr 3).toByte()
            packet[5] = (((packetLen and 7) shl 5) + 0x1F).toByte()
            packet[6] = 0xFC.toByte()
        }

        fun release() {
            try {
                codec?.stop()
                codec?.release()
            } catch (ignored: Exception) {}
            codec = null
            presentationTimeUs = 0L
        }
    }

    private class OpusEncoder(val sampleRate: Int, val channelCount: Int = 2, val bitRate: Int = AudioConfig.OPUS_BIT_RATE_HIGH) {
        private var codec: MediaCodec? = null
        private val bufferInfo = MediaCodec.BufferInfo()
        private var presentationTimeUs = 0L

        /** True only when the underlying MediaCodec was created, configured and started. */
        val isInitialized: Boolean get() = codec != null

        init {
            // The MediaCodec is created locally and only published to [codec] once it is fully started, so a
            // configuration/start failure can never leave a half-configured codec referenced by this encoder.
            var encoder: MediaCodec? = null
            try {
                val format = MediaFormat.createAudioFormat(AudioConfig.OPUS_MIME_TYPE, sampleRate, channelCount).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_COMPLEXITY, 5)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
                encoder = MediaCodec.createEncoderByType(AudioConfig.OPUS_MIME_TYPE)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()
                codec = encoder
                Log.i(TAG, "Initialized Opus MediaCodec encoder: rate=$sampleRate, channels=$channelCount, bitRate=$bitRate")
            } catch (e: Exception) {
                Log.e(TAG, "Failed initializing Opus encoder", e)
                try {
                    encoder?.release()
                } catch (ignored: Exception) {}
                codec = null
            }
        }

        fun encode(pcmData: ByteArray, offset: Int, length: Int): List<ByteArray> {
            val encoder = codec ?: return emptyList()
            val results = mutableListOf<ByteArray>()

            try {
                val inIndex = encoder.dequeueInputBuffer(2000L)
                if (inIndex >= 0) {
                    val inBuf = encoder.getInputBuffer(inIndex)
                    inBuf?.clear()
                    inBuf?.put(pcmData, offset, length)
                    val pts = presentationTimeUs
                    val durationUs = (length.toLong() * 1_000_000L) / (sampleRate * channelCount * 2)
                    presentationTimeUs += durationUs
                    encoder.queueInputBuffer(inIndex, 0, length, pts, 0)
                }

                var outIndex = encoder.dequeueOutputBuffer(bufferInfo, 1000L)
                while (outIndex >= 0 || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(TAG, "Opus encoder output format changed: ${encoder.outputFormat}")
                    } else {
                        val outBuf = encoder.getOutputBuffer(outIndex)
                        val outSize = bufferInfo.size
                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (!isConfig && outBuf != null && outSize > 0) {
                            val frame = ByteArray(outSize)
                            outBuf.position(bufferInfo.offset)
                            outBuf.get(frame, 0, outSize)
                            results.add(frame)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                    }
                    outIndex = encoder.dequeueOutputBuffer(bufferInfo, 0L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Opus encode error: ${e.message}")
            }
            return results
        }

        fun release() {
            try {
                codec?.stop()
                codec?.release()
            } catch (ignored: Exception) {}
            codec = null
            presentationTimeUs = 0L
        }
    }
}

private const val PIPELINE_TAG = "AudioCaptureService"

/**
 * A fully initialized capture pipeline for one profile-change attempt.
 *
 * An instance only ever holds resources that were ALL created and validated successfully: a partially
 * initialized pipeline is never handed out. The owner releases it via [CapturePipelineInitializer.release]
 * (or by releasing each resource) once the producer consuming it has been stopped.
 */
class CapturePipeline<R : Any, O : Any, A : Any>(
    val audioRecord: R,
    val opusEncoder: O?,
    val aacEncoder: A?
)

/**
 * Smallest runtime capture-pipeline initialization boundary used by the profile-change transaction.
 *
 * Creates every resource the negotiated config requires, in order, and only returns a pipeline once all of
 * them are valid:
 * - the AudioRecord factory must return an already validated record (or null/throw when it cannot create one);
 * - a compressed codec additionally requires its encoder resource; a missing one is a failure;
 * - uncompressed codecs (PCM/LOSSLESS) require no encoder resources.
 *
 * If ANY step fails (exception or missing required resource) every resource created by that attempt is
 * released before null is returned, so a failed profile change can never leak an AudioRecord, an
 * encoder/MediaCodec, or leave a partially initialized pipeline referenced by service fields.
 */
class CapturePipelineInitializer<R : Any, O : Any, A : Any>(
    private val createAudioRecord: (NegotiatedStreamConfig) -> R?,
    private val createOpusEncoder: (NegotiatedStreamConfig) -> O?,
    private val createAacEncoder: (NegotiatedStreamConfig) -> A?,
    private val releaseAudioRecord: (R) -> Unit,
    private val releaseOpusEncoder: (O) -> Unit,
    private val releaseAacEncoder: (A) -> Unit
) {
    fun initialize(config: NegotiatedStreamConfig): CapturePipeline<R, O, A>? {
        var record: R? = null
        var opus: O? = null
        var aac: A? = null
        try {
            record = createAudioRecord(config)
            if (record == null) {
                Log.e(PIPELINE_TAG, "AudioRecord unavailable; capture pipeline not initialized for ${config.toSummaryString()}")
                return null
            }

            when (config.codec) {
                AudioCodec.OPUS -> {
                    opus = createOpusEncoder(config)
                    if (opus == null) {
                        throw IllegalStateException("Opus encoder initialization failed for ${config.toSummaryString()}")
                    }
                }
                AudioCodec.AAC -> {
                    aac = createAacEncoder(config)
                    if (aac == null) {
                        throw IllegalStateException("AAC encoder initialization failed for ${config.toSummaryString()}")
                    }
                }
                // Uncompressed streams need no encoder resources
                AudioCodec.PCM, AudioCodec.LOSSLESS -> Unit
            }

            return CapturePipeline(record, opus, aac)
        } catch (t: Throwable) {
            Log.e(
                PIPELINE_TAG,
                "Capture pipeline initialization failed for ${config.toSummaryString()}; releasing every created resource",
                t
            )
            releaseQuietly(record, opus, aac)
            return null
        }
    }

    /** Releases a pipeline previously returned by [initialize]. Safe to call with null. */
    fun release(pipeline: CapturePipeline<R, O, A>?) {
        if (pipeline == null) return
        releaseQuietly(pipeline.audioRecord, pipeline.opusEncoder, pipeline.aacEncoder)
    }

    private fun releaseQuietly(record: R?, opus: O?, aac: A?) {
        record?.let { res ->
            try {
                releaseAudioRecord(res)
            } catch (t: Throwable) {
                Log.w(PIPELINE_TAG, "Failed releasing AudioRecord: ${t.message}")
            }
        }
        opus?.let { enc ->
            try {
                releaseOpusEncoder(enc)
            } catch (t: Throwable) {
                Log.w(PIPELINE_TAG, "Failed releasing Opus encoder: ${t.message}")
            }
        }
        aac?.let { enc ->
            try {
                releaseAacEncoder(enc)
            } catch (t: Throwable) {
                Log.w(PIPELINE_TAG, "Failed releasing AAC encoder: ${t.message}")
            }
        }
    }
}
