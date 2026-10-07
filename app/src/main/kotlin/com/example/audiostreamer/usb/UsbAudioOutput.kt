package com.example.audiostreamer.usb

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log

/**
 * Consumes PCM from the [PcmRingBuffer] and plays it through a self-contained [AudioTrack]
 * (spec §5 "UsbTransport -> UsbPcmReceiver -> PcmStream -> AudioOutput", §7, §8).
 *
 * This is intentionally NOT [com.example.audiostreamer.AudioSinkService]'s track — it lives in its own
 * class so the USB path shares no mutable state with the network path (spec: leave network streaming
 * unchanged). It mirrors the sink's proven AudioTrack builder conventions (MODE_STREAM, USAGE_MEDIA /
 * CONTENT_TYPE_MUSIC, min-buffer sizing, a LOW_LATENCY-then-NONE retry) but keeps them independent.
 *
 * Routing to an external USB DAC is handled by Android itself: a track built for USAGE_MEDIA follows
 * the OS's currently-selected output (which becomes the USB DAC when one is attached as a host
 * device). We do not fake a DAC and do not claim bit-perfect — we guarantee unmodified PCM bytes with
 * no resample/codec, nothing more (spec §7, §8, §14).
 */
class UsbAudioOutput(
    private val ring: PcmRingBuffer,
    private val stats: UsbPcmStats,
    private val onLog: (String) -> Unit,
    private val onUnderrun: () -> Unit
) {

    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    private var format: PcmFormat? = null

    /** (Re)builds the AudioTrack for [newFormat]. Safe to call before [start] or on a format change. */
    @Synchronized
    fun configure(newFormat: PcmFormat) {
        if (format == newFormat && track?.state == AudioTrack.STATE_INITIALIZED) return
        // A format change mid-session must restart the playout on the NEW track: start()'s running
        // guard would otherwise skip play() and the ring would back up (silent, 0 KB/s).
        val wasRunning = running
        if (wasRunning) stopPlayout()
        releaseTrack()
        // Drain queued bytes: they were framed in the old geometry and would misalign the playout
        // reader once the format changes (live reconfigure).
        ring.reset()
        // Size the jitter cushion to the format (~120 ms before power-of-two rounding) so high
        // sample rates / 32-bit streams don't underrun on a fixed 48k-sized ring.
        ring.resize(newFormat.sampleRate * newFormat.bytesPerFrame * 12 / 100)
        val encoding = newFormat.toAudioEncodingConstant()
        if (encoding == null) {
            onLog("USB: no AudioFormat encoding for ${newFormat.displayLabel()}")
            return
        }
        val channelMask = if (newFormat.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = UsbPcmProber.probeMinBufferBytes(newFormat)
        if (minBuffer <= 0) {
            onLog("USB: device cannot render ${newFormat.displayLabel()} (minBuffer=$minBuffer)")
            return
        }
        // Low latency: size the AudioTrack at the device minimum. The ring buffer (not the track)
        // absorbs USB jitter, so sizing the track to ring.capacity only added ~680 ms of latency.
        val bufferBytes = minBuffer

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val audioFormat = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(newFormat.sampleRate)
            .setChannelMask(channelMask)
            .build()

        val perfMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            AudioTrack.PERFORMANCE_MODE_LOW_LATENCY else AudioTrack.PERFORMANCE_MODE_NONE

        var t = try {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferBytes)
                .setPerformanceMode(perfMode)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            onLog("USB: AudioTrack build failed (${e.message}); retrying PERFORMANCE_MODE_NONE")
            null
        }
        if (t == null || t.state != AudioTrack.STATE_INITIALIZED) {
            try { t?.release() } catch (_: Exception) {}
            t = try {
                AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferBytes)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Exception) {
                onLog("USB: AudioTrack retry failed: ${e.message}")
                null
            }
        }
        if (t == null || t.state != AudioTrack.STATE_INITIALIZED) {
            onLog("USB: AudioTrack initialization failed for ${newFormat.displayLabel()}")
            try { t?.release() } catch (_: Exception) {}
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try { t.setBufferSizeInFrames(minBuffer / newFormat.bytesPerFrame) } catch (_: Exception) {}
        }
        track = t
        format = newFormat
        onLog("USB: output ready ${newFormat.displayLabel()} minBuf=$minBuffer buf=$bufferBytes")
        // Resume on the newly built track if we were playing before the reconfigure.
        if (wasRunning) start()
    }

    @Synchronized
    fun start() {
        if (running) return
        val t = track ?: return
        running = true
        try {
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
        } catch (e: Exception) {
            onLog("USB: AudioTrack.play failed: ${e.message}")
        }
        thread = Thread({ playoutLoop(t) }, "UsbPcmPlayout").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 2
            start()
        }
    }

    private fun playoutLoop(t: AudioTrack) {
        val fmt = format ?: return
        val bpf = fmt.bytesPerFrame
        // Reusable write buffer; never allocate per callback in the steady state (spec §11).
        val chunkFrames = (fmt.sampleRate / 100).coerceAtLeast(16) // ~10ms granularity
        val chunk = ByteArray(chunkFrames * bpf)
        val silence = ByteArray(chunk.size)
        while (running) {
            val want = chunk.size - (chunk.size % bpf)
            val got = ring.read(chunk, 0, want)
            if (got <= 0) {
                // No data yet: feed silence to keep the track clocked, and record the underrun so the
                // consumer can see USB starved the output (spec §6/§12) without killing the stream.
                stats.bufferUnderruns.incrementAndGet()
                onUnderrun()
                val n = silence.size
                try { t.write(silence, 0, n, AudioTrack.WRITE_BLOCKING) } catch (e: Exception) { break }
                continue
            }
            // Keep writes frame-aligned (USB gives us whole frames, but clamp defensively).
            val aligned = got - (got % bpf)
            if (aligned > 0) {
                try { t.write(chunk, 0, aligned, AudioTrack.WRITE_BLOCKING) } catch (e: Exception) { break }
            }
        }
    }

    /** Stops the playout thread without touching the track (used on teardown and reconfigure). */
    @Synchronized
    private fun stopPlayout() {
        running = false
        val th = thread
        thread = null
        try { th?.join(750) } catch (_: InterruptedException) {}
    }

    @Synchronized
    fun stop() {
        stopPlayout()
        val t = track
        if (t != null) {
            try { t.stop() } catch (e: Exception) { Log.d(TAG, "track.stop: ${e.message}") }
            // Flush any residual queued playback so a STOP doesn't leak audio after teardown.
            try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) t.pause() else Unit } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun shutdown() {
        stop()
        releaseTrack()
        format = null
    }

    @Synchronized
    private fun releaseTrack() {
        val t = track
        track = null
        if (t != null) {
            try { t.release() } catch (_: Exception) {}
        }
    }

    private companion object {
        const val TAG = "UsbAudioOutput"
    }
}
