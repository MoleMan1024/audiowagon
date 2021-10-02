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
import kotlin.math.ceil

private const val TAG = "ChGroupAlbums"
private val logger = Logger

/**
 * A group containing albums in browse view
 */
class ContentHierarchyGroupAlbums(id: ContentHierarchyID, context: Context, audioItemLibrary: AudioItemLibrary) :
    ContentHierarchyElement(id, context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val audioItems = getAudioItems()
        for (album in audioItems) {
            val contentHierarchyID = deserialize(album.id)
            contentHierarchyID.albumGroupIndex = id.albumGroupIndex
            val description =
                audioItemLibrary.createAudioItemDescription(album, paramContentHierarchyID = contentHierarchyID)
            items += MediaItem(description, album.browsPlayableFlags)
        }
        return items
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        val repo = audioItemLibrary.getPrimaryRepo() ?: return emptyList()
        val numAlbumGroups: Int = if (id.artistID < 0) {
            ceil(repo.getNumAlbums().toDouble() / CONTENT_HIERARCHY_MAX_NUM_ITEMS).toInt()
        } else {
            ceil(repo.getNumAlbumsForArtist(id.artistID).toDouble() / CONTENT_HIERARCHY_MAX_NUM_ITEMS).toInt()
        }
        if (id.albumGroupIndex >= numAlbumGroups || id.albumGroupIndex < 0) {
            logger.error(TAG, "Group index ${id.albumGroupIndex} does not fit for $numAlbumGroups album groups")
            return emptyList()
        }
        return if (id.artistID < 0) {
            repo.getAlbumsLimitOffset(
                CONTENT_HIERARCHY_MAX_NUM_ITEMS, CONTENT_HIERARCHY_MAX_NUM_ITEMS * id.albumGroupIndex
            )
        } else {
            repo.getAlbumsForArtistLimitOffset(
                CONTENT_HIERARCHY_MAX_NUM_ITEMS, CONTENT_HIERARCHY_MAX_NUM_ITEMS * id.albumGroupIndex, id.artistID
            )
        }
    }

}
