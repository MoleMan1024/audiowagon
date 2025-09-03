/*
SPDX-FileCopyrightText: 2021-2025 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.asset

import android.content.res.AssetManager
import android.net.Uri
import de.moleman1024.audiowagon.filestorage.FileLike
import java.util.Date

data class AssetFile(override val uri: Uri, private val assetManager: AssetManager) : FileLike {
    override var lastModifiedDate: Date = Date(0)
    override val storageID: String
        get() = "assets"
    var isDirectory = false
    var isRoot = false

    override fun toString(): String {
        return "AssetFile{uri=$uri, path=$path, isDirectory=$isDirectory, isRoot=$isRoot}"
    }
}
