package com.example.audiostreamer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Android-backed implementation of the harness environment.
 *
 * It only *reads* live state (diagnostics sections, counters, telemetry) and *requests* profile changes through
 * the existing production control path (`stream_prefs` + `AudioCaptureService.ACTION_RESTART_CAPTURE`, exactly
 * what the Settings screen already does). It never reconfigures capture, the jitter buffer, FEC or codecs
 * itself, so the harness cannot diverge from the running implementation.
 */
class AndroidHatTestEnvironment(context: Context) : HatTestEnvironment {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("stream_prefs", Context.MODE_PRIVATE)

    /** Set when the last [applyProfile] call failed; surfaced through the diagnostics event only. */
    @Volatile
    private var lastApplyError: String? = null

    override fun preconditions(): HatTestPreconditions {
        val reasons = ArrayList<String>()
        val details = LinkedHashMap<String, Any?>()

        val transmitter = AudioCaptureService.isRunning.get()
        val receiver = AudioSinkService.isRunning.get()
        val telemetry = StreamState.telemetry.value
        val sections = try {
            HatDiagnostics.sectionsSnapshot()
        } catch (t: Throwable) {
            emptyMap()
        }
        val config = sections["CONFIG"]
        val capture = sections["CAPTURE"]
        val playback = sections["PLAYBACK"]
        val generation = currentGeneration()

        details["transmitterRunning"] = transmitter
        details["receiverRunning"] = receiver
        details["streamActive"] = telemetry.isActive
        details["activeReceivers"] = telemetry.activeReceiversCount
        details["remoteEndpoint"] = telemetry.remoteEndpoint
        details["generation"] = generation
        details["profile"] = currentProfileName()
        details["recordingState"] = (capture?.get("recordingState") as? Number)?.toInt()
        details["playState"] = (playback?.get("playState") as? Number)?.toInt()

        if (!transmitter && !receiver) {
            reasons += "HAT service is not running - start the transmitter or receiver first"
        }
        if (!telemetry.isActive) {
            reasons += "no established HAT connection yet (stream is not active)"
        }
        if (transmitter && telemetry.activeReceiversCount < 1) {
            reasons += "no receiver is connected (activeReceivers=0)"
        }
        if (transmitter) {
            val recordingState = (capture?.get("recordingState") as? Number)?.toInt()
            if (recordingState == null) {
                reasons += "capture diagnostics are unavailable (capture pipeline not initialized)"
            } else if (recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                reasons += "AudioRecord is not recording (recordingState=$recordingState)"
            }
        }
        if (receiver) {
            val playState = (playback?.get("playState") as? Number)?.toInt()
            if (playState != null && playState != AudioTrack.PLAYSTATE_PLAYING) {
                reasons += "AudioTrack is not playing (playState=$playState)"
            }
        }
        if (config.isNullOrEmpty()) {
            reasons += "no valid stream configuration is active"
        } else if ((config["profile"] as? String).isNullOrEmpty()) {
            reasons += "active stream configuration has no profile"
        }
        if (generation <= 0L) {
            reasons += "no authoritative stream generation"
        }

        return HatTestPreconditions(ok = reasons.isEmpty(), reasons = reasons, details = details)
    }

    override fun deviceInfo(): HatTestDeviceInfo {
        val metadata = HatDiagnostics.metadata()
        return HatTestDeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            product = Build.PRODUCT,
            device = Build.DEVICE,
            board = Build.BOARD,
            androidVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            fingerprint = Build.FINGERPRINT,
            audioOutputDevice = metadata.audioOutputDevice,
            networkTransport = metadata.networkTransport
        )
    }

    override fun appVersion(): String = BuildConfig.VERSION_NAME

    override fun versionCode(): Int = BuildConfig.VERSION_CODE

    override fun buildType(): String = BuildConfig.BUILD_TYPE

    /** No revision information is compiled into the APK today, so this is reported honestly as unavailable. */
    override fun gitRevision(): String? = null

    override fun currentGeneration(): Long = AudioCaptureService.currentStreamGeneration.get()

    override fun currentProfileName(): String? =
        (sectionsOf()["CONFIG"]?.get("profile") as? String)?.takeIf { it.isNotBlank() }

    override fun connectedReceiverCount(): Int = StreamState.telemetry.value.activeReceiversCount

    override fun sectionsSnapshot(): Map<String, Map<String, Any?>> = HatDiagnostics.sectionsSnapshot()

    override fun counterSnapshot(): Map<String, Long> = HatDiagnostics.counterSnapshot()

    override fun timingSnapshot(): List<HatDiagnostics.Timing.Snapshot> = HatDiagnostics.timingSnapshot()

    override fun diagnosticsSnapshot(): String = HatDiagnostics.snapshot()

    override fun recentEvents(): List<HatDiagnostics.Event> = HatDiagnostics.recentEvents(256)

    /**
     * Requests the profile change exactly like the Settings screen does: persist the profile preference and let
     * the running service renegotiate/reconfigure through `ACTION_RESTART_CAPTURE`. No reconfiguration logic is
     * duplicated here.
     */
    override fun applyProfile(target: LatencyTarget): Boolean {
        return try {
            prefs.edit().putString(AudioConfig.PREF_KEY_PROFILE, target.profilePreference).apply()
            val intent = Intent(appContext, AudioCaptureService::class.java).apply {
                action = AudioConfig.ACTION_RESTART_CAPTURE
            }
            appContext.startService(intent)
            lastApplyError = null
            true
        } catch (t: Throwable) {
            lastApplyError = "${t.javaClass.simpleName}: ${t.message}"
            HatDiagnostics.error(
                "HAT_TEST_PROFILE_REQUEST_FAILED",
                mapOf("profile" to target.name, "error" to (lastApplyError ?: "unknown")),
                t
            )
            false
        }
    }

    /**
     * The receiver is normally a different device, and the local sink's listening port is only known to the
     * receiving UI (it is not persisted), so the app cannot restart a receiver deterministically. The
     * RECEIVER_RECONNECT scenario is therefore reported as MANUAL_REQUIRED rather than faked as successful.
     */
    override fun canRestartLocalReceiver(): Boolean = false

    override fun restartLocalReceiver(): Boolean = false

    override fun isTransmitterRunning(): Boolean = AudioCaptureService.isRunning.get()

    override fun isReceiverRunning(): Boolean = AudioSinkService.isRunning.get()

    private fun sectionsOf(): Map<String, Map<String, Any?>> = try {
        HatDiagnostics.sectionsSnapshot()
    } catch (t: Throwable) {
        emptyMap()
    }
}

