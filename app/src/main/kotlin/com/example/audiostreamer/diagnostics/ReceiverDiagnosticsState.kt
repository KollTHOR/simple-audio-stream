package com.example.audiostreamer.diagnostics

/**
 * =================================================================================================
 * LATENCY DEFINITION & DERIVATION MODEL
 * =================================================================================================
 *
 * Estimated Receiver Playout Latency is defined as the total estimated delay (in milliseconds)
 * currently queued across the receiver pipeline awaiting playout.
 *
 * It is derived strictly as an estimate from receiver jitter-buffer occupancy plus queued
 * AudioTrack playback frames:
 *
 * 1. JITTER BUFFER AUDIO DEPTH (T_jb):
 *    The audio duration currently queued in the JitterBuffer awaiting readout by the
 *    AudioPlaybackThread.
 *
 *    T_jb = availablePackets * packetDurationMs
 *
 *    Equivalently in timeline frames:
 *    T_jb = ((latestReceivedTimelineFrame - nextReadTimelineFrame) / sampleRate) * 1000.0
 *
 * 2. AUDIOTRACK HARDWARE QUEUE DEPTH (T_track):
 *    The audio duration of decoded PCM frames written into the Android AudioTrack buffer
 *    that have not yet been rendered.
 *
 *    framesPending = max(0, diagFramesWritten - audioTrack.playbackHeadPosition)
 *    T_track = (framesPending / sampleRate) * 1000.0
 *    (Clamped to track.bufferSizeInFrames / sampleRate * 1000.0 to reflect active hardware buffer capacity)
 *
 * 3. TOTAL ESTIMATED RECEIVER PLAYOUT LATENCY (T_playout):
 *    T_playout = T_jb + T_track
 *
 * BOUNDARIES & EXCLUSIONS:
 * - This metric is an estimate derived purely from receiver jitter-buffer occupancy plus queued
 *   AudioTrack playback frames.
 * - It does NOT measure network one-way latency.
 * - It does NOT measure wall-clock end-to-end latency.
 * - It does NOT measure physical speaker acoustic emission latency.
 * - Observational and read-only: safely sampled from atomic counters without taking audio locks.
 * =================================================================================================
 */
data class ReceiverDiagnosticsState(
    val isReceiving: Boolean = false,
    val sampleRate: Int = 48000,
    val profileName: String = "Auto",

    // --- Primary Metric: Estimated Receiver Playout Latency ---
    /** Total Estimated Receiver Playout Latency (JitterBuffer depth + AudioTrack playback queue depth) in ms. */
    val estimatedPlayoutLatencyMs: Float = 0f,

    /** Audio duration currently queued in the JitterBuffer in ms. */
    val jitterBufferMs: Float = 0f,

    /** Decoded audio duration queued in the AudioTrack hardware buffer awaiting playback in ms. */
    val audioTrackBufferMs: Float = 0f,

    /** Target watermark latency computed by RFC 3550 jitter estimator in ms. */
    val targetWatermarkMs: Float = 0f,

    // --- Jitter & Buffer Subsystem State ---
    /** RFC 3550 statistical inter-arrival jitter in ms. */
    val jitterMs: Double = 0.0,

    /** Number of occupied packet slots in the JitterBuffer. */
    val bufferAvailableSlots: Int = 0,

    /** Total configured capacity of the JitterBuffer in slots. */
    val bufferTotalSlots: Int = 0,

    /** Buffer fill percentage (0 to 100%). */
    val bufferFillPercent: Int = 0,

    /** Clock drift correction ratio relative to unity (1.0 = nominal). */
    val driftCorrectionRatio: Double = 1.0,

    // --- Network & Recovery Counters ---
    val packetsReceived: Long = 0L,
    val packetsLost: Long = 0L,
    val packetsLate: Long = 0L,
    val packetsDuplicate: Long = 0L,
    val packetsOutOfOrder: Long = 0L,
    val fecRecovered: Long = 0L,

    // --- AudioTrack Hardware & Playout Counters ---
    val underruns: Long = 0L,
    val audioTrackWrites: Long = 0L,
    val framesWritten: Long = 0L,
    val playbackHead: Long = 0L,

    val timestampMs: Long = System.currentTimeMillis()
)
