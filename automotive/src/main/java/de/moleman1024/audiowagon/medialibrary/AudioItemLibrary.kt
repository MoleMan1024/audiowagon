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
import androidx.room.Room
import de.moleman1024.audiowagon.GUI
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.SharedPrefs
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.exceptions.CannotRecoverUSBException
import de.moleman1024.audiowagon.exceptions.NoAudioItemException
import de.moleman1024.audiowagon.filestorage.*
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.*
import de.moleman1024.audiowagon.repository.AudioItemDatabase
import de.moleman1024.audiowagon.repository.AudioItemRepository
import de.moleman1024.audiowagon.repository.entities.Status
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

private const val MAX_NUM_ALBUM_ART_TO_CACHE = 30

@ExperimentalCoroutinesApi
class AudioItemLibrary(
    private val context: Context,
    private val audioFileStorage: AudioFileStorage,
    val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val gui: GUI,
    private val sharedPrefs: SharedPrefs
) {
    private val metadataMaker = AudioMetadataMaker(audioFileStorage)
    val storageToRepoMap = mutableMapOf<String, AudioItemRepository>()
    var isBuildingLibrary = false
    var numFilesSeenWhenBuildingLibrary = 0
    var libraryExceptionObservers = mutableListOf<(Exception) -> Unit>()
    private var isBuildLibraryCancelled: Boolean = false
    private val recentAudioItemToAlbumArtMap: AudioItemToAlbumArtMapCache = AudioItemToAlbumArtMapCache()
    private class AudioItemToAlbumArtMapCache : LinkedHashMap<String, ByteArray>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            return size > MAX_NUM_ALBUM_ART_TO_CACHE
        }
    }
    var albumArtStyleSetting: AlbumStyleSetting = sharedPrefs.getAlbumStyleSettingEnum(context, logger, TAG)
    var useInMemoryDatabase: Boolean = false

    fun initRepository(storageID: String) {
        recentAudioItemToAlbumArtMap.clear()
        if (storageToRepoMap.containsKey(storageID)) {
            return
        }
        val dbName = AudioItemRepository.createDatabaseName(storageID)
        var dbBuilder = Room.databaseBuilder(context, AudioItemDatabase::class.java, dbName)
            .fallbackToDestructiveMigration()
        if (useInMemoryDatabase) {
            dbBuilder =
                Room.inMemoryDatabaseBuilder(context, AudioItemDatabase::class.java).fallbackToDestructiveMigration()
        }
        val repo = AudioItemRepository(storageID, context, dispatcher, dbBuilder)
        storageToRepoMap[storageID] = repo
    }

    fun removeRepository(storageID: String) {
        recentAudioItemToAlbumArtMap.clear()
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
    suspend fun buildLibrary(channel: ReceiveChannel<FileLike>, callback: () -> Unit) {
        val metadataReadSetting = sharedPrefs.getMetadataReadSettingEnum(context, logger, TAG)
        val isReadAudioFileMetadata =
            metadataReadSetting in listOf(MetadataReadSetting.WHEN_USB_CONNECTED, MetadataReadSetting.MANUALLY)
        isBuildingLibrary = true
        isBuildLibraryCancelled = false
        numFilesSeenWhenBuildingLibrary = 0
        // indicate indexing has started via callback function
        callback()
        val repo = getPrimaryRepository() ?: throw RuntimeException("No repository")
        repo.hasUpdatedDatabase = false
        channel.consumeEach { fileOrDirectory ->
            logger.verbose(TAG, "buildLibrary() received: $fileOrDirectory")
            if (!isBuildLibraryCancelled) {
                if (fileOrDirectory is AudioFile) {
                    if (isReadAudioFileMetadata) {
                        updateLibraryTracksFromAudioFile(fileOrDirectory, repo)
                    }
                    updateLibraryPathsFromFileOrDir(fileOrDirectory, repo)
                    numFilesSeenWhenBuildingLibrary++
                    // We limit the number of indexing notification updates sent here. It seems Android will throttle
                    // if too many notifications are posted and the last important notification indicating "finished" might be
                    // ignored if too many are sent
                    if (numFilesSeenWhenBuildingLibrary % UPDATE_INDEX_NOTIF_FOR_EACH_NUM_ITEMS == 0) {
                        gui.updateIndexingNotification(numFilesSeenWhenBuildingLibrary)
                        logger.flushToUSB()
                        callback()
                    }
                } else if (fileOrDirectory is Directory) {
                    updateLibraryPathsFromFileOrDir(fileOrDirectory, repo)
                }
            } else {
                logger.debug(TAG, "Library build has been cancelled")
            }
        }
        logger.debug(TAG, "Channel drained in buildLibrary()")
        if (!isBuildLibraryCancelled) {
            if (metadataReadSetting != MetadataReadSetting.OFF) {
                repo.clean()
            }
            repo.updateGroups()
            setDatabaseStatus(Status(storageID = repo.storageID, wasCompletedOnce = true))
        } else {
            setDatabaseStatus(Status(storageID = repo.storageID, wasCompletedOnce = false))
        }
        isBuildingLibrary = false
        isBuildLibraryCancelled = false
    }

    private suspend fun updateLibraryTracksFromAudioFile(file: AudioFile, repo: AudioItemRepository) {
        val trackIDInDatabase: Long? = repo.getDatabaseIDForTrack(file)
        if (trackIDInDatabase == null) {
            extractMetadataAndPopulateDB(file, repo, coroutineContext)
            repo.hasUpdatedDatabase = true
        } else {
            try {
                val audioFileHasChanged =
                    repo.hasAudioFileChangedForTrack(file, trackIDInDatabase)
                if (!audioFileHasChanged) {
                    repo.trackIDsToKeep.add(trackIDInDatabase)
                } else {
                    repo.removeTrack(trackIDInDatabase)
                    extractMetadataAndPopulateDB(file, repo, coroutineContext)
                    repo.hasUpdatedDatabase = true
                }
            } catch (exc: RuntimeException) {
                logger.exception(TAG, exc.message.toString(), exc)
            }
        }
    }

    private suspend fun updateLibraryPathsFromFileOrDir(fileOrDir: FileLike, repo: AudioItemRepository) {
        val pathIDInDatabase: Long? = repo.getDatabaseIDForPath(fileOrDir)
        if (pathIDInDatabase == null) {
            repo.populateDatabaseFromFileOrDir(fileOrDir)
            repo.hasUpdatedDatabase = true
        } else {
            when (fileOrDir) {
                is AudioFile -> {
                    val audioFileHasChanged =
                        repo.hasAudioFileChangedForPath(fileOrDir, pathIDInDatabase)
                    if (!audioFileHasChanged) {
                        repo.pathIDsToKeep.add(pathIDInDatabase)
                    } else {
                        repo.removePath(pathIDInDatabase)
                        repo.populateDatabaseFromFileOrDir(fileOrDir)
                        repo.hasUpdatedDatabase = true
                    }
                }
                is Directory -> {
                    // in FAT32 directory timestamps are not updated when the contents change, must always update
                    repo.removePath(pathIDInDatabase)
                    repo.populateDatabaseFromFileOrDir(fileOrDir)
                    repo.hasUpdatedDatabase = true
                }
                else -> {
                    throw RuntimeException("Not supported file type: $fileOrDir")
                }
            }
        }
    }

    private suspend fun setDatabaseStatus(status: Status) {
        val statusInDB = getDatabaseStatus()
        if (statusInDB == status) {
            return
        }
        val repo = getPrimaryRepository() ?: throw RuntimeException("No repository")
        repo.getDatabase()?.statusDAO()?.deleteStatus(repo.storageID)
        repo.getDatabase()?.statusDAO()?.insert(status)
    }

    private suspend fun getDatabaseStatus(): Status? {
        val repo = getPrimaryRepository() ?: throw RuntimeException("No repository")
        return repo.getDatabase()?.statusDAO()?.queryStatus(repo.storageID)
    }

    private suspend fun extractMetadataAndPopulateDB(
        audioFile: AudioFile,
        repo: AudioItemRepository,
        coroutineContext: CoroutineContext
    ) {
        try {
            // TODO: extract album art in extractMetadataFrom
            val metadata: AudioItem = extractMetadataFrom(audioFile)
            if (isBuildLibraryCancelled) {
                return
            }
            val albumArtSourceURI: FileLike? = findAlbumArtFor(audioFile)
            repo.populateDatabaseFrom(audioFile, metadata, albumArtSourceURI)
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
     * Returns all files in given directory (recursive) based on previously indexed paths in database. Used when
     * searching in files/directories.
     */
    suspend fun getFilesInDirRecursive(uri: Uri, numMaxItems: Int): List<AudioItem> {
        val repo = getPrimaryRepository()
        val directory = Directory(uri)
        val items = repo?.getFilesInDirRecursive(directory.path, numMaxItems) ?: listOf()
        items.forEach {
            logger.verbose(TAG, it.toString())
        }
        return items
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
            ContentHierarchyType.ROOT -> ContentHierarchyRoot(context, this, sharedPrefs)
            ContentHierarchyType.SHUFFLE_ALL_TRACKS -> ContentHierarchyShuffleAllTracks(context, this)
            ContentHierarchyType.ROOT_TRACKS -> ContentHierarchyRootTracks(context, this, audioFileStorage, sharedPrefs)
            ContentHierarchyType.ROOT_FILES -> ContentHierarchyRootFiles(context, this, audioFileStorage, sharedPrefs)
            ContentHierarchyType.ROOT_ALBUMS -> ContentHierarchyRootAlbums(context, this)
            ContentHierarchyType.ROOT_ARTISTS -> ContentHierarchyRootArtists(context, this)
            ContentHierarchyType.TRACK -> ContentHierarchyTrack(contentHierarchyID, context, this)
            ContentHierarchyType.ALBUM -> ContentHierarchyAlbum(contentHierarchyID, context, this)
            ContentHierarchyType.COMPILATION -> ContentHierarchyCompilation(contentHierarchyID, context, this)
            ContentHierarchyType.UNKNOWN_ALBUM -> ContentHierarchyUnknAlbum(contentHierarchyID, context, this)
            ContentHierarchyType.ARTIST -> ContentHierarchyArtist(contentHierarchyID, context, this)
            ContentHierarchyType.FILE -> ContentHierarchyFile(contentHierarchyID, context, this, audioFileStorage)
            ContentHierarchyType.DIRECTORY -> ContentHierarchyDirectory(
                contentHierarchyID, context, this, audioFileStorage, sharedPrefs
            )
            ContentHierarchyType.PLAYLIST -> ContentHierarchyPlaylist(contentHierarchyID, context, this, audioFileStorage)
            ContentHierarchyType.TRACK_GROUP -> ContentHierarchyGroupTracks(contentHierarchyID, context, this)
            ContentHierarchyType.ALBUM_GROUP -> ContentHierarchyGroupAlbums(contentHierarchyID, context, this)
            ContentHierarchyType.ARTIST_GROUP -> ContentHierarchyGroupArtists(contentHierarchyID, context, this)
            ContentHierarchyType.FILELIKE_GROUP -> ContentHierarchyGroupFileLike(
                contentHierarchyID, context, this, audioFileStorage, sharedPrefs
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
                    if (audioItem.artist.isNotBlank() || audioItem.albumArtist.isNotBlank()) {
                        val numTracks: Int
                        val numAlbums: Int
                        if (audioItem.artist.isNotBlank()) {
                            setTitle(audioItem.artist)
                        } else {
                            setTitle(audioItem.albumArtist)
                        }
                        if (contentHierarchyID.albumArtistID >= DATABASE_ID_UNKNOWN) {
                            numTracks = getNumTracksForAlbumArtist(contentHierarchyID.albumArtistID)
                            numAlbums = getNumAlbumsForAlbumArtist(contentHierarchyID.albumArtistID)
                        } else {
                            numTracks = getNumTracksForArtist(contentHierarchyID.artistID)
                            numAlbums = getNumAlbumsForArtist(contentHierarchyID.artistID)
                        }
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
                    if (audioItem.albumArtURI != Uri.EMPTY) {
                        setIconUri(audioItem.albumArtURI)
                    } else {
                        setIconUri(Uri.parse(RESOURCE_ROOT_URI
                                + context.resources.getResourceEntryName(R.drawable.baseline_album_24)))
                    }
                }
                ContentHierarchyType.TRACK -> {
                    setTitle(audioItem.title)
                    if (audioItem.artist.isNotBlank()) {
                        setSubtitle(audioItem.artist)
                    } else {
                        setSubtitle(context.getString(R.string.browse_tree_unknown_artist))
                    }
                    if (audioItem.albumArtURI != Uri.EMPTY) {
                        setIconUri(audioItem.albumArtURI)
                    } else {
                        setIconUri(Uri.parse(RESOURCE_ROOT_URI
                                + context.resources.getResourceEntryName(R.drawable.baseline_music_note_24)))
                    }
                }
                ContentHierarchyType.FILE -> {
                    setTitle(audioItem.title)
                    setIconUri(Uri.parse(RESOURCE_ROOT_URI
                            + context.resources.getResourceEntryName(R.drawable.baseline_insert_drive_file_24)))
                }
                ContentHierarchyType.DIRECTORY -> {
                    setTitle(audioItem.title)
                    setIconUri(Uri.parse(RESOURCE_ROOT_URI
                            + context.resources.getResourceEntryName(R.drawable.baseline_folder_24)))
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

    private suspend fun getNumTracksForArtist(artistID: Long): Int {
        var numTracks = 0
        storageToRepoMap.values.forEach { repo ->
            numTracks += repo.getNumTracksForArtist(artistID)
        }
        return numTracks
    }

    private suspend fun getNumTracksForAlbumArtist(artistID: Long): Int {
        var numTracks = 0
        val repo = getPrimaryRepository() ?: return 0
        numTracks += repo.getNumTracksForAlbumArtist(artistID)
        return numTracks
    }

    private suspend fun getNumAlbumsForArtist(artistID: Long): Int {
        var numAlbums = 0
        val repo = getPrimaryRepository() ?: return 0
        numAlbums += repo.getNumAlbumsBasedOnTracksArtist(artistID)
        numAlbums += if (repo.getNumTracksWithUnknAlbumForArtist(artistID) > 0) 1 else 0
        return numAlbums
    }

    private suspend fun getNumAlbumsForAlbumArtist(artistID: Long): Int {
        var numAlbums = 0
        val repo = getPrimaryRepository() ?: return 0
        numAlbums += repo.getNumAlbumsBasedOnTracksAlbumArtist(artistID)
        numAlbums += if (repo.getNumTracksWithUnknAlbumForAlbumArtist(artistID) > 0) 1 else 0
        return numAlbums
    }

    /**
     * Searches for tracks, albums, artists, files based on the given query provided by user.
     * We limit this to 10 items per category. Users will probably not look through a lot of items in the
     * car and it will cause BINDER TRANSACTION failures if too many results are returned
     */
    suspend fun searchMediaItems(query: String): MutableList<MediaItem> {
        val searchResults: MutableList<MediaItem> = mutableListOf()
        if (query.isBlank()) {
            // somehow AAOS does not pass an empty query, so this is not triggered and results are never cleared
            return searchResults
        }
        val repo = getPrimaryRepository()
        val tracks = repo?.searchTracks(query)
        tracks?.forEach {
            val trackExtras = Bundle()
            trackExtras.putString(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE,
                context.getString(R.string.search_group_tracks)
            )
            val description = createAudioItemDescription(it, trackExtras)
            searchResults += MediaItem(description, it.browsPlayableFlags)
        }
        val artists = repo?.searchArtists(query)
        artists?.forEach {
            val artistExtras = Bundle()
            artistExtras.putString(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE,
                context.getString(R.string.search_group_artists)
            )
            if (albumArtStyleSetting == AlbumStyleSetting.GRID) {
                artistExtras.putAll(ContentHierarchyElement.generateExtrasBrowsableGridItems())
            }
            val description = createAudioItemDescription(it, artistExtras)
            searchResults += MediaItem(description, it.browsPlayableFlags)
        }
        val albums = repo?.searchAlbums(query)
        albums?.forEach {
            val albumExtras = Bundle()
            albumExtras.putString(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE,
                context.getString(R.string.search_group_albums)
            )
            val description = createAudioItemDescription(it, albumExtras)
            searchResults += MediaItem(description, it.browsPlayableFlags)
        }
        val files = repo?.searchFiles(query)
        files?.forEach {
            val filesExtras = Bundle()
            filesExtras.putString(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE,
                context.getString(R.string.search_group_files)
            )
            val description = createAudioItemDescription(it, filesExtras)
            searchResults += MediaItem(description, it.browsPlayableFlags)
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

    private fun getAlbumArtForFile(file: FileLike): ByteArray? {
        val byteArrayFromCache: ByteArray? = recentAudioItemToAlbumArtMap[file.uri.toString()]
        if (byteArrayFromCache != null) {
            return byteArrayFromCache
        }
        var albumArtBytes: ByteArray? = null
        when (file) {
            is AudioFile -> {
                val audioItem = createAudioItemForFile(file)
                albumArtBytes = metadataMaker.getEmbeddedAlbumArtForAudioItem(audioItem)
                if (albumArtBytes != null) {
                    val albumArtResized = metadataMaker.resizeAlbumArt(albumArtBytes)
                    if (albumArtResized != null) {
                        logger.verbose(TAG, "Got album art with size ${albumArtResized.size} for: ${audioItem.uri}")
                        recentAudioItemToAlbumArtMap[file.uri.toString()] = albumArtBytes
                    }
                    return albumArtResized
                }
            }
            is GeneralFile -> {
                albumArtBytes = audioFileStorage.getByteArrayForURI(file.uri)
                val albumArtResized = metadataMaker.resizeAlbumArt(albumArtBytes)
                if (albumArtResized != null) {
                    logger.verbose(
                        TAG,
                        "Found album art in directory with size ${albumArtResized.size} for: ${file.uri}"
                    )
                    recentAudioItemToAlbumArtMap[file.uri.toString()] = albumArtBytes
                }
                return albumArtResized
            }
        }
        logger.warning(TAG, "Could not retrieve any album art for: ${file.uri}")
        return albumArtBytes
    }

    fun getAlbumArtForArtURI(uri: Uri): ByteArray? {
        if (!uri.toString().contains("$ART_URI_PART/$ART_URI_PART_FILE")) {
            val repo = getPrimaryRepository()
            val albumForAlbumArt = repo?.getAlbumForAlbumArt(uri.toString()) ?: return null
            val albumArtFile: FileLike
            if (albumForAlbumArt.albumArtURIString.isBlank()) {
                return null
            }
            val albumArtSourceURI = Uri.parse(albumForAlbumArt.albumArtSourceURIString)
            if (albumArtSourceURI == Uri.EMPTY) {
                return null
            }
            albumArtFile = if (albumForAlbumArt.hasFolderImage) {
                GeneralFile(albumArtSourceURI)
            } else {
                AudioFile(albumArtSourceURI)
            }
            return getAlbumArtForFile(albumArtFile)
        } else {
            val pathInURI = uri.path?.replace("^/$ART_URI_PART/$ART_URI_PART_FILE".toRegex(), "") ?: return null
            val audioFile =
                AudioFile(Util.createURIForPath(audioFileStorage.getPrimaryStorageLocation().storageID, pathInURI))
            val albumArtFile = findAlbumArtFor(audioFile) ?: return null
            return getAlbumArtForFile(albumArtFile)
        }
    }

    private fun findAlbumArtFor(audioFile: AudioFile): FileLike? {
        val audioItem = createAudioItemForFile(audioFile)
        val albumArtBytes = metadataMaker.getEmbeddedAlbumArtForAudioItem(audioItem)
        if (albumArtBytes != null) {
            return AudioFile(audioFile.uri)
        }
        val albumArtFile = audioFileStorage.getAlbumArtFileInDirectoryForURI(audioItem.uri)
        if (albumArtFile != null) {
            return GeneralFile(albumArtFile.uri)
        }
        return null
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
        recentAudioItemToAlbumArtMap.clear()
    }

    fun suspend() {
        logger.debug(TAG, "suspend()")
        storageToRepoMap.values.forEach { it.close() }
        storageToRepoMap.clear()
        recentAudioItemToAlbumArtMap.clear()
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

    // we do not support multiple repositories
    fun getPrimaryRepository(): AudioItemRepository? {
        if (storageToRepoMap.isEmpty()) {
            return null
        }
        return storageToRepoMap.values.toTypedArray()[0]
    }

    suspend fun getPseudoCompilationArtistID(): Long? {
        return getPrimaryRepository()?.getPseudoCompilationArtistID()
    }

    fun clearAlbumArtCache() {
        recentAudioItemToAlbumArtMap.clear()
    }

    companion object {
        fun createAudioItemForFile(file: AudioFile): AudioItem {
            val audioItem = AudioItem()
            val contentHierarchyID = ContentHierarchyID(ContentHierarchyType.FILE)
            contentHierarchyID.path = file.path
            audioItem.id = ContentHierarchyElement.serialize(contentHierarchyID)
            audioItem.uri = file.uri
            audioItem.title = file.name
            audioItem.browsPlayableFlags = audioItem.browsPlayableFlags.or(MediaBrowser.MediaItem.FLAG_PLAYABLE)
            return audioItem
        }

        fun createAudioItemForDirectory(directory: Directory): AudioItem {
            val audioItem = AudioItem()
            val contentHierarchyID = ContentHierarchyID(ContentHierarchyType.DIRECTORY)
            contentHierarchyID.path = directory.path
            audioItem.id = ContentHierarchyElement.serialize(contentHierarchyID)
            audioItem.uri = directory.uri
            audioItem.title = directory.name
            audioItem.browsPlayableFlags = audioItem.browsPlayableFlags.or(MediaBrowser.MediaItem.FLAG_BROWSABLE)
            return audioItem
        }
    }

}
