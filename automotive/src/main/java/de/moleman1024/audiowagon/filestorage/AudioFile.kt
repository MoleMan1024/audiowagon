/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage

import android.net.Uri
import java.util.Date

/**
 * This class is an abstraction for an audio file on a storage device.
 *
 * @property uri Contains the ID of the storage device and the filepath of the file (perecent encoded)
 * @property lastModifiedDate The unix timestamp when the file was last modified. This is used to check if the
 * metadata of the file has changed (e.g. updated MP3 tags)
 */
data class AudioFile(val uri: Uri = Uri.EMPTY) {
    var lastModifiedDate: Date = Date(0)

    fun getStorageID(): String {
        return uri.authority.toString()
    }

    fun getFilePath(): String {
        val filePathStringBuilder: StringBuilder = StringBuilder()
        uri.path?.let {
            filePathStringBuilder.append(it)
        }
        uri.fragment?.let {
            filePathStringBuilder.append("#$it")
        }
        return filePathStringBuilder.toString()
    }

    fun getFileName(): String {
        return getFilePath().split("/").last()
    }
}
