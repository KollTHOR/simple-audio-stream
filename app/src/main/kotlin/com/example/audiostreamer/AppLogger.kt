package com.example.audiostreamer

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.audiostreamer.update.BuildInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * The app's single logging facade and diagnostics source.
 *
 * - Every log call is kept in a bounded in-memory ring ([entries]) and mirrored to `android.util.Log`
 *   so `adb logcat` still works. Nothing is ever *read* from logcat — the in-app logs screen and the
 *   diagnostic report are built from the ring, so there is no subprocess and no polling.
 * - [revision] bumps on every change; the logs UI collects it and re-reads [entries].
 * - [report] logs at ERROR *and* surfaces the message through [UserAlertCenter]; plain [e] only logs.
 */
object AppLogger {

    // ---- Support / reporting targets. Placeholders: update before a wider release. ----
    const val DEVELOPER_CONTACT = "developer@example.com"
    const val REPO_URL = "https://github.com/KollTHOR/simple-audio-stream"
    const val NEW_ISSUE_URL = "$REPO_URL/issues/new"

    enum class Level(val letter: Char) {
        VERBOSE('V'), DEBUG('D'), INFO('I'), WARN('W'), ERROR('E')
    }

    /** One log line. [id] is monotonic so the list adapter can diff cheaply. */
    data class LogEntry(
        val id: Long,
        val timestamp: Long,
        val level: Char,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null
    ) {
        val isProblem: Boolean get() = level == 'W' || level == 'E'

        /** `HH:mm:ss.SSS L Tag: message` — one line, no newlines (safe for a list row). */
        fun line(): String =
            "${formatTime(timestamp)} $level $tag: ${message.replace('\n', ' ')}"

        /** Full text for the detail sheet, including the stack trace when there is one. */
        fun detail(): String = buildString {
            append(formatDateTime(timestamp)).append(' ').append(level).append(' ').append(tag)
            append('\n').append(message)
            throwable?.let { append("\n\n").append(Log.getStackTraceString(it)) }
        }
    }

    private const val MAX_ENTRIES = 2000
    private const val REPORT_LOG_TAIL = 400
    private const val REPORT_PROBLEMS = 40
    private const val ISSUE_BODY_LIMIT = 6000

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>()
    private val nextId = AtomicLong(0L)

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    // ---- Logging ---------------------------------------------------------------------------

    fun v(tag: String, msg: String, tr: Throwable? = null) = add(Level.VERBOSE, tag, msg, tr)
    fun d(tag: String, msg: String, tr: Throwable? = null) = add(Level.DEBUG, tag, msg, tr)
    fun i(tag: String, msg: String, tr: Throwable? = null) = add(Level.INFO, tag, msg, tr)
    fun w(tag: String, msg: String, tr: Throwable? = null) = add(Level.WARN, tag, msg, tr)
    fun w(tag: String, tr: Throwable) = add(Level.WARN, tag, tr.message ?: tr.javaClass.simpleName, tr)

    /** Log an error. Does not disturb the user — use [report] for failures they should see. */
    fun e(tag: String, msg: String, tr: Throwable? = null) = add(Level.ERROR, tag, msg, tr)
    fun e(tag: String, tr: Throwable) = add(Level.ERROR, tag, tr.message ?: tr.javaClass.simpleName, tr)

    /**
     * Log an error **and** surface it through [UserAlertCenter] (Snackbar in the foreground, a
     * heads-up notification otherwise). [UserAlertCenter] throttles duplicates and bursts.
     */
    fun report(tag: String, msg: String, tr: Throwable? = null) {
        add(Level.ERROR, tag, msg, tr)
        UserAlertCenter.publishFromLogger("$tag: $msg")
    }

