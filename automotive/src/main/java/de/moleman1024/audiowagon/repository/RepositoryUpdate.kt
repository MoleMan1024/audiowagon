/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.repository

import android.content.Context
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.Util.Companion.getSortNameOrBlank
import de.moleman1024.audiowagon.filestorage.AudioFile
import de.moleman1024.audiowagon.filestorage.Directory
import de.moleman1024.audiowagon.filestorage.FileLike
import de.moleman1024.audiowagon.filestorage.GeneralFile
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemType
import de.moleman1024.audiowagon.medialibrary.AudioMetadataMaker
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.CONTENT_HIERARCHY_MAX_NUM_ITEMS
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.DATABASE_ID_UNKNOWN
import de.moleman1024.audiowagon.repository.entities.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.temporal.ChronoUnit

private const val TAG = "RepositoryUpdate"
private val logger = Logger

@ExperimentalCoroutinesApi
class RepositoryUpdate(private val repo: AudioItemRepository, private val context: Context) {
    private var pseudoCompilationArtistID: Long? = null
    val trackIDsToKeep = mutableListOf<Long>()
    val pathIDsToKeep = mutableListOf<Long>()

    /**
     * Adds a new entries into database (tracks, artists, albums, paths).
     */
    suspend fun populateDatabaseFrom(audioFile: AudioFile, metadata: AudioItem, albumArtSource: FileLike?) {
        var artistID: Long = DATABASE_ID_UNKNOWN
        var albumArtistID: Long = DATABASE_ID_UNKNOWN
        var newArtist: Artist? = null
        var newAlbumArtist: Artist? = null
        if (metadata.artist.isNotBlank()) {
            // multiple artists with same name are unlikely, ignore this case
            val artistInDB: Artist? = repo.getDatabase()?.artistDAO()?.queryByName(metadata.artist)
            if (artistInDB?.artistId != null) {
                artistID = artistInDB.artistId
            } else {
                newArtist = Artist(name = metadata.artist, sortName = getSortNameOrBlank(metadata.artist))
                if (metadata.isInCompilation) {
                    newArtist.isCompilationArtist = true
                }
            }
        }
        if (metadata.albumArtist.isNotBlank()) {
            if (metadata.albumArtist == metadata.artist) {
                albumArtistID = artistID
                newArtist?.isAlbumArtist = true
            } else {
                // this will support album artists https://github.com/MoleMan1024/audiowagon/issues/22
                // (these are not considered compilations, the album artist is treated as the "main" artist)
                if (!metadata.isInCompilation) {
                    val albumArtistInDB: Artist? = repo.getDatabase()?.artistDAO()?.queryByName(metadata.albumArtist)
                    if (albumArtistInDB?.artistId != null) {
                        albumArtistID = albumArtistInDB.artistId
                    } else {
                        newAlbumArtist = Artist(
                            name = metadata.albumArtist,
                            sortName = getSortNameOrBlank(metadata.albumArtist),
                            isAlbumArtist = true
                        )
                    }
                }
            }
        }
        if (newArtist != null || newAlbumArtist != null) {
            if (newArtist?.name == newAlbumArtist?.name) {
                // do not insert duplicate artists
                newArtist = null
            }
            if (metadata.albumArtist.isBlank() && !metadata.isInCompilation) {
                newArtist?.isAlbumArtist = true
            }
            if (newArtist != null) {
                logger.debug(TAG, "Inserting artist: $newArtist")
                artistID = repo.getDatabase()?.artistDAO()?.insert(newArtist) ?: DATABASE_ID_UNKNOWN
            }
            if (newAlbumArtist != null) {
                logger.debug(TAG, "Inserting album artist: $newAlbumArtist")
                albumArtistID = repo.getDatabase()?.artistDAO()?.insert(newAlbumArtist) ?: DATABASE_ID_UNKNOWN
            }
        }
        if (albumArtistID <= DATABASE_ID_UNKNOWN) {
            albumArtistID = artistID
        }
        val albumArtID = Util.createIDForAlbumArtForFile(audioFile.path)
        val albumArtContentURIForAlbum = AudioMetadataMaker.createURIForAlbumArtForAlbum(albumArtID)
        var albumID: Long = DATABASE_ID_UNKNOWN
        if (metadata.album.isNotBlank()) {
            // Watch out for special cases for albums:
            // - same album name across several artists, e.g. "Greatest Hits" albums
            // - same album name for multiple artists could also be a compilation/various artists album
            if (metadata.isInCompilation) {
                albumArtistID = makePseudoCompilationArtist()
            }
            val albumInDB: Album? = repo.getDatabase()?.albumDAO()?.queryByNameAndArtist(metadata.album, albumArtistID)
            albumID = if (albumInDB?.albumId != null) {
               albumInDB.albumId
            } else {
                val album = Album(
                    name = metadata.album,
                    parentArtistId = albumArtistID,
                    sortName = getSortNameOrBlank(metadata.album),
                    albumArtURIString = albumArtContentURIForAlbum.toString(),
                    albumArtSourceURIString = albumArtSource?.uri?.toString() ?: "",
                    hasFolderImage = albumArtSource is GeneralFile
                )
                logger.debug(TAG, "Inserting album: $album")
                repo.getDatabase()?.albumDAO()?.insert(album) ?: DATABASE_ID_UNKNOWN
            }
        }
        val albumArtContentURIForTrack = AudioMetadataMaker.createURIForAlbumArtForTrack(albumArtID)
        val lastModifiedTime = audioFile.lastModifiedDate.toInstant().truncatedTo(ChronoUnit.SECONDS).toEpochMilli()
        val track = Track(
            name = metadata.title,
            sortName = getSortNameOrBlank(metadata.title),
            parentArtistId = artistID,
            parentAlbumArtistId = albumArtistID,
            parentAlbumId = albumID,
            trackNum = metadata.trackNum,
            discNum = metadata.discNum,
            albumArtURIString = albumArtContentURIForTrack.toString(),
            yearEpochTime = Util.yearShortToEpochTime(metadata.year),
            uriString = audioFile.uri.toString(),
            lastModifiedEpochTime = lastModifiedTime,
            durationMS = metadata.durationMS,
        )
        logger.debug(TAG, "Inserting track: $track")
        val trackID: Long = repo.getDatabase()?.trackDAO()?.insert(track) ?: DATABASE_ID_UNKNOWN
        repo.trackIDsToKeep.add(trackID)
    }

