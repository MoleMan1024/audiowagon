/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.moleman1024.audiowagon.AudioBrowserService
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi

private const val TAG = "PowerEvtReceiver"
private val logger = Logger

@ExperimentalCoroutinesApi
class PowerEventReceiver : BroadcastReceiver() {
    var audioBrowserService: AudioBrowserService? = null

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) {
            return
        }
        // ACTION_SHUTDOWN does not seem to appear on Polestar 2, works fine on Pixel 3 XL AAOS.
        // Cannot use CarPowerManager with android.car.permission.CAR_POWER, it is reserved for system signed apps.
        // I have the impression the app is suspended to RAM and is not actually shut down when the car is "shut
        // down"...
        logger.debug(TAG, "Received notification: $intent")
        when (intent.action) {
            Intent.ACTION_SHUTDOWN,
            Intent.ACTION_SCREEN_OFF -> audioBrowserService?.suspend()
            Intent.ACTION_SCREEN_ON -> audioBrowserService?.wakeup()
            // Intent.ACTION_BATTERY_CHANGED was not helpful
            else -> {
                // ignore
            }
        }
    }

}
