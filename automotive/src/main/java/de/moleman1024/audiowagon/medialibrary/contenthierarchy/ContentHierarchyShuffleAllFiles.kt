/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary.contenthierarchy

import android.content.Context
import android.support.v4.media.MediaBrowserCompat.MediaItem
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import de.moleman1024.audiowagon.filestorage.Directory
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.consumeEach

private const val TAG = "CHShuffleAllFiles"
private val logger = Logger

/**
 * A pseudo browse view entry to play all files on the device in shuffled order. Shown as first entry in "files"
 * root view.
 *
 * TODO: This is not used right now, delete maybe
 */
class ContentHierarchyShuffleAllFiles(
    context: Context,
    audioItemLibrary: AudioItemLibrary,
    private val audioFileStorage: AudioFileStorage
) :
    ContentHierarchyElement(ContentHierarchyID(ContentHierarchyType.SHUFFLE_ALL_FILES), context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        throw RuntimeException("Not browsable")
    }

    @ExperimentalCoroutinesApi
    override suspend fun getAudioItems(): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        if (!audioFileStorage.areAnyStoragesAvail()) {
            throw AssertionError("Trying to shuffle all audio items with no file storages")
        }
        val storageLocation = audioFileStorage.getPrimaryStorageLocation()
        val directoryURI = Util.createURIForPath(storageLocation.storageID, "/")
        // TODO: this is slow, limiting indexAudioFiles would be an option, but then the randomness is limited
        val audioFileChannel = storageLocation.indexAudioFiles(Directory(directoryURI), audioItemLibrary.scope)
        audioFileChannel.consumeEach { audioFile ->
            logger.debug(TAG, "getAudioItems() received: $audioFile")
            items += audioItemLibrary.createAudioItemForFile(audioFile)
        }
        // avoid huge play queues by limiting amount of files to play
        return items.shuffled().take(NUM_ITEMS_MAX_FOR_PLAY_SHUFFLE_ALL)
    }

}
