/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.repository.entities

import androidx.room.*

// no foreign key constraints here, in case parent artist/album cannot be determined
@Entity
data class Track(
    @PrimaryKey(autoGenerate = true)
    val trackId: Long = 0,
    @ColumnInfo(index = true)
    val name: String = "",
    @ColumnInfo(index = true)
    val parentArtistId: Long = -1,
    @ColumnInfo(index = true)
    val parentAlbumId: Long = -1,
    val indexOnAlbum: Short = -1,
    val albumArtURIString: String = "",
    @ColumnInfo(index = true)
    val yearEpochTime: Long = -1,
    @ColumnInfo(index = true)
    val genre: String = "",
    @ColumnInfo(index = true)
    val uriString: String = "",
    val lastModifiedEpochTime: Long = -1,
    val durationMS: Int = -1
)
