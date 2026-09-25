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

        @Volatile
        var currentInstance: MediaNotificationListenerService? = null
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

        /**
         * Extracts album artwork bitmap from active notification for [packageName]
         * as a fallback when MediaMetadata does not carry the artwork bitmap directly.
         */
        fun getArtworkForPackage(packageName: String): android.graphics.Bitmap? {
            val service = currentInstance ?: return null
            val notifs = try { service.activeNotifications } catch (e: Exception) { null } ?: return null
            for (sbn in notifs) {
                if (sbn.packageName == packageName) {
                    val extras = sbn.notification?.extras ?: continue
                    // 1. EXTRA_PICTURE (BigPictureStyle)
                    val pic = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        extras.getParcelable(android.app.Notification.EXTRA_PICTURE, android.graphics.Bitmap::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        extras.getParcelable(android.app.Notification.EXTRA_PICTURE)
                    }
                    if (pic != null) return pic

                    // 2. EXTRA_LARGE_ICON_BIG
                    val largeBig = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        extras.getParcelable(android.app.Notification.EXTRA_LARGE_ICON_BIG, android.graphics.Bitmap::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        extras.getParcelable(android.app.Notification.EXTRA_LARGE_ICON_BIG)
                    }
                    if (largeBig != null) return largeBig

                    // 3. EXTRA_LARGE_ICON
                    val largeIcon = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        extras.getParcelable(android.app.Notification.EXTRA_LARGE_ICON, android.graphics.Bitmap::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        extras.getParcelable(android.app.Notification.EXTRA_LARGE_ICON)
                    }
                    if (largeIcon != null) return largeIcon

                    // 4. getLargeIcon()
                    try {
                        val icon = sbn.notification.getLargeIcon()
                        if (icon != null) {
                            val drawable = icon.loadDrawable(service)
                            if (drawable is android.graphics.drawable.BitmapDrawable) {
                                return drawable.bitmap
                            } else if (drawable != null && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                                val bmp = android.graphics.Bitmap.createBitmap(
                                    drawable.intrinsicWidth.coerceAtMost(256),
                                    drawable.intrinsicHeight.coerceAtMost(256),
                                    android.graphics.Bitmap.Config.ARGB_8888
                                )
                                val canvas = android.graphics.Canvas(bmp)
                                drawable.setBounds(0, 0, canvas.width, canvas.height)
                                drawable.draw(canvas)
                                return bmp
                            }
                        }
                    } catch (ignored: Exception) {}
                }
            }
            return null
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        currentInstance = this
        isServiceConnected = true
        Log.i(TAG, "MediaNotificationListenerService connected — starting MediaSessionTracker")
        val cn = ComponentName(this, MediaNotificationListenerService::class.java)
        MediaSessionTracker.start(this, cn)
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "MediaNotificationListenerService disconnected — stopping MediaSessionTracker")
        MediaSessionTracker.stop()
        isServiceConnected = false
        currentInstance = null
        super.onListenerDisconnected()
    }
}
