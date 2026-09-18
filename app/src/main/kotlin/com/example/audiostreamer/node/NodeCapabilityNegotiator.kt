package com.example.audiostreamer.node

import com.example.audiostreamer.AudioBitDepth
import com.example.audiostreamer.AudioCodec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Result of negotiating capabilities between a local HAT Node and a remote HAT Node.
 */
data class NegotiatedNodeCapabilities(
    val isCompatible: Boolean,
    val localNodeId: String,
    val remoteNodeId: String,
    val protocolVersion: Int,
    val commonTransports: Set<NodeTransportType>,
    val selectedTransport: NodeTransportType?,
    val commonCodecs: Set<AudioCodec>,
    val selectedCodec: AudioCodec?,
    val commonSampleRates: Set<Int>,
    val selectedSampleRate: Int?,
    val commonChannels: Set<Int>,
    val selectedChannelCount: Int,
    val commonPcmFormats: Set<AudioBitDepth>,
    val selectedPcmFormat: AudioBitDepth?,
    val canStreamOutbound: Boolean, // Local node can capture/send to remote node's playback
    val canStreamInbound: Boolean,  // Remote node can capture/send to local node's playback
    val localAudioInputs: Set<AudioInputType>,
    val remoteAudioOutputs: Set<AudioOutputType>,
    val remoteAudioInputs: Set<AudioInputType>,
    val localAudioOutputs: Set<AudioOutputType>,
    val details: String = ""
) {
    fun summary(): String {
        if (!isCompatible) {
            return "Incompatible: $details"
        }
        val outDesc = if (canStreamOutbound) "Local->Remote (OK)" else "Local->Remote (No flow)"
        val inDesc = if (canStreamInbound) "Remote->Local (OK)" else "Remote->Local (No flow)"
        val transportStr = selectedTransport?.name ?: "None"
        val codecStr = selectedCodec?.name ?: "PCM"
        val rateStr = if (selectedSampleRate != null) "${selectedSampleRate / 1000}kHz" else "Unknown"
        val bitStr = if (selectedPcmFormat != null) "${selectedPcmFormat.bits}b" else "16b"
        return "$codecStr $rateStr $bitStr ($outDesc, $inDesc) via $transportStr"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("isCompatible", isCompatible)
        put("localNodeId", localNodeId)
        put("remoteNodeId", remoteNodeId)
        put("protocolVersion", protocolVersion)
        put("commonTransports", JSONArray(commonTransports.map { it.name }))
        put("selectedTransport", selectedTransport?.name ?: "")
        put("commonCodecs", JSONArray(commonCodecs.map { it.name }))
        put("selectedCodec", selectedCodec?.name ?: "")
        put("commonSampleRates", JSONArray(commonSampleRates))
        put("selectedSampleRate", selectedSampleRate ?: 0)
        put("commonChannels", JSONArray(commonChannels))
        put("selectedChannelCount", selectedChannelCount)
        put("commonPcmFormats", JSONArray(commonPcmFormats.map { it.name }))
        put("selectedPcmFormat", selectedPcmFormat?.name ?: "")
        put("canStreamOutbound", canStreamOutbound)
        put("canStreamInbound", canStreamInbound)
        put("details", details)
    }
}

/**
 * Negotiates capabilities between two HAT Nodes deterministically.
 *
 * Invariants:
 * 1. Symmetry: Either Node can negotiate the session.
 * 2. Asymmetric Nodes (e.g. Node A = audio output only, Node B = microphone + audio output)
 *    negotiate successfully if at least one compatible data flow and shared format exists.
 * 3. Unknown capability fields or unsupported future options are safely ignored.
 * 4. Missing optional fields fall back gracefully.
 */
object NodeCapabilityNegotiator {

    fun negotiate(
        localNode: NodeInfo,
        remoteNode: NodeInfo,
        preferredTransport: NodeTransportType? = null
    ): NegotiatedNodeCapabilities {
        return negotiate(
            localId = localNode.id,
            localCaps = localNode.capabilities,
            remoteId = remoteNode.id,
            remoteCaps = remoteNode.capabilities,
            preferredTransport = preferredTransport
        )
    }

