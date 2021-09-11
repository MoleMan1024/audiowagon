/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary

import android.content.Context
import android.support.v4.media.MediaBrowserCompat.MediaItem

/**
 * All artists
 */
class ContentHierarchyAllArtists(
    context: Context,
    audioItemLibrary: AudioItemLibrary
) :
    ContentHierarchyElement(CONTENT_HIERARCHY_ARTISTS_ROOT, context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        var mediaItems = mutableListOf<MediaItem>()
        val audioItems = getAudioItems()
        if (audioItems.size <= CONTENT_HIERARCHY_NUM_ITEMS_PER_GROUP) {
            for (artist in audioItems) {
                val description = audioItemLibrary.createAudioItemDescription(artist)
                mediaItems += MediaItem(description, artist.browsPlayableFlags)
            }
        } else {
            mediaItems = createGroups(audioItems, AudioItemType.GROUP_ARTISTS)
        }
        return mediaItems
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        for (repo in audioItemLibrary.storageToRepoMap.values) {
            items += repo.getAllArtists()
        }
        return items.sortedBy { it.artist.lowercase() }
    }

}
