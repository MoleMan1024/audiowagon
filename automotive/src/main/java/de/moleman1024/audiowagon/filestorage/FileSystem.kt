/*
SPDX-FileCopyrightText: 2023 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage

import de.moleman1024.audiowagon.filestorage.usb.lowlevel.USBFile

interface FileSystem {
   val chunkSize: Int
   val freeSpace: Long
   val rootDirectory: USBFile
   val volumeLabel: String
}
