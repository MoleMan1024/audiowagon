/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.player.data

import android.support.v4.media.session.MediaSessionCompat

data class PlaybackQueueChange(
    val currentItem: MediaSessionCompat.QueueItem?,
    val items: List<MediaSessionCompat.QueueItem>
) {

    override fun toString(): String {
        return "PlaybackQueueChange(currentItem=$currentItem, num items=${items.size})"
    }
}
