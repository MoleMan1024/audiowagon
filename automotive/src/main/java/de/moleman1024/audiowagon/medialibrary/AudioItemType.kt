/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary

enum class AudioItemType {
    ARTIST,
    ALBUM,
    // a compilation is a special album for various artists
    COMPILATION,
    GROUP_ALBUMS,
    GROUP_ARTISTS,
    GROUP_TRACKS,
    GROUP_TRACKS_IN_ALBUM,
    UNKNOWN_ALBUM,
    TRACK,
    TRACKS_FOR_ALBUM,
    TRACKS_FOR_ARTIST,
    TRACKS_FOR_COMPILATION,
    TRACKS_FOR_UNKN_ALBUM
}
