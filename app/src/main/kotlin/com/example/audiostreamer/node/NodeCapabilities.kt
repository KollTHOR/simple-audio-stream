package com.example.audiostreamer.node

import com.example.audiostreamer.AudioBitDepth
import com.example.audiostreamer.AudioCapabilities
import com.example.audiostreamer.AudioCodec
import com.example.audiostreamer.HatPacket
import org.json.JSONArray
import org.json.JSONObject

/**
 * Supported network transport layer vectors.
 */
enum class NodeTransportType {
    LOCAL_WIFI,
    WIFI_DIRECT,
    BLUETOOTH_LE,
    CELLULAR,
    WIFI_AWARE,
    NFC
}

/**
 * Types of audio capture inputs supported by a HAT Node.
 */
enum class AudioInputType {
    NONE,
    MICROPHONE,
    SYSTEM_CAPTURE,
    LINE_IN
}

/**
 * Types of audio playback outputs supported by a HAT Node.
 */
enum class AudioOutputType {
    NONE,
    SPEAKER,
    HEADPHONES,
    LINE_OUT
}

/**
 * Hardware, codec, format, and network transport capabilities of a HAT Node.
 */
data class NodeCapabilities(
    val hasAudioInput: Boolean = true,
    val hasAudioOutput: Boolean = true,
    val hasMicrophone: Boolean = false,
    val hasSpeaker: Boolean = true,
    val supportedCodecs: Set<AudioCodec> = setOf(AudioCodec.PCM, AudioCodec.LOSSLESS, AudioCodec.OPUS),
    val supportedSampleRates: Set<Int> = setOf(48000, 44100),
    val supportedChannelCounts: Set<Int> = setOf(2),
    val supportedPcmFormats: Set<AudioBitDepth> = setOf(AudioBitDepth.BIT_16, AudioBitDepth.BIT_24),
    val supportedTransports: Set<NodeTransportType> = setOf(NodeTransportType.LOCAL_WIFI, NodeTransportType.WIFI_DIRECT),
    val supportedAudioInputs: Set<AudioInputType> = defaultAudioInputs(hasAudioInput, hasMicrophone),
    val supportedAudioOutputs: Set<AudioOutputType> = defaultAudioOutputs(hasAudioOutput, hasSpeaker),
    val protocolVersion: Int = HatPacket.PROTOCOL_VERSION.toInt()
) {
    val canCaptureMicrophone: Boolean
        get() = hasMicrophone || supportedAudioInputs.contains(AudioInputType.MICROPHONE)

    val canOutputToSpeaker: Boolean
        get() = hasSpeaker || supportedAudioOutputs.contains(AudioOutputType.SPEAKER)

    /**
     * Converts sample rate capabilities to the legacy bitmask used by [AudioCapabilities]
     * and wire packet headers.
     */
    fun toCapabilitiesMask(): Int {
        var mask = 0
        if (supportedSampleRates.contains(44100)) mask = mask or AudioCapabilities.CAP_FLAG_44100
        if (supportedSampleRates.contains(48000)) mask = mask or AudioCapabilities.CAP_FLAG_48000
        if (supportedSampleRates.contains(88200)) mask = mask or AudioCapabilities.CAP_FLAG_88200
        if (supportedSampleRates.contains(96000)) mask = mask or AudioCapabilities.CAP_FLAG_96000
        if (supportedSampleRates.contains(176400)) mask = mask or AudioCapabilities.CAP_FLAG_176400
        if (supportedSampleRates.contains(192000)) mask = mask or AudioCapabilities.CAP_FLAG_192000
        return mask
    }

    fun describe(): String {
        val rates = supportedSampleRates.sorted().joinToString("/") { "${it / 1000}kHz" }
        val depths = supportedPcmFormats.sortedBy { it.bits }.joinToString("/") { "${it.bits}b" }
        val codecs = supportedCodecs.joinToString("/") { it.name }
        val inDesc = if (supportedAudioInputs.isEmpty()) "none" else supportedAudioInputs.joinToString("/") { it.name }
        val outDesc = if (supportedAudioOutputs.isEmpty()) "none" else supportedAudioOutputs.joinToString("/") { it.name }
        return "$depths • $rates • $codecs (in: $inDesc, out: $outDesc)"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("in", hasAudioInput)
        put("out", hasAudioOutput)
        put("mic", hasMicrophone)
        put("spk", hasSpeaker)
        put("codecs", JSONArray(supportedCodecs.map { it.name }))
        put("rates", JSONArray(supportedSampleRates))
        put("channels", JSONArray(supportedChannelCounts))
        put("pcm", JSONArray(supportedPcmFormats.map { it.name }))
        put("transports", JSONArray(supportedTransports.map { it.name }))
        put("inputs", JSONArray(supportedAudioInputs.map { it.name }))
        put("outputs", JSONArray(supportedAudioOutputs.map { it.name }))
        put("pv", protocolVersion)
    }

    companion object {
        fun defaultAudioInputs(hasInput: Boolean, hasMic: Boolean): Set<AudioInputType> {
            if (!hasInput) return emptySet()
            val set = mutableSetOf<AudioInputType>()
            if (hasMic) set.add(AudioInputType.MICROPHONE)
            set.add(AudioInputType.SYSTEM_CAPTURE)
            return set
        }

        fun defaultAudioOutputs(hasOutput: Boolean, hasSpeaker: Boolean): Set<AudioOutputType> {
            if (!hasOutput) return emptySet()
            val set = mutableSetOf<AudioOutputType>()
            if (hasSpeaker) set.add(AudioOutputType.SPEAKER)
            return set
        }

        /**
         * Reconstitutes NodeCapabilities from a legacy [AudioCapabilities] bitmask.
         */
        fun fromCapabilitiesMask(
            mask: Int,
            hasAudioInput: Boolean = true,
            hasAudioOutput: Boolean = true,
            hasMicrophone: Boolean = false,
            hasSpeaker: Boolean = true,
            supportedCodecs: Set<AudioCodec> = setOf(AudioCodec.PCM, AudioCodec.LOSSLESS, AudioCodec.OPUS),
            supportedTransports: Set<NodeTransportType> = setOf(NodeTransportType.LOCAL_WIFI, NodeTransportType.WIFI_DIRECT),
            protocolVersion: Int = HatPacket.PROTOCOL_VERSION.toInt()
        ): NodeCapabilities {
            val rates = mutableSetOf<Int>()
            if ((mask and AudioCapabilities.CAP_FLAG_44100) != 0) rates.add(44100)
            if ((mask and AudioCapabilities.CAP_FLAG_48000) != 0) rates.add(48000)
            if ((mask and AudioCapabilities.CAP_FLAG_88200) != 0) rates.add(88200)
            if ((mask and AudioCapabilities.CAP_FLAG_96000) != 0) rates.add(96000)
            if ((mask and AudioCapabilities.CAP_FLAG_176400) != 0) rates.add(176400)
            if ((mask and AudioCapabilities.CAP_FLAG_192000) != 0) rates.add(192000)
            if (rates.isEmpty()) {
                rates.add(48000)
            }

            return NodeCapabilities(
                hasAudioInput = hasAudioInput,
                hasAudioOutput = hasAudioOutput,
                hasMicrophone = hasMicrophone,
                hasSpeaker = hasSpeaker,
                supportedCodecs = supportedCodecs,
                supportedSampleRates = rates,
                supportedChannelCounts = setOf(2),
                supportedPcmFormats = setOf(AudioBitDepth.BIT_16, AudioBitDepth.BIT_24),
                supportedTransports = supportedTransports,
                supportedAudioInputs = defaultAudioInputs(hasAudioInput, hasMicrophone),
                supportedAudioOutputs = defaultAudioOutputs(hasAudioOutput, hasSpeaker),
                protocolVersion = protocolVersion
            )
        }

        fun fromJson(json: JSONObject): NodeCapabilities {
            val codecs = mutableSetOf<AudioCodec>()
            val codecArr = json.optJSONArray("codecs") ?: json.optJSONArray("supportedCodecs")
            if (codecArr != null) {
                for (i in 0 until codecArr.length()) {
                    try {
                        codecs.add(AudioCodec.valueOf(codecArr.getString(i)))
                    } catch (ignored: Exception) {}
                }
            }
            if (codecs.isEmpty()) {
                codecs.addAll(listOf(AudioCodec.PCM, AudioCodec.LOSSLESS, AudioCodec.OPUS))
            }

            val sampleRates = mutableSetOf<Int>()
            val rateArr = json.optJSONArray("rates") ?: json.optJSONArray("supportedSampleRates")
            if (rateArr != null) {
                for (i in 0 until rateArr.length()) {
                    sampleRates.add(rateArr.getInt(i))
                }
            }
            if (sampleRates.isEmpty()) sampleRates.addAll(listOf(48000, 44100))

            val channelCounts = mutableSetOf<Int>()
            val chArr = json.optJSONArray("channels") ?: json.optJSONArray("supportedChannelCounts")
            if (chArr != null) {
                for (i in 0 until chArr.length()) {
                    channelCounts.add(chArr.getInt(i))
                }
            }
            if (channelCounts.isEmpty()) channelCounts.add(2)

            val pcmFormats = mutableSetOf<AudioBitDepth>()
            val pcmArr = json.optJSONArray("pcm") ?: json.optJSONArray("supportedPcmFormats")
            if (pcmArr != null) {
                for (i in 0 until pcmArr.length()) {
                    try {
                        pcmFormats.add(AudioBitDepth.valueOf(pcmArr.getString(i)))
                    } catch (ignored: Exception) {}
                }
            }
            if (pcmFormats.isEmpty()) pcmFormats.addAll(listOf(AudioBitDepth.BIT_16, AudioBitDepth.BIT_24))

            val transports = mutableSetOf<NodeTransportType>()
            val transArr = json.optJSONArray("transports") ?: json.optJSONArray("supportedTransports")
            if (transArr != null) {
                for (i in 0 until transArr.length()) {
                    try {
                        transports.add(NodeTransportType.valueOf(transArr.getString(i)))
                    } catch (ignored: Exception) {}
                }
            }
            if (transports.isEmpty()) transports.addAll(listOf(NodeTransportType.LOCAL_WIFI, NodeTransportType.WIFI_DIRECT))

            val inputs = mutableSetOf<AudioInputType>()
            val inArr = json.optJSONArray("inputs") ?: json.optJSONArray("supportedAudioInputs")
            if (inArr != null) {
                for (i in 0 until inArr.length()) {
                    try {
                        inputs.add(AudioInputType.valueOf(inArr.getString(i)))
                    } catch (ignored: Exception) {}
                }
            }

            val outputs = mutableSetOf<AudioOutputType>()
            val outArr = json.optJSONArray("outputs") ?: json.optJSONArray("supportedAudioOutputs")
            if (outArr != null) {
                for (i in 0 until outArr.length()) {
                    try {
                        outputs.add(AudioOutputType.valueOf(outArr.getString(i)))
                    } catch (ignored: Exception) {}
                }
            }

            val hasInput = json.optBoolean("in", json.optBoolean("hasAudioInput", inputs.isNotEmpty()))
            val hasOutput = json.optBoolean("out", json.optBoolean("hasAudioOutput", outputs.isNotEmpty() || json.optBoolean("hasSpeaker", true)))
            val hasMic = json.optBoolean("mic", json.optBoolean("hasMicrophone", inputs.contains(AudioInputType.MICROPHONE)))
            val hasSpk = json.optBoolean("spk", json.optBoolean("hasSpeaker", outputs.contains(AudioOutputType.SPEAKER) || outputs.isEmpty()))

            val effectiveInputs = if (inputs.isNotEmpty()) inputs else defaultAudioInputs(hasInput, hasMic)
            val effectiveOutputs = if (outputs.isNotEmpty()) outputs else defaultAudioOutputs(hasOutput, hasSpk)

            return NodeCapabilities(
                hasAudioInput = hasInput,
                hasAudioOutput = hasOutput,
                hasMicrophone = hasMic,
                hasSpeaker = hasSpk,
                supportedCodecs = codecs,
                supportedSampleRates = sampleRates,
                supportedChannelCounts = channelCounts,
                supportedPcmFormats = pcmFormats,
                supportedTransports = transports,
                supportedAudioInputs = effectiveInputs,
                supportedAudioOutputs = effectiveOutputs,
                protocolVersion = json.optInt("pv", json.optInt("protocolVersion", HatPacket.PROTOCOL_VERSION.toInt()))
            )
        }
    }
}
