package com.example.audiostreamer

/**
 * Control message protocol for end-to-end synchronized HAT diagnostic test sessions (Phase 3.2).
 *
 * Wire rules:
 *  - Exchanged out-of-band over the existing UDP control channel (never embedded in audio datagrams).
 *  - Encoded as UTF-8 flat JSON datagrams starting with '{' (byte 0x7B), so any HatPacket parser
 *    (which strictly requires magic "HT" 0x48 0x54) rejects them immediately with zero allocation.
 *  - HatPacket wire format and protocol are strictly unmodified.
 *  - Pure Kotlin serialization/deserialization: does not depend on org.json, so it works reliably
 *    in JVM unit tests as well as on Android.
 */
sealed class HatTestControlMessage {
    abstract val type: String
    abstract val testSessionId: String

    /** Transmitter announces the testSessionId to the connected receiver. */
    data class AnnounceSession(
        override val testSessionId: String,
        val generation: Long,
        val txDevice: String = ""
    ) : HatTestControlMessage() {
        override val type: String = TYPE
        companion object { const val TYPE = "TEST_SESSION_ANNOUNCE" }
    }

    /** Receiver explicitly acknowledges test session participation. */
    data class SessionJoined(
        override val testSessionId: String,
        val device: String,
        val appVersion: String,
        val generation: Long
    ) : HatTestControlMessage() {
        override val type: String = TYPE
        companion object { const val TYPE = "TEST_SESSION_JOINED" }
    }

    /** Receiver periodically (every ~1s) sends compact diagnostic telemetry during an active test session. */
    data class RxStats(
        override val testSessionId: String,
        val generation: Long,
        val timestamp: Long,
        val packetsReceived: Long,
        val packetsLost: Long,
        val packetsLate: Long,
        val packetsOutOfOrder: Long,
        val packetsDuplicate: Long,
        val fecRecovered: Long,
        val decodeErrors: Long,
        val bufferPackets: Int,
        val bufferFrames: Int,
        val bufferMs: Double,
        val targetLatencyMs: Double,
        val jitterMs: Double,
        val driftPpm: Double,
        val audioTrackWrites: Long,
        val framesWritten: Long,
        val underruns: Long,
        val writeErrors: Long,
        val avgReceiveMs: Double? = null,
        val maxReceiveMs: Double? = null,
        val avgDecodeMs: Double? = null,
        val maxDecodeMs: Double? = null,
        val avgWriteMs: Double? = null,
        val maxWriteMs: Double? = null,
        val playbackHead: Long = 0L
    ) : HatTestControlMessage() {
        override val type: String = TYPE
        companion object { const val TYPE = "RX_TEST_STATS" }
    }

    /** Receiver acknowledges new stream generation arrival and playback. */
    data class GenerationAck(
        override val testSessionId: String,
        val generation: Long,
        val profile: String? = null,
        val firstRxTimestamp: Long = 0L,
        val firstDecodeTimestamp: Long = 0L,
        val firstAudioWriteTimestamp: Long = 0L
    ) : HatTestControlMessage() {
        override val type: String = TYPE
        companion object { const val TYPE = "TEST_GENERATION_ACK" }
    }

    /** Transmitter announces end / cancellation of the active test session. */
    data class EndSession(
        override val testSessionId: String
    ) : HatTestControlMessage() {
        override val type: String = TYPE
        companion object { const val TYPE = "TEST_SESSION_END" }
    }

    fun toByteArray(): ByteArray = toJson().toByteArray(Charsets.UTF_8)

