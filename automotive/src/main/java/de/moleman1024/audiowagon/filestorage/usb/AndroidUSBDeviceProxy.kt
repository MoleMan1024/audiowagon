/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb

import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import de.moleman1024.audiowagon.USB_DEVICE_SERIALNUM_MAX_NUM_CHARS
import de.moleman1024.audiowagon.exceptions.NoPartitionsException
import de.moleman1024.audiowagon.filestorage.FileSystem
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.*
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.filesystem.UsbFile
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.filesystem.UsbFileInputStream
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.filesystem.UsbFileOutputStream
import de.moleman1024.audiowagon.log.Logger
import java.io.IOException
import java.nio.ByteBuffer

private const val TAG = "AndroidUSBDeviceProxy"
private val logger = Logger

// subclass 6 means that the usb mass storage device implements the SCSI transparent command set
private const val INTERFACE_SUBCLASS_SCSI = 6

// protocol 80 means the communication happens only via bulk transfers
private const val INTERFACE_PROTOCOL_BULK_TRANSFER = 80

@Suppress("SuspiciousVarProperty")
class AndroidUSBDeviceProxy(
    private val androidUSBDevice: UsbDevice, private val usbManager: UsbManager
) : USBDevice {
    private var massStorageDevice: USBMassStorageDevice? = null

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
                return this@AndroidUSBDeviceProxy.getInterface(index)
            }
        }
    }

    override fun getInterface(interfaceIndex: Int): USBInterface {
        return object : USBInterface {
            override val interfaceIndex: Int
                get() = interfaceIndex
            override val id: Int
                get() = androidUSBDevice.getInterface(interfaceIndex).id
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
                    override val index: Int
                        get() = endpointIndex
                    override val address: Int
                        get() = androidUSBDevice.getInterface(interfaceIndex).getEndpoint(endpointIndex).address
                    override val type: Int
                        get() = androidUSBDevice.getInterface(interfaceIndex).getEndpoint(endpointIndex).type
                    override val direction: Int
                        get() = androidUSBDevice.getInterface(interfaceIndex).getEndpoint(endpointIndex).direction

                    override fun toString(): String {
                        return "USBEndpoint(index=$index, address=$address, type=$type, " +
                                "direction=$direction, hashCode=${hashCode()})"
                    }
                }
            }
        }
    }

    override fun isCompatible(): Boolean {
        return getMassStorageDevices().isNotEmpty()
    }

    private fun getMassStorageDevices(): List<USBMassStorageDevice> {
        return (0 until this.interfaceCount).map { interfaceIndex ->
            this.getInterface(interfaceIndex)
        }.filter {
            // libaums currently only supports SCSI transparent command set with bulk transfers
            it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE
                    && it.interfaceSubclass == INTERFACE_SUBCLASS_SCSI
                    && it.interfaceProtocol == INTERFACE_PROTOCOL_BULK_TRANSFER
        }.map { usbInterface ->
            // Every mass storage device should have exactly two endpoints: One IN and one OUT endpoint
            // Some people connect strange devices e.g. Transcend MP3 players that have more endpoints but also
            // support USB mass storage
            val endpointCount = usbInterface.endpointCount
            if (endpointCount != 2) {
                logger.warning(TAG, "Interface endpoint count: $endpointCount != 2 for $usbInterface")
            }
            var outEndpoint: USBEndpoint? = null
            var inEndpoint: USBEndpoint? = null
            for (j in 0 until endpointCount) {
                val endpoint = usbInterface.getEndpoint(j)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                        outEndpoint = endpoint
                    } else {
                        inEndpoint = endpoint
                    }
                }
            }
            if (outEndpoint == null || inEndpoint == null) {
                logger.error(TAG, "Not all needed endpoints found")
                return@map null
            }
            return@map AndroidUSBMassStorageDevice(usbManager, androidUSBDevice, usbInterface, inEndpoint, outEndpoint)
        }.filterNotNull()
    }

    // locked externally by usbLibDispatcher
    override fun initFilesystem(context: Context): FileSystem? {
        logger.debug(TAG, "Initializing filesystem for: $this")
        if (massStorageDevice == null) {
            try {
                val massStorageDevices = getMassStorageDevices()
                logger.debug(TAG, "Found mass storage devices: $massStorageDevices")
                massStorageDevice = massStorageDevices.first()
                logger.debug(TAG, "Created mass storage device for: $this")
            } catch (_: NoSuchElementException) {
                throw RuntimeException("No mass storage device: $androidUSBDevice")
            }
            try {
                massStorageDevice?.init()
            } catch (exc: IOException) {
                logger.exception(TAG, "initFilesystem()", exc)
                if (exc.message?.contains(Regex("MAX_.*_ATTEMPTS|Could not claim|deviceConnection")) == true) {
                    massStorageDevice?.reset()
                    massStorageDevice?.close()
                    massStorageDevice = null
                    throw IOException("Could not initialize filesystem")
                }
            }
        } else {
            logger.warning(TAG, "massStorageDevice already initialized: $massStorageDevice")
        }
        try {
            logger.debug(TAG, "Found partitions: ${massStorageDevice?.partitions}")
        } catch (exc: UninitializedPropertyAccessException) {
            logger.exception(TAG, "Partitions not yet initialized but massStorageDevice exists", exc)
            try {
                massStorageDevice?.init()
            } catch (exc: IOException) {
                logger.exception(TAG, "initFilesystem()", exc)
                if (exc.message?.contains(Regex("MAX_.*_ATTEMPTS|Could not claim interface")) == true) {
                    throw IOException()
                }
            }
        }
        if (massStorageDevice?.partitions?.isEmpty() == true) {
            throw NoPartitionsException()
        }
        if (massStorageDevice?.partitions?.get(0)?.fileSystem == null) {
            return null
        }
        return object : FileSystem {
            val fileSystem = massStorageDevice?.partitions?.get(0)?.fileSystem

            override val chunkSize: Int
                get() = fileSystem?.chunkSize!!
            override val freeSpace: Long
                get() = fileSystem?.freeSpace!!
            override val rootDirectory: USBFile
                get() = fileSystem?.rootDirectory?.let { wrapUSBFile(it) }!!
            override val volumeLabel: String
                get() = fileSystem?.volumeLabel!!
        }
    }

    // locked externally by usbLibDispatcher
    override fun close() {
        logger.debug(TAG, "Closing mass storage device: $massStorageDevice")
        massStorageDevice?.close()
        massStorageDevice = null
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AndroidUSBDeviceProxy
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
        result = 31 * result + massStorageDevice.hashCode()
        return result
    }

    override fun toString(): String {
        return "USBDeviceProxy(deviceName='$deviceName', productId=$productId, vendorId=$vendorId, " +
               "serialNumber=${serialNumber?.take(USB_DEVICE_SERIALNUM_MAX_NUM_CHARS)}, " +
               "massStorageDevice.hashCode=${massStorageDevice.hashCode()}, " +
               "androidUSBDevice.hashCode=${androidUSBDevice.hashCode()})"
    }

    companion object {
        fun wrapUSBFile(libaumsUSBFile: UsbFile): USBFile {
            return object : USBFile {
                override val absolutePath: String
                    get() = libaumsUSBFile.absolutePath
                override val isDirectory: Boolean
                    get() = libaumsUSBFile.isDirectory
                override val isRoot: Boolean
                    get() = libaumsUSBFile.isRoot
                override var length: Long
                    get() = libaumsUSBFile.length
                    set(value) {
                        libaumsUSBFile.length = value
                    }
                override var name: String
                    get() = libaumsUSBFile.name
                    set(value) {
                        libaumsUSBFile.name = value
                    }
                override val parent: USBFile?
                    get() {
                        return if (libaumsUSBFile.parent == null) {
                            null
                        } else {
                            wrapUSBFile(libaumsUSBFile.parent!!)
                        }
                    }
                override val lastModified: Long
                    get() = libaumsUSBFile.lastModified()

                override fun close() {
                    libaumsUSBFile.close()
                }

                override fun createDirectory(name: String): USBFile {
                    return wrapUSBFile(libaumsUSBFile.createDirectory(name))
                }

                override fun createFile(name: String): USBFile {
                    return wrapUSBFile(libaumsUSBFile.createFile(name))
                }

                override fun flush() {
                    libaumsUSBFile.flush()
                }

                override fun listFiles(): Array<USBFile> {
                    return libaumsUSBFile.listFiles().map {
                        wrapUSBFile(it)
                    }.toTypedArray()
                }

                override fun read(offset: Long, outBuf: ByteBuffer) {
                    libaumsUSBFile.read(offset, outBuf)
                }

                override fun search(path: String): USBFile? {
                    return libaumsUSBFile.search(path)?.let { wrapUSBFile(it) }
                }

                override fun write(offset: Long, inBuf: ByteBuffer) {
                    return libaumsUSBFile.write(offset, inBuf)
                }

                override fun getOutputStream(): USBFileOutputStream {
                    val stream = UsbFileOutputStream(libaumsUSBFile)

                    return object : USBFileOutputStream() {
                        override fun close() {
                            stream.close()
                        }

                        override fun flush() {
                            stream.flush()
                        }

                        override fun write(data: Int) {
                            stream.write(data)
                        }

                        override fun write(buffer: ByteArray?) {
                            buffer?.let { stream.write(it) }
                        }

                        override fun write(buffer: ByteArray?, offset: Int, length: Int) {
                            buffer?.let { stream.write(it, offset, length) }
                        }
                    }
                }

                override fun getInputStream(): USBFileInputStream {
                    val stream = UsbFileInputStream(libaumsUSBFile)

                    return object : USBFileInputStream() {
                        override fun read(): Int {
                            return stream.read()
                        }

                        override fun read(buffer: ByteArray?): Int {
                            return buffer?.let { stream.read(it) }!!
                        }

                        override fun read(buffer: ByteArray?, offset: Int, length: Int): Int {
                            return buffer?.let { stream.read(it, offset, length) }!!
                        }

                        override fun skip(numBytes: Long): Long {
                            return stream.skip(numBytes)
                        }

                        override fun available(): Int {
                            return stream.available()
                        }

                        override fun close() {
                            stream.close()
                        }
                    }
                }

                override fun toString(): String {
                    return "wrappedUSBFile{$libaumsUSBFile})"
                }
            }
        }
    }

}
