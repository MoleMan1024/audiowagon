/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary

import android.content.Context
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.log.Logger

private const val TAG = "CHRoot"
private val logger = Logger

class ContentHierarchyRoot(
    context: Context, audioItemLibrary: AudioItemLibrary
) :
    ContentHierarchyElement(CONTENT_HIERARCHY_ID_ROOT, context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        val items: MutableList<MediaItem> = mutableListOf()
        val categoriesMap = mutableMapOf<String, Int>()
        categoriesMap[CONTENT_HIERARCHY_TRACKS_ROOT] = R.string.browse_tree_category_tracks
        if (audioItemLibrary.areAnyStoragesAvail()) {
            categoriesMap[CONTENT_HIERARCHY_ALBUMS_ROOT] = R.string.browse_tree_category_albums
            categoriesMap[CONTENT_HIERARCHY_ARTISTS_ROOT] = R.string.browse_tree_category_artists
        }
        for ((category, titleID) in categoriesMap) {
            val description = MediaDescriptionCompat.Builder().apply {
                setMediaId(category)
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
