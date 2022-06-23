/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.repository.entities

import androidx.room.*

// no foreign key constraints here, in case parent artist cannot be determined
@Entity
data class Album(
    @PrimaryKey(autoGenerate = true)
    val albumId: Long = 0,
    @ColumnInfo(index = true)
    val name: String = "",
    @ColumnInfo(index = true)
    val sortName: String = "",
    @ColumnInfo(index = true)
    val parentArtistId: Long = -1,
    // The URI for the content provider to retrieve the album art
    val albumArtURIString: String = "",
    // The source of the album art. Can point to an audiofile (embedded album art) or to a .jpg in a directory
    val albumArtSourceURIString: String = "",
    // If the source of the album art is an image file, this will be true
    val hasFolderImage: Boolean = false
)
