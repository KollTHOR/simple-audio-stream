package com.example.audiostreamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import com.example.audiostreamer.AppLogger as Log
import androidx.core.app.NotificationCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

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
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var audioTrack: AudioTrack? = null
    private val trackLock = Any()
    private val decoderLock = Any()
    private var datagramSocket: DatagramSocket? = null
    private var receiverThread: Thread? = null
    private var playbackThread: Thread? = null
    private var heartbeatThread: Thread? = null
    private var jitterBuffer = JitterBuffer()
    private val fecDecoder by lazy { FecDecoder(jitterBuffer) }
    private var currentRemoteVolume = 100
    private var localVolumeObserver: ContentObserver? = null
    private var lastSentLocalVolume: Int = -1
    @Volatile private var ignoreLocalVolumeUntil: Long = 0L
    private var lastSenderAddress: java.net.InetAddress? = null
    private var lastSenderPort: Int? = null
    private var lastSenderHost: String = "Transmitter"
    private var currentProfile: String = AudioConfig.PROFILE_MUSIC
    @Volatile private var currentSampleRate: Int = AudioConfig.SAMPLE_RATE_48000
    @Volatile private var currentEncoding: Int = AudioConfig.ENCODING
    @Volatile private var currentPerformanceMode: Int = AudioTrack.PERFORMANCE_MODE_NONE
    /** Performance mode the current AudioTrack was built with (what we asked the HAL for). */
    @Volatile private var requestedPerformanceMode: Int = AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
    @Volatile private var currentIsServer24Bit: Boolean = false
    @Volatile private var currentIsAac: Boolean = false
    @Volatile private var currentIsOpus: Boolean = false
    @Volatile private var aacDecoder: AacDecoder? = null
    @Volatile private var aacDecoderSampleRate: Int = AudioConfig.SAMPLE_RATE_48000
    @Volatile private var opusDecoder: OpusDecoder? = null
    @Volatile private var opusDecoderSampleRate: Int = AudioConfig.SAMPLE_RATE_48000
    private val configAuthority = StreamConfigurationAuthority()
    @Volatile private var currentStreamConfig: NegotiatedStreamConfig? = null
    @Volatile private var lastConfiguredCodec: String? = null
    private val audioLevelMeter = AudioLevelMeter(AudioConfig.CHANNELS)
    @Volatile private var currentBufferSizeInBytes = 0
    @Volatile private var currentTrackProfile: String = ""
    @Volatile private var lastDecodeDurationNs: Long = 0L
    @Volatile private var lastLatencyLogTime: Long = 0L

    // --- HAT runtime diagnostics ------------------------------------------------------------------
    private val diagRxPackets = java.util.concurrent.atomic.AtomicLong(0L)
    private val diagRxBytes = java.util.concurrent.atomic.AtomicLong(0L)
    private val diagFecRecovered = java.util.concurrent.atomic.AtomicLong(0L)
    private var diagStatsLastNs = 0L
    private var diagStatsLastPackets = 0L
    private var diagStatsLastBytes = 0L
    private var diagStatsLastUnderruns = 0
    private var diagStatsLastFecRecovered = 0L
    private var diagStatsLastDrops = 0L
    private var diagStatsLastDuplicates = 0L
    private var diagStatsLastLate = 0L
    private var diagStatsLastOutOfOrder = 0L
    private var diagStatsLastConcealed = 0L
    private var diagLastLatencyTargetMs = -1f
    @Volatile private var firstDecodeGeneration: Long = -1L
    @Volatile private var firstWriteGeneration: Long = -1L
    internal val diagAudioTrackWrites = java.util.concurrent.atomic.AtomicLong(0L)
    internal val diagFramesWritten = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var activeTestSessionId: String? = null
    @Volatile private var testSenderAddress: InetAddress? = null
    @Volatile private var testSenderPort: Int = 0
    private var testStatsThread: Thread? = null
    private val genFirstRxMs = ConcurrentHashMap<Long, Long>()
    private val genFirstDecodeMs = ConcurrentHashMap<Long, Long>()

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

    private fun applyBufferSizeForProfile(track: AudioTrack, profile: String, sampleRate: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val targetFrames = when (profile) {
                AudioConfig.PROFILE_VIDEO, AudioConfig.PROFILE_LOW_LATENCY -> {
                    (sampleRate * 35) / 1000
                }
                AudioConfig.PROFILE_AUTO -> {
                    val minWatermarkMs = if (sampleRate >= 88200) 60 else 35
                    val wmMs = jitterBuffer.getTargetWatermarkMs().toInt().coerceIn(minWatermarkMs, 400)
                    (sampleRate * wmMs) / 1000
                }
                else -> {
                    track.bufferCapacityInFrames
                }
            }
            val clampedFrames = track.setBufferSizeInFrames(targetFrames)
            val clampedMs = (clampedFrames * 1000L) / sampleRate
            Log.i(TAG, "AudioTrack setBufferSizeInFrames for profile $profile: requested=$targetFrames, actual=$clampedFrames (~${clampedMs}ms), capacity=${track.bufferCapacityInFrames} frames")
        }
    }

    /** Builds an AudioTrack for the given performance mode. Kept separate so a refused LOW_LATENCY request can
     *  be retried with PERFORMANCE_MODE_NONE without duplicating the builder call. */
    private fun buildTrack(
        attributes: AudioAttributes,
        format: AudioFormat,
        bufferSizeBytes: Int,
        performanceMode: Int
    ): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(attributes)
        .setAudioFormat(format)
        .setBufferSizeInBytes(bufferSizeBytes)
        .setPerformanceMode(performanceMode)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    @Synchronized
    private fun configureAudioTrack(sampleRate: Int, encoding: Int, profile: String, currentVolume: Int): AudioTrack {
        // HAT Phase 3.0 experiment: request the low-latency output path. Buffer sizing is deliberately
        // unchanged in this phase. This is never a hard failure: a refused request falls back to
        // PERFORMANCE_MODE_NONE and playback continues normally.
        val perfMode = AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
        val existing = audioTrack
        if (existing != null && currentSampleRate == sampleRate && currentEncoding == encoding && requestedPerformanceMode == perfMode && existing.state == AudioTrack.STATE_INITIALIZED) {
            if (currentTrackProfile != profile) {
                currentTrackProfile = profile
                applyBufferSizeForProfile(existing, profile, sampleRate)
            }
            return existing
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioConfig.CHANNEL_OUT_MASK)
            .build()

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioConfig.CHANNEL_OUT_MASK,
            encoding
        )

        val is24 = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED)
        val packetSize = AudioConfig.getPacketPayloadSize(sampleRate, is24)
        val bufferSize = when (profile) {
            AudioConfig.PROFILE_VIDEO, AudioConfig.PROFILE_LOW_LATENCY -> minBufferSize
            AudioConfig.PROFILE_AUTO -> maxOf(minBufferSize * 2, packetSize * 20)
            else -> maxOf(minBufferSize * 4, packetSize * 40)
        }

        val requestedTrack = buildTrack(audioAttributes, audioFormat, bufferSize, perfMode)
        var actualPerfMode = perfMode
        val track = if (requestedTrack.state == AudioTrack.STATE_INITIALIZED) {
            requestedTrack
        } else {
            Log.w(
                TAG,
                "AudioTrack PERFORMANCE_MODE_LOW_LATENCY creation failed (state=${requestedTrack.state}, " +
                    "sampleRate=$sampleRate, encoding=$encoding, bufferSizeBytes=$bufferSize); falling back to PERFORMANCE_MODE_NONE"
            )
            HatDiagnostics.warn(
                "PLAYBACK_PERF_MODE_FALLBACK",
                mapOf(
                    "requestedPerformanceMode" to perfMode,
                    "sampleRate" to sampleRate,
                    "encoding" to encoding,
                    "bufferSizeBytes" to bufferSize,
                    "failedState" to requestedTrack.state
                )
            )
            try { requestedTrack.release() } catch (ignored: Exception) {}
            actualPerfMode = AudioTrack.PERFORMANCE_MODE_NONE
            buildTrack(audioAttributes, audioFormat, bufferSize, actualPerfMode)
        }

        if (track.state != AudioTrack.STATE_INITIALIZED && encoding != AudioConfig.ENCODING) {
            Log.w(TAG, "AudioTrack failed to initialize with encoding $encoding at $sampleRate Hz, falling back to 16-bit PCM")
            try { track.release() } catch (ignored: Exception) {}
            return configureAudioTrack(sampleRate, AudioConfig.ENCODING, profile, currentVolume)
        }

        // Clamp AudioTrack active buffer depth via setBufferSizeInFrames
        applyBufferSizeForProfile(track, profile, sampleRate)
        currentTrackProfile = profile

        val floatVol = (currentVolume / 100.0f).coerceIn(0.0f, 1.0f)
        track.setVolume(floatVol)
        try {
            track.play()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack play failed: ${e.message}")
        }

        // Prime AudioTrack with pre-roll silence using WRITE_NON_BLOCKING so UdpReceiverThread is never blocked
        val primeBytes = when (profile) {
            AudioConfig.PROFILE_VIDEO, AudioConfig.PROFILE_LOW_LATENCY -> packetSize
            AudioConfig.PROFILE_AUTO -> packetSize * 2
            else -> packetSize * 4
        }
        val primeBuf = ByteArray(primeBytes)
        try {
            track.write(primeBuf, 0, primeBytes, AudioTrack.WRITE_NON_BLOCKING)
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack non-blocking prime failed: ${e.message}")
        }

        val oldTrack = existing
        synchronized(trackLock) {
            audioTrack = track
            currentSampleRate = sampleRate
            currentEncoding = encoding
            currentPerformanceMode = actualPerfMode
            requestedPerformanceMode = perfMode
            currentBufferSizeInBytes = bufferSize
        }

        if (oldTrack != null) {
            Thread({
                try {
                    Thread.sleep(60L) // Wait for any in-flight write in AudioPlaybackThread to cleanly exit
                    if (oldTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        oldTrack.stop()
                    }
                    oldTrack.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing previous AudioTrack: ${e.message}")
                }
            }, "OldTrackReleaser").apply {
                isDaemon = true
                start()
            }
        }

        val activeFrames = try { track.bufferSizeInFrames } catch (e: Exception) { 0 }
        val capacityFrames = try { track.bufferCapacityInFrames } catch (e: Exception) { 0 }
        Log.i(
            TAG,
            "Configured AudioTrack: requestedPerformanceMode=$perfMode, actualPerformanceMode=$actualPerfMode, " +
                "sampleRate=$sampleRate, channels=${AudioConfig.CHANNELS}, encoding=$encoding, " +
                "bufferSizeFrames=$activeFrames, bufferCapacityFrames=$capacityFrames"
        )
        HatDiagnostics.info(
            "PLAYBACK_TRACK_CONFIGURED",
            mapOf(
                "requestedPerformanceMode" to perfMode,
                "actualPerformanceMode" to actualPerfMode,
                "sampleRate" to sampleRate,
                "channels" to AudioConfig.CHANNELS,
                "encoding" to encoding,
                "bufferSizeFrames" to activeFrames,
                "bufferCapacityFrames" to capacityFrames,
                "bufferSizeBytes" to bufferSize
            )
        )
        return track
    }

    private fun applyStreamConfiguration(config: NegotiatedStreamConfig) {
        val previousGen = currentStreamConfig?.generation
        currentStreamConfig = config
        // Keep the run-wide diagnostics generation in step with the receiver's authoritative generation, so
        // every subsequent receiver event carries the correct identifier. Emits once per generation change.
        HatDiagnostics.setGeneration(
            config.generation,
            mapOf(
                "role" to "receiver",
                "profile" to config.transportProfile.latencyTarget.name,
                "logicalCodec" to config.codec.name,
                "sampleRate" to config.sampleRateHz,
                "bitDepth" to config.bitDepthBits
            )
        )
        if (previousGen != null && previousGen != config.generation) {
            Log.i(TAG, "Stream generation transition from $previousGen to ${config.generation}: resetting jitter buffer and re-anchoring timeline")
            jitterBuffer.reset()
        }
        jitterBuffer.applyConfiguration(config)

        val isIncomingOpus = config.codec == AudioCodec.OPUS
        val isIncomingAac = config.codec == AudioCodec.AAC
        currentIsAac = isIncomingAac
        currentIsOpus = isIncomingOpus
        val targetSampleRate = config.sampleRateHz

        if (isIncomingOpus) {
            if (opusDecoder == null || opusDecoderSampleRate != targetSampleRate) {
                synchronized(decoderLock) {
                    val old = opusDecoder
                    opusDecoder = OpusDecoder(targetSampleRate)
                    opusDecoderSampleRate = targetSampleRate
                    try { old?.release() } catch (ignored: Exception) {}
                }
            }
        } else if (isIncomingAac) {
            if (aacDecoder == null || aacDecoderSampleRate != targetSampleRate) {
                synchronized(decoderLock) {
                    val old = aacDecoder
                    aacDecoder = AacDecoder(targetSampleRate)
                    aacDecoderSampleRate = targetSampleRate
                    try { old?.release() } catch (ignored: Exception) {}
                }
            }
        }

        val isServer24Bit = config.is24Bit
        currentIsServer24Bit = isServer24Bit
        val serverProfile = config.transportProfile.latencyTarget.name
        val currentCodec = config.codec.name

        val profileChanged = (serverProfile != currentProfile || serverProfile != currentTrackProfile)
        val codecChanged = (currentCodec != lastConfiguredCodec)

        if (profileChanged || codecChanged) {
            currentProfile = serverProfile
            lastConfiguredCodec = currentCodec
            audioTrack?.let { applyBufferSizeForProfile(it, serverProfile, currentSampleRate) }
        }

        val targetEncoding = if (isServer24Bit && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioFormat.ENCODING_PCM_24BIT_PACKED
        } else {
            AudioConfig.ENCODING
        }
        val targetPerfMode = AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
        if (targetSampleRate != currentSampleRate || targetEncoding != currentEncoding || targetPerfMode != requestedPerformanceMode || profileChanged) {
            configureAudioTrack(targetSampleRate, targetEncoding, serverProfile, currentRemoteVolume)
            currentTrackProfile = serverProfile
        }
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
            val syncDeviceVolume = prefs.getBoolean(AudioConfig.PREF_KEY_SYNC_DEVICE_VOLUME, true)
            currentProfile = prefs.getString(AudioConfig.PREF_KEY_PROFILE, AudioConfig.PROFILE_MUSIC) ?: AudioConfig.PROFILE_MUSIC
            jitterBuffer = JitterBuffer(currentProfile)

            val initialEncoding = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioFormat.ENCODING_PCM_24BIT_PACKED
            } else {
                AudioConfig.ENCODING
            }
            configureAudioTrack(AudioConfig.SAMPLE_RATE_48000, initialEncoding, currentProfile, currentRemoteVolume)
            jitterBuffer.reset()
            registerLocalVolumeObserver()
            startDiagnosticsSession()

            // Bind UDP socket
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                receiveBufferSize = AudioConfig.SOCKET_RECEIVE_BUFFER_BYTES // 1MB OS receive buffer
                bind(InetSocketAddress(port))
            }
            datagramSocket = socket

            val localRxCaps = AudioCapabilities.getLocalPlaybackCapabilitiesMask()
            val rxCapDesc = "Android HAL: ${AudioCapabilities.describeCapabilities(localRxCaps)}"
            val initialBit = if (initialEncoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) "24-bit" else "16-bit"

            val localIp = NetworkUtils.getLocalIpAddress() ?: "0.0.0.0"
            StreamState.update {
                it.copy(
                    isActive = true,
                    isTransmitter = false,
                    remoteEndpoint = "Listening on $localIp:$port",
                    statusDetail = "Waiting for incoming UDP packets...",
                    sourceCapabilityDesc = "Pending incoming stream...",
                    receiverCapabilityDesc = rxCapDesc,
                    negotiatedFormatDesc = "48.0 kHz • $initialBit Stereo PCM"
                )
            }

            // 1. Dedicated UDP Receiver Thread
            receiverThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

                val rawBuffer = ByteArray(AudioConfig.HEADER_SIZE + AudioConfig.MAX_PACKET_SIZE)
                val packet = DatagramPacket(rawBuffer, rawBuffer.size)
                val losslessDecoder = LosslessAudioCodec(1024)
                val decompressedPcmBuf = ByteArray(AudioConfig.MAX_PACKET_SIZE)

                var totalPackets = 0L
                var totalBytes = 0L
                var intervalPackets = 0
                var intervalBytes = 0
                var lastStatsTime = SystemClock.elapsedRealtime()
                var lastSilencePacketTime = 0L
                var fecRecoveredTotal = 0L
                var smoothPps = 0f
                var smoothBps = 0f
                var lastTrackUnderrunCount = 0
                var firstRxGeneration = -1L

                while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                    try {
                        packet.length = rawBuffer.size
                        val recvStartNs = SystemClock.elapsedRealtimeNanos()
                        socket.receive(packet)
                        HatDiagnostics.recordTime("receive", SystemClock.elapsedRealtimeNanos() - recvStartNs)

                        val length = packet.length
                        val data = packet.data
                        val offset = packet.offset
                        if (length >= 2 && data[offset] == '{'.code.toByte()) {
                            val testMsg = HatTestControlMessage.parse(data, offset, length)
                            if (testMsg != null) {
                                handleTestControlMessage(testMsg, packet.address, packet.port, socket)
                                continue
                            }
                        }

                        if (length >= HatPacket.HEADER_SIZE) {
                            diagRxPackets.incrementAndGet()
                            diagRxBytes.addAndGet(length.toLong())

                            val header = HatPacket.parseHeader(data, offset, length) ?: continue

                            lastSenderAddress = packet.address
                            lastSenderPort = packet.port

                            // GENERATION_FIRST_RX: tracked with a local Long so the hot receive path allocates
                            // and formats nothing per packet.
                            if (header.generation > 0L && header.generation != firstRxGeneration) {
                                firstRxGeneration = header.generation
                                genFirstRxMs[header.generation] = System.currentTimeMillis()
                                HatDiagnostics.lifecycle("GENERATION_FIRST_RX", header.generation)
                            }

                            // Check for XOR FEC Parity packet
                            if (header.packetType == HatPacket.TYPE_FEC_PARITY) {
                                var activeConfig = configAuthority.currentConfig
                                if (activeConfig != null && header.generation > activeConfig.generation) {
                                    val newConfig = NegotiatedStreamConfig.fromHeader(header, fecEnabled = true, generation = header.generation)
                                    if (newConfig != null) {
                                        val result = configAuthority.applyUpdate(newConfig)
                                        if (result is ConfigTransitionResult.Applied) {
                                            Log.i(TAG, "CONFIG_APPLIED (in-band fec): ${newConfig.toSummaryString()}")
                                            applyStreamConfiguration(newConfig)
                                        }
                                        activeConfig = configAuthority.currentConfig
                                    }
                                }
                                if (activeConfig != null && !HatPacket.isGenerationValid(header.generation, activeConfig.generation)) {
                                    Log.w(TAG, "Dropping stale-generation FEC parity packet: packet gen=${header.generation}, active gen=${activeConfig.generation}")
                                    noteGenerationMismatch(header.generation, activeConfig.generation, "fec_parity")
                                    continue
                                }
                                val baseSeq = header.sequenceNumber
                                val blockSize = header.fecBlockSize.toInt() and 0xFF
                                val parityLen = header.payloadLength
                                if (parityLen > 0 && length >= HatPacket.HEADER_SIZE + parityLen) {
                                    val recovered = fecDecoder.decode(
                                        baseSeq = baseSeq,
                                        baseTimestamp = header.timestamp,
                                        blockSize = blockSize,
                                        parityPayload = data,
                                        parityOffset = offset + HatPacket.HEADER_SIZE,
                                        parityLen = parityLen
                                    )
                                    if (recovered) {
                                        fecRecoveredTotal++
                                        diagFecRecovered.incrementAndGet()
                                    }
                                }
                                totalPackets++
                                totalBytes += length
                                intervalPackets++
                                intervalBytes += length
                                continue
                            }

                            // Check for Disconnect signal from transmitter
                            if (header.packetType == HatPacket.TYPE_DISCONNECT) {
                                Log.i(TAG, "Received disconnect signal from transmitter: $lastSenderHost")
                                configAuthority.reset()
                                currentStreamConfig = null
                                jitterBuffer.reset()
                                continue
                            }

                            // Check for Stream Announcement / Control packet
                            if (header.packetType == HatPacket.TYPE_CONTROL) {
                                val incomingGen = header.generation
                                val announcedConfig = NegotiatedStreamConfig.fromHeader(header, fecEnabled = true, generation = incomingGen)
                                if (announcedConfig != null && incomingGen > 0L) {
                                    when (val result = configAuthority.applyUpdate(announcedConfig)) {
                                        is ConfigTransitionResult.RejectedStale -> {
                                            Log.w(TAG, "Rejected stale stream configuration generation ${result.incomingGeneration} (current: ${result.currentGeneration})")
                                            noteGenerationMismatch(result.incomingGeneration, result.currentGeneration, "control_announcement")
                                        }
                                        is ConfigTransitionResult.IdempotentIgnored -> {
                                            // Repeated announcement of identical generation: idempotent no-op
                                        }
                                        is ConfigTransitionResult.Applied -> {
                                            Log.i(TAG, "CONFIG_APPLIED: ${announcedConfig.toSummaryString()}")
                                            applyStreamConfiguration(announcedConfig)
                                        }
                                    }
                                }

                                val volume = (header.volumeOrCaps.toInt() and 0xFF).coerceIn(0, 100)
                                if (volume != currentRemoteVolume) {
                                    currentRemoteVolume = volume
                                    lastSentLocalVolume = volume
                                    val floatVol = (volume / 100.0f).coerceIn(0.0f, 1.0f)
                                    audioTrack?.setVolume(floatVol)

                                    if (syncDeviceVolume) {
                                        try {
                                            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                            val minVol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                                audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
                                            } else 0
                                            val targetStep = minVol + ((maxVol - minVol) * (volume / 100.0f)).roundToInt().coerceIn(minVol, maxVol)
                                            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != targetStep) {
                                                ignoreLocalVolumeUntil = SystemClock.elapsedRealtime() + 500L
                                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetStep, 0)
                                            }
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Failed syncing receiver hardware volume: ${e.message}")
                                        }
                                    }
                                }

                                totalPackets++
                                totalBytes += length
                                intervalPackets++
                                intervalBytes += length
                                continue
                            }

                            val isSilence = header.packetType == HatPacket.TYPE_SILENCE_HEARTBEAT
                            if (isSilence) {
                                var activeConfig = configAuthority.currentConfig
                                if (activeConfig != null && header.generation > activeConfig.generation) {
                                    Log.i(TAG, "In-band generation advance detected: silence gen=${header.generation} > active gen=${activeConfig.generation}")
                                    val newConfig = NegotiatedStreamConfig.fromHeader(header, fecEnabled = true, generation = header.generation)
                                    if (newConfig != null) {
                                        val result = configAuthority.applyUpdate(newConfig)
                                        if (result is ConfigTransitionResult.Applied) {
                                            Log.i(TAG, "CONFIG_APPLIED (in-band silence): ${newConfig.toSummaryString()}")
                                            applyStreamConfiguration(newConfig)
                                        }
                                        activeConfig = configAuthority.currentConfig
                                    }
                                }
                                if (activeConfig != null && !HatPacket.isGenerationValid(header.generation, activeConfig.generation)) {
                                    Log.w(TAG, "Dropping stale-generation silence heartbeat: packet gen=${header.generation}, active gen=${activeConfig.generation}")
                                    noteGenerationMismatch(header.generation, activeConfig.generation, "silence_heartbeat")
                                    continue
                                }
                                jitterBuffer.onSilenceHeartbeat()
                                lastSilencePacketTime = SystemClock.elapsedRealtime()
                            } else if (header.payloadLength > 0) {
                                lastSilencePacketTime = 0L
                            }

                            val isAudio = header.packetType == HatPacket.TYPE_AUDIO
                            var isIncomingOpus = false
                            var isIncomingAac = false
                            var isLossless = false

                            if (isAudio) {
                                var activeConfig = configAuthority.currentConfig
                                if (activeConfig == null) {
                                    // Initial audio packet before any announcement: establish initial generation
                                    val initialGen = if (header.generation > 0L) header.generation else 1L
                                    val initialConfig = NegotiatedStreamConfig.fromHeader(header, fecEnabled = true, generation = initialGen)
                                    if (initialConfig != null) {
                                        val result = configAuthority.applyUpdate(initialConfig)
                                        if (result is ConfigTransitionResult.Applied) {
                                            Log.i(TAG, "CONFIG_APPLIED (initial audio): ${initialConfig.toSummaryString()}")
                                            applyStreamConfiguration(initialConfig)
                                        }
                                        activeConfig = configAuthority.currentConfig
                                    } else {
                                        Log.w(TAG, "Rejected audio packet with invalid configuration: codec=${header.codec}, rate=${header.sampleRateHz}, bitDepth=${header.bitDepth}")
                                        HatDiagnostics.increment("rx_invalid_config")
                                        continue
                                    }
                                } else if (header.generation > activeConfig.generation) {
                                    // In-band stream configuration update: transmitter changed generation but announcement was delayed/dropped
                                    Log.i(TAG, "In-band generation advance detected: packet gen=${header.generation} > active gen=${activeConfig.generation}")
                                    val newConfig = NegotiatedStreamConfig.fromHeader(header, fecEnabled = true, generation = header.generation)
                                    if (newConfig != null) {
                                        val result = configAuthority.applyUpdate(newConfig)
                                        if (result is ConfigTransitionResult.Applied) {
                                            Log.i(TAG, "CONFIG_APPLIED (in-band audio): ${newConfig.toSummaryString()}")
                                            applyStreamConfiguration(newConfig)
                                        }
                                        activeConfig = configAuthority.currentConfig
                                    }
                                }

                                val currentActiveConfig = activeConfig ?: continue

                                // Generation enforcement: drop audio packets from obsolete stream generations!
                                if (!HatPacket.isGenerationValid(header.generation, currentActiveConfig.generation)) {
                                    Log.w(TAG, "Dropping stale-generation audio packet: packet gen=${header.generation}, active gen=${currentActiveConfig.generation}")
                                    noteGenerationMismatch(header.generation, currentActiveConfig.generation, "audio")
                                    continue
                                }

                                // Once generation N starts, receivers MUST keep using that configuration.
                                val agreement = currentActiveConfig.validatePacketAgreement(header)
                                if (agreement !is AgreementResult.Agreed) {
                                    Log.w(TAG, "Dropping packet incompatible with active configuration: ${(agreement as AgreementResult.Disagreement).reason}")
                                    HatDiagnostics.increment("rx_codec_mismatch")
                                    continue
                                }

                                isIncomingOpus = currentActiveConfig.codec == AudioCodec.OPUS
                                isIncomingAac = currentActiveConfig.codec == AudioCodec.AAC
                                isLossless = (header.codec == HatPacket.CODEC_LOSSLESS_PCM)
                            }

                            val activeTrack = audioTrack ?: configureAudioTrack(
                                currentSampleRate,
                                currentEncoding,
                                currentProfile,
                                currentRemoteVolume
                            )

                            // Apply remote volume control if changed -- 0ms software scaling, zero IPC, zero cutouts!
                            val volume = (header.volumeOrCaps.toInt() and 0xFF).coerceIn(0, 100)
                            if (volume != currentRemoteVolume) {
                                currentRemoteVolume = volume
                                lastSentLocalVolume = volume
                                val floatVol = (volume / 100.0f).coerceIn(0.0f, 1.0f)
                                activeTrack.setVolume(floatVol)

                                if (syncDeviceVolume) {
                                    try {
                                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                        val minVol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                            audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
                                        } else 0
                                        val targetStep = minVol + ((maxVol - minVol) * (volume / 100.0f)).roundToInt().coerceIn(minVol, maxVol)
                                        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != targetStep) {
                                            ignoreLocalVolumeUntil = SystemClock.elapsedRealtime() + 500L
                                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetStep, 0)
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed syncing receiver hardware volume: ${e.message}")
                                    }
                                }
                                Log.d(TAG, "Applied remote volume: $volume%")
                            }

                            // Cache sender host string -- avoid repeated DNS reverse-lookups
                            val senderAddr = packet.address
                            if (senderAddr != null && senderAddr !== lastSenderAddress) {
                                lastSenderAddress = senderAddr
                                lastSenderHost = senderAddr.hostAddress ?: "Transmitter"
                            }

                            // Route PCM, Opus, or AAC audio to JitterBuffer
                            val payloadLen = header.payloadLength
                            val isPcmPayload = isAudio && !isIncomingOpus && !isIncomingAac && payloadLen > 0 && payloadLen <= AudioConfig.MAX_PACKET_SIZE
                            val isCompressedPayload = isAudio && (isIncomingOpus || isIncomingAac) && payloadLen > 0
                            if (isPcmPayload || isCompressedPayload) {
                                val pcmOffset = offset + HatPacket.HEADER_SIZE
                                val effectivePayloadLen: Int
                                val writeData: ByteArray
                                val writeOffset: Int

                                if (isLossless && !isCompressedPayload) {
                                    val is24 = currentIsServer24Bit
                                    val decompLen = losslessDecoder.decode(
                                        comp = data,
                                        offset = pcmOffset,
                                        length = payloadLen,
                                        is24Bit = is24,
                                        out = decompressedPcmBuf,
                                        outOffset = 0
                                    )
                                    if (decompLen > 0) {
                                        writeData = decompressedPcmBuf
                                        writeOffset = 0
                                        effectivePayloadLen = decompLen
                                    } else {
                                        writeData = data
                                        writeOffset = pcmOffset
                                        effectivePayloadLen = payloadLen
                                    }
                                } else {
                                    writeData = data
                                    writeOffset = pcmOffset
                                    effectivePayloadLen = payloadLen
                                }

                                jitterBuffer.write(header.sequenceNumber, header.timestamp, writeData, writeOffset, effectivePayloadLen)
                            }

                            totalPackets++
                            totalBytes += length
                            intervalPackets++
                            intervalBytes += length
                        }

                        val now = SystemClock.elapsedRealtime()
                        val dt = now - lastStatsTime
                        if (dt >= 250) {
                            val is24 = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && currentEncoding == AudioFormat.ENCODING_PCM_24BIT_PACKED)
                            val instantPps = ((intervalPackets * 1000L) / dt).toFloat()
                            val instantBps = ((intervalBytes * 1000L) / dt).toFloat()
                            smoothPps = if (smoothPps == 0f) instantPps else (smoothPps * 0.7f + instantPps * 0.3f)
                            smoothBps = if (smoothBps == 0f) instantBps else (smoothBps * 0.7f + instantBps * 0.3f)
                            val pps = smoothPps.toInt()
                            val bps = smoothBps.toInt()
                            val intervalPeak = audioLevelMeter.getAndResetIntervalPeak()
                            val peakPercent = AudioLevelMeter.calculatePeakPercent(intervalPeak, is24)
                            val fill = jitterBuffer.getFillLevel()
                            val usedSlots = jitterBuffer.getAvailableCount()
                            val totalSlots = jitterBuffer.getSlotCount()
                            val bitDepth = if (currentIsAac || currentIsOpus) 16 else if (is24) 24 else 16
                            val bitrate = if (currentIsOpus) {
                                320
                            } else if (currentIsAac) {
                                192
                            } else {
                                ((currentSampleRate.toLong() * 2 * (if (is24) 24 else 16)) / 1000).toInt()
                            }
                            val srKhz = currentSampleRate / 1000.0
                            val srStr = if (currentSampleRate % 1000 == 0) "${currentSampleRate / 1000}k" else String.format(Locale.US, "%.1fk", srKhz)
                            val profileName = if (currentIsOpus) {
                                "Low Latency (Opus 320k)"
                            } else if (currentIsAac) {
                                "Low Latency (AAC 192k)"
                            } else if (currentProfile == AudioConfig.PROFILE_VIDEO || currentProfile == AudioConfig.PROFILE_LOW_LATENCY) {
                                if (is24) "Low Latency 20ms 24-bit (Server)" else "Low Latency 20ms PCM (Server)"
                            } else if (currentProfile == AudioConfig.PROFILE_AUTO) {
                                val targetWatermark = jitterBuffer.getTargetWatermarkMs()
                                "Auto Adaptive (${targetWatermark.toInt()}ms / $srStr)"
                            } else if (is24) {
                                "Uncapped Music 24-bit $srStr (Server)"
                            } else {
                                "Uncapped Music 16-bit $srStr (Server)"
                            }

                            val isSilenceSuppressed = (now - lastSilencePacketTime) < 1500L
                            val statusDetailText = if (isSilenceSuppressed) {
                                "Silent Standby (Suppressed)"
                            } else if (peakPercent > 1) {
                                "Playing Audio (Vol: $currentRemoteVolume%)"
                            } else {
                                "Receiving (Silent)"
                            }

                            val negotiatedDesc = if (currentIsOpus) {
                                "Opus • 320 kbps • 48.0 kHz Stereo"
                            } else if (currentIsAac) {
                                "AAC • 192 kbps • 48.0 kHz Stereo"
                            } else {
                                "${currentSampleRate / 1000.0} kHz • ${bitDepth}-bit Stereo PCM"
                            }
                            val sourceCap = "Transmitter: ${currentSampleRate / 1000.0} kHz / ${bitDepth}-bit"
                            val currentRxCaps = AudioCapabilities.getLocalPlaybackCapabilitiesMask()
                            val currentRxCapDesc = "Android HAL: ${AudioCapabilities.describeCapabilities(currentRxCaps)}"

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
                                    sampleRate = currentSampleRate,
                                    bitDepth = bitDepth,
                                    bitrateKbps = bitrate,
                                    isSilenceSuppressed = isSilenceSuppressed,
                                    fecRecoveredTotal = fecRecoveredTotal,
                                    sourceCapabilityDesc = sourceCap,
                                    receiverCapabilityDesc = currentRxCapDesc,
                                    negotiatedFormatDesc = negotiatedDesc
                                )
                            }

                            if (now - lastLatencyLogTime >= 1000L) {
                                lastLatencyLogTime = now
                                val jbMs = usedSlots * 10
                                val track = audioTrack
                                val atFrames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && track != null) {
                                    track.bufferSizeInFrames
                                } else {
                                    currentBufferSizeInBytes / (2 * (if (bitDepth == 24) 3 else 2))
                                }
                                val atMs = (atFrames / (currentSampleRate / 1000.0)).toInt()
                                val underruns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && track != null) track.underrunCount else 0
                                if (underruns > lastTrackUnderrunCount && track != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    val delta = underruns - lastTrackUnderrunCount
                                    lastTrackUnderrunCount = underruns
                                    HatDiagnostics.increment("playback_underruns", delta.toLong())
                                    HatDiagnostics.warn(
                                        "PLAYBACK_UNDERRUN",
                                        mapOf(
                                            "delta" to delta,
                                            "total" to underruns,
                                            "bufferMs" to atMs,
                                            "jitterMs" to jbMs,
                                            "targetLatencyMs" to jitterBuffer.getTargetWatermarkMs(),
                                            "generation" to configAuthority.currentGeneration
                                        )
                                    )
                                    HatDiagnostics.snapshotContext(
                                        "PLAYBACK_UNDERRUN",
                                        mapOf("delta" to delta, "total" to underruns, "bufferMs" to atMs)
                                    )
                                    if (currentProfile == AudioConfig.PROFILE_LOW_LATENCY || currentProfile == AudioConfig.PROFILE_VIDEO) {
                                        val curFrames = track.bufferSizeInFrames
                                        val maxFrames = (currentSampleRate * 80) / 1000 // 80ms max safety cap
                                        if (curFrames < maxFrames) {
                                            val step = (currentSampleRate * 10) / 1000 // +10ms step
                                            val newFrames = minOf(curFrames + step, maxFrames)
                                            val res = track.setBufferSizeInFrames(newFrames)
                                            Log.w(TAG, "AudioTrack underrun detected (+$delta). Dynamically expanded buffer to $res frames (~${(res * 1000L) / currentSampleRate}ms)")
                                        }
                                    }
                                } else if (underruns < lastTrackUnderrunCount) {
                                    lastTrackUnderrunCount = underruns
                                }
                                // Per-second latency detail is reported structurally by the diagnostics
                                // JITTER_STATS / RX_STATS / PLAYBACK_STATS events instead of a formatted log line.
                            }

                            intervalPackets = 0
                            intervalBytes = 0
                            lastStatsTime = now
                        }

                    } catch (e: SocketException) {
                        Log.d(TAG, "UDP socket closed")
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in receiver loop", e)
                        HatDiagnostics.increment("rx_loop_errors")
                        HatDiagnostics.error("THREAD_FAILURE", mapOf("thread" to "UdpReceiverThread"), e)
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
                val pcmTruncateBuf = ByteArray(AudioConfig.MAX_PACKET_SIZE)
                var lastPlayedSampleLeft16: Short = 0
                var lastPlayedSampleRight16: Short = 0
                var wasInSilenceFill = false

                while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                    try {
                        val bytesToPlay = jitterBuffer.read(chunk)
                        if (bytesToPlay > 0) {
                            val track = audioTrack
                            if (track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                                if (currentIsOpus) {
                                     val isSilenceFill = (bytesToPlay < 8) || (chunk[0] == 0.toByte() && chunk[1] == 0.toByte() && chunk[2] == 0.toByte())
                                     if (!isSilenceFill) {
                                         val t0 = SystemClock.elapsedRealtimeNanos()
                                         val pcmList = synchronized(decoderLock) {
                                             opusDecoder?.decode(chunk, 0, bytesToPlay)
                                         } ?: emptyList()
                                         lastDecodeDurationNs = SystemClock.elapsedRealtimeNanos() - t0
                                         HatDiagnostics.recordTime("decode", lastDecodeDurationNs)
                                         val decodeGeneration = configAuthority.currentGeneration
                                         if (decodeGeneration > 0L && decodeGeneration != firstDecodeGeneration) {
                                             firstDecodeGeneration = decodeGeneration
                                             genFirstDecodeMs[decodeGeneration] = System.currentTimeMillis()
                                             HatDiagnostics.lifecycle("GENERATION_FIRST_DECODE", decodeGeneration)
                                         }
                                         for (i in pcmList.indices) {
                                             val pcm = pcmList[i]
                                             if (pcm.isNotEmpty()) {
                                                 if (wasInSilenceFill && i == 0) {
                                                     val rampFrames = minOf(64, pcm.size / 4)
                                                     for (f in 0 until rampFrames) {
                                                         val factor = f.toFloat() / rampFrames.toFloat()
                                                         val p = f * 4
                                                         val sL = (((pcm[p].toInt() and 0xFF) or (pcm[p + 1].toInt() shl 8)).toShort() * factor).toInt().toShort()
                                                         val sR = (((pcm[p + 2].toInt() and 0xFF) or (pcm[p + 3].toInt() shl 8)).toShort() * factor).toInt().toShort()
                                                         pcm[p] = (sL.toInt() and 0xFF).toByte()
                                                         pcm[p + 1] = ((sL.toInt() shr 8) and 0xFF).toByte()
                                                         pcm[p + 2] = (sR.toInt() and 0xFF).toByte()
                                                         pcm[p + 3] = ((sR.toInt() shr 8) and 0xFF).toByte()
                                                     }
                                                     wasInSilenceFill = false
                                                 }

                                         writeToPlaybackTrack(track, pcm, pcm.size)
                                         audioLevelMeter.analyze(pcm, 0, pcm.size, is24Bit = false)
                                             }
                                         }
                                         val lastPcm = pcmList.lastOrNull { it.size >= 4 }
                                         if (lastPcm != null) {
                                             val lastOff = lastPcm.size - 4
                                             lastPlayedSampleLeft16 = ((lastPcm[lastOff].toInt() and 0xFF) or (lastPcm[lastOff + 1].toInt() shl 8)).toShort()
                                             lastPlayedSampleRight16 = ((lastPcm[lastOff + 2].toInt() and 0xFF) or (lastPcm[lastOff + 3].toInt() shl 8)).toShort()
                                             wasInSilenceFill = false
                                         }
                                     } else {
                                         val silenceBytes = if (currentSampleRate == AudioConfig.SAMPLE_RATE_44100) 3528 else 3840
                                         val silence = ByteArray(silenceBytes)
                                         if (!wasInSilenceFill && (lastPlayedSampleLeft16 != 0.toShort() || lastPlayedSampleRight16 != 0.toShort())) {
                                             val rampFrames = minOf(64, silenceBytes / 4)
                                             for (f in 0 until rampFrames) {
                                                 val factor = (rampFrames - f).toFloat() / rampFrames.toFloat()
                                                 val sL = (lastPlayedSampleLeft16 * factor).toInt().toShort()
                                                 val sR = (lastPlayedSampleRight16 * factor).toInt().toShort()
                                                 val p = f * 4
                                                 silence[p] = (sL.toInt() and 0xFF).toByte()
                                                 silence[p + 1] = ((sL.toInt() shr 8) and 0xFF).toByte()
                                                 silence[p + 2] = (sR.toInt() and 0xFF).toByte()
                                                 silence[p + 3] = ((sR.toInt() shr 8) and 0xFF).toByte()
                                             }
                                         }
                                         lastPlayedSampleLeft16 = 0
                                         lastPlayedSampleRight16 = 0
                                         wasInSilenceFill = true
                                         writeToPlaybackTrack(track, silence, silence.size)
                                     }
                                 } else if (currentIsAac) {
                                     val isAdts = (bytesToPlay >= 7 && (chunk[0].toInt() and 0xFF) == 0xFF && (chunk[1].toInt() and 0xF0) == 0xF0)
                                     if (isAdts) {
                                         val t0 = SystemClock.elapsedRealtimeNanos()
                                         val pcmList = synchronized(decoderLock) {
                                             aacDecoder?.decode(chunk, 0, bytesToPlay)
                                         } ?: emptyList()
                                         lastDecodeDurationNs = SystemClock.elapsedRealtimeNanos() - t0
                                         HatDiagnostics.recordTime("decode", lastDecodeDurationNs)
                                         val decodeGeneration = configAuthority.currentGeneration
                                         if (decodeGeneration > 0L && decodeGeneration != firstDecodeGeneration) {
                                             firstDecodeGeneration = decodeGeneration
                                             genFirstDecodeMs[decodeGeneration] = System.currentTimeMillis()
                                             HatDiagnostics.lifecycle("GENERATION_FIRST_DECODE", decodeGeneration)
                                         }
                                         for (i in pcmList.indices) {
                                             val pcm = pcmList[i]
                                             if (pcm.isNotEmpty()) {
                                                 if (wasInSilenceFill && i == 0) {
                                                     val rampFrames = minOf(64, pcm.size / 4)
                                                     for (f in 0 until rampFrames) {
                                                         val factor = f.toFloat() / rampFrames.toFloat()
                                                         val p = f * 4
                                                         val sL = (((pcm[p].toInt() and 0xFF) or (pcm[p + 1].toInt() shl 8)).toShort() * factor).toInt().toShort()
                                                         val sR = (((pcm[p + 2].toInt() and 0xFF) or (pcm[p + 3].toInt() shl 8)).toShort() * factor).toInt().toShort()
                                                         pcm[p] = (sL.toInt() and 0xFF).toByte()
                                                         pcm[p + 1] = ((sL.toInt() shr 8) and 0xFF).toByte()
                                                         pcm[p + 2] = (sR.toInt() and 0xFF).toByte()
                                                         pcm[p + 3] = ((sR.toInt() shr 8) and 0xFF).toByte()
                                                     }
                                                     wasInSilenceFill = false
                                                 }
                                         writeToPlaybackTrack(track, pcm, pcm.size)
                                         audioLevelMeter.analyze(pcm, 0, pcm.size, is24Bit = false)
                                             }
                                         }
                                         val lastPcm = pcmList.lastOrNull { it.size >= 4 }
                                         if (lastPcm != null) {
                                             val lastOff = lastPcm.size - 4
                                             lastPlayedSampleLeft16 = ((lastPcm[lastOff].toInt() and 0xFF) or (lastPcm[lastOff + 1].toInt() shl 8)).toShort()
                                             lastPlayedSampleRight16 = ((lastPcm[lastOff + 2].toInt() and 0xFF) or (lastPcm[lastOff + 3].toInt() shl 8)).toShort()
                                             wasInSilenceFill = false
                                         }
                                     } else {
                                         val silence = ByteArray(4096)
                                         if (!wasInSilenceFill && (lastPlayedSampleLeft16 != 0.toShort() || lastPlayedSampleRight16 != 0.toShort())) {
                                             val rampFrames = minOf(64, silence.size / 4)
                                             for (f in 0 until rampFrames) {
                                                 val factor = (rampFrames - f).toFloat() / rampFrames.toFloat()
                                                 val sL = (lastPlayedSampleLeft16 * factor).toInt().toShort()
                                                 val sR = (lastPlayedSampleRight16 * factor).toInt().toShort()
                                                 val p = f * 4
                                                 silence[p] = (sL.toInt() and 0xFF).toByte()
                                                 silence[p + 1] = ((sL.toInt() shr 8) and 0xFF).toByte()
                                                 silence[p + 2] = (sR.toInt() and 0xFF).toByte()
                                                 silence[p + 3] = ((sR.toInt() shr 8) and 0xFF).toByte()
                                             }
                                         }
                                         lastPlayedSampleLeft16 = 0
                                         lastPlayedSampleRight16 = 0
                                         wasInSilenceFill = true
                                         writeToPlaybackTrack(track, silence, silence.size)
                                     }
                                 } else {
                                    val decodeGeneration = configAuthority.currentGeneration
                                    if (decodeGeneration > 0L && decodeGeneration != firstDecodeGeneration) {
                                        firstDecodeGeneration = decodeGeneration
                                        genFirstDecodeMs[decodeGeneration] = System.currentTimeMillis()
                                        HatDiagnostics.lifecycle("GENERATION_FIRST_DECODE", decodeGeneration)
                                    }
                                    audioLevelMeter.analyze(chunk, 0, bytesToPlay, is24Bit = currentIsServer24Bit)
                                     if (currentEncoding == AudioFormat.ENCODING_PCM_16BIT && currentIsServer24Bit && bytesToPlay >= 6) {
                                         val frames = bytesToPlay / 6
                                         val outLen = frames * 4
                                         var srcP = 0
                                         var dstP = 0
                                         for (f in 0 until frames) {
                                             pcmTruncateBuf[dstP] = chunk[srcP + 1]
                                             pcmTruncateBuf[dstP + 1] = chunk[srcP + 2]
                                             pcmTruncateBuf[dstP + 2] = chunk[srcP + 4]
                                             pcmTruncateBuf[dstP + 3] = chunk[srcP + 5]
                                             srcP += 6
                                             dstP += 4
                                         }
                                         writeToPlaybackTrack(track, pcmTruncateBuf, outLen)
                                     } else {
                                         writeToPlaybackTrack(track, chunk, bytesToPlay)
                                     }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in playback loop", e)
                        HatDiagnostics.increment("playback_write_errors")
                        HatDiagnostics.error("PLAYBACK_ERROR", mapOf("thread" to "AudioPlaybackThread"), e)
                    }
                }
                Log.i(TAG, "Audio playback thread finished")
            }, "AudioPlaybackThread").apply {
                isDaemon = true
                start()
            }

            // 3. Receiver Keep-Alive Heartbeat Thread (every 2.5s)
            heartbeatThread = Thread({
                val heartbeatBuf = ByteArray(HatPacket.HEADER_SIZE)
                HatPacket.writeHeader(
                    buffer = heartbeatBuf,
                    offset = 0,
                    header = HatPacket.Header(
                        packetType = HatPacket.TYPE_RECEIVER_HEARTBEAT,
                        volumeOrCaps = AudioCapabilities.getLocalPlaybackCapabilitiesMask().toByte(),
                        payloadLength = 0
                    )
                )

                val packet = DatagramPacket(heartbeatBuf, heartbeatBuf.size)
                while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                    try {
                        val addr = lastSenderAddress
                        val port = lastSenderPort ?: AudioConfig.DEFAULT_PORT
                        val sock = datagramSocket
                        if (addr != null && sock != null && !sock.isClosed) {
                            packet.address = addr
                            packet.port = port
                            sock.send(packet)
                        }
                        Thread.sleep(2500L)
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Error in receiver keep-alive heartbeat: ${e.message}")
                    }
                }
                Log.d(TAG, "Receiver heartbeat thread finished")
            }, "AudioSinkHeartbeat").apply {
                isDaemon = true
                start()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed initializing AudioSinkService", e)
            HatDiagnostics.error("THREAD_FAILURE", mapOf("thread" to "AudioSinkService", "stage" to "init"), e)
            StreamState.update { it.copy(statusDetail = "Error: ${e.message}") }
            stopSink()
            stopSelf()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // HAT runtime diagnostics wiring
    // ---------------------------------------------------------------------------------------------

    /** Opens the diagnostics run session for the receiver role and starts periodic RX/JITTER/PLAYBACK stats. */
    private fun startDiagnosticsSession() {
        HatDiagnostics.setMetadata(
            HatDiagnostics.Metadata(
                appVersion = BuildConfig.VERSION_NAME,
                deviceModel = Build.MODEL,
                manufacturer = Build.MANUFACTURER,
                androidVersion = Build.VERSION.RELEASE,
                apiLevel = Build.VERSION.SDK_INT,
                audioOutputDevice = "AudioTrack (media)",
                audioSampleRate = currentSampleRate,
                audioChannelConfig = if (AudioConfig.CHANNELS == 2) "stereo" else AudioConfig.CHANNELS.toString(),
                networkTransport = "UDP (unicast receive)"
            )
        )
        HatDiagnostics.startRun(
            streamId = "rx:${AudioConfig.DEFAULT_PORT}",
            runId = HatDiagnostics.newRunId(Build.MODEL)
        )
        HatDiagnostics.registerSection("CONFIG") {
            currentStreamConfig?.diagnosticFields(1) ?: emptyMap()
        }
        HatDiagnostics.registerSection("GENERATION") {
            mapOf(
                "generation" to configAuthority.currentGeneration,
                "codec" to (currentStreamConfig?.codec?.name ?: "NONE"),
                "profile" to currentProfile
            )
        }
        HatDiagnostics.registerSection("JITTER") { jitterDiagnosticsSnapshot() }
        HatDiagnostics.registerSection("PLAYBACK") { playbackDiagnosticsSnapshot() }
        HatDiagnostics.registerSection("RX") { rxDiagnosticsSnapshot() }
        HatDiagnostics.registerPeriodicTask("rxPlaybackStats") { emitReceiverStats() }
        diagStatsLastNs = System.nanoTime()
        diagStatsLastPackets = diagRxPackets.get()
        diagStatsLastBytes = diagRxBytes.get()
        HatDiagnostics.startPeriodicStats()
        HatDiagnostics.info(
            "NETWORK_CHANGED",
            mapOf(
                "role" to "receiver",
                "transport" to "UDP",
                "localIp" to (NetworkUtils.getLocalIpAddress() ?: "unknown")
            )
        )
    }

    private fun stopDiagnosticsSession() {
        HatDiagnostics.unregisterPeriodicTask("rxPlaybackStats")
        HatDiagnostics.unregisterSection("CONFIG")
        HatDiagnostics.unregisterSection("GENERATION")
        HatDiagnostics.unregisterSection("JITTER")
        HatDiagnostics.unregisterSection("PLAYBACK")
        HatDiagnostics.unregisterSection("RX")
        HatDiagnostics.stopPeriodicStats()
    }

    private fun rxDiagnosticsSnapshot(): Map<String, Any?> = linkedMapOf(
        "packetsReceived" to diagRxPackets.get(),
        "bytesReceived" to diagRxBytes.get(),
        "duplicates" to jitterBuffer.getDuplicatePackets(),
        "outOfOrder" to jitterBuffer.getOutOfOrderPackets(),
        "latePackets" to jitterBuffer.getLatePackets(),
        "lostPackets" to jitterBuffer.getConcealedPackets(),
        "fecRecovered" to diagFecRecovered.get(),
        "decodeErrors" to HatDiagnostics.counter("rx_decode_errors"),
        "unknownGeneration" to HatDiagnostics.counter("rx_generation_mismatch"),
        "unknownCodec" to HatDiagnostics.counter("rx_codec_mismatch") + HatDiagnostics.counter("rx_invalid_config")
    )

    private fun jitterDiagnosticsSnapshot(): Map<String, Any?> {
        val slots = jitterBuffer.getSlotCount()
        val available = jitterBuffer.getAvailableCount()
        val packetMs = jitterBuffer.getPacketDurationMs()
        return linkedMapOf(
            "generation" to configAuthority.currentGeneration,
            "bufferPackets" to available,
            "bufferFrames" to (available * AudioConfig.getFramesPerPacket(currentSampleRate)),
            "bufferMs" to (available * packetMs),
            "targetLatencyMs" to jitterBuffer.getTargetWatermarkMs(),
            "minLatencyMs" to (jitterBuffer.getPreRollPackets() * packetMs),
            "maxLatencyMs" to (slots * packetMs),
            "jitterMs" to jitterBuffer.getEstimatedJitterMs(),
            "estimatedDriftPpm" to ((jitterBuffer.getCorrectionRatio() - 1.0) * 1_000_000.0),
            "corrections" to ((jitterBuffer.getCorrectionRatio() - 1.0) * 1_000_000.0).toLong(),
            "drops" to jitterBuffer.getDroppedPackets(),
            "duplicates" to jitterBuffer.getDuplicatePackets(),
            "latePackets" to jitterBuffer.getLatePackets(),
            "missingPackets" to jitterBuffer.getConcealedPackets(),
            "fecRecovered" to diagFecRecovered.get()
        )
    }

    private fun playbackDiagnosticsSnapshot(): Map<String, Any?> {
        val track = audioTrack
        return linkedMapOf(
            "generation" to configAuthority.currentGeneration,
            "sampleRate" to currentSampleRate,
            "channels" to AudioConfig.CHANNELS,
            "bufferSizeFrames" to (try { track?.bufferSizeInFrames ?: 0 } catch (e: Exception) { 0 }),
            "bufferCapacityFrames" to (try { track?.bufferCapacityInFrames ?: 0 } catch (e: Exception) { 0 }),
            "availableFrames" to (try {
                val t = track
                if (t != null) t.bufferCapacityInFrames - t.bufferSizeInFrames else 0
            } catch (e: Exception) { 0 }),
            "playbackHead" to (try { track?.playbackHeadPosition ?: 0 } catch (e: Exception) { 0 }),
            "underruns" to (try { track?.underrunCount ?: 0 } catch (e: Exception) { 0 }),
            "performanceMode" to currentPerformanceMode,
            "requestedPerformanceMode" to requestedPerformanceMode,
            "playState" to (try { track?.playState ?: 0 } catch (e: Exception) { 0 }),
            "audioTrackWrites" to diagAudioTrackWrites.get(),
            "framesWritten" to diagFramesWritten.get(),
            "writeErrors" to HatDiagnostics.counter("playback_write_errors")
        )
    }

    /**
     * Periodic receiver statistics. Runs on the diagnostics thread, not on the UDP or playback threads, and
     * emits RX_STATS, JITTER_STATS / JITTER_LATENCY_CHANGE and PLAYBACK_STATS once per interval.
     */
    private fun emitReceiverStats() {
        val nowNs = System.nanoTime()
        val elapsedNs = (nowNs - diagStatsLastNs).coerceAtLeast(1L)
        val elapsedSec = elapsedNs / 1_000_000_000.0
        val packets = diagRxPackets.get()
        val bytes = diagRxBytes.get()
        val fecRecovered = diagFecRecovered.get()
        val generation = configAuthority.currentGeneration

        HatDiagnostics.stats(
            "RX_STATS",
            linkedMapOf(
                "packetsReceived" to packets,
                "bytesReceived" to bytes,
                "packetsPerSecond" to ((packets - diagStatsLastPackets) / elapsedSec).toInt(),
                "bytesPerSecond" to ((bytes - diagStatsLastBytes) / elapsedSec).toInt(),
                "duplicates" to jitterBuffer.getDuplicatePackets(),
                "outOfOrder" to jitterBuffer.getOutOfOrderPackets(),
                "latePackets" to jitterBuffer.getLatePackets(),
                "lostPackets" to jitterBuffer.getConcealedPackets(),
                "fecRecovered" to fecRecovered,
                "decodeErrors" to HatDiagnostics.counter("rx_decode_errors"),
                "unknownGeneration" to HatDiagnostics.counter("rx_generation_mismatch"),
                "unknownCodec" to HatDiagnostics.counter("rx_codec_mismatch") + HatDiagnostics.counter("rx_invalid_config"),
                "generation" to generation
            )
        )

        val drops = jitterBuffer.getDroppedPackets()
        val duplicates = jitterBuffer.getDuplicatePackets()
        val late = jitterBuffer.getLatePackets()
        val outOfOrder = jitterBuffer.getOutOfOrderPackets()
        val concealed = jitterBuffer.getConcealedPackets()
        HatDiagnostics.stats(
            "JITTER_STATS",
            linkedMapOf(
                "generation" to generation,
                "bufferPackets" to jitterBuffer.getAvailableCount(),
                "bufferMs" to (jitterBuffer.getAvailableCount() * jitterBuffer.getPacketDurationMs()),
                "targetLatencyMs" to jitterBuffer.getTargetWatermarkMs(),
                "jitterMs" to jitterBuffer.getEstimatedJitterMs(),
                "estimatedDriftPpm" to ((jitterBuffer.getCorrectionRatio() - 1.0) * 1_000_000.0),
                "corrections" to (drops + duplicates),
                "drops" to drops,
                "duplicates" to duplicates,
                "latePackets" to late,
                "missingPackets" to concealed,
                "outOfOrder" to outOfOrder,
                "fecRecovered" to fecRecovered
            )
        )

        // JITTER_LATENCY_CHANGE: only when the adaptive target latency actually moves. The latency algorithm
        // itself is untouched; this only reports the change. Computed on the diagnostics thread, never in
        // the jitter/audio path.
        val targetMs = jitterBuffer.getTargetWatermarkMs()
        if (diagLastLatencyTargetMs >= 0f && kotlin.math.abs(targetMs - diagLastLatencyTargetMs) >= 1f) {
            HatDiagnostics.info(
                "JITTER_LATENCY_CHANGE",
                linkedMapOf(
                    "oldMs" to diagLastLatencyTargetMs,
                    "newMs" to targetMs,
                    "reason" to "adaptive_watermark",
                    "jitterMs" to jitterBuffer.getEstimatedJitterMs(),
                    "lossRate" to if (packets > 0) concealed.toDouble() / packets.toDouble() else 0.0,
                    "bufferMs" to (jitterBuffer.getAvailableCount() * jitterBuffer.getPacketDurationMs())
                )
            )
        }
        diagLastLatencyTargetMs = targetMs

        val track = audioTrack
        if (track != null) {
            val underruns = try { track.underrunCount } catch (e: Exception) { 0 }
            val activeFrames = try { track.bufferSizeInFrames } catch (e: Exception) { 0 }
            val capacityFrames = try { track.bufferCapacityInFrames } catch (e: Exception) { 0 }
            val playbackHead = try { track.playbackHeadPosition } catch (e: Exception) { 0 }
            val playState = try { track.playState } catch (e: Exception) { 0 }
            HatDiagnostics.stats(
                "PLAYBACK_STATS",
                linkedMapOf(
                    "generation" to generation,
                    "sampleRate" to currentSampleRate,
                    "channels" to AudioConfig.CHANNELS,
                    "bufferSizeFrames" to activeFrames,
                    "bufferCapacityFrames" to capacityFrames,
                    "availableFrames" to (capacityFrames - activeFrames),
                    "playbackHead" to playbackHead,
                    "underruns" to underruns,
                    "performanceMode" to currentPerformanceMode,
                    "playState" to playState,
                    "writeErrors" to HatDiagnostics.counter("playback_write_errors"),
                    "framesWritten" to playbackHead
                )
            )
            diagStatsLastUnderruns = underruns
        }

        diagStatsLastNs = nowNs
        diagStatsLastPackets = packets
        diagStatsLastBytes = bytes
        diagStatsLastFecRecovered = fecRecovered
        diagStatsLastDrops = drops
        diagStatsLastDuplicates = duplicates
        diagStatsLastLate = late
        diagStatsLastOutOfOrder = outOfOrder
        diagStatsLastConcealed = concealed
    }

    /**
     * Counts a packet rejected because its generation does not match the active one. Counted for every
     * occurrence but only reported (with a diagnostic snapshot) on the first and then sparsely, so a burst of
     * stale in-flight packets cannot flood the log.
     */
    private fun noteGenerationMismatch(packetGeneration: Long, activeGeneration: Long, where: String) {
        val count = HatDiagnostics.increment("rx_generation_mismatch")
        if (count == 1L || count % 250L == 0L) {
            HatDiagnostics.info(
                "GENERATION_MISMATCH",
                mapOf(
                    "where" to where,
                    "packetGeneration" to packetGeneration,
                    "activeGeneration" to activeGeneration,
                    "count" to count
                )
            )
            HatDiagnostics.snapshotContext(
                "GENERATION_MISMATCH",
                mapOf("where" to where, "count" to count, "packetGeneration" to packetGeneration)
            )
        }
    }

    private fun writeToPlaybackTrack(track: AudioTrack, buffer: ByteArray, length: Int) {
        if (length <= 0) return
        val startNs = SystemClock.elapsedRealtimeNanos()
        try {
            val written = track.write(buffer, 0, length, AudioTrack.WRITE_BLOCKING)
            if (written > 0) {
                diagAudioTrackWrites.incrementAndGet()
                val bytesPerFrame = if (currentEncoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) 6 else 4
                diagFramesWritten.addAndGet((written / bytesPerFrame).toLong())
            }
        } finally {
            HatDiagnostics.recordTime("audioTrackWrite", SystemClock.elapsedRealtimeNanos() - startNs)
            val generation = configAuthority.currentGeneration
            if (generation > 0L && generation != firstWriteGeneration) {
                firstWriteGeneration = generation
                HatDiagnostics.lifecycle("GENERATION_FIRST_AUDIO_WRITE", generation, mapOf("frames" to (length / 4)))
                onGenerationFirstAudioWrite(generation)
            }
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

            multicastLock = wifiManager.createMulticastLock("AudioStreamer:SinkMulticastLock").apply {
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

        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MulticastLock", e)
        }
        multicastLock = null
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
            .setOnlyAlertOnce(true)
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
        HatDiagnostics.lifecycle(
            "GENERATION_STOPPED",
            configAuthority.currentGeneration,
            mapOf("reason" to "sink_stopped")
        )
        stopDiagnosticsSession()
        unregisterLocalVolumeObserver()

        val hb = heartbeatThread
        val rx = receiverThread
        val pb = playbackThread
        heartbeatThread = null
        receiverThread = null
        playbackThread = null

        hb?.interrupt()
        rx?.interrupt()
        pb?.interrupt()

        jitterBuffer.reset()

        // Notify transmitter phone that client is disconnecting to pause media playback
        try {
            lastSenderAddress?.let { addr ->
                val targetPort = lastSenderPort ?: AudioConfig.DEFAULT_PORT
                val disconnectBuf = ByteArray(HatPacket.HEADER_SIZE)
                HatPacket.writeHeader(
                    buffer = disconnectBuf,
                    offset = 0,
                    header = HatPacket.Header(
                        packetType = HatPacket.TYPE_DISCONNECT,
                        payloadLength = 0
                    )
                )
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
            hb?.join(300)
            rx?.join(500)
            pb?.join(500)
        } catch (ignored: InterruptedException) {}

        synchronized(trackLock) {
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
        }

        synchronized(decoderLock) {
            try {
                aacDecoder?.release()
            } catch (ignored: Exception) {}
            aacDecoder = null

            try {
                opusDecoder?.release()
            } catch (ignored: Exception) {}
            opusDecoder = null
        }

        configAuthority.reset()
        currentStreamConfig = null
        lastConfiguredCodec = null
        currentTrackProfile = ""
        currentIsOpus = false
        currentIsAac = false
        audioLevelMeter.resetInterval()

        jitterBuffer.reset()
        releaseLocks()

        StreamState.update {
            it.copy(
                isActive = false,
                isTransmitter = false,
                audioPeakPercent = 0,
                packetsPerSec = 0,
                bytesPerSec = 0,
                statusDetail = "Stopped",
                fecRecoveredTotal = 0L
            )
        }

        stopPeriodicTestStats()
        activeTestSessionId = null
        testSenderAddress = null
        genFirstRxMs.clear()
        genFirstDecodeMs.clear()

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

    private fun handleTestControlMessage(
        msg: HatTestControlMessage,
        senderAddress: InetAddress,
        senderPort: Int,
        socket: DatagramSocket
    ) {
        when (msg) {
            is HatTestControlMessage.AnnounceSession -> {
                activeTestSessionId = msg.testSessionId
                testSenderAddress = senderAddress
                testSenderPort = senderPort
                Log.i(TAG, "Joined HAT test session: ${msg.testSessionId} from $senderAddress:$senderPort")
                val joined = HatTestControlMessage.SessionJoined(
                    testSessionId = msg.testSessionId,
                    device = Build.MODEL,
                    appVersion = BuildConfig.VERSION_NAME,
                    generation = configAuthority.currentGeneration
                )
                sendTestControlPacket(joined, senderAddress, senderPort, socket)
                startPeriodicTestStats(msg.testSessionId, senderAddress, senderPort, socket)
            }
            is HatTestControlMessage.EndSession -> {
                if (activeTestSessionId == msg.testSessionId) {
                    Log.i(TAG, "Ended HAT test session: ${msg.testSessionId}")
                    stopPeriodicTestStats()
                    activeTestSessionId = null
                    testSenderAddress = null
                }
            }
            else -> { /* Ignore RxStats, SessionJoined, GenerationAck on receiver */ }
        }
    }

    private fun buildCurrentRxStats(sessionId: String): HatTestControlMessage.RxStats {
        val available = jitterBuffer.getAvailableCount()
        val packetMs = jitterBuffer.getPacketDurationMs()
        val track = audioTrack
        val timings = try { HatDiagnostics.timingSnapshot() } catch (e: Exception) { emptyList() }
        val rxTiming = timings.find { it.name == "receive" }
        val decTiming = timings.find { it.name == "decode" }
        val writeTiming = timings.find { it.name == "audioTrackWrite" }

        return HatTestControlMessage.RxStats(
            testSessionId = sessionId,
            generation = configAuthority.currentGeneration,
            timestamp = System.currentTimeMillis(),
            packetsReceived = diagRxPackets.get(),
            packetsLost = jitterBuffer.getConcealedPackets(),
            packetsLate = jitterBuffer.getLatePackets(),
            packetsOutOfOrder = jitterBuffer.getOutOfOrderPackets(),
            packetsDuplicate = jitterBuffer.getDuplicatePackets(),
            fecRecovered = diagFecRecovered.get(),
            decodeErrors = HatDiagnostics.counter("rx_decode_errors"),
            bufferPackets = available,
            bufferFrames = available * AudioConfig.getFramesPerPacket(currentSampleRate),
            bufferMs = (available * packetMs).toDouble(),
            targetLatencyMs = jitterBuffer.getTargetWatermarkMs().toDouble(),
            jitterMs = jitterBuffer.getEstimatedJitterMs().toDouble(),
            driftPpm = (jitterBuffer.getCorrectionRatio() - 1.0) * 1_000_000.0,
            audioTrackWrites = diagAudioTrackWrites.get(),
            framesWritten = diagFramesWritten.get(),
            underruns = (try { track?.underrunCount?.toLong() ?: 0L } catch (e: Exception) { 0L }),
            writeErrors = HatDiagnostics.counter("playback_write_errors"),
            avgReceiveMs = rxTiming?.let { it.avgNs / 1_000_000.0 },
            maxReceiveMs = rxTiming?.let { it.maxNs / 1_000_000.0 },
            avgDecodeMs = decTiming?.let { it.avgNs / 1_000_000.0 },
            maxDecodeMs = decTiming?.let { it.maxNs / 1_000_000.0 },
            avgWriteMs = writeTiming?.let { it.avgNs / 1_000_000.0 },
            maxWriteMs = writeTiming?.let { it.maxNs / 1_000_000.0 },
            playbackHead = (try { track?.playbackHeadPosition?.toLong() ?: 0L } catch (e: Exception) { 0L })
        )
    }

    private fun startPeriodicTestStats(
        sessionId: String,
        address: InetAddress,
        port: Int,
        socket: DatagramSocket
    ) {
        stopPeriodicTestStats()
        testStatsThread = Thread({
            Log.i(TAG, "Starting periodic RX_TEST_STATS thread for session $sessionId to $address:$port")
            try {
                // Send initial snapshot immediately so the test runner has baseline telemetry right at session start
                val initialStats = buildCurrentRxStats(sessionId)
                sendTestControlPacket(initialStats, address, port, socket)

                while (isRunning.get() && activeTestSessionId == sessionId && !Thread.currentThread().isInterrupted) {
                    Thread.sleep(1000L)
                    if (!isRunning.get() || activeTestSessionId != sessionId) break

                    val stats = buildCurrentRxStats(sessionId)
                    sendTestControlPacket(stats, address, port, socket)
                }
            } catch (e: InterruptedException) {
                // Thread interrupted on shutdown
            } catch (e: Exception) {
                Log.e(TAG, "Error in periodic test stats thread", e)
            }
        }, "AudioSinkTestStatsSender").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopPeriodicTestStats() {
        testStatsThread?.interrupt()
        testStatsThread = null
    }

    private fun onGenerationFirstAudioWrite(generation: Long) {
        val sessionId = activeTestSessionId ?: return
        val addr = testSenderAddress ?: return
        val port = testSenderPort
        val sock = datagramSocket ?: return
        if (sock.isClosed) return

        val ack = HatTestControlMessage.GenerationAck(
            testSessionId = sessionId,
            generation = generation,
            profile = configAuthority.currentConfig?.transportProfile?.latencyTarget?.name ?: currentProfile,
            firstRxTimestamp = genFirstRxMs[generation] ?: System.currentTimeMillis(),
            firstDecodeTimestamp = genFirstDecodeMs[generation] ?: System.currentTimeMillis(),
            firstAudioWriteTimestamp = System.currentTimeMillis()
        )
        // Send a burst of 3 packets with 10ms interval to protect against UDP loss
        Thread({
            repeat(3) {
                sendTestControlPacket(ack, addr, port, sock)
                try { Thread.sleep(10L) } catch (ignored: Exception) {}
            }
        }, "GenAckSender").apply {
            isDaemon = true
            start()
        }
    }

    private fun sendTestControlPacket(
        msg: HatTestControlMessage,
        address: InetAddress,
        port: Int,
        socket: DatagramSocket
    ) {
        try {
            val bytes = msg.toByteArray()
            val packet = DatagramPacket(bytes, bytes.size, address, port)
            socket.send(packet)
        } catch (e: Exception) {
            Log.w(TAG, "Failed sending test control packet ${msg.type}: ${e.message}")
        }
    }

    private fun registerLocalVolumeObserver() {
        unregisterLocalVolumeObserver()
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val minVol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
                } else 0
                val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val range = maxVol - minVol
                if (range > 0) {
                    lastSentLocalVolume = (((curVol - minVol).toFloat() / range) * 100).roundToInt().coerceIn(0, 100)
                }
            }

            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    checkAndSendLocalVolumeSync()
                }
            }
            contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
            localVolumeObserver = observer
            Log.d(TAG, "Receiver local volume ContentObserver registered (initial volume: $lastSentLocalVolume%)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register receiver local volume ContentObserver: ${e.message}")
        }
    }

    private fun unregisterLocalVolumeObserver() {
        localVolumeObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
                Log.d(TAG, "Receiver local volume ContentObserver unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering receiver local volume ContentObserver: ${e.message}")
            }
        }
        localVolumeObserver = null
    }

    private fun checkAndSendLocalVolumeSync() {
        if (!isRunning.get()) return
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val minVol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
            } else 0
            val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val range = maxVol - minVol
            val percent = if (range > 0) {
                (((curVol - minVol).toFloat() / range) * 100).roundToInt().coerceIn(0, 100)
            } else 100

            if (SystemClock.elapsedRealtime() < ignoreLocalVolumeUntil) {
                lastSentLocalVolume = percent
                return
            }

            if (percent != currentRemoteVolume && percent != lastSentLocalVolume) {
                Log.i(TAG, "Local receiver hardware volume changed to $curVol ($percent%), syncing to transmitter")
                lastSentLocalVolume = percent
                currentRemoteVolume = percent
                val floatVol = (percent / 100.0f).coerceIn(0.0f, 1.0f)
                audioTrack?.setVolume(floatVol)
                sendVolumeSyncDatagram(percent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking receiver local volume: ${e.message}")
        }
    }

    private fun sendVolumeSyncDatagram(volume: Int) {
        val addr = lastSenderAddress ?: return
        val port = lastSenderPort ?: AudioConfig.DEFAULT_PORT
        val sock = datagramSocket ?: return
        if (sock.isClosed) return

        try {
            val syncBuf = ByteArray(HatPacket.HEADER_SIZE)
            HatPacket.writeHeader(
                buffer = syncBuf,
                offset = 0,
                header = HatPacket.Header(
                    packetType = HatPacket.TYPE_REVERSE_VOLUME_SYNC,
                    volumeOrCaps = volume.coerceIn(0, 100).toByte(),
                    payloadLength = 0
                )
            )

            val packet = DatagramPacket(syncBuf, syncBuf.size, addr, port)
            Thread({
                try {
                    sock.send(packet)
                    Log.d(TAG, "Sent reverse volume sync to transmitter $addr:$port: $volume%")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed sending reverse volume sync: ${e.message}")
                }
            }, "VolumeSyncThread").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error preparing reverse volume sync packet: ${e.message}")
        }
    }

    private class AacDecoder(private val sampleRate: Int, private val channelCount: Int = 2) {
        private var codec: MediaCodec? = null
        private val bufferInfo = MediaCodec.BufferInfo()
        private var presentationTimeUs = 0L

        init {
            initCodec()
        }

        private fun initCodec() {
            try {
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, AudioConfig.AAC_BIT_RATE)
                    setInteger(MediaFormat.KEY_IS_ADTS, 1)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
                val csd = when (sampleRate) {
                    44100 -> byteArrayOf(0x12.toByte(), 0x10.toByte())
                    else -> byteArrayOf(0x11.toByte(), 0x90.toByte())
                }
                format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd))

                val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                decoder.configure(format, null, null, 0)
                decoder.start()
                codec = decoder
                Log.i(TAG, "Initialized AAC MediaCodec decoder: rate=$sampleRate, channels=$channelCount")
            } catch (e: Exception) {
                Log.e(TAG, "Failed initializing AAC decoder", e)
            }
        }

        fun decode(adtsData: ByteArray, offset: Int, length: Int): List<ByteArray> {
            val decoder = codec ?: return emptyList()
            if (length <= 7) return emptyList()
            val results = mutableListOf<ByteArray>()

            try {
                val inIndex = decoder.dequeueInputBuffer(2000L)
                if (inIndex >= 0) {
                    val inBuf = decoder.getInputBuffer(inIndex)
                    inBuf?.clear()
                    inBuf?.put(adtsData, offset, length)
                    val pts = presentationTimeUs
                    presentationTimeUs += 21_333L // ~1024 samples @ 48kHz
                    decoder.queueInputBuffer(inIndex, 0, length, pts, 0)
                }

                var outIndex = decoder.dequeueOutputBuffer(bufferInfo, 2000L)
                while (outIndex >= 0 || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(TAG, "AAC decoder output format changed: ${decoder.outputFormat}")
                    } else {
                        val outBuf = decoder.getOutputBuffer(outIndex)
                        val outSize = bufferInfo.size
                        if (outBuf != null && outSize > 0) {
                            val pcm = ByteArray(outSize)
                            outBuf.position(bufferInfo.offset)
                            outBuf.get(pcm, 0, outSize)
                            results.add(pcm)
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                    }
                    outIndex = decoder.dequeueOutputBuffer(bufferInfo, 0L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "AAC decode error: ${e.message}, re-initializing")
                try {
                    decoder.stop()
                    decoder.release()
                } catch (ignored: Exception) {}
                codec = null
                initCodec()
            }
            return results
        }

        fun release() {
            try {
                codec?.stop()
                codec?.release()
            } catch (ignored: Exception) {}
            codec = null
        }
    }

    private class OpusDecoder(private val sampleRate: Int, private val channelCount: Int = 2) {
        private var codec: MediaCodec? = null
        private val bufferInfo = MediaCodec.BufferInfo()
        private var presentationTimeUs = 0L

        init {
            initCodec()
        }

        private fun initCodec() {
            try {
                val format = MediaFormat.createAudioFormat(AudioConfig.OPUS_MIME_TYPE, sampleRate, channelCount).apply {
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }

                // Construct 19-byte OpusHead identification header
                val header = ByteArray(19)
                val magic = "OpusHead".toByteArray(Charsets.US_ASCII)
                System.arraycopy(magic, 0, header, 0, 8)
                header[8] = 1 // Version
                header[9] = channelCount.toByte()
                // Pre-skip = 0 for live streaming (no encoder delay discard)
                header[10] = 0
                header[11] = 0
                // Sample rate (little endian)
                header[12] = (sampleRate and 0xFF).toByte()
                header[13] = ((sampleRate shr 8) and 0xFF).toByte()
                header[14] = ((sampleRate shr 16) and 0xFF).toByte()
                header[15] = ((sampleRate shr 24) and 0xFF).toByte()
                // Gain = 0
                header[16] = 0
                header[17] = 0
                // Channel mapping family = 0
                header[18] = 0

                val csd0 = ByteBuffer.wrap(header)
                format.setByteBuffer("csd-0", csd0)

                // csd-1: Pre-skip in nanoseconds (Long, little-endian) = 0 ns
                val csd1 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0L).apply { flip() }
                format.setByteBuffer("csd-1", csd1)

                // csd-2: Seek pre-roll in nanoseconds (Long, little-endian) = 0 ns
                val csd2 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0L).apply { flip() }
                format.setByteBuffer("csd-2", csd2)

                val decoder = MediaCodec.createDecoderByType(AudioConfig.OPUS_MIME_TYPE)
                decoder.configure(format, null, null, 0)
                decoder.start()
                codec = decoder
                Log.i(TAG, "Initialized Opus MediaCodec decoder: rate=$sampleRate, channels=$channelCount")
            } catch (e: Exception) {
                Log.e(TAG, "Failed initializing Opus decoder", e)
            }
        }

        fun decode(opusData: ByteArray, offset: Int, length: Int): List<ByteArray> {
            val decoder = codec ?: return emptyList()
            if (length <= 0) return emptyList()
            val results = mutableListOf<ByteArray>()

            try {
                val inIndex = decoder.dequeueInputBuffer(2000L)
                if (inIndex >= 0) {
                    val inBuf = decoder.getInputBuffer(inIndex)
                    inBuf?.clear()
                    inBuf?.put(opusData, offset, length)
                    val pts = presentationTimeUs
                    presentationTimeUs += 20_000L // 20ms frame
                    decoder.queueInputBuffer(inIndex, 0, length, pts, 0)
                }

                var outIndex = decoder.dequeueOutputBuffer(bufferInfo, 2000L)
                while (outIndex >= 0 || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(TAG, "Opus decoder output format changed: ${decoder.outputFormat}")
                    } else {
                        val outBuf = decoder.getOutputBuffer(outIndex)
                        val outSize = bufferInfo.size
                        if (outBuf != null && outSize > 0) {
                            val pcm = ByteArray(outSize)
                            outBuf.position(bufferInfo.offset)
                            outBuf.get(pcm, 0, outSize)
                            results.add(pcm)
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                    }
                    outIndex = decoder.dequeueOutputBuffer(bufferInfo, 0L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Opus decode error: ${e.message}, re-initializing")
                try {
                    decoder.stop()
                    decoder.release()
                } catch (ignored: Exception) {}
                codec = null
                initCodec()
            }
            return results
        }

        fun release() {
            try {
                codec?.stop()
                codec?.release()
            } catch (ignored: Exception) {}
            codec = null
        }
    }
}
