package com.example.audiostreamer.usb

/**
 * ASLC (Audio Stream Link Codec) USB link protocol — framing layer.
 *
 * A compact, versioned, self-delimiting message framing designed to run over ANY reliable,
 * ordered byte pipe, so the exact same wire format can be implemented independently by the
 * Windows/Linux desktop node later without Android-specific behavior.
 *
 * On Android the byte pipe is AOA (Android Open Accessory): the desktop is the USB host, the
 * phone is the accessory, and [android.hardware.usb.UsbManager.openAccessory] yields a
 * ParcelFileDescriptor whose input/output streams are this pipe. The framing itself does NOT
 * depend on USB — it depends only on "an ordered, lossless stream of bytes".
 *
 * Frame layout (12-byte header, Big-Endian, then payload):
 * ----------------------------------------------------------------------------------
 * Offset  Size  Field           Description
 * 0        1    protocolVersion Protocol version (currently [PROTOCOL_VERSION] = 1)
 * 1        1    messageType     Message type (see MSG_* constants)
 * 2        2    flags           Reserved per-message flags (Big-Endian uint16, 0 unless defined)
 * 4        4    sequence        Monotonic per-sender sequence (Big-Endian uint32)
 * 8        4    payloadLength   Payload byte count that follows (Big-Endian uint32)
 * 12       N    payload         [payloadLength] bytes; codec/format payloads are below
 * ----------------------------------------------------------------------------------
 *
 * The header carries no magic/sync word: the stream is reliable and ordered, so framing is
 * purely self-delimiting via [payloadLength] — the reader always consumes exactly
 * HEADER_SIZE + payloadLength bytes per frame and therefore can never desynchronize. Malformed
 * input is detected by bounds/version/type/length checks, not by hunting a magic byte.
 */
object AslcProtocol {

    /** Current protocol version. Bumped only for backward-incompatible framing changes. */
    const val PROTOCOL_VERSION: Int = 1

    const val HEADER_SIZE = 12

    /**
     * Hard upper bound on a single frame payload. A valid frame never exceeds this; anything
     * larger is treated as a malformed/desynced stream and the receiver resyncs or errors out.
     * Sized for a full USB burst at the top supported format (192 kHz / 32-bit / 2ch).
     */
    const val MAX_PAYLOAD: Int = 1 shl 18 // 262144 bytes (256 KiB)

    // Message types (byte 1). Values are the shared contract with the desktop implementation.
    const val MSG_HELLO: Int = 0x01        // host -> device, or device -> host: greeting + version
    const val MSG_CAPABILITIES: Int = 0x02 // device -> host: supported PCM capability set
    const val MSG_CONFIGURE: Int = 0x03    // host -> device: request a specific PCM format
    const val MSG_CONFIGURE_ACK: Int = 0x04 // device -> host: accepted format (granted)
    const val MSG_START: Int = 0x05        // host -> device: begin PCM_DATA on the configured format
    const val MSG_PCM_DATA: Int = 0x06     // host -> device: PCM frame(s)
    const val MSG_STOP: Int = 0x07         // host -> device: end the PCM stream
    const val MSG_ERROR: Int = 0x08        // either direction: protocol-level error report
    const val MSG_TELEMETRY: Int = 0x09    // device -> host: periodic buffer/latency figures
    const val MSG_AUDIO_INFO: Int = 0x0A   // device -> host: the device's audio-output characteristics

    fun describeMessageType(type: Int): String = when (type) {
        MSG_HELLO -> "HELLO"
        MSG_CAPABILITIES -> "CAPABILITIES"
        MSG_CONFIGURE -> "CONFIGURE"
        MSG_CONFIGURE_ACK -> "CONFIGURE_ACK"
        MSG_START -> "START"
        MSG_PCM_DATA -> "PCM_DATA"
        MSG_STOP -> "STOP"
        MSG_ERROR -> "ERROR"
        MSG_TELEMETRY -> "TELEMETRY"
        MSG_AUDIO_INFO -> "AUDIO_INFO"
        else -> "UNKNOWN(0x${(type and 0xFF).toString(16)})"
    }

