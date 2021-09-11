/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary

import android.net.Uri

data class AudioItem(
    //  unique identifier used by media browser service (content hierarchy)
    var id: String = "",
    var uri: Uri = Uri.EMPTY,
    var artist: String = "",
    var album: String = "",
    // will use the filename if no title in metadata
    var title: String = "",
    var genre: String = "",
    var trackNum: Short = -1,
    var year: Short = -1,
    var durationMS: Int = -1,
    var albumArtURI: Uri = Uri.EMPTY,
    // bitmask to determine if item is browsable, playable or both. See [MediaBrowser.MediaItem]
    var browsPlayableFlags: Int = 0,
    var isInCompilation: Boolean = false
)
