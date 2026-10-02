package com.example.audiostreamer.usb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable state for the USB input source, kept SEPARATE from the network [com.example.audiostreamer.StreamState]
 * so USB never perturbs the network telemetry struct (spec: leave network streaming unchanged). The UI
 * card and diagnostics render from this; the service pushes updates.
 */
data class UsbUiState(
    val connected: Boolean = false,
    val streaming: Boolean = false,
    val format: PcmFormat? = null,
    val bytesPerSec: Long = 0L,
    val framesPerSec: Long = 0L,
    val bufferFillPercent: Int = 0,
    val bufferUnderruns: Long = 0L,
    val usbErrors: Long = 0L,
    val uptimeMs: Long = 0L,
    val outputDevice: String = "",
    val statusDetail: String = "Idle"
) {
    val formatLabel: String get() = format?.displayLabel() ?: "—"
    val channelLabel: String get() = when (format?.channels) {
        1 -> "Mono"; 2 -> "Stereo"; null -> "—"; else -> "${format.channels}ch"
    }
}

object UsbState {
    private val _state = MutableStateFlow(UsbUiState())
    val state: StateFlow<UsbUiState> = _state.asStateFlow()

    fun update(transform: (UsbUiState) -> UsbUiState) {
        _state.value = transform(_state.value)
    }

    fun reset() {
        _state.value = UsbUiState()
        stats = null
        transportName = ""
        transportState = "DISCONNECTED"
        advertisedCaps = null
    }

    // ---- Diagnostics backing (written by UsbPcmService, read by the HatDiagnostics "USB" section) ----

    /** Live stats of the current session, or null when disconnected. */
    @Volatile var stats: UsbPcmStats? = null

    /** Human-readable accessory description (manufacturer/model), or "" when none. */
    @Volatile var transportName: String = ""

    /** AOA lifecycle tag: DISCONNECTED / ATTACHED / NEGOTIATING / STREAMING / ERROR. */
    @Volatile var transportState: String = "DISCONNECTED"

    /** The capability set actually advertised after the runtime probe narrowed it. */
    @Volatile var advertisedCaps: PcmCapabilities? = null

    fun diagnosticsSnapshot(): Map<String, Any?> {
        val s = stats
        val st = _state.value
        return linkedMapOf(
            "connected" to st.connected,
            "streaming" to st.streaming,
            "accessory" to transportName,
            "transportState" to transportState,
            "format" to st.format?.displayLabel().orEmpty(),
            "sampleRate" to (st.format?.sampleRate ?: 0),
            "bitDepth" to (st.format?.bitDepth ?: 0),
            "channels" to (st.format?.channels ?: 0),
            "bytesPerSec" to st.bytesPerSec,
            "framesPerSec" to st.framesPerSec,
            "receivedBytes" to (s?.receivedBytes?.get() ?: 0L),
            "receivedFrames" to (s?.receivedFrames?.get() ?: 0L),
            "receivedPackets" to (s?.receivedPackets?.get() ?: 0L),
            "bufferFillPercent" to st.bufferFillPercent,
            "bufferUnderruns" to (s?.bufferUnderruns?.get() ?: 0L),
            "bufferOverflows" to (s?.bufferOverflows?.get() ?: 0L),
            "usbTransferErrors" to (s?.usbTransferErrors?.get() ?: 0L),
            "protocolErrors" to (s?.protocolErrors?.get() ?: 0L),
            "uptimeMs" to st.uptimeMs,
            "outputDevice" to st.outputDevice,
            "advertisedSampleRates" to advertisedCaps?.sampleRates?.joinToString(),
            "advertisedBitDepths" to advertisedCaps?.bitDepths?.joinToString(),
            "advertisedChannels" to advertisedCaps?.channels?.joinToString()
        )
    }
}
