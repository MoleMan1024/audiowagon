/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb

import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbConstants.USB_CLASS_MASS_STORAGE
import android.hardware.usb.UsbDevice
import android.media.MediaDataSource
import android.net.Uri
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.exceptions.DriveAlmostFullException
import de.moleman1024.audiowagon.exceptions.NoFileSystemException
import de.moleman1024.audiowagon.filestorage.AudioFile
import de.moleman1024.audiowagon.filestorage.MediaDevice
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.*
import java.io.FileNotFoundException
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.lang.IllegalArgumentException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.Executors
import kotlin.NoSuchElementException
import kotlin.collections.LinkedHashMap

private const val MINIMUM_FREE_SPACE_FOR_LOGGING_MB = 10
const val LOG_DIRECTORY = "aw_logs"

// subclass 6 means that the usb mass storage device implements the SCSI transparent command set
private const val INTERFACE_SUBCLASS_SCSI = 6

// protocol 80 means the communication happens only via bulk transfers
private const val INTERFACE_PROTOCOL_BULK_TRANSFER = 80

private const val DEFAULT_FILESYSTEM_CHUNK_SIZE = 32768

// used in a hash map during indexing to improve speed
private const val MAX_NUM_FILEPATHS_TO_CACHE = 20

class USBMediaDevice(private val context: Context, private val usbDevice: USBDevice) : MediaDevice {
    override val TAG = "USBMediaDevice"
    override val logger = Logger
    private var fileSystem: FileSystem? = null
    private var serialNum: String = ""
    private var isSerialNumAvail: Boolean? = null
    private var logFile: UsbFile? = null
    private var volumeLabel: String = ""
    private var logDirectoryNum: Int = 0
    private val recentFilepathToFileMap: FilePathToFileMapCache = FilePathToFileMapCache()

