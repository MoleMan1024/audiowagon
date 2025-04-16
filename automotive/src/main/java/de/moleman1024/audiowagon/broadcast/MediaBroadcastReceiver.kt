/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.broadcast

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.player.AudioPlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi

private const val TAG = "MediaBCRecv"
private val logger = Logger
const val ACTION_PLAY = "de.moleman1024.audiowagon.ACTION_PLAY"
const val ACTION_PAUSE = "de.moleman1024.audiowagon.ACTION_PAUSE"
const val ACTION_NEXT = "de.moleman1024.audiowagon.ACTION_NEXT"
const val ACTION_PREV = "de.moleman1024.audiowagon.ACTION_PREV"

@ExperimentalCoroutinesApi
class MediaBroadcastReceiver : ManagedBroadcastReceiver() {
    var audioPlayer: AudioPlayer? = null

    override fun getIntentFilter(): IntentFilter {
        return IntentFilter().apply {
            addAction(ACTION_PLAY)
            addAction(ACTION_PAUSE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREV)
        }
    }

    override fun getFlags(): Int {
        return getNotExportedFlags()
    }

    override fun getType(): BroadcastReceiverType {
        return BroadcastReceiverType.MEDIA
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) {
            return
        }
        logger.debug(TAG, "onReceive($intent)")
        when (intent.action) {
            ACTION_PLAY -> {
                audioPlayer?.onBroadcastPlay()
            }
            ACTION_PAUSE -> {
                audioPlayer?.onBroadcastPause()
            }
            ACTION_NEXT -> {
                audioPlayer?.onBroadcastNext()
            }
            ACTION_PREV -> {
                audioPlayer?.onBroadcastPrev()
            }
            else -> {
                logger.warning(TAG, "Ignoring unhandled action: ${intent.action}")
            }
        }
    }

    override fun toString(): String {
        return "${MediaBroadcastReceiver::class.simpleName}{type=${getType()}, " +
                "hashCode=${Integer.toHexString(hashCode())}}"
    }

}
