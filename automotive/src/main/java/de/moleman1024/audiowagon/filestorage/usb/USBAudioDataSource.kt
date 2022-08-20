/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb

import android.media.MediaDataSource
import me.jahnen.libaums.core.fs.UsbFile
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.min

private const val TAG = "USBAudDataSrc"
private val logger = Logger

/**
 * Class to read data from given file on USB filesystem. It is used for extraction of metadata of audio files
 * (e.g. MP3 tags, album art).
 * Do NOT use this class for playback, USB filesystem access is slow and will introduce audio glitches during playback.
 * Use [USBAudioCachedDataSource] instead for playback.
 */
open class USBAudioDataSource(
    private var usbFile: UsbFile?,
    private val chunkSize: Int,
    private val libaumsDispatcher: CoroutineDispatcher
) : MediaDataSource() {
    var isClosed = false
    var hasError = false

    init {
        runBlocking(libaumsDispatcher) {
            logger.verbose(
                TAG, "Init data source with chunk size $chunkSize for file with length ${usbFile?.length}: $usbFile"
            )
        }
    }

    override fun close() {
        if (isClosed || hasError) {
            usbFile = null
            return
        }
        runBlocking(libaumsDispatcher) {
            try {
                usbFile?.close()
                isClosed = true
                usbFile = null
            } catch (exc: IOException) {
                // If we rethrow this, it will trigger a fatal SIGABRT and the app will be restarted.
                logger.exceptionLogcatOnly(TAG, "Error while closing USB file, did you unplug the device?", exc)
                hasError = true
            }
        }
    }

    /**
     * Retrieve data from USB file starting from [position] with length [size]. Put this data into [buffer] at position
     * [offset]. Returns 0 if no bytes were read. Returns -1 to indicate end of stream was reached
     */
    override fun readAt(position: Long, buffer: ByteArray?, offset: Int, size: Int): Int {
        if (hasError) {
            return -1
        }
        if (buffer == null || isClosed) {
            return 0
        }
        val fileLength = getSize()
        if (fileLength <= 0) {
            return 0
        }
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
            try {
                numBytesInChunk = readFromUSBFileSystem(startPos, tempBuffer)
            } catch (exc: IOException) {
                return -1
            }
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
        return runBlocking(libaumsDispatcher) {
            return@runBlocking usbFile?.length ?: 0
        }
    }

    private fun readFromUSBFileSystem(position: Long, outBuffer: ByteBuffer): Int {
        val outBufPosBeforeRead = outBuffer.position()
        read(position, outBuffer)
        val numBytesRead = outBuffer.position() - outBufPosBeforeRead
        outBuffer.flip()
        return numBytesRead
    }

    fun read(position: Long, outBuffer: ByteBuffer) {
        try {
            runBlocking(libaumsDispatcher) {
                usbFile?.read(position, outBuffer)
            }
        } catch (exc: IOException) {
            logger.exception(TAG, exc.message.toString(), exc)
            hasError = true
            throw exc
        }
    }

}
