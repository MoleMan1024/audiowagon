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
import de.moleman1024.audiowagon.filestorage.usb.USBMediaDevice
import de.moleman1024.audiowagon.log.Logger

const val ACTION_USB_PERMISSION_CHANGE = "de.moleman1024.audiowagon.authorization.USB_PERMISSION_CHANGE"

private const val TAG = "USBDevPerm"
private val logger = Logger

class USBDevicePermissions(private val context: Context) {

    // TODO: do we even need to track this? Isn't device.hasPermission() quick enough?
    private val usbDeviceToPermissionMap = mutableMapOf<USBMediaDevice, USBPermission>()

    /**
     * Called after the user has granted/denied access to a USB device
     */
    fun onUSBPermissionChanged(intent: Intent, device: USBMediaDevice) {
        val permissionGranted: Boolean = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        val permission = if (permissionGranted) USBPermission.GRANTED else USBPermission.DENIED
        setPermissionForDevice(device, permission)
        logger.debug(TAG, "USB device permission updated to $permission for: ${device.getName()}")
    }

    /**
     * Sends an intent broadcast to show a dialog to user to allow access to the given USB device
     */
    fun requestPermissionForDevice(device: USBMediaDevice) {
        val requestCode = 0
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = Intent(ACTION_USB_PERMISSION_CHANGE)
        val intentBroadcast = PendingIntent.getBroadcast(context, requestCode, intent, flags)
        logger.debug(TAG, "Requesting permission to access device: $device")
        device.requestPermission(intentBroadcast)
    }

    fun getCurrentPermissionForDevice(device: USBMediaDevice): USBPermission {
        var permission: USBPermission = USBPermission.UNKNOWN
        if (device in usbDeviceToPermissionMap) {
            if (usbDeviceToPermissionMap[device] != USBPermission.UNKNOWN) {
                permission = usbDeviceToPermissionMap.getOrDefault(device, USBPermission.UNKNOWN)
            }
        }
        if (permission == USBPermission.UNKNOWN) {
            permission = if (device.hasPermission()) USBPermission.GRANTED else USBPermission.UNKNOWN
        }
        logger.debug(TAG, "USB device ${device.getName()} has permission: $permission")
        return permission
    }

    fun setPermissionForDevice(device: USBMediaDevice, permission: USBPermission) {
        usbDeviceToPermissionMap[device] = permission
    }

    fun removeDevice(device: USBMediaDevice) {
        usbDeviceToPermissionMap.remove(device)
    }

}
