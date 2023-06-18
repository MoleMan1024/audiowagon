/*
SPDX-FileCopyrightText: 2023 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb.lowlevel

import java.io.InputStream

abstract class USBFileInputStream : InputStream() {
    abstract override fun read(): Int
    abstract override fun read(buffer: ByteArray?): Int
    abstract override fun read(buffer: ByteArray?, offset: Int, length: Int): Int
    abstract override fun skip(numBytes: Long): Long
    abstract override fun available(): Int
    abstract override fun close()
}
