package com.example.audiostreamer

import android.media.AudioFormat

object AudioConfig {
    const val SAMPLE_RATE = 48000
    const val CHANNELS = 2 // Stereo
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    
    // 5ms chunk = 240 frames * 2 channels * 2 bytes = 960 bytes
    // 960 bytes payload + 8 bytes header = 968 bytes total (strictly < 1500 MTU, zero Wi-Fi fragmentation)
    const val FRAME_SIZE_MS = 5
    const val PACKET_SIZE = 960

    // Packet Header
    const val HEADER_SIZE = 8
    const val MAGIC_HEADER: Short = 0x5341 // "SA" (Simple Audio)
    const val FLAG_NORMAL: Byte = 0x00
    const val FLAG_MUTE: Byte = 0x01
    const val FLAG_CONTROL_ONLY: Byte = 0x02
    const val FLAG_DISCONNECT: Byte = 0x04

    const val DEFAULT_PORT = 50005

    // Streaming Profiles
    const val PROFILE_MUSIC = "MUSIC"
    const val PROFILE_LOW_LATENCY = "LOW_LATENCY"
    const val PREF_KEY_PROFILE = "streaming_profile"

    // Music Mode: Deep cushion for lossless, uninterrupted playback
    const val MUSIC_JITTER_BUFFER_SLOTS = 256 // ~1,280ms
    const val MUSIC_PRE_ROLL_PACKETS = 40 // 200ms
    const val MUSIC_MAX_UNDERRUN_FRAMES = 30 // ~150ms
    const val MUSIC_WAIT_TIMEOUT_MS = 20L

    // Low Latency Mode: Optimized for video/gaming real-time responsiveness
    const val LOW_LATENCY_JITTER_BUFFER_SLOTS = 48 // ~240ms
    const val LOW_LATENCY_PRE_ROLL_PACKETS = 6 // 30ms
    const val LOW_LATENCY_MAX_UNDERRUN_FRAMES = 6 // ~30ms
    const val LOW_LATENCY_WAIT_TIMEOUT_MS = 6L

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

    // OS Socket Buffers
    const val SOCKET_SEND_BUFFER_BYTES = 524288 // 512 KB
    const val SOCKET_RECEIVE_BUFFER_BYTES = 1048576 // 1 MB

    // AudioRecord buffer capacity (500ms = 96,000 bytes)
    const val CAPTURE_BUFFER_BYTES = SAMPLE_RATE * CHANNELS * 2 / 2 // 500ms

    const val CHANNEL_IN_MASK = AudioFormat.CHANNEL_IN_STEREO
    const val CHANNEL_OUT_MASK = AudioFormat.CHANNEL_OUT_STEREO
}
