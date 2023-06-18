package de.moleman1024.audiowagon.filestorage.usb.lowlevel.partition.fs

import de.moleman1024.audiowagon.filestorage.usb.lowlevel.driver.BlockDeviceDriver
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.filesystem.FileSystem
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.partition.PartitionTable
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.partition.PartitionTableEntry
import de.moleman1024.audiowagon.log.Logger
import java.util.*

private val logger = Logger

/**
 * Represents a dummy partition table. Sometimes devices do not have an MBR or GPT to save memory.
 * https://stackoverflow.com/questions/38004064/is-it-possible-that-small-sd-cards-are-formatted-without-an-mbr
 * Actual File System is then reevaluated in a later stage in
 * [de.moleman1024.audiowagon.filestorage.usb.lowlevel.filesystem.FileSystemFactory].
 */
class FileSystemPartitionTable(blockDevice: BlockDeviceDriver, fs: FileSystem) : PartitionTable {

    internal var entries: MutableList<PartitionTableEntry> = ArrayList()

    override val size: Int
        get() = 0

    override val partitionTableEntries: List<PartitionTableEntry>
        get() = entries

    init {
        logger.debug(TAG, "Found a device without partition table")
        val totalNumberOfSectors = fs.capacity.toInt() / blockDevice.blockSize
        if (fs.capacity % blockDevice.blockSize != 0L) {
            logger.warning(TAG, "fs capacity is not multiple of block size")
        }
        entries.add(PartitionTableEntry(fs.type, 0, totalNumberOfSectors))
    }

    companion object {
        private val TAG = FileSystemPartitionTable::class.java.simpleName
    }
}
