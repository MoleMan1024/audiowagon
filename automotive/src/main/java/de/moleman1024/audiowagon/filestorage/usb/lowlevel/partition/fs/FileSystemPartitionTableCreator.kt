package de.moleman1024.audiowagon.filestorage.usb.lowlevel.partition.fs

import de.moleman1024.audiowagon.filestorage.usb.lowlevel.driver.BlockDeviceDriver
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.driver.ByteBlockDevice
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.filesystem.FileSystemFactory
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.partition.PartitionTable
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.partition.PartitionTableEntry
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.partition.PartitionTableFactory

import java.io.IOException

/**
 * Created by magnusja on 30/07/17.
 */

class FileSystemPartitionTableCreator : PartitionTableFactory.PartitionTableCreator {

    override fun read(blockDevice: BlockDeviceDriver): PartitionTable? {
        return try {
            FileSystemPartitionTable(
                blockDevice,
                FileSystemFactory.createFileSystem(PartitionTableEntry(0, 0, 0), ByteBlockDevice(blockDevice))
            )
        } catch (e: FileSystemFactory.UnsupportedFileSystemException) {
            null
        }

    }
}
