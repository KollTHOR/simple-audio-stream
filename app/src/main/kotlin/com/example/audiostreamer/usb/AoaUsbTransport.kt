package com.example.audiostreamer.usb

import android.hardware.usb.UsbAccessory
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * [UsbTransport] backed by Android Open Accessory (AOA). This is the ONLY orientation that lets a
 * normal (no-root, non-gadget, non-UAC) Android app receive a reliable ordered byte pipe FROM a USB
 * host: the desktop issues the AOA control handshake, the phone enumerates as an accessory, and
 * [android.hardware.usb.UsbManager.openAccessory] hands back a [ParcelFileDescriptor] whose two
 * streams are the bulk endpoints.
 *
 * This class does not itself detect attach/detach (the service owns the broadcast receiver); it just
 * owns the descriptor + streams for ONE connected accessory and releases them precisely on [close],
 * which also unblocks any in-flight read on the pump thread with an IOException -> clean teardown.
 */
class AoaUsbTransport(
    private val accessory: UsbAccessory,
    private val fileDescriptor: ParcelFileDescriptor
) : UsbTransport {

    val accessoryDescription: String =
        listOfNotNull(accessory.manufacturer, accessory.model).joinToString(" ").ifBlank { "USB accessory" }

    private var closed = false

    // ParcelFileDescriptor exposes the raw fd; wrap it in the bulk in/out streams. These are the two
    // half-duplex AOA endpoints. Closing them unblocks the pump and lets the descriptor be freed.
    override val input: InputStream = FileInputStream(fileDescriptor.fileDescriptor)
    override val output: OutputStream = FileOutputStream(fileDescriptor.fileDescriptor)

    override val isAlive: Boolean get() = !closed

    /**
     * Closes the streams then the descriptor. Ordering matters: closing the streams first wakes the
     * blocked pump thread (read returns -1 / throws), then the fd is freed. Idempotent.
     */
    override fun close() {
        if (closed) return
        closed = true
        try { input.close() } catch (_: Exception) {}
        try { output.close() } catch (_: Exception) {}
        try { fileDescriptor.close() } catch (_: Exception) {}
    }
}
