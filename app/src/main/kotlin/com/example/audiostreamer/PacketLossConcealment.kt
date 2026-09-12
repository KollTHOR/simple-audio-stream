package com.example.audiostreamer

/**
 * Packet Loss Concealment (PLC) and Audio Discontinuity Smoothing Engine.
 *
 * Responsibilities:
 * - Caches end-of-packet audio sample amplitudes for 16-bit and 24-bit PCM.
 * - Synthesizes smooth fade-to-zero concealment buffers during underruns to prevent DC click/pop spikes.
 * - Applies smooth ramped crossfade upon audio stream resumption after loss or concealment.
 */
class PacketLossConcealment {
    var wasConcealed: Boolean = false
        private set

    var lastSampleLeft16: Short = 0
        private set
    var lastSampleRight16: Short = 0
        private set
    var lastSampleLeft24: Int = 0
        private set
    var lastSampleRight24: Int = 0
        private set

    fun reset() {
        wasConcealed = false
        lastSampleLeft16 = 0
        lastSampleRight16 = 0
        lastSampleLeft24 = 0
        lastSampleRight24 = 0
    }

    fun markConcealed() {
        wasConcealed = true
    }

    fun clearConcealed() {
        wasConcealed = false
    }

    fun clearCachedSamples() {
        lastSampleLeft16 = 0
        lastSampleRight16 = 0
        lastSampleLeft24 = 0
        lastSampleRight24 = 0
    }

    fun cacheLastSamples(output: ByteArray, len: Int, is24: Boolean) {
        if (is24 && len >= 6) {
            val idxL = len - 6
            val idxR = len - 3
            val rawL = (output[idxL].toInt() and 0xFF) or
                ((output[idxL + 1].toInt() and 0xFF) shl 8) or
                ((output[idxL + 2].toInt() and 0xFF) shl 16)
            lastSampleLeft24 = if (rawL and 0x800000 != 0) rawL or 0xFF000000.toInt() else rawL
            val rawR = (output[idxR].toInt() and 0xFF) or
                ((output[idxR + 1].toInt() and 0xFF) shl 8) or
                ((output[idxR + 2].toInt() and 0xFF) shl 16)
            lastSampleRight24 = if (rawR and 0x800000 != 0) rawR or 0xFF000000.toInt() else rawR
        } else if (len >= 4) {
            val lastIdx = len - 4
            lastSampleLeft16 = ((output[lastIdx].toInt() and 0xFF) or (output[lastIdx + 1].toInt() shl 8)).toShort()
            lastSampleRight16 = ((output[lastIdx + 2].toInt() and 0xFF) or (output[lastIdx + 3].toInt() shl 8)).toShort()
        }
    }

    fun applyCrossfadeOnResumption(output: ByteArray, len: Int, is24: Boolean) {
        wasConcealed = false
        val frameBytes = if (is24) 6 else 4
        val totalFrames = len / frameBytes
        val fadeFrames = minOf(20, totalFrames)

        if (is24) {
            for (f in 0 until fadeFrames) {
                val factor = (f + 1).toFloat() / fadeFrames
                val i = f * 6
                val rawL = (output[i].toInt() and 0xFF) or
                    ((output[i + 1].toInt() and 0xFF) shl 8) or
                    ((output[i + 2].toInt() and 0xFF) shl 16)
                var sL = if (rawL and 0x800000 != 0) rawL or 0xFF000000.toInt() else rawL
                val rawR = (output[i + 3].toInt() and 0xFF) or
                    ((output[i + 4].toInt() and 0xFF) shl 8) or
                    ((output[i + 5].toInt() and 0xFF) shl 16)
                var sR = if (rawR and 0x800000 != 0) rawR or 0xFF000000.toInt() else rawR
                sL = (sL * factor).toInt()
                sR = (sR * factor).toInt()
                output[i] = (sL and 0xFF).toByte()
                output[i + 1] = ((sL shr 8) and 0xFF).toByte()
                output[i + 2] = ((sL shr 16) and 0xFF).toByte()
                output[i + 3] = (sR and 0xFF).toByte()
                output[i + 4] = ((sR shr 8) and 0xFF).toByte()
                output[i + 5] = ((sR shr 16) and 0xFF).toByte()
            }
        } else {
            for (f in 0 until fadeFrames) {
                val factor = (f + 1).toFloat() / fadeFrames
                val i = f * 4
                var sL = ((output[i].toInt() and 0xFF) or (output[i + 1].toInt() shl 8)).toShort().toInt()
                var sR = ((output[i + 2].toInt() and 0xFF) or (output[i + 3].toInt() shl 8)).toShort().toInt()
                sL = (sL * factor).toInt()
                sR = (sR * factor).toInt()
                output[i] = (sL and 0xFF).toByte()
                output[i + 1] = ((sL shr 8) and 0xFF).toByte()
                output[i + 2] = (sR and 0xFF).toByte()
                output[i + 3] = ((sR shr 8) and 0xFF).toByte()
            }
        }
    }

    fun synthesizeLossConcealment(output: ByteArray, len: Int, is24: Boolean) {
        wasConcealed = true
        if (is24) {
            val numFrames = len / 6
            val initL = lastSampleLeft24
            val initR = lastSampleRight24
            for (f in 0 until numFrames) {
                val factor = (numFrames - f).toFloat() / numFrames
                val sL = (initL * factor).toInt()
                val sR = (initR * factor).toInt()
                val i = f * 6
                output[i] = (sL and 0xFF).toByte()
                output[i + 1] = ((sL shr 8) and 0xFF).toByte()
                output[i + 2] = ((sL shr 16) and 0xFF).toByte()
                output[i + 3] = (sR and 0xFF).toByte()
                output[i + 4] = ((sR shr 8) and 0xFF).toByte()
                output[i + 5] = ((sR shr 16) and 0xFF).toByte()
            }
            lastSampleLeft24 = 0
            lastSampleRight24 = 0
        } else {
            val numFrames = len / 4
            val initL = lastSampleLeft16.toInt()
            val initR = lastSampleRight16.toInt()
            for (f in 0 until numFrames) {
                val factor = (numFrames - f).toFloat() / numFrames
                val sL = (initL * factor).toInt()
                val sR = (initR * factor).toInt()
                val i = f * 4
                output[i] = (sL and 0xFF).toByte()
                output[i + 1] = ((sL shr 8) and 0xFF).toByte()
                output[i + 2] = (sR and 0xFF).toByte()
                output[i + 3] = ((sR shr 8) and 0xFF).toByte()
            }
            lastSampleLeft16 = 0
            lastSampleRight16 = 0
        }
    }
}
