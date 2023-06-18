/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb

import android.app.PendingIntent
import android.content.Context
import de.moleman1024.audiowagon.filestorage.FileSystem
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.USBDeviceConfig
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.USBInterface

interface USBDevice {
    var configurationCount: Int
    var deviceClass: Int
    var deviceName: String
    var interfaceCount: Int
    var manufacturerName: String?
    var productId: Int
    var productName: String?
    var vendorId: Int
    var serialNumber: String?

    fun requestPermission(intent: PendingIntent)
    fun hasPermission(): Boolean
    fun getConfiguration(configIndex: Int): USBDeviceConfig
    fun getInterface(interfaceIndex: Int): USBInterface
    fun initFilesystem(context: Context): FileSystem?
    fun close()
    fun isCompatible(): Boolean
}
