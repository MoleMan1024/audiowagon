/*
SPDX-FileCopyrightText: 2023 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb.lowlevel

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.USBCommunication.Companion.TRANSFER_TIMEOUT_MS
import de.moleman1024.audiowagon.log.Logger
/*
import org.usb4java.*
*/
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.IntBuffer


private const val TAG = "LibusbAndroidUSBComm"
private val logger = Logger

/**
 * Uses native library libusb via JNI calls compiled for Android
 *
 * CURRENTLY NOT USED
 */
/*
class LibusbAndroidUSBCommunication(
    override val usbManager: UsbManager,
    override val usbDevice: UsbDevice,
    override val usbInterface: USBInterface,
    override val inEndpoint: USBEndpoint,
    override val outEndpoint: USBEndpoint
) : AndroidUSBCommunication {
    private val libusbContext = null
    private var deviceHandle: DeviceHandle? = null
    private val libusbInEndpoint: Byte = inEndpoint.address.toByte()
    private val libusbOutEndpoint: Byte = outEndpoint.address.toByte()
    private var usbDeviceConnection: UsbDeviceConnection? = null

    override fun initConnection() {
        // we need to turn off automatic device discovery because Android is not rooted
        LibUsb.setOption(this.libusbContext, LibUsb.OPTION_NO_DEVICE_DISCOVERY)
        val initResult = LibUsb.init(this.libusbContext)
        if (initResult != LibUsb.SUCCESS) {
            throw IOException("Unable to initialize libusb: ${LibUsb.errorName(initResult)}")
        }
        usbDeviceConnection = usbManager.openDevice(usbDevice)
        val handle = DeviceHandle()
        val wrapResult = usbDeviceConnection?.fileDescriptor?.toLong()?.let {
            LibUsb.wrapSysDevice(this.libusbContext, it, handle)
        } ?: throw IOException("No system file descriptor")
        if (wrapResult != LibUsb.SUCCESS) {
            throw IOException("Unable to wrap system device: ${LibUsb.errorName(wrapResult)}")
        }
        deviceHandle = handle
        // TODO: hopefully interface indices are the same as in android usb device?
        val detachKernelDriverResult = LibUsb.detachKernelDriver(deviceHandle, usbInterface.interfaceIndex)
        if (detachKernelDriverResult != LibUsb.SUCCESS) {
            throw IOException("Could not detach kernel driver: ${LibUsb.errorName(detachKernelDriverResult)}")
        }
        val claimResult = LibUsb.claimInterface(deviceHandle, usbInterface.interfaceIndex)
        if (claimResult != LibUsb.SUCCESS) {
            throw IOException("Unable to claim interface: ${LibUsb.errorName(claimResult)}")
        }
    }

    override fun bulkOutTransfer(src: ByteBuffer): Int {
        if (deviceHandle == null) {
            logger.error(TAG, "No device handle")
            return -1
        }
        val transferred: IntBuffer = BufferUtils.allocateIntBuffer()
        val result =
            LibUsb.bulkTransfer(deviceHandle, libusbOutEndpoint, src, transferred, TRANSFER_TIMEOUT_MS.toLong())
        if (result != LibUsb.SUCCESS) {
            throw IOException("Error in bulkOutTransfer: ${LibUsb.errorName(result)}")
        }
        val numBytesWritten = transferred.get()
        src.position(src.position() + numBytesWritten)
        return numBytesWritten
    }

    override fun bulkInTransfer(dest: ByteBuffer): Int {
        if (deviceHandle == null) {
            logger.error(TAG, "No device handle")
            return -1
        }
        val transferred: IntBuffer = BufferUtils.allocateIntBuffer()
        val result =
            LibUsb.bulkTransfer(deviceHandle, libusbInEndpoint, dest, transferred, TRANSFER_TIMEOUT_MS.toLong())
        if (result != LibUsb.SUCCESS) {
            throw IOException("Error in bulkInTransfer: ${LibUsb.errorName(result)}")
        }
        val numBytesRead = transferred.get()
        dest.position(dest.position() + numBytesRead)
        return numBytesRead
    }

    override fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray,
        length: Int
    ): Int {
        if (deviceHandle == null) {
            logger.error(TAG, "No device handle")
            return -1
        }
        val data = BufferUtils.allocateByteBuffer(buffer.size)
        data.put(buffer)
        val result = LibUsb.controlTransfer(
            deviceHandle,
            requestType.toByte(),
            request.toByte(),
            value.toShort(),
            index.toShort(),
            data,
            TRANSFER_TIMEOUT_MS.toLong()
        )
        if (result < 0) {
            throw IOException("Error in bulkInTransfer: ${LibUsb.errorName(result)}")
        }
        return result
    }

    override fun reset() {
        if (deviceHandle == null) {
            logger.error(TAG, "No device handle")
            return
        }
        LibUsb.resetDevice(deviceHandle)
    }

    override fun clearHalt(endpoint: USBEndpoint) {
        if (deviceHandle == null) {
            logger.error(TAG, "No device handle")
            return
        }
        val clearInHalt = LibUsb.clearHalt(deviceHandle, libusbInEndpoint)
        if (clearInHalt != LibUsb.SUCCESS) {
            logger.error(TAG, "Could not clear halt on IN endpoint: ${LibUsb.errorName(clearInHalt)}")
        }
        val clearOutHalt = LibUsb.clearHalt(deviceHandle, libusbOutEndpoint)
        if (clearOutHalt != LibUsb.SUCCESS) {
            logger.error(TAG, "Could not clear halt on OUT endpoint: ${LibUsb.errorName(clearOutHalt)}")
        }
    }

    override fun close() {
        if (deviceHandle != null) {
            val releaseResult = LibUsb.releaseInterface(deviceHandle, usbInterface.interfaceIndex)
            if (releaseResult != LibUsb.SUCCESS) {
                logger.error(TAG, "Unable to release interface: ${LibUsb.errorName(releaseResult)}")
            }
            LibUsb.close(deviceHandle)
        }
        usbDeviceConnection?.close()
        LibUsb.exit(this.libusbContext)
    }
}
*/
