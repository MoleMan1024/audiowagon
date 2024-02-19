/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.sd

import android.media.MediaDataSource
import android.net.Uri
import de.moleman1024.audiowagon.exceptions.NoSuchDeviceException
import de.moleman1024.audiowagon.filestorage.data.AudioFile
import de.moleman1024.audiowagon.filestorage.MediaDevice
import de.moleman1024.audiowagon.log.Logger
import java.io.File
import java.io.IOException

/**
 * NOTE: SD card support is only enabled in debug builds used in the Android emulator
 */
class SDCardMediaDevice(val id: String, private val rootDir: String = "/") : MediaDevice {
    override val TAG = "SDCardMediaDevice"
    override val logger = Logger
    private val rootDirectory = File("/storage/${id}${rootDir}")
    var isClosed: Boolean = false

    init {
        if (!rootDirectory.exists()) {
            throw NoSuchDeviceException("SD card media device does not exist at: $rootDirectory")
        }
    }

    fun getRoot(): File {
        return rootDirectory
    }

    fun getDirectoryContents(directoryURI: Uri): List<File> {
        val directory = getFileFromURI(directoryURI)
        require(directory.isDirectory) {
            "Is not a directory: $directory"
        }
        return directory.listFiles()!!.toList()
    }

    override suspend fun getDataSourceForURI(uri: Uri): MediaDataSource {
        return SDCardAudioDataSource(getFileFromURI(uri))
    }

    override suspend fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource {
        return getDataSourceForURI(uri)
    }

    @Synchronized
    override fun getFileFromURI(uri: Uri): File {
        val audioFile = AudioFile(uri)
        val filePath = audioFile.path
        val file = File(filePath)
        if (!file.exists()) {
            throw IOException("File not found: $uri")
        }
        return file
    }

    override fun getID(): String {
        return if (rootDir == "/") {
            id
        } else {
            id + rootDir.replace("/", "_")
        }
    }

    override fun getName(): String {
        return "SDCardMediaDevice(id=${getID()})"
    }

    fun close() {
        isClosed = true
    }

}
