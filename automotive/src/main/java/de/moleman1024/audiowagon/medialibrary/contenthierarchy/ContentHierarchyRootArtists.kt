/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary.contenthierarchy

import android.content.Context
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import de.moleman1024.audiowagon.SharedPrefs
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import de.moleman1024.audiowagon.medialibrary.AlbumStyleSetting
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * The browse view showing all artists on the device (or showing groups of artists)
 */
@ExperimentalCoroutinesApi
class ContentHierarchyRootArtists(
    context: Context,
    audioItemLibrary: AudioItemLibrary,
    private val audioFileStorage: AudioFileStorage,
    private val sharedPrefs: SharedPrefs
) :
    ContentHierarchyElement(ContentHierarchyID(ContentHierarchyType.ROOT_ARTISTS), context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        var items = mutableListOf<MediaItem>()
        val numArtists = getNumArtists()
        val hasUnknownArtist = hasUnknownArtist()
        if (!audioItemLibrary.areAnyReposAvail()
            || (!hasUnknownArtist && numArtists <= 0 && !audioItemLibrary.isBuildingLibrary)
        ) {
            items += createPseudoNoEntriesItem(audioFileStorage, sharedPrefs)
            return items
        }
        if (audioItemLibrary.isBuildingLibrary) {
            items += createPseudoFoundXItems()
            return items
        }
        if (!hasTooManyItems(numArtists)) {
            val audioItems = getAudioItems()
            var extras: Bundle? = null
            if (audioItemLibrary.albumArtStyleSetting == AlbumStyleSetting.GRID) {
                extras = generateExtrasBrowsableGridItems()
            }
            for (artist in audioItems) {
                val description = audioItemLibrary.createAudioItemDescription(artist, extras = extras)
                items += MediaItem(description, artist.browsPlayableFlags)
            }
        } else {
            val groupContentHierarchyID = id
            groupContentHierarchyID.type = ContentHierarchyType.ARTIST_GROUP
            items = createGroups(groupContentHierarchyID, numArtists)
            val repo = audioItemLibrary.getPrimaryRepository() ?: return items
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
        val repo = audioItemLibrary.getPrimaryRepository() ?: return emptyList()
        items += repo.getAllAlbumAndCompilationArtists()
        return items
    }

    private suspend fun hasUnknownArtist(): Boolean {
        val repo = audioItemLibrary.getPrimaryRepository() ?: return false
        return repo.getAudioItemForUnknownArtist() != null
    }

}
