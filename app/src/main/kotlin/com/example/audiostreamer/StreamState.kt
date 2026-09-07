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
    val statusDetail: String = "Idle"
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
