/*
SPDX-FileCopyrightText: 2023 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb.lowlevel

import java.nio.ByteBuffer

const val USE_DIRECT_BYTE_BUFFERS = false

class Util {

    companion object {
        fun allocateByteBuffer(capacity: Int): ByteBuffer {
            return if (USE_DIRECT_BYTE_BUFFERS) {
                ByteBuffer.allocateDirect(capacity)
            } else {
                ByteBuffer.allocate(capacity)
            }
        }

        fun byteBufferToHex(buffer: ByteBuffer): String {
            val data = buffer.array().asList().subList(0, buffer.limit())
            return data.joinToString(separator = " ") { "%02x".format(it) }
        }
    }
}
