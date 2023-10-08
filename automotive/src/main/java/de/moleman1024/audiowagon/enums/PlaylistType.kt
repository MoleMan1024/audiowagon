/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.enums

@Suppress("unused")
enum class PlaylistType(val mimeType: String) {
    M3U("audio/x-mpegurl"),
    PLS("audio/x-scpls"),
    XSPF("application/xspf+xml");

    companion object {
        fun fromMimeType(mimeType: String): PlaylistType? {
            return values().find { it.mimeType == mimeType }
        }
    }
}
