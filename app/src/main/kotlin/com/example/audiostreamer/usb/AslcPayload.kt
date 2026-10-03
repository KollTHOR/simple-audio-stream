package com.example.audiostreamer.usb

/**
 * Payload codecs for the ASLC control-plane messages, plus the wire constants for PCM encoding.
 *
 * These are deliberately plain, deterministic byte layouts (no JSON, no protobuf) so a future
 * Windows/Linux desktop node can reproduce them with a few lines of C#/C/C++/Rust. Every multi-byte
 * integer is Big-Endian to match the framing header in [AslcProtocol].
 */
object AslcPayload {

    /** PCM encoding identifiers carried in CONFIGURE / CAPABILITIES. Extensible: add a new id for a
     *  future lossless variant without breaking older peers (unknown ids negotiate to an error). */
    const val ENCODING_PCM: Int = 0x01        // signed PCM, native byte order per format below
    // Reserved for future expansion:
    // const val ENCODING_PCM_F32: Int = 0x02  // 32-bit float PCM (not yet supported)

    const val MAX_SAMPLE_RATES = 16
    const val MAX_BIT_DEPTHS = 8
    const val MAX_CHANNEL_COUNTS = 8

    /**
     * HELLO payload (host -> device and device -> host on attach). Carries the sender's protocol
     * version and a short human-readable role tag.
     *
     * Layout: u8 protocolVersion, u8 role (0=host,1=device), then role tag as UTF-8 (rest).
     */
    fun helloPayload(protocolVersion: Int, isDevice: Boolean, roleTag: String): ByteArray {
        val tag = roleTag.toByteArray(Charsets.UTF_8)
        val buf = ByteArray(2 + tag.size)
        buf[0] = (protocolVersion and 0xFF).toByte()
        buf[1] = (if (isDevice) 1 else 0).toByte()
        System.arraycopy(tag, 0, buf, 2, tag.size)
        return buf
    }

    data class Hello(
        val protocolVersion: Int,
        val isDevice: Boolean,
        val roleTag: String
    )

    fun parseHello(payload: ByteArray, offset: Int, length: Int): Hello? {
        if (length < 2) return null
        val version = payload[offset].toInt() and 0xFF
        val isDevice = (payload[offset + 1].toInt() and 0xFF) != 0
        val tagLen = length - 2
        val tag = if (tagLen > 0) String(payload, offset + 2, tagLen, Charsets.UTF_8) else ""
        return Hello(version, isDevice, tag)
    }

    /**
     * CAPABILITIES payload (device -> host). A compact list-of-values layout.
     *
     * Layout:
     *   u8   sampleRateCount
     *   u32[] sampleRates           (Hz, BE)
     *   u8   bitDepthCount
     *   u8[] bitDepths              (bits per sample)
     *   u8   channelCount
     *   u8[] channels               (channel counts)
     *   u8   encodingCount
     *   u8[] encodings              (ENCODING_*)
     *   u32  maxFrameBytes          (largest single PCM_DATA payload accepted)
     *   u32  recommendedBufferBytes (device's preferred sink buffer for smooth playout)
     *   u32  capabilitiesFlags      (reserved bitmask, 0 today)
     */
    fun capabilitiesPayload(caps: PcmCapabilities): ByteArray {
        val rates = caps.sampleRates
        val bits = caps.bitDepths
        val chans = caps.channels
        val encs = caps.encodings
        require(rates.size <= MAX_SAMPLE_RATES && bits.size <= MAX_BIT_DEPTHS &&
            chans.size <= MAX_CHANNEL_COUNTS) { "capability list too long" }

        val buf = ByteArray(1 + rates.size * 4 + 1 + bits.size + 1 + chans.size + 1 + encs.size + 12)
        var p = 0
        buf[p++] = rates.size.toByte()
        for (r in rates) { AslcProtocol.writeUInt32BE(buf, p, r.toLong()); p += 4 }
        buf[p++] = bits.size.toByte()
        for (b in bits) buf[p++] = (b and 0xFF).toByte()
        buf[p++] = chans.size.toByte()
        for (c in chans) buf[p++] = (c and 0xFF).toByte()
        buf[p++] = encs.size.toByte()
        for (e in encs) buf[p++] = (e and 0xFF).toByte()
        AslcProtocol.writeUInt32BE(buf, p, caps.maxFrameBytes.toLong()); p += 4
        AslcProtocol.writeUInt32BE(buf, p, caps.recommendedBufferBytes.toLong()); p += 4
        AslcProtocol.writeUInt32BE(buf, p, caps.capabilityFlags.toLong()); p += 4
        return buf
    }

