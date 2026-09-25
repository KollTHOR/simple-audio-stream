package com.example.audiostreamer

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import com.example.audiostreamer.AppLogger as Log

/**
 * MediaSessionTracker — single source of truth for:
 *   - which media controller is currently selected
 *   - current track metadata and playback state
 *   - transport command dispatch (always to the current controller)
 *   - monotonic media-state sequence (guards stale network packets)
 *
 * Selection priority:
 *   1. Android media-key session  (getMediaKeyEventSession → OnMediaKeyEventSessionChangedListener API 34+)
 *   2. Any PLAYING controller     (OnActiveSessionsChangedListener fallback)
 *   3. Most recently seen paused controller
 *   4. null  →  no-media state published
 *
 * Threading: mutation via [mainHandler] (main thread) or [listenerExecutor].
 * Callers from background threads are safe — callbacks are posted to main.
 */
object MediaSessionTracker {

    private const val TAG = "MediaSessionTracker"

    // ──────────────────────────────────────────────────────────────────────────
    // Public state
    // ──────────────────────────────────────────────────────────────────────────

    data class MediaState(
        val packageName: String,
        /** Unique token identity — hashCode used as a stable string key. */
        val sessionIdentity: String,
        val title: String,
        val artist: String,
        val album: String,
        val isPlaying: Boolean,
        /** Monotonically increasing. Receiver rejects packets with seq <= lastReceivedSeq. */
        val sequence: Long
    )

    /**
     * Null means "no active media session".
     * Listener is notified on every state change (main thread).
     */
    @Volatile var currentState: MediaState? = null
        private set

    /**
     * Invoked on the main thread whenever tracked state changes.
     * AudioCaptureService wires this to [AudioCaptureService.sendMediaMetadata].
     */
    var onStateChangedListener: ((MediaState?) -> Unit)? = null

    // ──────────────────────────────────────────────────────────────────────────
    // Private internals
    // ──────────────────────────────────────────────────────────────────────────

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listenerExecutor = Executors.newSingleThreadExecutor()
    private val sequence = AtomicLong(0L)

    private var context: Context? = null
    private var sessionManager: MediaSessionManager? = null
    private var listenerComponentName: ComponentName? = null

    /** The controller we currently have a Callback registered on. */
    private var registeredController: MediaController? = null

