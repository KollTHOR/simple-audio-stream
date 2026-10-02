package com.example.audiostreamer.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

// ---- file-private test scaffolding (kept top-level so the nested Harness can use them) ----

private val USB_CAPS = PcmCapabilities.fullMatrix()
private val OK_PROBE: (PcmFormat) -> Int = { 4096 }
private val FAIL_PROBE: (PcmFormat) -> Int = { -1 }

private data class Decoded(val header: AslcProtocol.AslcHeader, val payload: ByteArray)

private fun decodeAll(bytes: ByteArray): List<Decoded> {
    val list = ArrayList<Decoded>()
    val input = ByteArrayInputStream(bytes)
    while (true) {
        val h = ByteArray(AslcProtocol.HEADER_SIZE)
        if (!AslcProtocol.readFully(input, h, 0, AslcProtocol.HEADER_SIZE)) break
        val header = AslcProtocol.decodeHeader(h, 0) ?: break
        val p = ByteArray(header.payloadLength)
        if (header.payloadLength > 0 && !AslcProtocol.readFully(input, p, 0, header.payloadLength)) break
        list.add(Decoded(header, p))
    }
    return list
}

private fun frame(type: Int, payload: ByteArray, seq: Long): ByteArray =
    AslcProtocol.encodeFrame(type, payload, seq)

private fun pcmBody(fmt: PcmFormat, frames: Int): ByteArray {
    val pcm = ByteArray(fmt.bytesPerFrame * frames)
    val p = ByteArray(4 + pcm.size)
    AslcProtocol.writeUInt32BE(p, 0, frames.toLong())
    System.arraycopy(pcm, 0, p, 4, pcm.size)
    return p
}

/** Builds a receiver wired to an in-memory output; runs it over host->device bytes; decodes replies. */
private class Harness(probe: (PcmFormat) -> Int = OK_PROBE) {
    val out = ByteArrayOutputStream()
    val ring = PcmRingBuffer(1 shl 15)
    val stats = UsbPcmStats()
    val events = ArrayList<String>()
    val receiver = UsbPcmReceiver(
        output = out,
        capabilities = { USB_CAPS },
        probe = probe,
        ring = ring,
        stats = stats,
        listener = object : UsbPcmReceiver.Listener {
            override fun onStreamStarted(format: PcmFormat) { events.add("START:${format.displayLabel()}") }
            override fun onStreamStopped() { events.add("STOP") }
            override fun onProtocolError(code: Int, detail: String) { events.add("ERR:$code") }
            override fun onLog(message: String) { events.add("LOG") }
        }
    )

    fun run(vararg hostToId: ByteArray): List<Decoded> {
        val all = if (hostToId.isEmpty()) ByteArray(0) else hostToId.reduce { a, b -> a + b }
        receiver.pump(ByteArrayInputStream(all))
        return decodeAll(out.toByteArray())
    }

    fun errors(): List<AslcPayload.ErrorInfo> =
        outErrors(decodeAll(out.toByteArray()))

    private fun outErrors(resp: List<Decoded>) =
        resp.mapNotNull { if (it.header.messageType == AslcProtocol.MSG_ERROR) AslcPayload.parseError(it.payload, 0, it.payload.size) else null }
}

/**
 * End-to-end state-machine tests for [UsbPcmReceiver] driven purely by an in-memory byte pipe
 * (no USB, no AudioTrack). Covers the negotiation happy path and every recoverable error case in
 * spec §12: start-before-config, duplicate START, STOP without stream, malformed, unsupported
 * version/format, invalid frame length.
 */
class UsbPcmReceiverTest {

    @Test
    fun happyPathNegotiateStartPcmStop() {
        val h = Harness()
        val fmt = PcmFormat(96000, 24, 2)
        val resp = h.run(
            frame(AslcProtocol.MSG_HELLO, AslcPayload.helloPayload(AslcProtocol.PROTOCOL_VERSION, false, "LinuxDesktop"), 0),
            frame(AslcProtocol.MSG_CONFIGURE, AslcPayload.configurePayload(fmt), 1),
            frame(AslcProtocol.MSG_START, ByteArray(0), 2),
            frame(AslcProtocol.MSG_PCM_DATA, pcmBody(fmt, 10), 3),
            frame(AslcProtocol.MSG_STOP, ByteArray(0), 4)
        )
        val types = resp.map { it.header.messageType }
        assertTrue(types.contains(AslcProtocol.MSG_HELLO))
        assertTrue(types.contains(AslcProtocol.MSG_CAPABILITIES))
        assertTrue("expected CONFIGURE_ACK, got $types", types.contains(AslcProtocol.MSG_CONFIGURE_ACK))
        assertTrue("no ERROR expected: ${h.errors()}", !types.contains(AslcProtocol.MSG_ERROR))

        assertEquals(60, h.ring.available()) // 10 frames * 6 bytes
        assertEquals(60L, h.stats.receivedBytes.get())
        assertEquals(10L, h.stats.receivedFrames.get())
        assertTrue(h.events.contains("START:24-bit • 96.000 kHz • Stereo"))
        assertTrue(h.events.contains("STOP"))
    }

