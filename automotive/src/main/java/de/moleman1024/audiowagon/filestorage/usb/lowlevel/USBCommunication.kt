/*
SPDX-FileCopyrightText: 2023 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb.lowlevel

import java.io.Closeable
import java.nio.ByteBuffer

interface USBCommunication : Closeable {
    companion object {
        const val TRANSFER_TIMEOUT_MS = 5000
    }
    val inEndpoint: USBEndpoint
    val outEndpoint: USBEndpoint
    val usbInterface: USBInterface

    fun initConnection()
    fun bulkOutTransfer(src: ByteBuffer): Int
    fun bulkInTransfer(dest: ByteBuffer): Int
    fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, length: Int): Int
    fun reset()
    fun clearHalt(endpoint: USBEndpoint)
    fun isClosed(): Boolean
}
