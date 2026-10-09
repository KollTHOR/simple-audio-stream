package com.example.audiostreamer.usb

import android.app.Activity
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.audiostreamer.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Thin façade over the wired (AOA) USB input.
 *
 * The USB lifecycle is owned entirely by [UsbPcmService]: it polls for the accessory, requests the
 * accessory permission (once), adopts the pipe and streams. This object only remembers whether the
 * user wants USB input and derives a single [Phase] from the live [UsbState].
 *
 * Why: the accessory only exists *after* the desktop host performs the AOA handshake, so a one-shot
 * "check the accessory list now" can never be reliable, and doing it from the Activity meant the
 * phone only noticed the host on resume. Polling in the service makes the two sides converge no
 * matter which one starts first, with no ordering constraints.
 *
 * The wireless path is untouched.
 */
object UsbConnectionController {
    private const val TAG = "UsbConnection"

    enum class Phase {
        /** Not wanted (USB mode not active / stopped). */
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True once the user (USB mode) wants the input running. */
    private val wanted = MutableStateFlow(false)

    /**
     * The single phase the UI renders — a pure function of the live USB state (+ whether the user
     * wants input), so it is never stale and needs no Activity to update.
     */
    val phase: StateFlow<Phase> = combine(wanted, UsbState.state) { want, st ->
        when {
            st.streaming -> Phase.STREAMING
            st.connected -> Phase.CONNECTED
            st.awaitingPermission -> Phase.REQUESTING_PERMISSION
            st.accessoryPresent -> Phase.ARMED
            want -> Phase.WAITING_FOR_HOST
            else -> Phase.IDLE
        }
    }.stateIn(scope, SharingStarted.Eagerly, Phase.IDLE)

    /** Start (or re-arm) the USB input. Idempotent; the service always begins a fresh session. */
    fun start(activity: Activity) {
        if (wanted.value && UsbPcmService.isRunning.get()) {
            // Already armed — never restart a live session from an incidental trigger (cable
            // broadcast, mode re-entry).
            return
        }
        wanted.value = true
        AppLogger.i(TAG, "arming USB input")
        try {
            ContextCompat.startForegroundService(
                activity,
                Intent(activity, UsbPcmService::class.java).setAction(UsbPcmService.ACTION_START)
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "could not start USB input service: ${e.message}")
        }
    }

    /** Stop the USB input. */
    fun stop(activity: Activity) {
        wanted.value = false
        if (UsbPcmService.isRunning.get()) {
            AppLogger.i(TAG, "disarming USB input")
            try {
                activity.startService(
                    Intent(activity, UsbPcmService::class.java).setAction(UsbPcmService.ACTION_STOP)
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "could not stop USB input service: ${e.message}")
            }
        }
    }

    /**
     * The host stopped the stream (ASLC STOP) or the pipe closed, or the accessory detached. Return
     * to Idle so the phone UI matches the PC — stopping on either side stops both.
     */
    fun onRemoteStopped() {
        AppLogger.i(TAG, "session ended by the host/accessory — disarming")
        wanted.value = false
    }
}
