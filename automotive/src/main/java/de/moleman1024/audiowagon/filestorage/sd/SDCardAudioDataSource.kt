/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.sd

import android.media.MediaDataSource
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min

/**
 * NOTE: SD card support is only enabled in debug builds used in the Android emulator
 */
class SDCardAudioDataSource(private var file: File) : MediaDataSource() {
    var isClosed: Boolean = false
    var randomAccessFile: RandomAccessFile = RandomAccessFile(file, "r")

    override fun close() {
        if (isClosed) {
            return
        }
        randomAccessFile.close()
        isClosed = true
    }

    /**
     * Retrieve data from file starting from [position] with length [size]. Put this data into [buffer] at position
     * [offset]. Returns 0 if no bytes were read. Returns -1 to indicate end of stream was reached.
     */
    override fun readAt(position: Long, buffer: ByteArray?, offset: Int, size: Int): Int {
        if (buffer == null) {
            return 0
        }
        val numBytesToRead = min(size.toLong(), file.length() - position).toInt()
        randomAccessFile.seek(position)
        randomAccessFile.read(buffer, offset, numBytesToRead)
        return numBytesToRead
    }

    override fun getSize(): Long {
        return file.length()
    }
}
