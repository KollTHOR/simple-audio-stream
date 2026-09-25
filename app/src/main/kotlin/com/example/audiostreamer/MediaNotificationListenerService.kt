package com.example.audiostreamer

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import com.example.audiostreamer.AppLogger as Log

/**
 * High-definition Audio Transport Media Notification & Session Listener.
 *
 * Monitors Android MediaSession changes to extract rich track metadata (Title, Artist, Album,
 * Playback State) from active music players (e.g. Spotify, YouTube Music, Apple Music, Tidal,
 * Poweramp) and dispatches remote transport controls.
 */
class MediaNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MediaNotifListener"

        @Volatile
        var isServiceConnected: Boolean = false
            private set

        @Volatile
        var activeController: MediaController? = null
            private set

        data class TrackInfo(
            val title: String,
            val artist: String,
            val album: String,
            val isPlaying: Boolean
        )

        @Volatile
        var currentTrackInfo: TrackInfo? = null
            private set

        var onTrackChangedListener: ((TrackInfo) -> Unit)? = null

        /**
         * Dispatches remote transport controls directly to the active media controller.
         * Returns true if handled by an active controller, false otherwise.
         */
        fun dispatchMediaControl(command: Byte): Boolean {
            val controller = activeController
            if (controller != null) {
                try {
                    val tc = controller.transportControls
                    when (command) {
                        HatPacket.MEDIA_CMD_PLAY_PAUSE -> {
                            val state = controller.playbackState?.state
                            if (state == PlaybackState.STATE_PLAYING) {
                                tc.pause()
                            } else {
                                tc.play()
                            }
                        }
                        HatPacket.MEDIA_CMD_PLAY -> tc.play()
                        HatPacket.MEDIA_CMD_PAUSE -> tc.pause()
                        HatPacket.MEDIA_CMD_NEXT -> tc.skipToNext()
                        HatPacket.MEDIA_CMD_PREVIOUS -> tc.skipToPrevious()
                        else -> return false
                    }
                    Log.i(TAG, "Dispatched media command $command via MediaController ${controller.packageName}")
                    return true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed controlling via MediaController: ${e.message}")
                }
            }
            return false
        }
    }

    private var sessionManager: MediaSessionManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateActiveController(controllers)
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMetadata(metadata, activeController?.playbackState)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateMetadata(activeController?.metadata, state)
        }

        override fun onSessionDestroyed() {
            refreshControllers()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isServiceConnected = true
        Log.i(TAG, "MediaNotificationListenerService connected")

        try {
            sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val componentName = ComponentName(this, MediaNotificationListenerService::class.java)
            sessionManager?.addOnActiveSessionsChangedListener(sessionsChangedListener, componentName)
            refreshControllers()
        } catch (e: Exception) {
            Log.w(TAG, "Failed setting up sessions listener: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        isServiceConnected = false
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
            activeController?.unregisterCallback(controllerCallback)
        } catch (ignored: Exception) {}
        activeController = null
        super.onListenerDisconnected()
        Log.i(TAG, "MediaNotificationListenerService disconnected")
    }

    private fun refreshControllers() {
        try {
            val componentName = ComponentName(this, MediaNotificationListenerService::class.java)
            val controllers = sessionManager?.getActiveSessions(componentName)
            updateActiveController(controllers)
        } catch (e: Exception) {
            Log.w(TAG, "Failed refreshing active sessions: ${e.message}")
        }
    }

    @Synchronized
    private fun updateActiveController(controllers: List<MediaController>?) {
        if (controllers.isNullOrEmpty()) {
            activeController?.unregisterCallback(controllerCallback)
            activeController = null
            return
        }

        // Prioritize a controller that is currently playing
        val playingController = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()

        if (playingController != activeController) {
            activeController?.unregisterCallback(controllerCallback)
            activeController = playingController
            playingController?.registerCallback(controllerCallback, mainHandler)
            Log.i(TAG, "Active MediaController changed: ${playingController?.packageName}")
        }

        updateMetadata(playingController?.metadata, playingController?.playbackState)
    }

    private fun updateMetadata(metadata: MediaMetadata?, state: PlaybackState?) {
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_AUTHOR)
            ?: ""
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING

        val info = TrackInfo(
            title = title,
            artist = artist,
            album = album,
            isPlaying = isPlaying
        )

        currentTrackInfo = info
        mainHandler.post {
            onTrackChangedListener?.invoke(info)
        }
    }
}
