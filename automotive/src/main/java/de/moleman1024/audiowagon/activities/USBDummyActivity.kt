/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.activities

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import de.moleman1024.audiowagon.ACTION_START_SERVICE_WITH_USB_DEVICE
import de.moleman1024.audiowagon.AudioBrowserService
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi


private const val TAG = "USBDummyAct"
private val logger = Logger

/**
 * A dummy activity to register USB_DEVICE_ATTACHED events to. This is done to avoid asking the user for permission
 * each time when plugging in the same USB flash drive. When we receive such an event, we start the
 * AudioBrowserService and rebroadcast the event.
 * This activity is invisible to the user in the GUI.
 *
 * See
 * https://www.sdgsystems.com/post/android-usb-permissions
 * https://stackoverflow.com/questions/12388914/usb-device-access-pop-up-suppression
 *
 * This works in AAOS in Polestar 2 with car version 3.4.4 only. Lower versions probably do not integrated the
 * car-usb-handler ( https://android.googlesource.com/platform/packages/services/Car/+/refs/tags/android-10.0.0_r40/car-usb-handler/ ).
 * Works fine on Pixel 3 XL with default AAOS.
 */
@ExperimentalCoroutinesApi
class USBDummyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?, persistentState: PersistableBundle?) {
        logger.debug(TAG, "onCreate()")
        super.onCreate(savedInstanceState, persistentState)
        makeTransparent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        logger.debug(TAG, "onCreate()")
        super.onCreate(savedInstanceState)
        makeTransparent()
    }

    // we don't want this view to be visible to the user nor catch any touch events
    private fun makeTransparent() {
        window.decorView.rootView.visibility = View.GONE
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    }

    override fun onStart() {
        super.onStart()
        logger.debug(TAG, "onStart()")
        makeTransparent()
        // We must not call to shared preferences in here, as this may happen during direct boot where accessing
        // preferences might not be allowed yet (protected storage).
        rebroadcastUSBDeviceAttached()
        // close this activity again immediately so it does not block the GUI
        finish()
    }

    override fun onResume() {
        logger.debug(TAG, "onResume()")
        super.onResume()
        makeTransparent()
    }

    override fun onNewIntent(intent: Intent?) {
        logger.debug(TAG, "onNewIntent(intent=$intent)")
        super.onNewIntent(intent)
        makeTransparent()
    }

    private fun rebroadcastUSBDeviceAttached() {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            // we do not need to re-broadcast DETACHED and other events, they will be caught by the broadcast
            // receiver in USBDeviceConnections
            return
        }
        val usbDevice: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        usbDevice?.let {
            logger.debug(TAG, "Re-broadcasting USB_DEVICE_ATTACHED intent")
            val startServiceIntent = Intent(ACTION_START_SERVICE_WITH_USB_DEVICE, Uri.EMPTY, this,
                AudioBrowserService::class.java)
            startServiceIntent.putExtra(UsbManager.EXTRA_DEVICE, it)
            // See https://developer.android.com/develop/background-work/services/foreground-services#background-start-restrictions
            try {
                startService(startServiceIntent)
            } catch (exc: IllegalStateException) {
                logger.exception(TAG, exc.message.toString(), exc)
                logger.error(TAG, "Could not start service, will try to start foreground service instead")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        startForegroundService(startServiceIntent)
                    } catch (exc: ForegroundServiceStartNotAllowedException) {
                        logger.exception(TAG, exc.message.toString(), exc)
                    }
                } else {
                    startForegroundService(startServiceIntent)
                }
            }
        }
    }

    override fun onStop() {
        logger.debug(TAG, "onStop()")
        super.onStop()
    }

    override fun onDestroy() {
        logger.debug(TAG, "onDestroy()")
        super.onDestroy()
    }
}
