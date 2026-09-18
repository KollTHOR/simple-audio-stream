package com.example.audiostreamer

import com.example.audiostreamer.node.AudioInputType
import com.example.audiostreamer.node.AudioOutputType
import com.example.audiostreamer.node.NodeCapabilities
import com.example.audiostreamer.node.NodeCapabilityExchange
import com.example.audiostreamer.node.NodeCapabilityNegotiator
import com.example.audiostreamer.node.NodeIdentity
import com.example.audiostreamer.node.NodeInfo
import com.example.audiostreamer.node.NodeState
import com.example.audiostreamer.node.NodeTransportType
import com.example.audiostreamer.node.StreamRole
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NodeCapabilityExchangeTest {

    @Test
    fun testJsonRoundtripAndCompactness() {
        val identity = NodeIdentity.generate("Living Room M300")
        val caps = NodeCapabilities(
            hasAudioInput = true,
            hasAudioOutput = true,
            hasMicrophone = true,
            hasSpeaker = true,
            supportedCodecs = setOf(AudioCodec.PCM, AudioCodec.LOSSLESS, AudioCodec.OPUS),
            supportedSampleRates = setOf(44100, 48000, 96000),
            supportedChannelCounts = setOf(2),
            supportedPcmFormats = setOf(AudioBitDepth.BIT_16, AudioBitDepth.BIT_24),
            supportedTransports = setOf(NodeTransportType.LOCAL_WIFI, NodeTransportType.WIFI_DIRECT),
            supportedAudioInputs = setOf(AudioInputType.MICROPHONE, AudioInputType.SYSTEM_CAPTURE),
            supportedAudioOutputs = setOf(AudioOutputType.SPEAKER, AudioOutputType.HEADPHONES)
        )
        val node = NodeInfo(
            identity = identity,
            capabilities = caps,
            state = NodeState.AVAILABLE,
            activeRole = StreamRole.IDLE
        )

        val exchange = NodeCapabilityExchange.fromNode(node)
        val bytes = exchange.toByteArray()

        // 1. Requirement: Compact wire payload (< 500 bytes)
        assertTrue("Capability payload size ${bytes.size} bytes should be compact (< 500 bytes)", bytes.size < 500)

        // 2. Requirement: No unnecessary Android/device metadata
        val json = exchange.toJson()
        assertFalse(json.has("manufacturer"))
        assertFalse(json.has("model"))
        assertFalse(json.has("osVersion"))
        assertFalse(json.has("apiLevel"))
        assertFalse(json.has("buildId"))

        // 3. Roundtrip deserialization
        val parsed = NodeCapabilityExchange.parseOrNull(bytes)
        assertNotNull(parsed)
        assertEquals(exchange.nodeId, parsed!!.nodeId)
        assertEquals(exchange.nodeName, parsed.nodeName)
        assertEquals(exchange.protocolVersion, parsed.protocolVersion)
        assertEquals(exchange.capabilities.supportedCodecs, parsed.capabilities.supportedCodecs)
        assertEquals(exchange.capabilities.supportedSampleRates, parsed.capabilities.supportedSampleRates)
        assertEquals(exchange.capabilities.supportedChannelCounts, parsed.capabilities.supportedChannelCounts)
        assertEquals(exchange.capabilities.supportedPcmFormats, parsed.capabilities.supportedPcmFormats)
        assertEquals(exchange.capabilities.supportedTransports, parsed.capabilities.supportedTransports)
        assertEquals(exchange.capabilities.supportedAudioInputs, parsed.capabilities.supportedAudioInputs)
        assertEquals(exchange.capabilities.supportedAudioOutputs, parsed.capabilities.supportedAudioOutputs)
    }

    @Test
    fun testSafelyIgnoresUnknownFutureFieldsAndEnums() {
        val futureJson = JSONObject().apply {
            put("v", 2)
            put("id", "hat-node-future01")
            put("name", "Future Space Node")
            put("pv", 99)
            put("unknownTopLevelField", "quantum-data")
            put("futureNumber", 42)
            val capsJson = JSONObject().apply {
                put("hasAudioInput", true)
                put("hasAudioOutput", true)
                put("supportedCodecs", org.json.JSONArray(listOf("PCM", "QUANTUM_LOSSLESS_FUTURE", "OPUS")))
                put("supportedTransports", org.json.JSONArray(listOf("LOCAL_WIFI", "NEURAL_LINK_P2P", "WIFI_DIRECT")))
                put("inputs", org.json.JSONArray(listOf("MICROPHONE", "TELEPATHY")))
                put("outputs", org.json.JSONArray(listOf("SPEAKER", "HOLOGRAM_EMITTER")))
                put("futureCapabilityKey", true)
            }
            put("caps", capsJson)
        }

        val parsed = NodeCapabilityExchange.parseOrNull(futureJson.toString().toByteArray(Charsets.UTF_8))
        assertNotNull(parsed)
        assertEquals("hat-node-future01", parsed!!.nodeId)
        assertEquals("Future Space Node", parsed.nodeName)
        assertEquals(99, parsed.protocolVersion)

        // Known enums preserved, unknown enums safely filtered without throwing
        assertTrue(parsed.capabilities.supportedCodecs.contains(AudioCodec.PCM))
        assertTrue(parsed.capabilities.supportedCodecs.contains(AudioCodec.OPUS))
        assertEquals(2, parsed.capabilities.supportedCodecs.size)

        assertTrue(parsed.capabilities.supportedTransports.contains(NodeTransportType.LOCAL_WIFI))
        assertTrue(parsed.capabilities.supportedTransports.contains(NodeTransportType.WIFI_DIRECT))
        assertEquals(2, parsed.capabilities.supportedTransports.size)

        assertTrue(parsed.capabilities.supportedAudioInputs.contains(AudioInputType.MICROPHONE))
        assertEquals(1, parsed.capabilities.supportedAudioInputs.size)

        assertTrue(parsed.capabilities.supportedAudioOutputs.contains(AudioOutputType.SPEAKER))
        assertEquals(1, parsed.capabilities.supportedAudioOutputs.size)
    }

    @Test
    fun testMissingOptionalFieldsHandledWithSafeDefaults() {
        val minimalJson = JSONObject().apply {
            put("id", "hat-node-minimal01")
        }

        val parsed = NodeCapabilityExchange.parseOrNull(minimalJson.toString().toByteArray(Charsets.UTF_8))
        assertNotNull(parsed)
        assertEquals("hat-node-minimal01", parsed!!.nodeId)
        assertEquals("Unknown Node", parsed.nodeName)
        assertEquals(1, parsed.protocolVersion)
        assertTrue(parsed.capabilities.supportedCodecs.isNotEmpty())
        assertTrue(parsed.capabilities.supportedSampleRates.isNotEmpty())
    }

    @Test
    fun testAsymmetricNegotiationAudioOutputOnlyWithMicAndOutput() {
        // Node A: Audio Output Only (e.g. smart speaker or DAC)
        val nodeA = NodeInfo(
            identity = NodeIdentity("hat-node-speaker-dac", "Living Room Speaker"),
            capabilities = NodeCapabilities(
                hasAudioInput = false,
                hasAudioOutput = true,
                hasMicrophone = false,
                hasSpeaker = true,
                supportedCodecs = setOf(AudioCodec.PCM, AudioCodec.OPUS),
                supportedSampleRates = setOf(48000),
                supportedChannelCounts = setOf(2),
                supportedPcmFormats = setOf(AudioBitDepth.BIT_16),
                supportedTransports = setOf(NodeTransportType.LOCAL_WIFI),
                supportedAudioInputs = emptySet(),
                supportedAudioOutputs = setOf(AudioOutputType.SPEAKER)
            ),
            activeRole = StreamRole.RECEIVER
        )

        // Node B: Microphone + Audio Output (e.g. Android phone or intercom)
        val nodeB = NodeInfo(
            identity = NodeIdentity("hat-node-phone-mic", "User Phone"),
            capabilities = NodeCapabilities(
                hasAudioInput = true,
                hasAudioOutput = true,
                hasMicrophone = true,
                hasSpeaker = true,
                supportedCodecs = setOf(AudioCodec.PCM, AudioCodec.OPUS),
                supportedSampleRates = setOf(44100, 48000),
                supportedChannelCounts = setOf(2),
                supportedPcmFormats = setOf(AudioBitDepth.BIT_16),
                supportedTransports = setOf(NodeTransportType.LOCAL_WIFI),
                supportedAudioInputs = setOf(AudioInputType.MICROPHONE, AudioInputType.SYSTEM_CAPTURE),
                supportedAudioOutputs = setOf(AudioOutputType.SPEAKER)
            ),
            activeRole = StreamRole.SENDER
        )

        // Negotiate from perspective of Node A (Output Only)
        val negFromA = NodeCapabilityNegotiator.negotiate(nodeA, nodeB)
        assertTrue("Asymmetric nodes should be compatible", negFromA.isCompatible)
        assertEquals(nodeA.id, negFromA.localNodeId)
        assertEquals(nodeB.id, negFromA.remoteNodeId)
        assertFalse("Node A has no audio input, cannot stream outbound", negFromA.canStreamOutbound)
        assertTrue("Node B has audio input and Node A has audio output, can stream inbound", negFromA.canStreamInbound)
        assertEquals(AudioCodec.OPUS, negFromA.selectedCodec)
        assertEquals(48000, negFromA.selectedSampleRate)
        assertEquals(NodeTransportType.LOCAL_WIFI, negFromA.selectedTransport)

        // Negotiate from perspective of Node B (Mic + Output)
        val negFromB = NodeCapabilityNegotiator.negotiate(nodeB, nodeA)
        assertTrue("Asymmetric nodes should be compatible", negFromB.isCompatible)
        assertEquals(nodeB.id, negFromB.localNodeId)
        assertEquals(nodeA.id, negFromB.remoteNodeId)
        assertTrue("Node B can stream outbound to Node A", negFromB.canStreamOutbound)
        assertFalse("Node A cannot stream outbound to Node B", negFromB.canStreamInbound)
    }

    @Test
    fun testIncompatibleNodesNegotiation() {
        // Case 1: Two output-only nodes with NO input capabilities anywhere
        val nodeA = NodeInfo(
            identity = NodeIdentity("hat-node-out1", "Speaker 1"),
            capabilities = NodeCapabilities(
                hasAudioInput = false,
                hasAudioOutput = true,
                supportedAudioInputs = emptySet(),
                supportedAudioOutputs = setOf(AudioOutputType.SPEAKER)
            )
        )
        val nodeB = NodeInfo(
            identity = NodeIdentity("hat-node-out2", "Speaker 2"),
            capabilities = NodeCapabilities(
                hasAudioInput = false,
                hasAudioOutput = true,
                supportedAudioInputs = emptySet(),
                supportedAudioOutputs = setOf(AudioOutputType.SPEAKER)
            )
        )
        val negNoAudioFlow = NodeCapabilityNegotiator.negotiate(nodeA, nodeB)
        assertFalse("Two output-only nodes cannot form an audio stream", negNoAudioFlow.isCompatible)
        assertFalse(negNoAudioFlow.canStreamOutbound)
        assertFalse(negNoAudioFlow.canStreamInbound)
        assertTrue(negNoAudioFlow.details.contains("No viable audio flow direction"))

        // Case 2: Disjoint codecs
        val nodeDisjointCodec1 = NodeInfo(
            identity = NodeIdentity("hat-node-codec1", "Opus only"),
            capabilities = NodeCapabilities(
                hasAudioInput = true,
                hasAudioOutput = true,
                supportedCodecs = setOf(AudioCodec.OPUS)
            )
        )
        val nodeDisjointCodec2 = NodeInfo(
            identity = NodeIdentity("hat-node-codec2", "PCM only"),
            capabilities = NodeCapabilities(
                hasAudioInput = true,
                hasAudioOutput = true,
                supportedCodecs = setOf(AudioCodec.PCM)
            )
        )
        val negNoCodec = NodeCapabilityNegotiator.negotiate(nodeDisjointCodec1, nodeDisjointCodec2)
        assertFalse("Nodes with no common codecs must be incompatible", negNoCodec.isCompatible)
        assertNull(negNoCodec.selectedCodec)
        assertTrue(negNoCodec.details.contains("No common audio codecs"))

        // Case 3: Disjoint sample rates
        val nodeRate1 = NodeInfo(
            identity = NodeIdentity("hat-node-rate1", "44.1k only"),
            capabilities = NodeCapabilities(
                hasAudioInput = true,
                hasAudioOutput = true,
                supportedSampleRates = setOf(44100)
            )
        )
        val nodeRate2 = NodeInfo(
            identity = NodeIdentity("hat-node-rate2", "48k only"),
            capabilities = NodeCapabilities(
                hasAudioInput = true,
                hasAudioOutput = true,
                supportedSampleRates = setOf(48000)
            )
        )
        val negNoRate = NodeCapabilityNegotiator.negotiate(nodeRate1, nodeRate2)
        assertFalse("Nodes with no common sample rates must be incompatible", negNoRate.isCompatible)
        assertNull(negNoRate.selectedSampleRate)
        assertTrue(negNoRate.details.contains("No common sample rates"))
    }

    @Test
    fun testNodeIdentityRejectionOfIpAndMac() {
        val validCaps = NodeCapabilities()

        // IP addresses must be rejected as Node identity
        val ipAddresses = listOf(
            "192.168.1.1",
            "10.0.0.1",
            "127.0.0.1",
            "192.168.49.1:19850",
            "::1",
            "2001:0db8:85a3:0000:0000:8a2e:0370:7334"
        )
        for (ip in ipAddresses) {
            assertTrue("Should detect IP: $ip", NodeIdentity.isIpOrMac(ip))
            try {
                NodeCapabilityExchange(
                    nodeId = ip,
                    nodeName = "Invalid Node",
                    protocolVersion = 1,
                    capabilities = validCaps
                )
                fail("Should have rejected IP address '$ip' as Node ID")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("cannot be an IP or MAC address"))
            }
        }

        // MAC addresses must be rejected as Node identity
        val macAddresses = listOf(
            "00:1A:2B:3C:4D:5E",
            "02:15:b2:aa:bb:cc",
            "00-1A-2B-3C-4D-5E"
        )
        for (mac in macAddresses) {
            assertTrue("Should detect MAC: $mac", NodeIdentity.isIpOrMac(mac))
            try {
                NodeCapabilityExchange(
                    nodeId = mac,
                    nodeName = "Invalid Node",
                    protocolVersion = 1,
                    capabilities = validCaps
                )
                fail("Should have rejected MAC address '$mac' as Node ID")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("cannot be an IP or MAC address"))
            }
        }
    }
}
