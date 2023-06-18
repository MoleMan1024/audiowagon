/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage

import de.moleman1024.audiowagon.filestorage.usb.lowlevel.USBFile
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.USBFileInputStream
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.USBFileOutputStream
import de.moleman1024.audiowagon.log.Logger
import java.nio.ByteBuffer

class MockUSBFile : USBFile {
    private lateinit var byteBuf: ByteBuffer
    override val absolutePath: String = "absolutePath"
    override val isDirectory: Boolean = false
    override val isRoot: Boolean = false
    override var length: Long = -1
    override var name: String = "MockUSBFile"
    override val parent: USBFile? = null
    override val lastModified: Long
        get() = -1L
    val bytes: ByteArray
        get() = byteBuf.array()

    fun setBytes(bytes: ByteArray) {
        byteBuf = ByteBuffer.wrap(bytes)
        length = byteBuf.array().size.toLong()
    }

    override fun close() {
        println("close()")
    }

    override fun createDirectory(name: String): USBFile {
        return MockUSBFile()
    }

    override fun createFile(name: String): USBFile {
        return MockUSBFile()
    }

    override fun flush() {
    }

    override fun listFiles(): Array<USBFile> {
        return arrayOf()
    }

    override fun read(offset: Long, outBuf: ByteBuffer) {
        Logger.debug("MockUSBFile",
            "read(offset=$offset, destination=$outBuf " +
                    "[content: destination=${outBuf.array().contentToString()}])")
        val destOffset = 0
        byteBuf.position(offset.toInt())
        byteBuf.get(outBuf.array(), destOffset, outBuf.limit() - outBuf.position())
        outBuf.position(outBuf.limit())
        byteBuf.rewind()
        Logger.debug("MockUSBFile", "destination=${outBuf.array().contentToString()})")
    }

    override fun search(path: String): USBFile? {
        return null
    }

    override fun write(offset: Long, inBuf: ByteBuffer) {
    }

    override fun getOutputStream(): USBFileOutputStream {
        TODO("Not yet implemented")
    }

    override fun getInputStream(): USBFileInputStream {
        TODO("Not yet implemented")
    }

}
