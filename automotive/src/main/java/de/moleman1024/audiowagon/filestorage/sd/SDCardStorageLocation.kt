/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.sd

import android.media.MediaDataSource
import android.net.Uri
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.Util.Companion.determinePlayableFileType
import de.moleman1024.audiowagon.filestorage.*
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import java.io.File
import java.io.InputStream
import java.net.URLConnection
import java.util.*


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

    @ExperimentalCoroutinesApi
    override fun indexAudioFiles(directory: Directory, scope: CoroutineScope): ReceiveChannel<AudioFile> {
        logger.debug(TAG, "indexAudioFiles(directory=$directory, ${device.getName()})")
        val startDirectory = device.getFileFromURI(directory.uri)
        return scope.produce {
            // TODO: similar code to USBDeviceStorageLocation
            try {
                for (file in device.walkTopDown(startDirectory)) {
                    if (isIndexingCancelled) {
                        logger.debug(TAG, "Cancel indexAudioFiles()")
                        break
                    }
                    val fileType = determinePlayableFileType(file)
                    if (fileType == null) {
                        logger.debug(TAG, "Skipping unsupported file: $file")
                        continue
                    }
                    if (fileType !is AudioFile) {
                        continue
                    }
                    val audioFile = createAudioFileFromFile(file)
                    send(audioFile)
                }
            } catch (exc: CancellationException) {
                logger.warning(TAG, "Coroutine for indexAudioFiles() was cancelled")
            } catch (exc: RuntimeException) {
                logger.exception(TAG, exc.message.toString(), exc)
            } finally {
                isIndexingCancelled = false
            }
        }
    }

    private fun createAudioFileFromFile(file: File): AudioFile {
        val uri = Util.createURIForPath(storageID, file.absolutePath)
        val audioFile = AudioFile(uri)
        audioFile.lastModifiedDate = Date(file.lastModified())
        return audioFile
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
                    val audioFile = createAudioFileFromFile(file)
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
