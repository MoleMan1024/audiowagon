/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage

import android.media.MediaDataSource
import android.net.Uri
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.enums.IndexingStatus
import de.moleman1024.audiowagon.filestorage.data.AudioFile
import de.moleman1024.audiowagon.filestorage.data.Directory
import de.moleman1024.audiowagon.filestorage.data.PlaylistFile
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.*

/**
 * An interface for a location to be managed by [AudioFileStorage] class.
 * This works with File-like objects and URIs only, so the different implementations can be used (USB, SD card, local
 * filesystem, Android asset files, ...)
 */
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
    fun indexAudioFiles(
        directory: Directory,
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher
    ): ReceiveChannel<FileLike> {
        logger.debug(TAG, "indexAudioFiles(directory=$directory, ${device.getName()})")
        val exceptionHandler = CoroutineExceptionHandler { _, exc ->
            when (exc) {
                is IOException -> {
                    logger.exception(TAG, "I/O exception in indexAudioFiles()", exc)
                }
                else -> {
                    logger.exception(TAG, "Exception in indexAudioFiles()", exc)
                }
            }
            isIndexingCancelled = false
            postIndexAudioFiles()
        }
        return scope.produce(exceptionHandler + dispatcher) {
            val startDirectory = device.getFileFromURI(directory.uri)
            try {
                for (file in walkTopDown(startDirectory, this)) {
                    if (isIndexingCancelled) {
                        logger.debug(TAG, "Cancel indexAudioFiles()")
                        break
                    }
                    val createTypedFileLambda: (suspend() -> Unit) = createTypedFileLambda@ {
                        val fileType = Util.determinePlayableFileType(file)
                        if (fileType == null) {
                            logger.debug(TAG, "Skipping unsupported file: $file")
                            return@createTypedFileLambda
                        }
                        when (fileType) {
                            is PlaylistFile -> {
                                // skip playlists, we don't need them indexed in media library
                                return@createTypedFileLambda
                            }
                            is Directory -> {
                                send(createDirectoryFromFileInIndex(file))
                            }
                            is AudioFile -> {
                                send(createAudioFileFromFileInIndex(file))
                            }
                        }
                    }
                    run {
                        createTypedFileLambda()
                    }
                }
            } catch (exc: CancellationException) {
                logger.exception(TAG, "Coroutine for indexAudioFiles() was cancelled", exc)
            } catch (exc: RuntimeException) {
                logger.exception(TAG, exc.message.toString(), exc)
            } finally {
                isIndexingCancelled = false
                postIndexAudioFiles()
            }
        }
    }

    fun walkTopDown(startDirectory: Any, scope: CoroutineScope): Sequence<Any>

    fun createDirectoryFromFileInIndex(file: Any): FileLike {
        file as File
        val uri = Util.createURIForPath(storageID, file.absolutePath)
        val dir = Directory(uri)
        dir.lastModifiedDate = Date(file.lastModified())
        return dir
    }

    fun createAudioFileFromFileInIndex(file: Any): FileLike {
        file as File
        return Util.createAudioFileFromFile(file, storageID)
    }

    fun postIndexAudioFiles() {
        // no op
    }

    suspend fun getDataSourceForURI(uri: Uri): MediaDataSource
    suspend fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource
    suspend fun getByteArrayForURI(uri: Uri): ByteArray
    suspend fun getInputStreamForURI(uri: Uri): InputStream
    fun close()
    fun setDetached()

    /**
     * Returns all content (files/directories) in the given directory. Non-recursive
     */
    suspend fun getDirectoryContents(directory: Directory): List<FileLike>

    /**
     * Returns all directories and audio files in the given directory. Non-recursive
     */
    suspend fun getDirectoryContentsPlayable(directory: Directory): List<FileLike>

    /**
     * Returns the URI of the root directory of the storage location
     */
    fun getRootURI(): Uri

    /**
     * Cancels an ongoing indexing pipeline
     */
    fun cancelIndexAudioFiles() {
        logger.debug(TAG, "cancelIndexAudioFiles()")
        if (indexingStatus == IndexingStatus.INDEXING) {
            logger.debug(TAG, "Cancelling ongoing audio file indexing")
            isIndexingCancelled = true
        }
    }

}