    // libaums is not thread-safe. My changes made it a bit more safe but still having some threading problems here and
    // there, so will try thread confinement
    val libaumsDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private class FilePathToFileMapCache : LinkedHashMap<String, UsbFile>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, UsbFile>?): Boolean {
            return size > MAX_NUM_FILEPATHS_TO_CACHE
        }
    }

    fun requestPermission(intentBroadcast: PendingIntent) {
        usbDevice.requestPermission(intentBroadcast)
    }

    fun hasPermission(): Boolean {
        return usbDevice.hasPermission()
    }

    fun hasFileSystem(): Boolean {
        return fileSystem != null
    }

    fun isMassStorageDevice(): Boolean {
        if (isBitfieldMassStorage(usbDevice.deviceClass)) {
            return true
        }
        for (indexCfg in 0 until usbDevice.configurationCount) {
            val configuration = usbDevice.getConfiguration(indexCfg)
            for (indexInterface in 0 until configuration.interfaceCount) {
                val usbInterface = configuration.getInterface(indexInterface)
                if (isBitfieldMassStorage(usbInterface.interfaceClass)) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Ignore certain devices on the USB that are built-in to the car (decimal IDs).
     */
    fun isToBeIgnored(): Boolean {
        val vendorProductID = Pair(usbDevice.vendorId, usbDevice.productId)
        val vendorProductIDHex = Pair("0x%x".format(usbDevice.vendorId), "0x%x".format(usbDevice.productId))
        logger.debug(TAG, "isToBeIgnored($vendorProductID / $vendorProductIDHex)")
        // Volvo, Polestar, General Motors, Renault
        if (vendorProductID in listOf(
                // Microchip AN20021 USB to UART Bridge with USB 2.0 hub 0x2530
                Pair(1060, 9520),
                // USB Ethernet 0X9E08
                Pair(1060, 40456),
                // MicroChip OS81118 network interface card 0x0424
                Pair(1060, 53016),
                // Microchip Tech USB49XX NCM/IAP Bridge
                Pair(1060, 18704),
                // Microchip USB4913
                Pair(1060, 18707),
                // Microchip Tech USB2 Controller Hub
                Pair(1060, 18752),
                // Microchip MCP2200 USB to UART converter 0x04D8
                Pair(1240, 223),
                // Mitsubishi USB to Modem Bridge 0x06D3
                Pair(1747, 10272),
                // Cambridge Silicon Radio Bluetooth dongle 0x0A12
                Pair(2578, 1),
                // Cambridge Silicon Radio Bluetooth dongle 0x0A12
                Pair(2578, 3),
                // Linux xHCI Host Controller
                Pair(7531, 2),
                // Linux xHCI Host Controller
                Pair(7531, 3),
                // Delphi Host to Host Bridge
                Pair(11336, 261),
                // Delphi Vendor
                Pair(11336, 288),
                // Delphi Hub
                Pair(11336, 306),
                // Microchip USB2 Controller Hub
                Pair(18752, 1060)
            )
        ) {
            return true
        }
        return false
    }

    private fun isBitfieldMassStorage(bitfield: Int): Boolean {
        return bitfield == USB_CLASS_MASS_STORAGE
    }

    /**
     * This re-implements some of the checks done in libaums class [UsbMassStorageDevice]. The content of this
     * function is originally licensed under Apache 2.0. I modified it slightly.
     *
     * @see [library source code](https://github.com/magnusja/libaums/blob/develop/libaums/src/main/java/me.jahnen.libaums.core/UsbMassStorageDevice.kt)
     * TODO: avoid code duplication with libaums library
     *
     * (C) Copyright 2014-2019 magnusja <github@mgns.tech>
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
     */
    fun isCompatibleWithLib(): Boolean {
        return (0 until usbDevice.interfaceCount)
            .map { usbDevice.getInterface(it) }
            .filter {
                // libaums currently only supports SCSI transparent command set with bulk transfers
                it.interfaceSubclass == INTERFACE_SUBCLASS_SCSI
                        && it.interfaceProtocol == INTERFACE_PROTOCOL_BULK_TRANSFER
            }
            .map { usbInterface ->
                // Every mass storage device should have exactly two endpoints: One IN and one OUT endpoint
                // Some people connect strange devices e.g. Transcend MP3 players that have more endpoints but also
                // support USB mass storage
                val endpointCount = usbInterface.endpointCount
                if (endpointCount != 2) {
                    logger.warning(TAG, "Interface endpoint count: $endpointCount != 2 for $usbInterface")
                }
                var outEndpoint: USBEndpoint? = null
                var inEndpoint: USBEndpoint? = null
                for (j in 0 until endpointCount) {
                    val endpoint = usbInterface.getEndpoint(j)
                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                            outEndpoint = endpoint
                        } else {
                            inEndpoint = endpoint
                        }
                    }
                }
                if (outEndpoint == null || inEndpoint == null) {
                    logger.error(TAG, "Not all needed endpoints found")
                    return false
                }
            }.isNotEmpty()
    }

    suspend fun initFilesystem() {
        if (hasFileSystem()) {
            return
        }
        withContext(libaumsDispatcher) {
            fileSystem = usbDevice.initFilesystem(context)
            if (fileSystem == null) {
                logger.error(TAG, "No filesystem after initFilesystem() for: $this")
                return@withContext
            }
            volumeLabel = fileSystem?.volumeLabel?.trim() ?: ""
            logger.debug(TAG, "Initialized filesystem with volume label: $volumeLabel")
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun enableLogging() {
        logger.verbose(TAG, "enableLogging()")
        if (logFile != null) {
            logger.debug(TAG, "Logging to file is already enabled")
            return
        }
        if (!hasFileSystem()) {
            throw RuntimeException("Cannot write log files to non-initialized mass storage: $usbDevice")
        }
        if (isDriveAlmostFull()) {
            throw DriveAlmostFullException()
        }
        val now = LocalDateTime.now()
        val logFileName = "audiowagon_${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.log"
        try {
            withContext(libaumsDispatcher) {
                var logDirectory: UsbFile?
                logDirectory = getRoot().search("${LOG_DIRECTORY}_$logDirectoryNum")
                if (logDirectory == null) {
                    logDirectory = getRoot().createDirectory("${LOG_DIRECTORY}_$logDirectoryNum")
                }
                logFile = logDirectory.createFile(logFileName)
                logger.debug(TAG, "Logging to file on USB device: ${logFile!!.absolutePath}")
                logger.setUSBFile(
                    logFile!!,
                    fileSystem?.chunkSize ?: DEFAULT_FILESYSTEM_CHUNK_SIZE,
                    libaumsDispatcher
                )
            }
            logVersionToUSBLogfile()
        } catch (exc: IOException) {
            logger.exception(TAG, "Cannot create log file on USB device", exc)
        }
        logger.launchLogFileWriteJob()
    }

    fun disableLogging() {
        logger.debug(TAG, "disableLogging()")
        if (logFile == null) {
            logger.debug(TAG, "Logging to file is already disabled")
            return
        }
        logger.cancelLogFileWriteJob()
        try {
            logVersionToUSBLogfile()
            logger.info(TAG, "Disabling log to file on USB device")
            logger.setFlushToUSBFlag()
            runBlocking(Dispatchers.IO) {
                logger.writeBufferedLogToUSBFile()
            }
            logger.closeUSBFile()
        } catch (exc: IOException) {
            logFile = null
            throw exc
        } finally {
            logFile = null
        }
    }

    fun preventLoggingToDetachedDevice() {
        logger.setUSBFileStreamToNull()
        logFile = null
    }

    private fun logVersionToUSBLogfile() {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            logger.info(TAG, "Version: ${packageInfo.versionName} (code: ${packageInfo.longVersionCode})")
            logger.setFlushToUSBFlag()
        } catch (exc: PackageManager.NameNotFoundException) {
            logger.exception(TAG, "Package name not found", exc)
        }
    }

    // confined by libaumsDispatcher internally
    fun close() {
        logger.debug(TAG, "Closing: ${getName()}")
        try {
            disableLogging()
        } catch (exc: IOException) {
            logger.exceptionLogcatOnly(TAG, exc.message.toString(), exc)
        }
        closeMassStorageFilesystem()
    }

    fun closeMassStorageFilesystem() {
        runBlocking(libaumsDispatcher) {
            fileSystem = null
            usbDevice.close()
        }
    }

    // needs to be confied via libaumsDispatcher externally
    fun getRoot(): UsbFile {
        if (!hasFileSystem()) {
            throw NoSuchElementException("No filesystem in getRoot() for: $this")
        }
        logger.verbose(TAG, "Using filesystem: $fileSystem")
        return fileSystem!!.rootDirectory
    }

    /**
     * Traverses files/directories breadth-first. Locked internally by usbMutex
     */
    fun walkTopDown(rootDirectory: UsbFile, scope: CoroutineScope): Sequence<UsbFile> = sequence {
        val queue = LinkedList<UsbFile>()
        val allFilesDirs = mutableMapOf<String, Unit>()
        clearRecentFilepathToFileMap()
        runBlocking(libaumsDispatcher) {
            allFilesDirs[rootDirectory.absolutePath] = Unit
        }
        queue.add(rootDirectory)
        while (queue.isNotEmpty()) {
            scope.ensureActive()
            val fileOrDirectory = queue.removeFirst()
            assertFileSystemAvailable()
            var isDirectory: Boolean
            var absPath: String
            var name: String
            runBlocking(libaumsDispatcher) {
                isDirectory = fileOrDirectory.isDirectory
                absPath = fileOrDirectory.absolutePath
                name = fileOrDirectory.name
            }
            if (!isDirectory) {
                if (name.contains(Util.FILES_TO_IGNORE_REGEX)) {
                    logger.debug(TAG, "Ignoring file: $name")
                } else {
                    logger.verbose(TAG, "Found file: $absPath")
                    recentFilepathToFileMap[absPath] = fileOrDirectory
                    yield(fileOrDirectory)
                }
            } else {
                if (name.contains(Util.DIRECTORIES_TO_IGNORE_REGEX)) {
                    logger.debug(TAG, "Ignoring directory: $name")
                } else {
                    logger.debug(TAG, "Walking directory: $absPath")
                    recentFilepathToFileMap[absPath] = fileOrDirectory
                    yield(fileOrDirectory)
                    var subFilesDirs: List<UsbFile>
                    runBlocking(libaumsDispatcher) {
                        subFilesDirs = fileOrDirectory.listFiles().sortedBy { it.name.lowercase() }
                    }
                    for (subFileOrDir in subFilesDirs) {
                        assertFileSystemAvailable()
                        runBlocking(libaumsDispatcher) {
                            if (!allFilesDirs.containsKey(subFileOrDir.absolutePath)) {
                                allFilesDirs[subFileOrDir.absolutePath] = Unit
                                if (subFileOrDir.isDirectory) {
                                    logger.verbose(TAG, "Found directory: ${subFileOrDir.absolutePath}")
                                }
                                queue.add(subFileOrDir)
                            }
                        }
                    }
                }
            }
        }
    }

    fun clearRecentFilepathToFileMap() {
        recentFilepathToFileMap.clear()
    }

    private fun assertFileSystemAvailable() {
        if (!hasFileSystem()) {
            throw NoFileSystemException()
        }
    }

    fun getDirectoryContents(directoryURI: Uri): List<UsbFile> {
        logger.verbose(TAG, "getDirectoryContents(directoryURI=$directoryURI)")
        return runBlocking(libaumsDispatcher) {
            val directory = getUSBFileFromURI(directoryURI)
            if (!directory.isDirectory) {
                throw IllegalArgumentException("Is not a directory: $directory")
            }
            return@runBlocking directory.listFiles().sortedBy { it.name }.toList()
        }
    }

    override fun getName(): String {
        val stringBuilder: StringBuilder = StringBuilder()
        stringBuilder.append("${USBMediaDevice::class.simpleName}{")
        if (usbDevice.manufacturerName?.isBlank() == false) {
            stringBuilder.append(usbDevice.manufacturerName)
        }
        if (usbDevice.productName?.isBlank() == false) {
            stringBuilder.append(" ${usbDevice.productName}")
        }
        stringBuilder.append("(")
        if (usbDevice.vendorId >= 0) {
            stringBuilder.append(usbDevice.vendorId)
        }
        if (usbDevice.productId >= 0) {
            stringBuilder.append(";${usbDevice.productId}")
        }
        // this "deviceName" is actually a unix device file (e.g. /dev/bus/usb/002/002 )
        if (usbDevice.deviceName.isNotBlank()) {
            stringBuilder.append(";${usbDevice.deviceName}")
        }
        if (volumeLabel.isNotBlank()) {
            stringBuilder.append(";${volumeLabel}")
        }
        stringBuilder.append(";${hashCode()}")
        stringBuilder.append(")}")
        return stringBuilder.toString()
    }

    override fun toString(): String {
        return "${USBMediaDevice::class.simpleName}{${usbDevice};${hashCode()}}"
    }

    override suspend fun getDataSourceForURI(uri: Uri): MediaDataSource {
        if (!hasFileSystem()) {
            throw IOException("No filesystem for data source")
        }
        val chunkSize: Int
        val usbFile: UsbFile
        withContext(libaumsDispatcher) {
            chunkSize = fileSystem!!.chunkSize
            usbFile = getUSBFileFromURI(uri)
        }
        return USBMetaDataSource(usbFile, chunkSize, libaumsDispatcher)
    }

    override suspend fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource {
        if (!hasFileSystem()) {
            throw IOException("No filesystem for data source")
        }
        val chunkSize: Int
        val usbFile: UsbFile
        withContext(libaumsDispatcher) {
            chunkSize = fileSystem!!.chunkSize
            usbFile = getUSBFileFromURI(uri)
        }
        return USBAudioCachedDataSource(usbFile, chunkSize, libaumsDispatcher)
    }

    // locked externally
    override fun getFileFromURI(uri: Uri): Any {
        return getUSBFileFromURI(uri)
    }

    // needs to be confied via libaumsDispatcher externally
    fun getUSBFileFromURI(uri: Uri): UsbFile {
        val audioFile = AudioFile(uri)
        val filePath = audioFile.path
        val usbFileFromCache: UsbFile? = recentFilepathToFileMap[filePath]
        if (usbFileFromCache != null) {
            logger.verbose(TAG, "Returning file/dir from cache: $uri")
            return usbFileFromCache
        }
        return if (filePath == "/") {
            getRoot()
        } else {
            // changed libaums to not be case sensitive in search()
            getRoot().search(filePath) ?: throw FileNotFoundException("USB file not found: $uri")
        }
    }

    /**
     * Creates a "unique" identifier for the USB device that is persistent across USB device (dis)connects.
     * We cannot use [UsbDevice.getDeviceId] because of that requirement.
     * TODO: not great because it changes depending on permissions/volume label status
     */
    override fun getID(): String {
        val stringBuilder: StringBuilder = StringBuilder()
        val serialNum: String = getSerialNum()
        if (serialNum.isNotBlank()) {
            stringBuilder.append(serialNum)
        } else {
            // In case we have no permission to access serial number, use volume label instead, that should be
            // unique enough for our purposes
            try {
                if (volumeLabel.isNotBlank()) {
                    val safeVolumeLabel = Util.sanitizeVolumeLabel(volumeLabel)
                    stringBuilder.append(safeVolumeLabel)
                }
            } catch (exc: UnsupportedEncodingException) {
                logger.exception(TAG, "UTF-8 is not supported?!", exc)
            }
        }
        if (usbDevice.vendorId >= 0) {
            if (stringBuilder.isNotEmpty()) {
                stringBuilder.append("-")
            }
            stringBuilder.append("${usbDevice.vendorId}")
        }
        if (usbDevice.productId >= 0) {
            if (stringBuilder.isNotEmpty()) {
                stringBuilder.append("-")
            }
            stringBuilder.append("${usbDevice.productId}")
        }
        return stringBuilder.toString()
    }

    /**
     * It seems that when USB-device-detached intent is received, the permissions to e.g. access serial numbers are
     * already revoked. Thus we copy the serial number and store it as a property when we still have the permission.
     * Note that accessing the USB drive serial number does not work in the car (probably due to security permissions).
     */
    private fun getSerialNum(): String {
        if (isSerialNumAvail != null && !isSerialNumAvail!!) {
            return ""
        }
        if (serialNum.isNotBlank()) {
            return serialNum
        }
        if (!hasPermission()) {
            logger.warning(TAG, "Missing permission to access serial number of: ${getName()}")
            return ""
        }
        if (usbDevice.serialNumber.isNullOrBlank()) {
            // we don't have sufficient priviliges to access the serial number, or the USB drive did not provide one
            logger.warning(TAG, "Serial number is not available for: ${getName()}")
            isSerialNumAvail = false
            return ""
        }
        // we limit the length here, I have seen some USB devices with ridiculously long serial numbers
        val numCharsSerialMax = 14
        serialNum = usbDevice.serialNumber.toString().take(numCharsSerialMax)
        isSerialNumAvail = true
        return serialNum
    }

    private suspend fun isDriveAlmostFull(): Boolean {
        if (!hasFileSystem()) {
            throw NoFileSystemException()
        }
        return withContext(libaumsDispatcher) {
            return@withContext fileSystem!!.freeSpace < 1024 * 1024 * MINIMUM_FREE_SPACE_FOR_LOGGING_MB
        }
    }

    suspend fun getChunkSize(): Int {
        return withContext(libaumsDispatcher) {
            return@withContext fileSystem?.chunkSize ?: DEFAULT_FILESYSTEM_CHUNK_SIZE
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is USBMediaDevice) {
            return false
        }
        if (this.hasPermission() && other.hasPermission() && this.hasFileSystem() && other.hasFileSystem()) {
            if (getID() == other.getID()) {
                return true
            }
        } else {
            // permission is missing or filesystem not initialized on one object, fallback to just comparing the
            // vendor/product ID
            if (usbDevice.vendorId == other.usbDevice.vendorId && usbDevice.productId == other.usbDevice.productId) {
                return true
            }
        }
        return false
    }

    override fun hashCode(): Int {
        var result = usbDevice.hashCode()
        result = 31 * result + (if (this.hasPermission()) 1 else 0)
        result = 31 * result + (if (this.hasFileSystem()) 1 else 0)
        if (this.hasPermission() && this.hasFileSystem()) {
            result = 31 * result + serialNum.hashCode()
            result = 31 * result + volumeLabel.hashCode()
        }
        return result
    }

}
