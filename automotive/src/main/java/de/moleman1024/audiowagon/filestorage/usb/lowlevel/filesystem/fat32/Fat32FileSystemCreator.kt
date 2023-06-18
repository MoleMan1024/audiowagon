package de.moleman1024.audiowagon.filestorage.usb.lowlevel.filesystem.fat32

import de.moleman1024.audiowagon.filestorage.usb.lowlevel.driver.BlockDeviceDriver
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.filesystem.FileSystem
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.filesystem.FileSystemCreator
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.partition.PartitionTableEntry
import java.io.IOException

/**
 * Created by magnusja on 28/02/17.
 */

class Fat32FileSystemCreator : FileSystemCreator {

    override fun read(entry: PartitionTableEntry, blockDevice: BlockDeviceDriver): FileSystem? {
        return Fat32FileSystem.read(blockDevice)
    }
}
