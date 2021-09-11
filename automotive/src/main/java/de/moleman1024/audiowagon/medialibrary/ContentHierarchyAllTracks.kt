/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary

import android.content.Context
import android.net.Uri
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import de.moleman1024.audiowagon.log.Logger

private const val TAG = "CHAllTracks"
private val logger = Logger

/**
 * All tracks
 */
class ContentHierarchyAllTracks(
    context: Context,
    audioItemLibrary: AudioItemLibrary,
    private val audioFileStorage: AudioFileStorage
) :
    ContentHierarchyElement(CONTENT_HIERARCHY_TRACKS_ROOT, context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        var mediaItems = mutableListOf<MediaItem>()
        if (!audioItemLibrary.areAnyStoragesAvail()) {
            logger.debug(TAG, "Showing pseudo MediaItem 'no entries available'")
            mediaItems += createPseudoNoEntriesItem()
            return mediaItems
        }
        if (audioItemLibrary.isBuildingLibray) {
            mediaItems += createPseudoFoundXItems()
            return mediaItems
        }
        val audioItems = getAudioItems()
        if (audioItems.isNotEmpty()) {
            mediaItems += createPseudoShuffleAllItem()
        }
        if (audioItems.size <= CONTENT_HIERARCHY_NUM_ITEMS_PER_GROUP) {
            for (track in audioItems) {
                val description = audioItemLibrary.createAudioItemDescription(track)
                mediaItems += MediaItem(description, track.browsPlayableFlags)
            }
        } else {
            // TODO: handle the case where we go above this number of groups times number of items (right now > 160000
            //  tracks)
            mediaItems += createGroups(audioItems, AudioItemType.GROUP_TRACKS)
        }
        return mediaItems
    }

    private fun createPseudoFoundXItems(): MediaItem {
        logger.debug(TAG, "Showing pseudo MediaItem 'Found <num> items ...'")
        val numItemsFoundText = context.getString(
            R.string.notif_indexing_text_in_progress_num_items,
            audioItemLibrary.numFilesSeenWhenBuildingLibrary
        )
        val description = MediaDescriptionCompat.Builder().apply {
            setMediaId(CONTENT_HIERARCHY_NONE)
            setTitle(context.getString(R.string.notif_indexing_text_in_progress))
            setSubtitle(numItemsFoundText)
            setIconUri(
                Uri.parse(RESOURCE_ROOT_URI + context.resources.getResourceEntryName(R.drawable.baseline_sync_24))
            )
        }.build()
        return MediaItem(description, MediaItem.FLAG_BROWSABLE)
    }

    /**
     * Creates a pseudo-[MediaItem] to show a "Shuffle All" item at the top of the track list always
     */
    private fun createPseudoShuffleAllItem(): MediaItem {
        val description = MediaDescriptionCompat.Builder().apply {
            setMediaId(CONTENT_HIERARCHY_SHUFFLE_ALL)
            setTitle(context.getString(R.string.browse_tree_pseudo_track_shuffle_all))
            setIconUri(
                Uri.parse(RESOURCE_ROOT_URI + context.resources.getResourceEntryName(R.drawable.baseline_shuffle_24))
            )
        }.build()
        return MediaItem(description, MediaItem.FLAG_PLAYABLE)
    }

    private fun createPseudoNoEntriesItem(): MediaItem {
        val numConnectedUSBDevices = audioFileStorage.getNumConnectedDevices()
        var title = context.getString(R.string.browse_tree_no_entries_title)
        var subtitle = context.getString(R.string.browse_tree_no_usb_drive)
        if (numConnectedUSBDevices > 0) {
            if (Util.isLegalDisclaimerAgreed(context)) {
                subtitle = context.getString(R.string.browse_tree_usb_drive_ejected)
            } else {
                title = context.getString(R.string.browse_tree_need_to_agree_legal_title)
                subtitle = context.getString(R.string.browse_tree_need_to_agree_legal_desc)
            }
        }
        val description = MediaDescriptionCompat.Builder().apply {
            setMediaId(CONTENT_HIERARCHY_NONE)
            setTitle(title)
            setSubtitle(subtitle)
            setIconUri(
                Uri.parse(RESOURCE_ROOT_URI + context.resources.getResourceEntryName(R.drawable.baseline_report_problem_24))
            )
        }.build()
        // this should do nothing. We use BROWSABLE flag here, when clicked an empty subfolder will open. This is the
        // better alternative than flag PLAYABLE which will open the playback view in front of the browser
        return MediaItem(description, MediaItem.FLAG_BROWSABLE)
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        for (repo in audioItemLibrary.storageToRepoMap.values) {
            items += repo.getAllTracks()
        }
        return items.sortedBy { it.title.lowercase() }
    }

}
