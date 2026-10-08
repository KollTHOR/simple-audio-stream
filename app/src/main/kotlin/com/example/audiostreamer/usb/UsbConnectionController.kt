package com.example.audiostreamer.usb

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.audiostreamer.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the wired (AOA) USB input lifecycle, fully independent of the wireless modes.
 *
 * The desktop node is the USB host and the phone is the accessory. This controller is the single
 * place that knows the USB rules:
 *  - find the attached accessory (poll / attach),
 *  - request the accessory permission from the Activity so the system dialog reliably appears,
 *  - **wait for the user to accept** before arming [UsbPcmService] (arming early produced a broken
 *    stop/start-again state),
 *  - expose one [phase] the UI renders.
 *
 * The UI stays dumb: it renders [phase] + [UsbState] and forwards Start/Stop. Nothing here touches
 * the network path.
 */
object UsbConnectionController {
    private const val TAG = "UsbConnection"

    /** Broadcast action for our own accessory-permission result. */
    const val ACTION_PERMISSION_RESULT = "com.example.audiostreamer.USB_ACCESSORY_PERMISSION"

    enum class Phase {
        /** Not wanted (Start not pressed / stopped). */
        IDLE,

        /** Wanted, but no accessory is attached yet (the host's handshake will bring one). */
        WAITING_FOR_HOST,

        /** Accessory present; waiting for the user to allow the permission dialog. */
        REQUESTING_PERMISSION,

        /** Permission granted; the input service is armed and waiting for the host to configure. */
        ARMED,

        /** The host connected and negotiated a format. */
        CONNECTED,

        /** Streaming PCM. */
        STREAMING
    }

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** The accessory identity we have already asked for (avoids dialog spam). */
    private var requestedFor: String? = null

    /** True once the user (or USB mode) wants the input running. */
    private var wanted = false

    /** Start (or re-arm) the USB input. Idempotent. */
    fun start(activity: Activity) {
        wanted = true
        sync(activity)
    }

    /** Stop the USB input and forget the pending permission request. */
    fun stop(activity: Activity) {
        wanted = false
        requestedFor = null
        if (UsbPcmService.isRunning.get()) {
            AppLogger.i(TAG, "disarming USB input")
            activity.startService(
                Intent(activity, UsbPcmService::class.java).setAction(UsbPcmService.ACTION_STOP)
            )
        }
        _phase.value = Phase.IDLE
    }

    /** Recompute the phase from the live USB state. Call on the poll tick and on accessory attach. */
    fun sync(activity: Activity) {
        if (!wanted) {
            _phase.value = Phase.IDLE
            return
        }
        val usb = activity.getSystemService(UsbManager::class.java) ?: return
        val accessory = try {
            usb.accessoryList?.firstOrNull()
        } catch (_: Exception) {
            null
        }
        if (accessory == null) {
            _phase.value = if (UsbPcmService.isRunning.get()) Phase.ARMED else Phase.WAITING_FOR_HOST
            return
        }
        if (usb.hasPermission(accessory)) {
            requestedFor = null
            if (!UsbPcmService.isRunning.get()) {
                arm(activity)
            }
            _phase.value = when {
                UsbState.state.value.streaming -> Phase.STREAMING
                UsbState.state.value.connected -> Phase.CONNECTED
                else -> Phase.ARMED
            }
        } else {
            // Permission required. NEVER read accessory.serial here: getSerial() is permission-gated
            // and throws SecurityException before the grant.
            val id = safeIdentity(accessory)
            if (requestedFor != id) {
                requestedFor = id
                requestPermission(activity, usb, accessory)
            }
            _phase.value = Phase.REQUESTING_PERMISSION
        }
    }

    /** Handle the accessory-permission result (from the Activity's broadcast receiver). */
    fun onPermissionResult(activity: Activity, granted: Boolean) {
        AppLogger.i(TAG, "accessory permission granted=$granted")
        requestedFor = null
        if (granted) {
            sync(activity)
        } else {
            _phase.value = if (wanted) Phase.WAITING_FOR_HOST else Phase.IDLE
        }
    }

    private fun arm(activity: Activity) {
        AppLogger.i(TAG, "arming USB input")
        ContextCompat.startForegroundService(
            activity,
            Intent(activity, UsbPcmService::class.java).setAction(UsbPcmService.ACTION_START)
        )
    }

    private fun requestPermission(activity: Activity, usb: UsbManager, accessory: UsbAccessory) {
        try {
            AppLogger.i(TAG, "requesting accessory permission")
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
            val pi = PendingIntent.getBroadcast(
                activity,
                0,
                Intent(ACTION_PERMISSION_RESULT).setPackage(activity.packageName),
                flags
            )
            usb.requestPermission(accessory, pi)
        } catch (e: Exception) {
            AppLogger.i(TAG, "permission request failed: ${e.message}")
        }
    }

    /** Identity for de-dup that never touches permission-gated fields. */
    private fun safeIdentity(accessory: UsbAccessory): String =
        listOfNotNull(accessory.manufacturer, accessory.model, accessory.description)
            .joinToString("|")
            .trim()
            .ifEmpty { "accessory" }
}
