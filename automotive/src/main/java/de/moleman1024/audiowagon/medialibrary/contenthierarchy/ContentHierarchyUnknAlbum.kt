/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary.contenthierarchy

import android.content.Context
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.net.Uri
import android.support.v4.media.MediaDescriptionCompat
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.enums.ContentHierarchyType
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary
import de.moleman1024.audiowagon.medialibrary.RESOURCE_ROOT_URI
import de.moleman1024.audiowagon.repository.AudioItemRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi

private const val TAG = "CHUnknAlbum"
private val logger = Logger

/**
 * A pseudo album "unknown" that is used to collect all tracks for an artist where album information is missing
 */
@ExperimentalCoroutinesApi
class ContentHierarchyUnknAlbum(
    id: ContentHierarchyID,
    context: Context,
    audioItemLibrary: AudioItemLibrary
) : ContentHierarchyElement(id, context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        val items: MutableList<MediaItem> = mutableListOf()
        val numTracks = getNumTracks()
        if (!hasTooManyItems(numTracks)) {
            val tracks = getAudioItems()
            if (tracks.isNotEmpty()) {
                items += createPseudoPlayAllItem()
            }
            for (track in tracks) {
                val description = audioItemLibrary.createAudioItemDescription(track)
                items += MediaItem(description, track.browsPlayableFlags)
            }
        } else {
            val groupContentHierarchyID = id
            groupContentHierarchyID.type = ContentHierarchyType.TRACK_GROUP
            items += createGroups(groupContentHierarchyID, numTracks)
        }
        return items
    }

    /**
     * Creates pseudo [MediaItem] to play all tracks where album is unknown
     */
    private fun createPseudoPlayAllItem(): MediaItem {
        val playAllTracksForUnknAlbum = id
        playAllTracksForUnknAlbum.type = ContentHierarchyType.ALL_TRACKS_FOR_UNKN_ALBUM
        val description = MediaDescriptionCompat.Builder().apply {
            setMediaId(serialize(playAllTracksForUnknAlbum))
            setTitle(context.getString(R.string.browse_tree_pseudo_play_all))
            setIconUri(
                Uri.parse(RESOURCE_ROOT_URI + context.resources.getResourceEntryName(R.drawable.playlist_play))
            )
        }.build()
        return MediaItem(description, MediaItem.FLAG_PLAYABLE)
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        val repo: AudioItemRepository = audioItemLibrary.getRepoForContentHierarchyID(id) ?: return mutableListOf()
        val tracks: List<AudioItem>
        try {
            val artistId = pickArtistOrAlbumArtistID()
            tracks = if (artistId >= DATABASE_ID_UNKNOWN) {
                repo.getTracksWithUnknAlbumForArtist(artistId)
            } else {
                repo.getTracksWithUnknAlbum()
            }
        } catch (exc: IllegalArgumentException) {
            logger.error(TAG, exc.toString())
            return mutableListOf()
        }
        // tracks are already sorted in database query
        return tracks
    }

    private suspend fun getNumTracks(): Int {
        val numTracks: Int
        val repo = audioItemLibrary.getPrimaryRepository() ?: return 0
        val artistId = pickArtistOrAlbumArtistID()
        numTracks = if (artistId >= DATABASE_ID_UNKNOWN) {
            repo.getNumTracksWithUnknAlbumForArtist(artistId)
        } else {
            repo.getNumTracksWithUnknAlbum()
        }
        return numTracks
    }

}
