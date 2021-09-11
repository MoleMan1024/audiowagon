/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.player

/**
 *
 * See https://stackoverflow.com/questions/29036015/who-knows-the-android-mediaplayer-states-by-their-int-value-in-logcat
*/
@Suppress("UNUSED_PARAMETER")
enum class AudioPlayerState(value: Int) {
    ERROR(0),
    IDLE(1),
    INITIALIZED(2),
    // PREPARING is only used for prepareAsync()
    PREPARING(4),
    PREPARED(8),
    STARTED(16),
    PAUSED(32),
    STOPPED(64),
    PLAYBACK_COMPLETED(128),
    END(256)
}
