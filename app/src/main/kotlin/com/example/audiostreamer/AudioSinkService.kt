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

        // Jitter pre-buffering: 3 packets = ~30ms of audio before starting playback
        private const val PREFILL_PACKET_COUNT = 3

        val isRunning = AtomicBoolean(false)
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var audioTrack: AudioTrack? = null
    private var datagramSocket: DatagramSocket? = null
    private var sinkThread: Thread? = null

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

    private fun startSink(port: Int) {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "AudioSinkService is already active")
            return
        }

        startServiceForeground(port)
        acquireLocks()

        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioConfig.ENCODING)
                .setSampleRate(AudioConfig.SAMPLE_RATE)
                .setChannelMask(AudioConfig.CHANNEL_OUT_MASK)
                .build()

            val minBufferSize = AudioTrack.getMinBufferSize(
                AudioConfig.SAMPLE_RATE,
                AudioConfig.CHANNEL_OUT_MASK,
                AudioConfig.ENCODING
            )
            val bufferSize = maxOf(minBufferSize * 2, AudioConfig.SINK_BUFFER_SIZE_BYTES * 2)

            val track = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            track.setVolume(1.0f)
            audioTrack = track

            // Bind socket allowing address reuse and broadcast
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                receiveBufferSize = 131072
                bind(InetSocketAddress(port))
            }
            datagramSocket = socket

            sinkThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

                val rawBuffer = ByteArray(AudioConfig.PACKET_SIZE * 2)
                val packet = DatagramPacket(rawBuffer, rawBuffer.size)

                Log.i(TAG, "AudioSink listening on UDP port $port...")

                var totalPackets = 0L
                var totalBytes = 0L
                var intervalPackets = 0
                var intervalBytes = 0
                var lastStatsTime = SystemClock.elapsedRealtime()
                var maxSampleInInterval = 0
                var isAudioTrackPlaying = false
                var bufferedCount = 0

                val localIp = NetworkUtils.getLocalIpAddress() ?: "0.0.0.0"
                StreamState.update {
                    it.copy(
                        isActive = true,
                        isTransmitter = false,
                        remoteEndpoint = "Listening on $localIp:$port",
                        statusDetail = "Waiting for incoming UDP packets..."
                    )
                }

                while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                    try {
                        // Reset packet length before each receive call!
                        packet.length = rawBuffer.size
                        socket.receive(packet)

                        val length = packet.length
                        if (length > 0) {
                            val senderHost = packet.address.hostAddress

                            // Write to AudioTrack buffer
                            track.write(packet.data, packet.offset, length, AudioTrack.WRITE_BLOCKING)

                            // Pre-buffer a few packets (~30ms) before initiating playback
                            // to prevent immediate underflow/crackling on hardware DACs
                            if (!isAudioTrackPlaying) {
                                bufferedCount++
                                if (bufferedCount >= PREFILL_PACKET_COUNT) {
                                    track.play()
                                    isAudioTrackPlaying = true
                                    Log.i(TAG, "AudioTrack playback started after pre-buffering")
                                }
                            }

                            totalPackets++
                            totalBytes += length
                            intervalPackets++
                            intervalBytes += length

                            // Compute peak level of received PCM data
                            var i = packet.offset
                            val end = packet.offset + length
                            while (i < end - 1) {
                                val sample = (packet.data[i].toInt() and 0xFF) or (packet.data[i + 1].toInt() shl 8)
                                val abs = Math.abs(sample.toShort().toInt())
                                if (abs > maxSampleInInterval) {
                                    maxSampleInInterval = abs
                                }
                                i += 2
                            }

                            val now = SystemClock.elapsedRealtime()
                            val dt = now - lastStatsTime
                            if (dt >= 250) {
                                val pps = ((intervalPackets * 1000L) / dt).toInt()
                                val bps = ((intervalBytes * 1000L) / dt).toInt()
                                val peakPercent = ((maxSampleInInterval * 100) / 32768).coerceIn(0, 100)

                                StreamState.update {
                                    it.copy(
                                        isActive = true,
                                        isTransmitter = false,
                                        packetsTotal = totalPackets,
                                        packetsPerSec = pps,
                                        bytesPerSec = bps,
                                        audioPeakPercent = peakPercent,
                                        remoteEndpoint = "$senderHost:${packet.port}",
                                        statusDetail = if (peakPercent > 1) "Playing Audio" else "Receiving (Silent)"
                                    )
                                }

                                intervalPackets = 0
                                intervalBytes = 0
                                maxSampleInInterval = 0
                                lastStatsTime = now
                            }
                        }
                    } catch (e: SocketException) {
                        Log.d(TAG, "UDP socket closed")
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Audio playback error in sink loop", e)
                    }
                }
                Log.i(TAG, "AudioSink playback thread ended")
            }, "AudioSinkPlayer").apply {
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
            @Suppress("DEPRECATION")
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "AudioStreamer:SinkWifiLock"
            ).apply {
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

        sinkThread?.interrupt()
        sinkThread = null

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
