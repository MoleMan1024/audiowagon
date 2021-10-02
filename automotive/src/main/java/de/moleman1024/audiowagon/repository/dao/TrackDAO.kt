/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.repository.dao

import androidx.room.*
import de.moleman1024.audiowagon.repository.MAX_DATABASE_SEARCH_ROWS
import de.moleman1024.audiowagon.repository.entities.Track
import de.moleman1024.audiowagon.repository.entities.TrackFTS

@Dao
interface TrackDAO {

    @Insert
    fun insert(track: Track): Long

    @Query("SELECT * FROM track ORDER BY name COLLATE NOCASE ASC")
    fun queryAll(): List<Track>

    @Query("SELECT * FROM track ORDER BY random() LIMIT :maxNumItems")
    fun queryRandom(maxNumItems: Int): List<Track>

    @Query("SELECT * FROM track WHERE uriString = :uri ORDER BY name COLLATE NOCASE ASC")
    fun queryByURI(uri: String): Track?

    @Query("SELECT trackId FROM track WHERE uriString = :uri")
    fun queryIDByURI(uri: String): Long?

    @Query("SELECT * FROM track WHERE trackId = :trackId ORDER BY name COLLATE NOCASE ASC")
    fun queryByID(trackId: Long): Track?

    @Query("SELECT * FROM track WHERE parentAlbumId = :albumId ORDER BY indexOnAlbum ASC")
    fun queryTracksByAlbum(albumId: Long): List<Track>

    @Query("SELECT * FROM track WHERE parentArtistId = :artistId ORDER BY name COLLATE NOCASE ASC")
    fun queryTracksByArtist(artistId: Long): List<Track>

    @Query("SELECT * FROM track WHERE parentArtistId = :artistId AND parentAlbumId = :albumId ORDER BY name COLLATE " +
            "NOCASE ASC")
    fun queryTracksByArtistAndAlbum(artistId: Long, albumId: Long): List<Track>

    @Query("SELECT * FROM track WHERE parentArtistId = :artistId AND parentAlbumId = -1 ORDER BY name COLLATE NOCASE " +
            "ASC")
    fun queryTracksByArtistWhereAlbumUnknown(artistId: Long): List<Track>

    @Query("SELECT * FROM track WHERE parentArtistId = -1 ORDER BY name COLLATE NOCASE ASC")
    fun queryTracksArtistUnknown(): List<Track>

    @Query("SELECT * FROM track WHERE parentAlbumId = -1 ORDER BY name COLLATE NOCASE ASC")
    fun queryTracksAlbumUnknown(): List<Track>

    @Query("SELECT * FROM track ORDER BY name COLLATE NOCASE ASC LIMIT :maxNumRows OFFSET :offsetRows")
    fun queryTracksLimitOffset(maxNumRows: Int, offsetRows: Int): List<Track>

    @Query("SELECT * FROM track WHERE parentArtistId = :artistId AND parentAlbumId = :albumId ORDER BY name COLLATE " +
            "NOCASE ASC LIMIT :maxNumRows OFFSET :offsetRows"
    )
    fun queryTracksForArtistAlbumLimitOffset(
        maxNumRows: Int, offsetRows: Int, artistId: Long, albumId: Long
    ): List<Track>

    @Query("SELECT * FROM track WHERE parentArtistId = :artistId ORDER BY name COLLATE NOCASE ASC LIMIT :maxNumRows " +
            "OFFSET :offsetRows")
    fun queryTracksForArtistLimitOffset(maxNumRows: Int, offsetRows: Int, artistId: Long): List<Track>

    @Query("SELECT * FROM track WHERE parentAlbumId = :albumId ORDER BY name COLLATE NOCASE ASC LIMIT :maxNumRows " +
            "OFFSET :offsetRows")
    fun queryTracksForAlbumLimitOffset(maxNumRows: Int, offsetRows: Int, albumId: Long): List<Track>

    @Query("SELECT COUNT(*) FROM track WHERE parentArtistId = :artistId AND parentAlbumId = -1")
    fun queryNumTracksByArtistAlbumUnkn(artistId: Long): Int

    @Query("SELECT COUNT(*) FROM track WHERE parentArtistId = :artistId AND parentAlbumId = :albumId")
    fun queryNumTracksByArtistForAlbum(artistId: Long, albumId: Long): Int

    @Query("SELECT COUNT(*) FROM track WHERE parentArtistId = -1")
    fun queryNumTracksArtistUnknown(): Int

    @Query("SELECT COUNT(*) FROM track WHERE parentAlbumId = -1")
    fun queryNumTracksAlbumUnknown(): Int

    @Query("SELECT COUNT(*) FROM track WHERE parentAlbumId = :albumId")
    fun queryNumTracksForAlbum(albumId: Long): Int

    @Query("SELECT COUNT(*) FROM track WHERE parentArtistId = :artistId")
    fun queryNumTracksForArtist(artistId: Long): Int

    @Query("SELECT COUNT(*) FROM track")
    fun queryNumTracks(): Int

    @Query("SELECT COUNT(*) FROM track WHERE parentArtistId = :artistId AND parentAlbumId = :albumId ORDER BY name " +
            "COLLATE NOCASE ASC")
    fun queryNumTracksByArtistAndAlbum(artistId: Long, albumId: Long): Int

    @Query("SELECT * FROM track JOIN trackfts ON track.name = trackfts.name WHERE trackfts MATCH :query " +
            "LIMIT $MAX_DATABASE_SEARCH_ROWS")
    fun search(query: String): List<Track>

    @Delete
    fun delete(track: Track)

    @Query("DELETE FROM track WHERE trackId = :trackId")
    fun deleteByID(trackId: Long)

}
