package io.dayd.bebop.aoa

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Canal raw bidirectionnel avec un UsbAccessory (mode AOA).
 * - input : ce que l'accessoire envoie au téléphone
 * - output : ce que le téléphone envoie à l'accessoire
 *
 * Le file descriptor sous-jacent est un duplex socket-like fourni par le kernel.
 * Fermer le ParcelFileDescriptor coupe les deux streams.
 */
class AoaTransport private constructor(
    private val pfd: ParcelFileDescriptor,
    val input: FileInputStream,
    val output: FileOutputStream,
    val accessory: UsbAccessory,
) : AutoCloseable {

    @Volatile var isClosed: Boolean = false
        private set

    override fun close() {
        if (isClosed) return
        isClosed = true
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { pfd.close() }
    }

    companion object {
        fun open(usbManager: UsbManager, accessory: UsbAccessory): AoaTransport? {
            val pfd = usbManager.openAccessory(accessory) ?: return null
            val fd = pfd.fileDescriptor
            return AoaTransport(
                pfd = pfd,
                input = FileInputStream(fd),
                output = FileOutputStream(fd),
                accessory = accessory,
            )
        }
    }
}
