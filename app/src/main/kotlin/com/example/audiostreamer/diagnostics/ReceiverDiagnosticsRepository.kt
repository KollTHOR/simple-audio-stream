package com.example.audiostreamer.diagnostics

import com.example.audiostreamer.AudioSinkService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Observational, read-only repository managing receiver runtime diagnostics
 * and latency history for the UI.
 *
 * Estimated Receiver Playout Latency is derived strictly from receiver jitter-buffer
 * occupancy plus queued AudioTrack playback frames. It does not measure network one-way
 * latency, wall-clock end-to-end latency, or physical speaker acoustic emission latency.
 *
 * Design constraints:
 * - Purely observational: never mutates audio track, jitter buffer, or network transport.
 * - Samples at 500ms intervals on Dispatchers.Default (never runs on audio threads).
 * - Thread-safe StateFlow emits immutable [ReceiverDiagnosticsState] snapshots to the UI.
 */
object ReceiverDiagnosticsRepository {

    private val _diagnosticsState = MutableStateFlow(ReceiverDiagnosticsState())
    val diagnosticsState: StateFlow<ReceiverDiagnosticsState> = _diagnosticsState.asStateFlow()

    val latencyHistory = LatencyHistory()

    private var samplingJob: Job? = null

    /**
     * Obtains an immediate, thread-safe point-in-time snapshot of the receiver's state.
     */
    fun snapshot(): ReceiverDiagnosticsState {
        return AudioSinkService.snapshotReceiverDiagnostics() ?: ReceiverDiagnosticsState()
    }

    /**
     * Starts periodic sampling at 500ms intervals within the provided [coroutineScope].
     * Typically launched in lifecycleScope while the Diagnostics screen is visible.
     */
    @Synchronized
    fun startSampling(scope: CoroutineScope) {
        if (samplingJob?.isActive == true) return

        samplingJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val state = snapshot()
                _diagnosticsState.value = state

                if (state.isReceiving && state.estimatedPlayoutLatencyMs > 0f) {
                    latencyHistory.addSample(state.estimatedPlayoutLatencyMs)
                }

                delay(500L) // 2 Hz sampling
            }
        }
    }

    /**
     * Stops periodic background sampling when the UI is paused or navigated away from.
     */
    @Synchronized
    fun stopSampling() {
        samplingJob?.cancel()
        samplingJob = null
    }

    fun clearHistory() {
        latencyHistory.clear()
    }
}
