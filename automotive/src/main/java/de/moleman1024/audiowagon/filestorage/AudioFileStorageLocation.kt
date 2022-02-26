/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage

import android.media.MediaDataSource
import android.net.Uri
import com.github.mjdev.libaums.fs.UsbFile
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import java.io.File
import java.io.InputStream
import java.net.URLConnection

interface AudioFileStorageLocation {
    @Suppress("PropertyName")
    val TAG: String
    val logger: Logger
    val device: MediaDevice
    val storageID: String
    var indexingStatus: IndexingStatus
    var isDetached: Boolean
    var isIndexingCancelled: Boolean

    @ExperimentalCoroutinesApi
    fun indexAudioFiles(directory: Directory, scope: CoroutineScope): ReceiveChannel<AudioFile>
    fun getDataSourceForURI(uri: Uri): MediaDataSource
    fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource
    fun getByteArrayForURI(uri: Uri): ByteArray
    fun getInputStreamForURI(uri: Uri): InputStream
    fun close()
    fun setDetached()

    /**
     * Returns all content (files/directories) in the given directory. Non-recursive
     */
    fun getDirectoryContents(directory: Directory): List<FileLike>

    /**
     * Returns all directories and audio files in the given directory. Non-recursive
     */
    fun getDirectoryContentsPlayable(directory: Directory): List<FileLike>

    /**
     * Returns the URI of the root directory of the storage location
     */
    fun getRootURI(): Uri

    /**
     * Cancels an ongoing indexing pipeline
     */
    fun cancelIndexAudioFiles() {
        if (indexingStatus == IndexingStatus.INDEXING) {
            logger.debug(TAG, "Cancelling ongoing audio file indexing")
            isIndexingCancelled = true
        }
    }

}
