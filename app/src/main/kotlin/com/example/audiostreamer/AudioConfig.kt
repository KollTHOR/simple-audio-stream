package com.example.audiostreamer

import android.media.AudioFormat

object AudioConfig {
    const val SAMPLE_RATE_44100 = 44100
    const val SAMPLE_RATE_48000 = 48000
    const val DEFAULT_SAMPLE_RATE = SAMPLE_RATE_48000
    const val SAMPLE_RATE = SAMPLE_RATE_48000
    const val PREF_KEY_SAMPLE_RATE = "pref_sample_rate"
    const val SAMPLE_RATE_AUTO = "AUTO"
    const val SAMPLE_RATE_44K = "44100"
    const val SAMPLE_RATE_48K = "48000"

    const val CHANNELS = 2 // Stereo
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    
    // 5ms chunk:
    // 44.1 kHz: 220 frames (~4.99ms)
    //   16-bit: 220 frames * 2 channels * 2 bytes = 880 bytes
    //   24-bit: 220 frames * 2 channels * 3 bytes = 1,320 bytes
    // 48.0 kHz: 240 frames (5.00ms)
    //   16-bit: 240 frames * 2 channels * 2 bytes = 960 bytes
    //   24-bit: 240 frames * 2 channels * 3 bytes = 1,440 bytes
    const val FRAME_SIZE_MS = 5
    const val FRAMES_PER_PACKET_44K = 220
    const val PACKET_SIZE_16BIT_44K = FRAMES_PER_PACKET_44K * CHANNELS * 2 // 880
    const val PACKET_SIZE_24BIT_44K = FRAMES_PER_PACKET_44K * CHANNELS * 3 // 1320
    const val FRAMES_PER_PACKET_48K = 240
    const val PACKET_SIZE_16BIT_48K = FRAMES_PER_PACKET_48K * CHANNELS * 2 // 960
    const val PACKET_SIZE_24BIT_48K = FRAMES_PER_PACKET_48K * CHANNELS * 3 // 1440
    const val PACKET_SIZE_16BIT = PACKET_SIZE_16BIT_48K
    const val PACKET_SIZE_24BIT = PACKET_SIZE_24BIT_48K
    const val MAX_PACKET_SIZE = PACKET_SIZE_24BIT_48K
    const val PACKET_SIZE = PACKET_SIZE_16BIT

    // Packet Header
    const val HEADER_SIZE = 8
    const val MAGIC_HEADER: Short = 0x5341 // "SA" (Simple Audio)
    // Packet Header Flags
    const val FLAG_NORMAL: Byte = 0x00
    const val FLAG_CODEC_AAC: Byte = 0x01 // 1 = AAC encoded payload, 0 = Raw PCM payload
    const val FLAG_CONTROL_ONLY: Byte = 0x02
    const val FLAG_DISCONNECT: Byte = 0x04
    const val FLAG_PROFILE_MUSIC: Byte = 0x00
    const val FLAG_PROFILE_LOW_LATENCY: Byte = 0x08
    const val FLAG_24BIT: Byte = 0x10
    const val FLAG_SILENCE: Byte = 0x20
    const val FLAG_FEC_PARITY: Byte = 0x40
    const val FLAG_SAMPLE_RATE_44100: Byte = 0x80.toByte()
    const val FLAG_DISCOVERY_PROBE: Byte = 0x40
    const val FLAG_DISCOVERY_ANNOUNCE: Byte = 0x80.toByte()

    // Low Latency Codec Settings
    const val PREF_KEY_LOW_LATENCY_CODEC = "pref_low_latency_codec"
    const val CODEC_PCM = "PCM"
    const val CODEC_AAC = "AAC"
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
    const val PROFILE_MUSIC = "MUSIC"
    const val PROFILE_LOW_LATENCY = "LOW_LATENCY"
    const val PREF_KEY_PROFILE = "streaming_profile"

    // Music Mode: Deep cushion for lossless, uninterrupted studio playback (24-bit 2,304 kbps)
    const val MUSIC_JITTER_BUFFER_SLOTS = 512 // ~2,560ms (2.56 seconds headroom)
    const val MUSIC_PRE_ROLL_PACKETS = 100 // 500ms pre-roll cushion
    const val MUSIC_MAX_UNDERRUN_FRAMES = 100 // 500ms concealment before muting
    const val MUSIC_WAIT_TIMEOUT_MS = 60L // 60ms wait absorbs Wi-Fi jitter completely
    const val MUSIC_TARGET_WATERMARK_SLOTS = 100 // 500ms target watermark for clock drift lock

    // Low Latency Mode: Optimized for video/gaming sync without stutter (~40-60ms)
    const val LOW_LATENCY_JITTER_BUFFER_SLOTS = 48 // ~240ms max headroom
    const val LOW_LATENCY_PRE_ROLL_PACKETS = 8 // 40ms pre-roll cushion
    const val LOW_LATENCY_MAX_UNDERRUN_FRAMES = 16 // ~80ms concealment before rebuffering
    const val LOW_LATENCY_WAIT_TIMEOUT_MS = 25L // 25ms wait safely absorbs 20ms Wi-Fi aggregation bursts
    const val LOW_LATENCY_TARGET_WATERMARK_SLOTS = 12 // 60ms target watermark

    // Low Latency AAC Mode: 1024-sample frames (~21.3ms per packet)
    const val LOW_LATENCY_AAC_JITTER_BUFFER_SLOTS = 32 // ~680ms headroom
    const val LOW_LATENCY_AAC_PRE_ROLL_PACKETS = 2 // ~42ms cushion
    const val LOW_LATENCY_AAC_MAX_UNDERRUN_FRAMES = 8 // ~170ms concealment
    const val LOW_LATENCY_AAC_WAIT_TIMEOUT_MS = 50L // 50ms wait safely absorbs Wi-Fi jitter
    const val LOW_LATENCY_AAC_TARGET_WATERMARK_SLOTS = 3 // ~64ms watermark

    // Defaults (Music Mode)
    const val JITTER_BUFFER_SLOTS = MUSIC_JITTER_BUFFER_SLOTS
    const val PRE_ROLL_PACKETS = MUSIC_PRE_ROLL_PACKETS
    const val MAX_UNDERRUN_CONCEAL_FRAMES = MUSIC_MAX_UNDERRUN_FRAMES
    const val RECEIVER_WAIT_TIMEOUT_MS = MUSIC_WAIT_TIMEOUT_MS

    fun getJitterBufferSlots(profile: String): Int =
        if (profile == PROFILE_LOW_LATENCY) LOW_LATENCY_JITTER_BUFFER_SLOTS else MUSIC_JITTER_BUFFER_SLOTS

    fun getPreRollPackets(profile: String): Int =
        if (profile == PROFILE_LOW_LATENCY) LOW_LATENCY_PRE_ROLL_PACKETS else MUSIC_PRE_ROLL_PACKETS

    fun getMaxUnderrunFrames(profile: String): Int =
        if (profile == PROFILE_LOW_LATENCY) LOW_LATENCY_MAX_UNDERRUN_FRAMES else MUSIC_MAX_UNDERRUN_FRAMES

    fun getReceiverWaitTimeoutMs(profile: String): Long =
        if (profile == PROFILE_LOW_LATENCY) LOW_LATENCY_WAIT_TIMEOUT_MS else MUSIC_WAIT_TIMEOUT_MS

    fun getTargetWatermarkSlots(profile: String): Int =
        if (profile == PROFILE_LOW_LATENCY) LOW_LATENCY_TARGET_WATERMARK_SLOTS else MUSIC_TARGET_WATERMARK_SLOTS

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
