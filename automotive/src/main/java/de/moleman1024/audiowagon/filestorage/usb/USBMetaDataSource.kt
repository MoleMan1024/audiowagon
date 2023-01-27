/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb

import me.jahnen.libaums.core.fs.UsbFile
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.CoroutineDispatcher

private const val TAG = "USBMetaDataSrc"
private val logger = Logger

/**
 * Class to read data from given file on USB filesystem. It is used for extraction of metadata of audio files
 * (e.g. MP3 tags, album art). This class includes an in-memory cache to speed up reading.
 *  See [USBAudioCachedDataSource].
 *
 * When too many chunks are cached we clear the cache.
 */
private const val MAX_BYTES_TO_CACHE = 1200 * 1024

class USBMetaDataSource(
    private val usbFile: UsbFile?,
    private val chunkSize: Int,
    libaumsDispatcher: CoroutineDispatcher
) : USBAudioCachedDataSource(usbFile, chunkSize, libaumsDispatcher) {

    override fun increaseCacheAge() {
        // do nothing, we limit using size instead
    }

    override fun freeCache() {
        if (cacheMap.size * chunkSize > MAX_BYTES_TO_CACHE) {
            logger.verbose(TAG, "Too many chunks in cache (${cacheMap.size}), freeing: $usbFile")
            cacheMap.clear()
        }
    }

}
