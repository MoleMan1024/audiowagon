/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary

import android.content.Context
import android.support.v4.media.MediaBrowserCompat.MediaItem
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.repository.AudioItemRepository

private const val TAG = "CHTrack"
private val logger = Logger

class ContentHierarchyTrack(
    id: String,
    context: Context,
    audioItemLibrary: AudioItemLibrary
) :
    ContentHierarchyElement(id, context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        val tracks: List<AudioItem> = getAudioItems()
        if (tracks.isEmpty()) {
            return listOf()
        }
        if (tracks.size != 1) {
            logger.warning(TAG, "Multiple tracks for given content hierarchy id: $id")
        }
        val track: AudioItem = tracks[0]
        val description = audioItemLibrary.createAudioItemDescription(track)
        return listOf(MediaItem(description, track.browsPlayableFlags))
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        val repo: AudioItemRepository = audioItemLibrary.getRepoForBrowserID(id) ?: return listOf()
        val track: AudioItem
        try {
            track = repo.getTrack(getDatabaseID(id))
        } catch (exc: IllegalArgumentException) {
            logger.error(TAG, exc.toString())
            return listOf()
        }
        return listOf(track)
    }

}
