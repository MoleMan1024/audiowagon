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
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.enums.ContentHierarchyType
import de.moleman1024.audiowagon.filestorage.*
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.enums.AlbumStyleSetting
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary
import de.moleman1024.audiowagon.enums.MetadataReadSetting
import de.moleman1024.audiowagon.filestorage.data.AudioFile
import de.moleman1024.audiowagon.filestorage.data.Directory
import de.moleman1024.audiowagon.filestorage.data.PlaylistFile
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

/**
 * The root element that the media item content hierarchy is based on.
 *
 * The hierarchy of the media browser looks like this:
 * - Root elements are: TRACKS, ALBUMS, ARTISTS, FILELIKES (files/directories)
 * - ALBUMS contains TRACKS
 * - ARTIST contain ALBUMS (which contain TRACKS)
 * - optionally there are GROUPS (if number of items in one category exceeds [CONTENT_HIERARCHY_MAX_NUM_ITEMS], e.g.
 *   a "maximum" hierarchy that could be encountered would look like
 *   ROOT_ARTISTS
 *     has ARTIST_GROUPs (each 400 ARTISTS)
 *       has ARTIST
 *         has ALBUM(*)
 *           has TRACK_GROUP (each 400 tracks)
 *             has TRACK
 *
 *  (*) the app does not yet support artists with more than [CONTENT_HIERARCHY_MAX_NUM_ITEMS] albums (unlikely to occur)
 *
 *  [ContentHierarchyID]s represent the [MediaDescriptionCompat.mMediaId] which are serialized as JSON e.g.
 *  {"type":"ARTIST","storage":"18091809000547-6309-579","albArt":168}
 */
