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
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary
import de.moleman1024.audiowagon.medialibrary.RESOURCE_ROOT_URI
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.lang.AssertionError

private const val TAG = "CHElement"
private val logger = Logger

// This constant is used to limit the amount of items to return when getting media items. Supplying more than a
// certain amount of items will cause BINDER TRANSACTION failures because of too large size in onLoadChildren()
const val CONTENT_HIERARCHY_MAX_NUM_ITEMS = 400
const val NUM_TITLE_CHARS_FOR_GROUP = 16

// TODO: document possible hierarchies
abstract class ContentHierarchyElement(
    val id: ContentHierarchyID,
    val context: Context,
    val audioItemLibrary: AudioItemLibrary
) {
    abstract suspend fun getMediaItems(): List<MediaItem>
    abstract suspend fun getAudioItems(): List<AudioItem>

    companion object {
        fun deserialize(id: String): ContentHierarchyID {
            return Json.decodeFromString(id)
        }

        fun serialize(id: ContentHierarchyID): String {
            return Json.encodeToString(id)
        }
    }

    fun hasTooManyItems(numItems: Int): Boolean {
        return numItems > CONTENT_HIERARCHY_MAX_NUM_ITEMS
    }

    suspend fun createGroups(groupContentHierarchyID: ContentHierarchyID, numItems: Int): MutableList<MediaItem> {
        logger.debug(TAG, "Too many items ($numItems), creating groups ($groupContentHierarchyID)")
        val groups = mutableListOf<MediaItem>()
        var offset = 0
        val repo = audioItemLibrary.getPrimaryRepository() ?: return groups
        val lastGroupIndex = numItems / CONTENT_HIERARCHY_MAX_NUM_ITEMS
        for (groupIndex in 0 .. lastGroupIndex) {
            val offsetRows = if (groupIndex < lastGroupIndex) {
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
                        firstItemInGroup = repo.getTracksLimitOffset(1, offset)
                        lastItemInGroup = repo.getTracksLimitOffset(1, offsetRows)
                    }
                }
                ContentHierarchyType.ALBUM_GROUP -> {
                    if (groupContentHierarchyID.artistID < 0) {
                        firstItemInGroup = repo.getAlbumsLimitOffset(1, offset)
                        lastItemInGroup = repo.getAlbumsLimitOffset(1, offsetRows)
                    } else {
                        firstItemInGroup =
                            repo.getAlbumsForArtistLimitOffset(1, offset, groupContentHierarchyID.artistID)
                        lastItemInGroup =
                            repo.getAlbumsForArtistLimitOffset(1, offsetRows, groupContentHierarchyID.artistID)
                    }
                }
                ContentHierarchyType.ARTIST_GROUP -> {
                    firstItemInGroup = repo.getArtistsLimitOffset(1, offset)
                    lastItemInGroup = repo.getArtistsLimitOffset(1, offsetRows)
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
                    iconID = R.drawable.baseline_workspaces_24
                    groupContentHierarchyID.albumGroupIndex = groupIndex
                }
                ContentHierarchyType.ARTIST_GROUP -> {
                    firstItemTitle = firstItemInGroup[0].artist
                    lastItemTitle = lastItemInGroup[0].artist
                    iconID = R.drawable.baseline_recent_actors_24
                    groupContentHierarchyID.artistGroupIndex = groupIndex
                }
                else -> {
                    throw AssertionError("createGroups() not supported for type: ${groupContentHierarchyID.type}")
                }
            }
            val description = MediaDescriptionCompat.Builder().apply {
                setTitle(
                    "${firstItemTitle.take(NUM_TITLE_CHARS_FOR_GROUP)} " +
                            "… ${lastItemTitle.take(NUM_TITLE_CHARS_FOR_GROUP)}"
                )
                setIconUri(Uri.parse(RESOURCE_ROOT_URI + context.resources.getResourceEntryName(iconID)))
                setMediaId(serialize(groupContentHierarchyID))
            }.build()
            groups += MediaItem(description, MediaItem.FLAG_BROWSABLE)
            offset += CONTENT_HIERARCHY_MAX_NUM_ITEMS
        }
        return groups
    }
}
