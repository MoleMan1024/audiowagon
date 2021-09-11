/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.sd

import android.media.MediaDataSource
import android.net.Uri
import com.github.mjdev.libaums.fs.UsbFile
import de.moleman1024.audiowagon.exceptions.NoSuchDeviceException
import de.moleman1024.audiowagon.filestorage.AudioFile
import de.moleman1024.audiowagon.filestorage.MediaDevice
import de.moleman1024.audiowagon.log.Logger
import java.io.File
import java.io.IOException

private const val TAG = "SDCardMediaDevice"
private val logger = Logger

class SDCardMediaDevice(val id: String) : MediaDevice {
    private val rootDirectory = File("/storage/${id}/Music")
    var isClosed: Boolean = false

    init {
        if (!rootDirectory.exists()) {
            throw NoSuchDeviceException("SD card media device does not exist at: $rootDirectory")
        }
    }

    fun getRoot(): File {
        logger.debug(TAG, rootDirectory.isDirectory.toString())
        logger.debug(TAG, rootDirectory.canRead().toString())
        return rootDirectory
    }

    fun walkTopDown(rootDirectory: File): Sequence<File> = sequence {
        val stack = ArrayDeque<Iterator<File>>()
        val allFilesDirs = mutableMapOf<String, Unit>()
        // this requires permission READ_EXTERNAL_STORAGE requested at runtime and legacy storage enabled in manifest
        val filesAtRoot = rootDirectory.listFiles() ?: throw RuntimeException("No permission to list files on SD card")
        filesAtRoot.toString().let { logger.debug(TAG, it) }
        stack.add(filesAtRoot.iterator())
        while (stack.isNotEmpty()) {
            if (stack.last().hasNext()) {
                val fileOrDirectory = stack.last().next()
                if (!allFilesDirs.containsKey(fileOrDirectory.absolutePath)) {
                    allFilesDirs[fileOrDirectory.absolutePath] = Unit
                    if (!fileOrDirectory.isDirectory) {
                        logger.verbose(TAG, "Found file/dir: ${fileOrDirectory.absolutePath}")
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

    override fun getDataSourceForURI(uri: Uri): MediaDataSource {
        return SDCardAudioDataSource(getFileFromURI(uri))
    }

    override fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource {
        return getDataSourceForURI(uri)
    }

    @Synchronized
    private fun getFileFromURI(uri: Uri): File {
        val audioFile = AudioFile(uri)
        logger.debug(TAG, "audioFile=$audioFile")
        val filePath = audioFile.getFilePath()
        val file = File(filePath)
        logger.debug(TAG, "file=$file")
        if (!file.exists()) {
            throw IOException("File not found: $uri")
        }
        return file
    }

    override fun getID(): String {
        return id
    }

    override fun getShortName(): String {
        return "SDCardMediaDevice{id=$id}"
    }

    override fun getLongName(): String {
        return getShortName()
    }

    fun close() {
        isClosed = true
    }
}
