package com.example.audiostreamer.usb

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
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.audiostreamer.AppLogger
import com.example.audiostreamer.BuildConfig
import com.example.audiostreamer.HatDiagnostics
import com.example.audiostreamer.MainActivity
import com.example.audiostreamer.R
import com.example.audiostreamer.UserAlertCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that owns the USB PCM input path: listens for AOA accessory attach/detach,
 * opens the byte pipe, runs the [UsbPcmReceiver] state machine on a dedicated thread, and feeds
 * negotiated PCM into [UsbAudioOutput] for playback (including out to an attached USB DAC, which
 * Android routes automatically for a USAGE_MEDIA track).
 *
 * Fully additive — touches NO network code. Recovery is by construction: detach closes the pipe
 * (unblocking the pump), a re-attach starts a fresh session from HELLO, and the service stays alive
 * so the next cable reconnect renegotiates without user action (spec §1, §12).
 */
class UsbPcmService : Service() {

    companion object {
        const val TAG = "UsbPcmService"
        const val ACTION_STOP = "com.example.audiostreamer.USB_ACTION_STOP"
        const val ACTION_START = "com.example.audiostreamer.USB_ACTION_START"

        /** Internal: fires when the accessory-permission dialog (requestPermission, API 33+) is
         *  answered, so the service can retry adoption. */
        const val ACTION_ACCESSORY_PERMISSION_RESULT =
            "com.example.audiostreamer.USB_ACTION_ACCESSORY_PERMISSION_RESULT"
        private const val CHANNEL_ID = "UsbInputChannel"
        private const val NOTIFICATION_ID = 4001
        private const val RING_BYTES = 1 shl 15 // 32 KiB (~170 ms at 48k/16/2): jitter cushion only

        val isRunning = AtomicBoolean(false)
        @Volatile var currentInstance: UsbPcmService? = null

        /** Diagnostics snapshot (shared shape) when USB input is active, else null. */
        fun snapshotUsbDiagnostics(): com.example.audiostreamer.diagnostics.ReceiverDiagnosticsState? =
            currentInstance?.buildUsbDiagnostics()

        /** True once the "USB" diagnostics section has been registered for this process. */
        @Volatile private var diagnosticsRegistered = false
    }

    private lateinit var usbManager: UsbManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var transport: AoaUsbTransport? = null
    private var receiver: UsbPcmReceiver? = null
    private var ring: PcmRingBuffer? = null
    private var stats: UsbPcmStats? = null
    private var output: UsbAudioOutput? = null
    private var pumpThread: Thread? = null
    private var caps: PcmCapabilities = PcmCapabilities.fullMatrix()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val startedForeground = AtomicBoolean(false)

    /** Guards against re-showing the accessory grant dialog on every adoption poll. */
    private val grantRequested = AtomicBoolean(false)

    private var lastBytes = 0L
    private var lastFrames = 0L
    private var lastTickMs = 0L

