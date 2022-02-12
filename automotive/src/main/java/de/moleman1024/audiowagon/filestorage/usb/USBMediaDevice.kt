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
import com.github.mjdev.libaums.UsbMassStorageDevice
import com.github.mjdev.libaums.fs.FileSystem
import com.github.mjdev.libaums.fs.UsbFile
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.exceptions.DriveAlmostFullException
import de.moleman1024.audiowagon.exceptions.NoFileSystemException
import de.moleman1024.audiowagon.filestorage.AudioFile
import de.moleman1024.audiowagon.filestorage.MediaDevice
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import java.io.FileNotFoundException
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.lang.IllegalArgumentException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val TAG = "USBMediaDevice"
private val logger = Logger
private const val MINIMUM_FREE_SPACE_FOR_LOGGING_MB = 10
const val LOG_DIRECTORY = "aw_logs"

// subclass 6 means that the usb mass storage device implements the SCSI transparent command set
private const val INTERFACE_SUBCLASS = 6

// protocol 80 means the communication happens only via bulk transfers
private const val INTERFACE_PROTOCOL = 80

private const val DEFAULT_FILESYSTEM_CHUNK_SIZE = 32768

// used in a hash map during indexing to improve speed
private const val MAX_NUM_FILEPATHS_TO_CACHE = 20

