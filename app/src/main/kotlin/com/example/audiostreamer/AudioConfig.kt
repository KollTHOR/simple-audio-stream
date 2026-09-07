package com.example.audiostreamer

import android.media.AudioFormat

object AudioConfig {
    const val SAMPLE_RATE = 48000
    const val CHANNELS = 2 // Stereo
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    const val FRAME_SIZE_MS = 10
    const val PACKET_SIZE = 1920 // 480 frames * 2 channels * 2 bytes
    const val DEFAULT_PORT = 50005

    // Minimal buffer size for sink AudioTrack (~40ms = 4 * 1920 bytes = 7680 bytes)
    const val SINK_BUFFER_SIZE_BYTES = 7680
    const val CHANNEL_IN_MASK = AudioFormat.CHANNEL_IN_STEREO
    const val CHANNEL_OUT_MASK = AudioFormat.CHANNEL_OUT_STEREO
}
