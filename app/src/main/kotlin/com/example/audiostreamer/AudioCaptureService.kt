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
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCaptureService"
        const val ACTION_START = "com.example.audiostreamer.ACTION_START_CAPTURE"
        const val ACTION_STOP = "com.example.audiostreamer.ACTION_STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val EXTRA_TARGET_IP = "EXTRA_TARGET_IP"
        const val EXTRA_TARGET_PORT = "EXTRA_TARGET_PORT"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "AudioCaptureChannel"

        val isRunning = AtomicBoolean(false)
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var udpSocket: DatagramSocket? = null
    private var streamThread: Thread? = null

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

                startServiceForeground()
                startStreaming(resultCode, resultData, targetIp, targetPort)
            }
        }
        return START_NOT_STICKY
    }

    private fun startServiceForeground() {
        val notification = buildNotification("Streaming system audio...")
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

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            Log.e(TAG, "Unable to obtain MediaProjection")
            isRunning.set(false)
            stopSelf()
            return
        }
        this.mediaProjection = projection
        projection.registerCallback(projectionCallback, null)

        // Capture all typical audio sources (Media, Games, System/Web players)
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioConfig.ENCODING)
            .setSampleRate(AudioConfig.SAMPLE_RATE)
            .setChannelMask(AudioConfig.CHANNEL_IN_MASK)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioConfig.CHANNEL_IN_MASK,
            AudioConfig.ENCODING
        )
        val bufferSize = maxOf(minBufferSize, AudioConfig.PACKET_SIZE * 4)

        val record = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed")
            projection.stop()
            isRunning.set(false)
            stopSelf()
            return
        }
        this.audioRecord = record

        streamThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            var socket: DatagramSocket? = null
            try {
                val address = InetAddress.getByName(targetIp)
                socket = DatagramSocket().apply {
                    sendBufferSize = 65536
                    broadcast = true
                }
                udpSocket = socket

                val buffer = ByteArray(AudioConfig.PACKET_SIZE)
                val packet = DatagramPacket(buffer, buffer.size, address, targetPort)

                record.startRecording()
                Log.i(TAG, "AudioRecord recording started. Streaming to $targetIp:$targetPort")

                var totalPackets = 0L
                var totalBytes = 0L
                var intervalPackets = 0
                var intervalBytes = 0
                var lastStatsTime = SystemClock.elapsedRealtime()
                var maxSampleInInterval = 0

                StreamState.update {
                    it.copy(
                        isActive = true,
                        isTransmitter = true,
                        remoteEndpoint = "$targetIp:$targetPort",
                        statusDetail = "Transmitting to $targetIp:$targetPort"
                    )
                }

                while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                    val bytesRead = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (bytesRead > 0) {
                        packet.length = bytesRead
                        socket.send(packet)

                        totalPackets++
                        totalBytes += bytesRead
                        intervalPackets++
                        intervalBytes += bytesRead

                        // Compute audio peak amplitude (16-bit PCM stereo)
                        var i = 0
                        while (i < bytesRead - 1) {
                            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                            val abs = Math.abs(sample.toShort().toInt())
                            if (abs > maxSampleInInterval) {
                                maxSampleInInterval = abs
                            }
                            i += 2
                        }

                        val now = SystemClock.elapsedRealtime()
                        val dt = now - lastStatsTime
                        if (dt >= 250) { // Update telemetry every 250ms
                            val pps = ((intervalPackets * 1000L) / dt).toInt()
                            val bps = ((intervalBytes * 1000L) / dt).toInt()
                            val peakPercent = ((maxSampleInInterval * 100) / 32768).coerceIn(0, 100)

                            StreamState.update {
                                it.copy(
                                    isActive = true,
                                    isTransmitter = true,
                                    packetsTotal = totalPackets,
                                    packetsPerSec = pps,
                                    bytesPerSec = bps,
                                    audioPeakPercent = peakPercent,
                                    remoteEndpoint = "$targetIp:$targetPort",
                                    statusDetail = if (peakPercent > 1) "Active Audio" else "Silent Stream"
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
                    socket?.close()
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
        stopStreaming()
        super.onDestroy()
        Log.d(TAG, "AudioCaptureService destroyed")
    }
}
