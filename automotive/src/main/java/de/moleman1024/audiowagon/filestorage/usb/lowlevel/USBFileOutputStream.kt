/*
SPDX-FileCopyrightText: 2023 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb.lowlevel

import java.io.OutputStream

abstract class USBFileOutputStream : OutputStream() {
    abstract override fun write(data: Int)
    abstract override fun write(buffer: ByteArray?)
    abstract override fun write(buffer: ByteArray?, offset: Int, length: Int)
    abstract override fun close()
    abstract override fun flush()
}
