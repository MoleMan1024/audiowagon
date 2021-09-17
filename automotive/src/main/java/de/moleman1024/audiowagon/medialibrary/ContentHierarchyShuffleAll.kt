/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary

import android.content.Context
import android.support.v4.media.MediaBrowserCompat.MediaItem

private const val NUM_ITEMS_FOR_SHUFFLE_ALL = 500

class ContentHierarchyShuffleAll(
    context: Context,
    audioItemLibrary: AudioItemLibrary
) :
    ContentHierarchyElement(CONTENT_HIERARCHY_SHUFFLE_ALL, context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        throw RuntimeException("Not browsable")
    }

    /**
     * We only return 500 items here, assuming for example 2 minute tracks * 500 items = 16.6 hours of music. This is
     * done to avoid issues with huge media libraries, we don't want playback queues with 20k tracks.
     */
    override suspend fun getAudioItems(): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val numItemsPerRepo: Int = NUM_ITEMS_FOR_SHUFFLE_ALL / audioItemLibrary.storageToRepoMap.size
        for (repo in audioItemLibrary.storageToRepoMap.values) {
            items += repo.getRandomTracks(numItemsPerRepo)
        }
        return items
    }

}
