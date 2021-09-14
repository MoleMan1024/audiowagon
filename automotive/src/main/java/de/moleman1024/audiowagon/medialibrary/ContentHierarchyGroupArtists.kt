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
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import de.moleman1024.audiowagon.log.Logger


/**
 * Group of artists
 */
class ContentHierarchyGroupArtists(id: String, context: Context, audioItemLibrary: AudioItemLibrary) :
    ContentHierarchyElement(id, context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        val mediaItems = mutableListOf<MediaItem>()
        val audioItems = getAudioItems()
        for (artist in audioItems) {
            val description = audioItemLibrary.createAudioItemDescription(artist)
            mediaItems += MediaItem(description, artist.browsPlayableFlags)
        }
        return mediaItems
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        for (repo in audioItemLibrary.storageToRepoMap.values) {
            // TODO: this can be improved, we don't need to request all artist here, adds latency
            items += repo.getAllArtists()
        }
        val groupIndex = getDatabaseID(id).toInt()
        // TODO: not nice since this depends on implementation in ContentHierarchyAllArtists
        return items.sortedBy { it.artist.lowercase() }.chunked(CONTENT_HIERARCHY_NUM_ITEMS_PER_GROUP)[groupIndex]
    }

}
