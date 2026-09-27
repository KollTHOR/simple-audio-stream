package com.example.audiostreamer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

enum class AlertSeverity { INFO, WARN, ERROR }

/** A single user-facing alert. [id] increases monotonically so UI can dedupe replayed state. */
data class UserAlert(
    val id: Long,
    val severity: AlertSeverity,
    val message: String,
    val timestampMs: Long
)

/**
 * Central user-facing alert bus.
 *
 * Rules of the road:
 * - Errors reported through [AppLogger.e] arrive here automatically (see [publishFromLogger]),
 *   so a failure never depends on someone remembering to add a Toast.
 * - WARN/INFO only surface when a call site opts in explicitly, to keep the bus quiet.
 * - When the UI is in the foreground, [MainActivity] renders the latest alert as a Snackbar.
 *   When it is not (locked screen, another app), WARN/ERROR alerts post a heads-up notification
 *   instead — a streaming app whose failures are only visible while you stare at it is useless.
 *
 * This class never calls back into [AppLogger]/[HatDiagnostics]: those funnel *into* here, and a
 * reverse call would recurse.
 */
object UserAlertCenter {

    const val CHANNEL_ID = "hat_alerts"
    private const val NOTIFICATION_ID = 3001

    private val DIAGNOSTIC_NOISE = Regex("\\b(?:run|stream|gen)=[^\\s]+")
    private val COLLAPSE_SPACES = Regex("\\s{2,}")

    private val counter = AtomicLong(0)
    private val throttle = AlertThrottle()

    private val _latest = MutableStateFlow<UserAlert?>(null)
    val latest: StateFlow<UserAlert?> = _latest.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var appLevelChannelReady = false

    /** True while a visible UI (MainActivity) is in the foreground handling alerts. */
    @Volatile
    var uiVisible: Boolean = false

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun info(message: String) = publish(AlertSeverity.INFO, message)

    fun warn(message: String) = publish(AlertSeverity.WARN, message)

    fun error(message: String) = publish(AlertSeverity.ERROR, message)

    /**
     * Bridge for [AppLogger.e]. The message is used verbatim after trimming the caller's tag
     * prefix noise; the throttle guarantees retries of the same failure collapse into one alert.
     */
    fun publishFromLogger(message: String) {
        publish(AlertSeverity.ERROR, humanize(message))
    }

    /**
     * Errors rendered by [HatDiagnostics] embed internal identifiers (`run=… stream=… gen=…`)
     * that mean nothing to a user; drop them so the Snackbar stays readable.
     */
    private fun humanize(message: String): String =
        message
            .replace(DIAGNOSTIC_NOISE, "")
            .replace(COLLAPSE_SPACES, " ")
            .trim()

    private fun publish(severity: AlertSeverity, message: String) {
        val clean = message.trim()
        if (clean.isEmpty()) return
        val now = System.currentTimeMillis()
        when (throttle.accept("${severity.name}|$clean", now)) {
            AlertThrottle.Verdict.DUPLICATE_SUPPRESSED,
            AlertThrottle.Verdict.RATE_LIMITED -> return
            AlertThrottle.Verdict.ALLOW -> Unit
        }
        val alert = UserAlert(
            id = counter.incrementAndGet(),
            severity = severity,
            message = clean,
            timestampMs = now
        )
        _latest.value = alert
        if (severity != AlertSeverity.INFO && !uiVisible) {
            postNotification(alert)
        }
    }

    /** Clears the bus and its throttle history. Used by tests and by a full app-reset path. */
    fun reset() {
        throttle.reset()
        _latest.value = null
    }

    /**
     * Marks [alert] as presented so a config change / re-subscription does not replay a stale
     * Snackbar. Only clears the slot if it still holds that same alert.
     */
    fun consume(alert: UserAlert) {
        if (_latest.value?.id == alert.id) _latest.value = null
    }

    /** Posts a heads-up notification for alerts raised while no UI is in the foreground. */
    private fun postNotification(alert: UserAlert) {
        val context = appContext ?: return
        ensureNotificationChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isCritical = alert.severity == AlertSeverity.ERROR
        val title = if (isCritical) "Audio Stream error" else "Audio Stream warning"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_transmitter)
            .setContentTitle(title)
            .setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
            .setPriority(if (isCritical) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            // Notification posting must never crash a streaming path.
        }
    }

    private fun ensureNotificationChannel(context: Context) {
        if (appLevelChannelReady || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Stream alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Connection failures and other issues that need your attention."
                }
                manager?.createNotificationChannel(channel)
            }
        } catch (_: Exception) {
        }
        appLevelChannelReady = true
    }
}
