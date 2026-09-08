package com.example.audiostreamer

import android.media.AudioFormat

object AudioConfig {
    const val SAMPLE_RATE_44100 = 44100
    const val SAMPLE_RATE_48000 = 48000
    const val SAMPLE_RATE_88200 = 88200
    const val SAMPLE_RATE_96000 = 96000
    const val SAMPLE_RATE_176400 = 176400
    const val SAMPLE_RATE_192000 = 192000
    const val DEFAULT_SAMPLE_RATE = SAMPLE_RATE_48000
    const val SAMPLE_RATE = SAMPLE_RATE_48000
    const val PREF_KEY_SAMPLE_RATE = "pref_sample_rate"
    const val SAMPLE_RATE_AUTO = "AUTO"
    const val SAMPLE_RATE_44K = "44100"
    const val SAMPLE_RATE_48K = "48000"
    const val SAMPLE_RATE_96K = "96000"
    const val SAMPLE_RATE_192K = "192000"

    const val CHANNELS = 2 // Stereo
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    
    const val FRAME_SIZE_MS = 5
    const val FRAMES_PER_PACKET_44K = 220
    const val PACKET_SIZE_16BIT_44K = FRAMES_PER_PACKET_44K * CHANNELS * 2 // 880
    const val PACKET_SIZE_24BIT_44K = FRAMES_PER_PACKET_44K * CHANNELS * 3 // 1320
    const val FRAMES_PER_PACKET_48K = 240
    const val PACKET_SIZE_16BIT_48K = FRAMES_PER_PACKET_48K * CHANNELS * 2 // 960
    const val PACKET_SIZE_24BIT_48K = FRAMES_PER_PACKET_48K * CHANNELS * 3 // 1440
    const val PACKET_SIZE_16BIT = PACKET_SIZE_16BIT_48K
    const val PACKET_SIZE_24BIT = PACKET_SIZE_24BIT_48K
    const val MAX_PACKET_SIZE = 8192 // Buffer headroom
    const val PACKET_SIZE = PACKET_SIZE_16BIT

    fun getFramesPerPacket(sampleRate: Int): Int = when (sampleRate) {
        SAMPLE_RATE_44100, SAMPLE_RATE_88200, SAMPLE_RATE_176400 -> FRAMES_PER_PACKET_44K
        else -> FRAMES_PER_PACKET_48K
    }

    fun getPacketPayloadSize(sampleRate: Int, is24Bit: Boolean): Int {
        val frames = getFramesPerPacket(sampleRate)
        val bytesPerSample = if (is24Bit) 3 else 2
        return frames * CHANNELS * bytesPerSample
    }

    // Packet Header
    const val HEADER_SIZE = 8
    const val MAGIC_HEADER: Short = 0x5341 // "SA" (Simple Audio)

    // Packet Header Flag Byte 5:
    // Bits 0-2: Sample Rate Signaling (44.1, 48, 88.2, 96, 176.4, 192 kHz)
    const val SAMPLE_RATE_FLAG_44100: Byte = 0x00
    const val SAMPLE_RATE_FLAG_48000: Byte = 0x01
    const val SAMPLE_RATE_FLAG_88200: Byte = 0x02
    const val SAMPLE_RATE_FLAG_96000: Byte = 0x03
    const val SAMPLE_RATE_FLAG_176400: Byte = 0x04
    const val SAMPLE_RATE_FLAG_192000: Byte = 0x05
    const val SAMPLE_RATE_MASK: Byte = 0x07

    fun sampleRateToFlagBits(sampleRate: Int): Byte = when (sampleRate) {
        SAMPLE_RATE_44100 -> SAMPLE_RATE_FLAG_44100
        SAMPLE_RATE_48000 -> SAMPLE_RATE_FLAG_48000
        SAMPLE_RATE_88200 -> SAMPLE_RATE_FLAG_88200
        SAMPLE_RATE_96000 -> SAMPLE_RATE_FLAG_96000
        SAMPLE_RATE_176400 -> SAMPLE_RATE_FLAG_176400
        SAMPLE_RATE_192000 -> SAMPLE_RATE_FLAG_192000
        else -> SAMPLE_RATE_FLAG_48000
    }

    fun flagBitsToSampleRate(flagByte: Byte): Int = when (flagByte.toInt() and SAMPLE_RATE_MASK.toInt()) {
        SAMPLE_RATE_FLAG_44100.toInt() -> SAMPLE_RATE_44100
        SAMPLE_RATE_FLAG_48000.toInt() -> SAMPLE_RATE_48000
        SAMPLE_RATE_FLAG_88200.toInt() -> SAMPLE_RATE_88200
        SAMPLE_RATE_FLAG_96000.toInt() -> SAMPLE_RATE_96000
        SAMPLE_RATE_FLAG_176400.toInt() -> SAMPLE_RATE_176400
        SAMPLE_RATE_FLAG_192000.toInt() -> SAMPLE_RATE_192000
        else -> SAMPLE_RATE_48000
    }