    fun negotiate(
        localId: String,
        localCaps: NodeCapabilities,
        remoteId: String,
        remoteCaps: NodeCapabilities,
        preferredTransport: NodeTransportType? = null
    ): NegotiatedNodeCapabilities {
        val protoVersion = minOf(localCaps.protocolVersion, remoteCaps.protocolVersion)

        // 1. Network Transports
        val commonTransports = localCaps.supportedTransports.intersect(remoteCaps.supportedTransports)
        val selectedTransport = when {
            preferredTransport != null && commonTransports.contains(preferredTransport) -> preferredTransport
            commonTransports.contains(NodeTransportType.WIFI_DIRECT) -> NodeTransportType.WIFI_DIRECT
            commonTransports.contains(NodeTransportType.LOCAL_WIFI) -> NodeTransportType.LOCAL_WIFI
            else -> commonTransports.firstOrNull()
        }

        // 2. Audio Codecs
        // Preference order: OPUS > LOSSLESS > PCM > AAC
        val commonCodecs = localCaps.supportedCodecs.intersect(remoteCaps.supportedCodecs)
        val selectedCodec = when {
            commonCodecs.contains(AudioCodec.OPUS) -> AudioCodec.OPUS
            commonCodecs.contains(AudioCodec.LOSSLESS) -> AudioCodec.LOSSLESS
            commonCodecs.contains(AudioCodec.PCM) -> AudioCodec.PCM
            commonCodecs.contains(AudioCodec.AAC) -> AudioCodec.AAC
            else -> commonCodecs.firstOrNull()
        }

        // 3. Sample Rates
        val commonRates = localCaps.supportedSampleRates.intersect(remoteCaps.supportedSampleRates)
        // Select highest mutually supported sample rate
        val selectedRate = commonRates.maxOrNull()

        // 4. Channels
        val commonChannels = localCaps.supportedChannelCounts.intersect(remoteCaps.supportedChannelCounts)
        val selectedChannel = when {
            commonChannels.contains(2) -> 2
            commonChannels.contains(1) -> 1
            else -> commonChannels.firstOrNull() ?: 2
        }

        // 5. PCM Formats / Bit Depths
        val commonPcm = localCaps.supportedPcmFormats.intersect(remoteCaps.supportedPcmFormats)
        val selectedPcm = when {
            commonPcm.contains(AudioBitDepth.BIT_24) -> AudioBitDepth.BIT_24
            commonPcm.contains(AudioBitDepth.BIT_16) -> AudioBitDepth.BIT_16
            else -> commonPcm.firstOrNull()
        }

        // 6. Flow directions
        val localInputs = localCaps.supportedAudioInputs.filter { it != AudioInputType.NONE }.toSet()
        val localOutputs = localCaps.supportedAudioOutputs.filter { it != AudioOutputType.NONE }.toSet()
        val remoteInputs = remoteCaps.supportedAudioInputs.filter { it != AudioInputType.NONE }.toSet()
        val remoteOutputs = remoteCaps.supportedAudioOutputs.filter { it != AudioOutputType.NONE }.toSet()

        val localCanCapture = localInputs.isNotEmpty() || localCaps.hasAudioInput
        val remoteCanPlay = remoteOutputs.isNotEmpty() || remoteCaps.hasAudioOutput
        val remoteCanCapture = remoteInputs.isNotEmpty() || remoteCaps.hasAudioInput
        val localCanPlay = localOutputs.isNotEmpty() || localCaps.hasAudioOutput

        val canStreamOutbound = localCanCapture && remoteCanPlay
        val canStreamInbound = remoteCanCapture && localCanPlay

        // 7. Overall Compatibility Decision
        val hasFlow = canStreamOutbound || canStreamInbound
        val hasTransports = commonTransports.isNotEmpty()
        val hasCodecs = commonCodecs.isNotEmpty()
        val hasRates = commonRates.isNotEmpty()

        val isCompatible = hasFlow && hasTransports && hasCodecs && hasRates

        val details = when {
            !hasTransports -> "No common network transports (local=${localCaps.supportedTransports}, remote=${remoteCaps.supportedTransports})"
            !hasCodecs -> "No common audio codecs (local=${localCaps.supportedCodecs}, remote=${remoteCaps.supportedCodecs})"
            !hasRates -> "No common sample rates (local=${localCaps.supportedSampleRates}, remote=${remoteCaps.supportedSampleRates})"
            !hasFlow -> "No viable audio flow direction between nodes (local inputs: $localInputs, remote outputs: $remoteOutputs; remote inputs: $remoteInputs, local outputs: $localOutputs)"
            else -> "Compatible: negotiated ${selectedCodec?.name} at ${selectedRate}Hz"
        }

        return NegotiatedNodeCapabilities(
            isCompatible = isCompatible,
            localNodeId = localId,
            remoteNodeId = remoteId,
            protocolVersion = protoVersion,
            commonTransports = commonTransports,
            selectedTransport = selectedTransport,
            commonCodecs = commonCodecs,
            selectedCodec = selectedCodec,
            commonSampleRates = commonRates,
            selectedSampleRate = selectedRate,
            commonChannels = commonChannels,
            selectedChannelCount = selectedChannel,
            commonPcmFormats = commonPcm,
            selectedPcmFormat = selectedPcm,
            canStreamOutbound = canStreamOutbound,
            canStreamInbound = canStreamInbound,
            localAudioInputs = localCaps.supportedAudioInputs,
            remoteAudioOutputs = remoteCaps.supportedAudioOutputs,
            remoteAudioInputs = remoteCaps.supportedAudioInputs,
            localAudioOutputs = localCaps.supportedAudioOutputs,
            details = details
        )
    }
}
