package com.example.audiostreamer.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round-trips every control-plane payload codec against the wire format the desktop must match. */
class AslcPayloadTest {

    @Test
    fun helloRoundTrips() {
        val p = AslcPayload.helloPayload(AslcProtocol.PROTOCOL_VERSION, isDevice = true, roleTag = "Android")
        val parsed = AslcPayload.parseHello(p, 0, p.size)!!
        assertEquals(AslcProtocol.PROTOCOL_VERSION, parsed.protocolVersion)
        assertTrue(parsed.isDevice)
        assertEquals("Android", parsed.roleTag)
    }

    @Test
    fun helloRejectsTooShort() {
        assertNull(AslcPayload.parseHello(ByteArray(1), 0, 1))
    }

    @Test
    fun capabilitiesRoundTripFullMatrix() {
        val caps = PcmCapabilities.fullMatrix()
        val p = AslcPayload.capabilitiesPayload(caps)
        val parsed = AslcPayload.parseCapabilities(p, 0, p.size)!!
        assertArrayEquals(caps.sampleRates, parsed.sampleRates)
        assertArrayEquals(caps.bitDepths, parsed.bitDepths)
        assertArrayEquals(caps.channels, parsed.channels)
        assertArrayEquals(caps.encodings, parsed.encodings)
        assertEquals(caps.maxFrameBytes, parsed.maxFrameBytes)
        assertEquals(caps.recommendedBufferBytes, parsed.recommendedBufferBytes)
        assertEquals(caps.capabilityFlags, parsed.capabilityFlags)
    }

    @Test
    fun capabilitiesRejectsTruncated() {
        val caps = PcmCapabilities.fullMatrix()
        val p = AslcPayload.capabilitiesPayload(caps)
        // Chop the trailing fixed fields off the end.
        assertNull(AslcPayload.parseCapabilities(p, 0, p.size - 4))
    }

    @Test
    fun configureRoundTrips() {
        val fmt = PcmFormat(sampleRate = 96000, bitDepth = 24, channels = 2)
        val p = AslcPayload.configurePayload(fmt)
        val parsed = AslcPayload.parseConfigure(p, 0, p.size)!!
        assertEquals(fmt, parsed)
    }

    @Test
    fun configureRejectsShortPayload() {
        assertNull(AslcPayload.parseConfigure(ByteArray(11), 0, 11))
    }

    @Test
    fun configureAckEchoesAndValidatesFrameSize() {
        val fmt = PcmFormat(sampleRate = 192000, bitDepth = 32, channels = 2)
        val p = AslcPayload.configureAckPayload(fmt, maxFrameBytes = 16384)
        val parsed = AslcPayload.parseConfigureAck(p, 0, p.size)!!
        assertEquals(fmt, parsed)
    }

    @Test
    fun configureAckFailsOnInconsistentFrameSize() {
        val fmt = PcmFormat(48000, 16, 2)
        val p = AslcPayload.configureAckPayload(fmt, maxFrameBytes = 16384)
        // Corrupt the declared bytesPerFrame so it contradicts the format's derived value.
        AslcProtocol.writeUInt32BE(p, 12, (fmt.bytesPerFrame + 1).toLong())
        assertNull(AslcPayload.parseConfigureAck(p, 0, p.size))
    }

    @Test
    fun errorRoundTripsWithMessageAndOffendingType() {
        val p = AslcPayload.errorPayload(
            errorCode = AslcProtocol.ERR_FORMAT_UNSUPPORTED,
            offendingMessageType = AslcProtocol.MSG_CONFIGURE,
            message = "device cannot render 32-bit"
        )
        val parsed = AslcPayload.parseError(p, 0, p.size)!!
        assertEquals(AslcProtocol.ERR_FORMAT_UNSUPPORTED, parsed.errorCode)
        assertEquals(AslcProtocol.MSG_CONFIGURE, parsed.offendingMessageType)
        assertEquals("device cannot render 32-bit", parsed.message)
    }

    @Test
    fun pcmFormatDerivedGeometry() {
        assertEquals(4, PcmFormat(48000, 16, 2).bytesPerFrame)
        assertEquals(6, PcmFormat(48000, 24, 2).bytesPerFrame)
        assertEquals(8, PcmFormat(48000, 32, 2).bytesPerFrame)
        assertEquals(4, PcmFormat(48000, 32, 1).bytesPerFrame)
        assertFalse(PcmFormat(48000, 8, 2).isSupportedBitDepth)
    }
}
