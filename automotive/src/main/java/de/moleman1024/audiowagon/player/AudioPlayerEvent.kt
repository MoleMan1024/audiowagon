/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.player

data class AudioPlayerEvent(val state: AudioPlayerState) {
    var errorMsg: String = ""
    var errorCode: Int = -1
}
