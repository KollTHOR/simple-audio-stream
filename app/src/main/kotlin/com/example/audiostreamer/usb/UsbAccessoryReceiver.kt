package com.example.audiostreamer.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.example.audiostreamer.AppLogger

/**
 * Bootstraps the USB PCM input path when a desktop ASLC host performs the AOA handshake.
 *
 * Why a receiver and not just the activity intent-filter: the framework records matching
 * activities (dumpsys usb shows them under accessory_attached_activities), but launching an
 * activity from the background is blocked by modern Android/OEM policies — so the activity route
 * silently no-ops on devices like the HiBy M300. USB accessory attach broadcasts are on the
 * implicit-broadcast exemption list, so a manifest receiver reliably fires, and starting the
 * foreground service from it is the sanctioned trigger.
 *
 * The receiver only starts UsbPcmService with ACTION_START; the service's tryAdoptAccessory()
 * picks up the connected accessory (and drives the accessory-permission grant from there).
 */
class UsbAccessoryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return
        // Gate: only arm the USB input while the receiver is listening. This broadcast can start
        // the process when nothing is listening; in that case ignore it and let the user's
        // "Start listening" arm UsbPcmService later (the accessory stays connected).
        if (!com.example.audiostreamer.AudioSinkService.isRunning.get()) {
            AppLogger.i(TAG, "USB: accessory attach while not listening — deferring (press Start listening)")
            return
        }
        AppLogger.i(TAG, "USB: accessory attach received — starting USB input service")
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, UsbPcmService::class.java)
                    .setAction(UsbPcmService.ACTION_START)
            )
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException surfaces here if the OEM treats this
            // broadcast as a background start; the user can still open the app (MainActivity's
            // ACCESSORY_ATTACHED filter + manual enable path remain).
            AppLogger.e(TAG, "USB: could not auto-start service (${e.message})")
        }
    }

    private companion object {
        const val TAG = "UsbAccessoryReceiver"
    }
}
