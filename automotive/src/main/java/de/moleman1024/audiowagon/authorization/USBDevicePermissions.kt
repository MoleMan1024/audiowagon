/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.authorization

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import de.moleman1024.audiowagon.enums.USBPermission
import de.moleman1024.audiowagon.filestorage.usb.USBMediaDevice
import de.moleman1024.audiowagon.log.Logger

const val ACTION_USB_PERMISSION_CHANGE = "de.moleman1024.audiowagon.authorization.USB_PERMISSION_CHANGE"

private const val TAG = "USBDevPerm"
private val logger = Logger

class USBDevicePermissions(private val context: Context) {

    init {
        Logger.debug(TAG, "init()")
    }

    /**
     * Called after the user has granted/denied access to a USB device
     */
    fun onUSBPermissionChanged(intent: Intent, device: USBMediaDevice) {
        val permissionGranted: Boolean = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        val permission = if (permissionGranted) USBPermission.GRANTED else USBPermission.DENIED
        logger.debug(TAG, "USB device permission changed to $permission for: ${device.getName()}")
    }

    /**
     * Sends an intent broadcast to show a dialog to user to allow access to the given USB device
     */
    fun requestPermissionForDevice(device: USBMediaDevice) {
        val requestCode = 0
        // The intent must be mutable so the USB device can be added by Android as extra.
        // For Android 14 we must set the package also for security reasons
        // https://developer.android.com/about/versions/14/behavior-changes-14#safer-intents
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = Intent(ACTION_USB_PERMISSION_CHANGE)
        intent.setPackage("de.moleman1024.audiowagon")
        val intentBroadcast = PendingIntent.getBroadcast(context, requestCode, intent, flags)
        logger.debug(TAG, "Requesting permission to access device: $device")
        device.requestPermission(intentBroadcast)
    }

    fun getCurrentPermissionForDevice(device: USBMediaDevice): USBPermission {
        val permission: USBPermission = if (device.hasPermission()) USBPermission.GRANTED else USBPermission.UNKNOWN
        logger.debug(TAG, "USB device ${device.getName()} has permission: $permission")
        return permission
    }

}
