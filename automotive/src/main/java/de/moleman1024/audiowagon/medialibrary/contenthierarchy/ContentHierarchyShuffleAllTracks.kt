/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary.contenthierarchy

import android.content.Context
import android.support.v4.media.MediaBrowserCompat.MediaItem
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary
import kotlinx.coroutines.ExperimentalCoroutinesApi

const val NUM_ITEMS_MAX_FOR_PLAY_SHUFFLE_ALL = 500

/**
 * A pseudo browse view entry to play all tracks on the device in shuffled order. Shown as first entry in "tracks"
 * root view.
 */
@ExperimentalCoroutinesApi
class ContentHierarchyShuffleAllTracks(
    context: Context,
    audioItemLibrary: AudioItemLibrary
) :
    ContentHierarchyElement(ContentHierarchyID(ContentHierarchyType.SHUFFLE_ALL_TRACKS), context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        throw RuntimeException("Not browsable")
    }

    /**
     * We only return 500 items here, assuming for example 2 minute tracks * 500 items = 16.6 hours of music. This is
     * done to avoid issues with huge media libraries, we don't want playback queues with 20k tracks.
     */
    override suspend fun getAudioItems(): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        if (audioItemLibrary.storageToRepoMap.isEmpty()) {
            throw AssertionError("Trying to shuffle all audio items with no repositories")
        }
        val repo = audioItemLibrary.getPrimaryRepository() ?: return emptyList()
        items += repo.getRandomTracks(NUM_ITEMS_MAX_FOR_PLAY_SHUFFLE_ALL)
        return items
    }

}
