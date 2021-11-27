/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.repository

import android.content.Context
import android.media.browse.MediaBrowser.MediaItem.FLAG_BROWSABLE
import android.media.browse.MediaBrowser.MediaItem.FLAG_PLAYABLE
import android.net.Uri
import androidx.room.Room
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.filestorage.AudioFile
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyElement
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyID
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyType
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.DATABASE_ID_UNKNOWN
import de.moleman1024.audiowagon.repository.entities.Album
import de.moleman1024.audiowagon.repository.entities.Artist
import de.moleman1024.audiowagon.repository.entities.Track
import kotlinx.coroutines.*
import java.util.*

const val MAX_DATABASE_SEARCH_ROWS = 10
const val AUDIOITEM_REPO_DB_PREFIX = "audioItemRepo_"
private const val TAG = "AudioItemRepo"
private val logger = Logger

/**
 * See https://developer.android.com/training/data-storage/room
 */
class AudioItemRepository(
    private val storageID: String,
    val context: Context,
    private val dispatcher: CoroutineDispatcher
) {
    // The database file is created in internal storage only after the first row has been added to it
    private val databaseName = "$AUDIOITEM_REPO_DB_PREFIX${storageID}.sqlite"
    // TODO: this is not ideal, I would rather like to store the database on the USB flash drive to reduce wear on
    //  the internal flash memory, but found no easy way to do that
    // SQL query logging can be added via setQueryCallback()
    // TODO: https://developer.android.com/training/data-storage/app-specific#query-free-space
    //  I tried with 160 GB of music which resulted in a database of ~15 megabytes
    private var database: AudioItemDatabase = Room.databaseBuilder(
        context,
        AudioItemDatabase::class.java,
        databaseName
    ).build()
    private var pseudoCompilationArtistID: Long? = null
    val trackIDsToKeep = mutableListOf<Long>()
    private var isClosed: Boolean = false

    init {
        isClosed = false
    }

    fun close() {
        if (isClosed) {
            return
        }
        isClosed = true
        database.close()
    }

    suspend fun getTrack(id: Long): AudioItem {
        val track: Track = withContext(dispatcher) {
            database.trackDAO().queryByID(id) ?: throw RuntimeException("No track for id: $id")
        }
        return createAudioItemForTrack(track)
    }

    suspend fun getAlbumsForArtist(artistID: Long): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val albums: List<Album> = withContext(dispatcher) {
            database.albumDAO().queryAlbumsByArtist(artistID)
        }
        for (album in albums) {
            if (isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val audioItemAlbum: AudioItem = createAudioItemForAlbum(album, artistID)
            items += audioItemAlbum
        }
        val numTracksWithoutAlbum: Int = withContext(dispatcher) {
            database.trackDAO().queryNumTracksByArtistAlbumUnkn(artistID)
        }
        if (numTracksWithoutAlbum > 0) {
            logger.debug(TAG, "Artist $artistID has $numTracksWithoutAlbum tracks without album info")
            val unknownAlbum: AudioItem = createUnknAlbumAudioItemForArtist(artistID)
            items.add(0, unknownAlbum)
        }
        val compilationAlbums: List<Album> = getCompilationAlbumsForArtist(artistID)
        for (album in compilationAlbums) {
            if (isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val audioItemAlbum: AudioItem = createAudioItemForAlbum(album, artistID)
            audioItemAlbum.isInCompilation = true
            val contentHierarchyCompilation = ContentHierarchyID(ContentHierarchyType.COMPILATION)
            contentHierarchyCompilation.storageID = storageID
            contentHierarchyCompilation.albumID = album.albumId
            contentHierarchyCompilation.artistID = artistID
            audioItemAlbum.id = ContentHierarchyElement.serialize(contentHierarchyCompilation)
            items += audioItemAlbum
        }
        logger.debug(TAG, "Returning ${items.size} albums for artist $artistID")
        return items
    }

    suspend fun getTracksForAlbum(albumID: Long): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val tracks: List<Track> = database.trackDAO().queryTracksByAlbum(albumID)
        for (track in tracks) {
            if (isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val audioItemTrack: AudioItem = createAudioItemForTrack(track, albumID = albumID)
            items += audioItemTrack
        }
        logger.debug(TAG, "Returning ${items.size} tracks for album $albumID")
        return items
    }

    suspend fun getTracksForArtist(artistID: Long): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val tracks: List<Track> = database.trackDAO().queryTracksByArtist(artistID)
        for (track in tracks) {
            if (isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val audioItemTrack: AudioItem = createAudioItemForTrack(track, artistID = artistID)
            items += audioItemTrack
        }
        logger.debug(TAG, "Returning ${items.size} tracks for artist $artistID")
        return items
    }

    suspend fun getTracksForAlbumAndArtist(albumID: Long, artistID: Long): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val tracks: List<Track> = database.trackDAO().queryTracksByArtistAndAlbum(artistID, albumID)
        for (track in tracks) {
            if (isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val audioItemTrack: AudioItem = createAudioItemForTrack(track, artistID, albumID)
            items += audioItemTrack
        }
        logger.debug(TAG, "Returning ${items.size} tracks for album $albumID and artist $artistID")
        return if (albumID != DATABASE_ID_UNKNOWN) {
            items.sortedBy { it.trackNum }
        } else {
            items.sortedBy { it.title.lowercase() }
        }
    }

    suspend fun getNumTracksForAlbumAndArtist(albumID: Long, artistID: Long) = withContext(dispatcher) {
        database.trackDAO().queryNumTracksByArtistAndAlbum(artistID, albumID)
    }

    @Suppress("RedundantSuspendModifier")
    suspend fun getNumTracksForArtist(artistID: Long): Int {
        return if (artistID != getPseudoCompilationArtistID()) {
            database.trackDAO().queryNumTracksForArtist(artistID)
        } else {
            database.trackDAO().queryNumTracksForArtistViaAlbums(artistID)
        }
    }

    @Suppress("RedundantSuspendModifier")
    suspend fun getNumAlbumsBasedOnTracksArtist(artistID: Long): Int {
        return if (artistID != getPseudoCompilationArtistID()) {
            database.trackDAO().queryNumAlbumsByTrackArtist(artistID)
        } else {
            database.albumDAO().queryNumAlbumsByArtist(artistID)
        }
    }

    suspend fun getTracksWithUnknAlbumForArtist(artistID: Long): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val tracks: List<Track> = database.trackDAO().queryTracksByArtistWhereAlbumUnknown(artistID)
        for (track in tracks) {
            if (isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val audioItemTrack: AudioItem = createAudioItemForTrack(track, artistID, DATABASE_ID_UNKNOWN)
            items += audioItemTrack
        }
        logger.debug(TAG, "Returning ${items.size} tracks for unknown album for artist $artistID")
        return items
    }

    suspend fun getNumTracksWithUnknAlbumForArtist(artistID: Long) = withContext(dispatcher) {
        database.trackDAO().queryNumTracksByArtistAndAlbum(artistID, DATABASE_ID_UNKNOWN)
    }

    fun getDatabaseIDForTrack(audioFile: AudioFile): Long? {
        return database.trackDAO().queryIDByURI(audioFile.uri.toString())
    }

    /**
     * Adds a new track entry into database. Also inserts new artist, album database entry if those are new.
     */
    fun populateDatabaseFrom(audioFile: AudioFile, audioItem: AudioItem) {
        var artistID: Long = -1
        var albumArtistID: Long = -1
        var doCreateArtistInDB = true
        if (audioItem.artist.isNotBlank()) {
            // multiple artists with same name are unlikely, ignore this case
            val artistInDB: Artist? = database.artistDAO().queryByName(audioItem.artist)
            if (artistInDB != null) {
                artistID = artistInDB.artistId
            }
        }
        if (audioItem.albumArtist.isNotBlank()) {
            if (audioItem.albumArtist == audioItem.artist) {
                albumArtistID = artistID
            } else {
                // this will support album artists https://github.com/MoleMan1024/audiowagon/issues/22
                // (these are not considered compilations, the album artist is treated as the "main" artist)
                if (!audioItem.isInCompilation) {
                    doCreateArtistInDB = false
                    val albumArtistInDB: Artist? = database.artistDAO().queryByName(audioItem.albumArtist)
                    albumArtistID = albumArtistInDB?.artistId ?: database.artistDAO().insert(
                        Artist(name = audioItem.albumArtist)
                    )
                }
            }
        }
        if (artistID <= -1 && doCreateArtistInDB && audioItem.artist.isNotBlank()) {
            artistID = database.artistDAO().insert(Artist(name = audioItem.artist))
        }
        if (albumArtistID <= -1) {
            albumArtistID = artistID
        }
        var albumID: Long = -1
        if (audioItem.album.isNotBlank()) {
            // Watch out for special cases for albums:
            // - same album name across several artists, e.g. "Greatest Hits" albums
            // - same album name for multiple artists could also be a compilation/various artists album
            if (audioItem.isInCompilation) {
                albumArtistID = makePseudoCompilationArtistID()
            }
            val albumInDB: Album? = database.albumDAO().queryByNameAndArtist(audioItem.album, albumArtistID)
            albumID = albumInDB?.albumId ?: database.albumDAO()
                .insert(Album(name = audioItem.album, parentArtistId = albumArtistID))
        }
        val track = Track(
            name = audioItem.title,
            parentArtistId = if (albumArtistID > -1 && !audioItem.isInCompilation) albumArtistID else artistID,
            parentAlbumId = albumID,
            indexOnAlbum = audioItem.trackNum,
            yearEpochTime = yearShortToEpochTime(audioItem.year),
            // genre is not used by the app, do not store it (if we ever use it, create a database table to avoid
            // duplicate strings)
            genre = "",
            uriString = audioFile.uri.toString(),
            lastModifiedEpochTime = audioFile.lastModifiedDate.time,
            durationMS = audioItem.durationMS,
            // TODO: I mis-use this column to store the real artist name in case it does not match the album artist
            //  (should not happen that often, should be fine as a string for now)
            albumArtURIString = if (!doCreateArtistInDB && audioItem.artist.isNotBlank()) audioItem.artist else ""
        )
        logger.debug(TAG, "Inserting track: $track")
        val trackDatabaseID: Long = database.trackDAO().insert(track)
        trackIDsToKeep.add(trackDatabaseID)
    }

    fun getPseudoCompilationArtistID(): Long? {
        if (pseudoCompilationArtistID != null) {
            return pseudoCompilationArtistID
        }
        val pseudoCompilationArtistName = getPseudoCompilationArtistName()
        val artistInDB: Artist? = database.artistDAO().queryByName(pseudoCompilationArtistName)
        pseudoCompilationArtistID = artistInDB?.artistId
        return pseudoCompilationArtistID
    }

    private fun makePseudoCompilationArtistID(): Long {
        if (pseudoCompilationArtistID != null) {
            return pseudoCompilationArtistID!!
        }
        val pseudoCompilationArtistName = getPseudoCompilationArtistName()
        val artistInDB: Artist? = database.artistDAO().queryByName(pseudoCompilationArtistName)
        val resultID: Long = if (artistInDB?.artistId == null) {
            database.artistDAO().insert(Artist(name = pseudoCompilationArtistName))
        } else {
            artistInDB.artistId
        }
        pseudoCompilationArtistID = resultID
        return resultID
    }

    private fun getPseudoCompilationArtistName(): String {
        return context.getString(R.string.browse_tree_various_artists)
    }

    // TODO: this is probably inefficient
    private fun getCompilationAlbumsForArtist(artistID: Long): List<Album> {
        val pseudoCompilationArtistName = getPseudoCompilationArtistName()
        val variousArtistsInDB: Artist =
            database.artistDAO().queryByName(pseudoCompilationArtistName) ?: return listOf()
        val allCompilationAlbums = database.albumDAO().queryByArtist(variousArtistsInDB.artistId)
        val matchingCompilationAlbums = mutableListOf<Album>()
        for (album in allCompilationAlbums) {
            if (isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val numTracks = database.trackDAO().queryNumTracksByArtistForAlbum(artistID, album.albumId)
            if (numTracks > 0) {
                matchingCompilationAlbums.add(album)
            }
        }
        return matchingCompilationAlbums
    }

    fun hasAudioFileChangedForTrack(audioFile: AudioFile, trackID: Long): Boolean {
        val track =
            database.trackDAO().queryByID(trackID) ?: throw RuntimeException("No track for id: $trackID")
        // ignore the millisecond part in the timestamps
        val trackLastModSec = track.lastModifiedEpochTime / 1000
        val audioFileLastModSec = audioFile.lastModifiedDate.time / 1000
        val audioFileHasChanged = trackLastModSec != audioFileLastModSec
        if (audioFileHasChanged) {
            logger.debug(
                TAG, "AudioFile modification date differs from track in database: " +
                        "track=${track.lastModifiedEpochTime} != audioFile=${audioFile.lastModifiedDate.time}"
            )
        }
        return audioFileHasChanged
    }

    /**
     * Remove all tracks/albums/artists in database that were not added by previous buildLibrary() call
     */
    fun clean() {
        logger.debug(TAG, "Cleaning no longer available items from database")
        val allTracksInDB = database.trackDAO().queryAll()
        val artistDeletionCandidates = mutableSetOf<Long>()
        val albumDeletionCandidates = mutableSetOf<Long>()
        for (track in allTracksInDB) {
            if (track.trackId in trackIDsToKeep) {
                continue
            }
            artistDeletionCandidates.add(track.parentArtistId)
            albumDeletionCandidates.add(track.parentAlbumId)
            logger.debug(TAG, "Removing track from database: $track")
            database.trackDAO().delete(track)
        }
        trackIDsToKeep.clear()
        pruneAlbums(albumDeletionCandidates)
        pruneArtists(artistDeletionCandidates)
    }

    /**
     * Remove albums that no longer have any associated tracks
     */
    private fun pruneAlbums(deletionCandidateIDs: Set<Long>) {
        deletionCandidateIDs.forEach { albumID ->
            val numTracksForAlbum = database.trackDAO().queryNumTracksForAlbum(albumID)
            if (numTracksForAlbum <= 0) {
                logger.debug(TAG, "Removing album from database: $albumID")
                database.albumDAO().deleteByID(albumID)
            }
        }
    }

    /**
     * Remove artists that no longer have any associated tracks
     */
    private fun pruneArtists(deletionCandidateIDs: Set<Long>) {
        deletionCandidateIDs.forEach { artistID ->
            val numTracksForArtist = database.trackDAO().queryNumTracksForArtist(artistID)
            if (numTracksForArtist <= 0) {
                logger.debug(TAG, "Removing artist from database: $artistID")
                database.artistDAO().deleteByID(artistID)
            }
        }
    }

    fun removeTrack(trackID: Long) {
        logger.debug(TAG, "Removing track from database: $trackID")
        database.trackDAO().deleteByID(trackID)
    }

    suspend fun getAllTracks(): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val allTracks: List<Track> = withContext(dispatcher) {
             database.trackDAO().queryAll()
        }
        for (track in allTracks) {
            if (isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val audioItemTrack: AudioItem = createAudioItemForTrack(track)
            items += audioItemTrack
        }
        logger.debug(TAG, "Returning ${items.size} tracks")
        return items
    }

    suspend fun getNumTracks(): Int = withContext(dispatcher) {
        database.trackDAO().queryNumTracks()
    }

    suspend fun getNumTracksForAlbum(albumID: Long): Int = withContext(dispatcher) {
        database.trackDAO().queryNumTracksForAlbum(albumID)
    }

    // TODO: many similar functions in here
    suspend fun getTracksLimitOffset(maxNumRows: Int, offsetRows: Int): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val tracksAtOffset: List<Track> = withContext(dispatcher) {
            database.trackDAO().queryTracksLimitOffset(maxNumRows, offsetRows)
        }
        for (track in tracksAtOffset) {
            if (isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val audioItemTrack: AudioItem = createAudioItemForTrack(track)
            items += audioItemTrack
        }
        logger.debug(TAG, "Returning ${items.size} tracks for offset: $offsetRows (limit $maxNumRows)")
        return items
    }

    suspend fun getTracksForArtistAlbumLimitOffset(
        maxNumRows: Int, offsetRows: Int, artistID: Long, albumID: Long
    ): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val tracksAtOffset: List<Track> = withContext(dispatcher) {
            database.trackDAO().queryTracksForArtistAlbumLimitOffset(maxNumRows, offsetRows, artistID, albumID)
        }
        for (track in tracksAtOffset) {
            if (isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val audioItemTrack: AudioItem = createAudioItemForTrack(track, artistID, albumID)
            items += audioItemTrack
        }
        logger.debug(TAG, "Returning ${items.size} tracks for artist $artistID, album $albumID offset: $offsetRows " +
                "(limit $maxNumRows)")
        return items
    }

    suspend fun getTracksForAlbumLimitOffset(maxNumRows: Int, offsetRows: Int, albumID: Long): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val tracksAtOffset: List<Track> = withContext(dispatcher) {
            database.trackDAO().queryTracksForAlbumLimitOffset(maxNumRows, offsetRows, albumID)
        }
        for (track in tracksAtOffset) {
            if (isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val audioItemTrack: AudioItem = createAudioItemForTrack(track, albumID = albumID)
            items += audioItemTrack
        }
        logger.debug(TAG, "Returning ${items.size} tracks for album $albumID offset: $offsetRows " +
                "(limit $maxNumRows)")
        return items
    }

    suspend fun getTracksForArtistLimitOffset(maxNumRows: Int, offsetRows: Int, artistID: Long): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val tracksAtOffset: List<Track> = withContext(dispatcher) {
            database.trackDAO().queryTracksForArtistLimitOffset(maxNumRows, offsetRows, artistID)
        }
        for (track in tracksAtOffset) {
            if (isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val audioItemTrack: AudioItem = createAudioItemForTrack(track, artistID = artistID)
            items += audioItemTrack
        }
        logger.debug(TAG, "Returning ${items.size} tracks for artist $artistID, offset: $offsetRows " +
                "(limit $maxNumRows)")
        return items
    }

    suspend fun getRandomTracks(maxNumItems: Int): List<AudioItem> {
        if (maxNumItems <= 0) {
            throw IllegalArgumentException("Invalid number of random tracks: $maxNumItems")
        }
        val items: MutableList<AudioItem> = mutableListOf()
        val tracks: List<Track> = database.trackDAO().queryRandom(maxNumItems)
        for (track in tracks) {
            if (isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val audioItemTrack: AudioItem = createAudioItemForTrack(track)
            items += audioItemTrack
        }
        logger.debug(TAG, "Returning ${items.size} random tracks")
        return items
    }

    private suspend fun createAudioItemForTrack(
        track: Track, artistID: Long? = null, albumID: Long? = null
    ): AudioItem {
        val audioItemTrack = AudioItem()
        val contentHierarchyID = ContentHierarchyID(ContentHierarchyType.TRACK)
        contentHierarchyID.trackID = track.trackId
        if (artistID != null) {
            contentHierarchyID.artistID = artistID
        }
        if (albumID != null) {
            contentHierarchyID.albumID = albumID
        }
        contentHierarchyID.storageID = storageID
        audioItemTrack.id = ContentHierarchyElement.serialize(contentHierarchyID)
        audioItemTrack.uri = Uri.parse(track.uriString)
        if (track.parentArtistId >= 0) {
            val artistForTrack: Artist? = withContext(dispatcher) {
                database.artistDAO().queryByID(track.parentArtistId)
            }
            artistForTrack?.let { audioItemTrack.artist = it.name }
        }
        audioItemTrack.albumArtist = audioItemTrack.artist
        if (track.albumArtURIString.isNotBlank()) {
            audioItemTrack.artist = track.albumArtURIString
        }
        if (track.parentAlbumId >= 0) {
            val albumForTrack: Album? = withContext(dispatcher) {
                database.albumDAO().queryByID(track.parentAlbumId)
            }
            albumForTrack?.let { audioItemTrack.album = it.name }
        }
        audioItemTrack.title = track.name
        audioItemTrack.genre = track.genre
        audioItemTrack.trackNum = track.indexOnAlbum
        audioItemTrack.year = yearEpochTimeToShort(track.yearEpochTime)
        audioItemTrack.browsPlayableFlags =
            audioItemTrack.browsPlayableFlags.or(FLAG_PLAYABLE)
        audioItemTrack.durationMS = track.durationMS
        return audioItemTrack
    }

    suspend fun getAllAlbums(): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val allAlbums: List<Album> = database.albumDAO().queryAll()
        for (album in allAlbums) {
            if (isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val audioItemAlbum = createAudioItemForAlbum(album)
            items += audioItemAlbum
        }
        val audioItemUnknAlbum = getAudioItemForUnknownAlbum()
        if (audioItemUnknAlbum != null) {
            items += audioItemUnknAlbum
        }
        logger.debug(TAG, "Returning ${items.size} albums")
        return items
    }

    suspend fun getAudioItemForUnknownAlbum(): AudioItem? = withContext(dispatcher) {
        val numTracksUnknownAlbum = database.trackDAO().queryNumTracksAlbumUnknown()
        if (numTracksUnknownAlbum > 0) {
            return@withContext createUnknownAlbumAudioItem()
        }
        return@withContext null
    }

    suspend fun getNumAlbums(): Int = withContext(dispatcher) {
        database.albumDAO().queryNumAlbums()
    }

    suspend fun getAlbumsLimitOffset(maxNumRows: Int, offsetRows: Int): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val albumsAtOffset: List<Album> = withContext(dispatcher) {
            database.albumDAO().queryAlbumsLimitOffset(maxNumRows, offsetRows)
        }
        for (album in albumsAtOffset) {
            if (isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val audioItemTrack: AudioItem = createAudioItemForAlbum(album)
            items += audioItemTrack
        }
        logger.debug(TAG, "Returning ${items.size} albums for offset: $offsetRows (limit $maxNumRows)")
        return items
    }

    suspend fun getAlbumsForArtistLimitOffset(maxNumRows: Int, offsetRows: Int, artistID: Long): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val albumsAtOffset: List<Album> = withContext(dispatcher) {
            database.albumDAO().queryAlbumsForArtistLimitOffset(maxNumRows, offsetRows, artistID)
        }
        for (album in albumsAtOffset) {
            if (isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val audioItemTrack: AudioItem = createAudioItemForAlbum(album, artistID)
            items += audioItemTrack
        }
        logger.debug(TAG, "Returning ${items.size} albums for artist $artistID offset: $offsetRows (limit $maxNumRows)")
        return items
    }

    private suspend fun createAudioItemForAlbum(album: Album, artistID: Long? = null): AudioItem {
        val audioItemAlbum = AudioItem()
        val contentHierarchyID = ContentHierarchyID(ContentHierarchyType.ALBUM)
        contentHierarchyID.storageID = storageID
        contentHierarchyID.albumID = album.albumId
        if (artistID != null) {
            contentHierarchyID.artistID = artistID
        }
        audioItemAlbum.id = ContentHierarchyElement.serialize(contentHierarchyID)
        audioItemAlbum.album = album.name
        val artistForAlbum: Artist? = withContext(dispatcher) {
            database.artistDAO().queryByID(album.parentArtistId)
        }
        artistForAlbum?.let { audioItemAlbum.artist = it.name }
        audioItemAlbum.albumArtist = audioItemAlbum.artist
        audioItemAlbum.browsPlayableFlags = audioItemAlbum.browsPlayableFlags.or(FLAG_BROWSABLE)
        // Setting the FLAG_PLAYABLE additionally here does not do anything in Android Automotive. Instead we add
        // pseudo-"play all" items on each album instead.
        return audioItemAlbum
    }

    @Suppress("RedundantSuspendModifier")
    private suspend fun createAudioItemForArtist(artist: Artist): AudioItem {
        val audioItemArtist = AudioItem()
        val contentHierarchyID = ContentHierarchyID(ContentHierarchyType.ARTIST)
        contentHierarchyID.storageID = storageID
        contentHierarchyID.artistID = artist.artistId
        audioItemArtist.id = ContentHierarchyElement.serialize(contentHierarchyID)
        audioItemArtist.artist = artist.name
        audioItemArtist.browsPlayableFlags = audioItemArtist.browsPlayableFlags.or(FLAG_BROWSABLE)
        return audioItemArtist
    }

    private fun createUnknownAlbumAudioItem(): AudioItem {
        val unknownAlbum = AudioItem()
        val contentHierarchyID = ContentHierarchyID(ContentHierarchyType.UNKNOWN_ALBUM)
        contentHierarchyID.storageID = storageID
        contentHierarchyID.albumID = DATABASE_ID_UNKNOWN
        unknownAlbum.id = ContentHierarchyElement.serialize(contentHierarchyID)
        unknownAlbum.album = context.getString(R.string.browse_tree_unknown_album)
        unknownAlbum.browsPlayableFlags = unknownAlbum.browsPlayableFlags.or(FLAG_BROWSABLE)
        return unknownAlbum
    }

    private suspend fun createUnknAlbumAudioItemForArtist(artistID: Long): AudioItem {
        val unknownAlbum = createUnknownAlbumAudioItem()
        val contentHierarchyID = ContentHierarchyID(ContentHierarchyType.UNKNOWN_ALBUM)
        contentHierarchyID.storageID = storageID
        contentHierarchyID.albumID = DATABASE_ID_UNKNOWN
        contentHierarchyID.artistID = artistID
        unknownAlbum.id = ContentHierarchyElement.serialize(contentHierarchyID)
        val artistForAlbum: Artist? = withContext(dispatcher) {
            database.artistDAO().queryByID(artistID)
        }
        artistForAlbum?.let { unknownAlbum.artist = it.name }
        return unknownAlbum
    }

    suspend fun getAllArtists(): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val allArtists: List<Artist> = database.artistDAO().queryAll()
        for (artist in allArtists) {
            if (isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val audioItemArtist = createAudioItemForArtist(artist)
            items += audioItemArtist
        }
        val audioItemUnknArtist = getAudioItemForUnknownArtist()
        if (audioItemUnknArtist != null) {
            items += audioItemUnknArtist
        }
        logger.debug(TAG, "Returning ${allArtists.size} artists" + if (audioItemUnknArtist != null) " and 'unknown " +
                "artist'" else "")
        return items
    }

    suspend fun getNumArtists(): Int = withContext(dispatcher) {
        database.artistDAO().queryNumArtists()
    }

    suspend fun getArtistsLimitOffset(maxNumRows: Int, offsetRows: Int): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val artistsAtOffset: List<Artist> = withContext(dispatcher) {
            database.artistDAO().queryArtistsLimitOffset(maxNumRows, offsetRows)
        }
        for (artist in artistsAtOffset) {
            if (isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val audioItemArtist = createAudioItemForArtist(artist)
            items += audioItemArtist
        }
        logger.debug(TAG, "Returning ${items.size} artists for offset: $offsetRows (limit $maxNumRows)")
        return items
    }

    suspend fun getAudioItemForUnknownArtist(): AudioItem? = withContext(dispatcher) {
        val numTracksUnknownArtist = database.trackDAO().queryNumTracksArtistUnknown()
        if (numTracksUnknownArtist > 0) {
            return@withContext createUnknownArtistAudioItem()
        }
        return@withContext null
    }

    private fun createUnknownArtistAudioItem(): AudioItem {
        val unknownArtist = AudioItem()
        val contentHierarchyID = ContentHierarchyID(ContentHierarchyType.ARTIST)
        contentHierarchyID.storageID = storageID
        contentHierarchyID.artistID = DATABASE_ID_UNKNOWN
        unknownArtist.id = ContentHierarchyElement.serialize(contentHierarchyID)
        unknownArtist.artist = context.getString(R.string.browse_tree_unknown_artist)
        unknownArtist.browsPlayableFlags = unknownArtist.browsPlayableFlags.or(FLAG_BROWSABLE)
        return unknownArtist
    }

    suspend fun searchTracks(query: String): List<AudioItem> {
        val audioItems = mutableListOf<AudioItem>()
        val tracks = database.trackDAO().search(sanitizeSearchQuery(query))
        tracks.forEach {
            audioItems += createAudioItemForTrack(it)
        }
        return audioItems
    }

    suspend fun searchAlbums(query: String): List<AudioItem> {
        val audioItems = mutableListOf<AudioItem>()
        val albums = database.albumDAO().search(sanitizeSearchQuery(query))
        albums.forEach {
            audioItems += createAudioItemForAlbum(it)
        }
        return audioItems
    }

    suspend fun searchTracksForAlbum(query: String): List<AudioItem> {
        val audioItems = mutableListOf<AudioItem>()
        val albums = database.albumDAO().search(sanitizeSearchQuery(query))
        albums.forEach {
            audioItems += getTracksForAlbum(it.albumId)
        }
        return audioItems
    }

    suspend fun searchArtists(query: String): List<AudioItem> {
        val audioItems = mutableListOf<AudioItem>()
        val artists = database.artistDAO().search(sanitizeSearchQuery(query))
        artists.forEach {
            audioItems += createAudioItemForArtist(it)
        }
        return audioItems
    }

    suspend fun searchTracksForArtist(query: String): List<AudioItem> {
        val audioItems = mutableListOf<AudioItem>()
        val artists = database.artistDAO().search(sanitizeSearchQuery(query))
        artists.forEach {
            audioItems += getTracksForArtist(it.artistId)
        }
        return audioItems
    }

    suspend fun searchTrackByArtist(track: String, artist: String): List<AudioItem> {
        val audioItems = mutableListOf<AudioItem>()
        val tracksByArtist = database.trackDAO().searchWithArtist(
            sanitizeSearchQuery(track), sanitizeSearchQuery(artist)
        )
        tracksByArtist.forEach {
            audioItems += createAudioItemForTrack(it, it.parentArtistId)
        }
        return audioItems
    }

    suspend fun searchTrackByAlbum(track: String, album: String): List<AudioItem> {
        val audioItems = mutableListOf<AudioItem>()
        val tracksByAlbum = database.trackDAO().searchWithAlbum(
            sanitizeSearchQuery(track), sanitizeSearchQuery(album)
        )
        tracksByAlbum.forEach {
            audioItems += createAudioItemForTrack(it, it.parentArtistId)
        }
        return audioItems
    }

    suspend fun searchAlbumByArtist(album: String, artist: String): List<AudioItem> {
        val audioItems = mutableListOf<AudioItem>()
        val albumsByArtist = database.albumDAO().searchWithArtist(
            sanitizeSearchQuery(album), sanitizeSearchQuery(artist)
        )
        albumsByArtist.forEach {
            audioItems += createAudioItemForAlbum(it, it.parentArtistId)
        }
        return audioItems
    }

    private fun sanitizeSearchQuery(query: String): String {
        val queryEscaped = query.replace(Regex.fromLiteral("\""), "\"\"")
        return "\"*$queryEscaped*\""
    }

    private fun yearEpochTimeToShort(yearEpochTime: Long): Short {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.time = Date(yearEpochTime)
        return calendar.get(Calendar.YEAR).toShort()
    }

    private fun yearShortToEpochTime(year: Short): Long {
        if (year < 0) {
            return -1
        }
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.clear()
        calendar.set(year.toInt(), Calendar.JUNE, 1)
        return calendar.timeInMillis
    }

    // TODO: class too big, split (e.g. by track, artist, album?)

}
