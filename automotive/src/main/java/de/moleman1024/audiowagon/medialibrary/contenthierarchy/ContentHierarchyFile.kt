/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary.contenthierarchy

import android.content.Context
import android.support.v4.media.MediaBrowserCompat.MediaItem
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.filestorage.data.AudioFile
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import de.moleman1024.audiowagon.filestorage.data.Directory
import de.moleman1024.audiowagon.filestorage.FileLike
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary
import kotlinx.coroutines.ExperimentalCoroutinesApi

private const val TAG = "CHFile"
private val logger = Logger

/**
 * A file in the browse view
 */
@ExperimentalCoroutinesApi
class ContentHierarchyFile(
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
        val parentPath: String
        try {
            parentPath = Util.getParentPath(id.path)
        } catch (exc: RuntimeException) {
            logger.exception(TAG, exc.message.toString(), exc)
            return listOf()
        }
        val directoryURI = Util.createURIForPath(storageLocation.storageID, parentPath.removePrefix("/"))
        val directoryContents: List<FileLike> =
            storageLocation.getDirectoryContentsPlayable(Directory(directoryURI)).sortedBy { it.name.lowercase() }
        directoryContents.filterIsInstance<AudioFile>().forEach {
            val audioFile = AudioFile(Util.createURIForPath(storageLocation.storageID, it.path))
            items += AudioItemLibrary.createAudioItemForFile(audioFile)
        }
        return items
    }

}