/** Raised when a report file could not be written; the exact message is surfaced to the user. */
class HatTestExportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Writes the report into the public Downloads collection via MediaStore (`Downloads/HAT/`).
 *
 * Uses `MediaStore.Downloads` with `RELATIVE_PATH`, so no storage permission is requested and
 * MANAGE_EXTERNAL_STORAGE is not needed (minSdk is 29). Files are marked pending while being written and
 * deleted again if the write fails, so a failed export cannot leave a truncated document behind.
 */
class MediaStoreHatTestExporter(context: Context) : HatTestExporter {

    private val appContext = context.applicationContext

    override fun export(fileName: String, mimeType: String, content: String): HatTestExportedFile {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + TARGET_FOLDER
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = try {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } catch (t: Throwable) {
            throw HatTestExportException(
                "MediaStore rejected $fileName in Downloads/$TARGET_FOLDER: ${t.javaClass.simpleName}: ${t.message}",
                t
            )
        } ?: throw HatTestExportException("MediaStore refused to create Downloads/$TARGET_FOLDER/$fileName")

        try {
            val stream = resolver.openOutputStream(uri)
                ?: throw HatTestExportException("Could not open an output stream for Downloads/$TARGET_FOLDER/$fileName")
            stream.use { output ->
                output.write(bytes)
                output.flush()
            }
            val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, publish, null, null)
        } catch (t: Throwable) {
            // Do not leave a half-written document behind.
            runCatching { resolver.delete(uri, null, null) }
            if (t is HatTestExportException) throw t
            throw HatTestExportException(
                "Failed writing Downloads/$TARGET_FOLDER/$fileName: ${t.javaClass.simpleName}: ${t.message}",
                t
            )
        }

        return HatTestExportedFile(fileName = fileName, byteCount = bytes.size, uri = uri.toString())
    }

    companion object {
        const val TARGET_FOLDER = "HAT"
    }
}

/**
 * Process-wide owner of the currently running Full HAT Test.
 *
 * Guarantees: at most one run at a time, the run executes off the main thread on a private supervisor scope,
 * cancellation is always available, and the latest [progress] (including the finished report) is observable by
 * any Activity.
 */
object HatTestController {

    private const val TAG = "HatTestController"

    private val _progress = MutableStateFlow(HatTestProgress())
    val progress: StateFlow<HatTestProgress> = _progress.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var active: HatTestRun? = null

    private class HatTestRun(val runner: HatTestRunner) {
        @Volatile
        var job: Job? = null
    }

    fun isRunning(): Boolean = active != null

    /**
     * Starts a run. Returns false when a test is already running, so the caller can tell the user rather than
     * silently queueing a second test.
     */
    fun start(context: Context, config: HatTestConfig = HatTestConfig.DEFAULT): Boolean {
        val appContext = context.applicationContext
        return synchronized(lock) {
            if (active != null) return false
            val runner = HatTestRunner(
                env = AndroidHatTestEnvironment(appContext),
                config = config,
                exporter = MediaStoreHatTestExporter(appContext),
                listener = { updated -> _progress.value = updated }
            )
            launchLocked(runner)
            true
        }
    }

    /** Test seam: starts an arbitrary runner so the concurrent-start guard can be verified without a Context. */
    internal fun startWith(runner: HatTestRunner): Boolean = synchronized(lock) {
        if (active != null) return false
        launchLocked(runner)
        true
    }

    private fun launchLocked(runner: HatTestRunner) {
        val handle = HatTestRun(runner)
        active = handle
        _progress.value = HatTestProgress(state = HatTestState.PREPARING, message = "Starting Full HAT Test")
        val job = scope.launch {
            try {
                runner.run()
            } catch (c: kotlinx.coroutines.CancellationException) {
                // Cancellation is a normal outcome: the runner already published the CANCELLED state.
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Full HAT Test failed: ${t.javaClass.simpleName}: ${t.message}", t)
                _progress.value = _progress.value.copy(
                    state = HatTestState.FAILED,
                    message = "Test failed: ${t.message}",
                    error = "${t.javaClass.simpleName}: ${t.message}"
                )
            }
        }
        handle.job = job
        // invokeOnCompletion also fires when the coroutine was cancelled before its body ever ran, which a
        // try/finally inside the body would not: the active run must never leak and block future runs.
        job.invokeOnCompletion {
            synchronized(lock) {
                if (active === handle) active = null
            }
        }
    }

    /** Requests cancellation of the running test, if any. */
    fun cancel() {
        val current = active ?: return
        current.runner.cancel()
        current.job?.cancel()
    }

    /** Releases a pending manual checkpoint (SCENARIO 8). */
    fun continueManual() {
        active?.runner?.continueManualStep()
    }

    /** The most recent finished report, if any. */
    fun lastReport(): HatTestReport? = _progress.value.report
}
