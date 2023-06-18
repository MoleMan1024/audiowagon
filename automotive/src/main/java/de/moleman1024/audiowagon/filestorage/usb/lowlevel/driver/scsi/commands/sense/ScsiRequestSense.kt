/*
 * (C) Copyright 2014 mjahnen <github@mgns.tech>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package de.moleman1024.audiowagon.filestorage.usb.lowlevel.driver.scsi.commands.sense

import de.moleman1024.audiowagon.filestorage.usb.lowlevel.driver.scsi.commands.CommandBlockWrapper
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.driver.scsi.commands.CommandStatusWrapper
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * This class is used to issue a SCSI request sense when a command has failed.
 *
 * allocationLength MUST be 18
 *
 * @author mjahnen, Derpalus
 * @see [CommandStatusWrapper].getbCswStatus
 */
class ScsiRequestSense(private val allocationLength: Byte, lun: Byte) :
    CommandBlockWrapper(allocationLength.toInt(), Direction.IN, lun, LENGTH, true) {

    override fun serialize(buffer: ByteBuffer) {
        super.serialize(buffer)
        buffer.apply {
            put(OPCODE)
            put(0.toByte())
            put(0.toByte())
            put(0.toByte())
            put(allocationLength)
        }
    }

    override fun dynamicSizeFromPartialResponse(buffer: ByteBuffer): Int {
        buffer.order(ByteOrder.BIG_ENDIAN)
        return buffer.get(SENSE_BYTE_ADDR).toInt() + SENSE_BYTE_ADDR + 1
    }

    companion object {
        private const val OPCODE: Byte = 0x3
        private const val LENGTH: Byte = 0x6
        private const val SENSE_BYTE_ADDR: Int = 0x7
    }

}
