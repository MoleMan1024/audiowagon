/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.asset

import android.content.res.AssetFileDescriptor
import android.content.res.AssetManager
import android.media.MediaDataSource
import android.net.Uri
import de.moleman1024.audiowagon.filestorage.MediaDevice
import de.moleman1024.audiowagon.log.Logger
import java.io.IOException
import androidx.core.net.toUri
import java.io.InputStream


/**
 * NOTE: assets bundled with the app are only used to pass Google's automatic reviews which require some demo data
 */
class AssetMediaDevice(private val assetManager: AssetManager) : MediaDevice {
    override val TAG = "AssetMediaDevice"
    override val logger = Logger
    val chunkSize = 32768
    var isClosed = false

    override suspend fun getDataSourceForURI(uri: Uri): MediaDataSource {
        return AssetAudioDataSource(getFileDescriptorFromURI(uri))
    }

    override suspend fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource {
        return getDataSourceForURI(uri)
    }

    override fun getID(): String {
        return getName()
    }

    override fun getName(): String {
        return "assets"
    }

    override fun getFileFromURI(uri: Uri): AssetFile {
        logger.debug(TAG, "getFileFromURI(uri=$uri)")
        val assetFile = AssetFile(uri, assetManager)
        val filePath = removeLeadingSlash(assetFile.path)
        if (filePath != "") {
            if (!assetFile.path.matches(".*\\.[a-zA-Z0-9]+".toRegex())) {
                // crude and incomplete way to see if this is a file or directory
                assetFile.isDirectory = true
            } else {
                var fileDescriptor: AssetFileDescriptor? = null
                try {
                    fileDescriptor = assetManager.openFd(filePath)
                } catch (_: IOException) {
                    logger.warning(TAG, "Could not open file descriptor for: $filePath")
                }
                fileDescriptor?.close()
            }
        } else {
            assetFile.isRoot = true
            assetFile.isDirectory = true
        }
        logger.debug(TAG, "file=$assetFile")
        return assetFile
    }

    fun getRoot(): String {
        return ""
    }

    /**
     * If this returns an empty list, the given URI either did not exists or it was a regular file
     */
    fun getDirectoryContents(assetFileOrDirectory: AssetFile): List<AssetFile> {
        if (isClosed) {
            throw IOException("Asset manager is already closed")
        }
        if (!assetFileOrDirectory.isDirectory) {
            logger.debug(TAG, "Not a directory: $assetFileOrDirectory")
            return emptyList()
        }
        val directoryPath = removeLeadingSlash(assetFileOrDirectory.path)
        val files = mutableListOf<AssetFile>()
        assetManager.list(directoryPath)?.forEach {
            val filePath = "$directoryPath/$it"
            val assetFileOrDir = getFileFromURI(filePath.toUri())
            files.add(assetFileOrDir)
        }
        return files
    }

    fun getFileDescriptorFromURI(uri: Uri): AssetFileDescriptor {
        if (isClosed) {
            throw IOException("Asset manager is already closed")
        }
        val assetFile = AssetFile(uri, assetManager)
        return assetManager.openFd(removeLeadingSlash(assetFile.path))
    }

    fun close() {
        if (!isClosed) {
            assetManager.close()
        }
    }

    fun getInputStreamForURI(uri: Uri): InputStream {
        if (isClosed) {
            throw IOException("Asset manager is already closed")
        }
        val assetFile = AssetFile(uri, assetManager)
        return assetManager.open(removeLeadingSlash(assetFile.path))
    }

    private fun removeLeadingSlash(path: String): String {
        return path.replace(Regex("^/"), "")
    }

}
