package com.example.audiostreamer.usb

import java.util.concurrent.atomic.AtomicLong

/**
 * Live counters for the USB PCM receive path, shared by the [UsbPcmReceiver] (producer side) and the
 * audio output (consumer side) so the diagnostics section and UI read one consistent snapshot
 * (spec §6, §10). All fields are lock-free; only ever incremented/overwritten, never read-modify-write
 * across threads except the fill query which is derived from the [ring].
 */
class UsbPcmStats {
    /** Total PCM bytes delivered into the ring buffer. */
    val receivedBytes = AtomicLong(0L)

    /** Total audio frames (samples × channels) received. */
    val receivedFrames = AtomicLong(0L)

    /** Count of PCM_DATA messages received. */
    val receivedPackets = AtomicLong(0L)

    /** Consumer-side underruns (ring ran dry during playout). Incremented by the output. */
    val bufferUnderruns = AtomicLong(0L)

    /** Producer-side overflow drops (ring was full). Mirrors [PcmRingBuffer.overflowDroppedBytes] in frames. */
    val bufferOverflows = AtomicLong(0L)

    /** USB transport read/write faults (excluding clean detach). */
    val usbTransferErrors = AtomicLong(0L)

    /** Protocol errors surfaced to the host (malformed, unsupported version/format, sequencing). */
    val protocolErrors = AtomicLong(0L)

    /** Wall-clock (elapsedRealtime-style) ms at which the current stream started; 0 when not streaming. */
    val streamStartMs = AtomicLong(0L)

    /** The currently-granted PCM format, or null before a successful CONFIGURE. Volatile-safe ref. */
    @Volatile var configuredFormat: PcmFormat? = null
        private set

    fun setFormat(format: PcmFormat?) { configuredFormat = format }

    fun resetStreamCounters() {
        receivedBytes.set(0L)
        receivedFrames.set(0L)
        receivedPackets.set(0L)
        bufferUnderruns.set(0L)
        bufferOverflows.set(0L)
    }
}
