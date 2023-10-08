/*
SPDX-FileCopyrightText: 2023 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb.lowlevel

import de.moleman1024.audiowagon.filestorage.usb.lowlevel.partition.Partition

interface USBMassStorageDevice {
    var partitions: List<Partition>

    fun init()
    fun close()
    fun reset()
}