@ExperimentalCoroutinesApi
abstract class ContentHierarchyElement(
    val id: ContentHierarchyID, val context: Context, val audioItemLibrary: AudioItemLibrary
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
            return Bundle().apply {
                putInt(
                    MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                    MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
                )
            }
        }

        fun generateExtrasBrowsableCategoryGridItems(): Bundle {
            return Bundle().apply {
                putInt(
                    MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                    MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM
                )
            }
        }

        fun generateExtrasBrowsableCategoryListItems(): Bundle {
            return Bundle().apply {
                putInt(
                    MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                    MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM
                )
            }
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
        val extras: Bundle = if (audioItemLibrary.albumArtStyleSetting == AlbumStyleSetting.GRID) {
            generateExtrasBrowsableCategoryGridItems()
        } else {
            generateExtrasBrowsableCategoryListItems()
        }
        // TODO: partially duplicated code with createGroupsForType
        val numGroups = numItems / CONTENT_HIERARCHY_MAX_NUM_ITEMS
        for (groupIndex in 0..numGroups) {
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
                        if (firstLastItemInGroup.first.id.isBlank() || firstLastItemInGroup.second.id.isBlank()) {
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
                        if (firstLastItemInGroup.first.id.isBlank() || firstLastItemInGroup.second.id.isBlank()) {
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
                    if (firstLastItemInGroup.first.id.isBlank() || firstLastItemInGroup.second.id.isBlank()) {
                        logger.warning(TAG, "No artists in group, fallback to slow method")
                        firstItemInGroup = repo.getAlbumAndCompilationArtistsLimitOffset(1, offset)
                        lastItemInGroup = repo.getAlbumAndCompilationArtistsLimitOffset(1, offsetRows)
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
                    iconID = R.drawable.library_music
                    groupContentHierarchyID.trackGroupIndex = groupIndex
                }
                ContentHierarchyType.ALBUM_GROUP -> {
                    firstItemTitle = firstItemInGroup[0].album
                    lastItemTitle = lastItemInGroup[0].album
                    iconID = R.drawable.burst_mode
                    groupContentHierarchyID.albumGroupIndex = groupIndex
                }
                ContentHierarchyType.ARTIST_GROUP -> {
                    firstItemTitle = firstItemInGroup[0].albumArtist.ifBlank { firstItemInGroup[0].artist }
                    lastItemTitle = lastItemInGroup[0].albumArtist.ifBlank { lastItemInGroup[0].artist }
                    iconID = R.drawable.recent_actors
                    groupContentHierarchyID.artistGroupIndex = groupIndex
                }
                else -> {
                    throw AssertionError("createGroups() not supported for type: ${groupContentHierarchyID.type}")
                }
            }
            val description = MediaDescriptionCompat.Builder().apply {
                setTitle(
                    "${firstItemTitle.take(numTitleCharsPerGroup)} " + "… ${lastItemTitle.take(numTitleCharsPerGroup)}"
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
        directoryContents: List<FileLike>, audioFileStorage: AudioFileStorage
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
            R.string.notif_indexing_text_in_progress_num_items, audioItemLibrary.numFileDirsSeenWhenBuildingLibrary
        )
        val description = MediaDescriptionCompat.Builder().apply {
            setMediaId(serialize(ContentHierarchyID(ContentHierarchyType.NONE)))
            setTitle(context.getString(R.string.notif_indexing_text_in_progress))
            setSubtitle(numItemsFoundText)
            setIconUri(
                Uri.parse(RESOURCE_ROOT_URI + context.resources.getResourceEntryName(R.drawable.directory_sync))
            )
        }.build()
        return MediaItem(description, MediaItem.FLAG_BROWSABLE)
    }

    protected suspend fun createPseudoNoEntriesItem(
        audioFileStorage: AudioFileStorage, sharedPrefs: SharedPrefs
    ): MediaItem {
        var title = context.getString(R.string.browse_tree_no_entries_title)
        var subtitle: String
        subtitle = context.getString(R.string.browse_tree_no_usb_drive)
        var iconID: Int = R.drawable.usb_off
        if (!audioFileStorage.isUpdatingDevices()) {
            val numAttachedPermittedDevices = audioFileStorage.getNumAttachedPermittedDevices()
            if (numAttachedPermittedDevices > 0) {
                if (sharedPrefs.isLegalDisclaimerAgreed(context)) {
                    if (audioFileStorage.areAnyStoragesAvail()) {
                        if (this !is ContentHierarchyRootFiles) {
                            val metadataReadSetting = sharedPrefs.getMetadataReadSettingEnum(context, logger, TAG)
                            if (metadataReadSetting == MetadataReadSetting.MANUALLY || metadataReadSetting == MetadataReadSetting.FILEPATHS_ONLY) {
                                title = context.getString(R.string.browse_tree_metadata_not_yet_indexed_title)
                                subtitle = context.getString(R.string.browse_tree_metadata_not_yet_indexed_desc)
                                iconID = R.drawable.usb
                            } else {
                                subtitle = context.getString(R.string.browse_tree_usb_drive_ejected)
                                iconID = R.drawable.report
                            }
                        } else {
                            // metadata does not matter in "Files" view
                            subtitle = context.getString(R.string.browse_tree_usb_drive_ejected)
                            iconID = R.drawable.report
                        }
                    } else {
                        subtitle = context.getString(R.string.browse_tree_usb_drive_ejected)
                        iconID = R.drawable.report
                    }
                } else {
                    title = context.getString(R.string.browse_tree_need_to_agree_legal_title)
                    subtitle = context.getString(R.string.browse_tree_need_to_agree_legal_desc)
                    iconID = R.drawable.policy
                }
            } else {
                if (audioFileStorage.isAnyDeviceAttached() && !audioFileStorage.isAnyDevicePermitted()) {
                    subtitle = context.getString(R.string.setting_USB_status_connected_no_permission)
                    iconID = R.drawable.lock
                }
            }
        } else {
            title = context.getString(R.string.browse_tree_please_wait)
            subtitle = ""
            iconID = R.drawable.hourglass
        }
        val description = MediaDescriptionCompat.Builder().apply {
            setMediaId(serialize(ContentHierarchyID(ContentHierarchyType.NONE)))
            setTitle(title)
            setSubtitle(subtitle)
            setIconUri(Uri.parse(RESOURCE_ROOT_URI + context.resources.getResourceEntryName(iconID)))
        }.build()
        logger.debug(
            TAG, "Showing pseudo MediaItem 'No entries available': ${description.title} (${description.subtitle})'"
        )
        // Tapping this should do nothing. We use BROWSABLE flag here, when clicked an empty subfolder will open.
        // This is the better alternative than flag PLAYABLE which will open the playback view in front of the browser
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
                -99
            }
        }
    }

    suspend fun getNumArtists(): Int {
        var numArtists = 0
        val repo = audioItemLibrary.getPrimaryRepository() ?: return 0
        numArtists += repo.getNumAlbumAndCompilationArtists()
        return numArtists
    }

}
