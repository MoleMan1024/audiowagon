/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary

import android.content.Context
import android.media.MediaMetadataRetriever
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
import de.moleman1024.audiowagon.enums.AlbumStyleSetting
import de.moleman1024.audiowagon.enums.AudioItemType
import de.moleman1024.audiowagon.enums.ContentHierarchyType
import de.moleman1024.audiowagon.enums.MetadataReadSetting
import de.moleman1024.audiowagon.enums.ViewTabSetting
import de.moleman1024.audiowagon.exceptions.CannotRecoverUSBException
import de.moleman1024.audiowagon.exceptions.NoAudioItemException
import de.moleman1024.audiowagon.filestorage.*
import de.moleman1024.audiowagon.filestorage.data.AudioFile
import de.moleman1024.audiowagon.filestorage.data.Directory
import de.moleman1024.audiowagon.filestorage.data.GeneralFile
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.*
import de.moleman1024.audiowagon.repository.AudioItemDatabase
import de.moleman1024.audiowagon.repository.AudioItemRepository
import de.moleman1024.audiowagon.repository.entities.Status
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

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

/**
 * This class is responsible for turning media data from [AudioFileStorage] into an indexed, searchable database and
 * providing access to it.
 */
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
    private val libraryMutex = Mutex()
    var isBuildingLibrary = false
    var numFileDirsSeenWhenBuildingLibrary = 0
    var libraryExceptionObservers = mutableListOf<(Exception) -> Unit>()
    private var isBuildLibraryCancelled: Boolean = false
    private val recentAudioItemToAlbumArtMap: AudioItemToAlbumArtMapCache = AudioItemToAlbumArtMapCache()
    // The order of tabs at the top of the browse view
    // Configurable as per https://github.com/MoleMan1024/audiowagon/issues/124
    val viewTabs: MutableList<ViewTabSetting> = mutableListOf()

    private class AudioItemToAlbumArtMapCache : LinkedHashMap<String, ByteArray>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            return size > MAX_NUM_ALBUM_ART_TO_CACHE
        }
    }

    var albumArtStyleSetting: AlbumStyleSetting = sharedPrefs.getAlbumStyleSettingEnum(context, logger, TAG)
    var useInMemoryDatabase: Boolean = false
    private val buildLibraryJobs = mutableListOf<Job>()

    init {
        val viewTabsFromSettings = sharedPrefs.getViewTabSettings(context, logger, TAG)
        viewTabsFromSettings.forEach { viewTabs.add(it) }
    }

    suspend fun initRepository(storageID: String) {
        libraryMutex.withLock {
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
    }

    suspend fun removeRepository(storageID: String) {
        libraryMutex.withLock {
            recentAudioItemToAlbumArtMap.clear()
            if (!storageToRepoMap.containsKey(storageID)) {
                return
            }
            val repo = storageToRepoMap[storageID] ?: return
            repo.close()
            storageToRepoMap.remove(storageID)
        }
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
        logger.verbose(TAG, "buildLibrary(channel$channel)")
        val startTime = System.nanoTime()
        val metadataReadSetting = sharedPrefs.getMetadataReadSettingEnum(context, logger, TAG)
        val isReadAudioFileMetadata =
            metadataReadSetting in listOf(MetadataReadSetting.WHEN_USB_CONNECTED, MetadataReadSetting.MANUALLY)
        isBuildingLibrary = true
        isBuildLibraryCancelled = false
        numFileDirsSeenWhenBuildingLibrary = 0
        // indicate indexing has started via callback function
        callback()
        val repo = getPrimaryRepository() ?: throw RuntimeException("No repository")
        repo.hasUpdatedDatabase = false
        repeat(4) {
            val buildLibraryCoroutineJob = CoroutineScope(coroutineContext).launch {
                channel.consumeEach { fileOrDirectory ->
                    logger.debug(TAG, "buildLibrary() received: $fileOrDirectory")
                    if (!isBuildLibraryCancelled) {
                        if (fileOrDirectory is AudioFile) {
                            if (isReadAudioFileMetadata) {
                                updateLibraryTracksFromAudioFile(fileOrDirectory, repo)
                            }
                            if (!isBuildLibraryCancelled) {
                                updateLibraryPathsFromFileOrDir(fileOrDirectory, repo)
                            }
                        } else if (fileOrDirectory is Directory) {
                            updateLibraryPathsFromFileOrDir(fileOrDirectory, repo)
                        }
                        numFileDirsSeenWhenBuildingLibrary++
                        // We limit the number of indexing notification updates sent here. It seems Android will throttle
                        // if too many notifications are posted and the last important notification indicating "finished"
                        // might be ignored if too many are sent
                        // Below 100 entries send more updates to indicate to user early this is actually working
                        if (numFileDirsSeenWhenBuildingLibrary % UPDATE_INDEX_NOTIF_FOR_EACH_NUM_ITEMS == 0
                            || (numFileDirsSeenWhenBuildingLibrary < 100 && numFileDirsSeenWhenBuildingLibrary % 20 == 0)
                        ) {
                            gui.updateIndexingNotification(numFileDirsSeenWhenBuildingLibrary)
                            logger.setFlushToUSBFlag()
                            callback()
                        }
                    } else {
                        logger.debug(TAG, "Library build has been cancelled")
                    }
                }
            }
            buildLibraryJobs.add(buildLibraryCoroutineJob)
        }
        buildLibraryJobs.joinAll()
        logger.debug(TAG, "Channel drained in buildLibrary()")
        if (!isBuildLibraryCancelled) {
            if (metadataReadSetting != MetadataReadSetting.OFF) {
                repo.clean()
            }
            repo.updateGroups()
            if (areAnyReposAvail()) {
                setDatabaseStatus(Status(storageID = repo.storageID, wasCompletedOnce = true))
            }
        } else {
            if (areAnyReposAvail()) {
                setDatabaseStatus(Status(storageID = repo.storageID, wasCompletedOnce = false))
            }
        }
        isBuildingLibrary = false
        isBuildLibraryCancelled = false
        val endTime = System.nanoTime()
        val timeTakenMS = (endTime - startTime) / 1000000L
        logger.debug(TAG, "Building library took ${timeTakenMS}ms")
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
                    logger.debug(TAG, "Track $trackIDInDatabase already in database for file: $file")
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
                    // in FAT32 directory timestamps are not updated when the contents change, keep as-is
                    repo.pathIDsToKeep.add(pathIDInDatabase)
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
            val startTime = System.nanoTime()
            logger.debug(TAG, "Extracting metadata for: ${audioFile.name}")
            val dataSource = audioFileStorage.getDataSourceForAudioFile(audioFile)
            val metadataRetriever = metadataMaker.setupMetadataRetrieverWithDataSource(dataSource)
            if (isBuildLibraryCancelled) {
                return
            }
            val metadata: AudioItem = extractMetadataFrom(audioFile, metadataRetriever)
            val albumArtSourceURI: FileLike? = findAlbumArtFor(audioFile, metadataRetriever)
            metadataMaker.cleanMetadataRetriever(metadataRetriever, dataSource)
            if (isBuildLibraryCancelled) {
                return
            }
            val endTime = System.nanoTime()
            val timeTakenMS = (endTime - startTime) / 1000000L
            logger.debug(TAG, "Extracted metadata in ${timeTakenMS}ms: $metadata")
            repo.populateDatabaseFrom(audioFile, metadata, albumArtSourceURI)
        } catch (exc: IOException) {
            // this can happen for example for files with strange filenames, the file will be ignored
            logger.exception(TAG, "I/O exception when processing file: $audioFile", exc)
            if (("MAX_RECOVERY_ATTEMPTS|No filesystem|DataSource error|" +
                        "Communication was closed").toRegex().containsMatchIn(exc.stackTraceToString())
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

    suspend fun extractMetadataFrom(
        audioFile: AudioFile,
        metadataRetriever: MediaMetadataRetriever? = null
    ): AudioItem {
        return if (metadataRetriever == null) {
            val dataSource = audioFileStorage.getDataSourceForAudioFile(audioFile)
            val mediaMetadataRetriever = metadataMaker.setupMetadataRetrieverWithDataSource(dataSource)
            val audioItemForMetadata = metadataMaker.extractMetadataFrom(audioFile, mediaMetadataRetriever)
            metadataMaker.cleanMetadataRetriever(mediaMetadataRetriever, dataSource)
            audioItemForMetadata
        } else {
            metadataMaker.extractMetadataFrom(audioFile, metadataRetriever)
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
            ContentHierarchyType.ROOT -> ContentHierarchyRoot(context, this)
            ContentHierarchyType.SHUFFLE_ALL_TRACKS -> ContentHierarchyShuffleAllTracks(context, this)
            ContentHierarchyType.ROOT_TRACKS -> ContentHierarchyRootTracks(context, this, audioFileStorage, sharedPrefs)
            ContentHierarchyType.ROOT_FILES -> ContentHierarchyRootFiles(context, this, audioFileStorage, sharedPrefs)
            ContentHierarchyType.ROOT_ALBUMS -> ContentHierarchyRootAlbums(context, this, audioFileStorage, sharedPrefs)
            ContentHierarchyType.ROOT_ARTISTS -> ContentHierarchyRootArtists(context, this, audioFileStorage, sharedPrefs)
            ContentHierarchyType.TRACK -> ContentHierarchyTrack(contentHierarchyID, context, this)
            ContentHierarchyType.ALBUM -> ContentHierarchyAlbum(contentHierarchyID, context, this)
            ContentHierarchyType.COMPILATION -> ContentHierarchyCompilation(contentHierarchyID, context, this)
            ContentHierarchyType.UNKNOWN_ALBUM -> ContentHierarchyUnknAlbum(contentHierarchyID, context, this)
            ContentHierarchyType.ARTIST -> ContentHierarchyArtist(contentHierarchyID, context, this)
            ContentHierarchyType.FILE -> ContentHierarchyFile(contentHierarchyID, context, this, audioFileStorage)
            ContentHierarchyType.DIRECTORY -> ContentHierarchyDirectory(
                contentHierarchyID, context, this, audioFileStorage, sharedPrefs
            )
            ContentHierarchyType.PLAYLIST -> ContentHierarchyPlaylist(
                contentHierarchyID,
                context,
                this,
                audioFileStorage
            )
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
                            setTitle(localizeArtistName(audioItem.artist))
                        } else {
                            setTitle(localizeArtistName(audioItem.albumArtist))
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
                    setIconUri(
                        Uri.parse(
                            RESOURCE_ROOT_URI
                                    + context.resources.getResourceEntryName(R.drawable.person)
                        )
                    )
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
                        setSubtitle(localizeArtistName(audioItem.albumArtist))
                    } else {
                        if (audioItem.artist.isNotBlank()) {
                            setSubtitle(localizeArtistName(audioItem.artist))
                        } else {
                            setSubtitle(context.getString(R.string.browse_tree_unknown_artist))
                        }
                    }
                    if (audioItem.albumArtURI != Uri.EMPTY) {
                        setIconUri(audioItem.albumArtURI)
                    } else {
                        setIconUri(
                            Uri.parse(
                                RESOURCE_ROOT_URI
                                        + context.resources.getResourceEntryName(R.drawable.album)
                            )
                        )
                    }
                }
                ContentHierarchyType.TRACK -> {
                    setTitle(audioItem.title)
                    if (audioItem.artist.isNotBlank()) {
                        setSubtitle(localizeArtistName(audioItem.artist))
                    } else {
                        setSubtitle(context.getString(R.string.browse_tree_unknown_artist))
                    }
                    if (audioItem.albumArtURI != Uri.EMPTY) {
                        setIconUri(audioItem.albumArtURI)
                    } else {
                        setIconUri(
                            Uri.parse(
                                RESOURCE_ROOT_URI
                                        + context.resources.getResourceEntryName(R.drawable.music_note)
                            )
                        )
                    }
                }
                ContentHierarchyType.FILE -> {
                    setTitle(audioItem.title)
                    setIconUri(
                        Uri.parse(
                            RESOURCE_ROOT_URI
                                    + context.resources.getResourceEntryName(R.drawable.draft)
                        )
                    )
                }
                ContentHierarchyType.DIRECTORY -> {
                    setTitle(audioItem.title)
                    setIconUri(
                        Uri.parse(
                            RESOURCE_ROOT_URI
                                    + context.resources.getResourceEntryName(R.drawable.folder)
                        )
                    )
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

    /**
     * Convert internal artist name "Various artists" into localized string if necessary
     */
    private suspend fun localizeArtistName(artistName: String): String {
        val repo = getPrimaryRepository() ?: return artistName
        val variousArtistsEnglish = repo.getPseudoCompilationArtistNameEnglish()
        if (artistName.lowercase() != variousArtistsEnglish.lowercase()) {
            return artistName
        }
        return context.getString(R.string.browse_tree_various_artists)
    }

    private suspend fun getNumTracksForArtist(artistID: Long): Int {
        var numTracks = 0
        libraryMutex.withLock {
            storageToRepoMap.values.forEach { repo ->
                numTracks += repo.getNumTracksForArtist(artistID)
            }
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
        val artists = repo?.searchAlbumAndCompilationArtists(query)
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

    suspend fun searchTracksForArtist(query: String): MutableList<AudioItem> {
        return searchByType(query, AudioItemType.ARTIST)
    }

    suspend fun searchTracksForAlbum(query: String): MutableList<AudioItem> {
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

    suspend fun searchTracksForAlbumAndArtist(album: String, artist: String): MutableList<AudioItem> {
        val searchResults: MutableList<AudioItem> = mutableListOf()
        if (album.isBlank() || artist.isBlank()) {
            return searchResults
        }
        val repo = getPrimaryRepository() ?: return searchResults
        searchResults += repo.searchTracksForAlbumAndArtist(album, artist)
        return searchResults
    }

    private suspend fun searchByType(query: String, type: AudioItemType): MutableList<AudioItem> {
        val searchResults: MutableList<AudioItem> = mutableListOf()
        if (query.isBlank()) {
            return searchResults
        }
        val repo = getPrimaryRepository() ?: return searchResults
        searchResults += when (type) {
            AudioItemType.ARTIST -> repo.searchTracksForArtist(query)
            AudioItemType.ALBUM -> repo.searchTracksForAlbum(query)
            AudioItemType.TRACK -> repo.searchTracks(query)
            AudioItemType.UNSPECIFIC -> searchUnspecific(query)
        }
        return searchResults
    }

    private suspend fun searchUnspecific(query: String): MutableList<AudioItem> {
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

    /**
     * Search given an unspecific query and a fallback query. This is used for voice search in case first query does
     * not return any results e.g. "ACDC" but alternative fields "AC/DC" do return results
     */
    suspend fun searchUnspecificWithFallback(query: String, fallbackQuery: String? = null): List<AudioItem> {
        var audioItems: MutableList<AudioItem> = searchUnspecific(query)
        if (audioItems.isNotEmpty()) {
            return audioItems
        } else {
            logger.debug(TAG, "No results for primary query: $query")
            fallbackQuery?.let {
                if (it.isNotBlank() && it != query) {
                    logger.debug(TAG, "Trying fallback query: $fallbackQuery")
                    audioItems = searchUnspecific(it)
                }
            }
        }
        return audioItems
    }

    fun createMetadataForItem(audioItem: AudioItem): MediaMetadataCompat {
        logger.debug(TAG, "createMetadataForItem($audioItem)")
        return metadataMaker.createMetadataForItem(audioItem)
    }

    private suspend fun getAlbumArtForFile(file: FileLike): ByteArray? {
        val byteArrayFromCache: ByteArray? = recentAudioItemToAlbumArtMap[file.uri.toString()]
        if (byteArrayFromCache != null) {
            logger.debug(TAG, "Returning album art from cache for: $file")
            return byteArrayFromCache
        }
        val albumArtBytes: ByteArray?
        when (file) {
            is AudioFile -> {
                val audioItem = createAudioItemForFile(file)
                albumArtBytes = metadataMaker.getEmbeddedAlbumArtForAudioItem(audioItem)
                if (albumArtBytes != null) {
                    val albumArtResized = metadataMaker.resizeAlbumArt(albumArtBytes)
                    if (albumArtResized != null) {
                        logger.debug(TAG, "Got album art with size ${albumArtResized.size} for: ${audioItem.uri}")
                        recentAudioItemToAlbumArtMap[file.uri.toString()] = albumArtResized
                    }
                    return albumArtResized
                }
            }
            is GeneralFile -> {
                albumArtBytes = audioFileStorage.getByteArrayForURI(file.uri)
                val albumArtResized = metadataMaker.resizeAlbumArt(albumArtBytes)
                if (albumArtResized != null) {
                    logger.debug(TAG, "Found album art in directory with size ${albumArtResized.size} for: ${file.uri}")
                    recentAudioItemToAlbumArtMap[file.uri.toString()] = albumArtResized
                }
                return albumArtResized
            }
        }
        logger.warning(TAG, "Could not retrieve any album art for: ${file.uri}")
        return null
    }

    suspend fun getAlbumArtForArtURI(uri: Uri): ByteArray? {
        val uriString = uri.toString()
        if (!uriString.contains("$ART_URI_PART/$ART_URI_PART_FILE")) {
            val repo = getPrimaryRepository()
            val albumForAlbumArt = repo?.getAlbumForAlbumArt(uriString)
            if (albumForAlbumArt != null && albumForAlbumArt.albumArtURIString.isNotBlank()) {
                logger.debug(TAG, "Got album for album art: $albumForAlbumArt")
                val albumArtFile: FileLike
                val albumArtSourceURI = Uri.parse(albumForAlbumArt.albumArtSourceURIString)
                if (albumArtSourceURI == Uri.EMPTY) {
                    logger.debug(TAG, "Album art URI is empty for: $albumForAlbumArt")
                    return null
                }
                albumArtFile = if (albumForAlbumArt.hasFolderImage) {
                    GeneralFile(albumArtSourceURI)
                } else {
                    AudioFile(albumArtSourceURI)
                }
                return getAlbumArtForFile(albumArtFile)
            } else {
                // No album associated with this URI, could be a track without album info
                // https://github.com/MoleMan1024/audiowagon/issues/79
                val trackForAlbumArt = repo?.getTrackForAlbumArt(uriString)
                if (trackForAlbumArt != null) {
                    if (trackForAlbumArt.albumArtURIString.isBlank()) {
                        logger.warning(TAG, "Track album art URI is blank")
                        return null
                    }
                    val albumArtSourceURI = Uri.parse(trackForAlbumArt.uriString)
                    if (albumArtSourceURI == Uri.EMPTY) {
                        return null
                    }
                    val fileForTrack = AudioFile(albumArtSourceURI)
                    return getAlbumArtForFile(fileForTrack)
                } else {
                    logger.warning(TAG, "No track for album art")
                    return null
                }
            }
        } else {
            val pathInURI = uri.path?.replace("^/$ART_URI_PART/$ART_URI_PART_FILE".toRegex(), "") ?: return null
            val audioFile =
                AudioFile(Util.createURIForPath(audioFileStorage.getPrimaryStorageLocation().storageID, pathInURI))
            val albumArtFile = findAlbumArtFor(audioFile) ?: return null
            return getAlbumArtForFile(albumArtFile)
        }
    }

    private suspend fun findAlbumArtFor(
        audioFile: AudioFile, metadataRetriever: MediaMetadataRetriever? = null
    ): FileLike? {
        val audioItem = createAudioItemForFile(audioFile)
        val hasEmbeddedAlbumArt = if (metadataRetriever != null) {
            metadataMaker.getAlbumArtFromMetadataRetriever(metadataRetriever) != null
        } else {
            metadataMaker.hasEmbeddedAlbumArt(audioItem)
        }
        if (hasEmbeddedAlbumArt) {
            logger.debug(TAG, "Found embedded album art for: ${audioFile.name}")
            return AudioFile(audioFile.uri)
        }
        val albumArtFile = audioFileStorage.getAlbumArtFileInDirectoryForURI(audioItem.uri)
        if (albumArtFile != null) {
            logger.debug(TAG, "Found album art in directory for: ${audioFile.name}")
            return GeneralFile(albumArtFile.uri)
        }
        logger.debug(TAG, "No album art for: $audioFile")
        return null
    }

    suspend fun getRepoForContentHierarchyID(contentHierarchyID: ContentHierarchyID): AudioItemRepository? {
        val storageID = contentHierarchyID.storageID
        libraryMutex.withLock {
            return storageToRepoMap[storageID]
        }
    }

    fun getAllStorageIDs(): List<String> {
        return runBlocking(dispatcher) {
            libraryMutex.withLock {
                return@runBlocking storageToRepoMap.keys.toList()
            }
        }
    }

    suspend fun areAnyReposAvail(): Boolean {
        libraryMutex.withLock {
            return storageToRepoMap.isNotEmpty()
        }
    }

    suspend fun suspend() {
        logger.debug(TAG, "suspend()")
        libraryMutex.withLock {
            storageToRepoMap.values.forEach { it.close() }
            storageToRepoMap.clear()
            recentAudioItemToAlbumArtMap.clear()
        }
    }

    private fun notifyObservers(exc: Exception) {
        libraryExceptionObservers.forEach { it(exc) }
    }

    fun cancelBuildLibrary() {
        if (isBuildingLibrary) {
            logger.debug(TAG, "cancelBuildLibrary()")
            isBuildLibraryCancelled = true
            isBuildingLibrary = false
            buildLibraryJobs.forEach {
                it.cancel()
            }
        }
    }

    // we do not support multiple repositories
    suspend fun getPrimaryRepository(): AudioItemRepository? {
        libraryMutex.withLock {
            if (storageToRepoMap.isEmpty()) {
                return null
            }
            return storageToRepoMap.values.toTypedArray()[0]
        }
    }

    suspend fun getPseudoCompilationArtistID(): Long? {
        return getPrimaryRepository()?.getPseudoCompilationArtistID()
    }

    fun clearAlbumArtCache() {
        recentAudioItemToAlbumArtMap.clear()
    }

    fun setViewTabs(viewTabSettings: List<ViewTabSetting>) {
        logger.debug(TAG, "setViewTabs(${viewTabSettings})")
        viewTabs.clear()
        viewTabSettings.forEach { viewTabs.add(it) }
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