    // Error codes carried in MSG_ERROR payloads. Deterministic, non-Android-specific integers so
    // the desktop can react to them identically.
    const val ERR_VERSION_UNSUPPORTED: Int = 1
    const val ERR_FORMAT_UNSUPPORTED: Int = 2
    const val ERR_FRAME_LENGTH_INVALID: Int = 3
    const val ERR_MALFORMED_MESSAGE: Int = 4
    const val ERR_NOT_CONFIGURED: Int = 5       // START or PCM_DATA before a successful CONFIGURE
    const val ERR_PROTOCOL_SEQUENCE: Int = 6    // duplicate START, STOP without active stream, etc.
    const val ERR_BUFFER_OVERFLOW: Int = 7      // consumer could not drain in time (underrun risk)
    const val ERR_TRANSPORT_FAILURE: Int = 8     // underlying USB stream read/write failed
    const val ERR_INTERNAL: Int = 9

    fun describeErrorCode(code: Int): String = when (code) {
        ERR_VERSION_UNSUPPORTED -> "VERSION_UNSUPPORTED"
        ERR_FORMAT_UNSUPPORTED -> "FORMAT_UNSUPPORTED"
        ERR_FRAME_LENGTH_INVALID -> "FRAME_LENGTH_INVALID"
        ERR_MALFORMED_MESSAGE -> "MALFORMED_MESSAGE"
        ERR_NOT_CONFIGURED -> "NOT_CONFIGURED"
        ERR_PROTOCOL_SEQUENCE -> "PROTOCOL_SEQUENCE"
        ERR_BUFFER_OVERFLOW -> "BUFFER_OVERFLOW"
        ERR_TRANSPORT_FAILURE -> "TRANSPORT_FAILURE"
        ERR_INTERNAL -> "INTERNAL"
        else -> "UNKNOWN($code)"
    }

    // Per-message flag bits (uint16). Only defined where a message needs them; 0 otherwise.
    const val FLAG_NONE: Int = 0x0000

    /**
     * A parsed frame header. [payloadLength] tells the reader how many payload bytes immediately
     * follow; the payload itself is read separately so hot-path (PCM) frames can stream into a
     * reusable buffer without copying the whole frame into a fresh allocation.
     */
    data class AslcHeader(
        val protocolVersion: Int,
        val messageType: Int,
        val flags: Int,
        val sequence: Long,
        val payloadLength: Int
    ) {
        val isValidVersion: Boolean get() = protocolVersion == PROTOCOL_VERSION
    }

    // ---- Big-Endian primitives (mirrors HatPacket's manual, allocation-free style) ----------

    fun writeUInt16BE(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }

