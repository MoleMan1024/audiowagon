/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary.contenthierarchy

enum class ContentHierarchyType {
    NONE,
    ROOT,
    SHUFFLE_ALL_TRACKS,
    ROOT_TRACKS,
    ROOT_ALBUMS,
    ROOT_ARTISTS,
    TRACK,
    ALBUM,
    // a compilation is a special album for various artists
    COMPILATION,
    UNKNOWN_ALBUM,
    ARTIST,
    ALBUM_GROUP,
    ARTIST_GROUP,
    TRACK_GROUP,
    ALL_TRACKS_FOR_ARTIST,
    ALL_TRACKS_FOR_ALBUM,
    ALL_TRACKS_FOR_COMPILATION,
    ALL_TRACKS_FOR_UNKN_ALBUM
}
