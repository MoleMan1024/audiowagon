/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary.contenthierarchy

import android.content.Context
import android.net.Uri
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.SharedPrefs
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.filestorage.*
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary
import de.moleman1024.audiowagon.medialibrary.MetadataReadSetting
import de.moleman1024.audiowagon.medialibrary.RESOURCE_ROOT_URI
import kotlinx.coroutines.ExperimentalCoroutinesApi

private const val TAG = "CHDirectory"
private val logger = Logger

/**
 * The browse view showing a directory on the device (or showing groups of files)
 */
@ExperimentalCoroutinesApi
class ContentHierarchyDirectory(
    id: ContentHierarchyID,
    context: Context,
    audioItemLibrary: AudioItemLibrary,
    private val audioFileStorage: AudioFileStorage
) :
    ContentHierarchyElement(id, context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        val directoryContents = getDirectoryContents()
        val filesDirs = mutableListOf<MediaItem>()
        if (directoryContents.isNotEmpty()) {
            val metadataReadSetting = SharedPrefs.getMetadataReadSettingEnum(context, logger, TAG)
            if (metadataReadSetting != MetadataReadSetting.OFF && !audioItemLibrary.isBuildingLibrary) {
                val storageLocation = audioFileStorage.getPrimaryStorageLocation()
                val directoryURI = Util.createURIForPath(storageLocation.storageID, id.path.removePrefix("/"))
                val anyFileFound = audioItemLibrary.getFilesInDirRecursive(directoryURI, 1).isNotEmpty()
                if (anyFileFound) {
                    filesDirs += createPseudoPlayAllItem()
                }
            }
        }
        return if (!hasTooManyItems(directoryContents.size)) {
            filesDirs += createFileLikeMediaItemsForDir(directoryContents, audioFileStorage)
            filesDirs
        } else {
            filesDirs += createGroups(directoryContents)
            filesDirs
        }
    }

    /**
     * Creates pseudo [MediaItem] to play all files in this directory recursively
     */
    private fun createPseudoPlayAllItem(): MediaItem {
        val playAllFilesForDirectoryID = id
        playAllFilesForDirectoryID.type = ContentHierarchyType.ALL_FILES_FOR_DIRECTORY
        val description = MediaDescriptionCompat.Builder().apply {
            setMediaId(serialize(playAllFilesForDirectoryID))
            setTitle(context.getString(R.string.browse_tree_pseudo_play_all))
            setIconUri(
                Uri.parse(RESOURCE_ROOT_URI + context.resources.getResourceEntryName(R.drawable.baseline_playlist_play_24))
            )
        }.build()
        return MediaItem(description, MediaItem.FLAG_PLAYABLE)
    }

    fun createGroups(directoryContents: List<FileLike>): MutableList<MediaItem> {
        logger.debug(TAG, "Too many files/dirs (${directoryContents.size}), creating groups")
        if (numTitleCharsPerGroup < 0) {
            setNumTitleCharsPerGroupBasedOnScreenWidth()
        }
        val groups = mutableListOf<MediaItem>()
        var offsetStart = 0
        val lastGroupIndex = directoryContents.size / CONTENT_HIERARCHY_MAX_NUM_ITEMS
        val groupContentHierarchyID = id
        groupContentHierarchyID.type = ContentHierarchyType.FILELIKE_GROUP
        val storageLocation = audioFileStorage.getPrimaryStorageLocation()
        for (groupIndex in 0 .. lastGroupIndex) {
            if (offsetStart >= directoryContents.size) {
                break
            }
            val offsetEnd = if (groupIndex < lastGroupIndex) {
                offsetStart + CONTENT_HIERARCHY_MAX_NUM_ITEMS - 1
            } else {
                offsetStart + (directoryContents.size % CONTENT_HIERARCHY_MAX_NUM_ITEMS) - 1
            }
            if (offsetEnd >= directoryContents.size) {
                break
            }
            val firstAudioFileInGroup = AudioFile(
                Util.createURIForPath(storageLocation.storageID, directoryContents[offsetStart].path)
            )
            val lastAudioFileInGroup = AudioFile(
                Util.createURIForPath(storageLocation.storageID, directoryContents[offsetEnd].path)
            )
            val firstItemInGroup: AudioItem = AudioItemLibrary.createAudioItemForFile(firstAudioFileInGroup)
            val lastItemInGroup: AudioItem = AudioItemLibrary.createAudioItemForFile(lastAudioFileInGroup)
            groupContentHierarchyID.directoryGroupIndex = groupIndex
            val description = MediaDescriptionCompat.Builder().apply {
                setTitle(
                    "${firstItemInGroup.title.take(numTitleCharsPerGroup)} " +
                            "… ${lastItemInGroup.title.take(numTitleCharsPerGroup)}"
                )
                setIconUri(Uri.parse(RESOURCE_ROOT_URI
                        + context.resources.getResourceEntryName(R.drawable.baseline_summarize_24)))
                setMediaId(serialize(groupContentHierarchyID))
            }.build()
            groups += MediaItem(description, MediaItem.FLAG_BROWSABLE)
            offsetStart += CONTENT_HIERARCHY_MAX_NUM_ITEMS
        }
        return groups
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        throw RuntimeException("Not playable")
    }

    fun getDirectoryContents(): List<FileLike> {
        val storageLocation: AudioFileStorageLocation
        try {
            storageLocation = audioFileStorage.getPrimaryStorageLocation()
        } catch (exc: NoSuchElementException) {
            return listOf()
        }
        val directoryURI = Util.createURIForPath(storageLocation.storageID, id.path.removePrefix("/"))
        val directoryContents = storageLocation.getDirectoryContentsPlayable(Directory(directoryURI))
        // do not ignore articles using sortName here, that is only done for artists/albums/tracks
        return directoryContents.filter { !it.name.matches(Util.DIRECTORIES_TO_IGNORE_REGEX) }
            .sortedWith(compareBy { it.name.lowercase() })
    }

}
