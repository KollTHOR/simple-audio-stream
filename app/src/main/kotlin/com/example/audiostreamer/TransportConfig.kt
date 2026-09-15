package com.example.audiostreamer

import android.util.Log

/**
 * Clean, typed representations decoupling:
 * 1. Audio format (sample rate, bit depth, channel layout)
 * 2. Codec (PCM, custom lossless codec, Opus, AAC)
 * 3. Transport/profile behavior (latency target, jitter buffering, FEC policy, transport parameters)
 */

enum class AudioBitDepth(val bits: Int, val wireCode: Byte) {
    BIT_16(16, HatPacket.BIT_DEPTH_16),
    BIT_24(24, HatPacket.BIT_DEPTH_24);

    val bytesPerSample: Int get() = bits / 8

    companion object {
        fun fromBits(bits: Int): AudioBitDepth = when (bits) {
            16 -> BIT_16
            24 -> BIT_24
            else -> throw IllegalArgumentException("Unsupported bit depth: $bits (only 16-bit and 24-bit supported)")
        }

        fun fromWireCode(code: Byte): AudioBitDepth? = entries.find { it.wireCode == code }
    }
}

enum class AudioChannelLayout(val channelCount: Int, val wireCode: Byte) {
    STEREO(2, HatPacket.CHANNELS_STEREO);

    companion object {
        fun fromChannelCount(channels: Int): AudioChannelLayout = when (channels) {
            2 -> STEREO
            else -> throw IllegalArgumentException("Unsupported channel count: $channels (only 2-channel STEREO supported)")
        }

        fun fromWireCode(code: Byte): AudioChannelLayout? = entries.find { it.wireCode == code }
    }
}

enum class AudioSampleRate(val sampleRateHz: Int, val wireCode: Byte) {
    RATE_44100(44100, HatPacket.RATE_44100),
    RATE_48000(48000, HatPacket.RATE_48000),
    RATE_88200(88200, HatPacket.RATE_88200),
    RATE_96000(96000, HatPacket.RATE_96000),
    RATE_176400(176400, HatPacket.RATE_176400),
    RATE_192000(192000, HatPacket.RATE_192000);

    companion object {
        fun fromHz(hz: Int): AudioSampleRate = when (hz) {
            44100 -> RATE_44100
            48000 -> RATE_48000
            88200 -> RATE_88200
            96000 -> RATE_96000
            176400 -> RATE_176400
            192000 -> RATE_192000
            else -> throw IllegalArgumentException("Unsupported sample rate: $hz Hz")
        }

        fun fromWireCode(code: Byte): AudioSampleRate? = entries.find { it.wireCode == code }
    }
}

data class AudioFormatConfig(
    val sampleRate: AudioSampleRate = AudioSampleRate.RATE_48000,
    val bitDepth: AudioBitDepth = AudioBitDepth.BIT_24,
    val channelLayout: AudioChannelLayout = AudioChannelLayout.STEREO
) {
    val sampleRateHz: Int get() = sampleRate.sampleRateHz
    val channels: Int get() = channelLayout.channelCount
    val bytesPerSample: Int get() = bitDepth.bytesPerSample
    val bytesPerFrame: Int get() = channels * bytesPerSample

    fun frameCountForDurationMs(durationMs: Float): Int {
        return (sampleRateHz * durationMs / 1000f).toInt()
    }

    fun pcmBytesForFrames(frames: Int): Int {
        return frames * bytesPerFrame
    }

    fun pcmBytesForDurationMs(durationMs: Float): Int {
        return pcmBytesForFrames(frameCountForDurationMs(durationMs))
    }
}

/**
 * Audio codecs supported by the transport protocol.
 *
 * NOTE: [PCM] is the primary logical stream codec for uncompressed / lossless audio.
 * [LOSSLESS] represents the custom ASLC (Adaptive Sub-band Lossless Codec) wire format.
 * In HAT protocol semantics, [HatPacket.CODEC_LOSSLESS_PCM] is a wire-level compression of
 * a logical PCM stream. Therefore, both [PCM] and [LOSSLESS] logical stream configurations
 * accept both [HatPacket.CODEC_RAW_PCM] and [HatPacket.CODEC_LOSSLESS_PCM] wire frames interchangeably,
 * without reconfiguring the stream or mutating the active [NegotiatedStreamConfig].
 */
enum class AudioCodec(val wireCode: Byte, val isCompressed: Boolean, val mimeType: String?) {
    PCM(HatPacket.CODEC_RAW_PCM, isCompressed = false, mimeType = null),
    LOSSLESS(HatPacket.CODEC_LOSSLESS_PCM, isCompressed = false, mimeType = null),
    OPUS(HatPacket.CODEC_OPUS, isCompressed = true, mimeType = AudioConfig.OPUS_MIME_TYPE),
    AAC(HatPacket.CODEC_AAC, isCompressed = true, mimeType = AudioConfig.AAC_MIME_TYPE);

    companion object {
        fun fromWireCode(code: Byte): AudioCodec? = entries.find { it.wireCode == code }
        fun fromName(name: String): AudioCodec? = entries.find { it.name.equals(name, ignoreCase = true) }
    }

    /**
     * Checks if this logical stream codec supports the incoming wire format codec code.
     * In HAT protocol semantics, LOSSLESS_PCM is a wire-level compression of a logical PCM stream.
     */
    fun isWireCodecSupported(wireCodec: Byte): Boolean = when (this) {
        PCM, LOSSLESS -> wireCodec == HatPacket.CODEC_RAW_PCM || wireCodec == HatPacket.CODEC_LOSSLESS_PCM
        OPUS -> wireCodec == HatPacket.CODEC_OPUS
        AAC -> wireCodec == HatPacket.CODEC_AAC
    }