    fun readUInt16BE(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

    fun writeUInt32BE(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = ((value ushr 24) and 0xFFL).toByte()
        buffer[offset + 1] = ((value ushr 16) and 0xFFL).toByte()
        buffer[offset + 2] = ((value ushr 8) and 0xFFL).toByte()
        buffer[offset + 3] = (value and 0xFFL).toByte()
    }

    fun readUInt32BE(buffer: ByteArray, offset: Int): Long =
        ((buffer[offset].toLong() and 0xFFL) shl 24) or
            ((buffer[offset + 1].toLong() and 0xFFL) shl 16) or
            ((buffer[offset + 2].toLong() and 0xFFL) shl 8) or
            (buffer[offset + 3].toLong() and 0xFFL)

    /**
     * Encodes a complete frame into a freshly allocated byte array. Intended for CONTROL-plane
     * messages (HELLO/CAPABILITIES/CONFIGURE/...), which are rare. The PCM hot path uses
     * [writeFrame] writing straight into an output stream with a reusable scratch buffer instead.
     */
    fun encodeFrame(
        messageType: Int,
        payload: ByteArray,
        sequence: Long,
        flags: Int = FLAG_NONE,
        version: Int = PROTOCOL_VERSION
    ): ByteArray {
        require(payload.size <= MAX_PAYLOAD) {
            "Payload ${payload.size} exceeds MAX_PAYLOAD $MAX_PAYLOAD"
        }
        val frame = ByteArray(HEADER_SIZE + payload.size)
        writeHeaderInto(frame, messageType, payload.size, sequence, flags, version)
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, frame, HEADER_SIZE, payload.size)
        }
        return frame
    }

    private fun writeHeaderInto(
        buffer: ByteArray,
        messageType: Int,
        payloadLength: Int,
        sequence: Long,
        flags: Int,
        version: Int
    ) {
        buffer[0] = (version and 0xFF).toByte()
        buffer[1] = (messageType and 0xFF).toByte()
        writeUInt16BE(buffer, 2, flags and 0xFFFF)
        writeUInt32BE(buffer, 4, sequence and 0xFFFFFFFFL)
        writeUInt32BE(buffer, 8, payloadLength.toLong() and 0xFFFFFFFFL)
    }

    /**
     * Writes one frame (header + payload slice) into [out]. No intermediate frame-sized copy:
     * the header goes through a reusable 12-byte scratch, then the payload is streamed directly.
     * [scratch12] must be at least [HEADER_SIZE] bytes and is owned by the caller (reused).
     */
    fun writeFrame(
        out: java.io.OutputStream,
        scratch12: ByteArray,
        messageType: Int,
        payload: ByteArray,
        payloadOffset: Int,
        payloadLength: Int,
        sequence: Long,
        flags: Int = FLAG_NONE,
        version: Int = PROTOCOL_VERSION
    ) {
        require(payloadOffset >= 0 && payloadLength >= 0 && payloadOffset + payloadLength <= payload.size) {
            "payload slice [$payloadOffset, +$payloadLength) out of bounds (size ${payload.size})"
        }
        require(payloadLength <= MAX_PAYLOAD) {
            "payloadLength $payloadLength exceeds MAX_PAYLOAD $MAX_PAYLOAD"
        }
        writeHeaderInto(scratch12, messageType, payloadLength, sequence, flags, version)
        out.write(scratch12, 0, HEADER_SIZE)
        if (payloadLength > 0) out.write(payload, payloadOffset, payloadLength)
    }

    /**
     * Reads exactly [len] bytes from [input] into [dest] starting at [off]. Returns true on a full
     * read, false on a premature end-of-stream (peer vanished / clean detach). Never throws for
     * EOF; only throws IOException for genuine transport faults (caller maps those to
     * [ERR_TRANSPORT_FAILURE]).
     */
    fun readFully(input: java.io.InputStream, dest: ByteArray, off: Int, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val n = input.read(dest, off + read, len - read)
            if (n < 0) return false // EOF before len
            read += n
        }
        return true
    }

    /**
     * Parses the 12-byte header already sitting in [buffer] at [offset]. Validates version,
     * known message type, and that payloadLength is within [MAX_PAYLOAD]. [peekVersion] allows the
     * caller to distinguish a version-mismatch rejection from a generic malformed one (returns that
     * version even when rejecting). Returns null only if the header is structurally bad.
     */
    fun decodeHeader(buffer: ByteArray, offset: Int): AslcHeader? {
        val version = buffer[offset].toInt() and 0xFF
        val messageType = buffer[offset + 1].toInt() and 0xFF
        val flags = readUInt16BE(buffer, offset + 2)
        val sequence = readUInt32BE(buffer, offset + 4)
        val payloadLength = readUInt32BE(buffer, offset + 8).toInt()

        if (messageType < MSG_HELLO || messageType > MSG_ERROR) return null
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD) return null

        return AslcHeader(version, messageType, flags, sequence, payloadLength)
    }
}
