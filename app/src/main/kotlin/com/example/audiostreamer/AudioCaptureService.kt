package com.example.audiostreamer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.VolumeProvider
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AudioCaptureService : Service() {

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
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var udpSocket: DatagramSocket? = null
    private var streamThread: Thread? = null
    private var controlListenerThread: Thread? = null
    private var mediaSession: MediaSession? = null
    private var volumeProvider: VolumeProvider? = null
    private var previousPhoneVolume: Int? = null
    private var currentTargetIp = "192.168.43.255"
    private var currentTargetPort = AudioConfig.DEFAULT_PORT
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

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

                startServiceForeground()
                startStreaming(resultCode, resultData, targetIp, targetPort)
            }
        }
        return START_NOT_STICKY
    }

    private fun updateRemoteVolume(newVolume: Int) {
        val clamped = newVolume.coerceIn(0, 100)
        remoteVolumePercent.set(clamped)
        volumeProvider?.currentVolume = clamped
        Log.d(TAG, "Remote volume updated: $clamped%")

        // If streaming is actively running, streamThread transmits the new volume in the next 5ms audio packet.
        // Only dispatch out-of-band UDP control packet if streaming is idle.
        if (streamThread == null || !streamThread!!.isAlive) {
            sendControlPacket(clamped)
        }

        // Update notification
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification("Streaming @ DAP Vol: $clamped%"))
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

    private fun sendControlPacket(volume: Int) {
        val socket = udpSocket ?: return
        Thread({
            try {
                val targetAddresses = parseTargetAddresses(currentTargetIp)
                val buffer = ByteArray(AudioConfig.HEADER_SIZE)
                val prefs = getSharedPreferences("stream_prefs", Context.MODE_PRIVATE)
                val profile = prefs.getString(AudioConfig.PREF_KEY_PROFILE, AudioConfig.PROFILE_MUSIC) ?: AudioConfig.PROFILE_MUSIC
                val profileFlag = if (profile == AudioConfig.PROFILE_LOW_LATENCY) AudioConfig.FLAG_PROFILE_LOW_LATENCY else AudioConfig.FLAG_PROFILE_MUSIC

                // Magic "SA"
                buffer[0] = (AudioConfig.MAGIC_HEADER.toInt() shr 8).toByte()
                buffer[1] = (AudioConfig.MAGIC_HEADER.toInt() and 0xFF).toByte()
                // Sequence 0
                buffer[2] = 0
                buffer[3] = 0
                // Volume
                buffer[4] = volume.toByte()
                // Flags = CONTROL_ONLY combined with current profile flag
                buffer[5] = (AudioConfig.FLAG_CONTROL_ONLY.toInt() or profileFlag.toInt()).toByte()
                // Payload length = 0
                buffer[6] = 0
                buffer[7] = 0

                val packet = DatagramPacket(buffer, buffer.size)
                for (targetAddr in targetAddresses) {
                    packet.address = targetAddr
                    packet.port = currentTargetPort
                    socket.send(packet)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending volume control packet", e)
            }
        }, "AudioCaptureControlSender").apply {
            isDaemon = true
            start()
        }
    }

    private fun startServiceForeground() {
        val notification = buildNotification("Streaming @ DAP Vol: ${remoteVolumePercent.get()}%")
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
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingActivityIntent)
            .addAction(android.R.drawable.ic_media_previous, "-5%", pVolDown)
            .addAction(android.R.drawable.ic_media_next, "+5%", pVolUp)
            .addAction(android.R.drawable.ic_media_pause, "Stop", pendingStopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val prefs = getSharedPreferences("stream_prefs", Context.MODE_PRIVATE)
        val profile = prefs.getString(AudioConfig.PREF_KEY_PROFILE, AudioConfig.PROFILE_MUSIC) ?: AudioConfig.PROFILE_MUSIC
        val isLowLatency = profile == AudioConfig.PROFILE_LOW_LATENCY

        val sampleRatePref = prefs.getString(AudioConfig.PREF_KEY_SAMPLE_RATE, AudioConfig.SAMPLE_RATE_AUTO) ?: AudioConfig.SAMPLE_RATE_AUTO
        val captureSampleRate: Int = when (sampleRatePref) {
            AudioConfig.SAMPLE_RATE_44K -> AudioConfig.SAMPLE_RATE_44100
            AudioConfig.SAMPLE_RATE_48K -> AudioConfig.SAMPLE_RATE_48000
            else -> {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val nativeProp = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                val parsed = nativeProp?.toIntOrNull()
                if (parsed == 44100) 44100 else 48000
            }
        }

        val packetSize16 = if (captureSampleRate == AudioConfig.SAMPLE_RATE_44100) AudioConfig.PACKET_SIZE_16BIT_44K else AudioConfig.PACKET_SIZE_16BIT_48K
        val packetSize24 = if (captureSampleRate == AudioConfig.SAMPLE_RATE_44100) AudioConfig.PACKET_SIZE_24BIT_44K else AudioConfig.PACKET_SIZE_24BIT_48K

        var record: AudioRecord? = null
        var is24BitActive = false
        var activePayloadSize = packetSize16

        if (!isLowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val audioFormat24 = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_24BIT_PACKED)
                    .setSampleRate(captureSampleRate)
                    .setChannelMask(AudioConfig.CHANNEL_IN_MASK)
                    .build()
                val minBuf24 = AudioRecord.getMinBufferSize(
                    captureSampleRate,
                    AudioConfig.CHANNEL_IN_MASK,
                    AudioFormat.ENCODING_PCM_24BIT_PACKED
                )
                if (minBuf24 > 0) {
                    val bufSize24 = maxOf(minBuf24 * 4, AudioConfig.CAPTURE_BUFFER_BYTES_24BIT)
                    val candidateRecord = AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(captureConfig)
                        .setAudioFormat(audioFormat24)
                        .setBufferSizeInBytes(bufSize24)
                        .build()
                    if (candidateRecord.state == AudioRecord.STATE_INITIALIZED) {
                        record = candidateRecord
                        is24BitActive = true
                        activePayloadSize = packetSize24
                        Log.i(TAG, "Initialized 24-bit packed PCM AudioRecord at $captureSampleRate Hz for Music Mode")
                    } else {
                        candidateRecord.release()
                        Log.w(TAG, "24-bit AudioRecord not initialized by HAL at $captureSampleRate Hz, falling back to 16-bit")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Exception attempting 24-bit AudioRecord: ${e.message}, falling back to 16-bit")
            }
        }

        if (record == null) {
            val audioFormat16 = AudioFormat.Builder()
                .setEncoding(AudioConfig.ENCODING)
                .setSampleRate(captureSampleRate)
                .setChannelMask(AudioConfig.CHANNEL_IN_MASK)
                .build()
            val minBuf16 = AudioRecord.getMinBufferSize(
                captureSampleRate,
                AudioConfig.CHANNEL_IN_MASK,
                AudioConfig.ENCODING
            )
            // Always provide generous 500ms buffer so Android resampler never drops frames on 44.1k/48k conversions
            val bufSize16 = maxOf(minBuf16 * 4, AudioConfig.CAPTURE_BUFFER_BYTES_16BIT)
            val fallbackRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(audioFormat16)
                .setBufferSizeInBytes(bufSize16)
                .build()
            if (fallbackRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed at $captureSampleRate Hz")
                projection.stop()
                isRunning.set(false)
                stopSelf()
                return
            }
            record = fallbackRecord
            is24BitActive = false
            activePayloadSize = packetSize16
            Log.i(TAG, "Initialized 16-bit PCM AudioRecord at $captureSampleRate Hz")
        }
        this.audioRecord = record

        try {
            val session = MediaSession(this, "AudioStreamTransmitter")
            val vol = remoteVolumePercent.get()
            val provider = object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, vol) {
                override fun onAdjustVolume(direction: Int) {
                    val delta = when {
                        direction > 0 -> 5
                        direction < 0 -> -5
                        else -> 0
                    }
                    if (delta != 0) {
                        val newVol = (remoteVolumePercent.get() + delta).coerceIn(0, 100)
                        updateRemoteVolume(newVol)
                    }
                }
            }
            volumeProvider = provider
            session.setPlaybackToRemote(provider)
            val state = PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
            session.setPlaybackState(state)
            session.isActive = true
            this.mediaSession = session
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing MediaSession", e)
        }

        // Automatically silence phone speakers while preserving previous volume
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (currentVol > 0) {
                previousPhoneVolume = currentVol
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                Log.i(TAG, "Automatically silenced phone media volume (saved previous: $currentVol)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not automatically silence phone volume: ${e.message}")
        }

        streamThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            var socketToClose: DatagramSocket? = null
            try {
                val targetAddresses = parseTargetAddresses(targetIp)
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
                            if (recvPacket.length >= AudioConfig.HEADER_SIZE) {
                                val magic = ((recvBuf[0].toInt() and 0xFF) shl 8) or (recvBuf[1].toInt() and 0xFF)
                                if (magic == AudioConfig.MAGIC_HEADER.toInt()) {
                                    val flags = recvBuf[5]
                                    if (flags == AudioConfig.FLAG_DISCONNECT) {
                                        Log.i(TAG, "Received client disconnect signal from ${recvPacket.address?.hostAddress}. Pausing media.")
                                        pauseSystemMediaPlayback()
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
                var activeProfile = prefs.getString(AudioConfig.PREF_KEY_PROFILE, AudioConfig.PROFILE_MUSIC) ?: AudioConfig.PROFILE_MUSIC
                val initialInLowLatency = (activeProfile == AudioConfig.PROFILE_LOW_LATENCY)
                val rateFlag = if (captureSampleRate == AudioConfig.SAMPLE_RATE_44100) AudioConfig.FLAG_SAMPLE_RATE_44100.toInt() else 0
                var profileFlag = if (initialInLowLatency) {
                    (AudioConfig.FLAG_PROFILE_LOW_LATENCY.toInt() or rateFlag).toByte()
                } else {
                    var flag = AudioConfig.FLAG_PROFILE_MUSIC.toInt() or rateFlag
                    if (is24BitActive) {
                        flag = flag or AudioConfig.FLAG_24BIT.toInt()
                    }
                    flag.toByte()
                }

                val initialProfileDisplayName = if (initialInLowLatency) {
                    "Low Latency (Server)"
                } else if (is24BitActive) {
                    "Studio 24-bit Music (Server)"
                } else {
                    "Music (Server)"
                }

                val initialPayload = if (initialInLowLatency) packetSize16 else activePayloadSize
                sendBuffer[0] = (AudioConfig.MAGIC_HEADER.toInt() shr 8).toByte()
                sendBuffer[1] = (AudioConfig.MAGIC_HEADER.toInt() and 0xFF).toByte()
                sendBuffer[5] = profileFlag
                sendBuffer[6] = (initialPayload shr 8).toByte()
                sendBuffer[7] = (initialPayload and 0xFF).toByte()

                // XOR Forward Error Correction (FEC) setup
                val isFecEnabled = prefs.getBoolean(AudioConfig.PREF_KEY_FEC_ENABLED, true)
                val fecParityBuffer = ByteArray(AudioConfig.MAX_PACKET_SIZE)
                val fecPacketBuffer = ByteArray(AudioConfig.HEADER_SIZE + AudioConfig.MAX_PACKET_SIZE)
                val fecDatagramPacket = DatagramPacket(fecPacketBuffer, fecPacketBuffer.size)
                var fecBlockCount = 0
                var fecBaseSeq = 0
                var fecMaxPayloadLen = 0

                record.startRecording()
                Log.i(TAG, "AudioRecord recording started. Streaming 5ms chunks at $captureSampleRate Hz to ${targetAddresses.size} target(s) (Profile: $initialProfileDisplayName, Payload: $initialPayload bytes, FEC: $isFecEnabled)")

                var sequence = 0
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

                val initialBitDepth = if (is24BitActive && !initialInLowLatency) 24 else 16
                val initialBitrate = if (captureSampleRate == AudioConfig.SAMPLE_RATE_44100) {
                    if (initialBitDepth == 24) 2117 else 1411
                } else {
                    if (initialBitDepth == 24) 2304 else 1536
                }

                val initialEndpointLabel = if (targetAddresses.size > 1) {
                    "${targetAddresses.size} receivers"
                } else {
                    "$targetIp:$targetPort"
                }
                val initialStatus = if (targetAddresses.size > 1) {
                    "Multi-Unicast (${targetAddresses.size} receivers)"
                } else {
                    "Transmitting to $targetIp:$targetPort"
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
                        activeReceiversCount = targetAddresses.size
                    )
                }

                while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                    val bytesRead = record.read(sendBuffer, AudioConfig.HEADER_SIZE, activePayloadSize, AudioRecord.READ_BLOCKING)
                    if (bytesRead > 0) {
                        val currentInLowLatency = (activeProfile == AudioConfig.PROFILE_LOW_LATENCY)
                        val effectivePayloadLen: Int
                        val isEffective24: Boolean

                        if (currentInLowLatency) {
                            if (is24BitActive) {
                                // Downconvert 24-bit packed PCM to 16-bit PCM in-place for low latency mode
                                var src = AudioConfig.HEADER_SIZE
                                var dst = AudioConfig.HEADER_SIZE
                                val end = AudioConfig.HEADER_SIZE + bytesRead
                                while (src < end) {
                                    sendBuffer[dst] = sendBuffer[src + 1]
                                    sendBuffer[dst + 1] = sendBuffer[src + 2]
                                    dst += 2
                                    src += 3
                                }
                                effectivePayloadLen = dst - AudioConfig.HEADER_SIZE
                            } else {
                                effectivePayloadLen = bytesRead
                            }
                            isEffective24 = false
                            profileFlag = (AudioConfig.FLAG_PROFILE_LOW_LATENCY.toInt() or rateFlag).toByte()
                        } else {
                            effectivePayloadLen = bytesRead
                            isEffective24 = is24BitActive
                            var flag = AudioConfig.FLAG_PROFILE_MUSIC.toInt() or rateFlag
                            if (is24BitActive) {
                                flag = flag or AudioConfig.FLAG_24BIT.toInt()
                            }
                            profileFlag = flag.toByte()
                        }

                        // Compute peak amplitude of current chunk
                        var chunkPeak = 0
                        if (isEffective24) {
                            var i = AudioConfig.HEADER_SIZE
                            val end = AudioConfig.HEADER_SIZE + effectivePayloadLen
                            while (i < end - 2) {
                                val raw = (sendBuffer[i].toInt() and 0xFF) or
                                    ((sendBuffer[i + 1].toInt() and 0xFF) shl 8) or
                                    ((sendBuffer[i + 2].toInt() and 0xFF) shl 16)
                                val sample = if (raw and 0x800000 != 0) raw or 0xFF000000.toInt() else raw
                                val abs = kotlin.math.abs(sample)
                                if (abs > chunkPeak) chunkPeak = abs
                                i += 3
                            }
                        } else {
                            var i = AudioConfig.HEADER_SIZE
                            val end = AudioConfig.HEADER_SIZE + effectivePayloadLen
                            while (i < end - 1) {
                                val sample = (sendBuffer[i].toInt() and 0xFF) or (sendBuffer[i + 1].toInt() shl 8)
                                val abs = kotlin.math.abs(sample.toShort().toInt())
                                if (abs > chunkPeak) chunkPeak = abs
                                i += 2
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
                            silentPacketsCount = 0
                            isSilenceSuppressed = false
                        }

                        val now = SystemClock.elapsedRealtime()
                        val shouldSendHeartbeat = isSilenceSuppressed && (now - lastHeartbeatTime >= AudioConfig.SILENCE_HEARTBEAT_INTERVAL_MS)

                        // Transmit regular audio immediately (<5ms wakeup) or silence heartbeat at 2 pps
                        if (!isSilenceSuppressed || shouldSendHeartbeat) {
                            sendBuffer[2] = (sequence shr 8).toByte()
                            sendBuffer[3] = (sequence and 0xFF).toByte()
                            val currentSeq = sequence
                            sequence = (sequence + 1) and 0xFFFF

                            sendBuffer[4] = remoteVolumePercent.get().toByte()

                            val sendFlags = if (isSilenceSuppressed) {
                                (profileFlag.toInt() or AudioConfig.FLAG_SILENCE.toInt()).toByte()
                            } else {
                                profileFlag
                            }
                            sendBuffer[5] = sendFlags

                            if (isSilenceSuppressed) {
                                sendBuffer[6] = 0
                                sendBuffer[7] = 0
                                packet.length = AudioConfig.HEADER_SIZE
                                lastHeartbeatTime = now
                            } else {
                                sendBuffer[6] = (effectivePayloadLen shr 8).toByte()
                                sendBuffer[7] = (effectivePayloadLen and 0xFF).toByte()
                                packet.length = AudioConfig.HEADER_SIZE + effectivePayloadLen
                            }

                            for (targetAddr in targetAddresses) {
                                packet.address = targetAddr
                                packet.port = targetPort
                                socket.send(packet)
                            }

                            totalPackets++
                            totalBytes += packet.length
                            intervalPackets++
                            intervalBytes += packet.length

                            // Forward Error Correction (XOR FEC) Accumulation & Parity Dispatch
                            if (isSilenceSuppressed) {
                                fecBlockCount = 0
                                fecMaxPayloadLen = 0
                            } else if (isFecEnabled && effectivePayloadLen > 0) {
                                if (fecBlockCount == 0) {
                                    fecBaseSeq = currentSeq
                                    fecMaxPayloadLen = effectivePayloadLen
                                    System.arraycopy(sendBuffer, AudioConfig.HEADER_SIZE, fecParityBuffer, 0, effectivePayloadLen)
                                    fecBlockCount = 1
                                } else {
                                    fecMaxPayloadLen = maxOf(fecMaxPayloadLen, effectivePayloadLen)
                                    for (b in 0 until effectivePayloadLen) {
                                        fecParityBuffer[b] = (fecParityBuffer[b].toInt() xor sendBuffer[AudioConfig.HEADER_SIZE + b].toInt()).toByte()
                                    }
                                    fecBlockCount++

                                    if (fecBlockCount == AudioConfig.FEC_BLOCK_SIZE) {
                                        fecPacketBuffer[0] = (AudioConfig.MAGIC_HEADER.toInt() shr 8).toByte()
                                        fecPacketBuffer[1] = (AudioConfig.MAGIC_HEADER.toInt() and 0xFF).toByte()
                                        fecPacketBuffer[2] = (fecBaseSeq shr 8).toByte()
                                        fecPacketBuffer[3] = (fecBaseSeq and 0xFF).toByte()
                                        fecPacketBuffer[4] = AudioConfig.FEC_BLOCK_SIZE.toByte()
                                        fecPacketBuffer[5] = (profileFlag.toInt() or AudioConfig.FLAG_FEC_PARITY.toInt()).toByte()
                                        fecPacketBuffer[6] = (fecMaxPayloadLen shr 8).toByte()
                                        fecPacketBuffer[7] = (fecMaxPayloadLen and 0xFF).toByte()
                                        System.arraycopy(fecParityBuffer, 0, fecPacketBuffer, AudioConfig.HEADER_SIZE, fecMaxPayloadLen)
                                        fecDatagramPacket.length = AudioConfig.HEADER_SIZE + fecMaxPayloadLen

                                        for (targetAddr in targetAddresses) {
                                            fecDatagramPacket.address = targetAddr
                                            fecDatagramPacket.port = targetPort
                                            socket.send(fecDatagramPacket)
                                        }

                                        totalPackets++
                                        totalBytes += fecDatagramPacket.length
                                        intervalPackets++
                                        intervalBytes += fecDatagramPacket.length

                                        fecBlockCount = 0
                                        fecMaxPayloadLen = 0
                                    }
                                }
                            }
                        }

                        val dt = now - lastStatsTime
                        if (dt >= 250) {
                            activeProfile = prefs.getString(AudioConfig.PREF_KEY_PROFILE, AudioConfig.PROFILE_MUSIC) ?: AudioConfig.PROFILE_MUSIC
                            val inLowLatencyNow = (activeProfile == AudioConfig.PROFILE_LOW_LATENCY)
                            val is24Now = is24BitActive && !inLowLatencyNow

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
                            val bitDepth = if (is24Now) 24 else 16
                            val bitrate = if (captureSampleRate == AudioConfig.SAMPLE_RATE_44100) {
                                if (is24Now) 2117 else 1411
                            } else {
                                if (is24Now) 2304 else 1536
                            }
                            val profileDisplayName = if (inLowLatencyNow) {
                                "Low Latency (Server)"
                            } else if (is24BitActive) {
                                "Studio 24-bit Music (Server)"
                            } else {
                                "Music (Server)"
                            }

                            val statusDetailText = if (isSilenceSuppressed) {
                                "Silence Suppressed (Standby)"
                            } else if (targetAddresses.size > 1) {
                                "Multi-Unicast (${targetAddresses.size} receivers)"
                            } else if (peakPercent > 1) {
                                "Active Audio ($bitDepth-bit)"
                            } else {
                                "Silent Stream"
                            }

                            val endpointLabel = if (targetAddresses.size > 1) {
                                "${targetAddresses.size} receivers (DAP Vol: ${remoteVolumePercent.get()}%)"
                            } else {
                                "$targetIp:$targetPort (DAP Vol: ${remoteVolumePercent.get()}%)"
                            }

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
                                    activeReceiversCount = targetAddresses.size
                                )
                            }

                            intervalPackets = 0
                            intervalBytes = 0
                            maxSampleInInterval = 0
                            lastStatsTime = now
                        }
                    } else if (bytesRead < 0) {
                        Log.e(TAG, "AudioRecord read error: $bytesRead")
                        break
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
    }

    private fun stopStreaming() {
        if (!isRunning.getAndSet(false)) {
            return
        }
        Log.i(TAG, "Stopping audio capture service")

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
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaProjection", e)
        }
        mediaProjection = null

        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaSession", e)
        }
        mediaSession = null
        volumeProvider = null

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
            val wifiLockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
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
}
