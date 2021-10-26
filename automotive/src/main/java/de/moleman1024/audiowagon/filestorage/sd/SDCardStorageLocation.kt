/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.sd

import android.media.MediaDataSource
import android.net.Uri
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.filestorage.*
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import java.io.File
import java.net.URLConnection
import java.util.*

private const val TAG = "SDCardStorLoc"
private val logger = Logger

/**
 * NOTE: SD card support is only enabled in debug builds used in the Android emulator
 */
class SDCardStorageLocation(override val device: SDCardMediaDevice) : AudioFileStorageLocation {
    override val storageID: String
        get() = device.getID()
    override var indexingStatus: IndexingStatus = IndexingStatus.NOT_INDEXED
    override var isDetached: Boolean = false
    private var isIndexingCancelled: Boolean = false

    @ExperimentalCoroutinesApi
    override fun indexAudioFiles(directory: Directory, scope: CoroutineScope): ReceiveChannel<AudioFile> {
        logger.debug(TAG, "indexAudioFiles(directory=$directory, ${device.getName()})")
        val startDirectory = device.getFileFromURI(directory.uri)
        return scope.produce {
            try {
                for (file in device.walkTopDown(startDirectory)) {
                    if (isIndexingCancelled) {
                        logger.debug(TAG, "Cancel indexAudioFiles()")
                        break
                    }
                    if (!isPlayableAudioFile(file)) {
                        logger.debug(TAG, "Skipping non-audio file: $file")
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
                if (isPlayableAudioFile(file)) {
                    val audioFile = createAudioFileFromFile(file)
                    itemsInDir.add(audioFile)
                }
            }
        }
        return itemsInDir
    }

    override fun getDataSourceForURI(uri: Uri): MediaDataSource {
        return device.getDataSourceForURI(uri)
    }

    override fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource {
        return device.getBufferedDataSourceForURI(uri)
    }

    override fun close() {
        device.close()
    }

    override fun setDetached() {
        device.isClosed = true
        isDetached = true
    }

    // TODO: remove code duplication
    private fun isPlayableAudioFile(file: File): Boolean {
        if (file.isDirectory) {
            return false
        }
        val guessedContentType: String
        try {
            val safeFileName = makeFileNameSafeForContentTypeGuessing(file.name)
            guessedContentType = URLConnection.guessContentTypeFromName(safeFileName) ?: return false
        } catch (exc: StringIndexOutOfBoundsException) {
            logger.exception(TAG, "Error when guessing content type of: ${file.name}", exc)
            return false
        }
        if (!isSupportedContentType(guessedContentType)) {
            return false
        }
        return true
    }

    override fun getDirectoriesWithIndexingIssues(): List<String> {
        // TODO
        return emptyList()
    }

    override fun cancelIndexAudioFiles() {
        logger.debug(TAG, "Cancelling audio file indexing")
        isIndexingCancelled = true
    }

    override fun getRootURI(): Uri {
        return Util.createURIForPath(storageID, device.getRoot().absolutePath)
    }

}
