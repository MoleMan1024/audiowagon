/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.repository.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import de.moleman1024.audiowagon.repository.MAX_DATABASE_SEARCH_ROWS
import de.moleman1024.audiowagon.repository.entities.Track
import de.moleman1024.audiowagon.repository.entities.TrackGroup

@Dao
interface TrackDAO {

    @Insert
    fun insert(track: Track): Long

    @Insert
    fun insertGroup(trackGroup: TrackGroup): Long

    @Query("SELECT * FROM track ORDER BY COALESCE(NULLIF(sortName,''), name) COLLATE NOCASE ASC")
    fun queryAll(): List<Track>

    @Query(
        "SELECT * FROM track as t1 " +
                "JOIN (SELECT trackId FROM track ORDER BY RANDOM() LIMIT :maxNumItems) as t2 " +
                "ON t1.trackId = t2.trackId"
    )
    fun queryRandom(maxNumItems: Int): List<Track>

    @Query("SELECT * FROM track WHERE uriString = :uri ORDER BY COALESCE(NULLIF(sortName,''), name) COLLATE NOCASE ASC")
    fun queryByURI(uri: String): Track?

    @Query("SELECT trackId FROM track WHERE uriString = :uri")
    fun queryIDByURI(uri: String): Long?

    @Query("SELECT * FROM track WHERE trackId = :trackId ORDER BY COALESCE(NULLIF(sortName,''), name) COLLATE NOCASE ASC")
    fun queryByID(trackId: Long): Track?

    @Query("SELECT * FROM track WHERE parentAlbumId = :albumId ORDER BY discNum ASC, trackNum ASC")
    fun queryTracksByAlbum(albumId: Long): List<Track>

    @Query(
        "SELECT * FROM track WHERE parentArtistId = :artistId OR parentAlbumArtistId = :artistId ORDER BY COALESCE" +
                "(NULLIF(sortName,''), name) COLLATE NOCASE ASC"
    )
    fun queryTracksByArtist(artistId: Long): List<Track>

    @Query(
        "SELECT * FROM track WHERE (parentArtistId = :artistId OR parentAlbumArtistId = :artistId) " +
                "AND parentAlbumId = :albumId ORDER BY COALESCE(NULLIF(sortName,''), name) COLLATE NOCASE ASC"
    )
    fun queryTracksByArtistAndAlbum(artistId: Long, albumId: Long): List<Track>

    @Query(
        "SELECT * FROM track WHERE (parentArtistId = :artistId OR parentAlbumArtistId = :artistId) " +
                "AND parentAlbumId = -1 ORDER BY COALESCE(NULLIF(sortName,''), name) COLLATE NOCASE ASC"
    )
    fun queryTracksByArtistWhereAlbumUnknown(artistId: Long): List<Track>

    @Query(
        "SELECT * FROM track WHERE parentAlbumId = -1 ORDER BY COALESCE(NULLIF(sortName,''), name) COLLATE NOCASE ASC"
    )
    fun queryTracksWhereAlbumUnknown(): List<Track>

    @Query(
        "SELECT * FROM track WHERE parentArtistId = -1 ORDER BY " +
                "COALESCE(NULLIF(sortName,''), name) COLLATE NOCASE ASC"
    )
    fun queryTracksArtistUnknown(): List<Track>

    @Query(
        "SELECT * FROM track WHERE parentAlbumId = -1 ORDER BY " +
                "COALESCE(NULLIF(sortName,''), name) COLLATE NOCASE ASC"
    )
    fun queryTracksAlbumUnknown(): List<Track>

    @Query(
        "SELECT * FROM track WHERE trackId IN (SELECT trackId FROM track ORDER BY " +
                "COALESCE(NULLIF(sortName,''), name) COLLATE NOCASE ASC LIMIT " +
                ":maxNumRows OFFSET :offsetRows) ORDER BY COALESCE(NULLIF(sortName,''), name) COLLATE NOCASE ASC"
    )
    fun queryTracksLimitOffset(maxNumRows: Int, offsetRows: Int): List<Track>

    @Query(
        "SELECT * FROM track WHERE parentArtistId = :artistId AND parentAlbumId = :albumId ORDER BY " +
                "COALESCE(NULLIF(sortName,''), name) COLLATE " +
                "NOCASE ASC LIMIT :maxNumRows OFFSET :offsetRows"
    )
    fun queryTracksForArtistAlbumLimitOffset(
        maxNumRows: Int, offsetRows: Int, artistId: Long, albumId: Long
    ): List<Track>

    @Query(
        "SELECT * FROM track WHERE parentArtistId = :artistId ORDER BY " +
                "COALESCE(NULLIF(sortName,''), name) COLLATE NOCASE ASC LIMIT :maxNumRows " +
                "OFFSET :offsetRows"
    )
    fun queryTracksForArtistLimitOffset(maxNumRows: Int, offsetRows: Int, artistId: Long): List<Track>

    @Query(
        "SELECT * FROM track WHERE parentAlbumId = :albumId ORDER BY " +
                "COALESCE(NULLIF(sortName,''), name) COLLATE NOCASE ASC LIMIT :maxNumRows " +
                "OFFSET :offsetRows"
    )
    fun queryTracksForAlbumLimitOffset(maxNumRows: Int, offsetRows: Int, albumId: Long): List<Track>

