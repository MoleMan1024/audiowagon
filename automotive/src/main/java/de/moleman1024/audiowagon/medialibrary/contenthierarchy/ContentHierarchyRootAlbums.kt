/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary.contenthierarchy

import android.content.Context
import android.support.v4.media.MediaBrowserCompat.MediaItem
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * The browse view showing all albums on the device (or showing groups of albums)
 */
@ExperimentalCoroutinesApi
class ContentHierarchyRootAlbums(
    context: Context,
    audioItemLibrary: AudioItemLibrary
) :
    ContentHierarchyElement(ContentHierarchyID(ContentHierarchyType.ROOT_ALBUMS), context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        var items = mutableListOf<MediaItem>()
        val numAlbums = getNumAlbums()
        if (!hasTooManyItems(numAlbums)) {
            val audioItems = getAudioItems()
            for (album in audioItems) {
                val description = audioItemLibrary.createAudioItemDescription(album)
                items += MediaItem(description, album.browsPlayableFlags)
            }
        } else {
            val groupContentHierarchyID = id
            groupContentHierarchyID.type = ContentHierarchyType.ALBUM_GROUP
            items = createGroups(groupContentHierarchyID, numAlbums)
            val repo = audioItemLibrary.getPrimaryRepository() ?: return items
            val audioItemUnknownAlbum = repo.getAudioItemForUnknownAlbum()
            if (audioItemUnknownAlbum != null) {
                val description = audioItemLibrary.createAudioItemDescription(audioItemUnknownAlbum)
                items += MediaItem(description, audioItemUnknownAlbum.browsPlayableFlags)
            }
        }
        return items
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val repo = audioItemLibrary.getPrimaryRepository() ?: return emptyList()
        items += repo.getAllAlbums()
        // items are already sorted in database query
        return items
    }

    private suspend fun getNumAlbums(): Int {
        var numAlbums = 0
        val repo = audioItemLibrary.getPrimaryRepository() ?: return 0
        numAlbums += repo.getNumAlbums()
        return numAlbums
    }

}
