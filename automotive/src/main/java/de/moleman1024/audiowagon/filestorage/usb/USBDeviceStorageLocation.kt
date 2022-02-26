/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb

import android.media.MediaDataSource
import android.net.Uri
import com.github.mjdev.libaums.fs.UsbFile
import com.github.mjdev.libaums.fs.UsbFileInputStream
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.Util.Companion.determinePlayableFileType
import de.moleman1024.audiowagon.filestorage.*
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.*
import kotlin.RuntimeException

class USBDeviceStorageLocation(override val device: USBMediaDevice) : AudioFileStorageLocation {
    override val TAG = "USBDevStorLoc"
    override val logger = Logger
    override val storageID: String
        get() = device.getID()
    override var indexingStatus: IndexingStatus = IndexingStatus.NOT_INDEXED
    override var isDetached: Boolean = false
    override var isIndexingCancelled: Boolean = false

    @ExperimentalCoroutinesApi
    override fun indexAudioFiles(directory: Directory, scope: CoroutineScope): ReceiveChannel<AudioFile> {
        logger.debug(TAG, "indexAudioFiles(directory=$directory, ${device.getName()})")
        val startDirectory = device.getUSBFileFromURI(directory.uri)
        return scope.produce {
            try {
                for (usbFile in device.walkTopDown(startDirectory, this)) {
                    if (isIndexingCancelled) {
                        logger.debug(TAG, "Cancel indexAudioFiles()")
                        break
                    }
                    val fileType = determinePlayableFileType(usbFile)
                    if (fileType == null) {
                        logger.debug(TAG, "Skipping unsupported file: $usbFile")
                        continue
                    }
                    if (fileType !is AudioFile) {
                        // skip playlists, we don't need them indexed in media library
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

    override fun getDirectoryContents(directory: Directory): List<FileLike> {
        return getDirectoryContentsWithOptionalFilter(directory)
    }

    override fun getDirectoryContentsPlayable(directory: Directory): List<FileLike> {
        return getDirectoryContentsWithOptionalFilter(directory, ::determinePlayableFileType)
    }

    private fun getDirectoryContentsWithOptionalFilter(
        directory: Directory,
        fileTypeGuesser: ((UsbFile) -> FileLike?)? = null
    ): List<FileLike> {
        val itemsInDir = mutableListOf<FileLike>()
        device.getDirectoryContents(directory.uri).forEach { usbFile ->
            if (usbFile.isDirectory) {
                val uri = Util.createURIForPath(storageID, usbFile.absolutePath)
                itemsInDir.add(Directory(uri))
            } else {
                if (fileTypeGuesser == null) {
                    val uri = Util.createURIForPath(storageID, usbFile.absolutePath)
                    val generalFile = GeneralFile(uri)
                    itemsInDir.add(generalFile)
                } else {
                    when (fileTypeGuesser(usbFile)) {
                        is AudioFile -> {
                            val audioFile = createAudioFileFromUSBFile(usbFile)
                            itemsInDir.add(audioFile)
                        }
                        is PlaylistFile -> {
                            val uri = Util.createURIForPath(storageID, usbFile.absolutePath)
                            val playlistFile = PlaylistFile(uri)
                            itemsInDir.add(playlistFile)
                        }
                        else -> {
                            logger.warning(TAG, "Ignoring file type of: $usbFile")
                        }
                    }
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

    override fun getByteArrayForURI(uri: Uri): ByteArray {
        val inputStream = getInputStreamForURI(uri)
        val byteArrayOutputStream = ByteArrayOutputStream()
        val buffer = ByteArray(device.getChunkSize())
        var bytesRead = 0
        while (bytesRead > -1) {
            bytesRead = inputStream.read(buffer)
            byteArrayOutputStream.write(buffer)
        }
        return byteArrayOutputStream.toByteArray()
    }

    override fun getInputStreamForURI(uri: Uri): InputStream {
        val usbFile = device.getUSBFileFromURI(uri)
        return UsbFileInputStream(usbFile)
    }

    override fun close() {
        device.close()
    }

    override fun setDetached() {
        device.preventLoggingToDetachedDevice()
        device.closeMassStorageFilesystem()
        isDetached = true
    }

    override fun toString(): String {
        return "USBDeviceStorageLocation{${device.getID()}}"
    }

}
