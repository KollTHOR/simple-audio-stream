package com.example.audiostreamer

/**
 * A tiny holder for "what is the app doing right now" that isn't already covered by
 * [StreamState] or the service flags. Written by the UI, read by diagnostics/log reports.
 */
object AppStatus {
    /** The mode the user last selected in the main screen: "Broadcast", "Receive" or "USB input". */
    @Volatile
    var currentMode: String = "Unknown"

    fun describeServices(): String = buildString {
        append("Broadcast=").append(if (AudioCaptureService.isRunning.get()) "running" else "stopped")
        append(", Receive=").append(if (AudioSinkService.isRunning.get()) "running" else "stopped")
        append(", USB=").append(if (com.example.audiostreamer.usb.UsbPcmService.isRunning.get()) "running" else "stopped")
    }
}