    suspend fun populateDatabaseFromFileOrDir(fileLike: FileLike) {
        val lastModifiedTime = fileLike.lastModifiedDate.toInstant().truncatedTo(ChronoUnit.SECONDS).toEpochMilli()
        val parentPathID =
            repo.getDatabase()?.pathDAO()?.queryParentPath(fileLike.parentPath)?.pathId ?: DATABASE_ID_UNKNOWN
        val path = Path(parentPathId = parentPathID, parentPath = fileLike.parentPath, name = fileLike.name,
            lastModifiedEpochTime = lastModifiedTime)
        if (fileLike is Directory) {
            path.isDirectory = true
        }
        logger.debug(TAG, "Inserting file/directory: $path")
        val pathDatabaseID: Long = repo.getDatabase()?.pathDAO()?.insert(path) ?: DATABASE_ID_UNKNOWN
        repo.pathIDsToKeep.add(pathDatabaseID)
    }

    suspend fun cleanGroups() {
        logger.debug(TAG, "cleanGroups()")
        repo.getDatabase()?.artistDAO()?.deleteAllArtistGroups()
        repo.getDatabase()?.albumDAO()?.deleteAllAlbumGroups()
        repo.getDatabase()?.trackDAO()?.deleteAllTrackGroups()
    }

    /**
     * Creates groups of 400 tracks/artist/albums in database because creating those dynamically required too many
     * database queries which was too slow (mostly for tracks, see issue #38)
     */
    suspend fun createGroups() {
        createGroupsForType(AudioItemType.TRACK)
        // usually there are a lot less artists and albums than tracks, so the speed-up will be less for these
        createGroupsForType(AudioItemType.ARTIST)
        createGroupsForType(AudioItemType.ALBUM)
        // for files this should not be needed unless a user stores their whole music library without any directories
    }

