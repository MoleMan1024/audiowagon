/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.repository.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import de.moleman1024.audiowagon.repository.MAX_DATABASE_SEARCH_ROWS
import de.moleman1024.audiowagon.repository.entities.Artist

@Dao
interface ArtistDAO {

    @Insert
    fun insert(artist: Artist): Long

    @Query("SELECT * FROM artist ORDER BY name COLLATE NOCASE ASC")
    fun queryAll(): List<Artist>

    @Query("SELECT * FROM artist WHERE artistId = :artistId")
    fun queryByID(artistId: Long): Artist?

    @Query("SELECT * FROM artist WHERE name = :name")
    fun queryByName(name: String): Artist?

    @Query(
        "SELECT * FROM artist WHERE artistId IN (SELECT artistId FROM artist ORDER BY name COLLATE NOCASE ASC " +
            "LIMIT :maxNumRows OFFSET :offsetRows)"
    )
    fun queryArtistsLimitOffset(maxNumRows: Int, offsetRows: Int): List<Artist>

    @Query("SELECT COUNT(*) FROM artist")
    fun queryNumArtists(): Int

    @Query("SELECT * FROM artist JOIN artistfts ON artist.name = artistfts.name WHERE artistfts MATCH :query " +
            "LIMIT $MAX_DATABASE_SEARCH_ROWS")
    fun search(query: String): List<Artist>

    @Query("DELETE FROM artist where artistId = :artistId")
    fun deleteByID(artistId: Long)
}
