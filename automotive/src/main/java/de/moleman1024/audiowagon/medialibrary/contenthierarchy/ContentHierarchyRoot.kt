/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary.contenthierarchy

import android.content.Context
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.medialibrary.*

/**
 * The root of the browse tree. Shows max 4 categories (tracks, albums, artists)
 */
class ContentHierarchyRoot(
    context: Context, audioItemLibrary: AudioItemLibrary
) :
    ContentHierarchyElement(ContentHierarchyID(ContentHierarchyType.ROOT), context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        val items: MutableList<MediaItem> = mutableListOf()
        val categoriesMap = mutableMapOf<ContentHierarchyID, Int>()
        // we show the "tracks" even when no USB connected as a container for the "indexing" pseudo-items
        categoriesMap[ContentHierarchyID(ContentHierarchyType.ROOT_TRACKS)] = R.string.browse_tree_category_tracks
        if (audioItemLibrary.areAnyStoragesAvail() && !audioItemLibrary.isBuildingLibray) {
            categoriesMap[ContentHierarchyID(ContentHierarchyType.ROOT_ALBUMS)] = R.string.browse_tree_category_albums
            categoriesMap[ContentHierarchyID(ContentHierarchyType.ROOT_ARTISTS)] = R.string.browse_tree_category_artists
        }
        for ((category, titleID) in categoriesMap) {
            val description = MediaDescriptionCompat.Builder().apply {
                setMediaId(serialize(category))
                setTitle(context.getString(titleID))
            }.build()
            items += MediaItem(description, MediaItem.FLAG_BROWSABLE)
        }
        return items
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        throw RuntimeException("No audio items for root content hierarchy")
    }

}
