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

package de.moleman1024.audiowagon.filestorage.usb.lowlevel.driver.scsi

import de.moleman1024.audiowagon.filestorage.usb.lowlevel.PipeException
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.USBCommunication
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.Util
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.driver.BlockDeviceDriver
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.driver.scsi.commands.*
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.driver.scsi.commands.sense.*
import de.moleman1024.audiowagon.log.Logger
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*

private val logger = Logger
private const val OUT_BUFFER_SIZE = 31

/**
 * This class is responsible for handling mass storage devices which follow the
 * SCSI standard. This class communicates with the mass storage device via the
 * different SCSI commands.
 *
 * @author mjahnen, Derpalus
 * @see de.moleman1024.audiowagon.filestorage.usb.lowlevel.driver.scsi.commands
 */
class ScsiBlockDevice(private val usbCommunication: USBCommunication, private val lun: Byte) : BlockDeviceDriver {
    private val outBuffer: ByteBuffer = Util.allocateByteBuffer(OUT_BUFFER_SIZE)
    private val cswBuffer: ByteBuffer = Util.allocateByteBuffer(CommandStatusWrapper.SIZE)

    override var blockSize: Int = 0
    private var lastBlockAddress: Int = 0

    private val writeCommand = ScsiWrite10(lun = lun)
    private val readCommand = ScsiRead10(lun = lun)
    private val csw = CommandStatusWrapper()

    private var cbwTagCounter = 1

    /**
     * The size of the block device, in blocks of [blockSize] bytes,
     *
     * @return The block device size in blocks
     */
    override val blocks: Long = lastBlockAddress.toLong()

    /**
     * Issues a SCSI Inquiry to determine the connected device. After that it is checked if the unit is ready. Logs a
     * warning if the unit is not ready. Finally the capacity of the mass storage device is read.
     *
     * @throws IOException
     * If initialing fails due to an unsupported device or if reading fails.
     *
     * @see ScsiInquiry
     * @see ScsiInquiryResponse
     * @see ScsiTestUnitReady
     * @see ScsiReadCapacity
     * @see ScsiReadCapacityResponse
     */
    override fun init() {
        (0..MAX_INIT_ATTEMPTS).forEach { i ->
            try {
                initAttempt()
                return
            } catch (e: InitRequired) {
                logger.verbose(TAG, e.message ?: "Reinitializing device")
            } catch (e: NotReadyTryAgain) {
                logger.verbose(TAG, e.message ?: "Reinitializing device")
            }
            if (!usbCommunication.isClosed()) {
                Thread.sleep(100)
            } else {
                throw IOException("USBCommunication was closed")
            }
        }
        throw IOException("MAX_INIT_ATTEMPTS exceeded during communication init with USB device, re-attach device")
    }

    private fun initAttempt() {
        logger.debug(TAG, "initAttempt() (instance=$this)")
        val allocationSize = 36
        val inBuffer = Util.allocateByteBuffer(allocationSize)
        val inquiry = ScsiInquiry(allocationSize.toByte(), lun = lun)
        transferCommand(inquiry, inBuffer)
        inBuffer.clear()
        val inquiryResponse = ScsiInquiryResponse.read(inBuffer)
        logger.debug(TAG, "Inquiry response: $inquiryResponse")
        if (inquiryResponse.peripheralQualifier.toInt() != 0 || inquiryResponse.peripheralDeviceType.toInt() != 0) {
            throw IOException(
                "Unsupported PeripheralQualifier=${inquiryResponse.peripheralQualifier.toInt()} " +
                        "or PeripheralDeviceType=${inquiryResponse.peripheralDeviceType.toInt()}"
            )
        }
        val testUnit = ScsiTestUnitReady(lun = lun)
        transferCommandWithoutDataPhase(testUnit)
        val readCapacity = ScsiReadCapacity(lun = lun)
        inBuffer.clear()
        transferCommand(readCapacity, inBuffer)
        inBuffer.clear()
        val readCapacityResponse = ScsiReadCapacityResponse.read(inBuffer)
        blockSize = readCapacityResponse.blockLength
        lastBlockAddress = readCapacityResponse.logicalBlockAddress
        logger.debug(TAG, "Block size: $blockSize")
        logger.debug(TAG, "Last block address: $lastBlockAddress")
    }

