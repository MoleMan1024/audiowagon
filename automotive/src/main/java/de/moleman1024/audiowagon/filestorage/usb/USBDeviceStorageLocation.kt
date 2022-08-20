/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb

import android.media.MediaDataSource
import android.net.Uri
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileInputStream
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.Util.Companion.determinePlayableFileType
import de.moleman1024.audiowagon.filestorage.*
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.*
import java.util.concurrent.locks.ReentrantLock

class USBDeviceStorageLocation(override val device: USBMediaDevice) : AudioFileStorageLocation {
    override val TAG = "USBDevStorLoc"
    override val logger = Logger
    override val storageID: String
        get() = device.getID()
    override var indexingStatus: IndexingStatus = IndexingStatus.NOT_INDEXED
    override var isDetached: Boolean = false
    override var isIndexingCancelled: Boolean = false
    override var libaumsDispatcher: CoroutineDispatcher? = device.libaumsDispatcher

    override fun walkTopDown(startDirectory: Any, scope: CoroutineScope): Sequence<Any> {
        // locked internally by usbMutex
        return device.walkTopDown(startDirectory as UsbFile, scope)
    }

    // locked externally
    override fun createDirectoryFromFileInIndex(file: Any): FileLike {
        val usbFile = file as UsbFile
        val uri = Util.createURIForPath(storageID, usbFile.absolutePath)
        val dir = Directory(uri)
        // libaums does not support extracting lastModifiedDate from root directory
        if (dir.path != "/") {
            dir.lastModifiedDate = Date(usbFile.lastModified())
        }
        return dir
    }

    // locked externally
    override fun createAudioFileFromFileInIndex(file: Any): FileLike {
        return createAudioFileFromUSBFile(file as UsbFile)
    }

    override fun postIndexAudioFiles() {
        device.clearRecentFilepathToFileMap()
    }

    // locked externally
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
            runBlocking(device.libaumsDispatcher) {
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
        }
        return itemsInDir
    }

    override fun getRootURI(): Uri {
        val path: String
        runBlocking(device.libaumsDispatcher) {
            path = device.getRoot().absolutePath
        }
        return Util.createURIForPath(storageID, path)
    }

    override fun getDataSourceForURI(uri: Uri): MediaDataSource {
        return device.getDataSourceForURI(uri)
    }

    override fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource {
        return device.getBufferedDataSourceForURI(uri)
    }

    override fun getByteArrayForURI(uri: Uri): ByteArray {
        val lockableInputStream = getInputStreamForURI(uri)
        return runBlocking(device.libaumsDispatcher) {
            val byteArrayOutputStream = ByteArrayOutputStream()
            val buffer = ByteArray(device.getChunkSize())
            var bytesRead = 0
            while (bytesRead > -1) {
                bytesRead = lockableInputStream.inputStream.read(buffer)
                byteArrayOutputStream.write(buffer)
            }
            return@runBlocking byteArrayOutputStream.toByteArray()
        }
    }

    override fun getInputStreamForURI(uri: Uri): LockableInputStream {
        val inputStream: InputStream
        runBlocking(device.libaumsDispatcher) {
            inputStream = UsbFileInputStream(device.getUSBFileFromURI(uri))
        }
        return LockableInputStream(inputStream, device.libaumsDispatcher)
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