    private val accessoryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                    @Suppress("DEPRECATION")
                    val accessory: UsbAccessory? = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
                    if (accessory != null) onAccessoryAttached(accessory)
                }
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> onAccessoryDetached()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
        isRunning.set(true)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        createNotificationChannel()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }
        ContextCompat.registerReceiver(this, accessoryReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ensureDiagnosticsSectionRegistered()

        // Adopt an accessory that is already connected when we start (late service launch).
        tryAdoptAccessory()
        // And keep watching: on some vendor ROMs the attach broadcast never reaches the app, so
        // the host may connect at any time after receiver mode armed us.
        startAdoptPoller()
    }

    /**
     * Poll for a connected accessory while the service is armed. The HiBy M300 framework does not
     * reliably deliver ACTION_USB_ACCESSORY_ATTACHED to the app, so a one-shot adopt at start is
     * not enough: receiver mode arms this service before the desktop host performs the AOA
     * handshake, and the accessory appears later.
     */
    private fun startAdoptPoller() {
        scope.launch {
            while (isRunning.get()) {
                if (transport == null && !grantRequested.get()) {
                    tryAdoptAccessory()
                }
                delay(1000L)
            }
        }
    }

    /**
     * Poll the accessory list and adopt a connected accessory if present. Used from onCreate,
     * ACTION_START and the permission-result callback — the ATTACHED broadcast may arrive before
     * the service exists, so adoption must not depend on catching a fresh event.
     */
    @Synchronized
    private fun tryAdoptAccessory() {
        try {
            @Suppress("DEPRECATION")
            val existing: Array<UsbAccessory>? = usbManager.accessoryList
            if (existing != null && existing.isNotEmpty()) {
                logTransport("connected accessory found (${existing[0].manufacturer ?: "?"})")
                onAccessoryAttached(existing[0])
            }
        } catch (e: Exception) {
            logTransport("accessoryList probe failed: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // If this is also the service's first start, honor the FGS contract briefly so the
                // stop can proceed without the did-not-start-foreground kill.
                startForegroundCompat()
                mainHandler.post { teardownSession(); stopSelf() }
                return START_NOT_STICKY
            }
            ACTION_START -> {
                // Manual enable: promote to foreground immediately (compliance) and log the state.
                startForegroundCompat()
                logTransport("input enabled — waiting for USB host")
                UsbState.update { it.copy(statusDetail = "Waiting for host") }
                // We may have been started BY the attach event (via MainActivity): adopt now.
                tryAdoptAccessory()
            }
            ACTION_ACCESSORY_PERMISSION_RESULT -> {
                // Don't trust PendingIntent fill-in (mutability/OEM quirks) — re-check live state.
                @Suppress("DEPRECATION")
                val acc = try { usbManager.accessoryList?.firstOrNull() } catch (e: Exception) { null }
                if (acc != null && usbManager.hasPermission(acc)) {
                    grantRequested.set(false)
                    logTransport("accessory permission granted")
                    onAccessoryAttached(acc)
                } else {
                    logTransport("accessory permission not granted — USB input idle")
                    UsbState.update { it.copy(statusDetail = "Permission denied") }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { unregisterReceiver(accessoryReceiver) } catch (_: Exception) {}
        teardownSession()
        scope.cancel()
        isRunning.set(false)
        currentInstance = null
        UsbState.reset()
        super.onDestroy()
    }

    // ---- Attach / detach --------------------------------------------------------------------

    @Synchronized
    private fun onAccessoryAttached(accessory: UsbAccessory) {
        // This method is reached under a startForegroundService() contract from every entry path
        // (receiver, ACTION_START, permission retry) — promote NOW, before any early return, or the
        // 10s FGS deadline kills the process mid-grant-dialog (observed on M300).
        startForegroundCompat()

        // Guard against duplicate adoption on a fresh start: onCreate and onStartCommand both
        // call this, and tearing the live session down mid-handshake races the host's negotiation
        // (its START is lost, then the app rejects every PCM frame as "PCM_DATA before START").
        // A genuine re-attach clears the transport via DETACHED or a pump EOF first, so a live
        // transport here means this accessory is already adopted - ignore the duplicate.
        if (transport?.isAlive == true) {
            logTransport("session already active - ignoring duplicate adopt")
            return
        }

        // openAccessory() requires the accessory permission. Primary grant path: the system
        // launches MainActivity from the ACCESSORY_ATTACHED filter and grants it implicitly.
        // Fallback (manual enable before any filtered attach): ask via the system dialog
        // (UsbManager.requestPermission(UsbAccessory,…) — API 33+), retried on the result PI;
        // older OSes fall back to a reconnect, which re-fires the filtered attach.
        if (!usbManager.hasPermission(accessory)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Ask at most once per attach; the adoption poll runs every second and must not
                // re-open the dialog while the user is still answering it.
                if (grantRequested.compareAndSet(false, true)) {
                    logTransport("no accessory permission yet — showing grant dialog")
                    val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    val pi = PendingIntent.getService(
                        this, 0,
                        Intent(this, UsbPcmService::class.java).setAction(ACTION_ACCESSORY_PERMISSION_RESULT),
                        piFlags
                    )
                    UsbState.update { it.copy(statusDetail = "Waiting for permission") }
                    try { usbManager.requestPermission(accessory, pi) }
                    catch (e: Exception) { logTransport("requestPermission(accessory) failed: ${e.message}") }
                }
            } else {
                logTransport("no accessory permission yet — reconnect the USB cable (attach grants it via the activity filter)")
                UsbState.update { it.copy(statusDetail = "Waiting for accessory permission") }
                UserAlertCenter.warn("USB: reconnect the cable so the accessory grant can be issued")
            }
            return
        }
        grantRequested.set(false)

        val fd = try {
            usbManager.openAccessory(accessory)
        } catch (e: Exception) {
            logTransport("openAccessory threw: ${e.message}")
            UserAlertCenter.error("USB: could not open accessory (${e.message})")
            return
        }
        if (fd == null) {
            logTransport("openAccessory returned null (handshake/permission not completed)")
            return
        }

        val aoa = AoaUsbTransport(accessory, fd)
        transport = aoa
        UsbState.transportName = aoa.accessoryDescription
        UsbState.transportState = "ATTACHED"
        startForegroundCompat()
        logTransport("host connected (${aoa.accessoryDescription})")

        // Narrow advertised capabilities to what this device can actually render (spec §3).
        caps = UsbPcmProber.narrow(PcmCapabilities.fullMatrix()) { UsbPcmProber.probeMinBufferBytes(it) }
        UsbState.advertisedCaps = caps

        val r = PcmRingBuffer(RING_BYTES)
        val s = UsbPcmStats()
        ring = r
        stats = s
        UsbState.stats = s
        output = UsbAudioOutput(r, s, ::logTransport, ::noteUnderrun)

        val rcv = UsbPcmReceiver(
            output = aoa.output,
            capabilities = { caps },
            probe = { UsbPcmProber.probeMinBufferBytes(it) },
            ring = r,
            stats = s,
            latencyProvider = { output?.bufferedMs() ?: 0 },
            audioInfo = { deviceAudioInfo() },
            verboseLogging = { HatDiagnostics.isPacketLoggingEnabled() || BuildConfig.DEBUG },
            listener = receiverListener
        )
        receiver = rcv
        lastTickMs = 0L
        UsbState.update { it.copy(connected = true, statusDetail = "Negotiating") }

        // Advertise our capabilities immediately, then start pumping inbound frames.
        rcv.sendHelloAndCapabilities()
        pumpThread = Thread({ pumpLoop(aoa.input) }, "UsbPcmPump").apply { isDaemon = true; start() }
        startTicker()
    }

    @Synchronized
    private fun onAccessoryDetached() {
        logTransport("disconnected")
        grantRequested.set(false)
        teardownSession()
        UsbState.update {
            it.copy(connected = false, streaming = false, statusDetail = "Disconnected", format = null, bytesPerSec = 0, framesPerSec = 0)
        }
        // Stay alive (START_STICKY) so a re-attach renegotiates automatically.
    }

    private val receiverListener = object : UsbPcmReceiver.Listener {
        override fun onStateChange(state: UsbPcmReceiver.State) {
            UsbState.transportState = when (state) {
                UsbPcmReceiver.State.STREAMING -> "STREAMING"
                UsbPcmReceiver.State.CONFIGURED -> "NEGOTIATED"
                UsbPcmReceiver.State.CAPABLES_SENT -> "CAPABILITIES_SENT"
                else -> "ATTACHED"
            }
        }

        override fun onConfigured(format: PcmFormat) {
            val wasStreaming = UsbState.state.value.streaming
            logTransport("configured ${format.displayLabel()}")
            output?.configure(format)
            UsbState.update {
                it.copy(format = format, statusDetail = if (wasStreaming) "Reconfigured" else "Configured")
            }
        }

        override fun onStreamStarted(format: PcmFormat) {
            logTransport("stream started ${format.displayLabel()}")
            output?.configure(format)
            output?.start()
            val s = stats
            lastBytes = s?.receivedBytes?.get() ?: 0L
            lastFrames = s?.receivedFrames?.get() ?: 0L
            lastTickMs = SystemClock.elapsedRealtime()
            s?.streamStartMs?.set(SystemClock.elapsedRealtime())
            UsbState.update { it.copy(streaming = true, format = format, statusDetail = "Receiving") }
        }

        override fun onStreamStopped() {
            logTransport("stream stopped")
            output?.stop()
            stats?.streamStartMs?.set(0L)
            UsbState.update { it.copy(streaming = false, statusDetail = "Configured (idle)") }
        }

        override fun onProtocolError(code: Int, detail: String) {
            val name = AslcProtocol.describeErrorCode(code)
            HatDiagnostics.warn("USB_PROTOCOL_ERROR", mapOf("code" to name, "detail" to detail))
            UserAlertCenter.warn("USB: $name — $detail")
        }

        override fun onLog(message: String) {
            logTransport(message.removePrefix("USB: "))
        }
    }

    // ---- Pump loop --------------------------------------------------------------------------

    private fun pumpLoop(input: InputStream) {
        val rcv = receiver ?: return
        val reason = try {
            rcv.pump(input)
        } catch (e: Exception) {
            stats?.usbTransferErrors?.incrementAndGet()
            logTransport("transport read fault: ${e.message}")
            UsbPcmReceiver.EndReason.MALFORMED
        }
        logTransport("pump ended ($reason)")
        mainHandler.post { if (reason != UsbPcmReceiver.EndReason.STOPPED) teardownSession() }
    }

    // ---- Teardown ---------------------------------------------------------------------------

    @Synchronized
    private fun teardownSession() {
        try { receiver?.requestStop() } catch (_: Exception) {}
        val th = pumpThread
        pumpThread = null
        try { th?.join(750) } catch (_: InterruptedException) {}
        try { output?.shutdown() } catch (_: Exception) {}
        try { transport?.close() } catch (_: Exception) {}
        transport = null
        receiver = null
        output = null
        ring = null
        stats = null
        UsbState.stats = null
        UsbState.transportState = "DISCONNECTED"
        if (startedForeground.compareAndSet(true, false)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    // ---- UI ticker (coroutine) --------------------------------------------------------------

    private fun startTicker() {
        scope.launch {
            while (isRunning.get() && UsbState.state.value.connected) {
                val s = stats
                val r = ring
                val nowMs = SystemClock.elapsedRealtime()
                if (s != null) {
                    if (lastTickMs == 0L) {
                        lastTickMs = nowMs
                        lastBytes = s.receivedBytes.get()
                        lastFrames = s.receivedFrames.get()
                    } else {
                        val dtMs = (nowMs - lastTickMs).coerceAtLeast(1L)
                        val b = s.receivedBytes.get()
                        val f = s.receivedFrames.get()
                        val bytesPerSec = (b - lastBytes) * 1000 / dtMs
                        val framesPerSec = (f - lastFrames) * 1000 / dtMs
                        lastBytes = b; lastFrames = f; lastTickMs = nowMs
                        val start = s.streamStartMs.get()
                        val uptime = if (start > 0) nowMs - start else 0L
                        UsbState.update {
                            it.copy(
                                bytesPerSec = bytesPerSec,
                                framesPerSec = framesPerSec,
                                bufferFillPercent = r?.fillPercent() ?: 0,
                                bufferUnderruns = s.bufferUnderruns.get(),
                                usbErrors = s.usbTransferErrors.get() + s.protocolErrors.get(),
                                uptimeMs = uptime,
                                outputDevice = currentOutputDeviceLabel()
                            )
                        }
                    }
                }
                delay(1000L)
            }
        }
    }

    // ---- Diagnostics ------------------------------------------------------------------------

    /**
     * USB-mode diagnostics in the same shape the network receiver uses, so the existing diagnostics
     * screen renders it (latency graph, ring/track queue, drift, underruns). Null until a format is
     * negotiated.
     */
    private fun buildUsbDiagnostics(): com.example.audiostreamer.diagnostics.ReceiverDiagnosticsState? {
        val st = UsbState.state.value
        val fmt = st.format ?: return null
        val s = stats ?: return null
        val r = ring
        val o = output
        val bps = fmt.sampleRate.toLong() * fmt.bytesPerFrame
        fun bytesToMs(b: Int): Int = if (bps > 0) (b.toLong() * 1000 / bps).toInt() else 0
        val fillBytes = r?.available() ?: 0
        val capBytes = r?.capacity ?: 0
        val ringMs = bytesToMs(fillBytes)
        val ringCapMs = bytesToMs(capBytes)
        val trackMs = o?.bufferedMs() ?: 0
        val slotBytes = fmt.bytesPerFrame * (fmt.sampleRate / 100).coerceAtLeast(1)
        val nativeRate = try {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        }
        return com.example.audiostreamer.diagnostics.ReceiverDiagnosticsState(
            isReceiving = st.streaming,
            sampleRate = fmt.sampleRate,
            profileName = "USB",
            estimatedPlayoutLatencyMs = (ringMs + trackMs).toFloat(),
            jitterBufferMs = ringMs.toFloat(),
            audioTrackQueuedMs = trackMs.toFloat(),
            audioTrackQueuedFrames = o?.pendingFrames() ?: 0L,
            audioTrackBufferSizeFrames = o?.bufferSizeFrames() ?: 0,
            audioTrackBufferCapacityFrames = o?.bufferCapacityFrames() ?: 0,
            bufferAvailableSlots = if (slotBytes > 0) fillBytes / slotBytes else 0,
            bufferTotalSlots = if (slotBytes > 0) capBytes / slotBytes else 0,
            bufferFillPercent = r?.fillPercent() ?: 0,
            driftCorrectionRatio = o?.driftRatio() ?: 1.0,
            underruns = s.bufferUnderruns.get(),
            framesWritten = o?.totalFramesWritten() ?: 0L,
            playbackHead = o?.playbackHeadFrames() ?: 0L,
            isUsb = true,
            trackSampleRate = fmt.sampleRate,
            trackBitDepth = fmt.bitDepth,
            deviceNativeRate = nativeRate,
            performanceMode = o?.performanceMode() ?: 0,
        )
    }

    /** The device's declared audio-output characteristics, advertised to the host (AUDIO_INFO). */
    private fun deviceAudioInfo(): AslcPayload.AudioInfo {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val rate = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
        val frames = am?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
        return AslcPayload.AudioInfo(rate, frames, 0)
    }

    private fun ensureDiagnosticsSectionRegistered() {
        if (diagnosticsRegistered) return
        HatDiagnostics.registerSection("USB") { UsbState.diagnosticsSnapshot() }
        diagnosticsRegistered = true
    }

    // ---- Output device label (host-mode DAC routing visibility, spec §10) -------------------

    private fun currentOutputDeviceLabel(): String = try {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) else emptyArray()
        val preferred = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        } ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        when (preferred?.type) {
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB DAC (${preferred.productName ?: "device"})"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            else -> "Default output"
        }
    } catch (e: Exception) {
        "unknown"
    }

    // ---- Logging (transport-plane messages are clearly "USB:" prefixed; spec §10) -----------

    private fun logTransport(msg: String) {
        val clean = msg.removePrefix("USB:").removePrefix(" ")
        // PCM hot-path logs are gated behind verbose; control/teardown always logged (spec §11).
        if (clean.contains("PCM_DATA") && !(HatDiagnostics.isPacketLoggingEnabled() || BuildConfig.DEBUG)) return
        AppLogger.i(TAG, "USB: $clean")
    }

    private fun noteUnderrun() {
        val s = stats ?: return
        HatDiagnostics.increment("usb_underruns")
        val total = s.bufferUnderruns.get()
        // Throttle the alert bus: surface a *sustained* underrun condition, not every single one.
        if (total > 0 && total % 50L == 0L) {
            UserAlertCenter.warn("USB: $total audio underruns — check host send rate / buffer size")
        }
    }

    // ---- Notification -----------------------------------------------------------------------

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.usb_input_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun startForegroundCompat() {
        if (startedForeground.get()) return
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.usb_input_notif_title))
            .setContentText(getString(R.string.usb_input_notif_text))
            .setSmallIcon(R.drawable.ic_receiver)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startedForeground.set(true)
    }
}
