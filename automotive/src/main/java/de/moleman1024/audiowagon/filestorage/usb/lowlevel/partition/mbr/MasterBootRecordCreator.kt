package de.moleman1024.audiowagon.filestorage.usb.lowlevel.partition.mbr

import de.moleman1024.audiowagon.filestorage.usb.lowlevel.Util
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.driver.BlockDeviceDriver
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.partition.PartitionTable
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.partition.PartitionTableFactory
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.partition.mbr.MasterBootRecord
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Created by magnusja on 30/07/17.
 */

class MasterBootRecordCreator : PartitionTableFactory.PartitionTableCreator {
    @Throws(IOException::class)
    override fun read(blockDevice: BlockDeviceDriver): PartitionTable? {
        val buffer = Util.allocateByteBuffer(512.coerceAtLeast(blockDevice.blockSize))
        blockDevice.read(0, buffer)
        return MasterBootRecord.read(buffer)
    }
}
