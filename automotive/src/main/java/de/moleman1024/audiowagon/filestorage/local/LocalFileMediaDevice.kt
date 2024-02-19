/*
SPDX-FileCopyrightText: 2021-2022 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.local

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.os.Environment
import de.moleman1024.audiowagon.filestorage.data.AudioFile
import de.moleman1024.audiowagon.filestorage.MediaDevice
import de.moleman1024.audiowagon.log.Logger
import java.io.File
import java.io.IOException

class LocalFileMediaDevice(private val context: Context) : MediaDevice {
    override val TAG = "LocalFileMediaDevice"
    override val logger = Logger
    val chunkSize = 32768
    var rootPath = ""

    override suspend fun getDataSourceForURI(uri: Uri): MediaDataSource {
        return LocalFileAudioDataSource(getFileFromURI(uri))
    }

    override suspend fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource {
        return getDataSourceForURI(uri)
    }

    override fun getID(): String {
        return getName()
    }

    override fun getName(): String {
        return "LocalFiles"
    }

    override fun getFileFromURI(uri: Uri): File {
        logger.debug(TAG, "getFileFromURI(uri=$uri)")
        val audioFile = AudioFile(uri)
        val filePath = audioFile.path
        val file = File(filePath)
        logger.debug(TAG, "file=$file")
        if (!file.exists()) {
            throw IOException("File not found: $uri")
        }
        return file
    }

    fun getRoot(): String {
        if (rootPath.isNotBlank()) {
            return rootPath
        }
        val externalFilesPath = context.getExternalFilesDir(null)
        rootPath = Regex("^(.*)/Android.*").find(externalFilesPath.toString())?.groupValues?.get(1) ?: ""
        rootPath += "/${Environment.DIRECTORY_DOWNLOADS}"
        logger.debug(TAG, "Using rootPath: $rootPath")
        return rootPath
    }

    fun getDirectoryContents(directoryURI: Uri): List<File> {
        val directory = getFileFromURI(directoryURI)
        require(directory.isDirectory) {
            "Is not a directory: $directory"
        }
        return directory.listFiles()!!.toList()
    }
}
