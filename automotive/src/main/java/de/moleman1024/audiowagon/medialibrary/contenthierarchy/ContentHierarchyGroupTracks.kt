/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary.contenthierarchy

import android.content.Context
import android.support.v4.media.MediaBrowserCompat.MediaItem
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.math.ceil

private const val TAG = "CHGroupTracks"
private val logger = Logger

/**
 * One group of max 400 tracks in browse view
 */
@ExperimentalCoroutinesApi
class ContentHierarchyGroupTracks(id: ContentHierarchyID, context: Context, audioItemLibrary: AudioItemLibrary) :
    ContentHierarchyElement(id, context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val audioItems = getAudioItems()
        for (track in audioItems) {
            val contentHierarchyID = deserialize(track.id)
            contentHierarchyID.trackGroupIndex = id.trackGroupIndex
            val description =
                audioItemLibrary.createAudioItemDescription(track, paramContentHierarchyID = contentHierarchyID)
            items += MediaItem(description, track.browsPlayableFlags)
        }
        return items
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        val repo = audioItemLibrary.getPrimaryRepository() ?: return emptyList()
        val numTracks = if (id.albumID >= 0 && id.albumArtistID >= 0) {
            repo.getNumTracksForAlbumAndArtist(id.albumID, id.albumArtistID)
        } else if (id.albumID >= 0) {
            repo.getNumTracksForAlbum(id.albumID)
        } else if (id.albumArtistID >= 0) {
            repo.getNumTracksForArtist(id.albumArtistID)
        } else {
            repo.getNumTracks()
        }
        val numTrackGroups: Int = ceil(numTracks.toDouble() / CONTENT_HIERARCHY_MAX_NUM_ITEMS).toInt()
        if (id.trackGroupIndex >= numTrackGroups || id.trackGroupIndex < 0) {
            logger.error(TAG, "Group index ${id.trackGroupIndex} does not fit for $numTrackGroups track groups")
            return emptyList()
        }
        return if (id.albumID >= 0 && id.albumArtistID >= 0) {
            repo.getTracksForArtistAlbumLimitOffset(
                CONTENT_HIERARCHY_MAX_NUM_ITEMS, CONTENT_HIERARCHY_MAX_NUM_ITEMS * id.trackGroupIndex,
                id.albumArtistID, id.albumID
            )
        } else if (id.albumID >= 0) {
            repo.getTracksForAlbumLimitOffset(
                CONTENT_HIERARCHY_MAX_NUM_ITEMS, CONTENT_HIERARCHY_MAX_NUM_ITEMS * id.trackGroupIndex,
                id.albumID
            )
        } else if (id.albumArtistID >= 0) {
            repo.getTracksForArtistLimitOffset(
                CONTENT_HIERARCHY_MAX_NUM_ITEMS, CONTENT_HIERARCHY_MAX_NUM_ITEMS * id.trackGroupIndex,
                id.albumArtistID
            )
        } else {
            repo.getTracksLimitOffset(
                CONTENT_HIERARCHY_MAX_NUM_ITEMS, CONTENT_HIERARCHY_MAX_NUM_ITEMS * id.trackGroupIndex
            )
        }
    }

}
