package com.example.audiostreamer

import android.media.AudioFormat

object AudioConfig {
    const val SAMPLE_RATE = 48000
    const val CHANNELS = 2 // Stereo
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    
    // 5ms chunk:
    // 16-bit: 240 frames * 2 channels * 2 bytes = 960 bytes
    // 24-bit: 240 frames * 2 channels * 3 bytes = 1,440 bytes
    // 1,440 bytes payload + 8 bytes header = 1,448 bytes (< 1,500 Wi-Fi MTU, zero IP fragmentation)
    const val FRAME_SIZE_MS = 5
    const val PACKET_SIZE_16BIT = 960
    const val PACKET_SIZE_24BIT = 1440
    const val MAX_PACKET_SIZE = PACKET_SIZE_24BIT
    const val PACKET_SIZE = PACKET_SIZE_16BIT

    // Packet Header
    const val HEADER_SIZE = 8
    const val MAGIC_HEADER: Short = 0x5341 // "SA" (Simple Audio)
    // Packet Header Flags
    const val FLAG_NORMAL: Byte = 0x00
    const val FLAG_MUTE: Byte = 0x01
    const val FLAG_CONTROL_ONLY: Byte = 0x02
    const val FLAG_DISCONNECT: Byte = 0x04
    const val FLAG_PROFILE_MUSIC: Byte = 0x00
    const val FLAG_PROFILE_LOW_LATENCY: Byte = 0x08
    const val FLAG_24BIT: Byte = 0x10
    const val FLAG_SILENCE: Byte = 0x20
    const val FLAG_FEC_PARITY: Byte = 0x40
    const val FLAG_DISCOVERY_PROBE: Byte = 0x40
    const val FLAG_DISCOVERY_ANNOUNCE: Byte = 0x80.toByte()

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

    // Low Latency Mode: Optimized for video/gaming sync without stutter (~40-50ms)
    const val LOW_LATENCY_JITTER_BUFFER_SLOTS = 32 // ~160ms max headroom
    const val LOW_LATENCY_PRE_ROLL_PACKETS = 8 // 40ms pre-roll cushion (under 1 video frame)
    const val LOW_LATENCY_MAX_UNDERRUN_FRAMES = 12 // ~60ms concealment before rebuffering
    const val LOW_LATENCY_WAIT_TIMEOUT_MS = 25L // 25ms wait absorbs Wi-Fi jitter & allows FEC recovery
    const val LOW_LATENCY_TARGET_WATERMARK_SLOTS = 10 // 50ms target watermark

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
