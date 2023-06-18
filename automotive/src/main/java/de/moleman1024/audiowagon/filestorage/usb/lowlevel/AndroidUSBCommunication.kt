/*
SPDX-FileCopyrightText: 2023 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb.lowlevel

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

interface AndroidUSBCommunication : USBCommunication {
    val usbManager: UsbManager
    val usbDevice: UsbDevice
}
