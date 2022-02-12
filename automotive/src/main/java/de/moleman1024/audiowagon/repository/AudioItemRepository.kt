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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    //  the internal flash memory, but found no way to do that in Room
    // SQL query logging can be added via setQueryCallback()
    // TODO: https://developer.android.com/training/data-storage/app-specific#query-free-space
    //  I tried with 160 GB of music which resulted in a database of ~15 megabytes
    private var database: AudioItemDatabase? = null
    private var pseudoCompilationArtistID: Long? = null
    val trackIDsToKeep = mutableListOf<Long>()
    private var isClosed: Boolean = false
    private val databaseMutex: Mutex = Mutex()

    init {
        runBlocking(dispatcher) {
            databaseMutex.withLock {
                database = Room.databaseBuilder(context, AudioItemDatabase::class.java, databaseName).build()
                isClosed = false
            }
        }
    }

    fun close() {
        if (isClosed) {
            return
        }
        isClosed = true
        runBlocking(dispatcher) {
            databaseMutex.withLock {
                if (database?.isOpen == true) {
                    logger.debug(TAG, "Closing database")
                    database?.close()
                }
                database = null
            }
        }
    }

    suspend fun getTrack(id: Long): AudioItem {
        val track: Track = withContext(dispatcher) {
            getDatabase()?.trackDAO()?.queryByID(id) ?: throw RuntimeException("No track for id: $id")
        }
        return createAudioItemForTrack(track)
    }

    suspend fun getAlbumsForArtist(artistID: Long): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val albums: List<Album> = withContext(dispatcher) {
            getDatabase()?.albumDAO()?.queryAlbumsByArtist(artistID) ?: listOf()
        }
        for (album in albums) {
            yield()
            if (isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val audioItemAlbum: AudioItem = createAudioItemForAlbum(album, artistID)
            items += audioItemAlbum
        }
        val numTracksWithoutAlbum: Int = withContext(dispatcher) {
            getDatabase()?.trackDAO()?.queryNumTracksByArtistAlbumUnkn(artistID) ?: 0
        }
        if (numTracksWithoutAlbum > 0) {
            logger.debug(TAG, "Artist $artistID has $numTracksWithoutAlbum tracks without album info")
            val unknownAlbum: AudioItem = createUnknAlbumAudioItemForArtist(artistID)
            items.add(0, unknownAlbum)
        }
        val compilationAlbums: List<Album> = getCompilationAlbumsForArtist(artistID)
        for (album in compilationAlbums) {
            yield()
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
        val tracks: List<Track> = getDatabase()?.trackDAO()?.queryTracksByAlbum(albumID) ?: listOf()
        for (track in tracks) {
            yield()
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
        val tracks: List<Track> = getDatabase()?.trackDAO()?.queryTracksByArtist(artistID) ?: listOf()
        for (track in tracks) {
            yield()
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
        val tracks: List<Track> = getDatabase()?.trackDAO()?.queryTracksByArtistAndAlbum(artistID, albumID) ?: listOf()
        for (track in tracks) {
            yield()
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
        getDatabase()?.trackDAO()?.queryNumTracksByArtistAndAlbum(artistID, albumID) ?: 0
    }

    @Suppress("RedundantSuspendModifier")
    suspend fun getNumTracksForArtist(artistID: Long): Int {
        return if (artistID != getPseudoCompilationArtistID()) {
            getDatabase()?.trackDAO()?.queryNumTracksForArtist(artistID) ?: 0
        } else {
            getDatabase()?.trackDAO()?.queryNumTracksForArtistViaAlbums(artistID) ?: 0
        }
    }

    @Suppress("RedundantSuspendModifier")
    suspend fun getNumAlbumsBasedOnTracksArtist(artistID: Long): Int {
        return if (artistID != getPseudoCompilationArtistID()) {
            getDatabase()?.trackDAO()?.queryNumAlbumsByTrackArtist(artistID) ?: 0
        } else {
            getDatabase()?.albumDAO()?.queryNumAlbumsByArtist(artistID) ?: 0
        }
    }

    suspend fun getTracksWithUnknAlbumForArtist(artistID: Long): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val tracks: List<Track> = getDatabase()?.trackDAO()?.queryTracksByArtistWhereAlbumUnknown(artistID) ?: listOf()
        for (track in tracks) {
            yield()
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
        getDatabase()?.trackDAO()?.queryNumTracksByArtistAndAlbum(artistID, DATABASE_ID_UNKNOWN) ?: 0
    }

    suspend fun getDatabaseIDForTrack(audioFile: AudioFile): Long? {
        return getDatabase()?.trackDAO()?.queryIDByURI(audioFile.uri.toString())
    }

    /**
     * Adds a new track entry into database. Also inserts new artist, album database entry if those are new.
     */
    suspend fun populateDatabaseFrom(audioFile: AudioFile, audioItem: AudioItem) {
        var artistID: Long = DATABASE_ID_UNKNOWN
        var albumArtistID: Long = DATABASE_ID_UNKNOWN
        var doCreateArtistInDB = true
        if (audioItem.artist.isNotBlank()) {
            // multiple artists with same name are unlikely, ignore this case
            val artistInDB: Artist? = getDatabase()?.artistDAO()?.queryByName(audioItem.artist)
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
                    val albumArtistInDB: Artist? = getDatabase()?.artistDAO()?.queryByName(audioItem.albumArtist)
                    albumArtistID = (albumArtistInDB?.artistId ?: getDatabase()?.artistDAO()
                        ?.insert(Artist(name = audioItem.albumArtist))) ?: DATABASE_ID_UNKNOWN
                }
            }
        }
        if (artistID <= DATABASE_ID_UNKNOWN && doCreateArtistInDB && audioItem.artist.isNotBlank()) {
            artistID = getDatabase()?.artistDAO()?.insert(Artist(name = audioItem.artist)) ?: DATABASE_ID_UNKNOWN
        }
        if (albumArtistID <= DATABASE_ID_UNKNOWN) {
            albumArtistID = artistID
        }
        var albumID: Long = DATABASE_ID_UNKNOWN
        if (audioItem.album.isNotBlank()) {
            // Watch out for special cases for albums:
            // - same album name across several artists, e.g. "Greatest Hits" albums
            // - same album name for multiple artists could also be a compilation/various artists album
            if (audioItem.isInCompilation) {
                albumArtistID = makePseudoCompilationArtistID()
            }
            val albumInDB: Album? = getDatabase()?.albumDAO()?.queryByNameAndArtist(audioItem.album, albumArtistID)
            albumID = albumInDB?.albumId
                ?: getDatabase()?.albumDAO()?.insert(Album(name = audioItem.album, parentArtistId = albumArtistID))
                        ?: DATABASE_ID_UNKNOWN
        }
        val track = Track(
            name = audioItem.title,
            parentArtistId = if (albumArtistID > DATABASE_ID_UNKNOWN && !audioItem.isInCompilation) albumArtistID else artistID,
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
        val trackDatabaseID: Long = getDatabase()?.trackDAO()?.insert(track) ?: DATABASE_ID_UNKNOWN
        trackIDsToKeep.add(trackDatabaseID)
    }

    suspend fun getPseudoCompilationArtistID(): Long? {
        if (pseudoCompilationArtistID != null) {
            return pseudoCompilationArtistID
        }
        val pseudoCompilationArtistName = getPseudoCompilationArtistName()
        val artistInDB: Artist? = getDatabase()?.artistDAO()?.queryByName(pseudoCompilationArtistName)
        pseudoCompilationArtistID = artistInDB?.artistId
        return pseudoCompilationArtistID
    }

    private suspend fun makePseudoCompilationArtistID(): Long {
        if (pseudoCompilationArtistID != null) {
            return pseudoCompilationArtistID!!
        }
        val pseudoCompilationArtistName = getPseudoCompilationArtistName()
        val artistInDB: Artist? = getDatabase()?.artistDAO()?.queryByName(pseudoCompilationArtistName)
        val resultID: Long = if (artistInDB?.artistId == null) {
            getDatabase()?.artistDAO()?.insert(Artist(name = pseudoCompilationArtistName)) ?: DATABASE_ID_UNKNOWN
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
    private suspend fun getCompilationAlbumsForArtist(artistID: Long): List<Album> {
        val pseudoCompilationArtistName = getPseudoCompilationArtistName()
        val variousArtistsInDB: Artist =
            getDatabase()?.artistDAO()?.queryByName(pseudoCompilationArtistName) ?: return listOf()
        val allCompilationAlbums = getDatabase()?.albumDAO()?.queryByArtist(variousArtistsInDB.artistId) ?: listOf()
        val matchingCompilationAlbums = mutableListOf<Album>()
        for (album in allCompilationAlbums) {
            yield()
            if (isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val numTracks = getDatabase()?.trackDAO()?.queryNumTracksByArtistForAlbum(artistID, album.albumId) ?: 0
            if (numTracks > 0) {
                matchingCompilationAlbums.add(album)
            }
        }
        return matchingCompilationAlbums
    }

    suspend fun hasAudioFileChangedForTrack(audioFile: AudioFile, trackID: Long): Boolean {
        val track =
            getDatabase()?.trackDAO()?.queryByID(trackID) ?: throw RuntimeException("No track for id: $trackID")
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
    suspend fun clean() {
        logger.debug(TAG, "Cleaning no longer available items from database")
        val allTracksInDB = getDatabase()?.trackDAO()?.queryAll() ?: listOf()
        val artistDeletionCandidates = mutableSetOf<Long>()
        val albumDeletionCandidates = mutableSetOf<Long>()
        for (track in allTracksInDB) {
            if (track.trackId in trackIDsToKeep) {
                continue
            }
            artistDeletionCandidates.add(track.parentArtistId)
            albumDeletionCandidates.add(track.parentAlbumId)
            logger.debug(TAG, "Removing track from database: $track")
            getDatabase()?.trackDAO()?.delete(track)
        }
        trackIDsToKeep.clear()
        pruneAlbums(albumDeletionCandidates)
        pruneArtists(artistDeletionCandidates)
    }

    /**
     * Remove albums that no longer have any associated tracks
     */
    private suspend fun pruneAlbums(deletionCandidateIDs: Set<Long>) {
        deletionCandidateIDs.forEach { albumID ->
            val numTracksForAlbum = getDatabase()?.trackDAO()?.queryNumTracksForAlbum(albumID) ?: 0
            if (numTracksForAlbum <= 0) {
                logger.debug(TAG, "Removing album from database: $albumID")
                getDatabase()?.albumDAO()?.deleteByID(albumID)
            }
        }
    }

    /**
     * Remove artists that no longer have any associated tracks
     */
    private suspend fun pruneArtists(deletionCandidateIDs: Set<Long>) {
        deletionCandidateIDs.forEach { artistID ->
            val numTracksForArtist = getDatabase()?.trackDAO()?.queryNumTracksForArtist(artistID) ?: 0
            if (numTracksForArtist <= 0) {
                logger.debug(TAG, "Removing artist from database: $artistID")
                getDatabase()?.artistDAO()?.deleteByID(artistID)
            }
        }
    }

    suspend fun removeTrack(trackID: Long) {
        logger.debug(TAG, "Removing track from database: $trackID")
        getDatabase()?.trackDAO()?.deleteByID(trackID)
    }

    suspend fun getAllTracks(): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val allTracks: List<Track> = withContext(dispatcher) {
             getDatabase()?.trackDAO()?.queryAll() ?: listOf()
        }
        for (track in allTracks) {
            yield()
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
        getDatabase()?.trackDAO()?.queryNumTracks() ?: 0
    }

    suspend fun getNumTracksForAlbum(albumID: Long): Int = withContext(dispatcher) {
        getDatabase()?.trackDAO()?.queryNumTracksForAlbum(albumID) ?: 0
    }

    // TODO: many similar functions in here
    suspend fun getTracksLimitOffset(maxNumRows: Int, offsetRows: Int): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val tracksAtOffset: List<Track> = withContext(dispatcher) {
            getDatabase()?.trackDAO()?.queryTracksLimitOffset(maxNumRows, offsetRows) ?: listOf()
        }
        for (track in tracksAtOffset) {
            yield()
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
            getDatabase()?.trackDAO()?.queryTracksForArtistAlbumLimitOffset(maxNumRows, offsetRows, artistID,
                albumID) ?: listOf()
        }
        for (track in tracksAtOffset) {
            yield()
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
            getDatabase()?.trackDAO()?.queryTracksForAlbumLimitOffset(maxNumRows, offsetRows, albumID) ?: listOf()
        }
        for (track in tracksAtOffset) {
            yield()
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
            getDatabase()?.trackDAO()?.queryTracksForArtistLimitOffset(maxNumRows, offsetRows, artistID) ?: listOf()
        }
        for (track in tracksAtOffset) {
            yield()
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
        val tracks: List<Track> = getDatabase()?.trackDAO()?.queryRandom(maxNumItems) ?: listOf()
        for (track in tracks) {
            yield()
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
                getDatabase()?.artistDAO()?.queryByID(track.parentArtistId)
            }
            artistForTrack?.let { audioItemTrack.artist = it.name }
        }
        audioItemTrack.albumArtist = audioItemTrack.artist
        if (track.albumArtURIString.isNotBlank()) {
            audioItemTrack.artist = track.albumArtURIString
        }
        if (track.parentAlbumId >= 0) {
            val albumForTrack: Album? = withContext(dispatcher) {
                getDatabase()?.albumDAO()?.queryByID(track.parentAlbumId)
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
        val allAlbums: List<Album> = getDatabase()?.albumDAO()?.queryAll() ?: listOf()
        for (album in allAlbums) {
            yield()
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
        val numTracksUnknownAlbum = getDatabase()?.trackDAO()?.queryNumTracksAlbumUnknown() ?: 0
        if (numTracksUnknownAlbum > 0) {
            return@withContext createUnknownAlbumAudioItem()
        }
        return@withContext null
    }

    suspend fun getNumAlbums(): Int = withContext(dispatcher) {
        getDatabase()?.albumDAO()?.queryNumAlbums() ?: 0
    }

    suspend fun getAlbumsLimitOffset(maxNumRows: Int, offsetRows: Int): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val albumsAtOffset: List<Album> = withContext(dispatcher) {
            getDatabase()?.albumDAO()?.queryAlbumsLimitOffset(maxNumRows, offsetRows) ?: listOf()
        }
        for (album in albumsAtOffset) {
            yield()
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
            getDatabase()?.albumDAO()?.queryAlbumsForArtistLimitOffset(maxNumRows, offsetRows, artistID) ?: listOf()
        }
        for (album in albumsAtOffset) {
            yield()
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
            getDatabase()?.artistDAO()?.queryByID(album.parentArtistId)
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
            getDatabase()?.artistDAO()?.queryByID(artistID)
        }
        artistForAlbum?.let { unknownAlbum.artist = it.name }
        return unknownAlbum
    }

    suspend fun getAllArtists(): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val allArtists: List<Artist> = getDatabase()?.artistDAO()?.queryAll() ?: listOf()
        for (artist in allArtists) {
            yield()
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
        getDatabase()?.artistDAO()?.queryNumArtists() ?: 0
    }

    suspend fun getArtistsLimitOffset(maxNumRows: Int, offsetRows: Int): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val artistsAtOffset: List<Artist> = withContext(dispatcher) {
            getDatabase()?.artistDAO()?.queryArtistsLimitOffset(maxNumRows, offsetRows) ?: listOf()
        }
        for (artist in artistsAtOffset) {
            yield()
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
        val numTracksUnknownArtist = getDatabase()?.trackDAO()?.queryNumTracksArtistUnknown() ?: 0
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
        val tracks = getDatabase()?.trackDAO()?.search(sanitizeSearchQuery(query))
        tracks?.forEach {
            audioItems += createAudioItemForTrack(it)
        }
        return audioItems
    }

    suspend fun searchAlbums(query: String): List<AudioItem> {
        val audioItems = mutableListOf<AudioItem>()
        val albums = getDatabase()?.albumDAO()?.search(sanitizeSearchQuery(query))
        albums?.forEach {
            audioItems += createAudioItemForAlbum(it)
        }
        return audioItems
    }

    suspend fun searchTracksForAlbum(query: String): List<AudioItem> {
        val audioItems = mutableListOf<AudioItem>()
        val albums = getDatabase()?.albumDAO()?.search(sanitizeSearchQuery(query))
        albums?.forEach {
            audioItems += getTracksForAlbum(it.albumId)
        }
        return audioItems
    }

    suspend fun searchArtists(query: String): List<AudioItem> {
        val audioItems = mutableListOf<AudioItem>()
        val artists = getDatabase()?.artistDAO()?.search(sanitizeSearchQuery(query))
        artists?.forEach {
            audioItems += createAudioItemForArtist(it)
        }
        return audioItems
    }

    suspend fun searchTracksForArtist(query: String): List<AudioItem> {
        val audioItems = mutableListOf<AudioItem>()
        val artists = getDatabase()?.artistDAO()?.search(sanitizeSearchQuery(query))
        artists?.forEach {
            audioItems += getTracksForArtist(it.artistId)
        }
        return audioItems
    }

    suspend fun searchTrackByArtist(track: String, artist: String): List<AudioItem> {
        val audioItems = mutableListOf<AudioItem>()
        val tracksByArtist = getDatabase()?.trackDAO()?.searchWithArtist(
            sanitizeSearchQuery(track), sanitizeSearchQuery(artist)
        )
        tracksByArtist?.forEach {
            audioItems += createAudioItemForTrack(it, it.parentArtistId)
        }
        return audioItems
    }

    suspend fun searchTrackByAlbum(track: String, album: String): List<AudioItem> {
        val audioItems = mutableListOf<AudioItem>()
        val tracksByAlbum = getDatabase()?.trackDAO()?.searchWithAlbum(
            sanitizeSearchQuery(track), sanitizeSearchQuery(album)
        )
        tracksByAlbum?.forEach {
            audioItems += createAudioItemForTrack(it, it.parentArtistId)
        }
        return audioItems
    }

    suspend fun searchAlbumByArtist(album: String, artist: String): List<AudioItem> {
        val audioItems = mutableListOf<AudioItem>()
        val albumsByArtist = getDatabase()?.albumDAO()?.searchWithArtist(
            sanitizeSearchQuery(album), sanitizeSearchQuery(artist)
        )
        albumsByArtist?.forEach {
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

    private suspend fun getDatabase(): AudioItemDatabase? {
        if (isClosed) {
            return null
        }
        databaseMutex.withLock {
            return database
        }
    }

    // TODO: class too big, split (e.g. by track, artist, album?)

}
