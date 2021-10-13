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
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary
import de.moleman1024.audiowagon.medialibrary.RESOURCE_ROOT_URI
import de.moleman1024.audiowagon.repository.AudioItemRepository

private const val TAG = "CHAlbum"
private val logger = Logger
const val DATABASE_ID_UNKNOWN = -1L

/**
 * A single album in the browse view (shows the tracks belonging to the album)
 */
class ContentHierarchyAlbum(
    id: ContentHierarchyID,
    context: Context,
    audioItemLibrary: AudioItemLibrary
) :
    ContentHierarchyElement(id, context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        val items: MutableList<MediaItem> = mutableListOf()
        val numTracksForAlbum = getNumTracks()
        if (!hasTooManyItems(numTracksForAlbum)) {
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
            items += createGroups(groupContentHierarchyID, numTracksForAlbum)
        }
        return items
    }

    /**
     * Creates pseudo [MediaItem] to play all tracks on this album
     */
    private fun createPseudoPlayAllItem(): MediaItem {
        val playAllTracksForAlbumID = id
        playAllTracksForAlbumID.type = ContentHierarchyType.ALL_TRACKS_FOR_ALBUM
        val description = MediaDescriptionCompat.Builder().apply {
            setMediaId(serialize(playAllTracksForAlbumID))
            setTitle(context.getString(R.string.browse_tree_pseudo_play_all))
            setIconUri(
                Uri.parse(RESOURCE_ROOT_URI + context.resources.getResourceEntryName(R.drawable.baseline_playlist_play_24))
            )
        }.build()
        return MediaItem(description, MediaItem.FLAG_PLAYABLE)
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        val repo: AudioItemRepository = audioItemLibrary.getRepoForContentHierarchyID(id) ?: return mutableListOf()
        val tracks: List<AudioItem>
        try {
            tracks = if (id.artistID < 0) {
                repo.getTracksForAlbum(id.albumID)
            } else {
                repo.getTracksForAlbumAndArtist(id.albumID, id.artistID)
            }
        } catch (exc: IllegalArgumentException) {
            logger.error(TAG, exc.toString())
            return mutableListOf()
        }
        return if (id.albumID != DATABASE_ID_UNKNOWN) {
            tracks.sortedBy { it.trackNum }
        } else {
            // unknown album collects all kinds of tracks, makes no sense to sort them by track number
            tracks.sortedBy { it.title.lowercase() }
        }
    }

    private suspend fun getNumTracks(): Int {
        var numTracks = 0
        val repo = audioItemLibrary.getPrimaryRepository() ?: return 0
        numTracks += repo.getNumTracksForAlbum(id.albumID)
        return numTracks
    }

}