    /**
     * Transfers the desired command to the device. If the command has a data
     * phase the parameter `inBuffer` is used to store or read data
     * to resp. from it. The direction of the data phase is determined by
     * [#getDirection()][CommandBlockWrapper]
     *
     * Return value is true if the status of the command status wrapper is
     * successful ( [#getbCswStatus()][CommandStatusWrapper] ).
     *
     * @param command
     * The command which should be transferred.
     * @param inBuffer
     * The buffer used for reading or writing.
     * @throws IOException
     * If something fails.
     */
    private fun transferCommand(command: CommandBlockWrapper, inBuffer: ByteBuffer) {
        val startTimeMS: Long = System.currentTimeMillis()
        var logAttempts = false
        for (i in 0..MAX_RECOVERY_ATTEMPTS) {
            try {
                if (logAttempts) {
                    logger.debug(TAG, "transferOneCommand(command=$command)")
                }
                val result = transferOneCommand(command, inBuffer)
                if (logAttempts) {
                    logger.debug(TAG, "transferOneCommand result=$result")
                }
                val senseWasNotIssued = handleCommandResult(result)
                if (senseWasNotIssued || command.direction == CommandBlockWrapper.Direction.NONE) {
                    // successful w/o need of sending sense command
                    // OR
                    // command has no data phase ie. no need to sent again
                    // and read response into buffer
                    return
                }
                // sense command was sent because of error, sense was successful
                // ie. NO_SENSE, RECOVERED_ERROR, COMPLETED, see
                // sense response impl
                // try again and hope that data phase ie. filling inBuffer works now
            } catch (e: SenseException) {
                logger.warning(TAG, (e.message ?: "SenseException"))
                when (e) {
                    is InitRequired -> init()
                    is NotReadyTryAgain -> {
                        // try again
                    }
                    else -> throw e
                }
            } catch (e: PipeException) {
                logger.warning(TAG, (e.message ?: "PipeException") + ", try bulk storage reset and retry")
                bulkOnlyMassStorageReset()
            } catch (e: IOException) {
                logger.warning(TAG, (e.message ?: "IOException") + ", attempt: $i (instance: $this)")
                logAttempts = true
                val nowMS: Long = System.currentTimeMillis()
                if (nowMS - startTimeMS > 10000) {
                    throw IOException("MAX_RECOVERY_ATTEMPTS exceeded, device timeout")
                }
                if (i >= MAX_RECOVERY_ATTEMPTS) {
                    break
                }
            }
            if (!usbCommunication.isClosed()) {
                Thread.sleep(100)
            } else {
                throw IOException("USBCommunication was closed")
            }
        }
        throw IOException("MAX_RECOVERY_ATTEMPTS exceeded during command transfer to device, re-attach device")
    }

    private fun transferCommandWithoutDataPhase(command: CommandBlockWrapper) {
        require(command.direction == CommandBlockWrapper.Direction.NONE) { "Command has a data phase" }
        transferCommand(command, Util.allocateByteBuffer(0))
    }

    private fun handleCommandResult(status: Int): Boolean {
        return when (status) {
            CommandStatusWrapper.COMMAND_PASSED -> true
            CommandStatusWrapper.COMMAND_FAILED -> {
                try {
                    requestSense()
                } catch (exc: UnitAttention) {
                    // UnitAttention comes normally with some SD card readers when adding new SD
                    // card
                    logger.exception(TAG, "UnitAttention", exc)
                }
                false
            }
            CommandStatusWrapper.PHASE_ERROR -> {
                bulkOnlyMassStorageReset()
                throw IOException("phase error, please reattach device and try again")
            }
            else -> error("CommandStatus wrapper illegal status $status")
        }
    }

    private fun requestSense() {
        val allocationSize = 18
        val inBuffer = Util.allocateByteBuffer(allocationSize)
        val sense = ScsiRequestSense(allocationSize.toByte(), lun = lun)
        when (val status = transferOneCommand(sense, inBuffer)) {
            CommandStatusWrapper.COMMAND_PASSED -> {
                inBuffer.clear()
                val response = ScsiRequestSenseResponse.read(inBuffer)
                response.checkResponseForError()
            }
            CommandStatusWrapper.COMMAND_FAILED -> throw IOException("requesting sense failed")
            CommandStatusWrapper.PHASE_ERROR -> {
                bulkOnlyMassStorageReset()
                throw IOException("phase error, please reattach device and try again")
            }
            else -> error("CommandStatus wrapper illegal status $status")
        }
    }

