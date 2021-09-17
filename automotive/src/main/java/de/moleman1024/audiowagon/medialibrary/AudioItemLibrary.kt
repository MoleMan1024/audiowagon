/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary

import android.content.Context
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import androidx.annotation.VisibleForTesting
import androidx.media.utils.MediaConstants
import de.moleman1024.audiowagon.GUI
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.exceptions.CannotRecoverUSBException
import de.moleman1024.audiowagon.filestorage.AudioFile
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.repository.AudioItemRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

const val CONTENT_HIERARCHY_ID_ROOT: String = "/"
const val CONTENT_HIERARCHY_TRACKS_ROOT: String = "__TRACKS__"
const val CONTENT_HIERARCHY_ALBUMS_ROOT: String = "__ALBUMS__"
const val CONTENT_HIERARCHY_ARTISTS_ROOT: String = "__ARTISTS__"
const val CONTENT_HIERARCHY_SHUFFLE_ALL: String = "__SHUFFLE_ALL__"
const val CONTENT_HIERARCHY_NONE: String = "__NONE__"
// could add one more category shown in header, maybe use for playlists

/*
These CONTENT_STYLE constants determine what the browse tree will look like on AAOS
https://developer.android.com/training/cars/media#apply_content_style
https://developers.google.com/cars/design/automotive-os/apps/media/interaction-model/browsing
*/
const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
const val CONTENT_STYLE_LIST_ITEM_HINT_VALUE = 1

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
    var isBuildingLibray = false
    var numFilesSeenWhenBuildingLibrary = 0
    var libraryExceptionObservers = mutableListOf<(Exception) -> Unit>()

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
        isBuildingLibray = true
        numFilesSeenWhenBuildingLibrary = 0
        // indicate indexing has started via callback function
        callback()
        channel.consumeEach { audioFile ->
            logger.verbose(TAG, "buildLibrary() received: $audioFile")
            val repo: AudioItemRepository = storageToRepoMap[audioFile.getStorageID()]
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
            if (numFilesSeenWhenBuildingLibrary % 50 == 0) {
                gui.updateIndexingNotification(numFilesSeenWhenBuildingLibrary)
                callback()
            }
        }
        logger.debug(TAG, "Channel drained in buildLibrary()")
        storageToRepoMap.values.forEach { repo ->
            repo.clean()
        }
        isBuildingLibray = false
    }

    private fun extractMetadataAndPopulateDB(
        audioFile: AudioFile,
        repo: AudioItemRepository,
        coroutineContext: CoroutineContext
    ) {
        try {
            val metadata: AudioItem = metadataMaker.extractMetadataFrom(audioFile)
            repo.populateDatabaseFrom(audioFile, metadata)
        } catch (exc: IOException) {
            // this can happen for example for files with strange filenames, the file will be ignored
            logger.exception(TAG, "I/O exception when processing file: $audioFile", exc)
            if ("MAX_RECOVERY_ATTEMPTS|No filesystem".toRegex().containsMatchIn(exc.stackTraceToString())) {
                coroutineContext.cancel(CancellationException("Unrecoverable I/O exception in buildLibrary()"))
                notifyObservers(CannotRecoverUSBException())
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
    suspend fun getMediaItemsStartingFrom(contentHierarchyID: String): List<MediaItem> {
        logger.debug(TAG, "Requested MediaItem content hierarchy from library: $contentHierarchyID")
        assertContentHierarchyIDNotEmpty(contentHierarchyID)
        val contentHierarchyElement = createContentHierarchyElementForID(contentHierarchyID)
        return contentHierarchyElement.getMediaItems()
    }

    suspend fun getAudioItemsStartingFrom(contentHierarchyID: String): List<AudioItem> {
        logger.debug(TAG, "Requested AudioItem content hierarchy from library: $contentHierarchyID")
        assertContentHierarchyIDNotEmpty(contentHierarchyID)
        val contentHierarchyElement = createContentHierarchyElementForID(contentHierarchyID)
        return contentHierarchyElement.getAudioItems()
    }

    private fun createContentHierarchyElementForID(contentHierarchyID: String): ContentHierarchyElement {
        assertContentHierarchyIDNotEmpty(contentHierarchyID)
        return when (contentHierarchyID) {
            CONTENT_HIERARCHY_ID_ROOT -> ContentHierarchyRoot(context, this)
            CONTENT_HIERARCHY_TRACKS_ROOT -> ContentHierarchyAllTracks(context, this, audioFileStorage)
            CONTENT_HIERARCHY_ALBUMS_ROOT -> ContentHierarchyAllAlbums(context, this)
            CONTENT_HIERARCHY_ARTISTS_ROOT -> ContentHierarchyAllArtists(context, this)
            CONTENT_HIERARCHY_SHUFFLE_ALL -> ContentHierarchyShuffleAll(context, this)
            CONTENT_HIERARCHY_NONE -> ContentHierarchyNone(context, this)
            else -> {
                when (ContentHierarchyElement.getType(contentHierarchyID)) {
                    AudioItemType.ARTIST -> ContentHierarchyArtist(contentHierarchyID, context, this)
                    AudioItemType.ALBUM,
                    AudioItemType.TRACKS_FOR_ALBUM -> ContentHierarchyAlbum(contentHierarchyID, context, this)
                    AudioItemType.UNKNOWN_ALBUM,
                    AudioItemType.TRACKS_FOR_UNKN_ALBUM -> ContentHierarchyUnknAlbum(
                        contentHierarchyID, context, this
                    )
                    AudioItemType.TRACKS_FOR_ARTIST -> ContentHierarchyTracksForArtist(
                        contentHierarchyID, context, this
                    )
                    AudioItemType.TRACK -> ContentHierarchyTrack(contentHierarchyID, context, this)
                    AudioItemType.COMPILATION,
                    AudioItemType.TRACKS_FOR_COMPILATION -> ContentHierarchyCompilation(
                        contentHierarchyID, context, this
                    )
                    AudioItemType.GROUP_TRACKS -> ContentHierarchyGroupTracks(contentHierarchyID, context, this)
                    AudioItemType.GROUP_ALBUMS -> ContentHierarchyGroupAlbums(contentHierarchyID, context, this)
                    AudioItemType.GROUP_ARTISTS -> ContentHierarchyGroupArtists(contentHierarchyID, context, this)
                }
            }
        }
    }

    private fun assertContentHierarchyIDNotEmpty(contentHierarchyID: String) {
        if (contentHierarchyID.isBlank()) {
            throw IllegalArgumentException("Given content hierarchy ID is empty: $contentHierarchyID")
        }
    }

    suspend fun getAudioItemForTrack(contentHierarchyID: String): AudioItem {
        logger.debug(TAG, "getAudioItemForTrack($contentHierarchyID)")
        assertContentHierarchyIDNotEmpty(contentHierarchyID)
        if (ContentHierarchyElement.getType(contentHierarchyID) != AudioItemType.TRACK) {
            throw IllegalArgumentException("Given content hierarchy ID is not for a track: $contentHierarchyID")
        }
        val trackContentHierarchy = ContentHierarchyTrack(contentHierarchyID, context, this)
        val audioItems: List<AudioItem> = trackContentHierarchy.getAudioItems()
        if (audioItems.isEmpty()) {
            throw RuntimeException("No track for content hierarchy ID: $contentHierarchyID")
        }
        if (audioItems.size != 1) {
            logger.warning(TAG, "Multiple tracks for content hierarchy ID: $contentHierarchyID")
        }
        return audioItems[0]
    }

    /**
     * This will determine what items in the browse tree will look like in the GUI
     */
    suspend fun createAudioItemDescription(audioItem: AudioItem, extras: Bundle? = null): MediaDescriptionCompat {
        val type: AudioItemType = ContentHierarchyElement.getType(audioItem.id)
        val builder = MediaDescriptionCompat.Builder().apply {
            when (type) {
                AudioItemType.ARTIST -> {
                    if (audioItem.artist.isNotBlank()) {
                        setTitle(audioItem.artist)
                        val databaseID = ContentHierarchyElement.getDatabaseID(audioItem.id)
                        val numTracks = getNumTracksForArtist(databaseID)
                        val numAlbums = getNumAlbumsForArtist(databaseID)
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
                AudioItemType.ALBUM,
                AudioItemType.UNKNOWN_ALBUM -> {
                    if (audioItem.album.isNotBlank()) {
                        setTitle(audioItem.album)
                    } else {
                        setTitle(context.getString(R.string.browse_tree_unknown_album))
                    }
                    if (audioItem.artist.isNotBlank()) {
                        setSubtitle(audioItem.artist)
                    } else {
                        setSubtitle(context.getString(R.string.browse_tree_unknown_artist))
                    }
                    setIconUri(Uri.parse(RESOURCE_ROOT_URI
                            + context.resources.getResourceEntryName(R.drawable.baseline_album_24)))
                }
                AudioItemType.TRACK,
                AudioItemType.TRACKS_FOR_ALBUM,
                AudioItemType.TRACKS_FOR_ARTIST,
                AudioItemType.TRACKS_FOR_UNKN_ALBUM,
                AudioItemType.TRACKS_FOR_COMPILATION -> {
                    setTitle(audioItem.title)
                    if (audioItem.artist.isNotBlank()) {
                        setSubtitle(audioItem.artist)
                    } else {
                        setSubtitle(context.getString(R.string.browse_tree_unknown_artist))
                    }
                    setIconUri(Uri.parse(RESOURCE_ROOT_URI
                            + context.resources.getResourceEntryName(R.drawable.baseline_music_note_24)))
                }
                AudioItemType.COMPILATION -> {
                    setTitle(audioItem.album)
                    setSubtitle(audioItem.artist)
                    setIconUri(Uri.parse(RESOURCE_ROOT_URI
                            + context.resources.getResourceEntryName(R.drawable.baseline_album_24)))
                }
                AudioItemType.GROUP_TRACKS, AudioItemType.GROUP_ALBUMS, AudioItemType.GROUP_ARTISTS -> {
                    throw AssertionError("createAudioItemDescription() is not supported for groups")
                }
            }
            setMediaId(audioItem.id)
            setMediaUri(audioItem.uri)
        }
        extras?.let { builder.setExtras(it) }
        return builder.build()
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
        storageToRepoMap.values.forEach { repo ->
            numAlbums += repo.getNumAlbumsForArtist(artistID)
        }
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

    private suspend fun searchByType(query: String, type: AudioItemType): MutableList<AudioItem> {
        val searchResults: MutableList<AudioItem> = mutableListOf()
        if (query.isBlank()) {
            return searchResults
        }
        storageToRepoMap.values.forEach { repo ->
            searchResults += when (type) {
                AudioItemType.ARTIST -> repo.searchArtists(query)
                AudioItemType.ALBUM -> repo.searchAlbums(query)
                AudioItemType.TRACK -> repo.searchTracks(query)
                else -> {
                    throw RuntimeException("Not supported type: $type")
                }
            }
        }
        return searchResults
    }

    suspend fun searchUnspecific(query: String): MutableList<AudioItem> {
        val searchResults: MutableList<AudioItem> = mutableListOf()
        if (query.isBlank()) {
            return searchResults
        }
        storageToRepoMap.values.forEach { repo ->
            searchResults += repo.searchTracksForArtist(query)
            searchResults += repo.searchTracksForAlbum(query)
            searchResults += repo.searchTracks(query)
        }
        return searchResults
    }

    fun createMetadataForItem(audioItem: AudioItem): MediaMetadataCompat {
        return metadataMaker.createMetadataForItem(audioItem)
    }

    fun getRepoForBrowserID(browserID: String): AudioItemRepository? {
        val storageID = ContentHierarchyElement.getStorageID(browserID)
        return storageToRepoMap[storageID]
    }

    fun getAllStorageIDs(): List<String> {
        return storageToRepoMap.keys.toList()
    }

    fun areAnyStoragesAvail(): Boolean {
        return storageToRepoMap.isNotEmpty()
    }

    fun shutdown() {
        logger.debug(TAG, "shutdown()")
        storageToRepoMap.values.forEach { it.close() }
        storageToRepoMap.clear()
    }

    private fun notifyObservers(exc: Exception) {
        libraryExceptionObservers.forEach { it(exc) }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun extractMetadataFrom(audioFile: AudioFile): AudioItem {
        return metadataMaker.extractMetadataFrom(audioFile)
    }

}
