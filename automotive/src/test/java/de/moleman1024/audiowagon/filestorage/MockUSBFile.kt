/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage

import me.jahnen.libaums.core.fs.UsbFile
import de.moleman1024.audiowagon.log.Logger
import java.nio.ByteBuffer

class MockUSBFile : UsbFile {
    private lateinit var byteBuf: ByteBuffer
    override val absolutePath: String = "absolutePath"
    override val isDirectory: Boolean = false
    override val isRoot: Boolean = false
    override var length: Long = -1
    override var name: String = "MockUSBFile"
    override val parent: UsbFile? = null
    val bytes: ByteArray
        get() = byteBuf.array()

    fun setBytes(bytes: ByteArray) {
        byteBuf = ByteBuffer.wrap(bytes)
        length = byteBuf.array().size.toLong()
    }

    override fun close() {
        println("close()")
    }

    override fun createDirectory(name: String): UsbFile {
        return MockUSBFile()
    }

    override fun createFile(name: String): UsbFile {
        return MockUSBFile()
    }

    override fun createdAt(): Long {
        return -1L
    }

    override fun delete() {
    }

    override fun flush() {
    }

    override fun lastAccessed(): Long {
        return -1L
    }

    override fun lastModified(): Long {
        return -1L
    }

    override fun list(): Array<String> {
        return arrayOf()
    }

    override fun listFiles(): Array<UsbFile> {
        return arrayOf()
    }

    override fun moveTo(destination: UsbFile) {
    }

    override fun read(offset: Long, destination: ByteBuffer) {
        Logger.debug("MockUSBFile",
            "read(offset=$offset, destination=$destination " +
                    "[content: destination=${destination.array().contentToString()}])")
        val destOffset = 0
        byteBuf.position(offset.toInt())
        byteBuf.get(destination.array(), destOffset, destination.limit() - destination.position())
        destination.position(destination.limit())
        byteBuf.rewind()
        Logger.debug("MockUSBFile", "destination=${destination.array().contentToString()})")
    }

    override fun search(path: String): UsbFile? {
        return null
    }

    override fun write(offset: Long, source: ByteBuffer) {
    }

}