    fun toJson(): String = when (this) {
        is AnnounceSession -> HatTestJson.compose(
            "type" to type,
            "testSessionId" to testSessionId,
            "generation" to generation,
            "txDevice" to txDevice
        )
        is SessionJoined -> HatTestJson.compose(
            "type" to type,
            "testSessionId" to testSessionId,
            "device" to device,
            "appVersion" to appVersion,
            "generation" to generation
        )
        is RxStats -> HatTestJson.compose(
            "type" to type,
            "testSessionId" to testSessionId,
            "generation" to generation,
            "timestamp" to timestamp,
            "packetsReceived" to packetsReceived,
            "packetsLost" to packetsLost,
            "packetsLate" to packetsLate,
            "packetsOutOfOrder" to packetsOutOfOrder,
            "packetsDuplicate" to packetsDuplicate,
            "fecRecovered" to fecRecovered,
            "decodeErrors" to decodeErrors,
            "bufferPackets" to bufferPackets,
            "bufferFrames" to bufferFrames,
            "bufferMs" to bufferMs,
            "targetLatencyMs" to targetLatencyMs,
            "jitterMs" to jitterMs,
            "driftPpm" to driftPpm,
            "audioTrackWrites" to audioTrackWrites,
            "framesWritten" to framesWritten,
            "underruns" to underruns,
            "writeErrors" to writeErrors,
            "avgReceiveMs" to avgReceiveMs,
            "maxReceiveMs" to maxReceiveMs,
            "avgDecodeMs" to avgDecodeMs,
            "maxDecodeMs" to maxDecodeMs,
            "avgWriteMs" to avgWriteMs,
            "maxWriteMs" to maxWriteMs,
            "playbackHead" to playbackHead
        )
        is GenerationAck -> HatTestJson.compose(
            "type" to type,
            "testSessionId" to testSessionId,
            "generation" to generation,
            "profile" to profile,
            "firstRxTimestamp" to firstRxTimestamp,
            "firstDecodeTimestamp" to firstDecodeTimestamp,
            "firstAudioWriteTimestamp" to firstAudioWriteTimestamp
        )
        is EndSession -> HatTestJson.compose(
            "type" to type,
            "testSessionId" to testSessionId
        )
    }

