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
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.filestorage.*
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary
import de.moleman1024.audiowagon.medialibrary.RESOURCE_ROOT_URI

private const val TAG = "CHDirectory"
private val logger = Logger

/**
 * The browse view showing a directory on the device (or showing groups of files)
 */
class ContentHierarchyDirectory(
    id: ContentHierarchyID,
    context: Context,
    audioItemLibrary: AudioItemLibrary,
    private val audioFileStorage: AudioFileStorage
) :
    ContentHierarchyElement(id, context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        val directoryContents = getDirectoryContents()
        return if (!hasTooManyItems(directoryContents.size)) {
            createFileLikeMediaItemsForDir(directoryContents, audioFileStorage)
        } else {
            createGroups(directoryContents)
        }
    }

    fun createGroups(directoryContents: List<FileLike>): MutableList<MediaItem> {
        logger.debug(TAG, "Too many files/dirs (${directoryContents.size}), creating groups")
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
            val firstItemInGroup: AudioItem = audioItemLibrary.createAudioItemForFile(firstAudioFileInGroup)
            val lastItemInGroup: AudioItem = audioItemLibrary.createAudioItemForFile(lastAudioFileInGroup)
            groupContentHierarchyID.directoryGroupIndex = groupIndex
            val description = MediaDescriptionCompat.Builder().apply {
                setTitle(
                    "${firstItemInGroup.title.take(NUM_TITLE_CHARS_FOR_GROUP)} " +
                            "… ${lastItemInGroup.title.take(NUM_TITLE_CHARS_FOR_GROUP)}"
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
        val directoryContents = storageLocation.getDirectoryContents(Directory(directoryURI))
        return directoryContents.filter { !it.name.matches(Util.DIRECTORIES_TO_IGNORE_REGEX) }
            .sortedBy { it.name.lowercase() }
    }

}
