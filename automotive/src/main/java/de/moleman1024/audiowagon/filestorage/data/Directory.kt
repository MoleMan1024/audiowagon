/*
SPDX-FileCopyrightText: 2023 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.data

import android.net.Uri
import de.moleman1024.audiowagon.filestorage.FileLike
import java.util.*

data class Directory(override val uri: Uri = Uri.EMPTY): FileLike {
    override var lastModifiedDate: Date = Date(0)
}
