/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.repository.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import de.moleman1024.audiowagon.repository.MAX_DATABASE_SEARCH_ROWS
import de.moleman1024.audiowagon.repository.entities.Album
import de.moleman1024.audiowagon.repository.entities.AlbumFTS

@Dao
interface AlbumDAO {

    @Insert
    fun insert(album: Album): Long

    @Query("SELECT * FROM album ORDER BY name COLLATE NOCASE ASC")
    fun queryAll(): List<Album>

    @Query("SELECT * FROM album WHERE albumId = :albumId")
    fun queryByID(albumId: Long): Album?

    @Query("SELECT * FROM album WHERE name = :name")
    fun queryByName(name: String): Album?

    @Query("SELECT * FROM album WHERE parentArtistId = :artistId")
    fun queryByArtist(artistId: Long): List<Album>

    @Query("SELECT * FROM album WHERE name = :name AND parentArtistId = :artistId")
    fun queryByNameAndArtist(name: String, artistId: Long): Album?

    @Query("SELECT * FROM album WHERE parentArtistId = :artistId")
    fun queryAlbumsByArtist(artistId: Long): List<Album>

    @Query("SELECT * FROM album WHERE albumId IN (SELECT albumId FROM album ORDER BY name COLLATE NOCASE ASC LIMIT " +
            ":maxNumRows OFFSET :offsetRows)")
    fun queryAlbumsLimitOffset(maxNumRows: Int, offsetRows: Int): List<Album>

    @Query("SELECT * FROM album WHERE parentArtistId = :artistId ORDER BY name COLLATE NOCASE ASC LIMIT :maxNumRows " +
            "OFFSET :offsetRows")
    fun queryAlbumsForArtistLimitOffset(maxNumRows: Int, offsetRows: Int, artistId: Long): List<Album>

    @Query("SELECT COUNT(*) FROM album WHERE parentArtistId = :artistId")
    fun queryNumAlbumsByArtist(artistId: Long): Int

    @Query("SELECT COUNT(*) FROM album")
    fun queryNumAlbums(): Int

    @Query("SELECT * FROM album JOIN albumfts ON album.name = albumfts.name WHERE albumfts MATCH :query " +
            "LIMIT $MAX_DATABASE_SEARCH_ROWS")
    fun search(query: String): List<Album>

    @Query("DELETE FROM album WHERE albumId = :albumId")
    fun deleteByID(albumId: Long)

}
