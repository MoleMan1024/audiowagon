/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary.contenthierarchy

import android.content.Context
import android.support.v4.media.MediaBrowserCompat.MediaItem
import de.moleman1024.audiowagon.filestorage.AudioFile
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import de.moleman1024.audiowagon.filestorage.Directory
import de.moleman1024.audiowagon.filestorage.FileLike
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary

private const val TAG = "CHGroupFileLike"
private val logger = Logger

/**
 * Group of files/directories in browse view
 */
class ContentHierarchyGroupFileLike(
    id: ContentHierarchyID,
    context: Context,
    audioItemLibrary: AudioItemLibrary,
    private val audioFileStorage: AudioFileStorage
) :
    ContentHierarchyElement(id, context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val directoryContents = getDirectoryContents()
        for (fileOrDir in directoryContents) {
            items += when (fileOrDir) {
                is Directory -> {
                    val description = audioFileStorage.createDirectoryDescription(fileOrDir)
                    MediaItem(description, MediaItem.FLAG_BROWSABLE)
                }
                is AudioFile -> {
                    val description = audioFileStorage.createFileDescription(fileOrDir)
                    MediaItem(description, MediaItem.FLAG_PLAYABLE)
                }
                else -> {
                    throw AssertionError("Invalid type: $fileOrDir")
                }
            }
        }
        return items
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        throw RuntimeException("Not playable")
    }

    private fun getDirectoryContents(): List<FileLike> {
        val contentHierarchyDirectory = ContentHierarchyDirectory(id, context, audioItemLibrary, audioFileStorage)
        val directoryContents = contentHierarchyDirectory.getDirectoryContents()
        val startIndex = CONTENT_HIERARCHY_MAX_NUM_ITEMS * id.directoryGroupIndex
        var endIndex = startIndex + CONTENT_HIERARCHY_MAX_NUM_ITEMS
        if (endIndex >= directoryContents.size) {
            endIndex = directoryContents.size
        }
        return directoryContents.subList(startIndex, endIndex)
    }

}
