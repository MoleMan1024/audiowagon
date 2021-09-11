/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary

import android.content.Context
import android.net.Uri
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.log.Logger
import java.lang.AssertionError

private const val TAG = "CHElement"
private val logger = Logger

const val CONTENT_HIERARCHY_NUM_ITEMS_PER_GROUP = 400
const val NUM_TITLE_CHARS_FOR_GROUP = 16

abstract class ContentHierarchyElement(
    val id: String,
    val context: Context,
    val audioItemLibrary: AudioItemLibrary
) {
    abstract suspend fun getMediaItems(): List<MediaItem>
    abstract suspend fun getAudioItems(): List<AudioItem>

    companion object {
        // the whole content hierarchy ID looks something like this: "aw-32_GB-1921-21909/ALBUM/1"

        fun getStorageID(id: String): String {
            return id.split("/")[0]
        }

        fun getType(id: String): AudioItemType {
            return AudioItemType.valueOf(id.split("/")[1])
        }

        fun getDatabaseID(id: String): Long {
            return id.split("/")[2].toLong()
        }

        fun getDatabaseIDAndExtraID(id: String): Pair<Long, Long> {
            val lastPart = id.split("/")[2]
            return lastPart.split("+").map { it.toLong() }.zipWithNext()[0]
        }

        fun replaceType(id: String, newType: AudioItemType): String {
            val split = id.split("/")
            return listOf(split[0], newType.toString(), split[2]).joinToString(separator = "/")
        }
    }

    fun createGroups(audioItems: List<AudioItem>, audioItemType: AudioItemType): MutableList<MediaItem> {
        logger.debug(TAG, "Too many items of $audioItemType (${audioItems.size}), need to create groups")
        val groups = mutableListOf<MediaItem>()
        audioItems.chunked(CONTENT_HIERARCHY_NUM_ITEMS_PER_GROUP).forEachIndexed { index, items ->
            val firstItemTitle: String
            val lastItemTitle: String
            val iconID: Int
            when (audioItemType) {
                AudioItemType.GROUP_TRACKS -> {
                    firstItemTitle = items[0].title
                    lastItemTitle = items[items.size-1].title
                    iconID = R.drawable.baseline_library_music_24
                }
                AudioItemType.GROUP_ARTISTS -> {
                    firstItemTitle = items[0].artist
                    lastItemTitle= items[items.size-1].artist
                    iconID = R.drawable.baseline_recent_actors_24
                }
                AudioItemType.GROUP_ALBUMS -> {
                    firstItemTitle = items[0].album
                    lastItemTitle = items[items.size-1].album
                    iconID = R.drawable.baseline_workspaces_24
                }
                else -> {
                    throw AssertionError("createGroups() not supported for audio item type: $audioItemType")
                }
            }
            val groupContentHierarchyID = Util.createAudioItemID(
                getStorageID(items[0].id), index.toLong(), audioItemType
            )
            val description = MediaDescriptionCompat.Builder().apply {
                setTitle("${firstItemTitle.take(NUM_TITLE_CHARS_FOR_GROUP)} " +
                        "… ${lastItemTitle.take(NUM_TITLE_CHARS_FOR_GROUP)}")
                setIconUri(Uri.parse(RESOURCE_ROOT_URI + context.resources.getResourceEntryName(iconID)))
                setMediaId(groupContentHierarchyID)
            }.build()
            groups += MediaItem(description, MediaItem.FLAG_BROWSABLE)
        }
        return groups
    }
}
