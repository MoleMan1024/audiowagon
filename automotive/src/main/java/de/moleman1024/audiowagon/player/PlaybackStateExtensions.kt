/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.player

import android.support.v4.media.session.PlaybackStateCompat

inline val PlaybackStateCompat.isPlaying
    get() = (state == PlaybackStateCompat.STATE_BUFFERING) ||
            (state == PlaybackStateCompat.STATE_PLAYING)

inline val PlaybackStateCompat.isPaused
    get() = state == PlaybackStateCompat.STATE_PAUSED

inline val PlaybackStateCompat.isStopped
    get() = state == PlaybackStateCompat.STATE_STOPPED
