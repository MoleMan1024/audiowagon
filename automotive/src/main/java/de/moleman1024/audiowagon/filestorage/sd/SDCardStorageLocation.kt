/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.sd

import android.media.MediaDataSource
import android.net.Uri
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.Util.Companion.determinePlayableFileType
import de.moleman1024.audiowagon.filestorage.AudioFileStorageLocation
import de.moleman1024.audiowagon.filestorage.Directory
import de.moleman1024.audiowagon.filestorage.FileLike
import de.moleman1024.audiowagon.filestorage.IndexingStatus
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.CoroutineScope
import java.io.InputStream


/**
 * NOTE: SD card support is only enabled in debug builds used in the Android emulator
 */
class SDCardStorageLocation(override val device: SDCardMediaDevice) : AudioFileStorageLocation {
    override val TAG = "SDCardStorLoc"
    override val logger = Logger
    override val storageID: String
        get() = device.getID()
    override var indexingStatus: IndexingStatus = IndexingStatus.NOT_INDEXED
    override var isDetached: Boolean = false
    override var isIndexingCancelled: Boolean = false

    override fun walkTopDown(startDirectory: Any, scope: CoroutineScope): Sequence<Any> {
        return device.walkTopDown(startDirectory)
    }

    override fun getDirectoryContents(directory: Directory): List<FileLike> {
        val itemsInDir = mutableListOf<FileLike>()
        device.getDirectoryContents(directory.uri).forEach { file ->
            if (file.isDirectory) {
                val uri = Util.createURIForPath(storageID, file.absolutePath)
                itemsInDir.add(Directory(uri))
            } else {
                val fileType = determinePlayableFileType(file)
                if (fileType != null) {
                    val audioFile = Util.createAudioFileFromFile(file, storageID)
                    itemsInDir.add(audioFile)
                }
            }
        }
        return itemsInDir
    }

    override fun getDirectoryContentsPlayable(directory: Directory): List<FileLike> {
        return getDirectoryContents(directory)
    }

    override fun getDataSourceForURI(uri: Uri): MediaDataSource {
        return device.getDataSourceForURI(uri)
    }

    override fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource {
        return device.getBufferedDataSourceForURI(uri)
    }

    override fun getByteArrayForURI(uri: Uri): ByteArray {
        TODO("Not yet implemented")
    }

    override fun getInputStreamForURI(uri: Uri): InputStream {
        TODO("Not yet implemented")
    }

    override fun close() {
        device.close()
    }

    override fun setDetached() {
        device.isClosed = true
        isDetached = true
    }

    override fun getRootURI(): Uri {
        return Util.createURIForPath(storageID, device.getRoot().absolutePath)
    }

}