    // Packet Header Flags (Bits 3-7)
    const val FLAG_NORMAL: Byte = 0x00
    const val FLAG_CONTROL_ONLY: Byte = 0x02 // In control packets (payloadLen == 0)
    const val FLAG_DISCONNECT: Byte = 0x04   // In control packets (payloadLen == 0)
    const val FLAG_PROFILE_LOW_LATENCY: Byte = 0x08 // Compressed Opus / AAC stream
    const val FLAG_PROFILE_MUSIC: Byte = 0x00
    const val FLAG_PROFILE_AUTO: Byte = 0x80.toByte() // When FLAG_PROFILE_LOW_LATENCY is 0: 0x80 = Auto Adaptive, 0x00 = Music
    const val FLAG_24BIT: Byte = 0x10
    const val FLAG_SILENCE: Byte = 0x20
    const val FLAG_FEC_PARITY: Byte = 0x40
    const val FLAG_CODEC_AAC: Byte = 0x80.toByte() // When FLAG_PROFILE_LOW_LATENCY: 1 = AAC, 0 = Opus
    const val FLAG_CODEC_OPUS: Byte = 0x00
    const val FLAG_DISCOVERY_PROBE: Byte = 0x40
    const val FLAG_DISCOVERY_ANNOUNCE: Byte = 0x80.toByte()

    fun getProfileFromFlags(flags: Byte): String {
        val f = flags.toInt() and 0xFF
        return when {
            (f and FLAG_PROFILE_LOW_LATENCY.toInt()) != 0 -> PROFILE_LOW_LATENCY
            (f and 0x80) != 0 -> PROFILE_AUTO
            else -> PROFILE_MUSIC
        }
    }

    // Low Latency Codec Settings
    const val PREF_KEY_LOW_LATENCY_CODEC = "pref_low_latency_codec"
    const val CODEC_OPUS = "OPUS"
    const val CODEC_AAC = "AAC"
    const val CODEC_PCM = "PCM"
    const val OPUS_MIME_TYPE = "audio/opus"
    const val OPUS_BIT_RATE_HIGH = 320000 // 320 kbps (transparent studio quality)
    const val OPUS_BIT_RATE_LOW = 192000 // 192 kbps
    const val AAC_BIT_RATE = 192000 // 192 kbps
    const val AAC_MIME_TYPE = "audio/mp4a-latm"

    // Action to seamlessly restart capture when settings change
    const val ACTION_RESTART_CAPTURE = "com.example.audiostreamer.ACTION_RESTART_CAPTURE"

    // Forward Error Correction (XOR FEC)
    const val FEC_BLOCK_SIZE = 4 // 1 parity packet per 4 audio packets (25% overhead)
    const val PREF_KEY_FEC_ENABLED = "pref_fec_enabled"

    // Silence Suppression (Battery Saver)
    const val SILENCE_PACKETS_THRESHOLD = 100 // 500ms of sustained silence enters suppression
    const val SILENCE_HEARTBEAT_INTERVAL_MS = 500L // 2 packets/sec during silence
    const val SILENCE_AMPLITUDE_THRESHOLD_16BIT = 16
    const val SILENCE_AMPLITUDE_THRESHOLD_24BIT = 4096

    const val DEFAULT_PORT = 50005
    const val DISCOVERY_PORT = 50006

    // Streaming Profiles
    const val PROFILE_AUTO = "AUTO"
    const val PROFILE_MUSIC = "MUSIC"
    const val PROFILE_VIDEO = "VIDEO"
    const val PROFILE_LOW_LATENCY = "LOW_LATENCY" // Alias for backward compatibility
    const val PREF_KEY_PROFILE = "streaming_profile"

    // Music Mode: Uncapped studio master PCM playback (up to 192 kHz 24-bit)
    const val MUSIC_JITTER_BUFFER_SLOTS = 512 // ~2,560ms (2.56 seconds headroom)
    const val MUSIC_PRE_ROLL_PACKETS = 40 // 200ms pre-roll cushion
    const val MUSIC_MAX_UNDERRUN_FRAMES = 100 // 500ms concealment before muting
    const val MUSIC_WAIT_TIMEOUT_MS = 60L // 60ms wait absorbs Wi-Fi jitter completely
    const val MUSIC_TARGET_WATERMARK_SLOTS = 40 // 200ms target watermark

    // Auto Mode: Dynamic adaptive jitter buffer (RFC 3550 floating watermark 35ms - 400ms)
    const val AUTO_JITTER_BUFFER_SLOTS = 256 // ~1,280ms headroom
    const val AUTO_PRE_ROLL_PACKETS = 10 // 50ms pre-roll cushion
    const val AUTO_MAX_UNDERRUN_FRAMES = 40 // 200ms concealment
    const val AUTO_WAIT_TIMEOUT_MS = 40L
    const val AUTO_TARGET_WATERMARK_SLOTS = 10 // 50ms initial watermark

