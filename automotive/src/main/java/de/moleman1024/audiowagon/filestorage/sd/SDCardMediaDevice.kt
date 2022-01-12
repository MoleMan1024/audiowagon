/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.sd

import android.media.MediaDataSource
import android.net.Uri
import de.moleman1024.audiowagon.exceptions.NoSuchDeviceException
import de.moleman1024.audiowagon.filestorage.AudioFile
import de.moleman1024.audiowagon.filestorage.MediaDevice
import de.moleman1024.audiowagon.log.Logger
import java.io.File
import java.io.IOException
import java.lang.IllegalArgumentException

private const val TAG = "SDCardMediaDevice"
private val logger = Logger

/**
 * NOTE: SD card support is only enabled in debug builds used in the Android emulator
 */
class SDCardMediaDevice(val id: String, private val rootDir: String = "/") : MediaDevice {
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

    fun walkTopDown(rootDirectory: File): Sequence<File> = sequence {
        val stack = ArrayDeque<Iterator<File>>()
        val allFilesDirs = mutableMapOf<String, Unit>()
        // this requires permission READ_EXTERNAL_STORAGE requested at runtime and legacy storage enabled in manifest
        val filesAtRoot = rootDirectory.listFiles() ?: throw RuntimeException("No permission to list files on SD card")
        stack.add(filesAtRoot.iterator())
        while (stack.isNotEmpty()) {
            if (stack.last().hasNext()) {
                val fileOrDirectory = stack.last().next()
                if (!allFilesDirs.containsKey(fileOrDirectory.absolutePath)) {
                    allFilesDirs[fileOrDirectory.absolutePath] = Unit
                    if (!fileOrDirectory.isDirectory) {
                        logger.verbose(TAG, "Found file: ${fileOrDirectory.absolutePath}")
                        yield(fileOrDirectory)
                    } else {
                        stack.add(fileOrDirectory.listFiles()!!.iterator())
                    }
                }
            } else {
                stack.removeLast()
            }
        }
    }

    fun getDirectoryContents(directoryURI: Uri): List<File> {
        val directory = getFileFromURI(directoryURI)
        if (!directory.isDirectory) {
            throw IllegalArgumentException("Is not a directory: $directory")
        }
        return directory.listFiles()!!.toList()
    }

    override fun getDataSourceForURI(uri: Uri): MediaDataSource {
        return SDCardAudioDataSource(getFileFromURI(uri))
    }

    override fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource {
        return getDataSourceForURI(uri)
    }

    @Synchronized
    fun getFileFromURI(uri: Uri): File {
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
