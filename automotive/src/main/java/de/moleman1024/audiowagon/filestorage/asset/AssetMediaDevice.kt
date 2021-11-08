/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.asset

import android.content.res.AssetFileDescriptor
import android.content.res.AssetManager
import android.media.MediaDataSource
import android.net.Uri
import de.moleman1024.audiowagon.filestorage.AudioFile
import de.moleman1024.audiowagon.filestorage.MediaDevice
import java.io.File


/**
 * NOTE: assets bundled with the app are only used to pass Google's automatic reviews which require some demo data
 */
class AssetMediaDevice(private val assetManager: AssetManager) : MediaDevice {
    val id: String = "assets"
    var isClosed: Boolean = false

    fun getRoot(): String {
        return "/"
    }

    fun getDirectoryContents(directoryURI: Uri): List<File> {
        val audioFile = AudioFile(directoryURI)
        val filePath = audioFile.path
        val files = mutableListOf<File>()
        assetManager.list(filePath)?.forEach {
            files.add(File(it))
        }
        return files
    }

    override fun getDataSourceForURI(uri: Uri): MediaDataSource {
        return AssetAudioDataSource(getFileDescriptorFromURI(uri))
    }

    override fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource {
        return getDataSourceForURI(uri)
    }

    @Synchronized
    fun getFileDescriptorFromURI(uri: Uri): AssetFileDescriptor {
        val audioFile = AudioFile(uri)
        val filePath = audioFile.path.replace("^/".toRegex(), "")
        return assetManager.openFd(filePath)
    }

    override fun getID(): String {
        return id
    }

    override fun getName(): String {
        return "AssetMediaDevice{id=$id}"
    }

    fun close() {
        isClosed = true
    }

}