    fun parseCapabilities(payload: ByteArray, offset: Int, length: Int): PcmCapabilities? {
        var p = offset
        val end = offset + length
        fun need(n: Int): Boolean = p + n <= end
        if (!need(1)) return null
        val rateCount = payload[p++].toInt() and 0xFF
        if (rateCount > MAX_SAMPLE_RATES || !need(rateCount * 4)) return null
        val rates = IntArray(rateCount) {
            val v = AslcProtocol.readUInt32BE(payload, p).toInt(); p += 4; v
        }
        if (!need(1)) return null
        val bitCount = payload[p++].toInt() and 0xFF
        if (bitCount > MAX_BIT_DEPTHS || !need(bitCount)) return null
        val bits = IntArray(bitCount) { (payload[p++].toInt() and 0xFF) }
        if (!need(1)) return null
        val chanCount = payload[p++].toInt() and 0xFF
        if (chanCount > MAX_CHANNEL_COUNTS || !need(chanCount)) return null
        val chans = IntArray(chanCount) { (payload[p++].toInt() and 0xFF) }
        if (!need(1)) return null
        val encCount = payload[p++].toInt() and 0xFF
        if (!need(encCount + 12)) return null
        val encs = IntArray(encCount) { (payload[p++].toInt() and 0xFF) }
        val maxFrame = AslcProtocol.readUInt32BE(payload, p).toInt(); p += 4
        val recBuf = AslcProtocol.readUInt32BE(payload, p).toInt(); p += 4
        val flags = AslcProtocol.readUInt32BE(payload, p); p += 4
        return PcmCapabilities(rates, bits, chans, encs, maxFrame, recBuf, flags)
    }

    /**
     * CONFIGURE payload (host -> device): request ONE concrete format.
     *
     * Layout: u32 sampleRate, u8 bitDepth, u8 channels, u8 encoding, u8 reserved, u32 flags.
     */
    fun configurePayload(format: PcmFormat): ByteArray {
        val buf = ByteArray(4 + 1 + 1 + 1 + 1 + 4)
        var p = 0
        AslcProtocol.writeUInt32BE(buf, p, format.sampleRate.toLong()); p += 4
        buf[p++] = (format.bitDepth and 0xFF).toByte()
        buf[p++] = (format.channels and 0xFF).toByte()
        buf[p++] = (format.encoding and 0xFF).toByte()
        buf[p++] = 0 // reserved
        AslcProtocol.writeUInt32BE(buf, p, format.flags.toLong())
        return buf
    }

    fun parseConfigure(payload: ByteArray, offset: Int, length: Int): PcmFormat? {
        if (length < 12) return null
        var p = offset
        val rate = AslcProtocol.readUInt32BE(payload, p).toInt(); p += 4
        val bits = payload[p++].toInt() and 0xFF
        val chans = payload[p++].toInt() and 0xFF
        val enc = payload[p++].toInt() and 0xFF
        p++ // reserved
        val flags = AslcProtocol.readUInt32BE(payload, p)
        return PcmFormat(rate, bits, chans, enc, flags)
    }

    /**
     * CONFIGURE_ACK payload (device -> host): the granted, validated format (echoed back so the
     * host can confirm exact parameters, incl. the frame size the device expects).
     *
     * Layout: same as CONFIGURE followed by u32 bytesPerFrame, u32 maxFrameBytes.
     */
    fun configureAckPayload(format: PcmFormat, maxFrameBytes: Int): ByteArray {
        val base = configurePayload(format)
        val buf = ByteArray(base.size + 8)
        System.arraycopy(base, 0, buf, 0, base.size)
        AslcProtocol.writeUInt32BE(buf, base.size, format.bytesPerFrame.toLong())
        AslcProtocol.writeUInt32BE(buf, base.size + 4, maxFrameBytes.toLong())
        return buf
    }

    fun parseConfigureAck(payload: ByteArray, offset: Int, length: Int): PcmFormat? {
        if (length < 12 + 8) return null
        val fmt = parseConfigure(payload, offset, 12) ?: return null
        // Validate the trailing fields are consistent with the format's derived frame size.
        val bytesPerFrame = AslcProtocol.readUInt32BE(payload, offset + 12).toInt()
        return if (bytesPerFrame == fmt.bytesPerFrame) fmt else null
    }

    /**
     * ERROR payload (either direction).
     *
     * Layout: u16 errorCode, u8 offendingMessageType, u8 reserved, UTF-8 message (rest).
     */
    fun errorPayload(errorCode: Int, offendingMessageType: Int, message: String): ByteArray {
        val msg = message.toByteArray(Charsets.UTF_8)
        val buf = ByteArray(4 + msg.size)
        AslcProtocol.writeUInt16BE(buf, 0, errorCode and 0xFFFF)
        buf[2] = (offendingMessageType and 0xFF).toByte()
        buf[3] = 0
        System.arraycopy(msg, 0, buf, 4, msg.size)
        return buf
    }

    data class ErrorInfo(val errorCode: Int, val offendingMessageType: Int, val message: String)

    fun parseError(payload: ByteArray, offset: Int, length: Int): ErrorInfo? {
        if (length < 4) return null
        val code = AslcProtocol.readUInt16BE(payload, offset)
        val offending = payload[offset + 2].toInt() and 0xFF
        val msgLen = length - 4
        val msg = if (msgLen > 0) String(payload, offset + 4, msgLen, Charsets.UTF_8) else ""
        return ErrorInfo(code, offending, msg)
    }

    // START / STOP carry no payload. PCM_DATA layout (frameCount prefix) is handled in AslcProtocol's
    // reader together with PcmFormat.bytesPerFrame so it can validate the exact byte length.
}
