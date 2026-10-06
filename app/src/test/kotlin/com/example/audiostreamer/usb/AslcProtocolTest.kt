package com.example.audiostreamer.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Wire-format round-trip and defensive-parsing tests for the ASLC USB framing layer. These are the
 * invariants the future Windows/Linux desktop node must reproduce, so they are pinned explicitly.
 */
class AslcProtocolTest {

    @Test
    fun encodeThenDecodeHeaderRoundTrips() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val frame = AslcProtocol.encodeFrame(
            messageType = AslcProtocol.MSG_CAPABILITIES,
            payload = payload,
            sequence = 0xDEADBEEFL,
            flags = 0x1234
        )
        assertEquals(AslcProtocol.HEADER_SIZE + payload.size, frame.size)
        val header = AslcProtocol.decodeHeader(frame, 0)
        assertNotNull(header)
        header!!
        assertEquals(AslcProtocol.PROTOCOL_VERSION, header.protocolVersion)
        assertEquals(AslcProtocol.MSG_CAPABILITIES, header.messageType)
        assertEquals(0x1234, header.flags)
        assertEquals(0xDEADBEEFL, header.sequence)
        assertEquals(payload.size, header.payloadLength)
    }

    @Test
    fun uint32SequenceRoundTripsFullRange() {
        val frame = AslcProtocol.encodeFrame(AslcProtocol.MSG_HELLO, ByteArray(0), sequence = 0xFFFFFFFFL)
        val header = AslcProtocol.decodeHeader(frame, 0)!!
        assertEquals(0xFFFFFFFFL, header.sequence)
    }

    @Test
    fun writeFrameThenReadFullyReconstructsStream() {
        val out = ByteArrayOutputStream()
        val scratch = ByteArray(AslcProtocol.HEADER_SIZE)
        val payload = ByteArray(1000) { (it % 251).toByte() }
        AslcProtocol.writeFrame(
            out = out,
            scratch12 = scratch,
            messageType = AslcProtocol.MSG_PCM_DATA,
            payload = payload,
            payloadOffset = 10,
            payloadLength = 990,
            sequence = 42L
        )
        val bytes = out.toByteArray()
        val input = ByteArrayInputStream(bytes)
        val headerBuf = ByteArray(AslcProtocol.HEADER_SIZE)
        assertTrue(AslcProtocol.readFully(input, headerBuf, 0, AslcProtocol.HEADER_SIZE))
        val header = AslcProtocol.decodeHeader(headerBuf, 0)!!
        assertEquals(AslcProtocol.MSG_PCM_DATA, header.messageType)
        assertEquals(990, header.payloadLength)
        val body = ByteArray(header.payloadLength)
        assertTrue(AslcProtocol.readFully(input, body, 0, header.payloadLength))
        assertArrayEquals(payload.copyOfRange(10, 1000), body)
    }

    @Test
    fun readFullyReturnsFalseOnPrematureEof() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        val dest = ByteArray(8)
        assertFalse(AslcProtocol.readFully(input, dest, 0, 8))
    }

    @Test
    fun unknownMessageTypeIsRejected() {
        val bad = ByteArray(AslcProtocol.HEADER_SIZE)
        bad[1] = 0x7F.toByte() // not a defined message type
        assertNull(AslcProtocol.decodeHeader(bad, 0))
    }

    @Test
    fun oversizedPayloadLengthIsRejected() {
        val frame = AslcProtocol.encodeFrame(AslcProtocol.MSG_HELLO, ByteArray(0), 0L)
        // Corrupt the payload length to exceed MAX_PAYLOAD.
        AslcProtocol.writeUInt32BE(frame, 8, (AslcProtocol.MAX_PAYLOAD + 1).toLong())
        assertNull(AslcProtocol.decodeHeader(frame, 0))
    }

    @Test
    fun versionIsDecodedEvenOnMismatchForDiagnostics() {
        val frame = AslcProtocol.encodeFrame(AslcProtocol.MSG_HELLO, ByteArray(0), 0L, version = 99)
        val header = AslcProtocol.decodeHeader(frame, 0)
        assertNotNull(header) // structurally valid
        assertEquals(99, header!!.protocolVersion)
        assertFalse(header.isValidVersion) // but not a version we speak
    }

    @Test
    fun messageAndErrorNamesResolve() {
        assertEquals("PCM_DATA", AslcProtocol.describeMessageType(AslcProtocol.MSG_PCM_DATA))
        assertEquals("FORMAT_UNSUPPORTED", AslcProtocol.describeErrorCode(AslcProtocol.ERR_FORMAT_UNSUPPORTED))
        assertTrue(AslcProtocol.describeMessageType(0xFF).startsWith("UNKNOWN"))
    }
}
