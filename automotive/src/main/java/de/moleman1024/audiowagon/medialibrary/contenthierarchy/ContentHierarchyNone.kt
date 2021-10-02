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
 * An empty element in the browse tree (used for example when user tries tapping on "no entries" pseudo-item)
 */
class ContentHierarchyNone(
    context: Context,
    audioItemLibrary: AudioItemLibrary
) : ContentHierarchyElement(ContentHierarchyID(ContentHierarchyType.NONE), context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        return listOf()
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        // not playable
        return listOf()
    }

}
