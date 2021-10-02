/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary.contenthierarchy

import android.content.Context
import android.support.v4.media.MediaBrowserCompat.MediaItem
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary

/**
 * The browse view showing all artists on the device (or showing groups of artists)
 */
class ContentHierarchyRootArtists(
    context: Context,
    audioItemLibrary: AudioItemLibrary
) :
    ContentHierarchyElement(ContentHierarchyID(ContentHierarchyType.ROOT_ARTISTS), context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        var items = mutableListOf<MediaItem>()
        val numArtists = getNumArtists()
        if (!hasTooManyItems(numArtists)) {
            val audioItems = getAudioItems()
            for (artist in audioItems) {
                val description = audioItemLibrary.createAudioItemDescription(artist)
                items += MediaItem(description, artist.browsPlayableFlags)
            }
        } else {
            val groupContentHierarchyID = id
            groupContentHierarchyID.type = ContentHierarchyType.ARTIST_GROUP
            items = createGroups(groupContentHierarchyID, numArtists)
            val repo = audioItemLibrary.getPrimaryRepo() ?: return items
            val audioItemUnknownArtist = repo.getAudioItemForUnknownArtist()
            if (audioItemUnknownArtist != null) {
                val description = audioItemLibrary.createAudioItemDescription(audioItemUnknownArtist)
                items += MediaItem(description, audioItemUnknownArtist.browsPlayableFlags)
            }
        }
        return items
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        val items: MutableList<AudioItem> = mutableListOf()
        val repo = audioItemLibrary.getPrimaryRepo() ?: return emptyList()
        items += repo.getAllArtists()
        return items.sortedBy { it.artist.lowercase() }
    }

    suspend fun getNumArtists(): Int {
        var numArtists = 0
        val repo = audioItemLibrary.getPrimaryRepo() ?: return 0
        numArtists += repo.getNumArtists()
        return numArtists
    }

}
