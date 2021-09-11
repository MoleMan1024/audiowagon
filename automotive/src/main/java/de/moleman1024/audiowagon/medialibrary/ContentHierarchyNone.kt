/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary

import android.content.Context
import android.support.v4.media.MediaBrowserCompat.MediaItem

class ContentHierarchyNone(
    context: Context,
    audioItemLibrary: AudioItemLibrary
) :
    ContentHierarchyElement(CONTENT_HIERARCHY_NONE, context, audioItemLibrary) {

    override suspend fun getMediaItems(): List<MediaItem> {
        return listOf()
    }

    override suspend fun getAudioItems(): List<AudioItem> {
        // not playable
        return listOf()
    }

}