class USBMediaDevice(private val context: Context, private val usbDevice: USBDevice): MediaDevice {
    private var fileSystem: FileSystem? = null
    private var serialNum: String = ""
    private var isSerialNumAvail: Boolean? = null
    private var logFile: UsbFile? = null
    private var volumeLabel: String = ""
    private var logDirectoryNum: Int = 0
    private val recentFilepathToFileMap: FilePathToFileMapCache = FilePathToFileMapCache()

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
        logger.debug(TAG, "isToBeIgnored($vendorProductID)")
        if (vendorProductID in listOf(
                // Cambridge Silicon Radio Bluetooth dongle 0x0A12
                Pair(2578, 1),
                // Cambridge Silicon Radio Bluetooth dongle 0x0A12
                Pair(2578, 3),
                // Microchip AN20021 USB to UART Bridge with USB 2.0 hub 0x2530
                Pair(1060, 9520),
                // USB Ethernet 0X9E08
                Pair(1060, 40456),
                // MicroChip OS81118 network interface card 0x0424
                Pair(1060, 53016),
                // Microchip MCP2200 USB to UART converter 0x04D8
                Pair(1240, 223)
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
     * @see [library source code](https://github.com/magnusja/libaums/blob/develop/libaums/src/main/java/com/github/mjdev/libaums/UsbMassStorageDevice.kt)
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
                it.interfaceSubclass == INTERFACE_SUBCLASS && it.interfaceProtocol == INTERFACE_PROTOCOL
            }
            .map { usbInterface ->
                // Every mass storage device has exactly two endpoints: One IN and one OUT endpoint
                val endpointCount = usbInterface.endpointCount
                if (endpointCount != 2) {
                    logger.warning(TAG, "Interface endpoint count != 2")
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

    fun initFilesystem() {
        if (hasFileSystem()) {
            return
        }
        fileSystem = usbDevice.initFilesystem(context)
        if (fileSystem == null) {
            logger.error(TAG, "No filesystem after initFilesystem() for: $this")
            return
        }
        volumeLabel = fileSystem?.volumeLabel?.trim() ?: ""
        logger.debug(TAG, "Initialized filesystem with volume label: $volumeLabel")
    }

    fun enableLogging() {
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
            var logDirectory: UsbFile?
            logDirectory = getRoot().search("${LOG_DIRECTORY}_$logDirectoryNum")
            if (logDirectory == null) {
                logDirectory = getRoot().createDirectory("${LOG_DIRECTORY}_$logDirectoryNum")
            }
            logFile = logDirectory.createFile(logFileName)
            logger.debug(TAG, "Logging to file on USB device: ${logFile!!.absolutePath}")
            logger.setUSBFile(logFile!!, fileSystem?.chunkSize ?: DEFAULT_FILESYSTEM_CHUNK_SIZE)
            logVersionToUSBLogfile()
        } catch (exc: IOException) {
            logger.exception(TAG, "Cannot create log file on USB device", exc)
        }
    }

    fun disableLogging() {
        if (logFile == null) {
            logger.debug(TAG, "Logging to file is already disabled")
            return
        }
        try {
            logVersionToUSBLogfile()
            logger.info(TAG, "Disabling log to file on USB device")
            logger.flushToUSB()
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
            logger.flushToUSB()
        } catch (exc: PackageManager.NameNotFoundException) {
            logger.exception(TAG, "Package name not found", exc)
        }
    }

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
        fileSystem = null
        usbDevice.close()
    }

    fun getRoot(): UsbFile {
        if (!hasFileSystem()) {
            throw RuntimeException("No filesystem in getRoot() for: $this")
        }
        return fileSystem!!.rootDirectory
    }

    /**
     * Traverses files/directories depth-first
     */
    fun walkTopDown(rootDirectory: UsbFile, scope: CoroutineScope): Sequence<UsbFile> = sequence {
        val stack = ArrayDeque<Iterator<UsbFile>>()
        val allFilesDirs = mutableMapOf<String, Unit>()
        recentFilepathToFileMap.clear()
        stack.add(rootDirectory.listFiles().sortedBy { it.name }.iterator())
        while (stack.isNotEmpty()) {
            scope.ensureActive()
            if (stack.last().hasNext()) {
                val fileOrDirectory = stack.last().next()
                if (!allFilesDirs.containsKey(fileOrDirectory.absolutePath)) {
                    allFilesDirs[fileOrDirectory.absolutePath] = Unit
                    assertFileSystemAvailable()
                    if (!fileOrDirectory.isDirectory) {
                        logger.verbose(TAG, "Found file: ${fileOrDirectory.absolutePath}")
                        recentFilepathToFileMap[fileOrDirectory.absolutePath] = fileOrDirectory
                        assertFileSystemAvailable()
                        yield(fileOrDirectory)
                    } else {
                        assertFileSystemAvailable()
                        if (fileOrDirectory.name.contains(Util.DIRECTORIES_TO_IGNORE_REGEX)) {
                            logger.debug(TAG, "Ignoring directory: ${fileOrDirectory.name}")
                        } else {
                            logger.debug(TAG, "Walking directory: ${fileOrDirectory.absolutePath}")
                            stack.add(fileOrDirectory.listFiles().sortedBy { it.name }.iterator())
                        }
                    }
                }
            } else {
                stack.removeLast()
            }
        }
        recentFilepathToFileMap.clear()
    }

    private fun assertFileSystemAvailable() {
        if (!hasFileSystem()) {
            throw NoFileSystemException()
        }
    }

    fun getDirectoryContents(directoryURI: Uri): List<UsbFile> {
        val directory = getUSBFileFromURI(directoryURI)
        if (!directory.isDirectory) {
            throw IllegalArgumentException("Is not a directory: $directory")
        }
        return directory.listFiles().sortedBy{ it.name }.toList()
    }

    override fun getName(): String {
        val stringBuilder: StringBuilder = StringBuilder()
        stringBuilder.append("${USBMediaDevice::class}{")
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
        return "${USBMediaDevice::class}{${usbDevice};${hashCode()}}"
    }

    override fun getDataSourceForURI(uri: Uri): MediaDataSource {
        if (!hasFileSystem()) {
            throw IOException("No filesystem for data source")
        }
        return USBAudioDataSource(getUSBFileFromURI(uri), fileSystem!!.chunkSize)
    }

    override fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource {
        if (!hasFileSystem()) {
            throw IOException("No filesystem for data source")
        }
        return USBAudioCachedDataSource(getUSBFileFromURI(uri), fileSystem!!.chunkSize)
    }

    @Synchronized
    fun getUSBFileFromURI(uri: Uri): UsbFile {
        val audioFile = AudioFile(uri)
        val filePath = audioFile.path
        val usbFileFromCache: UsbFile? = recentFilepathToFileMap[filePath]
        if (usbFileFromCache != null) {
            return usbFileFromCache
        }
        return getRoot().search(filePath) ?: throw FileNotFoundException("USB file not found: $uri")
    }

    /**
     * Creates a "unique" identifier for the USB device that is persistent across USB device (dis)connects.
     * We cannot use [UsbDevice.getDeviceId] because of that requirement.
     * TODO: not great because it changes depending on permissions/filesystem initialization status
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

    private fun isDriveAlmostFull(): Boolean {
        if (!hasFileSystem()) {
            throw NoFileSystemException()
        }
        return fileSystem!!.freeSpace < 1024 * 1024 * MINIMUM_FREE_SPACE_FOR_LOGGING_MB
    }

    fun getChunkSize(): Int {
        return fileSystem?.chunkSize ?: DEFAULT_FILESYSTEM_CHUNK_SIZE
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
