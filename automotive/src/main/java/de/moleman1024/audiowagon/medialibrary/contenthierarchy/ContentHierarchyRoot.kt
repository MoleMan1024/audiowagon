/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary.contenthierarchy

import android.content.Context
import android.net.Uri
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.ViewTabSetting
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
    private val albumCategoryTitle = R.string.browse_tree_category_albums

    data class CategoryData(val contentHierarchyID: ContentHierarchyID) {
        var titleID: Int = -1
        var iconID: Int = -1
        var viewTab: ViewTabSetting = ViewTabSetting.NONE
    }

    override suspend fun getMediaItems(): List<MediaItem> {
        var categories = mutableListOf<CategoryData>()
        val tracksCategory = CategoryData(ContentHierarchyID(ContentHierarchyType.ROOT_TRACKS))
        tracksCategory.titleID = R.string.browse_tree_category_tracks
        // we use a copy of the music note icon here, because of a bug in the GUI where the highlight color is
        // copied to each item with the same icon ID when selected
        tracksCategory.iconID = R.drawable.category_track
        tracksCategory.viewTab = ViewTabSetting.TRACKS
        categories += tracksCategory
        val albumsCategory = CategoryData(ContentHierarchyID(ContentHierarchyType.ROOT_ALBUMS))
        albumsCategory.titleID = albumCategoryTitle
        albumsCategory.iconID = R.drawable.category_album
        albumsCategory.viewTab = ViewTabSetting.ALBUMS
        categories += albumsCategory
        val artistsCategory = CategoryData(ContentHierarchyID(ContentHierarchyType.ROOT_ARTISTS))
        artistsCategory.titleID = R.string.browse_tree_category_artists
        artistsCategory.iconID = R.drawable.category_artist
        artistsCategory.viewTab = ViewTabSetting.ARTISTS
        categories += artistsCategory
        val filesCategory = CategoryData(ContentHierarchyID(ContentHierarchyType.ROOT_FILES))
        filesCategory.titleID = R.string.browse_tree_category_files
        filesCategory.iconID = R.drawable.category_folder
        filesCategory.viewTab = ViewTabSetting.FILES
        categories += filesCategory
        categories = resortCategories(categories)
        return createMediaItemsForCategories(categories)
    }

    // https://github.com/MoleMan1024/audiowagon/issues/124
    private fun resortCategories(categories: List<CategoryData>): MutableList<CategoryData> {
        val resortedViewTabs: MutableList<CategoryData> = mutableListOf()
        audioItemLibrary.viewTabs.forEach { viewTab ->
            if (viewTab != ViewTabSetting.NONE) {
                categories.find { it.viewTab == viewTab }?.let { resortedViewTabs.add(it) }
            } else {
                val emptyCategory = CategoryData(ContentHierarchyID(ContentHierarchyType.NONE))
                resortedViewTabs.add(emptyCategory)
            }
        }
        return resortedViewTabs
    }

    private fun createMediaItemsForCategories(categories: List<CategoryData>): List<MediaItem> {
        val items: MutableList<MediaItem> = mutableListOf()
        categories.forEach {
            val description = MediaDescriptionCompat.Builder().apply {
                setMediaId(serialize(it.contentHierarchyID))
                if (it.titleID >= 0) {
                    setTitle(context.getString(it.titleID))
                }
                if (it.iconID >= 0) {
                    setIconUri(
                        Uri.parse(RESOURCE_ROOT_URI + context.resources.getResourceEntryName(it.iconID))
                    )
                }
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
