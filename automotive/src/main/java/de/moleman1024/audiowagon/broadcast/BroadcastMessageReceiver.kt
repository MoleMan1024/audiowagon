/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.log.CrashReporting
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.player.AudioPlayer
import kotlinx.coroutines.*

private const val TAG = "BroadcastMsgRecv"
private val logger = Logger
const val ACTION_PLAY = "de.moleman1024.audiowagon.ACTION_PLAY"
const val ACTION_PAUSE = "de.moleman1024.audiowagon.ACTION_PAUSE"
const val ACTION_NEXT = "de.moleman1024.audiowagon.ACTION_NEXT"
const val ACTION_PREV = "de.moleman1024.audiowagon.ACTION_PREV"

@ExperimentalCoroutinesApi
class BroadcastMessageReceiver(
    private val audioPlayer: AudioPlayer,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val crashReporting: CrashReporting
) :
    BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) {
            return
        }
        logger.debug(TAG, "Received broadcast message: $intent")
        when (intent.action) {
            ACTION_PLAY -> launchInScopeSafely { audioPlayer.start() }
            ACTION_PAUSE -> launchInScopeSafely { audioPlayer.pause() }
            ACTION_NEXT -> launchInScopeSafely { audioPlayer.skipNextTrack() }
            ACTION_PREV -> launchInScopeSafely { audioPlayer.skipPreviousTrack() }
            else -> {
                logger.warning(TAG, "Ignoring unhandled action: ${intent.action}")
            }
        }
    }

    private fun launchInScopeSafely(func: suspend (CoroutineScope) -> Unit) {
        Util.launchInScopeSafely(scope, dispatcher, logger, TAG, crashReporting, func)
    }

}
