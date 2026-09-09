package com.example.audiostreamer

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

object AppLogger {

    data class LogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val level: Char,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null
    )

    private const val MAX_LOG_ENTRIES = 1000
    private val logBuffer = ConcurrentLinkedQueue<LogEntry>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun addEntry(entry: LogEntry) {
        logBuffer.add(entry)
        while (logBuffer.size > MAX_LOG_ENTRIES) {
            logBuffer.poll()
        }
    }

    fun v(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.v(tag, msg, tr) else Log.v(tag, msg)
        addEntry(LogEntry(level = 'V', tag = tag, message = msg, throwable = tr))
    }

    fun d(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.d(tag, msg, tr) else Log.d(tag, msg)
        addEntry(LogEntry(level = 'D', tag = tag, message = msg, throwable = tr))
    }

    fun i(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.i(tag, msg, tr) else Log.i(tag, msg)
        addEntry(LogEntry(level = 'I', tag = tag, message = msg, throwable = tr))
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
        addEntry(LogEntry(level = 'W', tag = tag, message = msg, throwable = tr))
    }

    fun w(tag: String, tr: Throwable) {
        Log.w(tag, tr)
        addEntry(LogEntry(level = 'W', tag = tag, message = tr.message ?: tr.javaClass.simpleName, throwable = tr))
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        addEntry(LogEntry(level = 'E', tag = tag, message = msg, throwable = tr))
    }

    fun e(tag: String, tr: Throwable) {
        val msg = tr.message ?: tr.javaClass.simpleName
        Log.e(tag, msg, tr)
        addEntry(LogEntry(level = 'E', tag = tag, message = msg, throwable = tr))
    }

    fun getStackTraceString(tr: Throwable?): String = Log.getStackTraceString(tr)

    fun clear() {
        logBuffer.clear()
    }

    fun getEntries(): List<LogEntry> = logBuffer.toList()

    fun captureSystemLogcat(maxLines: Int = 300): List<String> {
        val lines = mutableListOf<String>()
        try {
            val pid = Process.myPid()
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "--pid=$pid"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            val ring = ArrayDeque<String>(maxLines)
            while (reader.readLine().also { line = it } != null) {
                line?.let {
                    if (ring.size >= maxLines) ring.removeFirst()
                    ring.addLast(it)
                }
            }
            reader.close()
            process.waitFor()
            lines.addAll(ring)
        } catch (e: Exception) {
            lines.add("Failed to capture system logcat: ${e.message}")
        }
        return lines
    }

    fun buildDiagnosticReport(context: Context, includeSystemLogcat: Boolean = true, sessionTime: Long? = null): String {
        val sb = StringBuilder()
        val appVersionName = BuildConfig.VERSION_NAME
        val appVersionCode = BuildConfig.VERSION_CODE
        val pkgName = context.packageName

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val nativeSampleRate = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: "Unknown"
        val nativeBufferSize = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) ?: "Unknown"
        val hasLowLatencyFeature = context.packageManager.hasSystemFeature("android.hardware.audio.low_latency")
        val hasProAudioFeature = context.packageManager.hasSystemFeature("android.hardware.audio.pro")

        val ip = NetworkUtils.getLocalIpAddress() ?: "Unavailable"
        val tel = StreamState.telemetry.value

        val genDate = Date(sessionTime ?: System.currentTimeMillis())
        val genSuffix = if (sessionTime != null) " (Live Monitoring)" else ""

        sb.appendLine("==================================================")
        sb.appendLine("SIMPLE AUDIO STREAM - DIAGNOSTIC REPORT")
        sb.appendLine("Generated: ${dateFormat.format(genDate)}$genSuffix")
        sb.appendLine("==================================================")
        sb.appendLine()
        sb.appendLine("[APPLICATION]")
        sb.appendLine("Package: $pkgName")
        sb.appendLine("Version: $appVersionName (code $appVersionCode)")
        sb.appendLine("Build Type: ${BuildConfig.BUILD_TYPE}")
        sb.appendLine()
        sb.appendLine("[DEVICE HARDWARE]")
        sb.appendLine("Manufacturer: ${Build.MANUFACTURER}")
        sb.appendLine("Model: ${Build.MODEL}")
        sb.appendLine("Product: ${Build.PRODUCT}")
        sb.appendLine("Device: ${Build.DEVICE}")
        sb.appendLine("Board: ${Build.BOARD}")
        sb.appendLine("Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("Fingerprint: ${Build.FINGERPRINT}")
        sb.appendLine()
        sb.appendLine("[AUDIO HAL / NATIVE SETTINGS]")
        sb.appendLine("Native Sample Rate: $nativeSampleRate Hz")
        sb.appendLine("Native Buffer Frames: $nativeBufferSize")
        sb.appendLine("FEATURE_AUDIO_LOW_LATENCY: $hasLowLatencyFeature")
        sb.appendLine("FEATURE_AUDIO_PRO: $hasProAudioFeature")
        sb.appendLine()
        sb.appendLine("[NETWORK STATE]")
        sb.appendLine("Local IP: $ip")
        sb.appendLine()
        sb.appendLine("[CURRENT STREAM TELEMETRY]")
        sb.appendLine("Active: ${tel.isActive}")
        sb.appendLine("Role: ${if (tel.isTransmitter) "Transmitter" else "Receiver"}")
        sb.appendLine("Profile: ${tel.streamProfileName}")
        sb.appendLine("Sample Rate: ${tel.sampleRate} Hz")
        sb.appendLine("Bit Depth: ${tel.bitDepth}-bit")
        sb.appendLine("Channels: ${tel.channels}")
        sb.appendLine("Target Bitrate: ${tel.bitrateKbps} kbps")
        sb.appendLine("Packets Total: ${tel.packetsTotal}")
        sb.appendLine("Packets/Sec: ${tel.packetsPerSec} pps")
        sb.appendLine("Data Rate: ${(tel.bytesPerSec * 8) / 1000} kbps")
        sb.appendLine("Buffer Health: ${tel.bufferFillPercent}% (${tel.bufferSlotsUsed}/${tel.bufferSlotsTotal} slots)")
        sb.appendLine("FEC Recoveries: ${tel.fecRecoveredTotal}")
        sb.appendLine("Silence Suppressed: ${tel.isSilenceSuppressed}")
        sb.appendLine("Status Detail: ${tel.statusDetail}")
        sb.appendLine("Remote Endpoint: ${tel.remoteEndpoint ?: "None"}")
        sb.appendLine()
        sb.appendLine("[IN-APP RECENT LOGS (${logBuffer.size} entries)]")
        sb.appendLine("--------------------------------------------------")
        for (entry in logBuffer) {
            val ts = timeFormat.format(Date(entry.timestamp))
            sb.appendLine("$ts [${entry.level}] ${entry.tag}: ${entry.message}")
            entry.throwable?.let { tr ->
                sb.appendLine(Log.getStackTraceString(tr))
            }
        }
        if (includeSystemLogcat) {
            sb.appendLine()
            sb.appendLine("[SYSTEM LOGCAT (Process ${Process.myPid()})]")
            sb.appendLine("--------------------------------------------------")
            val sysLogs = captureSystemLogcat(200)
            for (line in sysLogs) {
                sb.appendLine(line)
            }
        }
        sb.appendLine("==================================================")
        sb.appendLine("END OF DIAGNOSTIC REPORT")
        sb.appendLine("==================================================")

        return sb.toString()
    }

    fun exportLogsToFile(context: Context): File {
        val file = File(context.cacheDir, "simple_audio_stream_logs.txt")
        val report = buildDiagnosticReport(context, includeSystemLogcat = true)
        file.writeText(report, Charsets.UTF_8)
        return file
    }

    fun copyToClipboard(context: Context) {
        val report = buildDiagnosticReport(context, includeSystemLogcat = true)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("Simple Audio Stream Diagnostics", report)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(context, "Diagnostics copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    fun shareLogs(activity: Activity) {
        try {
            val file = exportLogsToFile(activity)
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Simple Audio Stream Diagnostics - ${activity.packageName}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "Share Diagnostic Logs"))
        } catch (e: Exception) {
            Log.e("AppLogger", "Failed to share logs", e)
            Toast.makeText(activity, "Error sharing logs: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun showLogViewerDialog(activity: Activity) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_log_viewer, null)
        val tvContent = view.findViewById<TextView>(R.id.tv_log_content)
        val scrollVertical = view.findViewById<android.widget.ScrollView>(R.id.scroll_log_vertical)
        val btnCopy = view.findViewById<MaterialButton>(R.id.btn_dialog_copy)
        val btnShare = view.findViewById<MaterialButton>(R.id.btn_dialog_share)
        val btnRefresh = view.findViewById<MaterialButton>(R.id.btn_dialog_refresh)
        val btnLive = view.findViewById<MaterialButton>(R.id.btn_dialog_live)
        val btnClear = view.findViewById<MaterialButton>(R.id.btn_dialog_clear)
        val btnClose = view.findViewById<MaterialButton>(R.id.btn_dialog_close)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(view)
            .setCancelable(true)
            .create()

        var isLive = false
        val liveHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var liveRunnable: Runnable? = null
        var sessionStartTime = System.currentTimeMillis()
        var lastRenderedReport = ""

        fun refreshContent(includeSystemLogcat: Boolean = true, autoScroll: Boolean = false) {
            if (!isLive && includeSystemLogcat && lastRenderedReport.isEmpty()) {
                tvContent.text = "Compiling diagnostic report..."
            }
            val time = if (isLive) sessionStartTime else System.currentTimeMillis()
            Thread {
                val report = buildDiagnosticReport(activity, includeSystemLogcat = includeSystemLogcat, sessionTime = time)
                activity.runOnUiThread {
                    if (report == lastRenderedReport) return@runOnUiThread
                    lastRenderedReport = report

                    val child = scrollVertical?.getChildAt(0)
                    val isNearBottom = if (child != null && scrollVertical.height > 0) {
                        val bottomDiff = child.bottom - (scrollVertical.height + scrollVertical.scrollY)
                        bottomDiff <= 120
                    } else {
                        true
                    }
                    val prevScrollY = scrollVertical?.scrollY ?: 0

                    tvContent.text = report

                    if (autoScroll && isNearBottom) {
                        scrollVertical?.post {
                            val c = scrollVertical.getChildAt(0)
                            if (c != null) {
                                scrollVertical.scrollTo(0, c.bottom)
                            } else {
                                scrollVertical.fullScroll(android.view.View.FOCUS_DOWN)
                            }
                        }
                    } else if (prevScrollY > 0) {
                        scrollVertical?.post {
                            scrollVertical.scrollTo(0, prevScrollY)
                        }
                    }
                }
            }.start()
        }

        fun stopLiveFeed() {
            isLive = false
            liveRunnable?.let { liveHandler.removeCallbacks(it) }
            liveRunnable = null
            btnLive?.text = "Live: OFF"
            btnLive?.setTextColor(activity.getColor(R.color.text_secondary))
            btnLive?.strokeColor = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.card_stroke))
        }

        fun startLiveFeed() {
            isLive = true
            sessionStartTime = System.currentTimeMillis()
            lastRenderedReport = ""
            btnLive?.text = "Live: ON"
            btnLive?.setTextColor(activity.getColor(R.color.status_green))
            btnLive?.strokeColor = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.status_green))
            liveRunnable = object : Runnable {
                override fun run() {
                    if (!isLive || !dialog.isShowing) return
                    refreshContent(includeSystemLogcat = false, autoScroll = true)
                    liveHandler.postDelayed(this, 1000L)
                }
            }
            liveHandler.post(liveRunnable!!)
        }

        btnLive?.setOnClickListener {
            if (isLive) {
                stopLiveFeed()
            } else {
                startLiveFeed()
            }
        }

        btnCopy.setOnClickListener {
            copyToClipboard(activity)
        }

        btnShare.setOnClickListener {
            shareLogs(activity)
        }

        btnRefresh.setOnClickListener {
            refreshContent(includeSystemLogcat = true, autoScroll = false)
        }

        btnClear.setOnClickListener {
            clear()
            refreshContent(includeSystemLogcat = false, autoScroll = false)
            Toast.makeText(activity, "Log buffer cleared", Toast.LENGTH_SHORT).show()
        }

        btnClose.setOnClickListener {
            stopLiveFeed()
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            stopLiveFeed()
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        refreshContent(includeSystemLogcat = true, autoScroll = true)
    }
}
