/*
SPDX-FileCopyrightText: 2021-2024 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.broadcast

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import de.moleman1024.audiowagon.log.Logger

private const val TAG = "BCRecvMgr"
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
        initHandlers()
    }

    fun initHandlers() {
        if (handlerThread == null) {
            logger.debug(TAG, "Starting HandlerThread")
            handlerThread = HandlerThread("BCRecvMgrThread")
            handlerThread!!.start()
        }
        if (handler == null) {
            logger.debug(TAG, "Setting Handler")
            handler = Handler(handlerThread!!.looper)
        }
    }

    @Synchronized
    fun register(broadcastReceiver: ManagedBroadcastReceiver) {
        initHandlers()
        if (registeredReceivers.contains(broadcastReceiver)) {
            logger.warning(TAG, "Broadcast receiver already in list of registered receivers: $broadcastReceiver")
            return
        }
        // We should only ever have one receiver for each class type in the list
        val existingReceivers = registeredReceivers.filter { it.getType() == broadcastReceiver.getType() }
        if (existingReceivers.isNotEmpty()) {
            logger.warning(TAG, "Will replace broadcast receiver in list for type: ${broadcastReceiver.getType()}")
            existingReceivers.forEach {
                try {
                    logger.debug(TAG, "Will unregister: $it")
                    context.unregisterReceiver(it)
                } catch (exc: IllegalArgumentException) {
                    logger.warning(TAG, "Could not unregister receiver $broadcastReceiver: " + exc.message.toString())
                }
            }
            registeredReceivers.removeIf { it.getType() == broadcastReceiver.getType() }
        }
        logger.debug(TAG, "register(broadcastReceiver=$broadcastReceiver)")
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

    @Synchronized
    fun unregister(broadcastReceiver: ManagedBroadcastReceiver) {
        logger.debug(TAG, "unregister(broadcastReceiver=$broadcastReceiver)")
        try {
            context.unregisterReceiver(broadcastReceiver)
        } catch (exc: IllegalArgumentException) {
            logger.warning(TAG, "Could not unregister receiver $broadcastReceiver: " + exc.message.toString())
        }
        registeredReceivers.remove(broadcastReceiver)
    }

    @Synchronized
    fun shutdown() {
        registeredReceivers.forEach {
            try {
                logger.debug(TAG, "shutdown(): $it")
                context.unregisterReceiver(it)
            } catch (exc: IllegalArgumentException) {
                logger.warning(TAG, "Could not unregister receiver $it: " + exc.message.toString())
            }
        }
        registeredReceivers.clear()
        deinitHandlers()
    }

    fun deinitHandlers() {
        logger.debug(TAG, "deinitHandlers()")
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }

}
