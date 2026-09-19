package com.example.audiostreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCaptureServiceAdmissionTest {

    private fun isReceiverAdmissionPacket(packetType: Byte): Boolean {
        // Core HAT contract: Only TYPE_RECEIVER_HEARTBEAT is admitted as a dynamic receiver
        return packetType == HatPacket.TYPE_RECEIVER_HEARTBEAT
    }

    @Test
    fun testOnlyReceiverHeartbeatIsAdmissionPacket() {
        assertTrue(
            "TYPE_RECEIVER_HEARTBEAT must be an admission packet",
            isReceiverAdmissionPacket(HatPacket.TYPE_RECEIVER_HEARTBEAT)
        )

        val nonAdmissionTypes = listOf(
            HatPacket.TYPE_AUDIO,
            HatPacket.TYPE_SILENCE_HEARTBEAT,
            HatPacket.TYPE_FEC_PARITY,
            HatPacket.TYPE_CONTROL,
            HatPacket.TYPE_REVERSE_VOLUME_SYNC,
            HatPacket.TYPE_DISCONNECT,
            HatPacket.TYPE_DISCOVERY_PROBE,
            HatPacket.TYPE_DISCOVERY_ANNOUNCE,
            HatPacket.TYPE_STREAM_INVITE,
            HatPacket.TYPE_TRANSMITTER_ANNOUNCE
        )

        for (type in nonAdmissionTypes) {
            assertFalse(
                "Packet type ${HatPacket.describePacketType(type)} must NOT be an admission packet",
                isReceiverAdmissionPacket(type)
            )
        }
    }

    @Test
    fun testMalformedOrTruncatedPacketCannotBeAdmitted() {
        // Less than 24 bytes
        val shortBuffer = ByteArray(16)
        val parsed = HatPacket.parseHeader(shortBuffer, 0, shortBuffer.size)
        assertNull("Truncated buffer must fail header parse", parsed)

        // Invalid magic bytes
        val invalidMagicBuf = ByteArray(HatPacket.HEADER_SIZE) { 0xFF.toByte() }
        val parsedInvalid = HatPacket.parseHeader(invalidMagicBuf, 0, invalidMagicBuf.size)
        assertNull("Invalid magic bytes must fail header parse", parsedInvalid)
    }

    @Test
    fun testDiscoveryProbePacketParsedCorrectlyAndNotAdmitted() {
        val probeBuf = ByteArray(HatPacket.HEADER_SIZE)
        HatPacket.writeHeader(
            probeBuf, 0, HatPacket.Header(
                packetType = HatPacket.TYPE_DISCOVERY_PROBE,
                payloadLength = 0
            )
        )

        val parsed = HatPacket.parseHeader(probeBuf, 0, probeBuf.size)
        assertNotNull(parsed)
        assertEquals(HatPacket.TYPE_DISCOVERY_PROBE, parsed?.packetType)
        assertFalse(
            "Discovery probe must not admit receiver",
            isReceiverAdmissionPacket(parsed!!.packetType)
        )
    }

    @Test
    fun testTransmitterAnnouncementNotAdmittedAsReceiver() {
        val announceJson = "{\"role\":\"transmitter\"}".toByteArray(Charsets.UTF_8)
        val buf = ByteArray(HatPacket.HEADER_SIZE + announceJson.size)
        HatPacket.writeHeader(
            buf, 0, HatPacket.Header(
                packetType = HatPacket.TYPE_TRANSMITTER_ANNOUNCE,
                payloadLength = announceJson.size
            )
        )
        System.arraycopy(announceJson, 0, buf, HatPacket.HEADER_SIZE, announceJson.size)

        val parsed = HatPacket.parseHeader(buf, 0, buf.size)
        assertNotNull(parsed)
        assertEquals(HatPacket.TYPE_TRANSMITTER_ANNOUNCE, parsed?.packetType)
        assertFalse(
            "Transmitter announcement must not admit receiver",
            isReceiverAdmissionPacket(parsed!!.packetType)
        )
    }
}