    private suspend fun createGroupsForType(audioItemType: AudioItemType) {
        logger.debug(TAG, "createGroupsForType($audioItemType)")
        val numItems: Int = when (audioItemType) {
            AudioItemType.TRACK -> {
                repo.getNumTracks()
            }
            AudioItemType.ALBUM -> {
                repo.getNumAlbums()
            }
            AudioItemType.ARTIST -> {
                repo.getNumAlbumAndCompilationArtists()
            }
            else -> throw AssertionError("")
        }
        logger.debug(TAG, "num items in group $audioItemType: $numItems")
        var offset = 0
        val lastGroupIndex = numItems / CONTENT_HIERARCHY_MAX_NUM_ITEMS
        for (groupIndex in 0 .. lastGroupIndex) {
            val offsetRows = if (groupIndex < lastGroupIndex) {
                offset + CONTENT_HIERARCHY_MAX_NUM_ITEMS - 1
            } else {
                offset + (numItems % CONTENT_HIERARCHY_MAX_NUM_ITEMS) - 1
            }
            when (audioItemType) {
                AudioItemType.TRACK -> {
                    val firstItemInGroup = repo.getDatabase()?.trackDAO()?.queryTracksLimitOffset(1, offset)
                    val lastItemInGroup = repo.getDatabase()?.trackDAO()?.queryTracksLimitOffset(1, offsetRows)
                    if (firstItemInGroup.isNullOrEmpty() || lastItemInGroup.isNullOrEmpty()) {
                        break
                    }
                    val trackGroup = TrackGroup(
                        trackGroupIndex = groupIndex,
                        startTrackId = firstItemInGroup[0].trackId,
                        endTrackId = lastItemInGroup[0].trackId
                    )
                    repo.getDatabase()?.trackDAO()?.insertGroup(trackGroup)
                }
                AudioItemType.ALBUM -> {
                    val firstItemInGroup = repo.getDatabase()?.albumDAO()?.queryAlbumsLimitOffset(1, offset)
                    val lastItemInGroup = repo.getDatabase()?.albumDAO()?.queryAlbumsLimitOffset(1, offsetRows)
                    if (firstItemInGroup.isNullOrEmpty() || lastItemInGroup.isNullOrEmpty()) {
                        break
                    }
                    val albumGroup = AlbumGroup(
                        albumGroupIndex = groupIndex,
                        startAlbumId = firstItemInGroup[0].albumId,
                        endAlbumId = lastItemInGroup[0].albumId
                    )
                    repo.getDatabase()?.albumDAO()?.insertGroup(albumGroup)
                }
                AudioItemType.ARTIST -> {
                    val firstItemInGroup = repo.getDatabase()?.artistDAO()?.queryArtistsLimitOffset(1, offset)
                    val lastItemInGroup = repo.getDatabase()?.artistDAO()?.queryArtistsLimitOffset(1, offsetRows)
                    if (firstItemInGroup.isNullOrEmpty() || lastItemInGroup.isNullOrEmpty()) {
                        break
                    }
                    val artistGroup = ArtistGroup(
                        artistGroupIndex = groupIndex,
                        startArtistId = firstItemInGroup[0].artistId,
                        endArtistId = lastItemInGroup[0].artistId
                    )
                    repo.getDatabase()?.artistDAO()?.insertGroup(artistGroup)
                }
                else -> throw AssertionError("createGroups() not supported for type: $audioItemType")
            }
            offset += CONTENT_HIERARCHY_MAX_NUM_ITEMS
        }
    }

