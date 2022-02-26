/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.repository

import androidx.room.Database
import androidx.room.RoomDatabase
import de.moleman1024.audiowagon.repository.dao.AlbumDAO
import de.moleman1024.audiowagon.repository.dao.ArtistDAO
import de.moleman1024.audiowagon.repository.dao.TrackDAO
import de.moleman1024.audiowagon.repository.entities.*

@Database(
    entities = [Album::class, Artist::class, Track::class, AlbumFTS::class, ArtistFTS::class, TrackFTS::class],
    exportSchema = true,
    version = 1
)
abstract class AudioItemDatabase : RoomDatabase() {
    abstract fun albumDAO() : AlbumDAO
    abstract fun artistDAO() : ArtistDAO
    abstract fun trackDAO(): TrackDAO
}
