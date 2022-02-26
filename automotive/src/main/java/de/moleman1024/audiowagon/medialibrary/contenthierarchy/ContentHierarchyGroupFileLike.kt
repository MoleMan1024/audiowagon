/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary.contenthierarchy

import android.content.Context
import android.support.v4.media.MediaBrowserCompat.MediaItem
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import de.moleman1024.audiowagon.filestorage.FileLike
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary
import kotlinx.coroutines.ExperimentalCoroutinesApi


/**
 * Group of files/directories in browse view
 */
@ExperimentalCoroutinesApi
class ContentHierarchyGroupFileLike(
    id: ContentHierarchyID,
    context: Context,
    audioItemLibrary: AudioItemLibrary,
    private val audioFileStorage: AudioFileStorage
) :
    ContentHierarchyElement(id, context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        val directoryContents = getDirectoryContents()
        return createFileLikeMediaItemsForDir(directoryContents, audioFileStorage)
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        throw RuntimeException("Not playable")
    }

    private fun getDirectoryContents(): List<FileLike> {
        val contentHierarchyDirectory = ContentHierarchyDirectory(id, context, audioItemLibrary, audioFileStorage)
        val directoryContents = contentHierarchyDirectory.getDirectoryContents()
        if (directoryContents.isEmpty()) {
            return listOf()
        }
        val startIndex = CONTENT_HIERARCHY_MAX_NUM_ITEMS * id.directoryGroupIndex
        var endIndex = startIndex + CONTENT_HIERARCHY_MAX_NUM_ITEMS
        if (endIndex >= directoryContents.size) {
            endIndex = directoryContents.size
        }
        return directoryContents.subList(startIndex, endIndex)
    }

}
