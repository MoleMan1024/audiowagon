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
import de.moleman1024.audiowagon.repository.AudioItemRepository

private const val TAG = "CHTrack"
private val logger = Logger

/**
 * A single track in the browse view
 */
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
            if (id.artistID > DATABASE_ID_UNKNOWN && id.albumID > DATABASE_ID_UNKNOWN) {
                tracks += repo.getTracksForAlbumAndArtist(id.albumID, id.artistID)
            } else if (id.artistID >= DATABASE_ID_UNKNOWN && id.albumID == DATABASE_ID_UNKNOWN) {
                tracks += repo.getTracksWithUnknAlbumForArtist(id.artistID)
            } else if (id.artistID > DATABASE_ID_UNKNOWN) {
                tracks += repo.getTracksForArtist(id.artistID)
            } else if (id.albumID > DATABASE_ID_UNKNOWN) {
                tracks += repo.getTracksForAlbum(id.albumID)
            } else {
                // play a single track only, fill up playback queue with random tracks
                tracks += repo.getTrack(id.trackID)
                tracks += repo.getRandomTracks(NUM_ITEMS_FOR_SHUFFLE_ALL-1)
            }
        } catch (exc: RuntimeException) {
            logger.exception(TAG, exc.message.toString(), exc)
            return listOf()
        }
        return tracks
    }

}
