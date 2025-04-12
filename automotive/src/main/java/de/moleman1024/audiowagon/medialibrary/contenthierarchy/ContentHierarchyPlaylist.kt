/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary.contenthierarchy

import android.content.Context
import android.support.v4.media.MediaBrowserCompat.MediaItem
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import de.moleman1024.audiowagon.filestorage.data.PlaylistFile
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary
import de.moleman1024.audiowagon.medialibrary.PlaylistFileResolver
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * A playlist file in the browse view
 */
@ExperimentalCoroutinesApi
class ContentHierarchyPlaylist(
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
        val storageLocation = audioFileStorage.getPrimaryStorageLocation()
        val playlistFile = PlaylistFile(Util.createURIForPath(storageLocation.storageID, id.path))
        val playlistFileResolver = PlaylistFileResolver(playlistFile.uri, audioFileStorage)
        return playlistFileResolver.parseForAudioItems()
    }

}
