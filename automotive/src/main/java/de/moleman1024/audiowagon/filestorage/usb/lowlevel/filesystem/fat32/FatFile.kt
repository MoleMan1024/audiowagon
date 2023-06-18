/*
 * (C) Copyright 2014-2016 mjahnen <github@mgns.tech>
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

import de.moleman1024.audiowagon.filestorage.usb.lowlevel.driver.BlockDeviceDriver
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.filesystem.AbstractUsbFile
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.filesystem.UsbFile
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock

class FatFile
/**
 * Constructs a new file with the given information.
 *
 * @param blockDevice
 * The device where the file system is located.
 * @param fat
 * The FAT used to follow cluster chains.
 * @param bootSector
 * The boot sector of the file system.
 * @param entry
 * The corresponding entry in a FAT directory.
 * @param parent
 * The parent directory of the newly constructed file.
 */
internal constructor(
    private val blockDevice: BlockDeviceDriver,
    private val fat: FAT,
    private val bootSector: Fat32BootSector,
    private val entry: FatLfnDirectoryEntry,
    override var parent: FatDirectory?
) : AbstractUsbFile() {
    private val lock: ReentrantLock = ReentrantLock(true)
    private lateinit var chain: ClusterChain
    private var parentDirNeedsWrite = false

    override val isDirectory: Boolean
        get() = false

    override var name: String
        get() {
            lock.lock()
            try {
                return entry.name
            } finally {
                lock.unlock()
            }
        }
        set(newName) {
            lock.lock()
            try {
                parent!!.renameEntryWithLock(entry, newName)
            } finally {
                lock.unlock()
            }
        }

    override var length: Long
        get() {
            lock.lock()
            try {
                return entry.fileSize
            } finally {
                lock.unlock()
            }
        }
        set(newLength) {
            lock.lock()
            try {
                setFilesize(newLength)
            } finally {
                lock.unlock()
            }
        }

    override val isRoot: Boolean
        get() = false

    /**
     * Initializes the cluster chain to access the contents of the file.
     *
     * @throws IOException
     * If reading from FAT fails.
     */
    // locked externally
    private fun initChain() {
        if (!::chain.isInitialized) {
            chain = ClusterChain(entry.startCluster, blockDevice, fat, bootSector)
        }
    }

    override fun lastModified(): Long {
        lock.lock()
        try {
            return entry.actualEntry.lastModifiedDateTime
        } finally {
            lock.unlock()
        }
    }

    override fun list(): Array<String> {
        throw UnsupportedOperationException("This is a file!")
    }

    override fun listFiles(): Array<UsbFile> {
        throw UnsupportedOperationException("This is a file!")
    }

    override fun read(offset: Long, destination: ByteBuffer) {
        lock.lock()
        try {
            initChain()
            chain.read(offset, destination)
        } finally {
            lock.unlock()
        }
    }

    override fun write(offset: Long, source: ByteBuffer) {
        lock.lock()
        try {
            initChain()
            val length = offset + source.remaining()
            if (length > entry.fileSize) {
                setFilesize(length)
            }
            entry.setLastModifiedTimeToNow()
            chain.write(offset, source)
            parentDirNeedsWrite = true
        } finally {
            lock.unlock()
        }
    }

    // locked externally
    private fun setFilesize(newLength: Long) {
        initChain()
        chain.length = newLength
        entry.fileSize = newLength
    }

    // locked internally
    override fun close() {
        // flush changes to parent directory, if necessary
        flush()
    }

    override fun flush() {
        lock.lock()
        try {
            // We only have to update the parent because we are always writing everything immediately to the device.
            // The parent directory is responsible for updating the FatDirectoryEntry which contains things like the
            // file size and the date time fields
            if (parentDirNeedsWrite) {
                parent!!.writeWithLock()
                parentDirNeedsWrite = false
            }
        } finally {
            lock.unlock()
        }
    }

    override fun createDirectory(name: String): UsbFile {
        throw UnsupportedOperationException("This is a file!")
    }

    override fun createFile(name: String): UsbFile {
        throw UnsupportedOperationException("This is a file!")
    }

}
