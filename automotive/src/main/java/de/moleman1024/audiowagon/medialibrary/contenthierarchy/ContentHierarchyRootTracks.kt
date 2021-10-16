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
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.*

private const val TAG = "CHRootTracks"
private val logger = Logger

/**
 * The browse view showing all tracks on the device (plus a pseudo item to shuffle all tracks) (or showing groups of
 * tracks)
 */
class ContentHierarchyRootTracks(
    context: Context,
    audioItemLibrary: AudioItemLibrary,
    private val audioFileStorage: AudioFileStorage
) :
    ContentHierarchyElement(ContentHierarchyID(ContentHierarchyType.ROOT_TRACKS), context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        if (!audioItemLibrary.areAnyReposAvail()) {
            logger.debug(TAG, "Showing pseudo MediaItem 'no entries available'")
            items += createPseudoNoEntriesItem()
            return items
        }
        if (audioItemLibrary.isBuildingLibray) {
            items += createPseudoFoundXItems()
            return items
        }
        val numTracks = getNumTracks()
        if (!hasTooManyItems(numTracks)) {
            val audioItems = getAudioItems()
            if (audioItems.isNotEmpty()) {
                items += createPseudoShuffleAllItem()
            }
            for (track in audioItems) {
                val description = audioItemLibrary.createAudioItemDescription(track)
                items += MediaItem(description, track.browsPlayableFlags)
            }
        } else {
            items += createPseudoShuffleAllItem()
            val groupContentHierarchyID = id
            groupContentHierarchyID.type = ContentHierarchyType.TRACK_GROUP
            // TODO: low prio: also handle case where groups of 400 tracks are not sufficient (~ 400 x 400 items)
            items += createGroups(groupContentHierarchyID, numTracks)
        }
        return items
    }

    /**
     * Creates a pseudo-[MediaItem] to show a "Shuffle All" item at the top of the track list always
     */
    private fun createPseudoShuffleAllItem(): MediaItem {
        val description = MediaDescriptionCompat.Builder().apply {
            setMediaId(serialize(ContentHierarchyID(ContentHierarchyType.SHUFFLE_ALL_TRACKS)))
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
            setMediaId(serialize(ContentHierarchyID(ContentHierarchyType.NONE)))
            setTitle(title)
            setSubtitle(subtitle)
            setIconUri(
                Uri.parse(RESOURCE_ROOT_URI + context.resources.getResourceEntryName(R.drawable.baseline_report_problem_24))
            )
        }.build()
        // Tapping this should do nothing. We use BROWSABLE flag here, when clicked an empty subfolder will open.
        // This is the better alternative than flag PLAYABLE which will open the playback view in front of the browser
        return MediaItem(description, MediaItem.FLAG_BROWSABLE)
    }

    private fun createPseudoFoundXItems(): MediaItem {
        logger.debug(TAG, "Showing pseudo MediaItem 'Found <num> items ...'")
        val numItemsFoundText = context.getString(
            R.string.notif_indexing_text_in_progress_num_items,
            audioItemLibrary.numFilesSeenWhenBuildingLibrary
        )
        val description = MediaDescriptionCompat.Builder().apply {
            setMediaId(serialize(ContentHierarchyID(ContentHierarchyType.NONE)))
            setTitle(context.getString(R.string.notif_indexing_text_in_progress))
            setSubtitle(numItemsFoundText)
            setIconUri(
                Uri.parse(RESOURCE_ROOT_URI + context.resources.getResourceEntryName(R.drawable.baseline_sync_24))
            )
        }.build()
        return MediaItem(description, MediaItem.FLAG_BROWSABLE)
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val repo = audioItemLibrary.getPrimaryRepository() ?: return emptyList()
        items += repo.getAllTracks()
        return items.sortedBy { it.title.lowercase() }
    }

    private suspend fun getNumTracks(): Int {
        var numTracks = 0
        val repo = audioItemLibrary.getPrimaryRepository() ?: return 0
        numTracks += repo.getNumTracks()
        return numTracks
    }

}
