package com.example.audiostreamer

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
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

class AudioSinkService : Service() {

    companion object {
        private const val TAG = "AudioSinkService"
        const val ACTION_START = "com.example.audiostreamer.ACTION_START_SINK"
        const val ACTION_STOP = "com.example.audiostreamer.ACTION_STOP_SINK"
        const val EXTRA_PORT = "EXTRA_PORT"

        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "AudioSinkChannel"

        val isRunning = AtomicBoolean(false)
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var audioTrack: AudioTrack? = null
    private var datagramSocket: DatagramSocket? = null
    private var receiverThread: Thread? = null
    private var playbackThread: Thread? = null
    private var jitterBuffer = JitterBuffer()
    private var currentRemoteVolume = 100
    private var lastSenderAddress: java.net.InetAddress? = null
    private var lastSenderPort: Int? = null
    private var lastSenderHost: String = "Transmitter"
    private var currentProfile: String = AudioConfig.PROFILE_MUSIC
    @Volatile private var currentEncoding: Int = AudioConfig.ENCODING
    @Volatile private var currentPerformanceMode: Int = AudioTrack.PERFORMANCE_MODE_LOW_LATENCY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Received ACTION_STOP")
                stopSink()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, AudioConfig.DEFAULT_PORT)
                startSink(port)
            }
        }
        return START_NOT_STICKY
    }

    @Synchronized
    private fun configureAudioTrack(encoding: Int, isLowLatency: Boolean, currentVolume: Int): AudioTrack {
        val perfMode = if (isLowLatency) AudioTrack.PERFORMANCE_MODE_LOW_LATENCY else AudioTrack.PERFORMANCE_MODE_NONE
        val existing = audioTrack
        if (existing != null && currentEncoding == encoding && currentPerformanceMode == perfMode && existing.state == AudioTrack.STATE_INITIALIZED) {
            return existing
        }

        try {
            existing?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing previous AudioTrack: ${e.message}")
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(AudioConfig.SAMPLE_RATE)
            .setChannelMask(AudioConfig.CHANNEL_OUT_MASK)
            .build()

        val minBufferSize = AudioTrack.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioConfig.CHANNEL_OUT_MASK,
            encoding
        )

        val is24 = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED)
        val packetSize = if (is24) AudioConfig.PACKET_SIZE_24BIT else AudioConfig.PACKET_SIZE_16BIT
        val bufferSize = if (isLowLatency) {
            maxOf(minBufferSize, packetSize * 16)
        } else {
            maxOf(minBufferSize * 4, packetSize * 100) // Deep buffer ~500ms
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setPerformanceMode(perfMode)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val floatVol = (currentVolume / 100.0f).coerceIn(0.0f, 1.0f)
        track.setVolume(floatVol)
        track.play()

        audioTrack = track
        currentEncoding = encoding
        currentPerformanceMode = perfMode

        Log.i(TAG, "Configured AudioTrack: encoding=$encoding, perfMode=$perfMode, bufferSize=$bufferSize")
        return track
    }

    private fun startSink(port: Int) {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "AudioSinkService is already active")
            return
        }

        startServiceForeground(port)
        acquireLocks()

        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            val prefs = getSharedPreferences("stream_prefs", Context.MODE_PRIVATE)
            currentProfile = prefs.getString(AudioConfig.PREF_KEY_PROFILE, AudioConfig.PROFILE_MUSIC) ?: AudioConfig.PROFILE_MUSIC
            jitterBuffer = JitterBuffer(currentProfile)

            val isInitialLowLatency = (currentProfile == AudioConfig.PROFILE_LOW_LATENCY)
            configureAudioTrack(AudioConfig.ENCODING, isInitialLowLatency, currentRemoteVolume)
            jitterBuffer.reset()

            // Bind UDP socket
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                receiveBufferSize = AudioConfig.SOCKET_RECEIVE_BUFFER_BYTES // 1MB OS receive buffer
                bind(InetSocketAddress(port))
            }
            datagramSocket = socket

            val localIp = NetworkUtils.getLocalIpAddress() ?: "0.0.0.0"
            StreamState.update {
                it.copy(
                    isActive = true,
                    isTransmitter = false,
                    remoteEndpoint = "Listening on $localIp:$port",
                    statusDetail = "Waiting for incoming UDP packets..."
                )
            }

            // 1. Dedicated UDP Receiver Thread
            receiverThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

                val rawBuffer = ByteArray(2048)
                val packet = DatagramPacket(rawBuffer, rawBuffer.size)

                var totalPackets = 0L
                var totalBytes = 0L
                var intervalPackets = 0
                var intervalBytes = 0
                var lastStatsTime = SystemClock.elapsedRealtime()
                var maxSampleInInterval = 0
                var lastSilencePacketTime = 0L

                while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                    try {
                        packet.length = rawBuffer.size
                        socket.receive(packet)

                        val length = packet.length
                        if (length >= AudioConfig.HEADER_SIZE) {
                            val data = packet.data
                            val offset = packet.offset

                            // Verify magic header "SA"
                            val magic = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
                            if (magic == AudioConfig.MAGIC_HEADER.toInt()) {
                                val flags = data[offset + 5]

                                // Discovery probe from transmitter: ignore on audio port (discovery is on port 50006)
                                val isDiscoveryProbe = (flags.toInt() and AudioConfig.FLAG_DISCOVERY_PROBE.toInt()) != 0
                                if (isDiscoveryProbe) {
                                    continue
                                }

                                lastSenderAddress = packet.address
                                lastSenderPort = packet.port

                                val volume = data[offset + 4].toInt() and 0xFF
                                val payloadLen = ((data[offset + 6].toInt() and 0xFF) shl 8) or (data[offset + 7].toInt() and 0xFF)

                                val isSilence = (flags.toInt() and AudioConfig.FLAG_SILENCE.toInt()) != 0
                                if (isSilence) {
                                    jitterBuffer.onSilenceHeartbeat()
                                    lastSilencePacketTime = SystemClock.elapsedRealtime()
                                } else if (payloadLen > 0) {
                                    lastSilencePacketTime = 0L
                                }

                                val isDisconnect = (flags.toInt() and AudioConfig.FLAG_DISCONNECT.toInt()) != 0
                                val isControlOnly = (flags.toInt() and AudioConfig.FLAG_CONTROL_ONLY.toInt()) != 0
                                val isServerLowLatency = (flags.toInt() and AudioConfig.FLAG_PROFILE_LOW_LATENCY.toInt()) != 0

                                // Only adapt profile and reconfigure AudioTrack on audio packets (never on control-only packets)
                                if (!isControlOnly && !isDisconnect && !isSilence) {
                                    val isServer24Bit = !isServerLowLatency && ((flags.toInt() and AudioConfig.FLAG_24BIT.toInt()) != 0 || payloadLen == AudioConfig.PACKET_SIZE_24BIT)
                                    val serverProfile = if (isServerLowLatency) AudioConfig.PROFILE_LOW_LATENCY else AudioConfig.PROFILE_MUSIC
                                    if (serverProfile != currentProfile) {
                                        currentProfile = serverProfile
                                        jitterBuffer.setProfile(serverProfile)
                                        Log.i(TAG, "Adapted client buffer to server profile: $serverProfile")
                                    }

                                    val targetEncoding = if (isServer24Bit && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        AudioFormat.ENCODING_PCM_24BIT_PACKED
                                    } else {
                                        AudioConfig.ENCODING
                                    }
                                    val targetPerfMode = if (isServerLowLatency) AudioTrack.PERFORMANCE_MODE_LOW_LATENCY else AudioTrack.PERFORMANCE_MODE_NONE
                                    // Fast-path: skip @Synchronized call if nothing has changed
                                    if (targetEncoding != currentEncoding || targetPerfMode != currentPerformanceMode) {
                                        configureAudioTrack(targetEncoding, isServerLowLatency, currentRemoteVolume)
                                    }
                                }

                                val activeTrack = audioTrack ?: configureAudioTrack(
                                    currentEncoding,
                                    currentProfile == AudioConfig.PROFILE_LOW_LATENCY,
                                    currentRemoteVolume
                                )

                                // Apply remote volume control if changed — 0ms software scaling, zero IPC, zero cutouts!
                                if (volume != currentRemoteVolume) {
                                    currentRemoteVolume = volume
                                    val floatVol = (volume / 100.0f).coerceIn(0.0f, 1.0f)
                                    activeTrack.setVolume(floatVol)
                                    Log.d(TAG, "Applied remote volume: $volume%")
                                }

                                // Cache sender host string — avoid repeated DNS reverse-lookups
                                val senderAddr = packet.address
                                if (senderAddr != null && senderAddr !== lastSenderAddress) {
                                    lastSenderAddress = senderAddr
                                    lastSenderHost = senderAddr.hostAddress ?: "Transmitter"
                                }

                                // Route PCM audio to JitterBuffer
                                if ((payloadLen == AudioConfig.PACKET_SIZE_16BIT || payloadLen == AudioConfig.PACKET_SIZE_24BIT) && !isDisconnect && !isControlOnly) {
                                    val pcmOffset = offset + AudioConfig.HEADER_SIZE
                                    val effectivePayloadLen: Int
                                    val writeData: ByteArray
                                    val writeOffset: Int

                                    if (isServerLowLatency && payloadLen == AudioConfig.PACKET_SIZE_24BIT) {
                                        // Downconvert 24-bit to 16-bit for low latency mode (safety net)
                                        val downconverted16 = ByteArray(AudioConfig.PACKET_SIZE_16BIT)
                                        var s = pcmOffset
                                        var d = 0
                                        while (s < pcmOffset + payloadLen && d < AudioConfig.PACKET_SIZE_16BIT) {
                                            downconverted16[d] = data[s + 1]
                                            downconverted16[d + 1] = data[s + 2]
                                            d += 2
                                            s += 3
                                        }
                                        writeData = downconverted16
                                        writeOffset = 0
                                        effectivePayloadLen = AudioConfig.PACKET_SIZE_16BIT
                                    } else {
                                        writeData = data
                                        writeOffset = pcmOffset
                                        effectivePayloadLen = payloadLen
                                    }

                                    jitterBuffer.write(writeData, writeOffset, effectivePayloadLen)

                                    // Compute audio peak level
                                    var i = writeOffset
                                    val end = writeOffset + effectivePayloadLen
                                    if (effectivePayloadLen == AudioConfig.PACKET_SIZE_24BIT) {
                                        while (i < end - 2) {
                                            // Correct little-endian 24-bit sign extension
                                            val raw = (writeData[i].toInt() and 0xFF) or
                                                ((writeData[i + 1].toInt() and 0xFF) shl 8) or
                                                ((writeData[i + 2].toInt() and 0xFF) shl 16)
                                            val sample = if (raw and 0x800000 != 0) raw or 0xFF000000.toInt() else raw
                                            val abs = kotlin.math.abs(sample)
                                            if (abs > maxSampleInInterval) maxSampleInInterval = abs
                                            i += 3
                                        }
                                    } else {
                                        while (i < end - 1) {
                                            val sample = (writeData[i].toInt() and 0xFF) or (writeData[i + 1].toInt() shl 8)
                                            val abs = kotlin.math.abs(sample.toShort().toInt())
                                            if (abs > maxSampleInInterval) maxSampleInInterval = abs
                                            i += 2
                                        }
                                    }
                                }

                                totalPackets++
                                totalBytes += length
                                intervalPackets++
                                intervalBytes += length
                            }
                        }

                        val now = SystemClock.elapsedRealtime()
                        val dt = now - lastStatsTime
                        if (dt >= 250) {
                            val is24 = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && currentEncoding == AudioFormat.ENCODING_PCM_24BIT_PACKED && currentProfile != AudioConfig.PROFILE_LOW_LATENCY)
                            val pps = ((intervalPackets * 1000L) / dt).toInt()
                            val bps = ((intervalBytes * 1000L) / dt).toInt()
                            val peakPercent = if (is24) {
                                ((maxSampleInInterval * 100L) / 8388608L).toInt().coerceIn(0, 100)
                            } else {
                                ((maxSampleInInterval * 100) / 32768).coerceIn(0, 100)
                            }
                            val fill = jitterBuffer.getFillLevel()
                            val usedSlots = jitterBuffer.getAvailableCount()
                            val totalSlots = jitterBuffer.getSlotCount()
                            val bitDepth = if (is24) 24 else 16
                            val bitrate = if (is24) 2304 else 1536
                            val profileName = if (currentProfile == AudioConfig.PROFILE_LOW_LATENCY) {
                                "Low Latency (Server)"
                            } else if (is24) {
                                "Studio 24-bit Music (Server)"
                            } else {
                                "Music (Server)"
                            }

                            val isSilenceSuppressed = (now - lastSilencePacketTime) < 1500L
                            val statusDetailText = if (isSilenceSuppressed) {
                                "Silent Standby (Suppressed)"
                            } else if (peakPercent > 1) {
                                "Playing Audio (Vol: $currentRemoteVolume%)"
                            } else {
                                "Receiving (Silent)"
                            }

                            StreamState.update {
                                it.copy(
                                    isActive = true,
                                    isTransmitter = false,
                                    packetsTotal = totalPackets,
                                    packetsPerSec = pps,
                                    bytesPerSec = bps,
                                    audioPeakPercent = peakPercent,
                                    remoteEndpoint = "$lastSenderHost:${packet.port} (Vol: $currentRemoteVolume%)",
                                    statusDetail = statusDetailText,
                                    bufferFillPercent = fill,
                                    bufferSlotsUsed = usedSlots,
                                    bufferSlotsTotal = totalSlots,
                                    streamProfileName = profileName,
                                    bitDepth = bitDepth,
                                    bitrateKbps = bitrate,
                                    isSilenceSuppressed = isSilenceSuppressed
                                )
                            }

                            intervalPackets = 0
                            intervalBytes = 0
                            maxSampleInInterval = 0
                            lastStatsTime = now
                        }

                    } catch (e: SocketException) {
                        Log.d(TAG, "UDP socket closed")
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in receiver loop", e)
                    }
                }
                Log.i(TAG, "UDP receiver thread finished")
            }, "UdpReceiverThread").apply {
                isDaemon = true
                start()
            }

            // 2. Dedicated AudioTrack Playback Thread
            playbackThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

                val chunk = ByteArray(AudioConfig.MAX_PACKET_SIZE)

                while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                    try {
                        val bytesToPlay = jitterBuffer.read(chunk)
                        if (bytesToPlay > 0) {
                            val track = audioTrack
                            if (track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                                track.write(chunk, 0, bytesToPlay, AudioTrack.WRITE_BLOCKING)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in playback loop", e)
                    }
                }
                Log.i(TAG, "Audio playback thread finished")
            }, "AudioPlaybackThread").apply {
                isDaemon = true
                start()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed initializing AudioSinkService", e)
            StreamState.update { it.copy(statusDetail = "Error: ${e.message}") }
            stopSink()
            stopSelf()
        }
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AudioStreamer:SinkWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiLockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager.createWifiLock(wifiLockMode, "AudioStreamer:SinkWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring system locks", e)
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }
        wakeLock = null

        try {
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WifiLock", e)
        }
        wifiLock = null
    }

    private fun startServiceForeground(port: Int) {
        val notification = buildNotification("Listening on UDP port $port...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val activityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AudioSinkService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStopIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Receiver Active")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(activityIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", pendingStopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Sink Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Receives raw audio over UDP and plays via AudioTrack"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun stopSink() {
        if (!isRunning.getAndSet(false)) {
            return
        }
        Log.i(TAG, "Stopping audio sink")

        receiverThread?.interrupt()
        playbackThread?.interrupt()
        receiverThread = null
        playbackThread = null

        // Notify transmitter phone that client is disconnecting to pause media playback
        try {
            lastSenderAddress?.let { addr ->
                val targetPort = lastSenderPort ?: AudioConfig.DEFAULT_PORT
                val disconnectBuf = ByteArray(AudioConfig.HEADER_SIZE)
                disconnectBuf[0] = (AudioConfig.MAGIC_HEADER.toInt() shr 8).toByte()
                disconnectBuf[1] = (AudioConfig.MAGIC_HEADER.toInt() and 0xFF).toByte()
                disconnectBuf[5] = AudioConfig.FLAG_DISCONNECT
                val packet = DatagramPacket(disconnectBuf, disconnectBuf.size, addr, targetPort)
                datagramSocket?.send(packet)
                Log.i(TAG, "Sent disconnect notification to transmitter $addr:$targetPort")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not send disconnect notification: ${e.message}")
        }

        try {
            datagramSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing UDP socket", e)
        }
        datagramSocket = null

        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioTrack", e)
        }
        audioTrack = null

        jitterBuffer.reset()
        releaseLocks()

        StreamState.update {
            it.copy(
                isActive = false,
                isTransmitter = false,
                audioPeakPercent = 0,
                packetsPerSec = 0,
                bytesPerSec = 0,
                statusDetail = "Stopped"
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        stopSink()
        super.onDestroy()
        Log.d(TAG, "AudioSinkService destroyed")
    }
}