    /**
     * Validates whether this codec can carry the specified audio format.
     */
    fun validateFormat(format: AudioFormatConfig): CodecValidationResult {
        return when (this) {
            PCM, LOSSLESS -> {
                // PCM and ASLC lossless codec support all defined sample rates in 16/24-bit stereo
                CodecValidationResult.Valid
            }
            OPUS -> {
                // Opus RFC 6716 operates on 48 kHz native framing and decodes to 16-bit PCM
                if (format.sampleRate != AudioSampleRate.RATE_48000) {
                    CodecValidationResult.Invalid("Opus requires 48,000 Hz framing, but requested ${format.sampleRateHz} Hz")
                } else if (format.bitDepth != AudioBitDepth.BIT_16) {
                    CodecValidationResult.Invalid("Opus operates on 16-bit PCM, but requested ${format.bitDepth.bits}-bit")
                } else if (format.channelLayout != AudioChannelLayout.STEREO) {
                    CodecValidationResult.Invalid("Opus transport requires stereo, but requested ${format.channels} channels")
                } else {
                    CodecValidationResult.Valid
                }
            }
            AAC -> {
                // AAC operates on 16-bit stereo at standard broadcast rates (44.1k or 48k)
                if (format.bitDepth != AudioBitDepth.BIT_16) {
                    CodecValidationResult.Invalid("AAC operates on 16-bit PCM, but requested ${format.bitDepth.bits}-bit")
                } else if (format.sampleRate != AudioSampleRate.RATE_48000 && format.sampleRate != AudioSampleRate.RATE_44100) {
                    CodecValidationResult.Invalid("AAC supports 44.1k and 48k Hz, but requested ${format.sampleRateHz} Hz")
                } else if (format.channelLayout != AudioChannelLayout.STEREO) {
                    CodecValidationResult.Invalid("AAC transport requires stereo, but requested ${format.channels} channels")
                } else {
                    CodecValidationResult.Valid
                }
            }
        }
    }
}

sealed class CodecValidationResult {
    object Valid : CodecValidationResult()
    data class Invalid(val reason: String) : CodecValidationResult()
    val isValid: Boolean get() = this is Valid
}

enum class LatencyTarget(val wireCode: Byte, val displayName: String) {
    LOW_LATENCY(HatPacket.PROFILE_LOW_LATENCY, "Low Latency"),
    BALANCED(HatPacket.PROFILE_AUTO, "Auto Adaptive"),
    RELIABLE(HatPacket.PROFILE_MUSIC, "Music");

    companion object {
        fun fromWireCode(code: Byte): LatencyTarget = when (code) {
            HatPacket.PROFILE_LOW_LATENCY -> LOW_LATENCY
            HatPacket.PROFILE_AUTO -> BALANCED
            else -> RELIABLE
        }

        fun fromString(str: String): LatencyTarget = when (str.uppercase()) {
            AudioConfig.PROFILE_VIDEO, AudioConfig.PROFILE_LOW_LATENCY -> LOW_LATENCY
            AudioConfig.PROFILE_AUTO -> BALANCED
            else -> RELIABLE
        }
    }
}

data class FecPolicy(
    val enabled: Boolean = true,
    val blockSize: Int = AudioConfig.FEC_BLOCK_SIZE
) {
    init {
        if (enabled) {
            require(blockSize in 2..16) { "FEC block size must be between 2 and 16 (got $blockSize)" }
        }
    }

    val wireBlockSize: Byte get() = if (enabled) blockSize.toByte() else 0
}

data class JitterBufferParameters(
    val slotCount: Int,
    val preRollPackets: Int,
    val maxUnderrunFrames: Int,
    val waitTimeoutMs: Long,
    val targetWatermarkSlots: Int,
    val targetWatermarkMs: Float
)

data class TransportParameters(
    val packetDurationMs: Float = 5.0f,
    val silenceSuppressionEnabled: Boolean = true,
    val silenceHeartbeatIntervalMs: Long = AudioConfig.SILENCE_HEARTBEAT_INTERVAL_MS,
    val silencePacketsThreshold: Int = AudioConfig.SILENCE_PACKETS_THRESHOLD,
    val socketSendBufferBytes: Int = AudioConfig.SOCKET_SEND_BUFFER_BYTES,
    val socketReceiveBufferBytes: Int = AudioConfig.SOCKET_RECEIVE_BUFFER_BYTES
)

data class TransportProfile(
    val latencyTarget: LatencyTarget,
    val jitter: JitterBufferParameters,
    val fec: FecPolicy,
    val transport: TransportParameters = TransportParameters()
) {
    companion object {
        fun create(
            target: LatencyTarget,
            fecEnabled: Boolean = true,
            isCompressedCodec: Boolean = false,
            sampleRateHz: Int = 48000
        ): TransportProfile {
            val nominalDuration = when {
                isCompressedCodec -> 20.0f
                sampleRateHz in listOf(176400, 192000) -> 2.5f
                else -> 5.0f
            }
            val fec = FecPolicy(enabled = fecEnabled, blockSize = AudioConfig.FEC_BLOCK_SIZE)
            val jitter = when (target) {
                LatencyTarget.LOW_LATENCY -> {
                    if (isCompressedCodec) {
                        JitterBufferParameters(
                            slotCount = AudioConfig.LOW_LATENCY_JITTER_BUFFER_SLOTS,
                            preRollPackets = AudioConfig.LOW_LATENCY_PRE_ROLL_PACKETS,
                            maxUnderrunFrames = AudioConfig.LOW_LATENCY_MAX_UNDERRUN_FRAMES,
                            waitTimeoutMs = AudioConfig.LOW_LATENCY_WAIT_TIMEOUT_MS,
                            targetWatermarkSlots = AudioConfig.LOW_LATENCY_TARGET_WATERMARK_SLOTS,
                            targetWatermarkMs = 40.0f
                        )
                    } else {
                        JitterBufferParameters(
                            slotCount = AudioConfig.getJitterBufferSlots(AudioConfig.PROFILE_LOW_LATENCY),
                            preRollPackets = AudioConfig.getPreRollPackets(AudioConfig.PROFILE_LOW_LATENCY),
                            maxUnderrunFrames = AudioConfig.getMaxUnderrunFrames(AudioConfig.PROFILE_LOW_LATENCY),
                            waitTimeoutMs = AudioConfig.getReceiverWaitTimeoutMs(AudioConfig.PROFILE_LOW_LATENCY),
                            targetWatermarkSlots = AudioConfig.getTargetWatermarkSlots(AudioConfig.PROFILE_LOW_LATENCY),
                            targetWatermarkMs = 40.0f
                        )
                    }
                }
                LatencyTarget.BALANCED -> {
                    JitterBufferParameters(
                        slotCount = AudioConfig.AUTO_JITTER_BUFFER_SLOTS,
                        preRollPackets = AudioConfig.AUTO_PRE_ROLL_PACKETS,
                        maxUnderrunFrames = AudioConfig.AUTO_MAX_UNDERRUN_FRAMES,
                        waitTimeoutMs = AudioConfig.AUTO_WAIT_TIMEOUT_MS,
                        targetWatermarkSlots = AudioConfig.AUTO_TARGET_WATERMARK_SLOTS,
                        targetWatermarkMs = 50.0f
                    )
                }
                LatencyTarget.RELIABLE -> {
                    JitterBufferParameters(
                        slotCount = AudioConfig.MUSIC_JITTER_BUFFER_SLOTS,
                        preRollPackets = AudioConfig.MUSIC_PRE_ROLL_PACKETS,
                        maxUnderrunFrames = AudioConfig.MUSIC_MAX_UNDERRUN_FRAMES,
                        waitTimeoutMs = AudioConfig.MUSIC_WAIT_TIMEOUT_MS,
                        targetWatermarkSlots = AudioConfig.MUSIC_TARGET_WATERMARK_SLOTS,
                        targetWatermarkMs = 200.0f
                    )
                }
            }
            return TransportProfile(
                latencyTarget = target,
                jitter = jitter,
                fec = fec,
                transport = TransportParameters(packetDurationMs = nominalDuration)
            )
        }
    }
}

