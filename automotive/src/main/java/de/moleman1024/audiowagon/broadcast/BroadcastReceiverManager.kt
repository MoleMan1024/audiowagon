/*
SPDX-FileCopyrightText: 2021-2024 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.broadcast

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import de.moleman1024.audiowagon.log.Logger

private const val TAG = "BCRecvWrapper"
private val logger = Logger

/**
 * Creates a HandlerThread to use for all broadcast receivers so they don't block main thread and throw ANRs
 * https://developer.android.com/guide/components/broadcasts#security-and-best-practices
 */
class BroadcastReceiverManager(private val context: Context) {
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private val registeredReceivers: MutableSet<ManagedBroadcastReceiver> = mutableSetOf()

    init {
        handlerThread = HandlerThread("BCRecvMgrThread")
        handlerThread!!.start()
        handler = Handler(handlerThread!!.looper)
    }

    fun register(broadcastReceiver: ManagedBroadcastReceiver) {
        if (registeredReceivers.contains(broadcastReceiver)) {
            logger.warning(TAG, "Broadcast receiver already in list of registered receivers: $broadcastReceiver")
            return
        }
        try {
            context.registerReceiver(
                broadcastReceiver, broadcastReceiver.getIntentFilter(), null, handler,
                broadcastReceiver.getFlags()
            )
            registeredReceivers.add(broadcastReceiver)
        } catch (exc: IllegalArgumentException) {
            logger.exception(TAG, "Receiver already registered: $broadcastReceiver", exc)
        }
    }

    fun unregisterAll() {
        registeredReceivers.forEach {
            try {
                context.unregisterReceiver(it)
            } catch (exc: IllegalArgumentException) {
                logger.warning(TAG, "Could not unregister receiver $it: " + exc.message.toString())
            }
        }
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }

}