    companion object {
        private const val OPEN_BRACE: Byte = '{'.code.toByte()

        /**
         * Fast parsing of incoming UDP datagrams.
         * Returns null immediately if packet does not start with '{' (i.e. HatPacket binary packets).
         */
        fun parse(buffer: ByteArray, offset: Int = 0, length: Int): HatTestControlMessage? {
            if (length < 2 || offset < 0 || offset + length > buffer.size) return null
            if (buffer[offset] != OPEN_BRACE) return null

            val json = try {
                String(buffer, offset, length, Charsets.UTF_8).trim()
            } catch (e: Exception) {
                return null
            }

            val map = parseFlatJson(json)
            val type = map["type"] ?: return null
            val sessionId = map["testSessionId"] ?: return null

            return when (type) {
                AnnounceSession.TYPE -> AnnounceSession(
                    testSessionId = sessionId,
                    generation = map["generation"]?.toLongOrNull() ?: 0L,
                    txDevice = map["txDevice"].orEmpty()
                )
                SessionJoined.TYPE -> SessionJoined(
                    testSessionId = sessionId,
                    device = map["device"].orEmpty(),
                    appVersion = map["appVersion"].orEmpty(),
                    generation = map["generation"]?.toLongOrNull() ?: 0L
                )
                RxStats.TYPE -> RxStats(
                    testSessionId = sessionId,
                    generation = map["generation"]?.toLongOrNull() ?: 0L,
                    timestamp = map["timestamp"]?.toLongOrNull() ?: 0L,
                    packetsReceived = map["packetsReceived"]?.toLongOrNull() ?: 0L,
                    packetsLost = map["packetsLost"]?.toLongOrNull() ?: 0L,
                    packetsLate = map["packetsLate"]?.toLongOrNull() ?: 0L,
                    packetsOutOfOrder = map["packetsOutOfOrder"]?.toLongOrNull() ?: 0L,
                    packetsDuplicate = map["packetsDuplicate"]?.toLongOrNull() ?: 0L,
                    fecRecovered = map["fecRecovered"]?.toLongOrNull() ?: 0L,
                    decodeErrors = map["decodeErrors"]?.toLongOrNull() ?: 0L,
                    bufferPackets = map["bufferPackets"]?.toIntOrNull() ?: 0,
                    bufferFrames = map["bufferFrames"]?.toIntOrNull() ?: 0,
                    bufferMs = map["bufferMs"]?.toDoubleOrNull() ?: 0.0,
                    targetLatencyMs = map["targetLatencyMs"]?.toDoubleOrNull() ?: 0.0,
                    jitterMs = map["jitterMs"]?.toDoubleOrNull() ?: 0.0,
                    driftPpm = map["driftPpm"]?.toDoubleOrNull() ?: 0.0,
                    audioTrackWrites = map["audioTrackWrites"]?.toLongOrNull() ?: 0L,
                    framesWritten = map["framesWritten"]?.toLongOrNull() ?: 0L,
                    underruns = map["underruns"]?.toLongOrNull() ?: 0L,
                    writeErrors = map["writeErrors"]?.toLongOrNull() ?: 0L,
                    avgReceiveMs = parseNullableDouble(map["avgReceiveMs"]),
                    maxReceiveMs = parseNullableDouble(map["maxReceiveMs"]),
                    avgDecodeMs = parseNullableDouble(map["avgDecodeMs"]),
                    maxDecodeMs = parseNullableDouble(map["maxDecodeMs"]),
                    avgWriteMs = parseNullableDouble(map["avgWriteMs"]),
                    maxWriteMs = parseNullableDouble(map["maxWriteMs"]),
                    playbackHead = map["playbackHead"]?.toLongOrNull() ?: 0L
                )
                GenerationAck.TYPE -> GenerationAck(
                    testSessionId = sessionId,
                    generation = map["generation"]?.toLongOrNull() ?: 0L,
                    profile = map["profile"]?.takeIf { it != "null" && it.isNotBlank() },
                    firstRxTimestamp = map["firstRxTimestamp"]?.toLongOrNull() ?: 0L,
                    firstDecodeTimestamp = map["firstDecodeTimestamp"]?.toLongOrNull() ?: 0L,
                    firstAudioWriteTimestamp = map["firstAudioWriteTimestamp"]?.toLongOrNull() ?: 0L
                )
                EndSession.TYPE -> EndSession(
                    testSessionId = sessionId
                )
                else -> null
            }
        }

        private fun parseNullableDouble(str: String?): Double? {
            if (str == null || str == "null" || str.isBlank()) return null
            return str.toDoubleOrNull()
        }

        /**
         * Parses a flat single-level JSON object into key-value strings without external libraries.
         */
        internal fun parseFlatJson(json: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            val s = json.trim()
            if (!s.startsWith("{") || !s.endsWith("}")) return emptyMap()
            var i = 1
            val len = s.length - 1
            while (i < len) {
                // Find key opening quote
                val keyStart = s.indexOf('"', i)
                if (keyStart == -1 || keyStart >= len) break
                val keyEnd = s.indexOf('"', keyStart + 1)
                if (keyEnd == -1) break
                val key = s.substring(keyStart + 1, keyEnd)

                // Find colon
                val colon = s.indexOf(':', keyEnd + 1)
                if (colon == -1 || colon >= len) break

                // Find value start
                var valStart = colon + 1
                while (valStart < len && s[valStart].isWhitespace()) valStart++
                if (valStart >= len) break

                if (s[valStart] == '"') {
                    // Quoted string value
                    val valEnd = s.indexOf('"', valStart + 1)
                    if (valEnd == -1) break
                    val rawVal = s.substring(valStart + 1, valEnd)
                    result[key] = unescapeJson(rawVal)
                    i = valEnd + 1
                } else {
                    // Primitive (number, boolean, null)
                    var valEnd = valStart
                    while (valEnd < len && s[valEnd] != ',' && s[valEnd] != '}' && !s[valEnd].isWhitespace()) {
                        valEnd++
                    }
                    val rawVal = s.substring(valStart, valEnd).trim()
                    result[key] = rawVal
                    i = valEnd
                }

                // Advance to next comma or end
                while (i < len && s[i] != ',') {
                    if (s[i] == '}') break
                    i++
                }
                if (i < len && s[i] == ',') i++
            }
            return result
        }

        private fun unescapeJson(s: String): String {
            if (!s.contains('\\')) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (val next = s[i + 1]) {
                        '"' -> { sb.append('"'); i += 2 }
                        '\\' -> { sb.append('\\'); i += 2 }
                        'n' -> { sb.append('\n'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        else -> { sb.append(next); i += 2 }
                    }
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }
    }
}
