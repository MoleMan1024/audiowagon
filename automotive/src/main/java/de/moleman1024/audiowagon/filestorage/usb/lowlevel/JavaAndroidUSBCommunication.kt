/*
SPDX-FileCopyrightText: 2023 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb.lowlevel

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.USBCommunication.Companion.TRANSFER_TIMEOUT_MS
import de.moleman1024.audiowagon.log.Logger
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock

private const val TAG = "JavaAndroidUSBComm"
private val logger = Logger

/**
 * Uses android.hardware.usb Java interface to access USB devices
 */
class JavaAndroidUSBCommunication(
    override val usbManager: UsbManager,
    override val usbDevice: UsbDevice,
    override val usbInterface: USBInterface,
    override val inEndpoint: USBEndpoint,
    override val outEndpoint: USBEndpoint
) :
    AndroidUSBCommunication {
    private var deviceConnection: UsbDeviceConnection? = null
    private val androidInterface: UsbInterface = usbDevice.getInterface(usbInterface.interfaceIndex)
    private val androidInEndpoint: UsbEndpoint = androidInterface.getEndpoint(inEndpoint.index)
    private val androidOutEndpoint: UsbEndpoint = androidInterface.getEndpoint(outEndpoint.index)
    private val lock = ReentrantLock(true)
    private var isClosed = false
    private var isNativeInitialized = false

    init {
        lock.lock()
        try {
            initNativeLibrary()
        } finally {
            lock.unlock()
        }
    }

    override fun initConnection() {
        lock.lock()
        try {
            if (isClosed) {
                logger.debug(TAG, "Cannot init connection when already closed")
                return
            }
            logger.debug(TAG, "initConnection()")
            deviceConnection = usbManager.openDevice(usbDevice) ?: throw IOException("deviceConnection is null")
            // we must use force=true
            val claim = deviceConnection!!.claimInterface(androidInterface, true)
            if (!claim) {
                throw IOException("Could not claim interface")
            }
        } finally {
            lock.unlock()
        }
    }

    private fun initNativeLibrary() {
        try {
            System.loadLibrary("usb-reset-lib")
            isNativeInitialized = true
        } catch (exc: UnsatisfiedLinkError) {
            isNativeInitialized = false
            logger.exception(TAG, "could not load native library", exc)
        }
    }

    override fun bulkOutTransfer(src: ByteBuffer): Int {
        try {
            val result = deviceConnection!!.bulkTransfer(
                androidOutEndpoint,
                src.array(),
                src.position(),
                src.remaining(),
                TRANSFER_TIMEOUT_MS
            )
            if (result == -1) {
                throw IOException("Could not write to device")
            }
            src.position(src.position() + result)
            return result
        } catch (exc: IOException) {
            if (exc.message?.contains("MAX_RECOVERY_ATTEMPTS") == true) {
                reset()
            }
            throw exc
        }
    }

    override fun bulkInTransfer(dest: ByteBuffer): Int {
        try {
            val result = deviceConnection!!.bulkTransfer(
                androidInEndpoint,
                dest.array(),
                dest.position(),
                dest.remaining(),
                TRANSFER_TIMEOUT_MS
            )
            if (result == -1) {
                throw IOException("Could not read from device")
            }
            dest.position(dest.position() + result)
            return result
        } catch (exc: IOException) {
            if (exc.message?.contains("MAX_RECOVERY_ATTEMPTS") == true) {
                reset()
            }
            throw exc
        }
    }

    override fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray,
        length: Int
    ): Int {
        return deviceConnection!!.controlTransfer(
            requestType,
            request,
            value,
            index,
            buffer,
            length,
            TRANSFER_TIMEOUT_MS
        )
    }

    override fun reset() {
        logger.debug(TAG, "reset()")
        val usbInterface = usbDevice.getInterface(usbInterface.interfaceIndex)
        if (deviceConnection == null) {
            return
        }
        if (!deviceConnection!!.releaseInterface(usbInterface)) {
            logger.warning(TAG, "Failed to release interface")
        }
        if (!resetUSBNative(deviceConnection!!.fileDescriptor)) {
            logger.warning(TAG, "Failed ioctl USBDEVFS_RESET")
        }
        if (!deviceConnection!!.claimInterface(usbInterface, true)) {
            throw IOException("Could not re-claim interface")
        }
    }

    override fun clearHalt(endpoint: USBEndpoint) {
        if (deviceConnection == null) {
            return
        }
        logger.debug(TAG, "Clearing halt on endpoint: $endpoint")
        val isClearHaltSuccess = clearHaltNative(deviceConnection!!.fileDescriptor, endpoint.address)
        if (!isClearHaltSuccess) {
            logger.warning(TAG, "Failed to clear halt on endpoint: $endpoint")
        }
    }

    private external fun resetUSBNative(fd: Int): Boolean
    private external fun clearHaltNative(fd: Int, endpoint: Int): Boolean

    override fun isClosed(): Boolean {
        lock.lock()
        try {
            return isClosed
        } finally {
            lock.unlock()
        }
    }

    override fun close() {
        lock.lock()
        try {
            logger.debug(TAG, "close()")
            closeUsbConnection()
            isClosed = true
        } finally {
            lock.unlock()
        }
    }

    private fun closeUsbConnection() {
        if (deviceConnection == null) {
            return
        }
        val release = deviceConnection!!.releaseInterface(androidInterface)
        if (!release) {
            logger.error(TAG, "Could not release interface")
        }
        deviceConnection!!.close()
    }

}