    @Query("SELECT * FROM track WHERE albumArtURIString = :uriString")
    fun queryTrackByAlbumArt(uriString: String): Track?

    @Query("SELECT * FROM track WHERE parentAlbumId = :albumId ORDER BY trackNum LIMIT 1")
    fun querySingleTrackOnAlbum(albumId: Long): Track?

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

    @Query("SELECT COUNT(*) FROM track WHERE parentAlbumArtistId = :artistId")
    fun queryNumTracksForAlbumArtist(artistId: Long): Int

    @Query("SELECT COUNT(DISTINCT parentAlbumId) FROM track WHERE parentArtistId = :artistId AND parentAlbumId > -1")
    fun queryNumAlbumsByTrackArtist(artistId: Long): Int

    @Query("SELECT COUNT(DISTINCT parentAlbumId) FROM track WHERE parentAlbumArtistId = :artistId AND parentAlbumId > -1")
    fun queryNumAlbumsByTrackAlbumArtist(artistId: Long): Int

    @Query(
        "SELECT COUNT(DISTINCT trackId) FROM track, album WHERE track.parentAlbumId = album.albumId " +
                "AND album.parentArtistId = :artistId"
    )
    fun queryNumTracksForArtistViaAlbums(artistId: Long): Int

    @Query("SELECT COUNT(*) FROM track")
    fun queryNumTracks(): Int

    @Query("SELECT * FROM trackGroup WHERE trackGroupIndex = :groupIndex")
    fun queryTrackGroupByIndex(groupIndex: Int): TrackGroup?

    @Query(
        "SELECT COUNT(*) FROM track WHERE parentArtistId = :artistId AND parentAlbumId = :albumId ORDER BY " +
                "COALESCE(NULLIF(sortName,''), name) " +
                "COLLATE NOCASE ASC"
    )
    fun queryNumTracksByArtistAndAlbum(artistId: Long, albumId: Long): Int

    @Query(
        "SELECT COUNT(*) FROM track " +
                "WHERE parentAlbumArtistId = :artistId AND parentAlbumId = :albumId " +
                "ORDER BY COALESCE(NULLIF(sortName,''), name) COLLATE NOCASE ASC"
    )
    fun queryNumTracksByAlbumArtistAndAlbum(artistId: Long, albumId: Long): Int

    @Query(
        "SELECT track.* FROM track " +
                "JOIN trackfts ON track.name = trackfts.name " +
                "WHERE trackfts MATCH :query " +
                "ORDER BY COALESCE(NULLIF(track.sortName,''), track.name) COLLATE NOCASE ASC " +
                "LIMIT $MAX_DATABASE_SEARCH_ROWS"
    )
    fun search(query: String): List<Track>

    @Query(
        "SELECT DISTINCT track.* FROM track " +
                "JOIN trackfts ON track.name = trackfts.name " +
                "JOIN artist ON (track.parentArtistId = artist.artistId OR track.parentAlbumArtistId = artist.artistId) " +
                "JOIN artistfts ON artist.name = artistfts.name " +
                "WHERE trackfts MATCH :track AND artistfts MATCH :artist " +
                "ORDER BY COALESCE(NULLIF(track.sortName,''), track.name) COLLATE NOCASE ASC " +
                "LIMIT $MAX_DATABASE_SEARCH_ROWS"
    )
    fun searchWithArtist(track: String, artist: String): List<Track>

    @Query(
        "SELECT DISTINCT track.* FROM track " +
                "JOIN trackfts ON track.name = trackfts.name " +
                "JOIN album ON track.parentAlbumId = album.albumId " +
                "JOIN albumfts ON album.name = albumfts.name " +
                "WHERE trackfts MATCH :track AND albumfts MATCH :album " +
                "ORDER BY COALESCE(NULLIF(track.sortName,''), track.name) COLLATE NOCASE ASC " +
                "LIMIT $MAX_DATABASE_SEARCH_ROWS"
    )
    fun searchWithAlbum(track: String, album: String): List<Track>

    @Query(
        "SELECT DISTINCT track.* FROM track " +
                "JOIN artist ON (track.parentArtistId = artist.artistId OR track.parentAlbumArtistId = artist.artistId) " +
                "JOIN artistfts ON artist.name = artistfts.name " +
                "JOIN album ON track.parentAlbumId = album.albumId " +
                "JOIN albumfts ON album.name = albumfts.name " +
                "WHERE artistfts MATCH :artist AND albumfts MATCH :album " +
                "ORDER BY discNum ASC, trackNum ASC, COALESCE(NULLIF(track.sortName,''), track.name) COLLATE NOCASE ASC " +
                "LIMIT $MAX_DATABASE_SEARCH_ROWS"
    )
    fun searchTracksByAlbumAndArtist(album: String, artist: String): List<Track>

    @Query("UPDATE track SET parentAlbumId = :newAlbumId WHERE parentAlbumId = :oldAlbumId")
    fun replaceAlbumID(oldAlbumId: Long, newAlbumId: Long)

    @Delete
    fun delete(track: Track)

    @Query("DELETE FROM track WHERE trackId = :trackId")
    fun deleteByID(trackId: Long)

    @Query("DELETE FROM trackGroup")
    fun deleteAllTrackGroups()

}
