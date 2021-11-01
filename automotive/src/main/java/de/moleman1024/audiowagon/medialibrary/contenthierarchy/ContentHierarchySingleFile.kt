/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary.contenthierarchy

import android.content.Context
import android.support.v4.media.MediaBrowserCompat.MediaItem
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.filestorage.AudioFile
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary

private const val TAG = "CHSingleFile"
private val logger = Logger

/**
 * A single file is requested (used when preparing playback queue from persistent data)
 */
class ContentHierarchySingleFile(
    id: ContentHierarchyID,
    context: Context,
    audioItemLibrary: AudioItemLibrary,
    private val audioFileStorage: AudioFileStorage
) :
    ContentHierarchyElement(id, context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        throw RuntimeException("Not browsable")
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val storageLocation = audioFileStorage.getPrimaryStorageLocation()
        val audioFile = AudioFile(Util.createURIForPath(storageLocation.storageID, id.path))
        items += audioItemLibrary.createAudioItemForFile(audioFile)
        return items
    }

}
