/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb

import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants.USB_CLASS_MASS_STORAGE
import android.hardware.usb.UsbDevice
import android.media.MediaDataSource
import android.net.Uri
import android.os.Build
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.exceptions.DriveAlmostFullException
import de.moleman1024.audiowagon.exceptions.NoAudioItemException
import de.moleman1024.audiowagon.exceptions.NoFileSystemException
import de.moleman1024.audiowagon.filestorage.data.AudioFile
import de.moleman1024.audiowagon.filestorage.FileSystem
import de.moleman1024.audiowagon.filestorage.MediaDevice
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.USBFile
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.*
import java.io.FileNotFoundException
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.Executors

private const val MINIMUM_FREE_SPACE_FOR_LOGGING_MB = 10
const val LOG_DIRECTORY = "aw_logs"

private const val DEFAULT_FILESYSTEM_CHUNK_SIZE = 32768

// used in a hash map during indexing to improve speed
private const val MAX_NUM_FILEPATHS_TO_CACHE = 20

class USBMediaDevice(private val context: Context, private val usbDevice: USBDevice) : MediaDevice {
    override val TAG = "USBMediaDevice"
    override val logger = Logger
    private var fileSystem: FileSystem? = null
    private var serialNum: String = ""
    private var isSerialNumAvail: Boolean? = null
    private var logFile: USBFile? = null
    private var volumeLabel: String = ""
    private var logDirectoryNum: Int = 0
    private val recentFilepathToFileMap: FilePathToFileMapCache = FilePathToFileMapCache()
    // thread confinement for certain actions on this USB device
    private val usbLibDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private class FilePathToFileMapCache : LinkedHashMap<String, USBFile>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, USBFile>?): Boolean {
            return size > MAX_NUM_FILEPATHS_TO_CACHE
        }
    }

    fun requestPermission(intentBroadcast: PendingIntent) {
        usbDevice.requestPermission(intentBroadcast)
    }

    fun hasPermission(): Boolean {
        return usbDevice.hasPermission()
    }

    private fun hasFileSystem(): Boolean {
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
        return vendorProductID in Util.USB_DEVICES_TO_IGNORE
    }

    private fun isBitfieldMassStorage(bitfield: Int): Boolean {
        return bitfield == USB_CLASS_MASS_STORAGE
    }

    fun isCompatible(): Boolean {
        return usbDevice.isCompatible()
    }

    suspend fun initFilesystem() {
        if (hasFileSystem()) {
            return
        }
        try {
            withContext(usbLibDispatcher) {
                fileSystem = usbDevice.initFilesystem(context)
                if (fileSystem == null) {
                    logger.error(TAG, "No filesystem after initFilesystem() for: $this")
                    return@withContext
                }
                volumeLabel = fileSystem?.volumeLabel?.trim() ?: ""
                logger.debug(TAG, "Initialized filesystem with volume label: $volumeLabel")
            }
        } catch (exc: Exception) {
            throw exc
        }
    }

    suspend fun enableLogging() {
        logger.debug(TAG, "enableLogging()")
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
            withContext(usbLibDispatcher) {
                var logDirectory: USBFile?
                logDirectory = getRoot().search("${LOG_DIRECTORY}_$logDirectoryNum")
                if (logDirectory == null) {
                    logDirectory = getRoot().createDirectory("${LOG_DIRECTORY}_$logDirectoryNum")
                }
                logFile = logDirectory.createFile(logFileName)
                logger.debug(TAG, "Logging to file on USB device: ${logFile!!.absolutePath}")
                logger.setUSBFile(logFile!!, getChunkSize())
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
                withTimeout(4000) {
                    logger.writeBufferedLogToUSBFile()
                    logger.closeUSBFile()
                }
            }
        } catch (exc: IOException) {
            logFile = null
            throw exc
        } catch (exc: TimeoutCancellationException) {
            logger.exception(TAG, "Could not write/close log file on USB in time", exc)
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
            logger.logVersion(context)
            logger.setFlushToUSBFlag()
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
        runBlocking(usbLibDispatcher) {
            fileSystem = null
            usbDevice.close()
        }
    }

    fun getRoot(): USBFile {
        if (!hasFileSystem()) {
            throw NoSuchElementException("No filesystem in getRoot() for: $this")
        }
        logger.verbose(TAG, "Using filesystem: $fileSystem")
        return fileSystem!!.rootDirectory
    }

    /**
     * Traverses files/directories breadth-first
     */
    fun walkTopDown(rootDirectory: USBFile, scope: CoroutineScope): Sequence<USBFile> = sequence {
        val queue = LinkedList<USBFile>()
        val allFilesDirs = mutableMapOf<String, Unit>()
        clearRecentFilepathToFileMap()
        allFilesDirs[rootDirectory.absolutePath] = Unit
        queue.add(rootDirectory)
        while (queue.isNotEmpty()) {
            scope.ensureActive()
            val fileOrDirectory = queue.removeFirst()
            assertFileSystemAvailable()
            val isDirectory: Boolean = fileOrDirectory.isDirectory
            val absPath: String = fileOrDirectory.absolutePath
            val name: String = fileOrDirectory.name
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
                    val subFilesDirs: List<USBFile> = fileOrDirectory.listFiles().sortedBy { it.name.lowercase() }
                    for (subFileOrDir in subFilesDirs) {
                        assertFileSystemAvailable()
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

    fun clearRecentFilepathToFileMap() {
        recentFilepathToFileMap.clear()
    }

    private fun assertFileSystemAvailable() {
        if (!hasFileSystem()) {
            throw NoFileSystemException()
        }
    }

    fun getDirectoryContents(directoryURI: Uri): List<USBFile> {
        logger.verbose(TAG, "getDirectoryContents(directoryURI=$directoryURI)")
        val directory = getUSBFileFromURI(directoryURI)
        require(directory.isDirectory) {
            "Is not a directory: $directory"
        }
        return directory.listFiles().sortedBy { it.name }.toList()
    }

    override fun getName(): String {
        val stringBuilder: StringBuilder = StringBuilder()
        stringBuilder.append("${USBMediaDevice::class.simpleName}{")
        if (usbDevice.manufacturerName?.isBlank() == false) {
            stringBuilder.append(usbDevice.manufacturerName?.trimEnd())
        }
        if (usbDevice.productName?.isBlank() == false) {
            stringBuilder.append(" ${usbDevice.productName?.trimEnd()}")
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
        return runBlocking(usbLibDispatcher) {
            return@runBlocking "${USBMediaDevice::class.simpleName}{${usbDevice};${hashCode()}}"
        }
    }

    override suspend fun getDataSourceForURI(uri: Uri): MediaDataSource {
        if (!hasFileSystem()) {
            throw IOException("No filesystem for data source")
        }
        val usbFile: USBFile = getUSBFileFromURI(uri)
        return USBMetaDataSource(usbFile, getChunkSize())
    }

    override suspend fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource {
        if (!hasFileSystem()) {
            throw IOException("No filesystem for data source")
        }
        val usbFile: USBFile = getUSBFileFromURI(uri)
        return USBAudioCachedDataSource(usbFile, getChunkSize())
    }

    override fun getFileFromURI(uri: Uri): Any {
        return getUSBFileFromURI(uri)
    }

    fun getUSBFileFromURI(uri: Uri): USBFile {
        val audioFile = AudioFile(uri)
        val filePath = audioFile.path
        val usbFileFromCache: USBFile? = recentFilepathToFileMap[filePath]
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
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as USBMediaDevice
        if (usbDevice != other.usbDevice) return false
        return true
    }

    override fun hashCode(): Int {
        return usbDevice.hashCode()
    }

}