    private fun add(level: Level, tag: String, msg: String, tr: Throwable?) {
        val entry = LogEntry(nextId.incrementAndGet(), System.currentTimeMillis(), level.letter, tag, msg, tr)
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        }
        mirror(level, tag, msg, tr)
        _revision.value = _revision.value + 1L
    }

    /** Keep `adb logcat` useful; never the source of truth for the in-app views. */
    private fun mirror(level: Level, tag: String, msg: String, tr: Throwable?) {
        try {
            when (level) {
                Level.VERBOSE -> if (tr != null) Log.v(tag, msg, tr) else Log.v(tag, msg)
                Level.DEBUG -> if (tr != null) Log.d(tag, msg, tr) else Log.d(tag, msg)
                Level.INFO -> if (tr != null) Log.i(tag, msg, tr) else Log.i(tag, msg)
                Level.WARN -> if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
                Level.ERROR -> if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
            }
        } catch (_: Throwable) {
            // Logging must never take down a streaming path.
        }
    }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        _revision.value = _revision.value + 1L
    }

    fun getStackTraceString(tr: Throwable?): String = Log.getStackTraceString(tr)

    // ---- Reads -----------------------------------------------------------------------------

    /** Snapshot of everything currently buffered, oldest first. */
    fun entries(): List<LogEntry> = synchronized(lock) { buffer.toList() }

    fun size(): Int = synchronized(lock) { buffer.size }

    /** The last [limit] warning/error lines, oldest first. */
    fun recentProblems(limit: Int = REPORT_PROBLEMS): List<LogEntry> =
        synchronized(lock) { buffer.filter { it.isProblem }.takeLast(limit) }

    // ---- Diagnostic report -----------------------------------------------------------------

    /**
     * A sectioned report: app, device, **current state and settings**, network, stream, audio HAL,
     * permissions, recent problems, then the log tail. Everything a maintainer needs to triage a
     * report without asking follow-up questions.
     */
    fun buildDiagnosticReport(context: Context, sessionTime: Long? = null): String {
        val sb = StringBuilder()
        val build = BuildInfo.current()
        val prefs = context.getSharedPreferences("stream_prefs", Context.MODE_PRIVATE)
        val tel = StreamState.telemetry.value

        sb.appendLine("Simple Audio Stream — diagnostic report")
        sb.appendLine("Generated: ${formatDateTime(sessionTime ?: System.currentTimeMillis())}")
        sb.appendLine()

        sb.appendLine("== APP ==")
        sb.appendLine("Version: ${build.versionName} (build ${build.versionCode}, ${build.channel.displayName})")
        sb.appendLine("Commit: ${build.commitShort}  Built: ${build.formattedBuildDate}")
        sb.appendLine("Package: ${context.packageName}")
        sb.appendLine("Build type: ${BuildConfig.BUILD_TYPE}")
        sb.appendLine()

        sb.appendLine("== DEVICE ==")
        sb.appendLine("Manufacturer / model: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Device / product: ${Build.DEVICE} / ${Build.PRODUCT}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine()

        sb.appendLine("== CURRENT STATE ==")
        sb.appendLine("Selected mode: ${AppStatus.currentMode}")
        sb.appendLine("Services: ${AppStatus.describeServices()}")
        sb.appendLine("Stream active: ${tel.isActive}  Role: ${if (tel.isTransmitter) "Broadcast" else "Receive"}")
        sb.appendLine("Status: ${tel.statusDetail}")
        sb.appendLine("Remote endpoint: ${tel.remoteEndpoint ?: "none"}")
        sb.appendLine("Connected receivers: ${tel.connectedReceivers.size}")
        tel.connectedReceivers.forEach { r ->
            sb.appendLine("  • ${r.name} @ ${r.ip}:${r.port} (${r.transportType}, ${r.latencyMs} ms, ${r.packetsTransferred} packets)")
        }
        sb.appendLine()

        sb.appendLine("== SETTINGS ==")
        sb.appendLine("Profile: ${prefs.getString(AudioConfig.PREF_KEY_PROFILE, AudioConfig.PROFILE_AUTO)}")
        sb.appendLine("Sample rate: ${prefs.getString(AudioConfig.PREF_KEY_SAMPLE_RATE, AudioConfig.SAMPLE_RATE_AUTO)}")
        sb.appendLine("Bit depth: ${prefs.getString(AudioConfig.PREF_KEY_BIT_DEPTH, AudioConfig.BIT_DEPTH_AUTO)}")
        sb.appendLine("Low-latency codec: ${prefs.getString(AudioConfig.PREF_KEY_LOW_LATENCY_CODEC, AudioConfig.CODEC_OPUS)}")
        sb.appendLine("FEC: ${prefs.getBoolean(AudioConfig.PREF_KEY_FEC_ENABLED, true)}")
        sb.appendLine("Sync device volume: ${prefs.getBoolean(AudioConfig.PREF_KEY_SYNC_DEVICE_VOLUME, true)}")
        sb.appendLine()

        sb.appendLine("== STREAM (negotiated) ==")
        sb.appendLine("Profile: ${tel.streamProfileName}")
        sb.appendLine("Format: ${tel.sampleRate} Hz, ${tel.bitDepth}-bit, ${tel.channels} ch")
        sb.appendLine("Target bitrate: ${tel.bitrateKbps} kbps  Data rate: ${(tel.bytesPerSec * 8) / 1000} kbps")
        sb.appendLine("Packets: ${tel.packetsTotal} total, ${tel.packetsPerSec}/s")
        sb.appendLine("Buffer: ${tel.bufferFillPercent}% (${tel.bufferSlotsUsed}/${tel.bufferSlotsTotal} slots)")
        sb.appendLine("FEC recoveries: ${tel.fecRecoveredTotal}  Silence suppressed: ${tel.isSilenceSuppressed}")
        sb.appendLine()

        sb.appendLine("== AUDIO HAL ==")
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        sb.appendLine("Native sample rate: ${am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: "unknown"} Hz")
        sb.appendLine("Native frames/buffer: ${am?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) ?: "unknown"}")
        sb.appendLine("FEATURE_AUDIO_LOW_LATENCY: ${context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)}")
        sb.appendLine("FEATURE_AUDIO_PRO: ${context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO)}")
        sb.appendLine()

        sb.appendLine("== NETWORK ==")
        sb.appendLine("Local IP: ${NetworkUtils.getLocalIpAddress() ?: "unavailable"}")
        sb.appendLine()

        sb.appendLine("== PERMISSIONS ==")
        sb.appendLine("RECORD_AUDIO: ${granted(context, android.Manifest.permission.RECORD_AUDIO)}")
        sb.appendLine("POST_NOTIFICATIONS: ${granted(context, "android.permission.POST_NOTIFICATIONS")}")
        sb.appendLine("NEARBY_WIFI_DEVICES: ${granted(context, "android.permission.NEARBY_WIFI_DEVICES")}")
        sb.appendLine("ACCESS_FINE_LOCATION: ${granted(context, android.Manifest.permission.ACCESS_FINE_LOCATION)}")
        sb.appendLine("BLUETOOTH_CONNECT: ${granted(context, "android.permission.BLUETOOTH_CONNECT")}")
        sb.appendLine("Notification access: ${NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)}")
        sb.appendLine("Install unknown apps: ${context.packageManager.canRequestPackageInstalls()}")
        sb.appendLine("Ignoring battery optimisations: ${ignoringBatteryOptimisations(context)}")
        sb.appendLine()

        val problems = recentProblems()
        sb.appendLine("== RECENT PROBLEMS (${problems.size}) ==")
        if (problems.isEmpty()) {
            sb.appendLine("(none)")
        } else {
            problems.forEach { sb.appendLine(it.line()) }
        }
        sb.appendLine()

        val tail = synchronized(lock) { buffer.takeLast(REPORT_LOG_TAIL) }
        sb.appendLine("== LOG (last ${tail.size} of ${size()}) ==")
        tail.forEach { sb.appendLine(it.line()) }

        return sb.toString()
    }

    /** The compact body pre-filled into a GitHub issue (the full report goes to the clipboard). */
    fun buildIssueBody(context: Context): String {
        val build = BuildInfo.current()
        val sb = StringBuilder()
        sb.appendLine("### What happened?")
        sb.appendLine("<!-- Describe the problem, and what you expected instead. -->")
        sb.appendLine()
        sb.appendLine("### Steps")
        sb.appendLine("1. ")
        sb.appendLine()
        sb.appendLine("### Device")
        sb.appendLine("- App: ${build.versionName} (build ${build.versionCode}, ${build.channel.displayName})")
        sb.appendLine("- Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("- Mode: ${AppStatus.currentMode} (${AppStatus.describeServices()})")
        sb.appendLine()
        sb.appendLine("### Recent problems")
        val problems = recentProblems(15)
        if (problems.isEmpty()) {
            sb.appendLine("_none logged_")
        } else {
            sb.appendLine("```")
            problems.forEach { sb.appendLine(it.line()) }
            sb.appendLine("```")
        }
        sb.appendLine()
        sb.appendLine("> The full diagnostic report was copied to the clipboard — paste it below if it fits, or attach it as a file.")
        val body = sb.toString()
        return if (body.length > ISSUE_BODY_LIMIT) body.take(ISSUE_BODY_LIMIT) + "\n…" else body
    }

    // ---- Sharing / reporting ---------------------------------------------------------------

    fun exportReportToFile(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(context.cacheDir, "simple-audio-stream-report-$stamp.txt")
        file.writeText(buildDiagnosticReport(context), Charsets.UTF_8)
        return file
    }

    fun copyReportToClipboard(context: Context) {
        val report = buildDiagnosticReport(context)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Simple Audio Stream diagnostics", report))
        Toast.makeText(context, "Diagnostic report copied", Toast.LENGTH_SHORT).show()
    }

    fun shareReport(activity: Activity) {
        try {
            val file = exportReportToFile(activity)
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Simple Audio Stream diagnostic report")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "Share diagnostic report"))
        } catch (e: Exception) {
            e("AppLogger", "Failed to share report", e)
            Toast.makeText(activity, "Couldn't share the report: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Opens a pre-filled GitHub issue. GitHub's new-issue URL can't carry attachments, so the full
     * report is copied to the clipboard first and the user is told to paste it.
     */
    fun openIssue(activity: Activity) {
        copyReportToClipboard(activity)
        val build = BuildInfo.current()
        val title = "Bug: ${Build.MODEL} / Android ${Build.VERSION.RELEASE} / ${build.versionName}"
        val url = Uri.parse(NEW_ISSUE_URL)
            .buildUpon()
            .appendQueryParameter("title", title)
            .appendQueryParameter("body", buildIssueBody(activity))
            .build()
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, url))
            Toast.makeText(
                activity,
                "Full report copied — paste it into the issue",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            e("AppLogger", "No browser to open the issue", e)
            Toast.makeText(activity, "Couldn't open a browser — the report is on your clipboard", Toast.LENGTH_LONG).show()
        }
    }

    // ---- Helpers ---------------------------------------------------------------------------

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun ignoringBatteryOptimisations(context: Context): Boolean = try {
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    } catch (_: Exception) {
        false
    }
}

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

private fun formatTime(ts: Long): String = synchronized(TIME_FORMAT) { TIME_FORMAT.format(Date(ts)) }
private fun formatDateTime(ts: Long): String = synchronized(DATE_FORMAT) { DATE_FORMAT.format(Date(ts)) }
