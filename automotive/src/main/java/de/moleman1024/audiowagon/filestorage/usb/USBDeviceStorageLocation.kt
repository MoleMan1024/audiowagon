/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb

import android.media.MediaDataSource
import android.net.Uri
import com.github.mjdev.libaums.fs.UsbFile
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.filestorage.*
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import java.net.URLConnection
import java.util.*
import kotlin.RuntimeException

private const val TAG = "USBDevStorLoc"
private val logger = Logger

class USBDeviceStorageLocation(override val device: USBMediaDevice) : AudioFileStorageLocation {
    override val storageID: String
        get() = device.getID()
    override var indexingStatus: IndexingStatus = IndexingStatus.NOT_INDEXED
    override var isDetached: Boolean = false
    private var isIndexingCancelled: Boolean = false

    @ExperimentalCoroutinesApi
    override fun indexAudioFiles(directory: Directory, scope: CoroutineScope): ReceiveChannel<AudioFile> {
        logger.debug(TAG, "indexAudioFiles(directory=$directory, ${device.getName()})")
        val startDirectory = device.getUSBFileFromURI(directory.uri)
        return scope.produce {
            try {
                for (usbFile in device.walkTopDown(startDirectory)) {
                    if (isIndexingCancelled) {
                        logger.debug(TAG, "Cancel indexAudioFiles()")
                        break
                    }
                    if (!isPlayableAudioFile(usbFile)) {
                        logger.debug(TAG, "Skipping unsupported file: $usbFile")
                        continue
                    }
                    val audioFile = createAudioFileFromUSBFile(usbFile)
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

    private fun createAudioFileFromUSBFile(usbFile: UsbFile): AudioFile {
        val uri = Util.createURIForPath(storageID, usbFile.absolutePath)
        val audioFile = AudioFile(uri)
        audioFile.lastModifiedDate = Date(usbFile.lastModified())
        return audioFile
    }

    override fun cancelIndexAudioFiles() {
        logger.debug(TAG, "Cancelling audio file indexing")
        isIndexingCancelled = true
    }

    override fun getDirectoryContents(directory: Directory): List<FileLike> {
        val itemsInDir = mutableListOf<FileLike>()
        device.getDirectoryContents(directory.uri).forEach { usbFile ->
            if (usbFile.isDirectory) {
                val uri = Util.createURIForPath(storageID, usbFile.absolutePath)
                itemsInDir.add(Directory(uri))
            } else {
                if (isPlayableAudioFile(usbFile)) {
                    val audioFile = createAudioFileFromUSBFile(usbFile)
                    itemsInDir.add(audioFile)
                }
            }
        }
        return itemsInDir
    }

    override fun getRootURI(): Uri {
        return Util.createURIForPath(storageID, device.getRoot().absolutePath)
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
        device.preventLoggingToDetachedDevice()
        device.closeMassStorageFilesystem()
        isDetached = true
    }

    private fun isPlayableAudioFile(usbFile: UsbFile): Boolean {
        if (usbFile.isDirectory || usbFile.isRoot) {
            return false
        }
        val guessedContentType: String
        try {
            val safeFileName = makeFileNameSafeForContentTypeGuessing(usbFile.name)
            guessedContentType = URLConnection.guessContentTypeFromName(safeFileName) ?: return false
        } catch (exc: StringIndexOutOfBoundsException) {
            logger.exception(TAG, "Error when guessing content type of: ${usbFile.name}", exc)
            return false
        }
        if (!isSupportedContentType(guessedContentType)) {
            return false
        }
        return true
    }

    override fun toString(): String {
        return "USBDeviceStorageLocation{${device.getID()}}"
    }

    override fun getDirectoriesWithIndexingIssues(): List<String> {
        return device.directoriesWithIssues
    }

}
