/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary.contenthierarchy

import android.content.Context
import android.support.v4.media.MediaBrowserCompat.MediaItem
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary
import de.moleman1024.audiowagon.repository.AudioItemRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi

private const val TAG = "CHAllTracksForArtist"
private val logger = Logger

@ExperimentalCoroutinesApi
class ContentHierarchyAllTracksForArtist(
    id: ContentHierarchyID,
    context: Context,
    audioItemLibrary: AudioItemLibrary
) :
    ContentHierarchyElement(id, context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        throw RuntimeException("Not browsable")
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        val repo: AudioItemRepository = audioItemLibrary.getRepoForContentHierarchyID(id) ?: return mutableListOf()
        val tracks: List<AudioItem>
        try {
            val id = if (id.artistID >= DATABASE_ID_UNKNOWN) {
                id.artistID
            } else {
                id.albumArtistID
            }
            tracks = repo.getTracksForArtist(id)
        } catch (exc: IllegalArgumentException) {
            logger.error(TAG, exc.toString())
            return mutableListOf()
        }
        return tracks.sortedWith(
            compareBy(
                { it.album.lowercase() },
                { it.discNum },
                { it.trackNum },
                { it.sortName.lowercase() },
                { it.title.lowercase() })
        )
    }

}
