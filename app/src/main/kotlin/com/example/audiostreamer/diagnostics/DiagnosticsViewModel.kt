package com.example.audiostreamer.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.audiostreamer.HatDiagnostics
import kotlinx.coroutines.flow.StateFlow

/**
 * Lifecycle-aware ViewModel driving the receiver runtime diagnostics UI.
 *
 * Exposes thread-safe, immutable [ReceiverDiagnosticsState] snapshots and
 * rolling latency history to the UI layer.
 *
 * Latency is an Estimated Receiver Playout Latency derived strictly from receiver
 * jitter-buffer occupancy plus queued AudioTrack playback frames.
 */
class DiagnosticsViewModel : ViewModel() {

    val state: StateFlow<ReceiverDiagnosticsState> =
        ReceiverDiagnosticsRepository.diagnosticsState

    val latencyHistory: LatencyHistory
        get() = ReceiverDiagnosticsRepository.latencyHistory

    fun startSampling() {
        ReceiverDiagnosticsRepository.startSampling(viewModelScope)
    }

    fun stopSampling() {
        ReceiverDiagnosticsRepository.stopSampling()
    }

    fun currentSnapshot(): ReceiverDiagnosticsState {
        return ReceiverDiagnosticsRepository.snapshot()
    }

    fun getDiagnosticSnapshotText(): String {
        return HatDiagnostics.snapshot()
    }

    fun clearHistory() {
        ReceiverDiagnosticsRepository.clearHistory()
    }

    override fun onCleared() {
        super.onCleared()
        stopSampling()
    }
}