    @Test
    fun startBeforeConfigureIsRejectedThenRecovers() {
        val h = Harness()
        val resp = h.run(frame(AslcProtocol.MSG_START, ByteArray(0), 0))
        assertEquals(AslcProtocol.ERR_NOT_CONFIGURED, h.errors().first().errorCode)

        // A later valid negotiation on a fresh receiver should succeed cleanly.
        val h2 = Harness()
        val fmt = PcmFormat(48000, 16, 2)
        h2.run(
            frame(AslcProtocol.MSG_CONFIGURE, AslcPayload.configurePayload(fmt), 0),
            frame(AslcProtocol.MSG_START, ByteArray(0), 1),
            frame(AslcProtocol.MSG_PCM_DATA, pcmBody(fmt, 5), 2)
        )
        assertTrue(h2.errors().isEmpty())
        assertEquals(5 * 4, h2.ring.available())
    }

    @Test
    fun duplicateStartReportsSequenceError() {
        val h = Harness()
        val fmt = PcmFormat(48000, 16, 2)
        h.run(
            frame(AslcProtocol.MSG_CONFIGURE, AslcPayload.configurePayload(fmt), 0),
            frame(AslcProtocol.MSG_START, ByteArray(0), 1),
            frame(AslcProtocol.MSG_START, ByteArray(0), 2)
        )
        assertTrue(h.errors().any { it.errorCode == AslcProtocol.ERR_PROTOCOL_SEQUENCE })
    }

    @Test
    fun stopWithoutActiveStreamIsRecoverableError() {
        val h = Harness()
        val fmt = PcmFormat(48000, 16, 2)
        h.run(
            frame(AslcProtocol.MSG_CONFIGURE, AslcPayload.configurePayload(fmt), 0),
            frame(AslcProtocol.MSG_STOP, ByteArray(0), 1)
        )
        assertTrue(h.errors().any { it.errorCode == AslcProtocol.ERR_PROTOCOL_SEQUENCE })
    }

    @Test
    fun unsupportedFormatIsRejectedNotSubstituted() {
        val h = Harness(probe = FAIL_PROBE)
        val resp = h.run(
            frame(AslcProtocol.MSG_CONFIGURE, AslcPayload.configurePayload(PcmFormat(96000, 24, 2)), 0)
        )
        assertEquals(AslcProtocol.ERR_FORMAT_UNSUPPORTED, h.errors().first().errorCode)
        assertTrue(resp.none { it.header.messageType == AslcProtocol.MSG_CONFIGURE_ACK })
    }

    @Test
    fun invalidFrameLengthIsDetected() {
        val h = Harness()
        val fmt = PcmFormat(48000, 16, 2) // bpf 4
        val body = ByteArray(4 + 8 * 4)
        AslcProtocol.writeUInt32BE(body, 0, 10L) // claims 10 frames, sends 8
        h.run(
            frame(AslcProtocol.MSG_CONFIGURE, AslcPayload.configurePayload(fmt), 0),
            frame(AslcProtocol.MSG_START, ByteArray(0), 1),
            frame(AslcProtocol.MSG_PCM_DATA, body, 2)
        )
        assertTrue(h.errors().any { it.errorCode == AslcProtocol.ERR_FRAME_LENGTH_INVALID })
        assertEquals(0, h.ring.available())
    }

    @Test
    fun versionMismatchStopsPumpRecoverably() {
        val h = Harness()
        val badHello = AslcProtocol.encodeFrame(
            AslcProtocol.MSG_HELLO,
            AslcPayload.helloPayload(99, false, "FutureHost"),
            0,
            version = 99
        )
        h.run(badHello)
        assertEquals(AslcProtocol.ERR_VERSION_UNSUPPORTED, h.errors().first().errorCode)
        assertNull(h.stats.configuredFormat)
    }

    @Test
    fun pcmDataBeforeStartIsRejected() {
        val h = Harness()
        val fmt = PcmFormat(48000, 16, 2)
        h.run(
            frame(AslcProtocol.MSG_CONFIGURE, AslcPayload.configurePayload(fmt), 0),
            frame(AslcProtocol.MSG_PCM_DATA, pcmBody(fmt, 3), 1) // no START
        )
        assertTrue(h.errors().any { it.errorCode == AslcProtocol.ERR_NOT_CONFIGURED })
        assertEquals(0, h.ring.available())
    }

    @Test
    fun requestStopEndsPump() {
        val h = Harness()
        val input = ByteArrayInputStream(frame(AslcProtocol.MSG_HELLO, AslcPayload.helloPayload(AslcProtocol.PROTOCOL_VERSION, false, "H"), 0))
        h.receiver.requestStop()
        assertEquals(UsbPcmReceiver.EndReason.STOPPED, h.receiver.pump(input))
    }

    @Test
    fun malformedControlPayloadYieldsErrorNotCrash() {
        val h = Harness()
        // CONFIGURE with a truncated payload.
        h.run(frame(AslcProtocol.MSG_CONFIGURE, ByteArray(3), 0))
        assertTrue(h.errors().any { it.errorCode == AslcProtocol.ERR_MALFORMED_MESSAGE })
    }
}
