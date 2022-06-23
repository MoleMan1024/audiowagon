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
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.filestorage.*
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.AlbumStyleSetting
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary
import de.moleman1024.audiowagon.medialibrary.RESOURCE_ROOT_URI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.floor


private const val TAG = "CHElement"
private val logger = Logger

// This constant is used to limit the amount of items to return when getting media items. Supplying more than a
// certain amount of items will cause BINDER TRANSACTION failures because of too large size in onLoadChildren()
const val CONTENT_HIERARCHY_MAX_NUM_ITEMS = 400

// This constant limits the number of characters when creating group titles. For example:
// A group starting at track "Another One Bites The Dust" until "Bullet With Butterfly Wings" will show as
// "Another One Bite … Bullet With Butt"
const val DEFAULT_NUM_TITLE_CHARS_FOR_GROUP = 24

// TODO: document possible hierarchies
@ExperimentalCoroutinesApi
abstract class ContentHierarchyElement(
    val id: ContentHierarchyID,
    val context: Context,
    val audioItemLibrary: AudioItemLibrary
) {
    abstract suspend fun getMediaItems(): List<MediaItem>
    abstract suspend fun getAudioItems(): List<AudioItem>

    companion object {
        var numTitleCharsPerGroup: Int = -1

        fun deserialize(id: String): ContentHierarchyID {
            return Json.decodeFromString(id)
        }

        fun serialize(id: ContentHierarchyID): String {
            return Json.encodeToString(id)
        }

        fun generateExtrasBrowsableGridItems(): Bundle {
            val extras = Bundle()
            extras.putInt(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
            )
            return extras
        }
    }

    fun hasTooManyItems(numItems: Int): Boolean {
        return numItems > CONTENT_HIERARCHY_MAX_NUM_ITEMS
    }

    fun setNumTitleCharsPerGroupBasedOnScreenWidth() {
        if (numTitleCharsPerGroup > 0) {
            return
        }
        val maxCharsForScreenWidth = Util.getMaxCharsForScreenWidth(context)
        if (maxCharsForScreenWidth < 0) {
            numTitleCharsPerGroup = DEFAULT_NUM_TITLE_CHARS_FOR_GROUP
        } else {
            numTitleCharsPerGroup = floor((maxCharsForScreenWidth - 3) / 2.0).toInt()
            if (numTitleCharsPerGroup < DEFAULT_NUM_TITLE_CHARS_FOR_GROUP) {
                numTitleCharsPerGroup = DEFAULT_NUM_TITLE_CHARS_FOR_GROUP
            }
        }
        logger.debug(TAG, "numTitleCharsPerGroup=$numTitleCharsPerGroup")
    }

    suspend fun createGroups(groupContentHierarchyID: ContentHierarchyID, numItems: Int): MutableList<MediaItem> {
        logger.debug(TAG, "Too many items ($numItems), creating groups ($groupContentHierarchyID)")
        if (numTitleCharsPerGroup < 0) {
            setNumTitleCharsPerGroupBasedOnScreenWidth()
        }
        val groups = mutableListOf<MediaItem>()
        var offset = 0
        val repo = audioItemLibrary.getPrimaryRepository() ?: return groups
        var extras: Bundle? = null
        if (audioItemLibrary.albumArtStyleSetting == AlbumStyleSetting.GRID) {
            extras = generateExtrasBrowsableGridItems()
        }
        // TODO: partially duplicated code with createGroupsForType
        val numGroups = numItems / CONTENT_HIERARCHY_MAX_NUM_ITEMS
        for (groupIndex in 0 .. numGroups) {
            val offsetRows = if (groupIndex < numGroups) {
                offset + CONTENT_HIERARCHY_MAX_NUM_ITEMS - 1
            } else {
                offset + (numItems % CONTENT_HIERARCHY_MAX_NUM_ITEMS) - 1
            }
            var firstItemInGroup: List<AudioItem>
            var lastItemInGroup: List<AudioItem>
            when (groupContentHierarchyID.type) {
                ContentHierarchyType.TRACK_GROUP -> {
                    if (groupContentHierarchyID.artistID >= 0 && groupContentHierarchyID.albumID >= 0) {
                        firstItemInGroup = repo.getTracksForArtistAlbumLimitOffset(
                            1, offset, groupContentHierarchyID.artistID, groupContentHierarchyID.albumID
                        )
                        lastItemInGroup = repo.getTracksForArtistAlbumLimitOffset(
                            1, offsetRows, groupContentHierarchyID.artistID, groupContentHierarchyID.albumID
                        )
                    } else if (groupContentHierarchyID.albumID >= 0) {
                        firstItemInGroup = repo.getTracksForAlbumLimitOffset(1, offset, groupContentHierarchyID.albumID)
                        lastItemInGroup =
                            repo.getTracksForAlbumLimitOffset(1, offsetRows, groupContentHierarchyID.albumID)
                    } else if (groupContentHierarchyID.artistID >= 0) {
                        firstItemInGroup =
                            repo.getTracksForArtistLimitOffset(1, offset, groupContentHierarchyID.artistID)
                        lastItemInGroup =
                            repo.getTracksForArtistLimitOffset(1, offsetRows, groupContentHierarchyID.artistID)
                    } else {
                        val firstLastItemInGroup = repo.getTrackGroup(groupIndex)
                        firstItemInGroup = listOf(firstLastItemInGroup.first)
                        lastItemInGroup = listOf(firstLastItemInGroup.second)
                        if (firstLastItemInGroup.first.uri == Uri.EMPTY
                            || firstLastItemInGroup.second.uri == Uri.EMPTY) {
                            logger.warning(TAG, "No tracks in group, fallback to slow method")
                            firstItemInGroup = repo.getTracksLimitOffset(1, offset)
                            lastItemInGroup = repo.getTracksLimitOffset(1, offsetRows)
                        }
                    }
                }
                ContentHierarchyType.ALBUM_GROUP -> {
                    if (groupContentHierarchyID.albumArtistID < 0) {
                        val firstLastItemInGroup = repo.getAlbumGroup(groupIndex)
                        firstItemInGroup = listOf(firstLastItemInGroup.first)
                        lastItemInGroup = listOf(firstLastItemInGroup.second)
                        if (firstLastItemInGroup.first.uri == Uri.EMPTY
                            || firstLastItemInGroup.second.uri== Uri.EMPTY) {
                            logger.warning(TAG, "No albums in group, fallback to slow method")
                            firstItemInGroup = repo.getAlbumsLimitOffset(1, offset)
                            lastItemInGroup = repo.getAlbumsLimitOffset(1, offsetRows)
                        }
                    } else {
                        firstItemInGroup =
                            repo.getAlbumsForArtistLimitOffset(1, offset, groupContentHierarchyID.albumArtistID)
                        lastItemInGroup =
                            repo.getAlbumsForArtistLimitOffset(1, offsetRows, groupContentHierarchyID.albumArtistID)
                    }
                }
                ContentHierarchyType.ARTIST_GROUP -> {
                    val firstLastItemInGroup = repo.getArtistGroup(groupIndex)
                    firstItemInGroup = listOf(firstLastItemInGroup.first)
                    lastItemInGroup = listOf(firstLastItemInGroup.second)
                    if (firstLastItemInGroup.first.uri == Uri.EMPTY
                        || firstLastItemInGroup.second.uri== Uri.EMPTY) {
                        logger.warning(TAG, "No artists in group, fallback to slow method")
                        firstItemInGroup = repo.getArtistsLimitOffset(1, offset)
                        lastItemInGroup = repo.getArtistsLimitOffset(1, offsetRows)
                    }
                }
                else -> throw AssertionError("createGroups() not supported for type: ${groupContentHierarchyID.type}")
            }
            if (firstItemInGroup.isEmpty()) {
                break
            }
            if (lastItemInGroup.isEmpty()) {
                break
            }
            val firstItemTitle: String
            val lastItemTitle: String
            val iconID: Int
            when (groupContentHierarchyID.type) {
                ContentHierarchyType.TRACK_GROUP -> {
                    firstItemTitle = firstItemInGroup[0].title
                    lastItemTitle = lastItemInGroup[0].title
                    iconID = R.drawable.baseline_library_music_24
                    groupContentHierarchyID.trackGroupIndex = groupIndex
                }
                ContentHierarchyType.ALBUM_GROUP -> {
                    firstItemTitle = firstItemInGroup[0].album
                    lastItemTitle = lastItemInGroup[0].album
                    iconID = R.drawable.baseline_burst_mode_24
                    groupContentHierarchyID.albumGroupIndex = groupIndex
                }
                ContentHierarchyType.ARTIST_GROUP -> {
                    firstItemTitle = firstItemInGroup[0].albumArtist.ifBlank { firstItemInGroup[0].artist }
                    lastItemTitle = lastItemInGroup[0].albumArtist.ifBlank { lastItemInGroup[0].artist }
                    iconID = R.drawable.baseline_recent_actors_24
                    groupContentHierarchyID.artistGroupIndex = groupIndex
                }
                else -> {
                    throw AssertionError("createGroups() not supported for type: ${groupContentHierarchyID.type}")
                }
            }
            val description = MediaDescriptionCompat.Builder().apply {
                setTitle(
                    "${firstItemTitle.take(numTitleCharsPerGroup)} " +
                            "… ${lastItemTitle.take(numTitleCharsPerGroup)}"
                )
                setIconUri(Uri.parse(RESOURCE_ROOT_URI + context.resources.getResourceEntryName(iconID)))
                setMediaId(serialize(groupContentHierarchyID))
                if (groupContentHierarchyID.type == ContentHierarchyType.ALBUM_GROUP) {
                    setExtras(extras)
                }
            }.build()
            groups += MediaItem(description, MediaItem.FLAG_BROWSABLE)
            offset += CONTENT_HIERARCHY_MAX_NUM_ITEMS
        }
        return groups
    }

    fun createFileLikeMediaItemsForDir(
        directoryContents: List<FileLike>,
        audioFileStorage: AudioFileStorage
    ): MutableList<MediaItem> {
        val items = mutableListOf<MediaItem>()
        for (fileOrDir in directoryContents) {
            items += when (fileOrDir) {
                is Directory -> {
                    val description = audioFileStorage.createDirectoryDescription(fileOrDir)
                    MediaItem(description, MediaItem.FLAG_BROWSABLE)
                }
                is AudioFile -> {
                    val description = audioFileStorage.createAudioFileDescription(fileOrDir)
                    MediaItem(description, MediaItem.FLAG_PLAYABLE)
                }
                is PlaylistFile -> {
                    val description = audioFileStorage.createPlaylistFileDescription(fileOrDir)
                    MediaItem(description, MediaItem.FLAG_PLAYABLE)
                }
                else -> {
                    throw AssertionError("Invalid type: $fileOrDir")
                }
            }
        }
        return items
    }

    protected fun createPseudoFoundXItems(): MediaItem {
        logger.debug(TAG, "Showing pseudo MediaItem 'Found <num> items ...'")
        val numItemsFoundText = context.getString(
            R.string.notif_indexing_text_in_progress_num_items,
            audioItemLibrary.numFilesSeenWhenBuildingLibrary
        )
        val description = MediaDescriptionCompat.Builder().apply {
            setMediaId(serialize(ContentHierarchyID(ContentHierarchyType.NONE)))
            setTitle(context.getString(R.string.notif_indexing_text_in_progress))
            setSubtitle(numItemsFoundText)
            setIconUri(
                Uri.parse(RESOURCE_ROOT_URI + context.resources.getResourceEntryName(R.drawable.baseline_sync_24))
            )
        }.build()
        return MediaItem(description, MediaItem.FLAG_BROWSABLE)
    }

    protected fun pickArtistOrAlbumArtistID(): Long {
        return when {
            id.artistID >= DATABASE_ID_UNKNOWN -> {
                id.artistID
            }
            id.albumArtistID >= DATABASE_ID_UNKNOWN -> {
                id.albumArtistID
            }
            else -> {
                DATABASE_ID_UNKNOWN
            }
        }
    }

}
