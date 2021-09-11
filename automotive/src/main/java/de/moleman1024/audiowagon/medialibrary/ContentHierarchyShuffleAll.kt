/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary

import android.content.Context
import android.support.v4.media.MediaBrowserCompat.MediaItem
import de.moleman1024.audiowagon.filestorage.AudioFileStorage

class ContentHierarchyShuffleAll(
    context: Context,
    audioItemLibrary: AudioItemLibrary,
    private val audioFileStorage: AudioFileStorage
) :
    ContentHierarchyElement(CONTENT_HIERARCHY_SHUFFLE_ALL, context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        throw RuntimeException("Not browsable")
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        val allTracks = ContentHierarchyAllTracks(context, audioItemLibrary, audioFileStorage).getAudioItems()
        return allTracks.shuffled()
    }

}
