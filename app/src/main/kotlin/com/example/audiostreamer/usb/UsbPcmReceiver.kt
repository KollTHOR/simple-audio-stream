package com.example.audiostreamer.usb

import java.io.InputStream
import java.io.OutputStream

/**
 * The ASLC receive-side state machine. Consumes framed messages from a reliable byte [InputStream]
 * and drives: capability advertisement, format negotiation, PCM ingestion into a [PcmRingBuffer],
 * and protocol-level error reporting — WITHOUT knowing anything about USB, AudioTrack, or Android.
 *
 * This is the transport-independent "PCM receiver" of the spec §5 pipeline
 * (`UsbTransport -> UsbPcmReceiver -> PcmStream(ring) -> AudioOutput`). It can be fed by any ordered
 * byte stream, which is exactly what makes the same framing reusable by a future desktop/Network PCM
 * source (spec §13).
 *
 * Threading: [pump] is a blocking loop meant to run on a dedicated daemon thread (never the UI
 * thread). One logical producer (this class) writes the ring; the audio output is the consumer.
 */
class UsbPcmReceiver(
    private val output: OutputStream,
    private val capabilities: () -> PcmCapabilities,
    private val probe: (PcmFormat) -> Int,
    private val ring: PcmRingBuffer,
    private val stats: UsbPcmStats,
    private val listener: Listener = NOOP_LISTENER,
    private val verboseLogging: () -> Boolean = { false }
) {

    enum class State { IDLE, HELLO_SEEN, CAPABLES_SENT, CONFIGURED, STREAMING }

    interface Listener {
        fun onStateChange(state: State) {}
        fun onConfigured(format: PcmFormat) {}
        fun onStreamStarted(format: PcmFormat) {}
        fun onStreamStopped() {}
        fun onLog(message: String) {}
        fun onProtocolError(code: Int, detail: String) {}
    }

    @Volatile private var state: State = State.IDLE
    @Volatile private var configuredFormat: PcmFormat? = null
    @Volatile private var stopRequested = false

    /** Reusable scratch buffers — grown as needed, never reallocated per frame in the steady state. */
    private var headerScratch = ByteArray(AslcProtocol.HEADER_SIZE)
    private var pcmScratch = ByteArray(0)

    private val outSeq = java.util.concurrent.atomic.AtomicLong(0L)

    // ---- Response senders ----------------------------------------------------------------

    fun sendHelloAndCapabilities() {
        val caps = capabilities()
        writeMessage(AslcProtocol.MSG_HELLO, AslcPayload.helloPayload(AslcProtocol.PROTOCOL_VERSION, isDevice = true, roleTag = "Android"))
        writeMessage(AslcProtocol.MSG_CAPABILITIES, AslcPayload.capabilitiesPayload(caps))
        transition(State.CAPABLES_SENT)
    }

    private fun sendError(code: Int, offendingMessageType: Int, detail: String) {
        stats.protocolErrors.incrementAndGet()
        writeMessage(AslcProtocol.MSG_ERROR, AslcPayload.errorPayload(code, offendingMessageType, detail))
        listener.onProtocolError(code, detail)
    }

    private fun writeMessage(messageType: Int, payload: ByteArray) {
        val scratch = ByteArray(AslcProtocol.HEADER_SIZE)
        try {
            AslcProtocol.writeFrame(
                out = output,
                scratch12 = scratch,
                messageType = messageType,
                payload = payload,
                payloadOffset = 0,
                payloadLength = payload.size,
                sequence = outSeq.getAndIncrement()
            )
            output.flush()
        } catch (e: Exception) {
            listener.onLog("USB: write failed for ${AslcProtocol.describeMessageType(messageType)}: ${e.message}")
        }
    }

    // ---- Main read loop (run on a daemon thread) -----------------------------------------

    /**
     * Reads and dispatches frames until end-of-stream (clean detach), [stopRequested], or a
     * non-recoverable framing break (version mismatch / unparseable header — the transport layer is
     * expected to rebuild the pipe on the next attach). Returns a reason for the loop ending.
     */
    fun pump(input: InputStream): EndReason {
        while (!stopRequested) {
            if (!AslcProtocol.readFully(input, headerScratch, 0, AslcProtocol.HEADER_SIZE)) {
                return EndReason.EOF // host detached
            }
            val header = AslcProtocol.decodeHeader(headerScratch, 0)
                ?: return EndReason.MALFORMED // cannot resync a stream reliably -> hand back to transport

            if (!header.isValidVersion) {
                sendError(AslcProtocol.ERR_VERSION_UNSUPPORTED, header.messageType, "peer version ${header.protocolVersion}")
                return EndReason.VERSION_MISMATCH
            }

            if (header.messageType == AslcProtocol.MSG_PCM_DATA) {
                val reason = handlePcmData(input, header.payloadLength)
                if (reason != null) return reason
            } else {
                val payload = ByteArray(header.payloadLength)
                if (header.payloadLength > 0 && !AslcProtocol.readFully(input, payload, 0, header.payloadLength)) {
                    return EndReason.EOF
                }
                handleControlFrame(header, payload)
            }
        }
        return EndReason.STOPPED
    }

    enum class EndReason { EOF, MALFORMED, VERSION_MISMATCH, STOPPED }

    // ---- Control-plane dispatch ----------------------------------------------------------

    private fun handleControlFrame(header: AslcProtocol.AslcHeader, payload: ByteArray) {
        when (header.messageType) {
            AslcProtocol.MSG_HELLO -> {
                val hello = AslcPayload.parseHello(payload, 0, payload.size)
                if (hello == null) {
                    sendError(AslcProtocol.ERR_MALFORMED_MESSAGE, header.messageType, "bad HELLO")
                } else if (hello.protocolVersion != AslcProtocol.PROTOCOL_VERSION) {
                    sendError(AslcProtocol.ERR_VERSION_UNSUPPORTED, header.messageType, "peer version ${hello.protocolVersion}")
                    return
                } else {
                    transition(State.HELLO_SEEN)
                    // Reply with our greeting + capabilities so the host knows what it can ask for.
                    writeMessage(AslcProtocol.MSG_HELLO, AslcPayload.helloPayload(AslcProtocol.PROTOCOL_VERSION, isDevice = true, roleTag = "Android"))
                    writeMessage(AslcProtocol.MSG_CAPABILITIES, AslcPayload.capabilitiesPayload(capabilities()))
                    transition(State.CAPABLES_SENT)
                }
            }

            AslcProtocol.MSG_CONFIGURE -> {
                val req = AslcPayload.parseConfigure(payload, 0, payload.size)
                if (req == null) {
                    sendError(AslcProtocol.ERR_MALFORMED_MESSAGE, header.messageType, "bad CONFIGURE")
                    return
                }
                when (val result = ConfigValidator.validate(req, capabilities(), probe)) {
                    is ConfigValidator.Result.Accepted -> {
                        val wasStreaming = state == State.STREAMING
                        configuredFormat = result.format
                        stats.setFormat(result.format)
                        // Tell the output layer to (re)build for this format. On a live reconfigure
                        // (PC is master) this stops/restarts playout and drains the ring, so the new
                        // frame geometry stays aligned — the phone follows without a teardown.
                        listener.onConfigured(result.format)
                        writeMessage(
                            AslcProtocol.MSG_CONFIGURE_ACK,
                            AslcPayload.configureAckPayload(result.format, capabilities().maxFrameBytes)
                        )
                        // Live reconfigure keeps STREAMING; an initial CONFIGURE arms CONFIGURED.
                        if (!wasStreaming) transition(State.CONFIGURED)
                    }
                    is ConfigValidator.Result.Rejected -> {
                        sendError(result.errorCode, header.messageType, result.reason)
                        // Rejected config leaves prior state intact; a re-CONFIGURE is expected. Not fatal.
                    }
                }
            }

            AslcProtocol.MSG_START -> {
                val fmt = configuredFormat
                if (fmt == null) {
                    sendError(AslcProtocol.ERR_NOT_CONFIGURED, header.messageType, "START before CONFIGURE")
                    return
                }
                if (state == State.STREAMING) {
                    sendError(AslcProtocol.ERR_PROTOCOL_SEQUENCE, header.messageType, "duplicate START")
                    return
                }
                // A fresh stream resets run-level counters but keeps the negotiated format.
                ring.reset()
                stats.resetStreamCounters()
                stats.streamStartMs.set(System.currentTimeMillis())
                transition(State.STREAMING)
                listener.onStreamStarted(fmt)
            }

            AslcProtocol.MSG_STOP -> {
                if (state != State.STREAMING) {
                    // STOP with no active stream: report as a sequencing error but remain recoverable.
                    sendError(AslcProtocol.ERR_PROTOCOL_SEQUENCE, header.messageType, "STOP without active stream")
                    return
                }
                stats.streamStartMs.set(0L)
                transition(State.CONFIGURED)
                listener.onStreamStopped()
            }

            AslcProtocol.MSG_CAPABILITIES, AslcProtocol.MSG_CONFIGURE_ACK -> {
                // Device-side never receives these (they are device->host). Ignore leniently.
                listener.onLog("USB: unexpected inbound ${AslcProtocol.describeMessageType(header.messageType)} ignored")
            }

            AslcProtocol.MSG_ERROR -> {
                val err = AslcPayload.parseError(payload, 0, payload.size)
                if (err != null) {
                    stats.protocolErrors.incrementAndGet()
                    listener.onProtocolError(err.errorCode, "host: ${err.message}")
                }
            }

            else -> sendError(AslcProtocol.ERR_MALFORMED_MESSAGE, header.messageType, "unhandled type")
        }
    }

    // ---- Hot path: PCM ingestion ---------------------------------------------------------

    /**
     * Reads and ingests one PCM_DATA message body. Returns a non-null [EndReason] only when the loop
     * must hand control back to the transport (EOF); all recoverable faults (wrong length, not
     * configured) are reported and consumed here so framing stays intact.
     */
    private fun handlePcmData(input: InputStream, payloadLength: Int): EndReason? {
        val fmt = configuredFormat

        // We MUST consume payloadLength bytes to keep the stream framed, even on error paths.
        if (pcmScratch.size < payloadLength) pcmScratch = ByteArray(payloadLength.coerceAtMost(AslcProtocol.MAX_PAYLOAD))
        if (payloadLength > pcmScratch.size) {
            // Oversized frame the ring can't accept: consume-and-reject, stay framed.
            val drain = ByteArray(AslcProtocol.MAX_PAYLOAD)
            var left = payloadLength
            while (left > 0) {
                val n = minOf(left, drain.size)
                if (!AslcProtocol.readFully(input, drain, 0, n)) return EndReason.EOF
                left -= n
            }
            stats.protocolErrors.incrementAndGet()
            listener.onLog("USB: PCM frame larger than max (${payloadLength}B) dropped")
            return null
        }
        if (!AslcProtocol.readFully(input, pcmScratch, 0, payloadLength)) return EndReason.EOF

        if (fmt == null || state != State.STREAMING) {
            sendError(AslcProtocol.ERR_NOT_CONFIGURED, AslcProtocol.MSG_PCM_DATA, "PCM_DATA before START")
            return null
        }

        if (payloadLength < AslcPayload.PCM_FRAME_COUNT_SIZE) {
            sendError(AslcProtocol.ERR_MALFORMED_MESSAGE, AslcProtocol.MSG_PCM_DATA, "PCM payload missing frame count")
            return null
        }

        val frameCount = AslcPayload.pcmFrameCount(pcmScratch, 0)
        val bodyBytes = payloadLength - AslcPayload.PCM_FRAME_COUNT_SIZE
        val expected = frameCount.toLong() * fmt.bytesPerFrame
        if (expected.toLong() != bodyBytes.toLong()) {
            sendError(
                AslcProtocol.ERR_FRAME_LENGTH_INVALID,
                AslcProtocol.MSG_PCM_DATA,
                "frameCount $frameCount * bpf ${fmt.bytesPerFrame} != body $bodyBytes"
            )
            return null
        }

        val written = ring.writeDroppingNewest(pcmScratch, AslcPayload.PCM_FRAME_COUNT_SIZE, bodyBytes)
        val dropped = bodyBytes - written
        if (dropped > 0) stats.bufferOverflows.addAndGet(dropped.toLong())
        stats.receivedBytes.addAndGet(written.toLong())
        stats.receivedFrames.addAndGet((written / fmt.bytesPerFrame).toLong())
        stats.receivedPackets.incrementAndGet()

        if (verboseLogging()) {
            listener.onLog("USB: PCM_DATA frames=$frameCount bytes=$written fill=${ring.available()}")
        }
        return null
    }

    private fun transition(next: State) {
        if (state != next) {
            state = next
            listener.onStateChange(next)
        }
    }

    fun requestStop() { stopRequested = true }

    val currentState: State get() = state

    companion object {
        private val NOOP_LISTENER = object : Listener {}
    }
}
