package de.moleman1024.audiowagon.filestorage.usb.lowlevel.filesystem

import de.moleman1024.audiowagon.filestorage.usb.lowlevel.driver.BlockDeviceDriver
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.partition.PartitionTableEntry

import java.io.IOException

/**
 * Created by magnusja on 28/02/17.
 */

interface FileSystemCreator {
    fun read(entry: PartitionTableEntry, blockDevice: BlockDeviceDriver): FileSystem?
}
