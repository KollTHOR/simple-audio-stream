package com.example.audiostreamer.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Config negotiation policy: accept supported formats, reject unsupported at the protocol level, and
 * — critically — NEVER silently resample or substitute a bit depth (spec §4). The runtime AudioTrack
 * probe is stubbed so this is pure.
 */
class ConfigValidatorTest {

    private val caps = PcmCapabilities.fullMatrix()
    private val alwaysOk: (PcmFormat) -> Int = { 8192 }
    private val alwaysFail: (PcmFormat) -> Int = { -1 }

    @Test
    fun acceptsSupportedFormatWhenProbeSucceeds() {
        val req = PcmFormat(96000, 24, 2)
        val r = ConfigValidator.validate(req, caps, alwaysOk)
        assertTrue(r is ConfigValidator.Result.Accepted)
        assertEquals(req, (r as ConfigValidator.Result.Accepted).format)
    }

    @Test
    fun acceptsMonoAnd16BitFullRange() {
        for (rate in intArrayOf(44100, 48000, 88200, 96000, 176400, 192000)) {
            for (depth in intArrayOf(16, 24, 32)) {
                val r = ConfigValidator.validate(PcmFormat(rate, depth, 2), caps, alwaysOk)
                assertTrue("expected $rate/$depth accepted", r is ConfigValidator.Result.Accepted)
            }
        }
    }

    @Test
    fun rejectsUnsupportedSampleRate() {
        val req = PcmFormat(22050, 16, 2) // not advertised
        val r = ConfigValidator.validate(req, caps, alwaysOk)
        assertTrue(r is ConfigValidator.Result.Rejected)
        assertEquals(AslcProtocol.ERR_FORMAT_UNSUPPORTED, (r as ConfigValidator.Result.Rejected).errorCode)
    }

    @Test
    fun rejectsUnsupportedBitDepth() {
        val r = ConfigValidator.validate(PcmFormat(48000, 8, 2), caps, alwaysOk)
        assertEquals(AslcProtocol.ERR_FORMAT_UNSUPPORTED, (r as ConfigValidator.Result.Rejected).errorCode)
    }

    @Test
    fun rejectsUnknownEncoding() {
        val r = ConfigValidator.validate(PcmFormat(48000, 16, 2, encoding = 0x7F), caps, alwaysOk)
        assertTrue(r is ConfigValidator.Result.Rejected)
    }

    @Test
    fun noSilentResample_whenProbeRejectsExactFormat() {
        // A format that is advertised but the actual device cannot render must be REJECTED, not
        // quietly downgraded. Probe always fails.
        val r = ConfigValidator.validate(PcmFormat(192000, 32, 2), caps, alwaysFail)
        assertTrue("must reject, never substitute", r is ConfigValidator.Result.Rejected)
        assertEquals(AslcProtocol.ERR_FORMAT_UNSUPPORTED, (r as ConfigValidator.Result.Rejected).errorCode)
    }

    @Test
    fun rejectsWhenProbeThrows() {
        val boom: (PcmFormat) -> Int = { throw IllegalStateException("no audio HAL") }
        val r = ConfigValidator.validate(PcmFormat(96000, 24, 2), caps, boom)
        assertTrue(r is ConfigValidator.Result.Rejected)
    }

    @Test
    fun rejectsOversizedFrameAgainstMaxPayload() {
        val tinyCaps = caps.copy(maxFrameBytes = 4)
        val r = ConfigValidator.validate(PcmFormat(48000, 32, 2), tinyCaps, alwaysOk) // bpf=8 > 4
        assertTrue(r is ConfigValidator.Result.Rejected)
        assertEquals(AslcProtocol.ERR_FRAME_LENGTH_INVALID, (r as ConfigValidator.Result.Rejected).errorCode)
    }

    @Test
    fun narrowedCapsDropTheRemovedRate() {
        val narrowed = caps.copyNarrowed(sampleRates = intArrayOf(44100, 48000))
        val r = ConfigValidator.validate(PcmFormat(96000, 24, 2), narrowed, alwaysOk)
        assertTrue("96k was narrowed out -> must reject", r is ConfigValidator.Result.Rejected)
    }

    @Test
    fun proberAdvertisesOnlyRenderableCombinations() {
        // Device renders only 16-bit stereo at 48k/96k. Everything else "fails" the probe.
        val probe: (PcmFormat) -> Int = { f ->
            if (f.bitDepth == 16 && f.channels == 2 && (f.sampleRate == 48000 || f.sampleRate == 96000)) 4096 else -1
        }
        val narrowed = UsbPcmProber.narrow(caps, probe)
        assertEquals(listOf(48000, 96000), narrowed.sampleRates.toList())
        assertEquals(listOf(16), narrowed.bitDepths.toList())
        assertEquals(listOf(2), narrowed.channels.toList())
    }

    @Test
    fun proberKeepsARateIfAnyDepthWorksAtIt() {
        val probe: (PcmFormat) -> Int = { f -> if (f.sampleRate == 44100 && f.bitDepth == 24) 100 else -1 }
        val narrowed = UsbPcmProber.narrow(caps, probe)
        assertTrue(narrowed.sampleRates.toList() == listOf(44100))
        assertTrue(narrowed.bitDepths.toList() == listOf(24))
    }

    @Test
    fun proberYieldsEmptyWhenNothingRenders() {
        val narrowed = UsbPcmProber.narrow(caps) { -1 }
        assertTrue(narrowed.sampleRates.isEmpty())
        assertTrue(narrowed.bitDepths.isEmpty())
        assertTrue(narrowed.channels.isEmpty())
    }
}
