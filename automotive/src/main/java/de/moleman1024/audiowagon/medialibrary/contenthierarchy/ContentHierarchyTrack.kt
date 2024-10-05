/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary.contenthierarchy

import android.content.Context
import android.support.v4.media.MediaBrowserCompat.MediaItem
import de.moleman1024.audiowagon.enums.ContentHierarchyType
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary
import de.moleman1024.audiowagon.repository.AudioItemRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi

private const val TAG = "CHTrack"
private val logger = Logger

/**
 * A single track in the browse view
 */
@ExperimentalCoroutinesApi
class ContentHierarchyTrack(
    id: ContentHierarchyID,
    context: Context,
    audioItemLibrary: AudioItemLibrary
) :
    ContentHierarchyElement(id, context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        throw RuntimeException("Not browsable")
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        val repo: AudioItemRepository = audioItemLibrary.getRepoForContentHierarchyID(id) ?: return listOf()
        val tracks: MutableList<AudioItem> = mutableListOf()
        try {
            // Issue #18: originally we selecting e.g. a single track in an album we only play that single track.
            // However some users did not understand why next/previous track would not work in that case. Now we try
            // to fill the playback queue intelligently when selecting a single track.
            if (id.albumArtistID > DATABASE_ID_UNKNOWN && id.albumID > DATABASE_ID_UNKNOWN) {
                tracks += repo.getTracksForAlbumAndArtist(id.albumID, id.albumArtistID)
            } else if (id.artistID > DATABASE_ID_UNKNOWN && id.albumID > DATABASE_ID_UNKNOWN) {
                tracks += repo.getTracksForAlbumAndArtist(id.albumID, id.artistID)
            } else if (id.artistID > DATABASE_ID_UNKNOWN && id.albumID == DATABASE_ID_UNKNOWN) {
                tracks += repo.getTracksWithUnknAlbumForArtist(id.artistID)
            } else if (id.artistID == DATABASE_ID_UNKNOWN && id.albumID == DATABASE_ID_UNKNOWN) {
                tracks += repo.getTracksWithUnknAlbum()
            } else if (id.artistID > DATABASE_ID_UNKNOWN) {
                tracks += repo.getTracksForArtist(id.artistID)
            } else if (id.albumID > DATABASE_ID_UNKNOWN) {
                tracks += repo.getTracksForAlbum(id.albumID)
            } else if (id.trackGroupIndex >= 0) {
                // Selected a single track in tracks browse view when tracks are grouped, play tracks in alphabetical
                // order, like shown ( https://github.com/MoleMan1024/audiowagon/issues/157 )
                tracks += repo.getTracksLimitOffset(
                    CONTENT_HIERARCHY_MAX_NUM_ITEMS, CONTENT_HIERARCHY_MAX_NUM_ITEMS * id.trackGroupIndex
                )
            } else {
                // Selected a single track from tracks view or from a search: play tracks in alphabetical order, like
                // shown in tracks view ( https://github.com/MoleMan1024/audiowagon/issues/157 )
                val numTracks = repo.getNumTracks()
                if (!hasTooManyItems(numTracks)) {
                    tracks += repo.getAllTracks()
                } else {
                    // TODO: similar code in other places
                    val lastGroupIndex = numTracks / CONTENT_HIERARCHY_MAX_NUM_ITEMS
                    var offset = 0
                    val contentHierarchyID = serialize(id)
                    for (groupIndex in 0..lastGroupIndex) {
                        val tracksInGroup = repo.getTracksLimitOffset(CONTENT_HIERARCHY_MAX_NUM_ITEMS, offset)
                        if (tracksInGroup.any { it.id == contentHierarchyID }) {
                            tracks += tracksInGroup
                            break
                        }
                        offset += CONTENT_HIERARCHY_MAX_NUM_ITEMS
                    }
                    if (tracks.isEmpty()) {
                        Logger.error(TAG, "Could not find track in track groups: $id")
                        tracks += repo.getAllTracks()
                    }
                }
            }
        } catch (exc: RuntimeException) {
            logger.exception(TAG, exc.message.toString(), exc)
            return listOf()
        }
        return tracks
    }

}
