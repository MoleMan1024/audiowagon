/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.repository

import android.content.Context
import androidx.room.RoomDatabase
import de.moleman1024.audiowagon.filestorage.data.AudioFile
import de.moleman1024.audiowagon.filestorage.FileLike
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.ART_URI_PART
import de.moleman1024.audiowagon.medialibrary.ART_URI_PART_ALBUM
import de.moleman1024.audiowagon.medialibrary.ART_URI_PART_TRACK
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyID
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.DATABASE_ID_UNKNOWN
import de.moleman1024.audiowagon.repository.entities.Album
import de.moleman1024.audiowagon.repository.entities.Artist
import de.moleman1024.audiowagon.repository.entities.Track
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.temporal.ChronoUnit

const val MAX_DATABASE_SEARCH_ROWS = 10
const val AUDIOITEM_REPO_DB_PREFIX = "audioItemRepo_"
private const val TAG = "AudioItemRepo"
private val logger = Logger

/**
 * This class is responsible for creating and managing the Room SQLite database to store media metadata.
 * It is accessed by [AudioItemLibrary].
 *
 * See https://developer.android.com/training/data-storage/room
 */
@ExperimentalCoroutinesApi
class AudioItemRepository(
    val storageID: String,
    val context: Context,
    private val dispatcher: CoroutineDispatcher,
    private val dbBuilder: RoomDatabase.Builder<AudioItemDatabase>
) {
    // The database file is created in internal storage only after the first row has been added to it
    // TODO: this is not ideal, I would rather like to store the database on the USB flash drive to reduce wear on
    //  the internal flash memory, but found no way to do that in Room
    // TODO: https://developer.android.com/training/data-storage/app-specific#query-free-space
    //  I tried with 160 GB of music which resulted in a database of ~15 megabytes (version 1.1.2)
    private var database: AudioItemDatabase? = null
    var isClosed: Boolean = false
    private val databaseMutex: Mutex = Mutex()
    private val repoSearch = RepositorySearch(this)
    val trackIDsToKeep get() = repoUpdate.trackIDsToKeep
    val pathIDsToKeep get() = repoUpdate.pathIDsToKeep
    val repoUpdate = RepositoryUpdate(this, context)
    private val repoQuery = RepositoryQuery(this, dispatcher, storageID, context)
    var hasUpdatedDatabase: Boolean = false

    init {
        runBlocking(dispatcher) {
            databaseMutex.withLock {
                // TODO: some duplicate queries when I turn on logging, check that
                database = dbBuilder.build()
                Logger.debug(TAG, "Created database: $database")
                isClosed = false
            }
        }
    }

    suspend fun populateDatabaseFrom(audioFile: AudioFile, metadata: AudioItem, albumArtSource: FileLike?) {
        databaseMutex.withLock {
            repoUpdate.populateDatabaseFrom(audioFile, metadata, albumArtSource)
        }
    }

    suspend fun populateDatabaseFromFileOrDir(fileOrDir: FileLike) {
        databaseMutex.withLock {
            repoUpdate.populateDatabaseFromFileOrDir(fileOrDir)
        }
    }

    suspend fun updateGroups() {
        databaseMutex.withLock {
            if (!hasUpdatedDatabase) {
                logger.debug(TAG, "Database has not changed, no update of groups needed")
                return
            }
            repoUpdate.cleanGroups()
            repoUpdate.createGroups()
        }
    }

    suspend fun clean() {
        databaseMutex.withLock {
            repoUpdate.clean()
        }
    }

    suspend fun hasAudioFileChangedForTrack(audioFile: AudioFile, trackID: Long): Boolean {
        databaseMutex.withLock {
            val track = getDatabaseNoLock()?.trackDAO()?.queryByID(trackID)
                ?: throw RuntimeException("No track for id: $trackID")
            val trackLastModSec = track.lastModifiedEpochTime
            val audioFileLastModSec =
                audioFile.lastModifiedDate.toInstant().truncatedTo(ChronoUnit.SECONDS).toEpochMilli()
            val audioFileHasChanged = trackLastModSec != audioFileLastModSec
            if (audioFileHasChanged) {
                logger.verbose(
                    TAG, "AudioFile modification date differs from track in database: " +
                            "track=${track.lastModifiedEpochTime} != audioFile=${audioFile.lastModifiedDate.time}"
                )
            }
            return audioFileHasChanged
        }
    }

    suspend fun hasAudioFileChangedForPath(fileOrDir: FileLike, pathID: Long): Boolean {
        databaseMutex.withLock {
            val path =
                getDatabaseNoLock()?.pathDAO()?.queryByID(pathID) ?: throw RuntimeException("No path for id: $pathID")
            val pathLastModSec = path.lastModifiedEpochTime
            val audioFileLastModSec =
                fileOrDir.lastModifiedDate.toInstant().truncatedTo(ChronoUnit.SECONDS).toEpochMilli()
            val audioFileHasChanged = pathLastModSec != audioFileLastModSec
            if (audioFileHasChanged) {
                logger.verbose(
                    TAG, "AudioFile modification date differs from path in database: " +
                            "path=${path.lastModifiedEpochTime} != audioFile=${fileOrDir.lastModifiedDate.time}"
                )
            }
            return audioFileHasChanged
        }
    }

    suspend fun removeTrack(trackID: Long) {
        databaseMutex.withLock {
            repoUpdate.removeTrack(trackID)
        }
    }

    suspend fun removePath(pathID: Long) {
        databaseMutex.withLock {
            repoUpdate.removePath(pathID)
        }
    }

    suspend fun getTrack(id: Long): AudioItem {
        return repoQuery.getTrack(id)
    }

    suspend fun getAlbumsForArtist(artistID: Long): List<AudioItem> {
        return repoQuery.getAlbumsForArtist(artistID)
    }

    suspend fun getTracksForAlbum(albumID: Long): List<AudioItem> {
        return repoQuery.getTracksForAlbum(albumID)
    }

    suspend fun getTracksForArtist(artistID: Long): List<AudioItem> {
        return repoQuery.getTracksForArtist(artistID)
    }

    suspend fun getTracksForAlbumAndArtist(albumID: Long, artistID: Long): List<AudioItem> {
        return repoQuery.getTracksForAlbumAndArtist(albumID, artistID)
    }

    suspend fun getNumTracksForAlbumAndArtist(albumID: Long, artistID: Long): Int {
        return repoQuery.getNumTracksForAlbumAndArtist(albumID, artistID)
    }

    suspend fun getNumTracksForArtist(artistID: Long): Int {
        return repoQuery.getNumTracksForArtist(artistID)
    }

    suspend fun getNumTracksForAlbumArtist(artistID: Long): Int {
        return repoQuery.getNumTracksForAlbumArtist(artistID)
    }

    suspend fun getNumAlbumsBasedOnTracksArtist(artistID: Long): Int {
        return repoQuery.getNumAlbumsBasedOnTracksArtist(artistID)
    }

    suspend fun getNumAlbumsBasedOnTracksAlbumArtist(artistID: Long): Int {
        return repoQuery.getNumAlbumsBasedOnTracksAlbumArtist(artistID)
    }

    suspend fun getTracksWithUnknAlbumForArtist(artistID: Long): List<AudioItem> {
        return repoQuery.getTracksWithUnknAlbumForArtist(artistID)
    }

    suspend fun getTracksWithUnknAlbum(): List<AudioItem> {
        return repoQuery.getTracksWithUnknAlbum()
    }

    suspend fun getNumTracksWithUnknAlbumForArtist(artistID: Long): Int {
        return repoQuery.getNumTracksWithUnknAlbumForArtist(artistID)
    }

    suspend fun getNumTracksWithUnknAlbum(): Int {
        return repoQuery.getNumTracksWithUnknAlbum()
    }

    suspend fun getNumTracksWithUnknAlbumForAlbumArtist(artistID: Long): Int {
        return repoQuery.getNumTracksWithUnknAlbumForAlbumArtist(artistID)
    }

    suspend fun getDatabaseIDForTrack(audioFile: AudioFile): Long? {
        return repoQuery.getDatabaseIDForTrack(audioFile)
    }

    suspend fun getDatabaseIDForPath(fileOrDir: FileLike): Long? {
        return repoQuery.getDatabaseIDForPath(fileOrDir)
    }

    suspend fun getAllTracks(): List<AudioItem> {
        return repoQuery.getAllTracks()
    }

    suspend fun getNumTracks(): Int {
        return repoQuery.getNumTracks()
    }

    suspend fun getNumTracksNoLock(): Int {
        return repoQuery.getNumTracksNoLock()
    }

    suspend fun getNumPaths(): Int {
        return repoQuery.getNumPaths()
    }

    suspend fun getNumTracksForAlbum(albumID: Long): Int {
        return repoQuery.getNumTracksForAlbum(albumID)
    }

    suspend fun getTracksLimitOffset(maxNumRows: Int, offsetRows: Int): List<AudioItem> {
        return repoQuery.getTracksLimitOffset(maxNumRows, offsetRows)
    }

    suspend fun getTrackGroup(groupIndex: Int): Pair<AudioItem, AudioItem> {
        return repoQuery.getTrackGroup(groupIndex)
    }

    suspend fun getTracksForArtistAlbumLimitOffset(
        maxNumRows: Int, offsetRows: Int, artistID: Long, albumID: Long
    ): List<AudioItem> {
        return repoQuery.getTracksForArtistAlbumLimitOffset(maxNumRows, offsetRows, artistID, albumID)
    }

    suspend fun getTracksForAlbumLimitOffset(maxNumRows: Int, offsetRows: Int, albumID: Long): List<AudioItem> {
        return repoQuery.getTracksForAlbumLimitOffset(maxNumRows, offsetRows, albumID)
    }

    suspend fun getTracksForArtistLimitOffset(maxNumRows: Int, offsetRows: Int, artistID: Long): List<AudioItem> {
        return repoQuery.getTracksForArtistLimitOffset(maxNumRows, offsetRows, artistID)
    }

    suspend fun getRandomTracks(maxNumItems: Int): List<AudioItem> {
        return repoQuery.getRandomTracks(maxNumItems)
    }

    suspend fun createAudioItemForTrack(
        track: Track, artistID: Long? = null, albumID: Long? = null
    ): AudioItem {
        return repoQuery.createAudioItemForTrack(track, artistID, albumID)
    }

    suspend fun getAllAlbums(): List<AudioItem> {
        return repoQuery.getAllAlbums()
    }

    suspend fun getAudioItemForUnknownAlbum(): AudioItem? {
        return repoQuery.getAudioItemForUnknownAlbum()
    }

    suspend fun getNumAlbums(): Int {
        return repoQuery.getNumAlbums()
    }

    suspend fun getNumAlbumsNoLock(): Int {
        return repoQuery.getNumAlbumsNoLock()
    }

    suspend fun getAlbumsLimitOffset(maxNumRows: Int, offsetRows: Int): List<AudioItem> {
        return repoQuery.getAlbumsLimitOffset(maxNumRows, offsetRows)
    }

    suspend fun getAlbumGroup(groupIndex: Int): Pair<AudioItem, AudioItem> {
        return repoQuery.getAlbumGroup(groupIndex)
    }

    suspend fun getAlbumsForArtistLimitOffset(maxNumRows: Int, offsetRows: Int, artistID: Long): List<AudioItem> {
        return repoQuery.getAlbumsForArtistLimitOffset(maxNumRows, offsetRows, artistID)
    }

    suspend fun createAudioItemForAlbum(album: Album, artistID: Long? = null): AudioItem {
        return repoQuery.createAudioItemForAlbum(album, artistID)
    }

    suspend fun createAudioItemForArtist(artist: Artist): AudioItem {
        return repoQuery.createAudioItemForArtist(artist)
    }

    suspend fun getAllAlbumAndCompilationArtists(): List<AudioItem> {
        return repoQuery.getAllAlbumAndCompilationArtists()
    }

    suspend fun getNumAlbumAndCompilationArtists(): Int {
        return repoQuery.getNumAlbumAndCompilationArtists()
    }

    suspend fun getNumAlbumAndCompilationArtistsNoLock(): Int {
        return repoQuery.getNumAlbumAndCompilationArtistsNoLock()
    }

    suspend fun getAlbumAndCompilationArtistsLimitOffset(maxNumRows: Int, offsetRows: Int): List<AudioItem> {
        return repoQuery.getAlbumAndCompilationArtistsLimitOffset(maxNumRows, offsetRows)
    }

    suspend fun getArtistGroup(groupIndex: Int): Pair<AudioItem, AudioItem> {
        return repoQuery.getArtistGroup(groupIndex)
    }

    suspend fun getAudioItemForUnknownArtist(): AudioItem? {
        return repoQuery.getAudioItemForUnknownArtist()
    }

    suspend fun getFilesInDirRecursive(path: String, numMaxItems: Int): List<AudioItem> {
        return repoQuery.getFilesInDirRecursive(path, numMaxItems)
    }

    suspend fun getAlbumForAlbumArt(uriString: String): Album? {
        logger.verbose(TAG, "getAlbumForAlbumArt(uriString=$uriString)")
        var albumForAlbumArt: Album? = null
        if (uriString.contains("$ART_URI_PART/$ART_URI_PART_TRACK")) {
            val track = getDatabase()?.trackDAO()?.queryTrackByAlbumArt(uriString) ?: return null
            if (track.parentAlbumId == DATABASE_ID_UNKNOWN) {
                return null
            }
            albumForAlbumArt = getDatabase()?.albumDAO()?.queryByID(track.parentAlbumId) ?: return null
        } else if (uriString.contains("$ART_URI_PART/$ART_URI_PART_ALBUM")) {
            albumForAlbumArt = getDatabase()?.albumDAO()?.queryAlbumByAlbumArt(uriString) ?: return null
        }
        return albumForAlbumArt
    }

    suspend fun getTrackForAlbumArt(uriString: String): Track? {
        logger.verbose(TAG, "getTrackForAlbumArt(uriString=$uriString)")
        if (!uriString.contains("$ART_URI_PART/$ART_URI_PART_TRACK")) {
            return null
        }
        val track = getDatabase()?.trackDAO()?.queryTrackByAlbumArt(uriString)
        logger.verbose(TAG, "track=$track")
        return track
    }

    suspend fun searchTracks(query: String): List<AudioItem> {
        return repoSearch.searchTracks(query)
    }

    suspend fun searchAlbums(query: String): List<AudioItem> {
        return repoSearch.searchAlbums(query)
    }

    suspend fun searchFiles(query: String): List<AudioItem> {
        return repoSearch.searchFiles(query)
    }

    suspend fun searchTracksForAlbum(query: String): List<AudioItem> {
        return repoSearch.searchTracksForAlbum(query)
    }

    suspend fun searchAlbumAndCompilationArtists(query: String): List<AudioItem> {
        return repoSearch.searchAlbumAndCompilationArtists(query)
    }

    suspend fun searchTracksForArtist(query: String): List<AudioItem> {
        return repoSearch.searchTracksForArtist(query)
    }

    suspend fun searchTrackByArtist(track: String, artist: String): List<AudioItem> {
        return repoSearch.searchTrackByArtist(track, artist)
    }

    suspend fun searchTrackByAlbum(track: String, album: String): List<AudioItem> {
        return repoSearch.searchTrackByAlbum(track, album)
    }

    suspend fun searchTracksForAlbumAndArtist(album: String, artist: String): List<AudioItem> {
        return repoSearch.searchTracksForAlbumAndArtist(album, artist)
    }

    suspend fun getDatabase(): AudioItemDatabase? {
        if (isClosed) {
            return null
        }
        databaseMutex.withLock {
            return database
        }
    }

    fun getDatabaseNoLock(): AudioItemDatabase? {
        if (isClosed) {
            return null
        }
        return database
    }

    suspend fun getPseudoCompilationArtistID(): Long? {
        return repoUpdate.getPseudoCompilationArtistID()
    }

    fun getPseudoCompilationArtistNameEnglish(): String {
        return repoUpdate.getPseudoCompilationArtistNameEnglish()
    }

    suspend fun isVariousArtistsAlbum(id: ContentHierarchyID): Boolean {
        val pseudoCompilationArtistID: Long? = getPseudoCompilationArtistID()
        return pseudoCompilationArtistID == id.albumArtistID || pseudoCompilationArtistID == id.artistID
    }

    fun close() {
        if (isClosed) {
            return
        }
        runBlocking(dispatcher) {
            databaseMutex.withLock {
                if (database?.isOpen == true) {
                    logger.debug(TAG, "Closing database: $database")
                    database?.close()
                }
                database = null
            }
        }
        isClosed = true
    }

    companion object {
        fun createDatabaseName(storageID: String): String {
            return "$AUDIOITEM_REPO_DB_PREFIX${storageID}.sqlite"
        }
    }

}
