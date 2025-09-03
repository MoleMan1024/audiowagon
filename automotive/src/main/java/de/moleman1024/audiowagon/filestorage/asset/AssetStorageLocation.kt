/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.asset

import android.media.MediaDataSource
import android.net.Uri
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.Util.Companion.createURIForPath
import de.moleman1024.audiowagon.enums.IndexingStatus
import de.moleman1024.audiowagon.filestorage.*
import de.moleman1024.audiowagon.filestorage.data.AudioFile
import de.moleman1024.audiowagon.filestorage.data.Directory
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.CoroutineScope
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.*

/**
 * Assets bundled with the app are only used to pass Google's automatic reviews which seem to require some demo data
 */
class AssetStorageLocation(override val device: AssetMediaDevice) : AudioFileStorageLocation {
    override val TAG = "AssetStorLoc"
    override val logger = Logger
    override val storageID: String
        get() = device.getID()
    override var indexingStatus: IndexingStatus = IndexingStatus.NOT_INDEXED
    override var isDetached: Boolean = false
    override var isIndexingCancelled: Boolean = false

    override fun walkTopDown(startDirectory: Any, scope: CoroutineScope) = sequence {
        val queue = LinkedList<AssetFile>()
        val allFilesDirs = mutableMapOf<String, Unit>()
        allFilesDirs[(startDirectory as AssetFile).path] = Unit
        queue.add(startDirectory)
        while (queue.isNotEmpty()) {
            val fileOrDirectory = queue.removeFirst()
            if (!fileOrDirectory.isDirectory) {
                if (fileOrDirectory.name.contains(Util.FILES_TO_IGNORE_REGEX)) {
                    logger.debug(TAG, "Ignoring file: ${fileOrDirectory.name}")
                } else {
                    logger.verbose(TAG, "Found file: ${fileOrDirectory.path}")
                    yield(fileOrDirectory)
                }
            } else {
                if (fileOrDirectory.name.contains(Util.DIRECTORIES_TO_IGNORE_REGEX)) {
                    logger.debug(TAG, "Ignoring directory: ${fileOrDirectory.name}")
                } else {
                    logger.verbose(TAG, "Walking directory: ${fileOrDirectory.path}")
                    yield(fileOrDirectory)
                    for (subFileOrDir in device.getDirectoryContents(fileOrDirectory).sortedBy {
                        it.name.lowercase()
                    }) {
                        if (!allFilesDirs.containsKey(subFileOrDir.path)) {
                            allFilesDirs[subFileOrDir.path] = Unit
                            if (subFileOrDir.isDirectory) {
                                logger.verbose(TAG, "Found directory: ${subFileOrDir.path}")
                            }
                            queue.add(subFileOrDir)
                        }
                    }
                }
            }
        }
    }

    override suspend fun getDirectoryContents(directory: Directory): List<FileLike> {
        val itemsInDir = mutableListOf<FileLike>()
        device.getDirectoryContents(device.getFileFromURI(directory.uri)).forEach { file ->
            if (file.isDirectory) {
                val uri = createURIForPath(storageID, file.path)
                itemsInDir.add(Directory(uri))
            } else {
                val fileType = Util.determinePlayableFileType(file)
                if (fileType != null) {
                    val audioFile = createAudioFileFromAssetFile(file, storageID)
                    itemsInDir.add(audioFile)
                }
            }
        }
        return itemsInDir
    }

    override suspend fun getDirectoryContentsPlayable(directory: Directory): List<FileLike> {
        return getDirectoryContents(directory)
    }

    override suspend fun getDataSourceForURI(uri: Uri): MediaDataSource {
        return device.getDataSourceForURI(uri)
    }

    override suspend fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource {
        return device.getBufferedDataSourceForURI(uri)
    }

    override suspend fun getByteArrayForURI(uri: Uri): ByteArray {
        val inputStream = getInputStreamForURI(uri)
        val byteArrayOutputStream = ByteArrayOutputStream()
        val buffer = ByteArray(device.chunkSize)
        var bytesRead = 0
        while (bytesRead > -1) {
            bytesRead = inputStream.read(buffer)
            byteArrayOutputStream.write(buffer)
        }
        return byteArrayOutputStream.toByteArray()
    }

    override suspend fun getInputStreamForURI(uri: Uri): InputStream {
        return device.getInputStreamForURI(uri)
    }

    override fun close() {
        device.close()
    }

    override fun setDetached() {
        device.isClosed = true
        isDetached = true
    }

    override fun getRootURI(): Uri {
        return createURIForPath(storageID, device.getRoot())
    }

    fun createAudioFileFromAssetFile(assetFile: AssetFile, storageID: String): AudioFile {
        val uri = createURIForPath(storageID, assetFile.path)
        val audioFile = AudioFile(uri)
        audioFile.lastModifiedDate = assetFile.lastModifiedDate
        return audioFile
    }

    override fun createDirectoryFromFileInIndex(file: Any): FileLike {
        file as AssetFile
        val uri = createURIForPath(storageID, file.path)
        val dir = Directory(uri)
        dir.lastModifiedDate = file.lastModifiedDate
        return dir
    }

    override fun createAudioFileFromFileInIndex(file: Any): FileLike {
        file as AssetFile
        return createAudioFileFromAssetFile(file, file.storageID)
    }
}
