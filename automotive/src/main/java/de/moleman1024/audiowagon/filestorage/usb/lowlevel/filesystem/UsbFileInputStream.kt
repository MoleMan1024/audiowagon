/*
 * (C) Copyright 2016 mjahnen <github@mgns.tech>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package de.moleman1024.audiowagon.filestorage.usb.lowlevel.filesystem

import de.moleman1024.audiowagon.filestorage.usb.lowlevel.Util
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * UsbFileInputStream provides common InputStream access to a UsbFile.
 */
class UsbFileInputStream(private val file: UsbFile) : InputStream() {
    private var currentByteOffset: Long = 0

    init {
        if (file.isDirectory) {
            throw UnsupportedOperationException("UsbFileInputStream cannot be created on directory!")
        }
    }

    override fun available(): Int {
        // return 0, because we always block
        return 0
    }

    override fun read(): Int {
        if (currentByteOffset >= file.length) {
            return -1
        }
        val buffer = Util.allocateByteBuffer(512)
        buffer.limit(1)
        file.read(currentByteOffset, buffer)
        currentByteOffset++
        buffer.flip()
        return buffer.get().toInt()
    }

    override fun close() {
        file.close()
    }

    override fun read(buffer: ByteArray): Int {
        if (currentByteOffset >= file.length) {
            return -1
        }
        val length = file.length
        val toRead = Math.min(buffer.size.toLong(), length - currentByteOffset)
        val byteBuffer = ByteBuffer.wrap(buffer)
        byteBuffer.limit(toRead.toInt())
        file.read(currentByteOffset, byteBuffer)
        currentByteOffset += toRead
        return toRead.toInt()
    }

    override fun read(buffer: ByteArray, byteOffset: Int, byteCount: Int): Int {
        if (currentByteOffset >= file.length) {
            return -1
        }
        val length = file.length
        val toRead = byteCount.toLong().coerceAtMost(length - currentByteOffset)
        val byteBuffer = ByteBuffer.wrap(buffer)
        byteBuffer.position(byteOffset)
        byteBuffer.limit(toRead.toInt() + byteOffset)
        file.read(currentByteOffset, byteBuffer)
        currentByteOffset += toRead
        return toRead.toInt()
    }

    override fun skip(byteCount: Long): Long {
        val skippedBytes = byteCount.coerceAtMost(file.length - currentByteOffset)
        currentByteOffset += skippedBytes
        return skippedBytes
    }

}
