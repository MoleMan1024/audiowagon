/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary.contenthierarchy

import android.content.Context
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.AlbumStyleSetting
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.math.ceil

private const val TAG = "CHGroupArtists"
private val logger = Logger

/**
 * One group of max 400 artists in browse view
 */
@ExperimentalCoroutinesApi
class ContentHierarchyGroupArtists(id: ContentHierarchyID, context: Context, audioItemLibrary: AudioItemLibrary) :
    ContentHierarchyElement(id, context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val audioItems = getAudioItems()
        for (artist in audioItems) {
            val contentHierarchyID = deserialize(artist.id)
            contentHierarchyID.artistGroupIndex = id.artistGroupIndex
            var extras: Bundle? = null
            if (audioItemLibrary.albumArtStyleSetting == AlbumStyleSetting.GRID) {
                extras = generateExtrasBrowsableGridItems()
            }
            val description =
                audioItemLibrary.createAudioItemDescription(artist, paramContentHierarchyID = contentHierarchyID,
                    extras = extras)
            items += MediaItem(description, artist.browsPlayableFlags)
        }
        return items
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        val numArtists = ContentHierarchyRootArtists(context, audioItemLibrary).getNumArtists()
        val numArtistGroups: Int = ceil(numArtists.toDouble() / CONTENT_HIERARCHY_MAX_NUM_ITEMS).toInt()
        if (id.artistGroupIndex >= numArtistGroups || id.artistGroupIndex < 0) {
            logger.error(TAG, "Group index ${id.artistGroupIndex} does not fit for $numArtistGroups artist groups")
            return emptyList()
        }
        val items: MutableList<AudioItem> = mutableListOf()
        val repo = audioItemLibrary.getPrimaryRepository() ?: return emptyList()
        items += repo.getAlbumAndCompilationArtistsLimitOffset(
            CONTENT_HIERARCHY_MAX_NUM_ITEMS, CONTENT_HIERARCHY_MAX_NUM_ITEMS * id.artistGroupIndex
        )
        return items
    }

}
