/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb

import me.jahnen.libaums.core.fs.UsbFile
import de.moleman1024.audiowagon.log.Logger
import android.media.MediaDataSource
import kotlinx.coroutines.CoroutineDispatcher
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*
import kotlin.math.min

private const val TAG = "USBAudCachedDataSrc"
private val logger = Logger

/**
 * Creates a [MediaDataSource] for playback. This class includes an in-memory cache to avoid audio glitches during
 * playback.
 *
 * Whenever a block of bytes from the underlying USB file should be read, we read two chunks from the filesystem
 * around that read position instead and store those in memory. Since the filesystem chunks are usually bigger than the
 * blocks requested by media player, the next bytes will already be in memory when the next read is requested by media
 * player. This makes the next read faster and thus avoids audio glitches that happen when you try to randomly
 * access positions in the USB file directly.
 * If a cached chunk is not accessed after a couple of reads, it is discarded to free the memory (the media player
 * reads files sequentially for most of the time, older cached chunks are not required usually).
 */
open class USBAudioCachedDataSource(
    usbFile: UsbFile?,
    chunkSize: Int,
    libaumsDispatcher: CoroutineDispatcher
) : USBAudioDataSource(usbFile, chunkSize, libaumsDispatcher) {
    protected val cacheMap: TreeMap<Long, AgingCache> = TreeMap<Long, AgingCache>()
    private val bufSize: Int = chunkSize

    inner class AgingCache {
        // do not use ByteBuffer.allocateDirect() here, it causes errors to appear randomly in MediaPlayer
        val buffer: ByteBuffer = ByteBuffer.wrap(ByteArray(bufSize))
        var age: Short = 0

        override fun toString(): String {
            return "AgingCache(buffer=$buffer, age=$age)"
        }
    }

    override fun close() {
        cacheMap.clear()
        super.close()
    }

    override fun readAt(position: Long, buffer: ByteArray?, offset: Int, size: Int): Int {
        if (hasError) {
            return -1
        }
        if (buffer == null || isClosed) {
            return 0
        }
        val fileLength = getSize()
        if (fileLength <= 0) {
            return -1
        }
        if (position >= fileLength) {
            return -1
        }
        if (!fitsInCache(size)) {
            return super.readAt(position, buffer, offset, size)
        }
        val numBytesToRead = min(size.toLong(), fileLength - position).toInt()
        val outBuffer = ByteBuffer.wrap(buffer)
        outBuffer.position(offset)
        increaseCacheAge()
        freeCache()
        var cacheStartPos = getCacheMapKeyFor(position, numBytesToRead)
        if (cacheStartPos <= -1) {
            try {
                cacheStartPos = createCachesAround(position, numBytesToRead)
            } catch (exc: IOException) {
                return -1
            }
        }
        if (cacheStartPos < 0) {
            return -1
        }
        return readFromCache(cacheStartPos, position, outBuffer, offset, numBytesToRead)
    }

    private fun fitsInCache(size: Int): Boolean {
        return size <= bufSize
    }

    protected open fun increaseCacheAge() {
        cacheMap.forEach { (_, agingCache) -> agingCache.age++ }
    }

    protected open fun freeCache() {
        cacheMap.values.removeIf { it.age > 2 }
    }

    private fun getCacheMapKeyFor(position: Long, numBytesToRead: Int): Long {
        val cacheStartLTEQPosition: Long = cacheMap.floorKey(position) ?: return -1
        val endOfCachePos = cacheStartLTEQPosition + cacheMap[cacheStartLTEQPosition]?.buffer?.limit()!!
        if (numBytesToRead > endOfCachePos - position) {
            // the block to read overlaps with two caches, possibly need to prepare second cache
            return -1
        }
        return cacheStartLTEQPosition
    }

    private fun createCachesAround(position: Long, numBytesToRead: Int): Long {
        var cacheKey = -1L
        val fileLength = this.size
        if (fileLength <= 0) {
            return cacheKey
        }
        val cacheBeforeStartPos: Long = position - (position % bufSize)
        val cacheBefore: AgingCache
        if (cacheBeforeStartPos < fileLength) {
            if (cacheBeforeStartPos !in cacheMap) {
                cacheBefore = AgingCache()
                read(cacheBeforeStartPos, cacheBefore.buffer)
                cacheBefore.buffer.flip()
                cacheMap[cacheBeforeStartPos] = cacheBefore
            } else {
                cacheBefore = cacheMap[cacheBeforeStartPos]!!
            }
            if (position >= cacheBeforeStartPos && numBytesToRead <= cacheBefore.buffer.limit()) {
                cacheKey = cacheBeforeStartPos
            }
        }
        val cacheAfterStartPos: Long = (position + bufSize) - (position % bufSize)
        val cacheAfter: AgingCache
        if (cacheAfterStartPos < fileLength) {
            if (cacheAfterStartPos !in cacheMap) {
                cacheAfter = AgingCache()
                read(cacheAfterStartPos, cacheAfter.buffer)
                cacheAfter.buffer.flip()
                cacheMap[cacheAfterStartPos] = cacheAfter
            } else {
                cacheAfter = cacheMap[cacheAfterStartPos]!!
            }
            if (position >= cacheAfterStartPos && numBytesToRead <= cacheAfter.buffer.limit()) {
                cacheKey = cacheAfterStartPos
            }
        }
        if (cacheKey < 0) {
            logger.error(TAG, "Cache starting positions not applicable")
        }
        return cacheKey
    }

    private fun readFromCache(
        cacheKey: Long,
        position: Long,
        outBuffer: ByteBuffer,
        offset: Int,
        numBytesToRead: Int
    ): Int {
        val agingCache = cacheMap[cacheKey] ?: throw RuntimeException("Missing cache at: $cacheKey")
        agingCache.age = 0
        val agingCacheOffset = (position - cacheKey).toInt()
        agingCache.buffer.position(agingCacheOffset)
        val numBytesAvailToRead: Int = min(numBytesToRead, agingCache.buffer.remaining())
        val outBufferPosBeforeRead = outBuffer.position()
        agingCache.buffer.get(outBuffer.array(), offset, numBytesAvailToRead)
        outBuffer.position(offset + numBytesAvailToRead)
        var numBytesRead = outBuffer.position() - outBufferPosBeforeRead
        agingCache.buffer.rewind()
        if (numBytesRead < numBytesToRead) {
            val numBytesRemain = numBytesToRead - numBytesRead
            val nextCachePos = cacheMap.higherKey(cacheKey) ?: throw RuntimeException("Missing next cache")
            val nextAgingCache = cacheMap[nextCachePos] ?: throw RuntimeException("Missing next cache at: " +
                    "$nextCachePos")
            nextAgingCache.age = 0
            nextAgingCache.buffer.get(outBuffer.array(), offset + numBytesRead, numBytesRemain)
            outBuffer.position(offset + numBytesRead + numBytesRemain)
            nextAgingCache.buffer.rewind()
            numBytesRead = outBuffer.position()
        }
        if (numBytesToRead != numBytesRead) {
            logger.error(TAG, "Not enough data: $numBytesToRead > $numBytesRead")
        }
        return numBytesRead
    }

}
