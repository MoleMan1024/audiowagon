/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.repository

import android.content.Context
import android.media.browse.MediaBrowser
import android.net.Uri
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.filestorage.AudioFile
import de.moleman1024.audiowagon.filestorage.Directory
import de.moleman1024.audiowagon.filestorage.FileLike
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.*
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyElement
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyID
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyType
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.DATABASE_ID_UNKNOWN
import de.moleman1024.audiowagon.repository.entities.*
import kotlinx.coroutines.*
import java.util.*
import kotlin.collections.ArrayDeque

private const val TAG = "RepositoryQuery"
private val logger = Logger

@ExperimentalCoroutinesApi
class RepositoryQuery(
    private val repo: AudioItemRepository,
    private val dispatcher: CoroutineDispatcher,
    private val storageID: String,
    private val context: Context
) {

    suspend fun getTrack(id: Long): AudioItem {
        val track: Track = withContext(dispatcher) {
            repo.getDatabase()?.trackDAO()?.queryByID(id) ?: throw RuntimeException("No track for id: $id")
        }
        return createAudioItemForTrack(track)
    }

    suspend fun getAlbumsForArtist(artistID: Long): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val albums: List<Album> = withContext(dispatcher) {
            repo.getDatabase()?.albumDAO()?.queryAlbumsByArtist(artistID) ?: listOf()
        }
        val albumIDs: MutableSet<Long> = mutableSetOf()
        for (album in albums) {
            yield()
            if (repo.isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val audioItemAlbum: AudioItem = createAudioItemForAlbum(album, artistID)
            items += audioItemAlbum
            albumIDs.add(album.albumId)
        }
        val numTracksWithoutAlbum: Int = withContext(dispatcher) {
            repo.getDatabase()?.trackDAO()?.queryNumTracksByArtistAlbumUnkn(artistID) ?: 0
        }
        if (numTracksWithoutAlbum > 0) {
            logger.debug(TAG, "Artist $artistID has $numTracksWithoutAlbum tracks without album info")
            val unknownAlbum: AudioItem = createUnknAlbumAudioItemForArtist(artistID)
            items.add(0, unknownAlbum)
        }
        val compilationAlbums: List<Album> = getCompilationAlbumsForArtist(artistID)
        for (album in compilationAlbums) {
            yield()
            if (repo.isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            if (album.albumId in albumIDs) {
                logger.verbose(TAG, "Skipping compilation album with ID ${album.albumId} already in result list")
                continue
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
        val tracks: List<Track> = repo.getDatabase()?.trackDAO()?.queryTracksByAlbum(albumID) ?: listOf()
        for (track in tracks) {
            yield()
            if (repo.isClosed) {
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
        val tracks: List<Track> = repo.getDatabase()?.trackDAO()?.queryTracksByArtist(artistID) ?: listOf()
        for (track in tracks) {
            yield()
            if (repo.isClosed) {
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
        val tracks: List<Track> = repo.getDatabase()?.trackDAO()?.queryTracksByArtistAndAlbum(artistID, albumID) ?: listOf()
        for (track in tracks) {
            yield()
            if (repo.isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val audioItemTrack: AudioItem = createAudioItemForTrack(track, artistID, albumID)
            items += audioItemTrack
        }
        logger.debug(TAG, "Returning ${items.size} tracks for album $albumID and artist $artistID")
        return if (albumID != DATABASE_ID_UNKNOWN) {
            items.sortedWith(compareBy({ it.discNum }, { it.trackNum }))
        } else {
            items.sortedWith(compareBy({ it.sortName.lowercase() }, { it.title.lowercase() }))
        }
    }

    suspend fun getNumTracksForAlbumAndArtist(albumID: Long, artistID: Long) = withContext(dispatcher) {
        repo.getDatabase()?.trackDAO()?.queryNumTracksByArtistAndAlbum(artistID, albumID) ?: 0
    }

    @Suppress("RedundantSuspendModifier")
    suspend fun getNumTracksForArtist(artistID: Long): Int {
        return if (artistID != repo.repoUpdate.getPseudoCompilationArtistID()) {
            repo.getDatabase()?.trackDAO()?.queryNumTracksForArtist(artistID) ?: 0
        } else {
            repo.getDatabase()?.trackDAO()?.queryNumTracksForArtistViaAlbums(artistID) ?: 0
        }
    }

    @Suppress("RedundantSuspendModifier")
    suspend fun getNumTracksForAlbumArtist(artistID: Long): Int {
        return if (artistID != repo.repoUpdate.getPseudoCompilationArtistID()) {
            repo.getDatabase()?.trackDAO()?.queryNumTracksForAlbumArtist(artistID) ?: 0
        } else {
            repo.getDatabase()?.trackDAO()?.queryNumTracksForArtistViaAlbums(artistID) ?: 0
        }
    }

    @Suppress("RedundantSuspendModifier")
    suspend fun getNumAlbumsBasedOnTracksArtist(artistID: Long): Int {
        return if (artistID != repo.repoUpdate.getPseudoCompilationArtistID()) {
            repo.getDatabase()?.trackDAO()?.queryNumAlbumsByTrackArtist(artistID) ?: 0
        } else {
            repo.getDatabase()?.albumDAO()?.queryNumAlbumsByArtist(artistID) ?: 0
        }
    }

    @Suppress("RedundantSuspendModifier")
    suspend fun getNumAlbumsBasedOnTracksAlbumArtist(artistID: Long): Int {
        return if (artistID != repo.repoUpdate.getPseudoCompilationArtistID()) {
            repo.getDatabase()?.trackDAO()?.queryNumAlbumsByTrackAlbumArtist(artistID) ?: 0
        } else {
            repo.getDatabase()?.albumDAO()?.queryNumAlbumsByArtist(artistID) ?: 0
        }
    }

    suspend fun getTracksWithUnknAlbumForArtist(artistID: Long): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val tracks: List<Track> = repo.getDatabase()?.trackDAO()?.queryTracksByArtistWhereAlbumUnknown(artistID) ?: listOf()
        for (track in tracks) {
            yield()
            if (repo.isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val audioItemTrack: AudioItem = createAudioItemForTrack(track, artistID, DATABASE_ID_UNKNOWN)
            items += audioItemTrack
        }
        logger.debug(TAG, "Returning ${items.size} tracks for unknown album for artist $artistID")
        return items
    }

    suspend fun getTracksWithUnknAlbum(): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val tracks: List<Track> = repo.getDatabase()?.trackDAO()?.queryTracksWhereAlbumUnknown() ?: listOf()
        for (track in tracks) {
            yield()
            if (repo.isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val audioItemTrack: AudioItem = createAudioItemForTrack(
                track,
                artistID = DATABASE_ID_UNKNOWN,
                albumID = DATABASE_ID_UNKNOWN
            )
            items += audioItemTrack
        }
        logger.debug(TAG, "Returning ${items.size} tracks for unknown album for unknown artist")
        return items
    }

    suspend fun getNumTracksWithUnknAlbumForArtist(artistID: Long) = withContext(dispatcher) {
        repo.getDatabase()?.trackDAO()?.queryNumTracksByArtistAndAlbum(artistID, DATABASE_ID_UNKNOWN) ?: 0
    }

    suspend fun getNumTracksWithUnknAlbum() = withContext(dispatcher) {
        repo.getDatabase()?.trackDAO()?.queryNumTracksAlbumUnknown() ?: 0
    }

    suspend fun getNumTracksWithUnknAlbumForAlbumArtist(artistID: Long) = withContext(dispatcher) {
        repo.getDatabase()?.trackDAO()?.queryNumTracksByAlbumArtistAndAlbum(artistID, DATABASE_ID_UNKNOWN) ?: 0
    }

    suspend fun getDatabaseIDForTrack(audioFile: AudioFile): Long? {
        return repo.getDatabase()?.trackDAO()?.queryIDByURI(audioFile.uri.toString())
    }

    suspend fun getDatabaseIDForPath(fileOrDir: FileLike): Long? {
        return repo.getDatabase()?.pathDAO()?.queryIDByURI(fileOrDir.path)
    }

    // TODO: this is probably inefficient
    private suspend fun getCompilationAlbumsForArtist(artistID: Long): List<Album> {
        val pseudoCompilationArtistName = repo.repoUpdate.getPseudoCompilationArtistNameEnglish()
        val variousArtistsInDB: Artist =
            repo.getDatabase()?.artistDAO()?.queryByName(pseudoCompilationArtistName) ?: return listOf()
        val allCompilationAlbums = repo.getDatabase()?.albumDAO()?.queryByArtist(variousArtistsInDB.artistId) ?: listOf()
        val matchingCompilationAlbums = mutableListOf<Album>()
        for (album in allCompilationAlbums) {
            yield()
            if (repo.isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val numTracks = repo.getDatabase()?.trackDAO()?.queryNumTracksByArtistForAlbum(artistID, album.albumId) ?: 0
            if (numTracks > 0) {
                matchingCompilationAlbums.add(album)
            }
        }
        return matchingCompilationAlbums
    }

    suspend fun getAllTracks(): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val allTracks: List<Track> = withContext(dispatcher) {
            repo.getDatabase()?.trackDAO()?.queryAll() ?: listOf()
        }
        for (track in allTracks) {
            yield()
            if (repo.isClosed) {
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
        repo.getDatabase()?.trackDAO()?.queryNumTracks() ?: 0
    }

    suspend fun getNumPaths(): Int = withContext(dispatcher) {
        repo.getDatabase()?.pathDAO()?.queryNumPaths() ?: 0
    }

    suspend fun getNumTracksForAlbum(albumID: Long): Int = withContext(dispatcher) {
        repo.getDatabase()?.trackDAO()?.queryNumTracksForAlbum(albumID) ?: 0
    }

    // TODO: many similar functions in here
    suspend fun getTracksLimitOffset(maxNumRows: Int, offsetRows: Int): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val tracksAtOffset: List<Track> = withContext(dispatcher) {
            repo.getDatabase()?.trackDAO()?.queryTracksLimitOffset(maxNumRows, offsetRows) ?: listOf()
        }
        for (track in tracksAtOffset) {
            yield()
            if (repo.isClosed) {
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
            repo.getDatabase()?.trackDAO()?.queryTracksForArtistAlbumLimitOffset(maxNumRows, offsetRows, artistID,
                albumID) ?: listOf()
        }
        for (track in tracksAtOffset) {
            yield()
            if (repo.isClosed) {
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
            repo.getDatabase()?.trackDAO()?.queryTracksForAlbumLimitOffset(maxNumRows, offsetRows, albumID) ?: listOf()
        }
        for (track in tracksAtOffset) {
            yield()
            if (repo.isClosed) {
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
            repo.getDatabase()?.trackDAO()?.queryTracksForArtistLimitOffset(maxNumRows, offsetRows, artistID) ?: listOf()
        }
        for (track in tracksAtOffset) {
            yield()
            if (repo.isClosed) {
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
        val tracks: List<Track> = repo.getDatabase()?.trackDAO()?.queryRandom(maxNumItems) ?: listOf()
        for (track in tracks) {
            yield()
            if (repo.isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val audioItemTrack: AudioItem = createAudioItemForTrack(track)
            items += audioItemTrack
        }
        logger.debug(TAG, "Returning ${items.size} random tracks")
        return items
    }

    suspend fun createAudioItemForTrack(
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
                repo.getDatabase()?.artistDAO()?.queryByID(track.parentArtistId)
            }
            artistForTrack?.let { audioItemTrack.artist = it.name }
        }
        if (track.parentAlbumArtistId >= 0) {
            val albumArtistForTrack: Artist? = withContext(dispatcher) {
                repo.getDatabase()?.artistDAO()?.queryByID(track.parentAlbumArtistId)
            }
            albumArtistForTrack?.let { audioItemTrack.albumArtist = it.name }
        }
        if (track.parentAlbumId >= 0) {
            val albumForTrack: Album? = withContext(dispatcher) {
                repo.getDatabase()?.albumDAO()?.queryByID(track.parentAlbumId)
            }
            albumForTrack?.let { audioItemTrack.album = it.name }
        }
        audioItemTrack.title = track.name
        if (track.sortName.isNotBlank()) {
            audioItemTrack.sortName = track.sortName
        } else {
            audioItemTrack.sortName = track.name
        }
        audioItemTrack.trackNum = track.trackNum
        audioItemTrack.discNum = track.discNum
        audioItemTrack.year = Util.yearEpochTimeToShort(track.yearEpochTime)
        audioItemTrack.browsPlayableFlags = audioItemTrack.browsPlayableFlags.or(MediaBrowser.MediaItem.FLAG_PLAYABLE)
        audioItemTrack.durationMS = track.durationMS
        audioItemTrack.albumArtURI = Uri.parse(track.albumArtURIString)
        return audioItemTrack
    }

    suspend fun getAllAlbums(): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val allAlbums: List<Album> = repo.getDatabase()?.albumDAO()?.queryAll() ?: listOf()
        for (album in allAlbums) {
            yield()
            if (repo.isClosed) {
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
        return items.sortedWith(compareBy( { it.sortName.lowercase() }, { it.album.lowercase() } ))
    }

    suspend fun getAudioItemForUnknownAlbum(): AudioItem? = withContext(dispatcher) {
        val numTracksUnknownAlbum = repo.getDatabase()?.trackDAO()?.queryNumTracksAlbumUnknown() ?: 0
        if (numTracksUnknownAlbum > 0) {
            return@withContext createUnknownAlbumAudioItem()
        }
        return@withContext null
    }

    suspend fun getNumAlbums(): Int = withContext(dispatcher) {
        repo.getDatabase()?.albumDAO()?.queryNumAlbums() ?: 0
    }

    suspend fun getAlbumsLimitOffset(maxNumRows: Int, offsetRows: Int): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val albumsAtOffset: List<Album> = withContext(dispatcher) {
            repo.getDatabase()?.albumDAO()?.queryAlbumsLimitOffset(maxNumRows, offsetRows) ?: listOf()
        }
        for (album in albumsAtOffset) {
            yield()
            if (repo.isClosed) {
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
            repo.getDatabase()?.albumDAO()?.queryAlbumsForArtistLimitOffset(maxNumRows, offsetRows, artistID) ?: listOf()
        }
        for (album in albumsAtOffset) {
            yield()
            if (repo.isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val audioItemTrack: AudioItem = createAudioItemForAlbum(album, artistID)
            items += audioItemTrack
        }
        logger.debug(TAG, "Returning ${items.size} albums for artist $artistID offset: $offsetRows (limit $maxNumRows)")
        return items
    }

    suspend fun createAudioItemForAlbum(album: Album, artistID: Long? = null): AudioItem {
        val audioItemAlbum = AudioItem()
        val contentHierarchyID = ContentHierarchyID(ContentHierarchyType.ALBUM)
        contentHierarchyID.storageID = storageID
        contentHierarchyID.albumID = album.albumId
        if (artistID != null) {
            contentHierarchyID.albumArtistID = artistID
        }
        audioItemAlbum.id = ContentHierarchyElement.serialize(contentHierarchyID)
        audioItemAlbum.album = album.name
        if (album.sortName.isNotBlank()) {
            audioItemAlbum.sortName = album.sortName
        } else {
            audioItemAlbum.sortName = album.name
        }
        val artistForAlbum: Artist? = withContext(dispatcher) {
            repo.getDatabase()?.artistDAO()?.queryByID(album.parentArtistId)
        }
        artistForAlbum?.let { audioItemAlbum.artist = it.name }
        audioItemAlbum.albumArtist = audioItemAlbum.artist
        audioItemAlbum.albumArtURI = Uri.parse(album.albumArtURIString)
        audioItemAlbum.browsPlayableFlags = audioItemAlbum.browsPlayableFlags.or(MediaBrowser.MediaItem.FLAG_BROWSABLE)
        // Setting the FLAG_PLAYABLE additionally here does not do anything in Android Automotive. Instead we add
        // pseudo-"play all" items on each album instead.
        return audioItemAlbum
    }

    @Suppress("RedundantSuspendModifier")
    suspend fun createAudioItemForArtist(artist: Artist): AudioItem {
        val audioItemArtist = AudioItem()
        val contentHierarchyID = ContentHierarchyID(ContentHierarchyType.ARTIST)
        contentHierarchyID.storageID = storageID
        if (artist.isAlbumArtist) {
            contentHierarchyID.albumArtistID = artist.artistId
            audioItemArtist.albumArtist = artist.name
        } else {
            contentHierarchyID.artistID = artist.artistId
            audioItemArtist.artist = artist.name
        }
        audioItemArtist.id = ContentHierarchyElement.serialize(contentHierarchyID)
        if (artist.sortName.isNotBlank()) {
            audioItemArtist.sortName = artist.sortName
        } else {
            audioItemArtist.sortName = artist.name
        }
        audioItemArtist.browsPlayableFlags = audioItemArtist.browsPlayableFlags.or(MediaBrowser.MediaItem.FLAG_BROWSABLE)
        return audioItemArtist
    }

    private fun createUnknownAlbumAudioItem(): AudioItem {
        val unknownAlbum = AudioItem()
        val contentHierarchyID = ContentHierarchyID(ContentHierarchyType.UNKNOWN_ALBUM)
        contentHierarchyID.storageID = storageID
        contentHierarchyID.albumID = DATABASE_ID_UNKNOWN
        unknownAlbum.id = ContentHierarchyElement.serialize(contentHierarchyID)
        unknownAlbum.album = context.getString(R.string.browse_tree_unknown_album)
        unknownAlbum.sortName = unknownAlbum.album
        unknownAlbum.browsPlayableFlags = unknownAlbum.browsPlayableFlags.or(MediaBrowser.MediaItem.FLAG_BROWSABLE)
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
            repo.getDatabase()?.artistDAO()?.queryByID(artistID)
        }
        artistForAlbum?.let { unknownAlbum.artist = it.name }
        return unknownAlbum
    }

    suspend fun getAllAlbumAndCompilationArtists(): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val allArtists: List<Artist> = repo.getDatabase()?.artistDAO()?.queryAllAlbumAndCompilationArtists() ?: listOf()
        for (artist in allArtists) {
            yield()
            if (repo.isClosed) {
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
        return items.sortedWith(compareBy({ it.sortName.lowercase() }, { it.artist.lowercase() }))
    }

    suspend fun getNumAlbumAndCompilationArtists(): Int = withContext(dispatcher) {
        repo.getDatabase()?.artistDAO()?.queryNumAlbumAndCompilationArtists() ?: 0
    }

    suspend fun getArtistsLimitOffset(maxNumRows: Int, offsetRows: Int): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val artistsAtOffset: List<Artist> = withContext(dispatcher) {
            repo.getDatabase()?.artistDAO()?.queryArtistsLimitOffset(maxNumRows, offsetRows) ?: listOf()
        }
        for (artist in artistsAtOffset) {
            yield()
            if (repo.isClosed) {
                logger.warning(TAG, "Repository was closed")
                return listOf()
            }
            val audioItemArtist = createAudioItemForArtist(artist)
            items += audioItemArtist
        }
        logger.debug(TAG, "Returning ${items.size} artists for offset: $offsetRows (limit $maxNumRows)")
        return items
    }

    suspend fun getFilesInDirRecursive(rootDir: String, numMaxItems: Int): List<AudioItem> {
        logger.debug(TAG, "getFilesInDirRecursive(rootDir=$rootDir,numMaxItems=$numMaxItems)")
        val allFilesInDirs = mutableMapOf<String, Unit>()
        val allDirectoriesVisited = mutableMapOf<String, Unit>()
        val stack = ArrayDeque<Iterator<Path>>()
        val filesInRootDir = withContext(dispatcher) {
            this.ensureActive()
            repo.getDatabase()?.pathDAO()?.queryFilesRecursive(rootDir) ?: listOf()
        }
        logger.verbose(TAG, "filesInRootDir=$filesInRootDir")
        stack.add(filesInRootDir.iterator())
        while (stack.isNotEmpty()) {
            if (allFilesInDirs.size > numMaxItems) {
                logger.warning(TAG, "Limiting number of files retrieved recursively: $rootDir")
                break
            }
            if (stack.last().hasNext()) {
                val fileOrDir = stack.last().next()
                if (!fileOrDir.isDirectory) {
                    allFilesInDirs[fileOrDir.absolutePath] = Unit
                } else {
                    if (!allDirectoriesVisited.containsKey(fileOrDir.absolutePath)) {
                        allDirectoriesVisited[fileOrDir.absolutePath] = Unit
                        val filesInSubDir = withContext(dispatcher) {
                            this.ensureActive()
                            repo.getDatabase()?.pathDAO()?.queryFilesRecursive(fileOrDir.absolutePath) ?: listOf()
                        }
                        logger.verbose(TAG, "filesInSubDir=$filesInSubDir")
                        stack.add(filesInSubDir.iterator())
                    }
                }
            } else {
                stack.removeLast()
            }
        }
        val items: MutableList<AudioItem> = mutableListOf()
        allFilesInDirs.forEach { (file, _) ->
            val uri = Util.createURIForPath(storageID, file)
            val audioFile = AudioFile(uri)
            val audioItem = AudioItemLibrary.createAudioItemForFile(audioFile)
            items.add(audioItem)
        }
        return items
    }

    suspend fun getRootDirectories(): List<Directory> {
        val directories: MutableList<Directory> = mutableListOf()
        val paths: List<Path> = withContext(dispatcher) {
            repo.getDatabase()?.pathDAO()?.queryRootDirs() ?: listOf()
        }
        paths.forEach {
            val directory = Directory(Util.createURIForPath(storageID, it.absolutePath))
            directory.lastModifiedDate = Date(it.lastModifiedEpochTime)
            directories += directory
        }
        return directories
    }

    suspend fun getAudioItemForUnknownArtist(): AudioItem? = withContext(dispatcher) {
        val numTracksUnknownArtist = repo.getDatabase()?.trackDAO()?.queryNumTracksArtistUnknown() ?: 0
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
        unknownArtist.sortName = unknownArtist.artist
        unknownArtist.browsPlayableFlags = unknownArtist.browsPlayableFlags.or(MediaBrowser.MediaItem.FLAG_BROWSABLE)
        return unknownArtist
    }

    suspend fun getTrackGroup(groupIndex: Int): Pair<AudioItem, AudioItem> {
        var firstLastAudioItem = Pair(AudioItem(), AudioItem())
        val trackGroup: TrackGroup =
            repo.getDatabase()?.trackDAO()?.queryTrackGroupByIndex(groupIndex) ?: return firstLastAudioItem
        val firstGroupTrack =
            repo.getDatabase()?.trackDAO()?.queryByID(trackGroup.startTrackId) ?: return firstLastAudioItem
        val lastGroupTrack =
            repo.getDatabase()?.trackDAO()?.queryByID(trackGroup.endTrackId) ?: return firstLastAudioItem
        firstLastAudioItem = Pair(createAudioItemForTrack(firstGroupTrack), createAudioItemForTrack(lastGroupTrack))
        return firstLastAudioItem
    }

    suspend fun getAlbumGroup(groupIndex: Int): Pair<AudioItem, AudioItem> {
        var firstLastAudioItem = Pair(AudioItem(), AudioItem())
        val albumGroup: AlbumGroup =
            repo.getDatabase()?.albumDAO()?.queryAlbumGroupByIndex(groupIndex) ?: return firstLastAudioItem
        val firstGroupAlbum =
            repo.getDatabase()?.albumDAO()?.queryByID(albumGroup.startAlbumId) ?: return firstLastAudioItem
        val lastGroupAlbum =
            repo.getDatabase()?.albumDAO()?.queryByID(albumGroup.endAlbumId) ?: return firstLastAudioItem
        firstLastAudioItem = Pair(createAudioItemForAlbum(firstGroupAlbum), createAudioItemForAlbum(lastGroupAlbum))
        return firstLastAudioItem
    }

    suspend fun getArtistGroup(groupIndex: Int): Pair<AudioItem, AudioItem> {
        var firstLastAudioItem = Pair(AudioItem(), AudioItem())
        val artistGroup: ArtistGroup =
            repo.getDatabase()?.artistDAO()?.queryArtistGroupByIndex(groupIndex) ?: return firstLastAudioItem
        val firstGroupArtist =
            repo.getDatabase()?.artistDAO()?.queryByID(artistGroup.startArtistId) ?: return firstLastAudioItem
        val lastGroupArtist =
            repo.getDatabase()?.artistDAO()?.queryByID(artistGroup.endArtistId) ?: return firstLastAudioItem
        firstLastAudioItem = Pair(createAudioItemForArtist(firstGroupArtist), createAudioItemForArtist(lastGroupArtist))
        return firstLastAudioItem
    }

}
