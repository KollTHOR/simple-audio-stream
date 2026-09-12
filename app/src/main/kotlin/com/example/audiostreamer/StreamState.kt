package com.example.audiostreamer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Telemetry(
    val isActive: Boolean = false,
    val isTransmitter: Boolean = false,
    val packetsTotal: Long = 0,
    val packetsPerSec: Int = 0,
    val bytesPerSec: Int = 0,
    val audioPeakPercent: Int = 0, // 0 - 100%
    val remoteEndpoint: String? = null,
    val statusDetail: String = "Idle",
    val bufferFillPercent: Int = 0,
    val bufferSlotsUsed: Int = 0,
    val bufferSlotsTotal: Int = AudioConfig.MUSIC_JITTER_BUFFER_SLOTS,
    val streamProfileName: String = "Music Mode",
    val sampleRate: Int = AudioConfig.SAMPLE_RATE,
    val bitDepth: Int = 24,
    val channels: Int = AudioConfig.CHANNELS,
    val bitrateKbps: Int = 2304,
    val isSilenceSuppressed: Boolean = false,
    val fecRecoveredTotal: Long = 0L,
    val activeReceiversCount: Int = 1,
    val remoteVolumePercent: Int = 100,
    val sourceCapabilityDesc: String = "24-bit • 48.0 kHz Stereo",
    val receiverCapabilityDesc: String = "Unknown",
    val negotiatedFormatDesc: String = "48.0 kHz • 24-bit Stereo PCM"
)

object StreamState {
    private val _telemetry = MutableStateFlow(Telemetry())
    val telemetry: StateFlow<Telemetry> = _telemetry.asStateFlow()

    fun update(transform: (Telemetry) -> Telemetry) {
        _telemetry.value = transform(_telemetry.value)
    }

    fun reset() {
        _telemetry.value = Telemetry()
    }
}
