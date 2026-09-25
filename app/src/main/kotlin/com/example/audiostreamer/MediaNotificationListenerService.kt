package com.example.audiostreamer

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import com.example.audiostreamer.AppLogger as Log

/**
 * HAT Media Notification Listener Service.
 *
 * This service's sole responsibility is to hold the NotificationListenerService binding
 * that grants [android.media.session.MediaSessionManager] access to active media sessions.
 *
 * All session tracking, controller selection, metadata extraction, and transport control
 * dispatch is delegated to [MediaSessionTracker].
 *
 * Separated per specification §13: AudioPlaybackDetector (audio format detection) and
 * MediaSessionTracker (media metadata + transport control) must NOT compete.
 */
class MediaNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MediaNotifListener"

        @Volatile
        var isServiceConnected: Boolean = false
            private set

        /**
         * Convenience for legacy call-sites in AudioCaptureService.
         * Returns the currently tracked state from [MediaSessionTracker].
         */
        val currentTrackInfo: MediaSessionTracker.MediaState?
            get() = MediaSessionTracker.currentState

        /**
         * Dispatches a HAT media command to the current MediaSessionTracker controller.
         * Falls back gracefully if no controller is available.
         */
        fun dispatchMediaControl(command: Byte): Boolean =
            MediaSessionTracker.dispatchCommand(command)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isServiceConnected = true
        Log.i(TAG, "MediaNotificationListenerService connected — starting MediaSessionTracker")
        val cn = ComponentName(this, MediaNotificationListenerService::class.java)
        MediaSessionTracker.start(this, cn)
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "MediaNotificationListenerService disconnected — stopping MediaSessionTracker")
        MediaSessionTracker.stop()
        isServiceConnected = false
        super.onListenerDisconnected()
    }
}
