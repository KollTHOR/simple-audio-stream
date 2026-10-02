package com.example.audiostreamer.usb

import java.io.InputStream
import java.io.OutputStream

/**
 * A transport-agnostic, reliable, ordered, bidirectional byte pipe that the ASLC protocol runs over.
 *
 * Deliberately does NOT model packets/datagrams: the entire reliability of the design rests on the
 * transport giving an ordered stream (exactly what AOA and a socket both do), so the framing layer
 * stays self-delimiting. This lets the SAME PCM pipeline (spec §13) later be fed by:
 *   - [AoaUsbTransport]  (this task)
 *   - a future network-PCM source
 *   - any other ordered byte pipe
 */
interface UsbTransport {
    /** Ordered bytes arriving from the desktop host. Blocks / returns -1 at EOF (detach). */
    val input: InputStream

    /** Ordered bytes to the desktop host. */
    val output: OutputStream

    /** True while the underlying link is up. */
    val isAlive: Boolean

    /** Releases all hardware/OS resources. Idempotent. */
    fun close()
}
