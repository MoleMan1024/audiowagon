/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb

import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.UsbMassStorageDevice.Companion.getMassStorageDevices
import me.jahnen.libaums.core.fs.FileSystem
import de.moleman1024.audiowagon.exceptions.NoPartitionsException
import de.moleman1024.audiowagon.log.Logger

private const val TAG = "USBDeviceProxy"
private val logger = Logger

@Suppress("SuspiciousVarProperty")
class USBDeviceProxy(
    private val androidUSBDevice: UsbDevice,
    private val usbManager: UsbManager
) : USBDevice {
    private var massStorageDevice: UsbMassStorageDevice? = null

    override var configurationCount: Int = 0
        get() = androidUSBDevice.configurationCount
    override var deviceClass: Int = 0
        get() = androidUSBDevice.deviceClass
    override var deviceName: String = ""
        get() = androidUSBDevice.deviceName
    override var interfaceCount: Int = 0
        get() = androidUSBDevice.interfaceCount
    override var manufacturerName: String? = null
        get() = androidUSBDevice.manufacturerName
    override var productId: Int = 0
        get() = androidUSBDevice.productId
    override var productName: String? = null
        get() = androidUSBDevice.productName
    override var vendorId: Int = 0
        get() = androidUSBDevice.vendorId
    override var serialNumber: String? = null
        get() {
            var serialNum = ""
            if (hasPermission()) {
                if (androidUSBDevice.serialNumber != null) {
                    serialNum = androidUSBDevice.serialNumber!!
                }
            }
            return serialNum
        }

    override fun requestPermission(intent: PendingIntent) {
        usbManager.requestPermission(androidUSBDevice, intent)
    }

    override fun hasPermission(): Boolean {
        return usbManager.hasPermission(androidUSBDevice)
    }

    override fun getConfiguration(configIndex: Int): USBDeviceConfig {
        return object : USBDeviceConfig {
            override val interfaceCount: Int
                get() = androidUSBDevice.getConfiguration(configIndex).interfaceCount

            override fun getInterface(index: Int): USBInterface {
                return this@USBDeviceProxy.getInterface(index)
            }
        }
    }

    override fun getInterface(interfaceIndex: Int): USBInterface {
        return object : USBInterface {
            override val interfaceClass: Int
                get() = androidUSBDevice.getInterface(interfaceIndex).interfaceClass
            override val interfaceSubclass: Int
                get() = androidUSBDevice.getInterface(interfaceIndex).interfaceSubclass
            override val interfaceProtocol: Int
                get() = androidUSBDevice.getInterface(interfaceIndex).interfaceProtocol
            override val endpointCount: Int
                get() = androidUSBDevice.getInterface(interfaceIndex).endpointCount

            override fun getEndpoint(endpointIndex: Int): USBEndpoint {
                return object : USBEndpoint {
                    override val type: Int
                        get() = androidUSBDevice.getInterface(interfaceIndex).getEndpoint(endpointIndex).type
                    override val direction: Int
                        get() = androidUSBDevice.getInterface(interfaceIndex).getEndpoint(endpointIndex).direction
                }
            }
        }
    }

    // locked externally by usbMutex
    override fun initFilesystem(context: Context): FileSystem? {
        logger.debug(TAG, "Initializing filesystem")
        if (massStorageDevice == null) {
            try {
                val massStorageDevices = androidUSBDevice.getMassStorageDevices(context)
                logger.debug(TAG, "Found mass storage devices: $massStorageDevices")
                massStorageDevice = massStorageDevices.first()
                logger.verbose(TAG, "Created mass storage device: $massStorageDevice")
            } catch (exc: NoSuchElementException) {
                throw RuntimeException("No mass storage device: $androidUSBDevice")
            }
            massStorageDevice?.init()
        } else {
            logger.warning(TAG, "massStorageDevice already initialized: $massStorageDevice")
        }
        try {
            logger.debug(TAG, "Found partitions: ${massStorageDevice?.partitions}")
        } catch (exc: UninitializedPropertyAccessException) {
            logger.exception(TAG, "Partitions not yet initialized but massStorageDevice exists", exc)
            massStorageDevice?.init()
        }
        if (massStorageDevice?.partitions?.isEmpty() == true) {
            throw NoPartitionsException()
        }
        return massStorageDevice?.partitions?.get(0)?.fileSystem
    }

    // locked externally by usbMutex
    override fun close() {
        logger.debug(TAG, "Closing mass storage device: $massStorageDevice")
        massStorageDevice?.close()
        massStorageDevice = null
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as USBDeviceProxy
        if (deviceClass != other.deviceClass) return false
        if (deviceName != other.deviceName) return false
        if (productId != other.productId) return false
        if (vendorId != other.vendorId) return false
        return true
    }

    override fun hashCode(): Int {
        var result = deviceClass
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + productId
        result = 31 * result + vendorId
        return result
    }
}
