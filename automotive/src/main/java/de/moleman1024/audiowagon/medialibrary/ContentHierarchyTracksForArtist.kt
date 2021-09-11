/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary

import android.content.Context
import android.support.v4.media.MediaBrowserCompat.MediaItem
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.repository.AudioItemRepository

private const val TAG = "CHTracksForArtist"
private val logger = Logger

class ContentHierarchyTracksForArtist(
    id: String,
    context: Context,
    audioItemLibrary: AudioItemLibrary
) :
    ContentHierarchyElement(id, context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        throw RuntimeException("Not browsable")
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        val repo: AudioItemRepository = audioItemLibrary.getRepoForBrowserID(id) ?: return mutableListOf()
        val tracks: List<AudioItem>
        try {
            tracks = repo.getTracksForArtist(getDatabaseID(id))
        } catch (exc: IllegalArgumentException) {
            logger.error(TAG, exc.toString())
            return mutableListOf()
        }
        return tracks.sortedWith(compareBy({ it.album.lowercase() }, { it.trackNum }, { it.title.lowercase() }))
    }

}
