/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.activities

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
import androidx.appcompat.app.AppCompatActivity
import de.moleman1024.audiowagon.ACTION_RESTART_SERVICE
import de.moleman1024.audiowagon.AudioBrowserService
import de.moleman1024.audiowagon.SharedPrefs
import de.moleman1024.audiowagon.filestorage.usb.ACTION_USB_ATTACHED
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
 * This does not work on AAOS in Polestar 2, works fine on Pixel 3 XL with AAOS. I have not seen any app related to
 * https://android.googlesource.com/platform/packages/services/Car/+/refs/tags/android-10.0.0_r40/car-usb-handler/ in
 * the Polestar, maybe that is why ...
 */
@ExperimentalCoroutinesApi
class USBDummyActivity : AppCompatActivity() {
    private val sharedPrefs = SharedPrefs()

    override fun onCreate(savedInstanceState: Bundle?, persistentState: PersistableBundle?) {
        logger.debug(TAG, "onCreate()")
        super.onCreate(savedInstanceState, persistentState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        logger.debug(TAG, "onCreate()")
        super.onCreate(savedInstanceState)
    }

    override fun onStart() {
        logger.debug(TAG, "onStart()")
        super.onStart()
        startForegroundService(Intent(ACTION_RESTART_SERVICE, Uri.EMPTY, this, AudioBrowserService::class.java))
        if (!sharedPrefs.isLegalDisclaimerAgreed(this)) {
            showLegalDisclaimer()
        }
        rebroadcastUSBDeviceAttached()
        // close this activity again immediately so it does not block the GUI
        finish()
    }

    override fun onResume() {
        logger.debug(TAG, "onResume()")
        super.onResume()
    }

    override fun onNewIntent(intent: Intent?) {
        logger.debug(TAG, "onNewIntent(intent=$intent)")
        super.onNewIntent(intent)
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
            val rebroadcastIntent = Intent(ACTION_USB_ATTACHED)
            rebroadcastIntent.putExtra(UsbManager.EXTRA_DEVICE, it)
            sendBroadcast(rebroadcastIntent)
        }
    }

    private fun showLegalDisclaimer() {
        logger.debug(TAG, "Showing legal disclaimer to user")
        val showLegalDisclaimerIntent = Intent(this, LegalDisclaimerActivity::class.java)
        startActivity(showLegalDisclaimerIntent)
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
