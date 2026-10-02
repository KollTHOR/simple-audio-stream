package com.example.audiostreamer.usb

/**
 * Validates a desktop-requested [PcmFormat] against the advertised capabilities and a *runtime*
 * AudioTrack support probe, returning either an accepted configuration or a protocol-level error.
 *
 * This is the single source of truth for "is this format actually processable" and never silently
 * resamples or substitutes a depth (spec §4). The [probe] callback is what makes it testable without
 * hardware: production passes a lambda that calls `AudioTrack.getMinBufferSize`; tests pass a stub.
 */
object ConfigValidator {

    sealed class Result {
        data class Accepted(val format: PcmFormat) : Result()
        data class Rejected(val errorCode: Int, val reason: String) : Result()
    }

    /**
     * @param probe returns the min AudioTrack buffer bytes for the format, or <= 0 / throws when the
     *              device cannot render it. Injected so this logic is pure and testable.
     */
    fun validate(
        requested: PcmFormat,
        caps: PcmCapabilities,
        probe: (PcmFormat) -> Int
    ): Result {
        // 1. Encoding must be one we understand.
        if (requested.encoding != AslcPayload.ENCODING_PCM) {
            return Result.Rejected(
                AslcProtocol.ERR_FORMAT_UNSUPPORTED,
                "unsupported encoding ${requested.encoding}"
            )
        }

        // 2. Bit depth must be a supported integer PCM width at all.
        if (!requested.isSupportedBitDepth) {
            return Result.Rejected(
                AslcProtocol.ERR_FORMAT_UNSUPPORTED,
                "unsupported bit depth ${requested.bitDepth}"
            )
        }

        // 3. Must be within the advertised capability set.
        if (!caps.supports(requested)) {
            return Result.Rejected(
                AslcProtocol.ERR_FORMAT_UNSUPPORTED,
                "format not in advertised capabilities: ${requested.displayLabel()}"
            )
        }

        // 4. Derived sizing must be sane.
        if (requested.bytesPerFrame <= 0 || requested.sampleRate <= 0 || requested.channels <= 0) {
            return Result.Rejected(
                AslcProtocol.ERR_FORMAT_UNSUPPORTED,
                "invalid frame geometry"
            )
        }

        // 5. Frame size must fit the advertised max single-payload.
        if (requested.bytesPerFrame > caps.maxFrameBytes) {
            return Result.Rejected(
                AslcProtocol.ERR_FRAME_LENGTH_INVALID,
                "bytesPerFrame ${requested.bytesPerFrame} exceeds maxFrameBytes ${caps.maxFrameBytes}"
            )
        }

        // 6. Runtime AudioTrack must be able to render this exact format (no substitution).
        val minBuf = try {
            probe(requested)
        } catch (e: Exception) {
            return Result.Rejected(
                AslcProtocol.ERR_FORMAT_UNSUPPORTED,
                "runtime probe failed for ${requested.displayLabel()}: ${e.message}"
            )
        }
        if (minBuf <= 0) {
            return Result.Rejected(
                AslcProtocol.ERR_FORMAT_UNSUPPORTED,
                "device cannot render ${requested.displayLabel()} (probe=$minBuf)"
            )
        }

        return Result.Accepted(requested)
    }
}