/**
 * Unified stream configuration resulting from explicit negotiation.
 * Serves as the single source of truth for audio capture, packet serialization,
 * transport buffer sizing, and receiver sink configuration.
 */
data class NegotiatedStreamConfig(
    val audioFormat: AudioFormatConfig,
    val codec: AudioCodec,
    val transportProfile: TransportProfile,
    val generation: Long = 1L
) {
    init {
        val validation = codec.validateFormat(audioFormat)
        if (!validation.isValid) {
            val reason = (validation as CodecValidationResult.Invalid).reason
            throw IllegalArgumentException("Invalid transport configuration: $reason")
        }
    }

    val sampleRateHz: Int get() = audioFormat.sampleRateHz
    val bitDepthBits: Int get() = audioFormat.bitDepth.bits
    val channels: Int get() = audioFormat.channels
    val is24Bit: Boolean get() = audioFormat.bitDepth == AudioBitDepth.BIT_24
    val isCompressed: Boolean get() = codec.isCompressed
    val packetDurationMs: Float get() = transportProfile.transport.packetDurationMs

    fun toSummaryString(): String =
        "generation=$generation, profile=${transportProfile.latencyTarget.name}, codec=${codec.name}, sampleRate=$sampleRateHz, bitDepth=$bitDepthBits, channels=$channels"

    /**
     * Structured field set used by the runtime diagnostics lifecycle events (CONFIG_* / GENERATION_*).
     */
    fun diagnosticFields(receiverCount: Int = 0): Map<String, Any?> = linkedMapOf(
        "generation" to generation,
        "profile" to transportProfile.latencyTarget.name,
        "logicalCodec" to codec.name,
        "wireCodec" to codec.wireCode,
        "sampleRate" to sampleRateHz,
        "bitDepth" to bitDepthBits,
        "channels" to channels,
        "frameSize" to getFramesPerPacket(),
        "packetSize" to getNominalPcmPayloadSize(),
        "fecEnabled" to transportProfile.fec.enabled,
        "fecBlockSize" to transportProfile.fec.blockSize,
        "targetLatencyMs" to transportProfile.jitter.targetWatermarkMs,
        "receiverCount" to receiverCount
    )

    val transitionLogDescription: String
        get() = toSummaryString()

    fun getFramesPerPacket(): Int = when {
        codec == AudioCodec.OPUS -> 960
        codec == AudioCodec.AAC -> 1024
        else -> audioFormat.frameCountForDurationMs(packetDurationMs)
    }

    fun getNominalPcmPayloadSize(): Int {
        return getFramesPerPacket() * audioFormat.bytesPerFrame
    }

    /**
     * Verifies whether a joining receiver reporting [rxCapsMask] is capable
     * of consuming this running stream configuration without requiring renegotiation.
     *
     * In accordance with HAT protocol rules:
     * - An active stream generation is immutable once started.
     * - A joining receiver must support the stream's configured sample rate.
     * - If [rxCapsMask] is 0 (legacy or unstated), compatibility is assumed.
     */
    fun canReceiverConsume(rxCapsMask: Int): Boolean {
        if (rxCapsMask == 0) return true
        val requiredRateFlag = AudioCapabilities.rateToCapFlag(sampleRateHz)
        return (rxCapsMask and requiredRateFlag) != 0
    }

    /**
     * Derives a HatPacket.Header directly from this negotiated configuration.
     * Guarantees packet serialization matches the negotiated configuration.
     */
    fun createHeader(
        packetType: Byte,
        sequenceNumber: Int,
        payloadLength: Int,
        timestamp: Long,
        volumeOrCaps: Byte = 0,
        flags: Byte = HatPacket.FLAG_NONE,
        generation: Long = this.generation
    ): HatPacket.Header {
        return HatPacket.Header(
            version = HatPacket.PROTOCOL_VERSION,
            packetType = packetType,
            sequenceNumber = sequenceNumber,
            payloadLength = payloadLength,
            timestamp = if (packetType == HatPacket.TYPE_CONTROL && timestamp == 0L && generation > 0L) generation else timestamp,
            codec = codec.wireCode,
            profile = transportProfile.latencyTarget.wireCode,
            sampleRateCode = audioFormat.sampleRate.wireCode,
            bitDepth = if (codec.isCompressed) HatPacket.BIT_DEPTH_NONE else audioFormat.bitDepth.wireCode,
            channels = audioFormat.channelLayout.wireCode,
            volumeOrCaps = volumeOrCaps,
            fecBlockSize = if (transportProfile.fec.enabled) transportProfile.fec.wireBlockSize else 0,
            flags = flags,
            generation = generation
        )
    }

    /**
     * Validates whether an incoming packet header strictly matches this negotiated stream config.
     * Prevents transmitter and receiver from silently disagreeing about codec or audio format.
     */
    fun validatePacketAgreement(header: HatPacket.Header): AgreementResult {
        if (header.packetType != HatPacket.TYPE_AUDIO && header.packetType != HatPacket.TYPE_SILENCE_HEARTBEAT) {
            return AgreementResult.Agreed
        }

        if (!codec.isWireCodecSupported(header.codec)) {
            return AgreementResult.Disagreement(
                "Codec disagreement: expected ${codec.name} (0x${Integer.toHexString(codec.wireCode.toInt())}), " +
                "received 0x${Integer.toHexString(header.codec.toInt())}"
            )
        }

        if (header.sampleRateCode != audioFormat.sampleRate.wireCode) {
            return AgreementResult.Disagreement(
                "Sample rate disagreement: expected ${audioFormat.sampleRateHz} Hz (code ${audioFormat.sampleRate.wireCode}), " +
                "received ${header.sampleRateHz} Hz (code ${header.sampleRateCode})"
            )
        }

        if (header.channels != audioFormat.channelLayout.wireCode) {
            return AgreementResult.Disagreement(
                "Channels disagreement: expected ${audioFormat.channels}, received ${header.channels}"
            )
        }

        if (!codec.isCompressed && header.bitDepth != audioFormat.bitDepth.wireCode) {
            return AgreementResult.Disagreement(
                "Bit depth disagreement: expected ${audioFormat.bitDepth.bits}-bit, received ${header.bitDepth}-bit"
            )
        }

        return AgreementResult.Agreed
    }

    companion object {
        /**
         * Reconstructs a NegotiatedStreamConfig from an incoming packet header.
         * Returns null if any header field is unknown or combinations are invalid.
         */
        fun fromHeader(header: HatPacket.Header, fecEnabled: Boolean = true, generation: Long = if (header.generation > 0L) header.generation else 1L): NegotiatedStreamConfig? {
            val codec = AudioCodec.fromWireCode(header.codec) ?: return null
            val rate = AudioSampleRate.fromWireCode(header.sampleRateCode) ?: return null
            val layout = AudioChannelLayout.fromWireCode(header.channels) ?: return null
            val bitDepth = if (codec.isCompressed) {
                AudioBitDepth.BIT_16
            } else {
                AudioBitDepth.fromWireCode(header.bitDepth) ?: return null
            }
            val target = LatencyTarget.fromWireCode(header.profile)
            val fecActive = if (header.fecBlockSize > 0) true else fecEnabled
            val profile = TransportProfile.create(
                target = target,
                fecEnabled = fecActive,
                isCompressedCodec = codec.isCompressed,
                sampleRateHz = rate.sampleRateHz
            )
            val format = AudioFormatConfig(sampleRate = rate, bitDepth = bitDepth, channelLayout = layout)
            return try {
                NegotiatedStreamConfig(
                    audioFormat = format,
                    codec = codec,
                    transportProfile = profile,
                    generation = generation
                )
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

sealed class AgreementResult {
    object Agreed : AgreementResult()
    data class Disagreement(val reason: String) : AgreementResult()
    val isAgreed: Boolean get() = this is Agreed
}

/**
 * Result of attempting to transition or update a stream configuration.
 */
sealed class ConfigTransitionResult {
    data class Applied(val config: NegotiatedStreamConfig, val isInitial: Boolean) : ConfigTransitionResult()
    data class IdempotentIgnored(val generation: Long) : ConfigTransitionResult()
    data class RejectedStale(val incomingGeneration: Long, val currentGeneration: Long) : ConfigTransitionResult()
}

/**
 * Authoritative thread-safe state machine governing stream configurations.
 * Enforces:
 * - Deterministic, immutable configuration during generation N.
 * - Idempotency for repeated announcements with identical generation.
 * - Strict rejection of stale generation updates (generation < activeGeneration).
 * - Thread-safe synchronization preventing asynchronous/stale updates from overwriting newer configurations.
 */
class StreamConfigurationAuthority(initialConfig: NegotiatedStreamConfig? = null) {
    private val lock = Any()
    @Volatile
    private var _currentConfig: NegotiatedStreamConfig? = initialConfig
    @Volatile
    private var _currentGeneration: Long = initialConfig?.generation ?: 0L

    val currentConfig: NegotiatedStreamConfig? get() = _currentConfig
    val currentGeneration: Long get() = _currentGeneration

    /**
     * Atomically attempts to apply a stream configuration update.
     */
    fun applyUpdate(incoming: NegotiatedStreamConfig): ConfigTransitionResult {
        synchronized(lock) {
            val current = _currentConfig
            val activeGen = _currentGeneration

            if (current != null && incoming.generation < activeGen) {
                return ConfigTransitionResult.RejectedStale(
                    incomingGeneration = incoming.generation,
                    currentGeneration = activeGen
                )
            }

            if (current != null && incoming.generation == activeGen) {
                return ConfigTransitionResult.IdempotentIgnored(activeGen)
            }

            val isInitial = (current == null)
            _currentConfig = incoming
            _currentGeneration = incoming.generation
            return ConfigTransitionResult.Applied(incoming, isInitial)
        }
    }

    fun reset() {
        synchronized(lock) {
            _currentConfig = null
            _currentGeneration = 0L
        }
    }
}

/**
 * Explicit negotiation engine for audio streaming sessions.
 */
object StreamNegotiator {

    data class NegotiationRequest(
        val preferredProfile: LatencyTarget,
        val preferredCodec: AudioCodec? = null,
        val preferredSampleRateHz: Int = 48000,
        val preferred24Bit: Boolean = true,
        val txCapabilitiesMask: Int = 0,
        val rxCapabilitiesMask: Int = 0,
        val rxCapabilitiesList: List<Int> = emptyList(),
        val fecEnabled: Boolean = true,
        val isOpusEncoderAvailable: Boolean = true
    )

    /**
     * Resolves a mutually supported capability mask across multiple active receivers.
     * Uses intersection to prevent stream configuration oscillation when multiple receivers join or announce.
     */
    fun resolveMutuallySupportedCapabilities(receiverCapabilities: Collection<Int>): Int {
        val validMasks = receiverCapabilities.filter { it != 0 }
        if (validMasks.isEmpty()) return 0
        val intersection = validMasks.reduce { acc, mask -> acc and mask }
        if (intersection != 0) {
            return intersection
        }
        // Fallback: if intersection is empty (disjoint receiver capabilities), select 48 kHz native Android primary operating point
        return AudioCapabilities.CAP_FLAG_48000
    }

    /**
     * Executes explicit negotiation between transmitter and receiver capabilities.
     * Resolves format, codec, and transport parameters deterministically for generation [generation].
     */
    fun negotiate(request: NegotiationRequest, generation: Long = 1L): NegotiatedStreamConfig {
        // 1. Resolve Audio Format
        val effectiveTx = if (request.txCapabilitiesMask == 0) AudioCapabilities.getLocalCaptureCapabilitiesMask() else request.txCapabilitiesMask
        val effectiveRx = when {
            request.rxCapabilitiesList.isNotEmpty() -> resolveMutuallySupportedCapabilities(request.rxCapabilitiesList)
            request.rxCapabilitiesMask != 0 -> request.rxCapabilitiesMask
            else -> AudioCapabilities.getLocalPlaybackCapabilitiesMask()
        }
        val mutuallySupportedRate = AudioCapabilities.getHighestMutuallySupportedRate(effectiveTx, effectiveRx, request.preferredSampleRateHz)

        val sampleRate = AudioSampleRate.fromHz(mutuallySupportedRate)

        // 2. Resolve Codec and Profile
        val targetProfile = request.preferredProfile
        val (resolvedCodec, resolvedBitDepth) = when (targetProfile) {
            LatencyTarget.LOW_LATENCY -> {
                // Low latency requires 48kHz 16-bit compressed audio (Opus preferred, AAC fallback)
                val codec = request.preferredCodec ?: if (request.isOpusEncoderAvailable) AudioCodec.OPUS else AudioCodec.AAC
                Pair(codec, AudioBitDepth.BIT_16)
            }
            LatencyTarget.BALANCED, LatencyTarget.RELIABLE -> {
                val bitDepth = if (request.preferred24Bit) AudioBitDepth.BIT_24 else AudioBitDepth.BIT_16
                val codec = request.preferredCodec ?: AudioCodec.PCM
                Pair(codec, bitDepth)
            }
        }

        val format = AudioFormatConfig(
            sampleRate = if (resolvedCodec.isCompressed) AudioSampleRate.RATE_48000 else sampleRate,
            bitDepth = resolvedBitDepth,
            channelLayout = AudioChannelLayout.STEREO
        )

        val transportProfile = TransportProfile.create(
            target = targetProfile,
            fecEnabled = request.fecEnabled,
            isCompressedCodec = resolvedCodec.isCompressed,
            sampleRateHz = format.sampleRateHz
        )

        return NegotiatedStreamConfig(
            audioFormat = format,
            codec = resolvedCodec,
            transportProfile = transportProfile,
            generation = generation
        )
    }
}

/**
 * Result of attempting a transactional runtime profile change.
 */
sealed class ProfileChangeResult {
    /**
     * The transaction committed the new generation/config and announced it. [producerStarted] is false when
     * the producer could not be started afterwards: the committed generation is then authoritative but the
     * whole pipeline has been released and the transmitter is stopped.
     */
    data class Applied(
        val previousConfig: NegotiatedStreamConfig?,
        val newConfig: NegotiatedStreamConfig,
        val producerStarted: Boolean = true
    ) : ProfileChangeResult()

    data class IgnoredSameProfile(
        val activeProfile: LatencyTarget,
        val generation: Long
    ) : ProfileChangeResult()

    /**
     * The transaction aborted BEFORE committing anything, because the previous producer could not be stopped,
     * the new configuration could not be negotiated, or the new capture pipeline could not be fully
     * initialized ([cause] identifies the stage).
     *
     * Invariants guaranteed whenever this result is returned:
     * - [attemptedGeneration] was never committed: [StreamProfileTransactionManager.activeTransmitterConfig]
     *   still holds [previousConfig] (or null) and
     *   [StreamProfileTransactionManager.currentStreamGeneration] is unchanged.
     * - No announcement was emitted for [attemptedGeneration]: receivers never see a phantom generation.
     * - No producer was started and [StreamProfileTransactionManager.isTransmissionActive] is false.
     * - Every resource created by the aborted attempt was released.
     */
    data class InitializationFailed(
        val previousConfig: NegotiatedStreamConfig?,
        val requestedProfile: LatencyTarget,
        val attemptedGeneration: Long,
        val cause: Throwable? = null
    ) : ProfileChangeResult()

    /**
     * The transaction committed [abandonedGeneration] but the announcement failed, so the generation can no
     * longer be used: receivers may already have observed part of the announcement burst.
     *
     * Invariants guaranteed whenever this result is returned:
     * - [abandonedGeneration] stays consumed: generation numbers remain monotonic and are never reused, so
     *   the next successful transaction commits a strictly newer generation.
     * - [StreamProfileTransactionManager.activeTransmitterConfig] is rolled back to [previousConfig] so the
     *   transmitter advertises the last configuration that was fully committed AND announced.
     * - The pipeline initialized for [abandonedGeneration] was released and no producer was started:
     *   [StreamProfileTransactionManager.isTransmissionActive] is false.
     * - The failure is never swallowed; [cause] carries the announcement exception.
     */
    data class AnnouncementFailed(
        val previousConfig: NegotiatedStreamConfig?,
        val requestedProfile: LatencyTarget,
        val abandonedGeneration: Long,
        val cause: Throwable? = null
    ) : ProfileChangeResult()

    val isApplied: Boolean get() = this is Applied
}

/**
 * Transactional manager coordinating runtime transmitter stream profile changes.
 * Enforces atomic state transitions on the transmitter:
 * State A: Old config active, old generation transmitting.
 * Step 1: Stop the old producer synchronously (streamThread.join(), release/stop old AudioRecord) and clear
 *         the active-transmission flag. Assert: no old-generation packets produced.
 * Step 2: Build the PROVISIONAL new generation + complete NegotiatedStreamConfig. The generation counter is
 *         deliberately NOT advanced yet, so an aborted attempt cannot consume or expose a generation.
 * Step 3: Initialize the COMPLETE new capture pipeline (AudioRecord + required encoder resources) for the
 *         provisional config. Nothing is committed or announced until this succeeds.
 * Step 4: Commit the new generation + configuration as the transmitter's authoritative active state.
 * Step 5: Announce the committed generation so receivers can configure themselves before packets arrive.
 * Step 6: Start the producer for the already initialized pipeline (final activation step). Assert: every
 *         transmitted packet after resume uses the new generation and new configuration.
 *
 * Invariants held for EVERY exit path of this method (no unhandled exception may escape it):
 * - A generation is announced only AFTER the complete capture pipeline for it has been initialized;
 *   producer startup is the final activation step and may still fail afterwards.
 * - Every pipeline created for a committed generation is either consumed by a successfully started producer
 *   or fully released: the transmitter must never hold allocated capture resources without a producer.
 * - [isTransmissionActive] is true only while a producer for the committed generation is running.
 * - Pre-commit failures (step 1 or step 3) leave the previous configuration and generation fully
 *   authoritative and consume no generation.
 * - Post-commit failures keep the generation consumed (generation numbers are monotonic and never reused)
 *   and leave the transmitter in a clean stopped state with no producer.
 */
class StreamProfileTransactionManager(
    val currentStreamGeneration: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(0L)
) {
    private val profileChangeLock = java.util.concurrent.locks.ReentrantLock()

    @Volatile
    var activeTransmitterConfig: NegotiatedStreamConfig? = null
        private set

    @Volatile
    var isTransmissionActive: Boolean = false
        private set

    fun changeProfile(
        targetProfile: LatencyTarget,
        preferredCodec: AudioCodec? = null,
        preferredSampleRateHz: Int = 48000,
        preferred24Bit: Boolean = (targetProfile != LatencyTarget.LOW_LATENCY),
        txCapabilitiesMask: Int = 0,
        rxCapabilitiesMask: Int = 0,
        rxCapabilitiesList: List<Int> = emptyList(),
        fecEnabled: Boolean = true,
        isOpusEncoderAvailable: Boolean = true,
        onStopTransmission: (() -> Unit)? = null,
        onPublishAnnouncement: ((NegotiatedStreamConfig) -> Unit)? = null,
        /**
         * Initializes the COMPLETE capture pipeline (AudioRecord + every encoder the config requires)
         * for the given provisional config and returns true only when all of it is valid and armed.
         * Returning false must leave no resource behind.
         */
        onReconfigureCapture: ((NegotiatedStreamConfig) -> Boolean)? = null,
        /**
         * Starts the producer for the already initialized pipeline and returns true only when it is
         * actually running.
         */
        onResumeTransmission: ((NegotiatedStreamConfig) -> Boolean)? = null
    ): ProfileChangeResult {
        profileChangeLock.lock()
        try {
            val previousConfig = activeTransmitterConfig

            // Requirement 3: if requested profile equals current profile, return without changing generation/configuration or restarting capture
            if (previousConfig != null && previousConfig.transportProfile.latencyTarget == targetProfile) {
                return ProfileChangeResult.IgnoredSameProfile(
                    activeProfile = targetProfile,
                    generation = previousConfig.generation
                )
            }

            // Requirement 8: PROFILE_CHANGE_BEGIN
            val currentGen = previousConfig?.generation ?: currentStreamGeneration.get()
            val currentProfileName = previousConfig?.transportProfile?.latencyTarget?.name ?: "NONE"
            val currentCodecName = previousConfig?.codec?.name ?: "NONE"
            val currentRate = previousConfig?.sampleRateHz ?: preferredSampleRateHz
            val currentBits = previousConfig?.bitDepthBits ?: (if (preferred24Bit) 24 else 16)
            val currentChannels = previousConfig?.channels ?: 2
            Log.i(
                "AudioCaptureService",
                "PROFILE_CHANGE_BEGIN: targetProfile=${targetProfile.name}, generation=$currentGen, profile=$currentProfileName, codec=$currentCodecName, sampleRate=$currentRate, bitDepth=$currentBits, channels=$currentChannels"
            )
            HatDiagnostics.info(
                "CONFIG_REQUESTED",
                linkedMapOf(
                    "targetProfile" to targetProfile.name,
                    "requestedCodec" to (preferredCodec?.name ?: "AUTO"),
                    "requestedSampleRate" to preferredSampleRateHz,
                    "requested24Bit" to preferred24Bit,
                    "fecEnabled" to fecEnabled,
                    "receiverCount" to rxCapabilitiesList.size,
                    "previousGeneration" to currentGen,
                    "previousProfile" to currentProfileName,
                    "previousCodec" to currentCodecName,
                    "previousSampleRate" to currentRate,
                    "previousBitDepth" to currentBits,
                    "previousChannels" to currentChannels
                )
            )
            val transactionStartNs = System.nanoTime()
            val stopStartNs = System.nanoTime()
            var stopDurationNs = 0L
            var initDurationNs = 0L

            // Step 1: Stop the old producer synchronously (streamThread.join(), release/stop old AudioRecord).
            // Assert: no old-generation packets produced. From this point until a successful commit no
            // producer exists and no generation is transmitting.
            isTransmissionActive = false
            val provisionalGen = currentStreamGeneration.get() + 1L
            val stopFailure: Throwable? = try {
                onStopTransmission?.invoke()
                null
            } catch (t: Throwable) {
                Log.e("AudioCaptureService", "PROFILE_CHANGE_STOP_FAILED: previous producer teardown failed", t)
                t
            }
            stopDurationNs = System.nanoTime() - stopStartNs
            if (stopFailure != null) {
                // The previous producer's teardown is not trustworthy: abort before allocating anything new so
                // the transmitter stays stopped with the previous config/generation authoritative.
                HatDiagnostics.error(
                    "CONFIG_FAILURE",
                    linkedMapOf(
                        "stage" to "stop_previous_producer",
                        "targetProfile" to targetProfile.name,
                        "attemptedGeneration" to provisionalGen,
                        "stopMs" to (stopDurationNs / 1_000_000.0)
                    ),
                    stopFailure
                )
                return ProfileChangeResult.InitializationFailed(
                    previousConfig = previousConfig,
                    requestedProfile = targetProfile,
                    attemptedGeneration = provisionalGen,
                    cause = stopFailure
                )
            }

            // Step 2: Build the PROVISIONAL new generation + complete configuration. The generation counter
            // is only advanced at commit time so a failed attempt never consumes a generation.
            val resolvedCodec = preferredCodec ?: when (targetProfile) {
                LatencyTarget.LOW_LATENCY -> if (isOpusEncoderAvailable) AudioCodec.OPUS else AudioCodec.AAC
                else -> AudioCodec.PCM
            }
            val newConfig = try {
                StreamNegotiator.negotiate(
                    request = StreamNegotiator.NegotiationRequest(
                        preferredProfile = targetProfile,
                        preferredCodec = resolvedCodec,
                        preferredSampleRateHz = preferredSampleRateHz,
                        preferred24Bit = preferred24Bit && !resolvedCodec.isCompressed,
                        txCapabilitiesMask = txCapabilitiesMask,
                        rxCapabilitiesMask = rxCapabilitiesMask,
                        rxCapabilitiesList = rxCapabilitiesList,
                        fecEnabled = fecEnabled,
                        isOpusEncoderAvailable = isOpusEncoderAvailable
                    ),
                    generation = provisionalGen
                )
            } catch (t: Throwable) {
                // Nothing has been allocated, committed or announced yet: abort with the previous state intact.
                Log.e("AudioCaptureService", "PROFILE_CHANGE_NEGOTIATION_FAILED: targetProfile=${targetProfile.name}", t)
                HatDiagnostics.error(
                    "CONFIG_FAILURE",
                    linkedMapOf(
                        "stage" to "negotiation",
                        "targetProfile" to targetProfile.name,
                        "attemptedGeneration" to provisionalGen
                    ),
                    t
                )
                return ProfileChangeResult.InitializationFailed(
                    previousConfig = previousConfig,
                    requestedProfile = targetProfile,
                    attemptedGeneration = provisionalGen,
                    cause = t
                )
            }

            // Requirement 8: GENERATION_CREATED (provisional, NOT yet authoritative)
            Log.i(
                "AudioCaptureService",
                "GENERATION_CREATED: ${newConfig.toSummaryString()}"
            )
            HatDiagnostics.info("CONFIG_NEGOTIATED", newConfig.diagnosticFields(rxCapabilitiesList.size))
            HatDiagnostics.lifecycle("GENERATION_CREATED", provisionalGen)
            val initStartNs = System.nanoTime()

            // Step 3: Initialize the COMPLETE new capture pipeline (AudioRecord + required encoder
            // resources) BEFORE committing or announcing anything. A failure here aborts the transaction.
            var initFailure: Throwable? = null
            val pipelineReady = try {
                onReconfigureCapture?.invoke(newConfig) ?: true
            } catch (t: Throwable) {
                initFailure = t
                Log.e(
                    "AudioCaptureService",
                    "CAPTURE_PIPELINE_INIT_FAILED: ${newConfig.toSummaryString()}",
                    t
                )
                false
            }

            initDurationNs = System.nanoTime() - initStartNs
            if (!pipelineReady) {
                // Rollback: the previous configuration/generation stay authoritative, nothing is announced,
                // no producer is started and transmission stays inactive (clean stopped state).
                isTransmissionActive = false
                Log.e(
                    "AudioCaptureService",
                    "PROFILE_CHANGE_ABORTED: ${newConfig.toSummaryString()} was never committed or announced; " +
                        "previousGeneration=$currentGen remains authoritative"
                )
                HatDiagnostics.increment("config_init_failures")
                HatDiagnostics.error(
                    "CONFIG_FAILURE",
                    linkedMapOf(
                        "stage" to "capture_pipeline_init",
                        "attemptedGeneration" to provisionalGen,
                        "profile" to targetProfile.name,
                        "initMs" to (initDurationNs / 1_000_000.0),
                        "committed" to false,
                        "announced" to false
                    ),
                    initFailure
                )
                return ProfileChangeResult.InitializationFailed(
                    previousConfig = previousConfig,
                    requestedProfile = targetProfile,
                    attemptedGeneration = provisionalGen,
                    cause = initFailure
                )
            }

            // Step 4: Commit. Only now does the new generation/config become the authoritative active state.
            currentStreamGeneration.set(provisionalGen)
            activeTransmitterConfig = newConfig

            // Requirement 8: CONFIG_APPLIED
            Log.i(
                "AudioCaptureService",
                "CONFIG_APPLIED: ${newConfig.toSummaryString()}"
            )
            HatDiagnostics.setGeneration(provisionalGen, newConfig.diagnosticFields(rxCapabilitiesList.size))
            HatDiagnostics.info(
                "CONFIG_COMMITTED",
                newConfig.diagnosticFields(rxCapabilitiesList.size) +
                    mapOf(
                        "stopMs" to (stopDurationNs / 1_000_000.0),
                        "initMs" to (initDurationNs / 1_000_000.0),
                        "transactionMs" to ((System.nanoTime() - transactionStartNs) / 1_000_000.0)
                    )
            )
            HatDiagnostics.lifecycle("GENERATION_COMMITTED", provisionalGen)

            // Step 5: Announce the committed generation so receivers can adopt it before packets arrive.
            // An announcement failure must not be swallowed, and must not leave the generation half-live.
            val announcementFailure: Throwable? = try {
                onPublishAnnouncement?.invoke(newConfig)
                null
            } catch (t: Throwable) {
                Log.e("AudioCaptureService", "PROFILE_CHANGE_ANNOUNCE_FAILED: ${newConfig.toSummaryString()}", t)
                t
            }

            if (announcementFailure != null) {
                // The generation stays consumed (receivers may have seen part of the burst, so the number must
                // never be reused), while the authoritative config is rolled back to the previous one that was
                // fully committed AND announced. The pipeline is released and no producer runs.
                activeTransmitterConfig = previousConfig
                isTransmissionActive = false
                releaseInitializedPipeline(onStopTransmission, provisionalGen)
                Log.e(
                    "AudioCaptureService",
                    "PROFILE_CHANGE_ABORTED_AFTER_COMMIT: generation=$provisionalGen stays consumed, " +
                        "authoritative config rolled back, pipeline released, transmitter stopped"
                )
                HatDiagnostics.increment("config_announce_failures")
                HatDiagnostics.error(
                    "CONFIG_FAILURE",
                    linkedMapOf(
                        "stage" to "announcement",
                        "abandonedGeneration" to provisionalGen,
                        "profile" to targetProfile.name,
                        "committed" to true,
                        "announced" to false,
                        "producerStarted" to false
                    ),
                    announcementFailure
                )
                HatDiagnostics.lifecycle("GENERATION_STOPPED", provisionalGen, mapOf("reason" to "announcement_failed"))
                return ProfileChangeResult.AnnouncementFailed(
                    previousConfig = previousConfig,
                    requestedProfile = targetProfile,
                    abandonedGeneration = provisionalGen,
                    cause = announcementFailure
                )
            }

            // Announcement succeeded exactly now: this is the boundary receivers have observed.
            HatDiagnostics.info("CONFIG_ANNOUNCED", newConfig.diagnosticFields(rxCapabilitiesList.size))
            HatDiagnostics.lifecycle("GENERATION_ANNOUNCED", provisionalGen)

            // Step 6: Start the producer for the already initialized pipeline. Final activation step.
            val producerStarted = try {
                onResumeTransmission?.invoke(newConfig) ?: true
            } catch (t: Throwable) {
                Log.e("AudioCaptureService", "PRODUCER_START_FAILED: ${newConfig.toSummaryString()}", t)
                HatDiagnostics.error(
                    "CONFIG_FAILURE",
                    linkedMapOf(
                        "stage" to "producer_start",
                        "generation" to provisionalGen,
                        "profile" to targetProfile.name,
                        "committed" to true,
                        "announced" to true
                    ),
                    t
                )
                false
            }

            if (!producerStarted) {
                // The committed generation was announced, so it stays authoritative, but nothing may keep
                // allocated capture resources without a producer: release the pipeline and stay stopped.
                isTransmissionActive = false
                Log.e(
                    "AudioCaptureService",
                    "PROFILE_CHANGE_PRODUCER_START_FAILED: releasing pipeline and leaving transmitter stopped for ${newConfig.toSummaryString()}"
                )
                HatDiagnostics.increment("config_producer_start_failures")
                HatDiagnostics.lifecycle("GENERATION_STOPPED", provisionalGen, mapOf("reason" to "producer_start_failed"))
                releaseInitializedPipeline(onStopTransmission, provisionalGen)
            } else {
                isTransmissionActive = true
                HatDiagnostics.info(
                    "CONFIG_ACTIVE",
                    newConfig.diagnosticFields(rxCapabilitiesList.size) + mapOf(
                        "producerStarted" to true,
                        "transactionMs" to ((System.nanoTime() - transactionStartNs) / 1_000_000.0)
                    )
                )
            }

            // Requirement 8: PROFILE_CHANGE_COMPLETE
            Log.i(
                "AudioCaptureService",
                "PROFILE_CHANGE_COMPLETE: ${newConfig.toSummaryString()}, producerStarted=$producerStarted"
            )

            return ProfileChangeResult.Applied(
                previousConfig = previousConfig,
                newConfig = newConfig,
                producerStarted = producerStarted
            )
        } finally {
            profileChangeLock.unlock()
        }
    }

    /**
     * Best-effort release of a capture pipeline that was initialized for a generation which will not transmit.
     * A release failure must never propagate out of the transaction: the transmitter is already being left
     * stopped and [isTransmissionActive] is false on every path that calls this.
     */
    private fun releaseInitializedPipeline(onStopTransmission: (() -> Unit)?, generation: Long) {
        try {
            onStopTransmission?.invoke()
        } catch (t: Throwable) {
            Log.e("AudioCaptureService", "PIPELINE_RELEASE_FAILED: generation=$generation release callback threw", t)
        }
    }

    /**
     * Records that the active producer is no longer running, e.g. because the stream worker terminated on its
     * own after a streaming error. Keeps [isTransmissionActive] an accurate statement about whether a producer
     * is actually running without touching the committed configuration or generation.
     *
     * Deliberately lock-free: it is called from the terminating producer thread while a profile transaction may
     * hold [profileChangeLock] and wait for that very thread to finish.
     */
    fun markProducerStopped() {
        isTransmissionActive = false
        HatDiagnostics.lifecycle(
            "GENERATION_STOPPED",
            HatDiagnostics.generation(),
            mapOf("reason" to "worker_exited")
        )
    }

    fun reset() {
        profileChangeLock.lock()
        try {
            activeTransmitterConfig = null
            isTransmissionActive = false
        } finally {
            profileChangeLock.unlock()
        }
    }
}

