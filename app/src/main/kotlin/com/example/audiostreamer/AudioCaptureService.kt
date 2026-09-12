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

        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val EXTRA_TARGET_IP = "EXTRA_TARGET_IP"
        const val EXTRA_TARGET_PORT = "EXTRA_TARGET_PORT"
        const val EXTRA_VOLUME_PERCENT = "EXTRA_VOLUME_PERCENT"
        const val EXTRA_VOLUME_DELTA = "EXTRA_VOLUME_DELTA"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "AudioCaptureChannel"

        val isRunning = AtomicBoolean(false)
        val remoteVolumePercent = AtomicInteger(100)

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
    private var udpSocket: DatagramSocket? = null
    private var streamThread: Thread? = null
    private var controlListenerThread: Thread? = null
    private var previousPhoneVolume: Int? = null
    private var currentTargetIp = "192.168.43.255"
    private var currentTargetPort = AudioConfig.DEFAULT_PORT
    private val clientRegistry = ConcurrentHashMap<ClientEndpoint, Long>()
    private val clientCapabilities = ConcurrentHashMap<ClientEndpoint, Int>()
    private var lastClientPruneTime = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var aacEncoder: AacEncoder? = null
    private var opusEncoder: OpusEncoder? = null
    private var volumeReceiver: BroadcastReceiver? = null
    private var volumeObserver: ContentObserver? = null
    private var activeCaptureSampleRate = AudioConfig.SAMPLE_RATE_48000
    private var lastLiveAdaptTime = 0L

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
        }
        return START_NOT_STICKY
    }

    private fun updateRemoteVolume(newVolume: Int) {
        val clamped = newVolume.coerceIn(0, 100)
        remoteVolumePercent.set(clamped)
        StreamState.update { it.copy(remoteVolumePercent = clamped) }
        Log.d(TAG, "Remote volume updated: $clamped%")

        // If streaming is actively running, streamThread transmits the new volume in the next 5ms audio packet.
        // Only dispatch out-of-band UDP control packet if streaming is idle.
        if (streamThread == null || !streamThread!!.isAlive) {
            sendControlPacket(clamped)
        }

        // Update notification
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification("Streaming @ Receiver Vol: $clamped%"))
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
            while (iter.hasNext()) {
                val entry = iter.next()
                if (entry.value != Long.MAX_VALUE && (now - entry.value > 10_000L)) {
                    Log.i(TAG, "Pruning inactive multi-unicast client: ${entry.key}")
                    iter.remove()
                }
            }
        }

        for (client in clientRegistry.keys) {
            packet.address = client.address
            packet.port = client.port
            try {
                socket.send(packet)
            } catch (e: Exception) {
                Log.w(TAG, "Failed sending datagram to $client: ${e.message}")
            }
        }
    }

    private fun sendControlPacket(volume: Int) {
        val socket = udpSocket ?: return
        Thread({
            try {
                val buffer = ByteArray(HatPacket.HEADER_SIZE)
                val prefs = getSharedPreferences("stream_prefs", Context.MODE_PRIVATE)
                val profileStr = prefs.getString(AudioConfig.PREF_KEY_PROFILE, AudioConfig.PROFILE_AUTO) ?: AudioConfig.PROFILE_AUTO
                val profileCode = HatPacket.profileStringToCode(profileStr)

                HatPacket.writeHeader(
                    buffer = buffer,
                    header = HatPacket.Header(
                        packetType = HatPacket.TYPE_CONTROL,
                        profile = profileCode,
                        volumeOrCaps = volume.coerceIn(0, 100).toByte(),
                        payloadLength = 0
                    )
                )

                val packet = DatagramPacket(buffer, buffer.size)
                broadcastDatagram(socket, packet)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending volume control packet", e)
            }
        }, "AudioCaptureControlSender").apply {
            isDaemon = true
            start()
        }
    }

    private fun startServiceForeground() {
        val notification = buildNotification("Streaming @ Receiver Vol: ${remoteVolumePercent.get()}%")
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

    private fun buildNotification(statusText: String): Notification {
        val pendingActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val volDownIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = ACTION_STEP_VOLUME
            putExtra(EXTRA_VOLUME_DELTA, -5)
        }
        val pVolDown = PendingIntent.getService(
            this,
            2,
            volDownIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val volUpIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = ACTION_STEP_VOLUME
            putExtra(EXTRA_VOLUME_DELTA, 5)
        }
        val pVolUp = PendingIntent.getService(
            this,
            3,
            volUpIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStopIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Transmitter Active")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_transmitter)
            .setContentIntent(pendingActivityIntent)
            .addAction(R.drawable.ic_volume_down, "-5%", pVolDown)
            .addAction(R.drawable.ic_volume_up, "+5%", pVolUp)
            .addAction(R.drawable.ic_stop, "Stop", pendingStopIntent)
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

        startCapturePipeline(projection, targetIp, targetPort)
    }

    private fun restartStreaming() {
        val proj = mediaProjection
        if (proj == null) {
            Log.w(TAG, "Cannot restart streaming: mediaProjection is null")
            return
        }
        Log.i(TAG, "Live restarting capture pipeline with existing MediaProjection...")
        stopStreamingInternal(keepProjection = true)
        try { Thread.sleep(50) } catch (ignored: Exception) {}
        isRunning.set(true)
        startCapturePipeline(proj, currentTargetIp, currentTargetPort)
    }

    @SuppressLint("MissingPermission")
    private fun startCapturePipeline(
        projection: MediaProjection,
        targetIp: String,
        targetPort: Int
    ) {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val detectedMedia = AudioPlaybackDetector.getActiveMediaFormat(this)
        Log.i(TAG, "AudioPlaybackDetector detected: ${detectedMedia.description}")

        val prefs = getSharedPreferences("stream_prefs", Context.MODE_PRIVATE)
        val profile = prefs.getString(AudioConfig.PREF_KEY_PROFILE, AudioConfig.PROFILE_AUTO) ?: AudioConfig.PROFILE_AUTO
        val isLowLatency = (profile == AudioConfig.PROFILE_VIDEO || profile == AudioConfig.PROFILE_LOW_LATENCY)
        val isMusic = (profile == AudioConfig.PROFILE_MUSIC)
        val isAuto = (!isLowLatency && !isMusic)

        val isOpusSupported = isLowLatency && isOpusEncoderAvailable()
        val isOpusActive = isLowLatency && isOpusSupported
        val isAacActive = isLowLatency && !isOpusSupported
        val isCompressedActive = isLowLatency

        val rawRatePref = prefs.getString(AudioConfig.PREF_KEY_SAMPLE_RATE, AudioConfig.SAMPLE_RATE_AUTO) ?: AudioConfig.SAMPLE_RATE_AUTO
        val rawBitPref = prefs.getString(AudioConfig.PREF_KEY_BIT_DEPTH, AudioConfig.BIT_DEPTH_AUTO) ?: AudioConfig.BIT_DEPTH_AUTO

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val nativeProp = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val parsedRate = nativeProp?.toIntOrNull()
        val nativeSampleRate = if (parsedRate == 44100) 44100 else 48000

        var record: AudioRecord? = null
        var captureSampleRate = AudioConfig.SAMPLE_RATE_48000
        var is24BitActive = false
        var activePayloadSize = AudioConfig.PACKET_SIZE_16BIT_48K

        val targetRate: Int
        val target24Bit: Boolean

        if (isLowLatency) {
            // Low Latency: Strictly locked to 16-bit / 48 kHz Opus (RFC 6716 native 48kHz framing)
            targetRate = AudioConfig.SAMPLE_RATE_48000
            target24Bit = false
        } else if (isAuto) {
            // Auto Adaptive Mode: 24-bit / 48 kHz stereo is the primary Android operating point.
            // When media is CD audio (44.1 kHz) and hardware mix bus operates at 44.1 kHz, match 44.1 kHz.
            // Otherwise, 48.0 kHz native mix bus avoids AudioFlinger resampler distortion.
            val hwOutputRate = AudioPlaybackDetector.getHardwareOutputRate(this)
            targetRate = if (detectedMedia.sampleRate == AudioConfig.SAMPLE_RATE_44100 && hwOutputRate == AudioConfig.SAMPLE_RATE_44100) {
                AudioConfig.SAMPLE_RATE_44100
            } else {
                AudioConfig.SAMPLE_RATE_48000
            }
            target24Bit = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) && (rawBitPref != AudioConfig.BIT_DEPTH_16)
        } else {
            // Unlocked Music Mode: 24-bit / 48 kHz stereo primary operating point on Android.
            // Android capture is clamped to native rates (48k / 44.1k) to avoid AudioFlinger internal distortion.
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
        val rxCapsFromClients = clientCapabilities.values.firstOrNull { it != 0 } ?: 0
        val rxCapsFromDiscovery = DiscoveryManager.lastDiscoveredReceiverCapabilities
        val rxCapsFromPref = prefs.getInt(AudioConfig.PREF_KEY_RECEIVER_CAPS, 0)
        val rxCaps = when {
            rxCapsFromClients != 0 -> rxCapsFromClients
            rxCapsFromDiscovery != 0 -> rxCapsFromDiscovery
            rxCapsFromPref != 0 -> rxCapsFromPref
            else -> 0
        }

        val negotiatedRate = if (rxCaps != 0) {
            AudioCapabilities.getHighestMutuallySupportedRate(txCaps, rxCaps, targetRate)
        } else {
            AudioCapabilities.getHighestMutuallySupportedRate(txCaps, txCaps, targetRate)
        }

        val sourceCapDesc = "Android HAL: ${AudioCapabilities.describeCapabilities(txCaps)}"
        val rxCapDesc = if (rxCaps != 0) AudioCapabilities.describeCapabilities(rxCaps) else "Pending receiver discovery"

        if (profile == AudioConfig.PROFILE_MUSIC && negotiatedRate != targetRate) {
            Log.i(TAG, "Uncapped Music: requested $targetRate Hz clamped to mutually supported $negotiatedRate Hz (TX: ${AudioCapabilities.describeCapabilitiesMask(txCaps)}, RX: ${AudioCapabilities.describeCapabilitiesMask(rxCaps)})")
        }

        val candidateRates = if (isCompressedActive) {
            mutableListOf(AudioConfig.SAMPLE_RATE_48000)
        } else {
            // Android native operating rates: 48 kHz (primary) and 44.1 kHz (fallback)
            val list = mutableListOf(negotiatedRate)
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
                    val rec = AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(captureConfig)
                        .setAudioFormat(audioFormat16)
                        .setBufferSizeInBytes(bufSize16)
                        .build()
                    if (rec.state == AudioRecord.STATE_INITIALIZED) {
                        record = rec
                        captureSampleRate = rate
                        is24BitActive = false
                        activePayloadSize = AudioConfig.getPacketPayloadSize(rate, false)
                        Log.i(TAG, "Initialized compressed capture AudioRecord at $rate Hz")
                        break
                    } else {
                        rec.release()
                    }
                }
            }
        } else {
            // Phase 1: Probe 24-bit packed PCM ONLY if target24Bit is true and on Android 12+
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
                                captureSampleRate = rate
                                is24BitActive = true
                                activePayloadSize = AudioConfig.getPacketPayloadSize(rate, true)
                                Log.i(TAG, "Probed and initialized 24-bit packed PCM AudioRecord at $rate Hz (Payload: $activePayloadSize bytes)")
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

            // Phase 2: Probe 16-bit PCM (if 24-bit was not requested or failed)
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
                                captureSampleRate = rate
                                is24BitActive = false
                                activePayloadSize = AudioConfig.getPacketPayloadSize(rate, false)
                                Log.i(TAG, "Probed and initialized 16-bit PCM AudioRecord at $rate Hz (Payload: $activePayloadSize bytes)")
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
            projection.stop()
            isRunning.set(false)
            stopSelf()
            return
        }
        this.audioRecord = record
        // Read back actual AudioRecord sample rate after init.
        // The OS may grant a different rate than requested if the hardware mix bus does not
        // natively support the requested rate (AudioFlinger resamples internally).
        val actualGrantedRate = record.sampleRate
        if (actualGrantedRate > 0 && actualGrantedRate != captureSampleRate) {
            Log.w(TAG, "AudioRecord granted $actualGrantedRate Hz (requested $captureSampleRate Hz). Hardware mix bus rate mismatch - using $actualGrantedRate Hz.")
            captureSampleRate = actualGrantedRate
            activePayloadSize = AudioConfig.getPacketPayloadSize(actualGrantedRate, is24BitActive)
        }
        activeCaptureSampleRate = captureSampleRate

        silenceTransmitterSpeakers()

        streamThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

            var socketToClose: DatagramSocket? = null
            try {
                val targetAddresses = parseTargetAddresses(targetIp)
                clientRegistry.clear()
                clientCapabilities.clear()
                for (addr in targetAddresses) {
                    val ep = ClientEndpoint(addr, targetPort)
                    clientRegistry[ep] = Long.MAX_VALUE
                    val knownCaps = DiscoveryManager.lastDiscoveredReceiverCapabilities
                    if (knownCaps != 0) {
                        clientCapabilities[ep] = knownCaps
                    }
                }
                val firstTargetIp = targetAddresses.firstOrNull()?.hostAddress ?: targetIp
                val matchingLocalIp = NetworkUtils.findMatchingLocalIp(firstTargetIp)
                val socket: DatagramSocket = try {
                    if (matchingLocalIp != null) {
                        DatagramSocket(InetSocketAddress(InetAddress.getByName(matchingLocalIp), 0)).apply {
                            sendBufferSize = AudioConfig.SOCKET_SEND_BUFFER_BYTES
                            broadcast = true
                        }
                    } else {
                        DatagramSocket().apply {
                            sendBufferSize = AudioConfig.SOCKET_SEND_BUFFER_BYTES
                            broadcast = true
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed binding socket to interface IP $matchingLocalIp, falling back to unbound socket: ${e.message}")
                    DatagramSocket().apply {
                        sendBufferSize = AudioConfig.SOCKET_SEND_BUFFER_BYTES
                        broadcast = true
                    }
                }
                socketToClose = socket
                udpSocket = socket

                val listenerSocket = socket
                controlListenerThread = Thread({
                    val recvBuf = ByteArray(64)
                    val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                    while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                        try {
                            listenerSocket.receive(recvPacket)
                            val header = HatPacket.parseHeader(recvBuf, 0, recvPacket.length)
                            if (header != null) {
                                val endpoint = ClientEndpoint(recvPacket.address, recvPacket.port)
                                val byteVal = header.volumeOrCaps.toInt() and 0xFF

                                when (header.packetType) {
                                    HatPacket.TYPE_REVERSE_VOLUME_SYNC -> {
                                        val incomingVol = byteVal.coerceIn(0, 100)
                                        Log.i(TAG, "Received reverse volume sync: $incomingVol% from $endpoint")
                                        remoteVolumePercent.set(incomingVol)
                                        StreamState.update { it.copy() }
                                        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                                        notificationManager.notify(NOTIFICATION_ID, buildNotification("Streaming @ Receiver Vol: $incomingVol%"))
                                    }
                                    HatPacket.TYPE_RECEIVER_HEARTBEAT -> {
                                        if (byteVal != 0) {
                                            clientCapabilities[endpoint] = byteVal
                                            prefs.edit().putInt(AudioConfig.PREF_KEY_RECEIVER_CAPS, byteVal).apply()
                                        }
                                        val isNew = !clientRegistry.containsKey(endpoint)
                                        clientRegistry[endpoint] = SystemClock.elapsedRealtime()
                                        if (isNew) {
                                            val capsDesc = if (byteVal != 0) AudioCapabilities.describeCapabilitiesMask(byteVal) else "default"
                                            Log.i(TAG, "Registered new multi-unicast receiver: $endpoint (Caps: $capsDesc)")
                                        }
                                    }
                                    HatPacket.TYPE_DISCONNECT -> {
                                        Log.i(TAG, "Received client disconnect signal from $endpoint. Pausing media.")
                                        clientRegistry.remove(endpoint)
                                        clientCapabilities.remove(endpoint)
                                        if (clientRegistry.isEmpty()) {
                                            pauseSystemMediaPlayback()
                                        }
                                    }
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

                val sendBuffer = ByteArray(AudioConfig.HEADER_SIZE + AudioConfig.MAX_PACKET_SIZE)
                val packet = DatagramPacket(sendBuffer, sendBuffer.size)

                // Populate Magic Header "SA"
                val prefs = getSharedPreferences("stream_prefs", Context.MODE_PRIVATE)
                var activeProfile = prefs.getString(AudioConfig.PREF_KEY_PROFILE, AudioConfig.PROFILE_AUTO) ?: AudioConfig.PROFILE_AUTO

                val initialProfileDisplayName = when {
                    isOpusActive -> "Low Latency (Opus 320k)"
                    isAacActive -> "Low Latency (AAC 192k)"
                    activeProfile == AudioConfig.PROFILE_AUTO -> if (is24BitActive) "Auto Adaptive (24-bit, ${captureSampleRate / 1000}kHz)" else "Auto Adaptive (${captureSampleRate / 1000}kHz)"
                    is24BitActive -> if (activeProfile == AudioConfig.PROFILE_MUSIC && captureSampleRate < targetRate) "Studio 24-bit (Clamped ${captureSampleRate / 1000}kHz)" else "Studio 24-bit Music (${captureSampleRate / 1000}kHz)"
                    else -> if (activeProfile == AudioConfig.PROFILE_MUSIC && captureSampleRate < targetRate) "Music (Clamped ${captureSampleRate / 1000}kHz)" else "Music (${captureSampleRate / 1000}kHz)"
                }

                val initialPayload = activePayloadSize

                val opusEnc = if (isOpusActive) OpusEncoder(captureSampleRate) else null
                this.opusEncoder = opusEnc
                val aacEnc = if (isAacActive) AacEncoder(captureSampleRate) else null
                this.aacEncoder = aacEnc

                // XOR Forward Error Correction (FEC) setup
                val isFecEnabled = prefs.getBoolean(AudioConfig.PREF_KEY_FEC_ENABLED, true)
                val fecEncoder = FecEncoder(AudioConfig.FEC_BLOCK_SIZE)
                val fecDatagramPacket = DatagramPacket(ByteArray(AudioConfig.HEADER_SIZE + AudioConfig.MAX_PACKET_SIZE), 0)

                record.startRecording()
                Log.i(TAG, "AudioRecord recording started. Streaming at $captureSampleRate Hz to ${clientRegistry.size} target(s) (Profile: $initialProfileDisplayName, Payload: $initialPayload bytes, FEC: $isFecEnabled)")

                var sequence = 0
                var streamTimelineFrames = 0L
                var totalPackets = 0L
                var totalBytes = 0L
                var intervalPackets = 0
                var intervalBytes = 0
                var lastStatsTime = SystemClock.elapsedRealtime()
                var maxSampleInInterval = 0
                var silentPacketsCount = 0
                var isSilenceSuppressed = false
                var lastHeartbeatTime = 0L
                var smoothPps = 0f
                var smoothBps = 0f
                var lastLatencyLogTime = 0L
                var lastReadDurationNs = 0L
                var lastEncodeDurationNs = 0L

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
                    "$targetIp:$targetPort"
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
                        sourceCapabilityDesc = sourceCapDesc,
                        receiverCapabilityDesc = rxCapDesc,
                        negotiatedFormatDesc = initialNegotiatedFormat
                    )
                }

                val pcmReadBuffer = ByteArray(4096)

                val rawPcmBuffer = ByteArray(AudioConfig.MAX_PACKET_SIZE)
                val losslessCodec = LosslessAudioCodec(1024)

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
                        if (pcmBytesRead > 0) {
                            var chunkPeak = 0
                            var pi = 0
                            while (pi < pcmBytesRead - 1) {
                                val sample = (pcmReadBuffer[pi].toInt() and 0xFF) or (pcmReadBuffer[pi + 1].toInt() shl 8)
                                val abs = kotlin.math.abs(sample.toShort().toInt())
                                if (abs > chunkPeak) chunkPeak = abs
                                pi += 16
                            }
                            if (chunkPeak > maxSampleInInterval) maxSampleInInterval = chunkPeak

                            val isChunkSilent = (chunkPeak <= AudioConfig.SILENCE_AMPLITUDE_THRESHOLD_16BIT)
                            if (isChunkSilent) {
                                silentPacketsCount++
                                if (silentPacketsCount >= 25) isSilenceSuppressed = true
                            } else {
                                silentPacketsCount = 0
                                isSilenceSuppressed = false
                            }

                            val now = SystemClock.elapsedRealtime()
                            val shouldSendHeartbeat = isSilenceSuppressed && (now - lastHeartbeatTime >= AudioConfig.SILENCE_HEARTBEAT_INTERVAL_MS)

                            val codecFlag = if (isOpusActive) AudioConfig.FLAG_CODEC_OPUS else AudioConfig.FLAG_CODEC_AAC

                            val framesInChunk = if (isOpusActive) 960 else if (isAacActive) 1024 else (activePayloadSize / 4)

                            if (!isSilenceSuppressed) {
                                val tEnc0 = SystemClock.elapsedRealtimeNanos()
                                val encodedFrames = if (isOpusActive) {
                                    opusEnc?.encode(pcmReadBuffer, 0, pcmBytesRead) ?: emptyList()
                                } else {
                                    aacEnc?.encode(pcmReadBuffer, 0, pcmBytesRead) ?: emptyList()
                                }
                                lastEncodeDurationNs = SystemClock.elapsedRealtimeNanos() - tEnc0
                                for (frame in encodedFrames) {
                                    val frameLen = frame.size
                                    if (frameLen > 0 && frameLen <= AudioConfig.MAX_PACKET_SIZE) {
                                        val currentSeq = sequence
                                        sequence = (sequence + 1) and 0xFFFF
                                        val currentTimestamp = streamTimelineFrames
                                        streamTimelineFrames += framesInChunk

                                        val volByte = remoteVolumePercent.get().coerceIn(0, 100).toByte()
                                        val codecType = if (isOpusActive) HatPacket.CODEC_OPUS else HatPacket.CODEC_AAC

                                        HatPacket.writeHeader(
                                            buffer = sendBuffer,
                                            offset = 0,
                                            header = HatPacket.Header(
                                                packetType = HatPacket.TYPE_AUDIO,
                                                sequenceNumber = currentSeq,
                                                payloadLength = frameLen,
                                                timestamp = currentTimestamp,
                                                codec = codecType,
                                                profile = HatPacket.PROFILE_LOW_LATENCY,
                                                sampleRateCode = HatPacket.sampleRateToCode(captureSampleRate),
                                                bitDepth = HatPacket.BIT_DEPTH_16,
                                                channels = HatPacket.CHANNELS_STEREO,
                                                volumeOrCaps = volByte
                                            )
                                        )
                                        System.arraycopy(frame, 0, sendBuffer, HatPacket.HEADER_SIZE, frameLen)
                                        packet.length = HatPacket.HEADER_SIZE + frameLen

                                        broadcastDatagram(socket, packet)
                                        totalPackets++
                                        totalBytes += packet.length
                                        intervalPackets++
                                        intervalBytes += packet.length

                                        // Forward Error Correction (XOR FEC) for compressed stream
                                        if (isFecEnabled) {
                                            val parityBytes = fecEncoder.encode(
                                                seq = currentSeq,
                                                timestamp = currentTimestamp,
                                                payload = sendBuffer,
                                                offset = HatPacket.HEADER_SIZE,
                                                len = frameLen,
                                                codec = codecType,
                                                profile = HatPacket.PROFILE_LOW_LATENCY,
                                                sampleRateCode = HatPacket.sampleRateToCode(captureSampleRate),
                                                bitDepth = HatPacket.BIT_DEPTH_16,
                                                volume = volByte.toInt() and 0xFF
                                            )
                                            if (parityBytes != null) {
                                                fecDatagramPacket.setData(parityBytes, 0, parityBytes.size)
                                                broadcastDatagram(socket, fecDatagramPacket)
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
                                    val codecType = if (isOpusActive) HatPacket.CODEC_OPUS else HatPacket.CODEC_AAC

                                    HatPacket.writeHeader(
                                        buffer = sendBuffer,
                                        offset = 0,
                                        header = HatPacket.Header(
                                            packetType = HatPacket.TYPE_SILENCE_HEARTBEAT,
                                            sequenceNumber = currentSeq,
                                            payloadLength = 0,
                                            timestamp = currentTimestamp,
                                            codec = codecType,
                                            profile = HatPacket.PROFILE_LOW_LATENCY,
                                            sampleRateCode = HatPacket.sampleRateToCode(captureSampleRate),
                                            bitDepth = HatPacket.BIT_DEPTH_16,
                                            channels = HatPacket.CHANNELS_STEREO,
                                            volumeOrCaps = volByte
                                        )
                                    )
                                    packet.length = HatPacket.HEADER_SIZE
                                    lastHeartbeatTime = now
                                    broadcastDatagram(socket, packet)
                                    totalPackets++
                                    totalBytes += packet.length
                                    intervalPackets++
                                    intervalBytes += packet.length
                                }
                            }
                        } else if (pcmBytesRead == 0) {
                            Thread.sleep(2)
                        } else if (pcmBytesRead < 0) {
                            Log.e(TAG, "AudioRecord read error (compressed): $pcmBytesRead")
                            break
                        }
                    } else {
                    val bytesRead = record.read(rawPcmBuffer, 0, activePayloadSize, AudioRecord.READ_BLOCKING)
                    if (bytesRead > 0) {
                        val currentIsLowLat = (activeProfile == AudioConfig.PROFILE_VIDEO || activeProfile == AudioConfig.PROFILE_LOW_LATENCY)
                        val isEffective24 = is24BitActive
                        val bytesPerFrame = if (isEffective24) 6 else 4
                        val framesRead = bytesRead / bytesPerFrame
                        val currentTimestamp = streamTimelineFrames
                        streamTimelineFrames += framesRead

                        // Compute peak amplitude of current chunk
                        var chunkPeak = 0
                        if (isEffective24) {
                            var i = 0
                            while (i < bytesRead - 2) {
                                val raw = (rawPcmBuffer[i].toInt() and 0xFF) or
                                    ((rawPcmBuffer[i + 1].toInt() and 0xFF) shl 8) or
                                    ((rawPcmBuffer[i + 2].toInt() and 0xFF) shl 16)
                                val sample = if (raw and 0x800000 != 0) raw or 0xFF000000.toInt() else raw
                                val abs = kotlin.math.abs(sample)
                                if (abs > chunkPeak) chunkPeak = abs
                                i += 24
                            }
                        } else {
                            var i = 0
                            while (i < bytesRead - 1) {
                                val sample = (rawPcmBuffer[i].toInt() and 0xFF) or (rawPcmBuffer[i + 1].toInt() shl 8)
                                val abs = kotlin.math.abs(sample.toShort().toInt())
                                if (abs > chunkPeak) chunkPeak = abs
                                i += 16
                            }
                        }
                        if (chunkPeak > maxSampleInInterval) maxSampleInInterval = chunkPeak

                        // Silence suppression evaluation
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
                            if (isSilenceSuppressed && currentIsLowLat) {
                                val drainBuf = ByteArray(activePayloadSize)
                                while (record.read(drainBuf, 0, drainBuf.size, AudioRecord.READ_NON_BLOCKING) > 0) {
                                    // Drain stale backlog frames
                                }
                            }
                            silentPacketsCount = 0
                            isSilenceSuppressed = false
                        }

                        val now = SystemClock.elapsedRealtime()
                        val shouldSendHeartbeat = isSilenceSuppressed && (now - lastHeartbeatTime >= AudioConfig.SILENCE_HEARTBEAT_INTERVAL_MS)

                        // Transmit regular audio immediately (<5ms wakeup) or silence heartbeat at 2 pps
                        if (!isSilenceSuppressed || shouldSendHeartbeat) {
                            val currentSeq = sequence
                            sequence = (sequence + 1) and 0xFFFF

                            val volByte = remoteVolumePercent.get().coerceIn(0, 100).toByte()
                            val profileCode = HatPacket.profileStringToCode(activeProfile)
                            val rateCode = HatPacket.sampleRateToCode(captureSampleRate)
                            val bitDepthByte = if (isEffective24) HatPacket.BIT_DEPTH_24 else HatPacket.BIT_DEPTH_16

                            var effectivePayloadLen: Int
                            var activeCodec = HatPacket.CODEC_RAW_PCM

                            if (isSilenceSuppressed) {
                                HatPacket.writeHeader(
                                    buffer = sendBuffer,
                                    offset = 0,
                                    header = HatPacket.Header(
                                        packetType = HatPacket.TYPE_SILENCE_HEARTBEAT,
                                        sequenceNumber = currentSeq,
                                        payloadLength = 0,
                                        timestamp = currentTimestamp,
                                        codec = HatPacket.CODEC_RAW_PCM,
                                        profile = profileCode,
                                        sampleRateCode = rateCode,
                                        bitDepth = bitDepthByte,
                                        channels = HatPacket.CHANNELS_STEREO,
                                        volumeOrCaps = volByte
                                    )
                                )
                                packet.length = HatPacket.HEADER_SIZE
                                lastHeartbeatTime = now
                                effectivePayloadLen = 0
                            } else {
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
                                    activeCodec = HatPacket.CODEC_LOSSLESS_PCM
                                    effectivePayloadLen = compBytes
                                } else {
                                    activeCodec = HatPacket.CODEC_RAW_PCM
                                    System.arraycopy(rawPcmBuffer, 0, sendBuffer, HatPacket.HEADER_SIZE, bytesRead)
                                    effectivePayloadLen = bytesRead
                                }

                                HatPacket.writeHeader(
                                    buffer = sendBuffer,
                                    offset = 0,
                                    header = HatPacket.Header(
                                        packetType = HatPacket.TYPE_AUDIO,
                                        sequenceNumber = currentSeq,
                                        payloadLength = effectivePayloadLen,
                                        timestamp = currentTimestamp,
                                        codec = activeCodec,
                                        profile = profileCode,
                                        sampleRateCode = rateCode,
                                        bitDepth = bitDepthByte,
                                        channels = HatPacket.CHANNELS_STEREO,
                                        volumeOrCaps = volByte
                                    )
                                )
                                packet.length = HatPacket.HEADER_SIZE + effectivePayloadLen
                            }

                            broadcastDatagram(socket, packet)
                            totalPackets++
                            totalBytes += packet.length
                            intervalPackets++
                            intervalBytes += packet.length

                            // Forward Error Correction (XOR FEC) Accumulation & Parity Dispatch
                            if (isSilenceSuppressed) {
                                fecEncoder.reset()
                            } else if (isFecEnabled && effectivePayloadLen > 0) {
                                val parityBytes = fecEncoder.encode(
                                    seq = currentSeq,
                                    timestamp = currentTimestamp,
                                    payload = sendBuffer,
                                    offset = HatPacket.HEADER_SIZE,
                                    len = effectivePayloadLen,
                                    codec = activeCodec,
                                    profile = profileCode,
                                    sampleRateCode = rateCode,
                                    bitDepth = bitDepthByte,
                                    volume = volByte.toInt() and 0xFF
                                )
                                if (parityBytes != null) {
                                    fecDatagramPacket.setData(parityBytes, 0, parityBytes.size)
                                    broadcastDatagram(socket, fecDatagramPacket)
                                    totalBytes += parityBytes.size
                                    intervalBytes += parityBytes.size
                                }
                            }
                        }

                        val dt = now - lastStatsTime
                        if (dt >= 250) {
                            activeProfile = prefs.getString(AudioConfig.PREF_KEY_PROFILE, AudioConfig.PROFILE_MUSIC) ?: AudioConfig.PROFILE_MUSIC
                            val inLowLatencyNow = (activeProfile == AudioConfig.PROFILE_LOW_LATENCY)
                            val is24Now = is24BitActive && !isCompressedActive

                            val instantPps = ((intervalPackets * 1000L) / dt).toFloat()
                            val instantBps = ((intervalBytes * 1000L) / dt).toFloat()
                            smoothPps = if (smoothPps == 0f) instantPps else (smoothPps * 0.7f + instantPps * 0.3f)
                            smoothBps = if (smoothBps == 0f) instantBps else (smoothBps * 0.7f + instantBps * 0.3f)
                            val pps = smoothPps.toInt()
                            val bps = smoothBps.toInt()
                            val peakPercent = if (is24Now) {
                                ((maxSampleInInterval * 100L) / 8388608L).toInt().coerceIn(0, 100)
                            } else {
                                ((maxSampleInInterval * 100) / 32768).coerceIn(0, 100)
                            }
                            val bitDepth = if (isCompressedActive) 16 else if (is24Now) 24 else 16
                            val bitrate = when {
                                isOpusActive -> 320
                                isAacActive -> 192
                                else -> (captureSampleRate * 2 * (if (is24Now) 3 else 2) * 8) / 1000
                            }
                            val isLowLatNow = (activeProfile == AudioConfig.PROFILE_VIDEO || activeProfile == AudioConfig.PROFILE_LOW_LATENCY)
                            val profileDisplayName = when {
                                isOpusActive -> "Low Latency (Opus 320k)"
                                isAacActive -> "Low Latency (AAC 192k)"
                                activeProfile == AudioConfig.PROFILE_AUTO -> if (is24Now) "Auto Adaptive (24-bit, ${captureSampleRate / 1000}kHz)" else "Auto Adaptive (${captureSampleRate / 1000}kHz)"
                                is24BitActive -> if (activeProfile == AudioConfig.PROFILE_MUSIC && captureSampleRate < targetRate) "Studio 24-bit (Clamped ${captureSampleRate / 1000}kHz)" else "Studio 24-bit Music (${captureSampleRate / 1000}kHz)"
                                else -> if (activeProfile == AudioConfig.PROFILE_MUSIC && captureSampleRate < targetRate) "Music (Clamped ${captureSampleRate / 1000}kHz)" else "Music (${captureSampleRate / 1000}kHz)"
                            }

                            val receiverCount = clientRegistry.size
                            val statusDetailText = if (isSilenceSuppressed) {
                                "Silence Suppressed (Standby)"
                            } else if (receiverCount > 1) {
                                "Multi-Unicast ($receiverCount receivers)"
                            } else if (peakPercent > 1) {
                                "Active Audio ($bitDepth-bit)"
                            } else {
                                "Silent Stream"
                            }

                            val endpointLabel = if (receiverCount > 1) {
                                "$receiverCount receivers (DAP Vol: ${remoteVolumePercent.get()}%)"
                            } else if (receiverCount == 1) {
                                val single = clientRegistry.keys.first()
                                "${single.address.hostAddress}:${single.port} (DAP Vol: ${remoteVolumePercent.get()}%)"
                            } else {
                                "$targetIp:$targetPort (DAP Vol: ${remoteVolumePercent.get()}%)"
                            }

                            val periodicNegotiatedFormat = if (isCompressedActive) {
                                "${if (isOpusActive) "Opus" else "AAC"} • ${bitrate} kbps • ${captureSampleRate / 1000.0} kHz"
                            } else {
                                "${captureSampleRate / 1000.0} kHz • ${bitDepth}-bit Stereo PCM"
                            }
                            val currentRxCap = clientCapabilities.values.firstOrNull { it != 0 } ?: rxCaps
                            val currentRxCapDesc = if (currentRxCap != 0) AudioCapabilities.describeCapabilities(currentRxCap) else rxCapDesc

                            StreamState.update {
                                it.copy(
                                    isActive = true,
                                    isTransmitter = true,
                                    packetsTotal = totalPackets,
                                    packetsPerSec = pps,
                                    bytesPerSec = bps,
                                    audioPeakPercent = peakPercent,
                                    remoteEndpoint = endpointLabel,
                                    statusDetail = statusDetailText,
                                    streamProfileName = profileDisplayName,
                                    sampleRate = captureSampleRate,
                                    bitDepth = bitDepth,
                                    bitrateKbps = bitrate,
                                    isSilenceSuppressed = isSilenceSuppressed,
                                    activeReceiversCount = receiverCount,
                                    sourceCapabilityDesc = sourceCapDesc,
                                    receiverCapabilityDesc = currentRxCapDesc,
                                    negotiatedFormatDesc = periodicNegotiatedFormat
                                )
                            }

                            if (now - lastLatencyLogTime >= 1000L) {
                                lastLatencyLogTime = now
                                val readMsStr = String.format(Locale.US, "%.1f", lastReadDurationNs / 1_000_000.0)
                                val encMsStr = String.format(Locale.US, "%.1f", lastEncodeDurationNs / 1_000_000.0)
                                Log.i("LATENCY-TX", "Record: ${readMsStr}ms | Encode: ${encMsStr}ms | Sent: $pps pps ($bps B/s)")
                            }

                            intervalPackets = 0
                            intervalBytes = 0
                            maxSampleInInterval = 0
                            lastStatsTime = now
                        }
                    } else if (bytesRead == 0) {
                        Thread.sleep(2)
                    } else if (bytesRead < 0) {
                        Log.e(TAG, "AudioRecord read error: $bytesRead")
                        break
                    }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio streaming exception", e)
                StreamState.update { it.copy(statusDetail = "Error: ${e.message}") }
            } finally {
                try {
                    socketToClose?.close()
                } catch (ignored: Exception) {}
                udpSocket = null
                Log.i(TAG, "Audio streaming thread stopped")
            }
        }, "AudioCaptureStreamer").apply {
            isDaemon = true
            start()
        }

        // Real-time audio engine adaptation: monitor Android playback sessions (e.g. Tidal/Spotify)
        if (profile == AudioConfig.PROFILE_AUTO || (profile == AudioConfig.PROFILE_MUSIC && (rawRatePref == AudioConfig.SAMPLE_RATE_AUTO || rawBitPref == AudioConfig.BIT_DEPTH_AUTO))) {
            AudioPlaybackDetector.startMonitoring(this) { newFormat ->
                Log.d(TAG, "AudioPlaybackDetector active media format: ${newFormat.description}")
            }
        }
    }

    private fun stopStreaming() {
        stopStreamingInternal(keepProjection = false)
    }

    private fun stopStreamingInternal(keepProjection: Boolean) {
        if (!isRunning.getAndSet(false)) {
            return
        }
        Log.i(TAG, "Stopping audio capture service (keepProjection=$keepProjection)")

        AudioPlaybackDetector.stopMonitoring(this)

        controlListenerThread?.interrupt()
        controlListenerThread = null

        streamThread?.interrupt()
        streamThread = null

        try {
            udpSocket?.close()
        } catch (ignored: Exception) {}
        udpSocket = null

        try {
            audioRecord?.let {
                if (it.state == AudioRecord.STATE_INITIALIZED) {
                    if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        it.stop()
                    }
                    it.release()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null

        try {
            opusEncoder?.release()
        } catch (ignored: Exception) {}
        opusEncoder = null

        try {
            aacEncoder?.release()
        } catch (ignored: Exception) {}
        aacEncoder = null

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

            StreamState.update {
                it.copy(
                    isActive = false,
                    isTransmitter = false,
                    audioPeakPercent = 0,
                    packetsPerSec = 0,
                    bytesPerSec = 0,
                    statusDetail = "Stopped",
                    activeReceiversCount = 1
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }

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

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
        Log.d(TAG, "AudioCaptureService destroyed")
    }

    private class AacEncoder(val sampleRate: Int, val channelCount: Int = 2, val bitRate: Int = AudioConfig.AAC_BIT_RATE) {
        private var codec: MediaCodec? = null
        private val bufferInfo = MediaCodec.BufferInfo()

        init {
            try {
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
                val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()
                codec = encoder
                Log.i(TAG, "Initialized AAC MediaCodec encoder: rate=$sampleRate, channels=$channelCount, bitRate=$bitRate")
            } catch (e: Exception) {
                Log.e(TAG, "Failed initializing AAC encoder", e)
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

        init {
            try {
                val format = MediaFormat.createAudioFormat(AudioConfig.OPUS_MIME_TYPE, sampleRate, channelCount).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_COMPLEXITY, 5)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
                val encoder = MediaCodec.createEncoderByType(AudioConfig.OPUS_MIME_TYPE)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()
                codec = encoder
                Log.i(TAG, "Initialized Opus MediaCodec encoder: rate=$sampleRate, channels=$channelCount, bitRate=$bitRate")
            } catch (e: Exception) {
                Log.e(TAG, "Failed initializing Opus encoder", e)
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