    // Low Latency Mode: High-efficiency Opus / AAC compressed audio (~40ms cushion, 80-85% less airtime)
    const val LOW_LATENCY_JITTER_BUFFER_SLOTS = 32 // ~640ms max headroom
    const val LOW_LATENCY_PRE_ROLL_PACKETS = 2 // ~40ms pre-roll cushion
    const val LOW_LATENCY_MAX_UNDERRUN_FRAMES = 8 // ~160ms concealment before rebuffering
    const val LOW_LATENCY_WAIT_TIMEOUT_MS = 40L // 40ms wait absorbs Wi-Fi jitter smoothly
    const val LOW_LATENCY_TARGET_WATERMARK_SLOTS = 2 // ~40ms target watermark

    // Video AAC Mode (Fallback): 1024-sample frames (~21.3ms per packet)
    const val LOW_LATENCY_AAC_JITTER_BUFFER_SLOTS = 32 // ~680ms headroom
    const val LOW_LATENCY_AAC_PRE_ROLL_PACKETS = 2 // ~42ms cushion
    const val LOW_LATENCY_AAC_MAX_UNDERRUN_FRAMES = 8 // ~170ms concealment
    const val LOW_LATENCY_AAC_WAIT_TIMEOUT_MS = 50L // 50ms wait safely absorbs Wi-Fi jitter
    const val LOW_LATENCY_AAC_TARGET_WATERMARK_SLOTS = 3 // ~64ms watermark

    // Defaults (Auto Mode)
    const val JITTER_BUFFER_SLOTS = AUTO_JITTER_BUFFER_SLOTS
    const val PRE_ROLL_PACKETS = AUTO_PRE_ROLL_PACKETS
    const val MAX_UNDERRUN_CONCEAL_FRAMES = AUTO_MAX_UNDERRUN_FRAMES
    const val RECEIVER_WAIT_TIMEOUT_MS = AUTO_WAIT_TIMEOUT_MS

    fun getJitterBufferSlots(profile: String): Int = when (profile) {
        PROFILE_VIDEO, PROFILE_LOW_LATENCY -> LOW_LATENCY_JITTER_BUFFER_SLOTS
        PROFILE_AUTO -> AUTO_JITTER_BUFFER_SLOTS
        else -> MUSIC_JITTER_BUFFER_SLOTS
    }

    fun getPreRollPackets(profile: String): Int = when (profile) {
        PROFILE_VIDEO, PROFILE_LOW_LATENCY -> LOW_LATENCY_PRE_ROLL_PACKETS
        PROFILE_AUTO -> AUTO_PRE_ROLL_PACKETS
        else -> MUSIC_PRE_ROLL_PACKETS
    }

    fun getMaxUnderrunFrames(profile: String): Int = when (profile) {
        PROFILE_VIDEO, PROFILE_LOW_LATENCY -> LOW_LATENCY_MAX_UNDERRUN_FRAMES
        PROFILE_AUTO -> AUTO_MAX_UNDERRUN_FRAMES
        else -> MUSIC_MAX_UNDERRUN_FRAMES
    }

    fun getReceiverWaitTimeoutMs(profile: String): Long = when (profile) {
        PROFILE_VIDEO, PROFILE_LOW_LATENCY -> LOW_LATENCY_WAIT_TIMEOUT_MS
        PROFILE_AUTO -> AUTO_WAIT_TIMEOUT_MS
        else -> MUSIC_WAIT_TIMEOUT_MS
    }

    fun getTargetWatermarkSlots(profile: String): Int = when (profile) {
        PROFILE_VIDEO, PROFILE_LOW_LATENCY -> LOW_LATENCY_TARGET_WATERMARK_SLOTS
        PROFILE_AUTO -> AUTO_TARGET_WATERMARK_SLOTS
        else -> MUSIC_TARGET_WATERMARK_SLOTS
    }

    // OS Socket Buffers
    const val SOCKET_SEND_BUFFER_BYTES = 524288 // 512 KB
    const val SOCKET_RECEIVE_BUFFER_BYTES = 1048576 // 1 MB

    // AudioRecord buffer capacity (500ms)
    const val CAPTURE_BUFFER_BYTES_16BIT = SAMPLE_RATE * CHANNELS * 2 / 2 // 500ms = 96,000 bytes
    const val CAPTURE_BUFFER_BYTES_24BIT = SAMPLE_RATE * CHANNELS * 3 / 2 // 500ms = 144,000 bytes
    const val CAPTURE_BUFFER_BYTES = CAPTURE_BUFFER_BYTES_24BIT

    const val CHANNEL_IN_MASK = AudioFormat.CHANNEL_IN_STEREO
    const val CHANNEL_OUT_MASK = AudioFormat.CHANNEL_OUT_STEREO
}
