/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage

import android.media.MediaDataSource
import android.net.Uri
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import java.io.File
import java.io.IOException
import java.util.*

interface AudioFileStorageLocation {
    @Suppress("PropertyName")
    val TAG: String
    val logger: Logger
    val device: MediaDevice
    val storageID: String
    var indexingStatus: IndexingStatus
    var isDetached: Boolean
    var isIndexingCancelled: Boolean
    var libaumsDispatcher: CoroutineDispatcher?

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
        return scope.produce(dispatcher + exceptionHandler) {
            val startDirectory = device.getFileFromURI(directory.uri)
            try {
                // walkTopDown is locked internally for USB
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
                    libaumsDispatcher?.let {
                        withContext(it) {
                            createTypedFileLambda()
                        }
                    } ?: run {
                        createTypedFileLambda()
                    }
                }
            } catch (exc: CancellationException) {
                logger.warning(TAG, "Coroutine for indexAudioFiles() was cancelled")
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

    fun getDataSourceForURI(uri: Uri): MediaDataSource
    fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource
    fun getByteArrayForURI(uri: Uri): ByteArray
    fun getInputStreamForURI(uri: Uri): LockableInputStream
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
        logger.debug(TAG, "cancelIndexAudioFiles()")
        if (indexingStatus == IndexingStatus.INDEXING) {
            logger.debug(TAG, "Cancelling ongoing audio file indexing")
            isIndexingCancelled = true
        }
    }

}
