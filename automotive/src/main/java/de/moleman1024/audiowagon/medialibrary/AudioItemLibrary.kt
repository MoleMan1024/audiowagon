/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary

import android.content.Context
import android.media.browse.MediaBrowser
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import androidx.media.utils.MediaConstants
import de.moleman1024.audiowagon.GUI
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.exceptions.CannotRecoverUSBException
import de.moleman1024.audiowagon.exceptions.NoAudioItemException
import de.moleman1024.audiowagon.filestorage.AudioFile
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.*
import de.moleman1024.audiowagon.repository.AudioItemRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

// could add one more category shown in header, maybe use for playlists

/*
These CONTENT_STYLE constants determine what the browse tree will look like on AAOS
https://developer.android.com/training/cars/media#apply_content_style
https://developers.google.com/cars/design/automotive-os/apps/media/interaction-model/browsing
*/
const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
private const val UPDATE_INDEX_NOTIF_FOR_EACH_NUM_ITEMS = 100
private const val TAG = "AudioItemLibr"
private val logger = Logger

class AudioItemLibrary(
    private val context: Context,
    private val audioFileStorage: AudioFileStorage,
    val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val gui: GUI
) {
    private val metadataMaker = AudioMetadataMaker(audioFileStorage)
    val storageToRepoMap = mutableMapOf<String, AudioItemRepository>()
    var isBuildingLibrary = false
    var numFilesSeenWhenBuildingLibrary = 0
    var libraryExceptionObservers = mutableListOf<(Exception) -> Unit>()
    private var isBuildLibraryCancelled: Boolean = false
    private data class LastAlbumArt(
        @Suppress("ArrayInDataClass") val bytes: ByteArray,
        val albumArtist: String,
        val album: String
    ) {
        override fun toString(): String {
            return "LastAlbumArt(albumArtist=$albumArtist, album=$album)"
        }
    }
    private var lastAlbumArt: LastAlbumArt? = null

    fun initRepository(storageID: String) {
        if (storageToRepoMap.containsKey(storageID)) {
            return
        }
        val repo = AudioItemRepository(storageID, context, dispatcher)
        storageToRepoMap[storageID] = repo
    }

    fun removeRepository(storageID: String) {
        if (!storageToRepoMap.containsKey(storageID)) {
            return
        }
        val repo = storageToRepoMap[storageID] ?: return
        repo.close()
        storageToRepoMap.remove(storageID)
    }

    /**
     * This function builds the whole audio library:
     * - consumes a channel of [AudioFile] produced during indexing of all storage locations (i.e. USB devices)
     * - creates a [AudioItemRepository] for each storage location found. Each repository provides access to a sqlite
     * database on disk
     * - extracts metadata for each [AudioFile] to create a [AudioItem]
     * - synchronizes each [AudioItem] with the corresponding [AudioItemRepository], updating and purging old entries
     * as needed
     *
     * A callback is used to update a pseudo media item entry in media browser because the notification is not that
     * noticeable in the pulldown menu
     */
    @ExperimentalCoroutinesApi
    suspend fun buildLibrary(channel: ReceiveChannel<AudioFile>, callback: () -> Unit) {
        isBuildingLibrary = true
        isBuildLibraryCancelled = false
        numFilesSeenWhenBuildingLibrary = 0
        // indicate indexing has started via callback function
        callback()
        channel.consumeEach { audioFile ->
            logger.verbose(TAG, "buildLibrary() received: $audioFile")
            if (!isBuildLibraryCancelled) {
                val repo: AudioItemRepository = storageToRepoMap[audioFile.storageID]
                    ?: throw RuntimeException("No repository for file: $audioFile")
                val trackIDInDatabase: Long? = repo.getDatabaseIDForTrack(audioFile)
                if (trackIDInDatabase == null) {
                    extractMetadataAndPopulateDB(audioFile, repo, coroutineContext)
                } else {
                    try {
                        val audioFileHasChanged = repo.hasAudioFileChangedForTrack(audioFile, trackIDInDatabase)
                        if (!audioFileHasChanged) {
                            repo.trackIDsToKeep.add(trackIDInDatabase)
                        } else {
                            repo.removeTrack(trackIDInDatabase)
                            extractMetadataAndPopulateDB(audioFile, repo, coroutineContext)
                        }
                    } catch (exc: RuntimeException) {
                        logger.exception(TAG, exc.message.toString(), exc)
                    }
                }
                numFilesSeenWhenBuildingLibrary++
                // We limit the number of indexing notification updates sent here. It seems Android will throttle
                // if too many notifications are posted and the last important notification indicating "finished" might be
                // ignored if too many are sent
                if (numFilesSeenWhenBuildingLibrary % UPDATE_INDEX_NOTIF_FOR_EACH_NUM_ITEMS == 0) {
                    gui.updateIndexingNotification(numFilesSeenWhenBuildingLibrary)
                    logger.flushToUSB()
                    callback()
                }
            } else {
                logger.debug(TAG, "Library build has been cancelled")
            }
        }
        logger.debug(TAG, "Channel drained in buildLibrary()")
        if (!isBuildLibraryCancelled) {
            storageToRepoMap.values.forEach { repo ->
                repo.clean()
            }
        }
        isBuildingLibrary = false
        isBuildLibraryCancelled = false
    }

    private suspend fun extractMetadataAndPopulateDB(
        audioFile: AudioFile,
        repo: AudioItemRepository,
        coroutineContext: CoroutineContext
    ) {
        try {
            val metadata: AudioItem = extractMetadataFrom(audioFile)
            if (isBuildLibraryCancelled) {
                return
            }
            repo.populateDatabaseFrom(audioFile, metadata)
        } catch (exc: IOException) {
            // this can happen for example for files with strange filenames, the file will be ignored
            logger.exception(TAG, "I/O exception when processing file: $audioFile", exc)
            if ("MAX_RECOVERY_ATTEMPTS|No filesystem|DataSource error".toRegex()
                    .containsMatchIn(exc.stackTraceToString())
            ) {
                logger.exceptionLogcatOnly(TAG, "I/O exception when processing file: $audioFile", exc)
                coroutineContext.cancel(CancellationException("Unrecoverable I/O exception in buildLibrary()"))
                notifyObservers(CannotRecoverUSBException())
            } else {
                logger.exception(TAG, "I/O exception when processing file: $audioFile", exc)
            }
        } catch (exc: RuntimeException) {
            logger.exception(TAG, "Exception when processing file: $audioFile", exc)
        }
    }

    /**
     * Returns list of [MediaItem] for given identifier based on the music library hierarchy.
     *
     * Due to the size limitations in onLoadChildren() in browser service, the content is split into groups of some
     * hundred items each in case of large media libraries.
     */
    suspend fun getMediaItemsStartingFrom(contentHierarchyID: ContentHierarchyID): List<MediaItem> {
        logger.debug(TAG, "Requested MediaItem content hierarchy from library: $contentHierarchyID")
        val contentHierarchyElement = createContentHierarchyElementForID(contentHierarchyID)
        return contentHierarchyElement.getMediaItems()
    }

    suspend fun getAudioItemsStartingFrom(contentHierarchyID: ContentHierarchyID): List<AudioItem> {
        logger.debug(TAG, "Requested AudioItem content hierarchy from library: $contentHierarchyID")
        val contentHierarchyElement = createContentHierarchyElementForID(contentHierarchyID)
        return contentHierarchyElement.getAudioItems()
    }

    private fun createContentHierarchyElementForID(contentHierarchyID: ContentHierarchyID): ContentHierarchyElement {
        return when (contentHierarchyID.type) {
            ContentHierarchyType.NONE -> ContentHierarchyNone(context, this)
            ContentHierarchyType.ROOT -> ContentHierarchyRoot(context, this)
            ContentHierarchyType.SHUFFLE_ALL_TRACKS -> ContentHierarchyShuffleAllTracks(context, this)
            ContentHierarchyType.ROOT_TRACKS -> ContentHierarchyRootTracks(context, this, audioFileStorage)
            ContentHierarchyType.ROOT_FILES -> ContentHierarchyRootFiles(context, this, audioFileStorage)
            ContentHierarchyType.ROOT_ALBUMS -> ContentHierarchyRootAlbums(context, this)
            ContentHierarchyType.ROOT_ARTISTS -> ContentHierarchyRootArtists(context, this)
            ContentHierarchyType.TRACK -> ContentHierarchyTrack(contentHierarchyID, context, this)
            ContentHierarchyType.ALBUM -> ContentHierarchyAlbum(contentHierarchyID, context, this)
            ContentHierarchyType.COMPILATION -> ContentHierarchyCompilation(contentHierarchyID, context, this)
            ContentHierarchyType.UNKNOWN_ALBUM -> ContentHierarchyUnknAlbum(contentHierarchyID, context, this)
            ContentHierarchyType.ARTIST -> ContentHierarchyArtist(contentHierarchyID, context, this)
            ContentHierarchyType.FILE -> ContentHierarchyFile(contentHierarchyID, context, this, audioFileStorage)
            ContentHierarchyType.DIRECTORY -> ContentHierarchyDirectory(
                contentHierarchyID, context, this, audioFileStorage
            )
            ContentHierarchyType.TRACK_GROUP -> ContentHierarchyGroupTracks(contentHierarchyID, context, this)
            ContentHierarchyType.ALBUM_GROUP -> ContentHierarchyGroupAlbums(contentHierarchyID, context, this)
            ContentHierarchyType.ARTIST_GROUP -> ContentHierarchyGroupArtists(contentHierarchyID, context, this)
            ContentHierarchyType.FILELIKE_GROUP -> ContentHierarchyGroupFileLike(
                contentHierarchyID, context, this, audioFileStorage
            )
            ContentHierarchyType.ALL_TRACKS_FOR_ARTIST -> ContentHierarchyAllTracksForArtist(
                contentHierarchyID, context, this
            )
            ContentHierarchyType.ALL_TRACKS_FOR_ALBUM -> ContentHierarchyAllTracksForAlbum(
                contentHierarchyID, context, this
            )
            ContentHierarchyType.ALL_TRACKS_FOR_COMPILATION -> ContentHierarchyAllTracksForCompilation(
                contentHierarchyID, context, this
            )
            ContentHierarchyType.ALL_TRACKS_FOR_UNKN_ALBUM -> ContentHierarchyAllTracksForUnknAlbum(
                contentHierarchyID, context, this
            )
            // TODO: right now this is not used because it can be quite slow to recursively index directories
            ContentHierarchyType.ALL_FILES_FOR_DIRECTORY -> ContentHierarchyAllFilesForDirectory(
                contentHierarchyID, context, this, audioFileStorage
            )
        }
    }

    suspend fun getAudioItemForTrack(contentHierarchyID: ContentHierarchyID): AudioItem {
        if (contentHierarchyID.type != ContentHierarchyType.TRACK) {
            throw IllegalArgumentException("Given content hierarchy ID is not for a track: $contentHierarchyID")
        }
        val trackContentHierarchy = ContentHierarchySingleTrack(contentHierarchyID, context, this)
        val audioItems: List<AudioItem> = trackContentHierarchy.getAudioItems()
        if (audioItems.isEmpty()) {
            throw NoAudioItemException("No track for content hierarchy ID: $contentHierarchyID")
        }
        var startIndex: Int
        startIndex = audioItems.indexOfFirst {
            ContentHierarchyElement.deserialize(it.id).trackID == contentHierarchyID.trackID
        }
        if (startIndex < 0) {
            startIndex = 0
        }
        return audioItems[startIndex]
    }

    suspend fun getAudioItemForFile(contentHierarchyID: ContentHierarchyID): AudioItem {
        if (contentHierarchyID.type != ContentHierarchyType.FILE) {
            throw IllegalArgumentException("Given content hierarchy ID is not for a file: $contentHierarchyID")
        }
        val fileContentHierarchy = ContentHierarchySingleFile(contentHierarchyID, context, this, audioFileStorage)
        val audioItems: List<AudioItem> = fileContentHierarchy.getAudioItems()
        if (audioItems.isEmpty()) {
            throw NoAudioItemException("No file for content hierarchy ID: $contentHierarchyID")
        }
        var startIndex: Int
        startIndex = audioItems.indexOfFirst {
            ContentHierarchyElement.deserialize(it.id).path == contentHierarchyID.path
        }
        if (startIndex < 0) {
            startIndex = 0
        }
        return audioItems[startIndex]
    }

    /**
     * This will determine what items in the browse tree will look like in the GUI
     */
    suspend fun createAudioItemDescription(
        audioItem: AudioItem,
        extras: Bundle? = null,
        paramContentHierarchyID: ContentHierarchyID? = null
    ): MediaDescriptionCompat {
        val contentHierarchyID = paramContentHierarchyID ?: ContentHierarchyElement.deserialize(audioItem.id)
        val builder = MediaDescriptionCompat.Builder().apply {
            when (contentHierarchyID.type) {
                ContentHierarchyType.ARTIST -> {
                    if (audioItem.artist.isNotBlank()) {
                        setTitle(audioItem.artist)
                        val numTracks = getNumTracksForArtist(contentHierarchyID.artistID)
                        val numAlbums = getNumAlbumsForArtist(contentHierarchyID.artistID)
                        val subTitle = context.getString(
                            R.string.browse_tree_num_albums_num_tracks, numAlbums, numTracks
                        )
                        setSubtitle(subTitle)
                    } else {
                        setTitle(context.getString(R.string.browse_tree_unknown_artist))
                    }
                    setIconUri(Uri.parse(RESOURCE_ROOT_URI
                            + context.resources.getResourceEntryName(R.drawable.baseline_person_24)))
                }
                ContentHierarchyType.ALBUM,
                ContentHierarchyType.UNKNOWN_ALBUM,
                ContentHierarchyType.COMPILATION -> {
                    if (audioItem.album.isNotBlank()) {
                        setTitle(audioItem.album)
                    } else {
                        setTitle(context.getString(R.string.browse_tree_unknown_album))
                    }
                    if (audioItem.albumArtist.isNotBlank()) {
                        setSubtitle(audioItem.albumArtist)
                    } else {
                        if (audioItem.artist.isNotBlank()) {
                            setSubtitle(audioItem.artist)
                        } else {
                            setSubtitle(context.getString(R.string.browse_tree_unknown_artist))
                        }
                    }
                    setIconUri(Uri.parse(RESOURCE_ROOT_URI
                            + context.resources.getResourceEntryName(R.drawable.baseline_album_24)))
                }
                ContentHierarchyType.TRACK -> {
                    setTitle(audioItem.title)
                    if (audioItem.artist.isNotBlank()) {
                        setSubtitle(audioItem.artist)
                    } else {
                        setSubtitle(context.getString(R.string.browse_tree_unknown_artist))
                    }
                    setIconUri(Uri.parse(RESOURCE_ROOT_URI
                            + context.resources.getResourceEntryName(R.drawable.baseline_music_note_24)))
                }
                ContentHierarchyType.FILE -> {
                    setTitle(audioItem.title)
                    setIconUri(Uri.parse(RESOURCE_ROOT_URI
                            + context.resources.getResourceEntryName(R.drawable.baseline_insert_drive_file_24)))
                }
                else -> {
                    throw AssertionError("Cannot create audio item description for type: ${contentHierarchyID.type}")
                }
            }
            setMediaId(ContentHierarchyElement.serialize(contentHierarchyID))
            setMediaUri(audioItem.uri)
        }
        extras?.let { builder.setExtras(it) }
        return builder.build()
    }

    fun createAudioItemForFile(audioFile: AudioFile): AudioItem {
        val audioItem = AudioItem()
        val contentHierarchyID = ContentHierarchyID(ContentHierarchyType.FILE)
        contentHierarchyID.path = audioFile.path
        audioItem.id = ContentHierarchyElement.serialize(contentHierarchyID)
        audioItem.uri = audioFile.uri
        audioItem.title = audioFile.name
        audioItem.browsPlayableFlags =
            audioItem.browsPlayableFlags.or(MediaBrowser.MediaItem.FLAG_PLAYABLE)
        return audioItem
    }

    private suspend fun getNumTracksForArtist(artistID: Long): Int {
        var numTracks = 0
        storageToRepoMap.values.forEach { repo ->
            numTracks += repo.getNumTracksForArtist(artistID)
        }
        return numTracks
    }

    private suspend fun getNumAlbumsForArtist(artistID: Long): Int {
        var numAlbums = 0
        val repo = getPrimaryRepository() ?: return 0
        numAlbums += repo.getNumAlbumsBasedOnTracksArtist(artistID)
        numAlbums += if (repo.getNumTracksWithUnknAlbumForArtist(artistID) > 0) 1 else 0
        return numAlbums
    }

    /**
     * Searches for tracks, albums, artists based on the given query provided by user.
     * We limit this to 10 items per category. Users will probably not look through a lot of items in the
     * car and it will cause BINDER TRANSACTION failures if too many results are returned
     */
    suspend fun searchMediaItems(query: String): MutableList<MediaItem> {
        val searchResults: MutableList<MediaItem> = mutableListOf()
        if (query.isBlank()) {
            // somehow AAOS does not pass an empty query, so this is not triggered and results are never cleared
            return searchResults
        }
        storageToRepoMap.values.forEach { repo ->
            val tracks = repo.searchTracks(query)
            tracks.forEach {
                val trackExtras = Bundle()
                trackExtras.putString(
                    MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, context
                        .getString(R.string.search_group_tracks)
                )
                val description = createAudioItemDescription(it, trackExtras)
                searchResults += MediaItem(description, it.browsPlayableFlags)
            }
            val artists = repo.searchArtists(query)
            artists.forEach {
                val artistExtras = Bundle()
                artistExtras.putString(
                    MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, context
                        .getString(R.string.search_group_artists)
                )
                val description = createAudioItemDescription(it, artistExtras)
                searchResults += MediaItem(description, it.browsPlayableFlags)
            }
            val albums = repo.searchAlbums(query)
            albums.forEach {
                val albumExtras = Bundle()
                albumExtras.putString(
                    MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, context
                        .getString(R.string.search_group_albums)
                )
                val description = createAudioItemDescription(it, albumExtras)
                searchResults += MediaItem(description, it.browsPlayableFlags)
            }
        }
        return searchResults
    }

    suspend fun searchArtists(query: String): MutableList<AudioItem> {
        return searchByType(query, AudioItemType.ARTIST)
    }

    suspend fun searchAlbums(query: String): MutableList<AudioItem> {
        return searchByType(query, AudioItemType.ALBUM)
    }

    suspend fun searchTracks(query: String): MutableList<AudioItem> {
        return searchByType(query, AudioItemType.TRACK)
    }

    suspend fun searchTrackByArtist(track: String, artist: String): MutableList<AudioItem> {
        val searchResults: MutableList<AudioItem> = mutableListOf()
        if (track.isBlank() || artist.isBlank()) {
            return searchResults
        }
        val repo = getPrimaryRepository() ?: return searchResults
        searchResults += repo.searchTrackByArtist(track, artist)
        return searchResults
    }

    suspend fun searchTrackByAlbum(track: String, album: String): MutableList<AudioItem> {
        val searchResults: MutableList<AudioItem> = mutableListOf()
        if (track.isBlank() || album.isBlank()) {
            return searchResults
        }
        val repo = getPrimaryRepository() ?: return searchResults
        searchResults += repo.searchTrackByAlbum(track, album)
        return searchResults
    }

    suspend fun searchAlbumByArtist(album: String, artist: String): MutableList<AudioItem> {
        val searchResults: MutableList<AudioItem> = mutableListOf()
        if (album.isBlank() || artist.isBlank()) {
            return searchResults
        }
        val repo = getPrimaryRepository() ?: return searchResults
        searchResults += repo.searchAlbumByArtist(album, artist)
        return searchResults
    }

    private suspend fun searchByType(query: String, type: AudioItemType): MutableList<AudioItem> {
        val searchResults: MutableList<AudioItem> = mutableListOf()
        if (query.isBlank()) {
            return searchResults
        }
        val repo = getPrimaryRepository() ?: return searchResults
        searchResults += when (type) {
            AudioItemType.ARTIST -> repo.searchArtists(query)
            AudioItemType.ALBUM -> repo.searchAlbums(query)
            AudioItemType.TRACK -> repo.searchTracks(query)
            AudioItemType.UNSPECIFIC -> searchUnspecific(query)
        }
        return searchResults
    }

    suspend fun searchUnspecific(query: String): MutableList<AudioItem> {
        val searchResults: MutableList<AudioItem> = mutableListOf()
        if (query.isBlank()) {
            return searchResults
        }
        val repo = getPrimaryRepository() ?: return searchResults
        searchResults += repo.searchTracksForArtist(query)
        searchResults += repo.searchTracksForAlbum(query)
        searchResults += repo.searchTracks(query)
        return searchResults.distinctBy {
            val contentHierarchyID = ContentHierarchyElement.deserialize(it.id)
            contentHierarchyID.trackID
        }.toMutableList()
    }

    fun createMetadataForItem(audioItem: AudioItem): MediaMetadataCompat {
        logger.debug(TAG, "createMetadataForItem($audioItem)")
        return metadataMaker.createMetadataForItem(audioItem)
    }

    fun getAlbumArtForItem(audioItem: AudioItem): ByteArray? {
        var albumArtBytes = metadataMaker.getEmbeddedAlbumArtForAudioItem(audioItem)
        if (albumArtBytes != null) {
            val albumArtResized = metadataMaker.resizeAlbumArt(albumArtBytes)
            logger.debug(TAG, "Got album art with size: ${albumArtResized?.size}")
            lastAlbumArt = null
            return albumArtResized
        }
        // no embedded album art found, check for image in album directory
        if (lastAlbumArt != null
            && audioItem.albumArtist.isNotBlank() && audioItem.albumArtist == lastAlbumArt?.albumArtist
            && audioItem.album.isNotBlank() && audioItem.album == lastAlbumArt?.album
        ) {
            logger.debug(TAG, "Same album+artist, re-using last album art: $lastAlbumArt")
            return lastAlbumArt!!.bytes
        }
        albumArtBytes = audioFileStorage.getAlbumArtInDirectoryForURI(audioItem.uri)
        if (albumArtBytes != null) {
            val albumArtResized = metadataMaker.resizeAlbumArt(albumArtBytes)
            if (albumArtResized != null) {
                logger.debug(TAG, "Found album art in directory with size: ${albumArtResized.size}")
                if (audioItem.albumArtist.isNotBlank() && audioItem.album.isNotBlank()) {
                    lastAlbumArt = LastAlbumArt(albumArtResized, audioItem.albumArtist, audioItem.album)
                }
            }
            return albumArtResized
        }
        logger.warning(TAG, "Could not retrieve any album art")
        return albumArtBytes
    }

    fun getRepoForContentHierarchyID(contentHierarchyID: ContentHierarchyID): AudioItemRepository? {
        val storageID = contentHierarchyID.storageID
        return storageToRepoMap[storageID]
    }

    fun getAllStorageIDs(): List<String> {
        return storageToRepoMap.keys.toList()
    }

    fun areAnyReposAvail(): Boolean {
        return storageToRepoMap.isNotEmpty()
    }

    fun shutdown() {
        logger.debug(TAG, "shutdown()")
        storageToRepoMap.values.forEach { it.close() }
        storageToRepoMap.clear()
        lastAlbumArt = null
    }

    fun suspend() {
        logger.debug(TAG, "suspend()")
        storageToRepoMap.values.forEach { it.close() }
        storageToRepoMap.clear()
        lastAlbumArt = null
    }

    private fun notifyObservers(exc: Exception) {
        libraryExceptionObservers.forEach { it(exc) }
    }

    fun extractMetadataFrom(audioFile: AudioFile): AudioItem {
        return metadataMaker.extractMetadataFrom(audioFile)
    }

    fun cancelBuildLibrary() {
        if (isBuildingLibrary) {
            logger.debug(TAG, "cancelBuildLibrary()")
            isBuildLibraryCancelled = true
            isBuildingLibrary = false
        }
    }

    // TODO: we do not support multiple repositories right now
    fun getPrimaryRepository(): AudioItemRepository? {
        if (storageToRepoMap.isEmpty()) {
            return null
        }
        return storageToRepoMap.values.toTypedArray()[0]
    }

    suspend fun getPseudoCompilationArtistID(): Long? {
        return getPrimaryRepository()?.getPseudoCompilationArtistID()
    }

}
