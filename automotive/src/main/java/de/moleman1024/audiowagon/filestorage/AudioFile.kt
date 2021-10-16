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
 * @property uri Contains the ID of the storage device and the filepath of the file (percent encoded)
 * @property lastModifiedDate The unix timestamp when the file was last modified. This is used to check if the
 * metadata of the file has changed (e.g. updated MP3 tags)
 */
data class AudioFile(override val uri: Uri = Uri.EMPTY): FileLike {
    var lastModifiedDate: Date = Date(0)
}
