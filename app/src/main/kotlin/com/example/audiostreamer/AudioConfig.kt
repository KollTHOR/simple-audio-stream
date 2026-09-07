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
    // 32 slots * 960 bytes = 30,720 bytes (~160ms buffer capacity)
    const val JITTER_BUFFER_SLOTS = 32
    // Pre-roll threshold: 12 packets = 60ms of buffered audio before playback begins
    const val PRE_ROLL_PACKETS = 12

    const val CHANNEL_IN_MASK = AudioFormat.CHANNEL_IN_STEREO
    const val CHANNEL_OUT_MASK = AudioFormat.CHANNEL_OUT_STEREO
}
