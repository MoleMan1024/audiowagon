/*
SPDX-FileCopyrightText: 2023 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb.lowlevel

import java.nio.ByteBuffer

interface USBFile: java.io.Closeable {
   val absolutePath: String
   val isDirectory: Boolean
   val isRoot: Boolean
   var length: Long
   var name: String
   val parent: USBFile?
   val lastModified: Long

   override fun close()
   fun createDirectory(name: String): USBFile
   fun createFile(name: String): USBFile
   fun flush()
   fun listFiles(): Array<USBFile>
   fun read(offset: Long, outBuf: ByteBuffer)
   fun search(path: String): USBFile?
   fun write(offset: Long, inBuf: ByteBuffer)

   fun getOutputStream(): USBFileOutputStream
   fun getInputStream(): USBFileInputStream
}