    /**
     * Creates a pseudo artist for compilations ("Various artists") in database if necessary. Returns database ID of
     * that pseudo artist.
     */
    private suspend fun makePseudoCompilationArtist(): Long {
        if (pseudoCompilationArtistID != null) {
            return pseudoCompilationArtistID!!
        }
        val pseudoCompilationArtistName = getPseudoCompilationArtistName()
        val artistInDB: Artist? = repo.getDatabase()?.artistDAO()?.queryByName(pseudoCompilationArtistName)
        val resultID: Long = if (artistInDB?.artistId == null) {
            repo.getDatabase()?.artistDAO()?.insert(Artist(name = pseudoCompilationArtistName, isAlbumArtist = true))
                ?: DATABASE_ID_UNKNOWN
        } else {
            artistInDB.artistId
        }
        pseudoCompilationArtistID = resultID
        return resultID
    }

    fun getPseudoCompilationArtistName(): String {
        return context.getString(R.string.browse_tree_various_artists)
    }

    /**
     * Returns the ID of the compilation-artist (shown as "Various artists")
     */
    suspend fun getPseudoCompilationArtistID(): Long? {
        if (pseudoCompilationArtistID != null) {
            return pseudoCompilationArtistID
        }
        val pseudoCompilationArtistName = getPseudoCompilationArtistName()
        val artistInDB: Artist? = repo.getDatabase()?.artistDAO()?.queryByName(pseudoCompilationArtistName)
        pseudoCompilationArtistID = artistInDB?.artistId
        return pseudoCompilationArtistID
    }

    suspend fun removeTrack(trackID: Long) {
        logger.debug(TAG, "Removing track from database: $trackID")
        repo.getDatabase()?.trackDAO()?.deleteByID(trackID)
    }

    suspend fun removePath(pathID: Long) {
        logger.debug(TAG, "Removing path from database: $pathID")
        repo.getDatabase()?.pathDAO()?.deleteByID(pathID)
    }

    /**
     * Remove all tracks/albums/artists/paths in database that were not added by previous buildLibrary() call
     */
    suspend fun clean() {
        logger.debug(TAG, "Cleaning no longer available items from database")
        val allTracksInDB = repo.getDatabase()?.trackDAO()?.queryAll() ?: listOf()
        for (track in allTracksInDB) {
            if (track.trackId in trackIDsToKeep) {
                continue
            }
            logger.verbose(TAG, "Removing track from database: $track")
            repo.getDatabase()?.trackDAO()?.delete(track)
            repo.hasUpdatedDatabase = true
        }
        trackIDsToKeep.clear()
        val allPathsInDB = repo.getDatabase()?.pathDAO()?.queryAll() ?: listOf()
        for (path in allPathsInDB) {
            if (path.pathId in pathIDsToKeep) {
                continue
            }
            logger.verbose(TAG, "Removing path from database: $path")
            repo.getDatabase()?.pathDAO()?.delete(path)
            repo.hasUpdatedDatabase = true
        }
        pruneAlbums()
        pruneArtists()
    }

    /**
     * Remove albums that no longer have any associated tracks
     */
    private suspend fun pruneAlbums() {
        val albumIDs = repo.getDatabase()?.albumDAO()?.queryAll()?.map { it.albumId }
        albumIDs?.forEach { albumID ->
            val numTracksForAlbum = repo.getDatabase()?.trackDAO()?.queryNumTracksForAlbum(albumID) ?: 0
            if (numTracksForAlbum <= 0) {
                logger.verbose(TAG, "Removing album from database: $albumID")
                repo.getDatabase()?.albumDAO()?.deleteByID(albumID)
            }
        }
    }

    /**
     * Remove artists that no longer have any associated tracks
     */
    private suspend fun pruneArtists() {
        val artistIDs = repo.getDatabase()?.artistDAO()?.queryAll()?.map { it.artistId }
        artistIDs?.forEach { artistID ->
            val numTracksForArtist = repo.getDatabase()?.trackDAO()?.queryNumTracksForArtist(artistID) ?: 0
            val numTracksForAlbumArtist = repo.getDatabase()?.trackDAO()?.queryNumTracksForAlbumArtist(artistID) ?: 0
            if (numTracksForArtist <= 0 && numTracksForAlbumArtist <= 0) {
                logger.verbose(TAG, "Removing artist from database: $artistID")
                repo.getDatabase()?.artistDAO()?.deleteByID(artistID)
            }
        }
    }

}
