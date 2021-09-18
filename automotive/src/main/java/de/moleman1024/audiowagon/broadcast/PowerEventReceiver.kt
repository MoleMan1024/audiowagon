/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Bundle
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
        // TODO: ACTION_SHUTDOWN does not work on Polestar 2, works fine on Pixel 3 XL AAOS.
        //  Cannot use android.car.permission.CAR_POWER, it is reserved for system signed apps.
        //  These log lines never appear in logfile on USB stick in car, possibly USB_DEVICE_DETACHED arrives before
        //  ACTION_SHUTDOWN.
        logger.debug(TAG, "Received notification: $intent")
        when (intent.action) {
            Intent.ACTION_SHUTDOWN -> audioBrowserService?.shutdown()
            Intent.ACTION_BATTERY_CHANGED -> {
                // on Pixel 3 XL this also arrives "randomly" sometimes, does not seem suitable for detecting shutdown
                val status: Int = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                logger.debug(TAG, "battery extras status: $status")
            }
            else -> {
                // ignore
            }
        }
    }

}
