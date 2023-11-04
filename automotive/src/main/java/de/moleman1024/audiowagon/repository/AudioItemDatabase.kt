/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.repository

import androidx.room.*
import de.moleman1024.audiowagon.repository.dao.*
import de.moleman1024.audiowagon.repository.entities.*

@Database(
    entities = [Album::class, Artist::class, Track::class, Path::class, AlbumFTS::class, ArtistFTS::class,
        TrackFTS::class, PathFTS::class, AlbumGroup::class, ArtistGroup::class, TrackGroup::class,
        Status::class],
    exportSchema = true,
    version = 3,
    autoMigrations = [AutoMigration(from = 2, to = 3)]
)
abstract class AudioItemDatabase : RoomDatabase() {
    abstract fun albumDAO(): AlbumDAO
    abstract fun artistDAO(): ArtistDAO
    abstract fun trackDAO(): TrackDAO
    abstract fun pathDAO(): PathDAO
    abstract fun statusDAO(): StatusDAO
}
