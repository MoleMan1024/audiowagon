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
import de.moleman1024.audiowagon.filestorage.Directory
import de.moleman1024.audiowagon.filestorage.MediaDevice
import de.moleman1024.audiowagon.log.Logger
import java.io.File


/**
 * NOTE: assets bundled with the app are only used to pass Google's automatic reviews which require some demo data
 */
class AssetMediaDevice(private val assetManager: AssetManager) : MediaDevice {
    override val TAG = "AssetMediaDevice"
    override val logger = Logger
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


    override suspend fun getDataSourceForURI(uri: Uri): MediaDataSource {
        return AssetAudioDataSource(getFileDescriptorFromURI(uri))
    }

    override suspend fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource {
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

    override fun getFileFromURI(uri: Uri): Any {
        // this is not used in AssetStorageLocation
        return Directory(Uri.parse(getRoot()))
    }

    fun close() {
        isClosed = true
    }

}
