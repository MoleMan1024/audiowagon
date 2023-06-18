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

package de.moleman1024.audiowagon.filestorage.usb.lowlevel.filesystem.fat32

import de.moleman1024.audiowagon.filestorage.usb.lowlevel.Util
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.driver.BlockDeviceDriver
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.filesystem.FileSystem
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.filesystem.UsbFile
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.partition.PartitionTypes
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.locks.ReentrantLock

/**
 * This class represents the FAT32 file system and is responsible for setting
 * the FAT32 file system up and extracting the volume label and the root
 * directory.
 *
 * @author mjahnen
 */
class Fat32FileSystem
/**
 * This method constructs a FAT32 file system for the given block device.
 *
 * There are no further checks that the block device actually represents a valid FAT32 file system. That means it must
 * be ensured that the device actually holds a FAT32 file system in advance!
 *
 * @param blockDevice
 * The block device the FAT32 file system is located.
 * @param first512Bytes
 * First 512 bytes read from block device.
 * @throws IOException
 * If reading from the device fails.
 */
private constructor(blockDevice: BlockDeviceDriver, first512Bytes: ByteBuffer) : FileSystem {
    private val bootSector: Fat32BootSector = Fat32BootSector.read(first512Bytes)
    private val fat: FAT
    private val fsInfoStructure: FsInfoStructure
    override val rootDirectory: FatDirectory
    private val lock = ReentrantLock(true)

    /**
     * Caches UsbFile instances returned by list files method. If we do not do that we will get a new instance
     * when searching for the same file. Depending on what you do with the two different instances they can get out of
     * sync and only the one which is written latest will actually be persisted on disk. This is especially problematic
     * if you create files on different directory instances..
     * See also issue https://github.com/magnusja/libaums/issues/125
     */
    private val fileCache = WeakHashMap<String, UsbFile>()
    private val fileCacheLock = ReentrantLock(true)

    // this is confied to single thread externally
    override val volumeLabel: String
        get() {
            return rootDirectory.volumeLabel.orEmpty()
        }

    override val capacity: Long
        get() = bootSector.totalNumberOfSectors * bootSector.bytesPerSector

    override val occupiedSpace: Long
        get() = capacity - freeSpace

    override val freeSpace: Long
        get() {
            lock.lock()
            try {
                return fsInfoStructure.freeClusterCount * bootSector.bytesPerCluster
            } finally {
                lock.unlock()
            }
        }

    override val chunkSize: Int
        get() = bootSector.bytesPerCluster

    override val type: Int
        get() = PartitionTypes.FAT32

    init {
        fsInfoStructure = FsInfoStructure.read(blockDevice, bootSector.fsInfoStartSector * bootSector.bytesPerSector)
        fat = FAT(blockDevice, bootSector, fsInfoStructure)
        rootDirectory = FatDirectory.readRoot(this, blockDevice, fat, bootSector)
    }

    fun putFileInCache(key: String, value: UsbFile) {
        fileCacheLock.lock()
        try {
            fileCache[key] = value
        } finally {
            fileCacheLock.unlock()
        }
    }

    fun getFileFromCache(key: String): UsbFile? {
        fileCacheLock.lock()
        try {
            if (!fileCache.containsKey(key)) {
                return null
            }
            return fileCache[key]
        } finally {
            fileCacheLock.unlock()
        }
    }

    companion object {
        private val TAG = Fat32FileSystem::class.java.simpleName

        /**
         * This method constructs a FAT32 file system for the given block device.
         * There are no further checks if the block device actually represents a valid FAT32 file system. That means
         * it must be ensured that the device actually holds a FAT32 file system in advance!
         *
         * @param blockDevice
         * The block device the FAT32 file system is located.
         * @throws IOException
         * If reading from the device fails.
         */
        fun read(blockDevice: BlockDeviceDriver): Fat32FileSystem? {
            val buffer = Util.allocateByteBuffer(512)
            blockDevice.read(0, buffer)
            buffer.flip()
            return if (buffer.get(82).toInt().toChar() != 'F' ||
                buffer.get(83).toInt().toChar() != 'A' ||
                buffer.get(84).toInt().toChar() != 'T' ||
                buffer.get(85).toInt().toChar() != '3' ||
                buffer.get(86).toInt().toChar() != '2' ||
                buffer.get(87).toInt().toChar() != ' ' ||
                buffer.get(88).toInt().toChar() != ' ' ||
                buffer.get(89).toInt().toChar() != ' '
            ) {
                null
            } else {
                Fat32FileSystem(blockDevice, buffer)
            }

        }
    }
}
