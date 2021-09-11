/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb

import android.media.MediaDataSource
import com.github.mjdev.libaums.fs.UsbFile
import de.moleman1024.audiowagon.log.Logger
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.min

private const val TAG = "USBAudDataSrc"
private val logger = Logger

/**
 * Class to read audio data from given file on USB filesystem. It is used for extraction of metadata of audio files
 * (e.g. MP3 tags, album art).
 * Do NOT use this class for playback, USB filesystem access is slow and will introduce audio glitches during playback.
 * Use [USBAudioCachedDataSource] instead for playback.
 */
open class USBAudioDataSource(
    private var usbFile: UsbFile?,
    private val chunkSize: Int,
) : MediaDataSource() {
    var isClosed = false

    init {
        logger.debug(
            TAG, "Init data source with chunk size $chunkSize for file with length ${usbFile?.length}: $usbFile"
        )
    }

    @Synchronized
    override fun close() {
        logger.debug(TAG, "close(usbFile=$usbFile)")
        if (isClosed) {
            return
        }
        try {
            // this can take up to 800ms when logging to USB at the same time
            usbFile?.close()
            isClosed = true
            logger.debug(TAG, "closed usbFile=$usbFile")
            usbFile = null
        } catch (exc: IOException) {
            // This will trigger a fatal SIGABRT and the app will be restarted. But since this happens only
            // when the USB drive is unplugged while being in use, this is the "safest" way to "recover" the app
            logger.exceptionLogcatOnly(TAG, "Error while closing USB file, did you unplug the device?", exc)
        }
    }

    /**
     * Retrieve data from USB file starting from [position] with length [size]. Put this data into [buffer] at position
     * [offset]. Returns 0 if no bytes were read. Returns -1 to indicate end of stream was reached
     * (TODO: is this last statement still true?).
     */
    override fun readAt(position: Long, buffer: ByteArray?, offset: Int, size: Int): Int {
        if (buffer == null || isClosed) {
            return 0
        }
        val fileLength = usbFile?.length ?: return 0
        val numBytesToRead = min(size.toLong(), fileLength - position).toInt()
        val outBuffer = ByteBuffer.wrap(buffer)
        outBuffer.position(offset)
        outBuffer.limit(numBytesToRead + offset)
        val tempBuffer = ByteBuffer.wrap(ByteArray(chunkSize))
        var numBytesToReadRemain = numBytesToRead
        var startPos = position - (position % chunkSize)
        var offsetIntoChunk = (position - startPos).toInt()
        var numBytesInChunk: Int
        var outBufPosBeforeRead: Int
        var numBytesToReadFromChunk: Int
        while (numBytesToReadRemain > 0) {
            numBytesInChunk = readFromUSBFileSystem(startPos, tempBuffer)
            numBytesToReadFromChunk = min(numBytesToReadRemain, numBytesInChunk)
            outBufPosBeforeRead = outBuffer.position()
            if (offsetIntoChunk > 0) {
                tempBuffer.position(offsetIntoChunk)
                numBytesToReadFromChunk = min(numBytesToReadRemain, tempBuffer.limit() - tempBuffer.position())
                offsetIntoChunk = 0
            }
            if (numBytesToReadFromChunk <= 0) {
                throw IOException("Could not read next chunk")
            }
            tempBuffer.get(outBuffer.array(), outBuffer.position(), numBytesToReadFromChunk)
            outBuffer.position(outBufPosBeforeRead + numBytesToReadFromChunk)
            numBytesToReadRemain -= numBytesToReadFromChunk
            startPos += chunkSize
            tempBuffer.clear()
        }
        return numBytesToRead
    }

    override fun getSize(): Long {
        return usbFile?.length ?: 0
    }

    private fun readFromUSBFileSystem(position: Long, outBuffer: ByteBuffer): Int {
        val outBufPosBeforeRead = outBuffer.position()
        usbFile?.read(position, outBuffer)
        val numBytesRead = outBuffer.position() - outBufPosBeforeRead
        outBuffer.flip()
        return numBytesRead
    }

}
