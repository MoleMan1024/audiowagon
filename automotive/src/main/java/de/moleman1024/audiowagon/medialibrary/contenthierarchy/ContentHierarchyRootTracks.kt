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
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary
import de.moleman1024.audiowagon.medialibrary.RESOURCE_ROOT_URI
import kotlinx.coroutines.ExperimentalCoroutinesApi

private const val TAG = "CHRootTracks"
private val logger = Logger

/**
 * The browse view showing all tracks on the device (plus a pseudo item to shuffle all tracks) (or showing groups of
 * tracks)
 */
@ExperimentalCoroutinesApi
class ContentHierarchyRootTracks(
    context: Context,
    audioItemLibrary: AudioItemLibrary,
    private val audioFileStorage: AudioFileStorage,
    private val sharedPrefs: SharedPrefs
) :
    ContentHierarchyElement(ContentHierarchyID(ContentHierarchyType.ROOT_TRACKS), context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val numTracksInRepo = audioItemLibrary.getPrimaryRepository()?.getNumTracks() ?: 0
        if (!audioItemLibrary.areAnyReposAvail() || (numTracksInRepo <= 0 && !audioItemLibrary.isBuildingLibrary)) {
            items += createPseudoNoEntriesItem(audioFileStorage, sharedPrefs)
            return items
        }
        if (audioItemLibrary.isBuildingLibrary) {
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
                Uri.parse(RESOURCE_ROOT_URI + context.resources.getResourceEntryName(R.drawable.shuffle))
            )
        }.build()
        return MediaItem(description, MediaItem.FLAG_PLAYABLE)
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val repo = audioItemLibrary.getPrimaryRepository() ?: return emptyList()
        items += repo.getAllTracks()
        // items are sorted already in database query
        return items
    }

    private suspend fun getNumTracks(): Int {
        var numTracks = 0
        val repo = audioItemLibrary.getPrimaryRepository() ?: return 0
        numTracks += repo.getNumTracks()
        return numTracks
    }

}
