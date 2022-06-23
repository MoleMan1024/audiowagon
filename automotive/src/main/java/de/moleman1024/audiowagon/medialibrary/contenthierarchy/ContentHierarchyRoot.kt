/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary.contenthierarchy

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import androidx.media.utils.MediaConstants
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.SharedPrefs
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.*
import kotlinx.coroutines.ExperimentalCoroutinesApi

private const val TAG = "CHRoot"
private val logger = Logger

/**
 * The root of the browse tree. Shows max 4 categories (tracks, albums, artists, files)
 */
@ExperimentalCoroutinesApi
class ContentHierarchyRoot(
    context: Context, audioItemLibrary: AudioItemLibrary
) :
    ContentHierarchyElement(ContentHierarchyID(ContentHierarchyType.ROOT), context, audioItemLibrary) {
    data class CategoryData(val contentHierarchyID: ContentHierarchyID) {
        var titleID: Int = -1
        var iconID: Int = -1
    }

    override suspend fun getMediaItems(): List<MediaItem> {
        val items: MutableList<MediaItem> = mutableListOf()
        val categories = mutableListOf<CategoryData>()
        val metadataReadSetting = SharedPrefs.getMetadataReadSettingEnum(context, logger, TAG)
        val albumCategoryTitle = R.string.browse_tree_category_albums
        if (metadataReadSetting !in listOf(MetadataReadSetting.OFF, MetadataReadSetting.FILEPATHS_ONLY)) {
            val tracksCategory = CategoryData(ContentHierarchyID(ContentHierarchyType.ROOT_TRACKS))
            tracksCategory.titleID = R.string.browse_tree_category_tracks
            // we use a copy of the music note icon here, because of a bug in the GUI where the highlight color is
            // copied to each item with the same icon ID when selected
            tracksCategory.iconID = R.drawable.category_track
            categories += tracksCategory
            val numTracksInRepo = audioItemLibrary.getPrimaryRepository()?.getNumTracks() ?: 0
            if (audioItemLibrary.areAnyReposAvail() && !audioItemLibrary.isBuildingLibrary && numTracksInRepo > 0) {
                val albumsCategory = CategoryData(ContentHierarchyID(ContentHierarchyType.ROOT_ALBUMS))
                albumsCategory.titleID = albumCategoryTitle
                albumsCategory.iconID = R.drawable.category_album
                categories += albumsCategory
                val artistsCategory = CategoryData(ContentHierarchyID(ContentHierarchyType.ROOT_ARTISTS))
                artistsCategory.titleID = R.string.browse_tree_category_artists
                artistsCategory.iconID = R.drawable.category_artist
                categories += artistsCategory
            }
        }
        // we show the "files" even when no USB connected as a container for the "indexing" pseudo-items
        val filesCategory = CategoryData(ContentHierarchyID(ContentHierarchyType.ROOT_FILES))
        filesCategory.titleID = R.string.browse_tree_category_files
        filesCategory.iconID = R.drawable.category_folder
        categories += filesCategory
        categories.forEach {
            val description = MediaDescriptionCompat.Builder().apply {
                setMediaId(serialize(it.contentHierarchyID))
                setTitle(context.getString(it.titleID))
                setIconUri(
                    Uri.parse(RESOURCE_ROOT_URI + context.resources.getResourceEntryName(it.iconID))
                )
                if (it.titleID == albumCategoryTitle
                    && audioItemLibrary.albumArtStyleSetting == AlbumStyleSetting.GRID
                ) {
                    setExtras(generateExtrasBrowsableGridItems())
                }
            }.build()
            items += MediaItem(description, MediaItem.FLAG_BROWSABLE)
        }
        return items
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        // according to https://source.android.com/devices/automotive/radio#play-intents this must be implemented
        val contentHierarchyIDShuffleAll = ContentHierarchyID(ContentHierarchyType.SHUFFLE_ALL_TRACKS)
        return audioItemLibrary.getAudioItemsStartingFrom(contentHierarchyIDShuffleAll)
    }

}
