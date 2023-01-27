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

private const val TAG = "CHSingleTrack"
private val logger = Logger

/**
 * A single track is requested (used when preparing playback queue from persistent data)
 */
@ExperimentalCoroutinesApi
class ContentHierarchySingleTrack(
    id: ContentHierarchyID,
    context: Context,
    audioItemLibrary: AudioItemLibrary
) :
    ContentHierarchyElement(id, context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        throw RuntimeException("Not browsable")
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        val repo: AudioItemRepository = audioItemLibrary.getRepoForContentHierarchyID(id) ?: return listOf()
        val tracks: MutableList<AudioItem> = mutableListOf()
        try {
            // get a single track only
            tracks += repo.getTrack(id.trackID)
        } catch (exc: RuntimeException) {
            if (exc.message?.contains("No track for") == true) {
                // If there is a mismatch for persistent data because new tracks have been added to USB drive,
                // avoid to log an exception stacktrace here because it will be repeated for every item in persisted
                // playback queue
                logger.error(TAG, "Possible problem with persistent data: ${exc.message}")
            } else {
                logger.exception(TAG, exc.message.toString(), exc)
            }
            return listOf()
        }
        return tracks
    }

}
