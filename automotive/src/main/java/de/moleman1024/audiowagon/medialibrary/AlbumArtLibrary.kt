/*
SPDX-FileCopyrightText: 2021-2025 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary

import android.content.Context
import android.os.Environment
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import de.moleman1024.audiowagon.filestorage.FileLike
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

private const val TAG = "AlbumArtLibrary"
private val logger = Logger

/**
 * Stores resized album art on built-in disk. This was done to reduce I/O stress on USB filesystem during playback
 * (creating audio stutters, see https://github.com/MoleMan1024/audiowagon/issues/174 )
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumArtLibrary(context: Context, val storageID: String, private val audioFileStorage: AudioFileStorage) {
    var storageRootDir = getStorageRootDir(context, storageID)

    init {
        logger.debug(TAG, "AlbumArtLibrary(storageID=$storageID)")
        if (!storageRootDir.exists()) {
            logger.debug(TAG, "Creating directory for album art at: $storageRootDir")
            storageRootDir.mkdirs()
        }
    }

    fun findResizedAlbumArtOnDisk(albumArtID: Int): ByteArray? {
        val albumArtFile = File(storageRootDir, "$albumArtID.jpg")
        if (!albumArtFile.exists()) {
            return null
        }
        try {
            logger.debug(TAG, "Loading resized album art from disk: ${albumArtFile.absolutePath}")
            return Files.readAllBytes(albumArtFile.toPath())
        } catch (exc: IOException) {
            logger.exception(TAG, exc.message.toString(), exc)
        }
        return null
    }

    fun storeEmbeddedAlbumArtBytesOnDisk(bytes: ByteArray?, albumArtID: Int) {
        if (bytes == null) {
            return
        }
        resizeAndStoreAlbumArtBytesOnDisk(bytes, albumArtID)
    }

    suspend fun copyAlbumArtFromFileStorageToDisk(albumArtFileLike: FileLike, albumArtID: Int) {
        val albumArtBytes = audioFileStorage.getByteArrayForURI(albumArtFileLike.uri)
        resizeAndStoreAlbumArtBytesOnDisk(albumArtBytes, albumArtID)
    }

    private fun resizeAndStoreAlbumArtBytesOnDisk(bytes: ByteArray, albumArtID: Int) {
        val albumArtResizedBytes = AudioMetadataMaker.resizeAlbumArt(bytes)
        if (albumArtResizedBytes == null) {
            return
        }
        val albumArtResizedFile = File(storageRootDir, "$albumArtID.jpg")
        val md5Hash = computeMD5Hash(albumArtResizedBytes)
        val md5File = File(storageRootDir, "$albumArtID.md5")
        if (md5File.exists()) {
            val existingMD5Hash = md5File.readText()
            if (existingMD5Hash == md5Hash) {
                // This is done to avoid wearing down the flash disk unnecessarily
                logger.verbose(TAG, "Resized album art is already stored on disk: $albumArtResizedFile")
                return
            }
        }
        try {
            Files.write(albumArtResizedFile.toPath(), albumArtResizedBytes)
            md5File.writeText(md5Hash)
            logger.debug(TAG, "Stored album art with size ${albumArtResizedBytes.size} at: " +
                    "${albumArtResizedFile.absolutePath}")
        } catch (exc: IOException) {
            logger.exception(TAG, exc.message.toString(), exc)
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun computeMD5Hash(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(bytes)
        return digest.toHexString()
    }

    companion object {
        fun getAlbumArtRootDir(context: Context): File {
            return File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "albumart")
        }

        fun getStorageRootDir(context: Context, storageID: String): File {
            return File(getAlbumArtRootDir(context), storageID)
        }
    }

}
