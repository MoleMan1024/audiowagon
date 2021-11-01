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
import de.moleman1024.audiowagon.medialibrary.*

private const val TAG = "CHRootFiles"
private val logger = Logger

/**
 * The browse view showing all files/directories on the device (plus a pseudo item to shuffle all files) (or
 * showing groups of files)
 */
class ContentHierarchyRootFiles(
    context: Context,
    audioItemLibrary: AudioItemLibrary,
    private val audioFileStorage: AudioFileStorage
) :
    ContentHierarchyElement(ContentHierarchyID(ContentHierarchyType.ROOT_FILES), context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        if (!Util.isLegalDisclaimerAgreed(context) || !audioFileStorage.areAnyStoragesAvail()) {
            logger.debug(TAG, "Showing pseudo MediaItem 'no entries available'")
            items += createPseudoNoEntriesItem()
            return items
        }
        val directoryContents = getDirectoryContents()
        if (!hasTooManyItems(directoryContents.size)) {
            items += createFileLikeMediaItemsForDir(directoryContents, audioFileStorage)
        } else {
            val contentHierarchyID = id
            contentHierarchyID.path = "/"
            val contentHierarchyDirectory = ContentHierarchyDirectory(
                contentHierarchyID, context, audioItemLibrary, audioFileStorage
            )
            items += contentHierarchyDirectory.createGroups(directoryContents)
        }
        return items
    }

    private fun createPseudoNoEntriesItem(): MediaItem {
        val numConnectedDevices = audioFileStorage.getNumConnectedDevices()
        var title = context.getString(R.string.browse_tree_no_entries_title)
        var subtitle = context.getString(R.string.browse_tree_no_usb_drive)
        if (numConnectedDevices > 0) {
            if (Util.isLegalDisclaimerAgreed(context)) {
                subtitle = context.getString(R.string.browse_tree_usb_drive_ejected)
            } else {
                logger.debug(TAG, "Legal disclaimer was not yet agreed")
                title = context.getString(R.string.browse_tree_need_to_agree_legal_title)
                subtitle = context.getString(R.string.browse_tree_need_to_agree_legal_desc)
            }
        }
        val description = MediaDescriptionCompat.Builder().apply {
            setMediaId(serialize(ContentHierarchyID(ContentHierarchyType.NONE)))
            setTitle(title)
            setSubtitle(subtitle)
            setIconUri(
                Uri.parse(RESOURCE_ROOT_URI
                        + context.resources.getResourceEntryName(R.drawable.baseline_report_problem_24))
            )
        }.build()
        // Tapping this should do nothing. We use BROWSABLE flag here, when clicked an empty subfolder will open.
        // This is the better alternative than flag PLAYABLE which will open the playback view in front of the browser
        return MediaItem(description, MediaItem.FLAG_BROWSABLE)
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        throw RuntimeException("Not playable")
    }

    private fun getDirectoryContents(): List<FileLike> {
        val storageLocation: AudioFileStorageLocation
        try {
            storageLocation = audioFileStorage.getPrimaryStorageLocation()
        } catch (exc: NoSuchElementException) {
            return listOf()
        }
        val rootDirectory = Directory(storageLocation.getRootURI())
        val directoryContents = storageLocation.getDirectoryContents(rootDirectory)
        return directoryContents.filter { !it.name.matches(Util.DIRECTORIES_TO_IGNORE_REGEX) }
            .sortedBy { it.name.lowercase() }
    }

}
