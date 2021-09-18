/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary

import android.content.Context
import android.net.Uri
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.log.Logger

/**
 * All albums
 */
class ContentHierarchyAllAlbums(
    context: Context,
    audioItemLibrary: AudioItemLibrary
) :
    ContentHierarchyElement(CONTENT_HIERARCHY_ALBUMS_ROOT, context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        var mediaItems = mutableListOf<MediaItem>()
        val audioItems = getAudioItems()
        if (audioItems.size <= CONTENT_HIERARCHY_NUM_ITEMS_PER_GROUP) {
            for (album in audioItems) {
                val description = audioItemLibrary.createAudioItemDescription(album)
                mediaItems += MediaItem(description, album.browsPlayableFlags)
            }
        } else {
            mediaItems = createGroups(audioItems, AudioItemType.GROUP_ALBUMS)
        }
        return mediaItems
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        for (repo in audioItemLibrary.storageToRepoMap.values) {
            // TODO: this will need improvements when we actually have multiple repositories in parallel,
            //  albums/artists might overlap across storage locations
            items += repo.getAllAlbums()
        }
        return items.sortedBy { it.album.lowercase() }
    }

}
