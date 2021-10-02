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

private const val TAG = "CHArtist"
private val logger = Logger

/**
 * A single artist in the browse view (shows the albums belonging to the artist)
 */
class ContentHierarchyArtist(
    id: ContentHierarchyID,
    context: Context,
    audioItemLibrary: AudioItemLibrary
) :
    ContentHierarchyElement(id, context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val numAlbums = getNumAlbums()
        if (!hasTooManyItems(numAlbums)) {
            val albums = getAudioItems()
            if (albums.isNotEmpty()) {
                items += createPseudoPlayAllItem()
            }
            for (album in albums) {
                val description = audioItemLibrary.createAudioItemDescription(album)
                items += MediaItem(description, album.browsPlayableFlags)
            }
        } else {
            // TODO: implement this at some point
            throw NotImplementedError("No support for artists with > $CONTENT_HIERARCHY_MAX_NUM_ITEMS albums")
        }
        return items
    }

    /**
     * Creates pseudo [MediaItem] to play all tracks for this artist
     */
    private fun createPseudoPlayAllItem(): MediaItem {
        val playAllTracksForArtistID = id
        playAllTracksForArtistID.type = ContentHierarchyType.ALL_TRACKS_FOR_ARTIST
        val description = MediaDescriptionCompat.Builder().apply {
            setMediaId(serialize(playAllTracksForArtistID))
            setTitle(context.getString(R.string.browse_tree_pseudo_play_all))
            setIconUri(
                Uri.parse(RESOURCE_ROOT_URI + context.resources.getResourceEntryName(R.drawable.baseline_playlist_play_24))
            )
        }.build()
        return MediaItem(description, MediaItem.FLAG_PLAYABLE)
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        val repo: AudioItemRepository = audioItemLibrary.getRepoForContentHierarchyID(id) ?: return mutableListOf()
        val albums: List<AudioItem>
        try {
            albums = repo.getAlbumsForArtist(id.artistID)
        } catch (exc: IllegalArgumentException) {
            logger.error(TAG, exc.toString())
            return listOf()
        }
        return albums.sortedBy { it.album.lowercase() }
    }

    private suspend fun getNumAlbums(): Int {
        var numAlbums = 0
        val repo = audioItemLibrary.getPrimaryRepo() ?: return 0
        numAlbums += repo.getNumAlbumsForArtist(id.artistID)
        return numAlbums
    }

}
