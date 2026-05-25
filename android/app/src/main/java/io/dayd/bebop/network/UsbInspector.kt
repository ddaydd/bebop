package io.dayd.bebop.network

import android.content.Context
import android.hardware.usb.UsbManager

data class UsbDeviceInfo(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int,
    val deviceSubclass: Int,
    val productName: String?,
    val manufacturerName: String?,
    val serialNumber: String?,
    val interfaceCount: Int,
)

data class UsbAccessoryInfo(
    val manufacturer: String,
    val model: String,
    val version: String?,
    val description: String?,
    val serial: String?,
    val uri: String?,
)

data class UsbReport(
    val devices: List<UsbDeviceInfo>,
    val accessories: List<UsbAccessoryInfo>,
)

object UsbInspector {

    /** Vendor IDs Parrot. */
    val PARROT_VENDOR_IDS = setOf(0x19cf)

    fun snapshot(context: Context): UsbReport {
        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return UsbReport(emptyList(), emptyList())

        val devices = um.deviceList.values.map { d ->
            UsbDeviceInfo(
                name = d.deviceName,
                vendorId = d.vendorId,
                productId = d.productId,
                deviceClass = d.deviceClass,
                deviceSubclass = d.deviceSubclass,
                productName = runCatching { d.productName }.getOrNull(),
                manufacturerName = runCatching { d.manufacturerName }.getOrNull(),
                serialNumber = runCatching { d.serialNumber }.getOrNull(),
                interfaceCount = d.interfaceCount,
            )
        }

        val accessories = (um.accessoryList ?: emptyArray()).map { a ->
            UsbAccessoryInfo(
                manufacturer = a.manufacturer ?: "?",
                model = a.model ?: "?",
                version = a.version,
                description = a.description,
                serial = runCatching { a.serial }.getOrNull(),
                uri = a.uri,
            )
        }

        return UsbReport(devices, accessories)
    }

    fun looksLikeParrot(device: UsbDeviceInfo): Boolean = device.vendorId in PARROT_VENDOR_IDS

    fun looksLikeParrot(accessory: UsbAccessoryInfo): Boolean =
        accessory.manufacturer.contains("Parrot", ignoreCase = true) ||
            accessory.model.contains("Skycontroller", ignoreCase = true)
}