    // https://www.usb.org/sites/default/files/usbmassbulk_10.pdf
    // Section 5.3.4
    // "For Reset Recovery the host shall issue in the following order:
    //  (a) a Bulk-Only Mass Storage Reset
    //  (b) a Clear Feature HALT to the Bulk-In endpoint
    //  (c) a Clear Feature HALT to the Bulk-Out endpoint"
    private fun bulkOnlyMassStorageReset() {
        logger.warning(TAG, "Sending bulk only mass storage request")
        val bArr = ByteArray(2)
        // REQUEST_BULK_ONLY_MASS_STORAGE_RESET = 255
        // REQUEST_TYPE_BULK_ONLY_MASS_STORAGE_RESET = 33
        val transferred: Int = usbCommunication.controlTransfer(33, 255, 0, usbCommunication.usbInterface.id, bArr, 0)
        if (transferred == -1) {
            throw IOException("Bulk only mass storage reset failed!")
        }
        logger.debug(TAG, "Trying to clear halt on both endpoints")
        usbCommunication.clearHalt(usbCommunication.inEndpoint)
        usbCommunication.clearHalt(usbCommunication.outEndpoint)
    }

    private fun transferOneCommand(command: CommandBlockWrapper, inBuffer: ByteBuffer): Int {
        // TODO: we should probably check for hasArray()
        val outArray = outBuffer.array()
        Arrays.fill(outArray, 0.toByte())
        command.dCbwTag = cbwTagCounter
        cbwTagCounter++
        outBuffer.clear()
        command.serialize(outBuffer)
        outBuffer.clear()
        var numBytesWritten = usbCommunication.bulkOutTransfer(outBuffer)
        if (numBytesWritten != OUT_BUFFER_SIZE) {
            throw IOException(
                "Writing all bytes on command $command failed: written=$numBytesWritten expected=${OUT_BUFFER_SIZE}"
            )
        }
        var transferLength = command.dCbwDataTransferLength
        // Fix https://github.com/magnusja/libaums/issues/298#issuecomment-849216577
        var read = 0
        if (transferLength > 0) {
            if (command.direction == CommandBlockWrapper.Direction.IN) {
                val tempBuffer: ByteBuffer = Util.allocateByteBuffer(transferLength)
                do {
                    read += usbCommunication.bulkInTransfer(tempBuffer)
                    if (command.bCbwDynamicSize) {
                        transferLength = command.dynamicSizeFromPartialResponse(tempBuffer)
                        tempBuffer.limit(transferLength)
                    }
                } while (read < transferLength)
                if (read != transferLength) {
                    throw IOException("Unexpected command size ($read) on response to $command")
                }
                tempBuffer.flip()
                val limit = inBuffer.position() + transferLength
                if (limit < 0 || limit > inBuffer.capacity()) {
                    throw IOException(
                        "Could not set inBuffer limit to: $limit " +
                                "(pos=${inBuffer.position()}, capacity=${inBuffer.capacity()}, " +
                                "transferLength=$transferLength)"
                    )
                }
                inBuffer.limit(limit)
                inBuffer.put(tempBuffer)
            } else {
                numBytesWritten = 0
                do {
                    numBytesWritten += usbCommunication.bulkOutTransfer(inBuffer)
                } while (numBytesWritten < transferLength)
                if (numBytesWritten != transferLength) {
                    throw IOException("Could not write all bytes: $command")
                }
            }
        }
        // expecting CSW now
        cswBuffer.clear()
        read = usbCommunication.bulkInTransfer(cswBuffer)
        if (read != CommandStatusWrapper.SIZE) {
            throw IOException("Unexpected command size while expecting CSW")
        }
        cswBuffer.clear()
        csw.read(cswBuffer)
        if (csw.dCswTag != command.dCbwTag) {
            throw IOException("Wrong CSW tag")
        }
        return csw.bCswStatus.toInt()
    }

    /**
     * This method reads from the device at the specific device offset. The
     * devOffset specifies at which block the reading should begin. That means
     * the devOffset is not in bytes!
     */
    @Synchronized
    override fun read(deviceOffset: Long, buffer: ByteBuffer) {
        require(buffer.remaining() % blockSize == 0) { "buffer.remaining() must be multiple of blockSize" }
        readCommand.init(deviceOffset.toInt(), buffer.remaining(), blockSize)
        transferCommand(readCommand, buffer)
        buffer.position(buffer.limit())
    }

    /**
     * This method writes from the device at the specific device offset. The
     * devOffset specifies at which block the writing should begin. That means
     * the devOffset is not in bytes!
     */
    @Synchronized
    override fun write(deviceOffset: Long, buffer: ByteBuffer) {
        require(buffer.remaining() % blockSize == 0) { "buffer.remaining() must be multiple of blockSize" }
        writeCommand.init(deviceOffset.toInt(), buffer.remaining(), blockSize)
        transferCommand(writeCommand, buffer)
        buffer.position(buffer.limit())
    }

    companion object {
        private const val MAX_RECOVERY_ATTEMPTS = 20
        private const val MAX_INIT_ATTEMPTS = 2
        private val TAG = ScsiBlockDevice::class.java.simpleName
    }
}
