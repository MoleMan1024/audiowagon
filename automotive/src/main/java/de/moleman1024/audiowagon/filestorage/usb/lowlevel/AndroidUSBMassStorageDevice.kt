/*
SPDX-FileCopyrightText: 2023 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb.lowlevel

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.driver.BlockDeviceDriver
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.driver.BlockDeviceDriverFactory
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.driver.scsi.commands.sense.MediaNotInserted
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.partition.Partition
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.partition.PartitionTable
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.partition.PartitionTableFactory
import de.moleman1024.audiowagon.log.Logger
import java.io.IOException

private const val TAG = "AndroidUSBMassStg"
private val logger = Logger

class AndroidUSBMassStorageDevice(
    private val usbManager: UsbManager,
    val usbDevice: UsbDevice,
    private val usbInterface: USBInterface,
    private val inEndpoint: USBEndpoint,
    private val outEndpoint: USBEndpoint
) : USBMassStorageDevice {
    private lateinit var usbCommunication: USBCommunication
    override var partitions: List<Partition> = mutableListOf()

    override fun init() {
        setupDevice()
    }

    /**
     * Sets up the device. Claims interface and initiates the device connection.
     * Initializes the [.blockDevice] and reads the partitions.
     *
     * @throws IOException
     * If reading from the physical device fails.
     */
    private fun setupDevice() {
        // TODO: make implementation selectable
        usbCommunication = JavaAndroidUSBCommunication(usbManager, usbDevice, usbInterface, inEndpoint, outEndpoint)
        logger.debug(TAG, "setupDevice()")
        usbCommunication.initConnection()
        val maxLun = ByteArray(1)
        usbCommunication.controlTransfer(161, 254, 0, usbInterface.id, maxLun, 1)
        logger.verbose(TAG, "MAX LUN ${maxLun[0].toInt()}")
        partitions = (0..maxLun[0]).map { lun ->
            BlockDeviceDriverFactory.createBlockDevice(usbCommunication, lun = lun.toByte())
        }.mapNotNull { blockDevice ->
            try {
                blockDevice.init()
            } catch (exc: MediaNotInserted) {
                // This LUN does not have media inserted. Ignore it.
                return@mapNotNull null
            } catch (exc: Exception) {
                throw exc
            }
            val partitionTable = PartitionTableFactory.createPartitionTable(blockDevice)
            initPartitions(partitionTable, blockDevice)
        }.flatten()
    }

    /**
     * Fills [.partitions] with the information received by the [.partitionTable].
     *
     * @throws IOException
     * If reading from the [.blockDevice] fails.
     */
    private fun initPartitions(partitionTable: PartitionTable, blockDevice: BlockDeviceDriver) =
        partitionTable.partitionTableEntries.mapNotNull {
            Partition.createPartition(it, blockDevice)
        }

    /**
     * Releases the [android.hardware.usb.UsbInterface] and closes the [android.hardware.usb.UsbDeviceConnection].
     * After calling this method no further communication is possible. That means you can not read or write from or
     * to the partitions returned by [.getPartitions].
     */
    override fun close() {
        Logger.debug(TAG, "close()")
        usbCommunication.close()
    }

    override fun reset() {
        usbCommunication.clearHalt(inEndpoint)
        usbCommunication.clearHalt(outEndpoint)
        usbCommunication.reset()
    }

}