    /** Per-controller callback — re-evaluates state on any metadata/playback change. */
    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            val ctrl = registeredController ?: return
            Log.d(TAG, "MEDIA_STATE onMetadataChanged pkg=${ctrl.packageName}")
            mainHandler.post { publishState(ctrl) }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val ctrl = registeredController ?: return
            Log.d(TAG, "MEDIA_STATE onPlaybackStateChanged pkg=${ctrl.packageName} state=${state?.state}")
            mainHandler.post { publishState(ctrl) }
        }

        override fun onSessionDestroyed() {
            Log.i(TAG, "MEDIA_SESSION_DESTROYED pkg=${registeredController?.packageName}")
            mainHandler.post { reevaluate() }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle: start / stop
    // ──────────────────────────────────────────────────────────────────────────

    fun start(ctx: Context, componentName: ComponentName) {
        mainHandler.post {
            context = ctx.applicationContext
            val sm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: run {
                    Log.w(TAG, "MediaSessionManager unavailable — tracking disabled")
                    return@post
                }
            sessionManager = sm
            listenerComponentName = componentName

            // API 34+: register for instant media-key session changes
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    sm.addOnMediaKeyEventSessionChangedListener(
                        listenerExecutor,
                        mediaKeySessionChangedListener
                    )
                    Log.i(TAG, "Registered OnMediaKeyEventSessionChangedListener (API 34+)")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed registering OnMediaKeyEventSessionChangedListener: ${e.message}")
                }
            }

            // Always register the active-sessions fallback listener
            try {
                sm.addOnActiveSessionsChangedListener(activeSessionsChangedListener, componentName)
                Log.i(TAG, "Registered OnActiveSessionsChangedListener")
            } catch (e: Exception) {
                Log.w(TAG, "Failed registering OnActiveSessionsChangedListener: ${e.message}")
            }

            reevaluate()
        }
    }

    fun stop() {
        mainHandler.post {
            val sm = sessionManager ?: return@post

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try { sm.removeOnMediaKeyEventSessionChangedListener(mediaKeySessionChangedListener) }
                catch (ignored: Exception) {}
            }
            try { sm.removeOnActiveSessionsChangedListener(activeSessionsChangedListener) }
            catch (ignored: Exception) {}

            detachCurrentController()
            currentState = null
            sessionManager = null
            listenerComponentName = null
            context = null
            Log.i(TAG, "MEDIA_SESSION_CLEARED (tracker stopped)")
            notifyListener(null)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Transport control dispatch  (always to the CURRENT controller)
    // ──────────────────────────────────────────────────────────────────────────

    fun dispatchCommand(command: Byte): Boolean {
        val ctrl = registeredController
        if (ctrl == null) {
            Log.w(TAG, "MEDIA_COMMAND command=$command — no active controller")
            return false
        }
        return try {
            val pkg = ctrl.packageName
            val tc = ctrl.transportControls
            when (command) {
                HatPacket.MEDIA_CMD_PLAY_PAUSE -> {
                    val state = ctrl.playbackState?.state
                    if (state == PlaybackState.STATE_PLAYING) tc.pause() else tc.play()
                }
                HatPacket.MEDIA_CMD_PLAY     -> tc.play()
                HatPacket.MEDIA_CMD_PAUSE    -> tc.pause()
                HatPacket.MEDIA_CMD_NEXT     -> tc.skipToNext()
                HatPacket.MEDIA_CMD_PREVIOUS -> tc.skipToPrevious()
                else -> return false
            }
            Log.i(TAG, "MEDIA_COMMAND command=$command targetPackage=$pkg")
            true
        } catch (e: Exception) {
            Log.w(TAG, "MEDIA_COMMAND failed: ${e.message}")
            false
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Session-change listeners
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * API 34+: fires immediately when Android changes the media-key session.
     * Signature: onMediaKeyEventSessionChanged(packageName: String, token: MediaSession.Token?)
     * Note: packageName is the new session's package, token is the new session token (may be null).
     */
    @Suppress("UNCHECKED_CAST")
    private val mediaKeySessionChangedListener: MediaSessionManager.OnMediaKeyEventSessionChangedListener =
        MediaSessionManager.OnMediaKeyEventSessionChangedListener { pkg, token ->
            // Called on listenerExecutor; re-evaluate on main thread
            mainHandler.post {
                Log.i(TAG,
                    "MEDIA_SESSION_CHANGED reason=MEDIA_KEY_SESSION_CHANGED " +
                    "oldPackage=${registeredController?.packageName} " +
                    "newPackage=$pkg")
                if (token != null) {
                    val ctrl = tokenToController(token)
                    if (ctrl != null) {
                        switchToController(ctrl, reason = "MEDIA_KEY_SESSION_CHANGED")
                    } else {
                        reevaluate()
                    }
                } else {
                    // Media-key session cleared — fall back to active sessions
                    reevaluate()
                }
            }
        }

    private val activeSessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            mainHandler.post {
                Log.d(TAG, "OnActiveSessionsChangedListener: ${controllers?.size ?: 0} sessions")
                reevaluate(controllers)
            }
        }

    // ──────────────────────────────────────────────────────────────────────────
    // Core selection logic  (all on main thread)
    // ──────────────────────────────────────────────────────────────────────────

    private fun reevaluate(hint: List<MediaController>? = null) {
        val sm = sessionManager ?: return
        val cn = listenerComponentName ?: return
        val ctx = context ?: return

        // 1. Try the media-key session token (most authoritative)
        val mkToken: MediaSession.Token? = try { sm.getMediaKeyEventSession() } catch (e: Exception) { null }
        if (mkToken != null) {
            val mkPkg: String? = try { sm.getMediaKeyEventSessionPackageName() } catch (e: Exception) { null }
            // Check if this is already the registered controller
            if (registeredController?.sessionToken == mkToken) {
                publishState(registeredController!!)
                return
            }
            val ctrl = tokenToController(mkToken)
            if (ctrl != null) {
                switchToController(ctrl, reason = "MEDIA_KEY_SESSION")
                return
            }
        }

        // 2. Inspect all active controllers
        val controllers = hint ?: try { sm.getActiveSessions(cn) } catch (e: Exception) { emptyList() }
        val best = selectBestController(controllers)

        if (best != null) {
            if (best.sessionToken != registeredController?.sessionToken) {
                switchToController(best, reason = "PLAYING_FALLBACK")
            } else {
                publishState(best)
            }
        } else {
            if (registeredController != null) {
                Log.i(TAG, "MEDIA_SESSION_CLEARED — no active sessions remain")
                detachCurrentController()
                currentState = null
                notifyListener(null)
            }
        }
    }

    private fun selectBestController(controllers: List<MediaController>): MediaController? {
        if (controllers.isEmpty()) return null
        return controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
            ?: controllers.firstOrNull()
    }

    private fun switchToController(newController: MediaController, reason: String) {
        val oldPkg = registeredController?.packageName
        val newPkg = newController.packageName
        Log.i(TAG,
            "MEDIA_SESSION_CHANGED " +
            "oldPackage=$oldPkg " +
            "newPackage=$newPkg " +
            "reason=$reason")

        detachCurrentController()
        registeredController = newController
        try {
            newController.registerCallback(controllerCallback, mainHandler)
        } catch (e: Exception) {
            Log.w(TAG, "Failed registering controller callback for $newPkg: ${e.message}")
        }
        // Immediately publish — do NOT wait for a callback
        publishState(newController)
    }

    private fun detachCurrentController() {
        try { registeredController?.unregisterCallback(controllerCallback) }
        catch (ignored: Exception) {}
        registeredController = null
    }

    private fun tokenToController(token: MediaSession.Token): MediaController? {
        val ctx = context ?: return null
        return try { MediaController(ctx, token) } catch (e: Exception) {
            Log.w(TAG, "Failed creating MediaController from token: ${e.message}")
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // State publication  (main thread)
    // ──────────────────────────────────────────────────────────────────────────

    private fun publishState(ctrl: MediaController) {
        val meta = ctrl.metadata
        val ps   = ctrl.playbackState

        val title  = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_AUTHOR)
            ?: ""
        val album  = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val isPlaying = ps?.state == PlaybackState.STATE_PLAYING
        val seq    = sequence.incrementAndGet()

        val state = MediaState(
            packageName     = ctrl.packageName,
            sessionIdentity = ctrl.sessionToken.hashCode().toString(),
            title           = title,
            artist          = artist,
            album           = album,
            isPlaying       = isPlaying,
            sequence        = seq
        )
        currentState = state

        Log.i(TAG,
            "MEDIA_STATE package=${ctrl.packageName} " +
            "state=${if (isPlaying) "PLAYING" else "PAUSED/STOPPED"} " +
            "title=\"$title\" artist=\"$artist\" album=\"$album\" seq=$seq")

        notifyListener(state)
    }

    private fun notifyListener(state: MediaState?) {
        mainHandler.post { onStateChangedListener?.invoke(state) }
    }
}
