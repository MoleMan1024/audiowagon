/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.asset

import android.content.res.AssetFileDescriptor
import android.media.MediaDataSource
import de.moleman1024.audiowagon.log.Logger
import java.io.InputStream
import kotlin.math.min


/**
 * NOTE: assets bundled with the app are only used to pass Google's automatic reviews which require some demo data
 */
class AssetAudioDataSource(private val assetFD: AssetFileDescriptor) : MediaDataSource() {
    private val inputStream: InputStream = assetFD.createInputStream()
    // not memory efficient, but I don't care
    private val data: ByteArray = inputStream.readBytes()
    var isClosed: Boolean = false

    override fun close() {
        if (isClosed) {
            return
        }
        inputStream.close()
        assetFD.close()
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
        val numBytesToRead = min(size.toLong(), getSize() - position).toInt()
        data.copyInto(buffer, offset, position.toInt(), (position + numBytesToRead).toInt())
        return numBytesToRead
    }

    override fun getSize(): Long {
        return assetFD.length
    }
}
