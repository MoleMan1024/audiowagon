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
import de.moleman1024.audiowagon.repository.entities.AlbumGroup

@Dao
interface AlbumDAO {

    @Insert
    fun insert(album: Album): Long

    @Insert
    fun insertGroup(albumGroup: AlbumGroup): Long

    @Query("SELECT * FROM album ORDER BY COALESCE(NULLIF(sortName,''), name) COLLATE NOCASE ASC")
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

    @Query(
        "SELECT * FROM album WHERE albumId IN (SELECT albumId FROM album ORDER BY " +
                "COALESCE(NULLIF(sortName,''), name) COLLATE NOCASE ASC LIMIT " +
                ":maxNumRows OFFSET :offsetRows) ORDER BY COALESCE(NULLIF(sortName,''), name) COLLATE NOCASE ASC"
    )
    fun queryAlbumsLimitOffset(maxNumRows: Int, offsetRows: Int): List<Album>

    @Query(
        "SELECT * FROM album WHERE parentArtistId = :artistId ORDER BY " +
                "COALESCE(NULLIF(sortName,''), name) COLLATE NOCASE ASC LIMIT :maxNumRows " +
                "OFFSET :offsetRows"
    )
    fun queryAlbumsForArtistLimitOffset(maxNumRows: Int, offsetRows: Int, artistId: Long): List<Album>

    @Query("SELECT COUNT(*) FROM album WHERE parentArtistId = :artistId")
    fun queryNumAlbumsByArtist(artistId: Long): Int

    @Query("SELECT COUNT(*) FROM album")
    fun queryNumAlbums(): Int

    @Query("SELECT * FROM albumGroup WHERE albumGroupIndex = :groupIndex")
    fun queryAlbumGroupByIndex(groupIndex: Int): AlbumGroup?

    @Query("SELECT * FROM album WHERE albumArtURIString = :uriString")
    fun queryAlbumByAlbumArt(uriString: String): Album?

    @Query(
        "SELECT DISTINCT album.* FROM album " +
                "JOIN albumfts ON album.name = albumfts.name " +
                "WHERE albumfts MATCH :query " +
                "ORDER BY COALESCE(NULLIF(album.sortName,''), album.name) COLLATE NOCASE ASC " +
                "LIMIT $MAX_DATABASE_SEARCH_ROWS"
    )
    fun search(query: String): List<Album>

    @Query(
        "SELECT DISTINCT album.* FROM album " +
                "JOIN albumfts ON album.name = albumfts.name " +
                "JOIN artist ON album.parentArtistId = artist.artistId " +
                "JOIN artistfts ON artist.name = artistfts.name " +
                "WHERE albumfts MATCH :album AND artistfts MATCH :artist " +
                "ORDER BY COALESCE(NULLIF(album.sortName,''), album.name) COLLATE NOCASE ASC " +
                "LIMIT $MAX_DATABASE_SEARCH_ROWS"
    )
    fun searchWithArtist(album: String, artist: String): List<Album>

    @Query("DELETE FROM album WHERE albumId = :albumId")
    fun deleteByID(albumId: Long)

    @Query("DELETE FROM albumGroup")
    fun deleteAllAlbumGroups()

}
