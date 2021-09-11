/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary

import android.content.Context
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.net.Uri
import android.support.v4.media.MediaDescriptionCompat
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.repository.AudioItemRepository

private const val TAG = "CHArtist"
private val logger = Logger

class ContentHierarchyArtist(
    id: String,
    context: Context,
    audioItemLibrary: AudioItemLibrary
) :
    ContentHierarchyElement(id, context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        val items: MutableList<MediaItem> = mutableListOf()
        val albums = getAudioItems()
        if (albums.isNotEmpty()) {
            items += createPseudoPlayAllItem()
        }
        for (album in albums) {
            val description = audioItemLibrary.createAudioItemDescription(album)
            items += MediaItem(description, album.browsPlayableFlags)
        }
        return items
    }

    /**
     * Creates pseudo [MediaItem] to play all tracks for this artist
     */
    private fun createPseudoPlayAllItem(): MediaItem {
        val playAllTracksForArtistID = replaceType(id, AudioItemType.TRACKS_FOR_ARTIST)
        val description = MediaDescriptionCompat.Builder().apply {
            setMediaId(playAllTracksForArtistID)
            setTitle(context.getString(R.string.browse_tree_pseudo_play_all))
            setIconUri(
                Uri.parse(RESOURCE_ROOT_URI + context.resources.getResourceEntryName(R.drawable.baseline_playlist_play_24))
            )
        }.build()
        return MediaItem(description, MediaItem.FLAG_PLAYABLE)
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        val repo: AudioItemRepository = audioItemLibrary.getRepoForBrowserID(id) ?: return mutableListOf()
        val albums: List<AudioItem>
        try {
            albums = repo.getAlbumsForArtist(getDatabaseID(id))
        } catch (exc: IllegalArgumentException) {
            logger.error(TAG, exc.toString())
            return listOf()
        }
        return albums.sortedBy { it.album.lowercase() }
    }

}
