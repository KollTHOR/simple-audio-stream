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

    const val DEFAULT_PORT = 50005

    // Jitter buffer sizing on receiver
    // 256 slots * 960 bytes = 245,760 bytes (~1,280ms buffer capacity to absorb network bursts)
    const val JITTER_BUFFER_SLOTS = 256
    // Pre-roll threshold: 40 packets = 200ms of buffered audio before playback begins
    const val PRE_ROLL_PACKETS = 40
    // Maximum consecutive concealed frames before entering re-buffering (~150ms)
    const val MAX_UNDERRUN_CONCEAL_FRAMES = 30
    // Wait timeout in ms for packet arrival on receiver read
    const val RECEIVER_WAIT_TIMEOUT_MS = 20L

    // OS Socket Buffers
    const val SOCKET_SEND_BUFFER_BYTES = 524288 // 512 KB
    const val SOCKET_RECEIVE_BUFFER_BYTES = 1048576 // 1 MB

    // AudioRecord buffer capacity (500ms = 96,000 bytes)
    const val CAPTURE_BUFFER_BYTES = SAMPLE_RATE * CHANNELS * 2 / 2 // 500ms

    const val CHANNEL_IN_MASK = AudioFormat.CHANNEL_IN_STEREO
    const val CHANNEL_OUT_MASK = AudioFormat.CHANNEL_OUT_STEREO
}
