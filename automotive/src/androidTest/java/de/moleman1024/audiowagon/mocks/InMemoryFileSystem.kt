/*
SPDX-FileCopyrightText: 2021-2022 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.mocks

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import de.moleman1024.audiowagon.filestorage.FileSystem
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.USBFile
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.USBFileInputStream
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.USBFileOutputStream
import de.moleman1024.audiowagon.log.Logger
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.*

private const val TAG = "InMemoryFS"

class InMemoryFileSystem : FileSystem {
    private val defaultMaxSizeBytes = Configuration.Builder.DEFAULT_MAX_SIZE
    private val defaultChunkSizeBytes = Configuration.Builder.DEFAULT_BLOCK_SIZE
    override val chunkSize: Int
        get() = defaultChunkSizeBytes
    override val freeSpace: Long
        // TODO
        get() = defaultMaxSizeBytes
    override val rootDirectory: USBFile
        get() = getRoot()
    override val volumeLabel: String
        get() = "InMemoryFSForTests"
    private val configBuilder =
        Configuration.unix().toBuilder().setMaxSize(defaultMaxSizeBytes).setBlockSize(defaultChunkSizeBytes)
    private lateinit var filesystem: java.nio.file.FileSystem

    fun init() {
        if (this::filesystem.isInitialized) {
            Logger.info(TAG, "Filesystem already initialized")
            return
        }
        Logger.info(TAG, "init()")
        filesystem = Jimfs.newFileSystem(configBuilder.build())
    }

    fun close() {
        Logger.info(TAG, "close()")
        filesystem.close()
    }

    fun writeTextToFile(toFilePath: String, text: String) {
        Logger.debug(TAG, "writeText(toFilePath=$toFilePath)")
        val outFilePath = filesystem.getPath(toFilePath)
        createDirectoriesForFile(outFilePath)
        outFilePath.writeText(text)
    }

    fun copyToFile(inputStream: InputStream, toFilePath: String) {
        Logger.debug(TAG, "copyToFile(toFilePath=$toFilePath)")
        val outFilePath = filesystem.getPath(toFilePath)
        createDirectoriesForFile(outFilePath)
        val outStream = Files.newOutputStream(outFilePath, StandardOpenOption.CREATE)
        val buffer = ByteArray(1024)
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) {
            outStream.write(buffer, 0, read)
        }
        outStream.close()
    }

    private fun createDirectoriesForFile(outFilePath: Path) {
        val parentDir = outFilePath.parent
        createDirectories(parentDir.absolutePathString())
    }

    private fun getRoot(): USBFile {
        return object : USBFile {
            override val absolutePath: String
                get() = "/"
            override val isDirectory: Boolean
                get() = true
            override val isRoot: Boolean
                get() = true
            override var length: Long
                get() = 0
                set(value) {}
            override var name: String
                get() = "rootDir"
                set(value) {}
            override val parent: USBFile?
                get() = null
            override val lastModified: Long
                get() = 0L

            override fun close() {
                // no op
            }

            override fun createDirectory(name: String): USBFile {
                TODO()
            }

            override fun createFile(name: String): USBFile {
                TODO()
            }

            override fun flush() {
                // no op
            }

            override fun listFiles(): Array<USBFile> {
                Logger.debug(TAG, "listFiles(path=/)")
                val usbFiles = mutableListOf<USBFile>()
                filesystem.rootDirectories.forEach { rootDir ->
                    Files.list(rootDir).forEach {
                        // ignore "work" JimFS internal directory(?)
                        if (it.name != "work") {
                            Logger.debug(TAG, it.toString())
                            usbFiles.add(convertPathToUsbFile(it))
                        }
                    }
                }
                return usbFiles.toTypedArray()
            }

            override fun read(offset: Long, outBuf: ByteBuffer) {
                // no op
            }

            override fun search(path: String): USBFile? {
                return convertPathToUsbFile(filesystem.getPath(path))
            }

            override fun write(offset: Long, inBuf: ByteBuffer) {
                // no op
            }

            override fun getOutputStream(): USBFileOutputStream {
                TODO("Not yet implemented")
            }

            override fun getInputStream(): USBFileInputStream {
                TODO("Not yet implemented")
            }
        }
    }

    private fun convertPathToUsbFile(path: Path): USBFile {
        return object : USBFile {
            var byteChannel: SeekableByteChannel? = null

            override val absolutePath: String
                get() = path.absolutePathString()
            override val isDirectory: Boolean
                get() = path.isDirectory()
            override val isRoot: Boolean
                get() = path == path.root
            override var length: Long
                get() = path.fileSize()
                set(value) {
                    TODO()
                }
            override var name: String
                get() = path.name
                set(value) {
                    TODO()
                }
            override val parent: USBFile
                get() = convertPathToUsbFile(path.parent)
            override val lastModified: Long
                get() = 0L

            override fun close() {
                Logger.debug(TAG, "close(path=$path)")
                byteChannel?.close()
                byteChannel = null
            }

            override fun createDirectory(name: String): USBFile {
                TODO()
            }

            override fun createFile(name: String): USBFile {
                TODO()
            }

            override fun flush() {
                TODO()
            }

            override fun listFiles(): Array<USBFile> {
                val usbFiles = mutableListOf<USBFile>()
                Files.list(path).forEach {
                    usbFiles.add(convertPathToUsbFile(it))
                }
                return usbFiles.toTypedArray()
            }

            override fun read(offset: Long, outBuf: ByteBuffer) {
                Logger.verbose(TAG, "read(offset=$offset,outBuf=$outBuf) for $path")
                if (byteChannel == null) {
                    byteChannel = Files.newByteChannel(path)
                }
                byteChannel?.position(offset)
                byteChannel?.read(outBuf) ?: return
            }

            override fun search(path: String): USBFile {
                return convertPathToUsbFile(filesystem.getPath(path))
            }

            override fun write(offset: Long, inBuf: ByteBuffer) {
                if (byteChannel == null) {
                    byteChannel = Files.newByteChannel(path)
                }
                byteChannel?.position(offset)
                byteChannel?.write(inBuf)
            }

            override fun getOutputStream(): USBFileOutputStream {
                TODO("not yet implemented")
            }

            override fun getInputStream(): USBFileInputStream {
                return object : USBFileInputStream() {
                    private val inputStream = Files.newInputStream(path)

                    override fun read(): Int {
                        return inputStream.read()
                    }

                    override fun read(buffer: ByteArray?): Int {
                        return inputStream.read(buffer)
                    }

                    override fun read(buffer: ByteArray?, offset: Int, length: Int): Int {
                        return inputStream.read(buffer, offset, length)
                    }

                    override fun skip(numBytes: Long): Long {
                        return inputStream.skip(numBytes)
                    }

                    override fun available(): Int {
                        return inputStream.available()
                    }

                    override fun close() {
                        inputStream.close()
                    }

                }
            }
        }
    }

    fun getPath(filePath: String): Path? {
        return filesystem.getPath(filePath)
    }

    fun createDirectories(directoryPath: String) {
        if (!filesystem.getPath(directoryPath).exists()) {
            Logger.debug(TAG, "Creating directories: $directoryPath")
            filesystem.getPath(directoryPath).createDirectories()
        }
    }

}
