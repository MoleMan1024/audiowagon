/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.support.v4.media.MediaDescriptionCompat
import androidx.annotation.VisibleForTesting
import de.moleman1024.audiowagon.*
import de.moleman1024.audiowagon.authorization.SDCardDevicePermissions
import de.moleman1024.audiowagon.authorization.USBDevicePermissions
import de.moleman1024.audiowagon.exceptions.NoSuchDeviceException
import de.moleman1024.audiowagon.filestorage.asset.AssetMediaDevice
import de.moleman1024.audiowagon.filestorage.asset.AssetStorageLocation
import de.moleman1024.audiowagon.filestorage.sd.SDCardAudioDataSource
import de.moleman1024.audiowagon.filestorage.sd.SDCardMediaDevice
import de.moleman1024.audiowagon.filestorage.sd.SDCardStorageLocation
import de.moleman1024.audiowagon.filestorage.usb.USBAudioDataSource
import de.moleman1024.audiowagon.filestorage.usb.USBDeviceConnections
import de.moleman1024.audiowagon.filestorage.usb.USBDeviceStorageLocation
import de.moleman1024.audiowagon.filestorage.usb.USBMediaDevice
import de.moleman1024.audiowagon.log.CrashReporting
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.RESOURCE_ROOT_URI
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyElement
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyID
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "AudioFileStor"
private val logger = Logger
private const val SD_CARD_ID = "1404-9F0B"
private const val MAX_NUM_ALBUM_ART_DIRS_TO_CACHE = 100

/**
 * This class manages storage locations and provides functions to index them for audio files
 *
 * We need to implement this ourselves, none of the stuff in https://developer.android.com/training/data-storage will
 * work for USB
 */
@ExperimentalCoroutinesApi
open class AudioFileStorage(
    private val context: Context,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    usbDevicePermissions: USBDevicePermissions,
    private val sharedPrefs: SharedPrefs,
    crashReporting: CrashReporting
) {
    // TODO: this was originally intended to support multiple storage locations, but we only allow a single one now
    private val audioFileStorageLocations: MutableList<AudioFileStorageLocation> = mutableListOf()
    private var usbDeviceConnections: USBDeviceConnections = USBDeviceConnections(
        context, scope, usbDevicePermissions, sharedPrefs, crashReporting
    )
    private var dataSources = mutableListOf<MediaDataSource>()
    private val dataSourcesMutex = Mutex()
    private var isSuspended = false
    private val fileProducerChannels = mutableListOf<ReceiveChannel<FileLike>>()
    val storageObservers = mutableListOf<(StorageChange) -> Unit>()
    val mediaDevicesForTest = mutableListOf<MediaDevice>()
    private val recentDirToAlbumArtMap = DirectoryToAlbumArtMapCache()
    private class DirectoryToAlbumArtMapCache : LinkedHashMap<String, FileLike?>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FileLike?>?): Boolean {
            return size > MAX_NUM_ALBUM_ART_DIRS_TO_CACHE
        }
    }

    init {
        logger.debug(TAG, "Init AudioFileStorage()")
        cleanAlbumArtCache()
        initUSBObservers()
        try {
            initAssetsForEmulator()
        } catch (exc: NoSuchDeviceException) {
            logger.exception(TAG, exc.message.toString(), exc)
        }
    }

    private fun initUSBObservers() {
        usbDeviceConnections.registerForUSBIntents()
        // this callback is called from a coroutine
        usbDeviceConnections.deviceObservers.add { deviceChange ->
            if (deviceChange.error.isNotBlank()) {
                val storageChange = StorageChange(error = deviceChange.error)
                notifyObservers(storageChange)
                return@add
            }
            when (deviceChange.action) {
                DeviceAction.CONNECT -> deviceChange.device?.let {
                    addDevice(it)
                }
                DeviceAction.DISCONNECT -> deviceChange.device?.let {
                    setAllDataSourcesClosed()
                    detachStorageForDevice(it)
                    removeDevice(it)
                }
                else -> {
                    // ignore
                }
            }
        }
    }

    /**
     * This is only used to provide demo soundfiles to Google during automatic review of production builds
     */
    private fun initAssetsForEmulator() {
        if (!Util.isRunningInEmulator()) {
            return
        }
        if (!Util.isDebugBuild(context)) {
            logger.debug(TAG, "initAssetsForEmulator()")
            try {
                // agree to the legal disclaimer automatically in case this is automatically reviewed
                sharedPrefs.setLegalDisclaimerAgreed(context)
                val assetManager = context.assets
                val assetMediaDevice = AssetMediaDevice(assetManager)
                mediaDevicesForTest.add(assetMediaDevice)
            } catch (exc: Exception) {
                logger.exception(TAG, exc.message.toString(), exc)
            }
        } else if (BuildConfig.BUILD_TYPE == "emulatorSDCard") {
            logger.debug(TAG, "Loading SD card for testing in emulator")
            val sdCardMediaDevice = SDCardMediaDevice(SD_CARD_ID, "/many_files")
            mediaDevicesForTest.add(sdCardMediaDevice)
        }
    }

    private fun notifyObservers(storageChange: StorageChange) {
        logger.debug(TAG, "Notifying storage observers: $storageChange")
        storageObservers.forEach { it(storageChange) }
    }

    private fun addDevice(device: MediaDevice) {
        logger.debug(TAG, "Adding device to storage: ${device.getName()}")
        try {
            val storageLocation = createStorageLocationForDevice(device)
            logger.debug(TAG, "Created storage location: $storageLocation")
            if (audioFileStorageLocations.size > 0) {
                logger.warning(TAG, "Already a device in storage, clearing: ${audioFileStorageLocations}}")
                audioFileStorageLocations.clear()
            }
            audioFileStorageLocations.add(storageLocation)
            val storageChange = StorageChange(storageLocation.storageID, StorageAction.ADD)
            notifyObservers(storageChange)
        } catch (exc: RuntimeException) {
            logger.exception(TAG, exc.message.toString(), exc)
        }
    }

    private fun createStorageLocationForDevice(device: MediaDevice): AudioFileStorageLocation {
        return when (device) {
            is USBMediaDevice -> {
                USBDeviceStorageLocation(device)
            }
            is SDCardMediaDevice -> {
                SDCardStorageLocation(device)
            }
            is AssetMediaDevice -> {
                AssetStorageLocation(device)
            }
            else -> {
                throw RuntimeException("Unhandled device type when creating storage location: $device")
            }
        }
    }

    private fun removeDevice(device: MediaDevice) {
        logger.debug(TAG, "Removing device from storage: ${device.getName()}")
        val storageLocations: List<AudioFileStorageLocation> = audioFileStorageLocations.filter { it.device == device }
        logger.debug(TAG, "audioFileStorageLocations at start of removeDevice(): $audioFileStorageLocations")
        if (storageLocations.isEmpty()) {
            logger.warning(TAG, "Device ${device.getID()} is not in storage (storage is empty)")
            val storageChange = StorageChange(id = "", StorageAction.REMOVE)
            notifyObservers(storageChange)
            return
        }
        val storageLocation = storageLocations.first()
        // we first notify the observers before removing the storage location, the observer will access it
        val storageChange = StorageChange(storageLocation.storageID, StorageAction.REMOVE)
        notifyObservers(storageChange)
        // TODO: the initial design of the app allowed for multiple audio file storage locations in parallel, I
        //  kinda of gave up on that midway, should re-design the classes a bit
        // (check why calling audioFileStorageLocations.remove(storageLocation) does not work)
        audioFileStorageLocations.clear()
        logger.debug(TAG, "audioFileStorageLocations cleared at end of removeDevice()")
    }

    private fun detachStorageForDevice(device: MediaDevice) {
        logger.debug(TAG, "Setting storage location for device as already detached: ${device.getName()}")
        val storageLocations: List<AudioFileStorageLocation> = audioFileStorageLocations.filter { it.device == device }
        if (storageLocations.isEmpty()) {
            return
        }
        storageLocations.first().setDetached()
    }

    fun updateAttachedDevices() {
        val isDebugBuild = Util.isDebugBuild(context)
        val isInEmulator = Util.isRunningInEmulator()
        usbDeviceConnections.updateAttachedDevices()
        if (isDebugBuild) {
            val sdCardDevicePermissions = SDCardDevicePermissions(context)
            if (!sdCardDevicePermissions.isPermitted()) {
                logger.warning(TAG, "We do not yet have permission to access an SD card")
                return
            }
            mediaDevicesForTest.forEach {
                addDevice(it)
            }
        } else {
            if (isInEmulator) {
                // release builds in emulator is likely how Google is testing the app during Play Store approval
                mediaDevicesForTest.forEach {
                    addDevice(it)
                }
            }
        }
    }

    /**
     * Traverses all files/directories in all storages and produces a list of audio files
     */
    @ExperimentalCoroutinesApi
    fun indexStorageLocations(storageIDs: List<String>): ReceiveChannel<FileLike> {
        if (!storageIDs.all { isStorageIDKnown(it) }) {
            throw RuntimeException("Unknown storage id(s) given in: $storageIDs")
        }
        fileProducerChannels.clear()
        storageIDs.forEach {
            logger.debug(TAG, "Creating file producer channel for storage: $it")
            val storageLoc = getStorageLocationForID(it)
            val rootDirectory = Directory(storageLoc.getRootURI())
            val fileChannel = storageLoc.indexAudioFiles(rootDirectory, scope, dispatcher)
            storageLoc.indexingStatus = IndexingStatus.INDEXING
            fileProducerChannels.add(fileChannel)
        }
        logger.debug(TAG, "Merging file producer channels: $fileProducerChannels")
        return scope.produce(dispatcher) {
            fileProducerChannels.forEach { channel ->
                channel.consumeEach {
                    logger.verbose(TAG, "Channel produced audiofile: $it")
                    if (isClosedForSend) {
                        logger.error(TAG, "File producer channel was closed")
                        return@consumeEach
                    }
                    send(it)
                }
            }
        }
    }

    fun cancelIndexing(cause: CancellationException? = null) {
        logger.debug(TAG, "cancelIndexing()")
        audioFileStorageLocations.forEach {
            it.cancelIndexAudioFiles()
        }
        fileProducerChannels.forEach {
            logger.debug(TAG, "Cancelling audio file producer channel: $it")
            it.cancel(cause)
        }
        fileProducerChannels.clear()
    }

    private fun isStorageIDKnown(storageID: String): Boolean {
        return audioFileStorageLocations.any { it.storageID == storageID }
    }

    fun getStorageLocationForID(storageID: String): AudioFileStorageLocation {
        if (!isStorageIDKnown(storageID)) {
            throw RuntimeException("Unknown storage id: $storageID")
        }
        return audioFileStorageLocations.first { it.storageID == storageID }
    }

    fun getPrimaryStorageLocation(): AudioFileStorageLocation {
        if (audioFileStorageLocations.isEmpty()) {
            throw NoSuchElementException("No storage locations")
        }
        return audioFileStorageLocations.first()
    }

    suspend fun getDataSourceForAudioFile(audioFile: AudioFile): MediaDataSource {
        cleanClosedDataSources()
        val dataSource = getStorageLocationForID(audioFile.storageID).getDataSourceForURI(audioFile.uri)
        dataSourcesMutex.withLock {
            dataSources.add(dataSource)
        }
        return dataSource
    }

    suspend fun getDataSourceForURI(uri: Uri): MediaDataSource {
        cleanClosedDataSources()
        val dataSource = getStorageLocationForURI(uri).getDataSourceForURI(uri)
        dataSourcesMutex.withLock {
            dataSources.add(dataSource)
        }
        return dataSource
    }

    suspend fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource {
        cleanClosedDataSources()
        val dataSource = getStorageLocationForURI(uri).getBufferedDataSourceForURI(uri)
        dataSourcesMutex.withLock {
            dataSources.add(dataSource)
        }
        return dataSource
    }

    private suspend fun cleanClosedDataSources() {
        dataSourcesMutex.withLock {
            dataSources = dataSources.filterNot {
                when (it) {
                    is USBAudioDataSource -> it.isClosed
                    is SDCardAudioDataSource -> it.isClosed
                    else -> true
                }
            }.toMutableList()
        }
    }

    private fun setAllDataSourcesClosed() {
        runBlocking(dispatcher) {
            dataSourcesMutex.withLock {
                dataSources.forEach {
                    when (it) {
                        is USBAudioDataSource -> it.isClosed = true
                        is SDCardAudioDataSource -> it.isClosed = true
                        else -> throw NotImplementedError()
                    }
                }
                dataSources = mutableListOf()
            }
        }
    }

    private fun getStorageLocationForURI(uri: Uri): AudioFileStorageLocation {
        val audioFile = AudioFile(uri)
        return getStorageLocationForID(audioFile.storageID)
    }

    fun prepareForEject() {
        audioFileStorageLocations.forEach {
            val storageChange = StorageChange(it.storageID, StorageAction.REMOVE)
            notifyObservers(storageChange)
        }
        audioFileStorageLocations.clear()
        usbDeviceConnections.updateUSBStatusInSettings(R.string.setting_USB_status_ejected)
    }

    suspend fun enableLogToUSBPreference() {
        usbDeviceConnections.enableLogToUSBPreference()
    }

    fun disableLogToUSBPreference() {
        usbDeviceConnections.disableLogToUSBPreference()
    }

    fun disableLogToUSB() {
        usbDeviceConnections.disableLogToUSB()
    }

    fun getNumAvailableDevices(): Int {
        return usbDeviceConnections.getNumAttachedPermittedDevices() + mediaDevicesForTest.size
    }

    fun setIndexingStatus(storageID: String, indexingStatus: IndexingStatus) {
        val storageLoc = getStorageLocationForID(storageID)
        storageLoc.indexingStatus = indexingStatus
    }

    fun shutdown() {
        logger.debug(TAG, "shutdown()")
        usbDeviceConnections.unregisterForUSBIntents()
        closeDataSources()
        audioFileStorageLocations.forEach {
            it.cancelIndexAudioFiles()
            it.close()
        }
        audioFileStorageLocations.clear()
        cleanAlbumArtCache()
    }

    fun suspend() {
        logger.debug(TAG, "suspend()")
        closeDataSources()
        audioFileStorageLocations.forEach {
            it.cancelIndexAudioFiles()
            it.close()
        }
        audioFileStorageLocations.clear()
        cleanAlbumArtCache()
        usbDeviceConnections.isSuspended = true
        isSuspended = true
    }

    fun wakeup() {
        isSuspended = false
        usbDeviceConnections.isSuspended = false
    }

    private fun closeDataSources() {
        runBlocking(dispatcher) {
            dataSourcesMutex.withLock {
                dataSources.forEach {
                    it.close()
                }
                dataSources.clear()
            }
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun getIndexingStatus(): List<IndexingStatus> {
        val indexingStatusList = mutableListOf<IndexingStatus>()
        audioFileStorageLocations.forEach {
            indexingStatusList.add(it.indexingStatus)
        }
        return indexingStatusList
    }

    /**
     * This will determine what files in the browse tree will look like in the GUI
     */
    fun createAudioFileDescription(file: AudioFile): MediaDescriptionCompat {
        val contentHierarchyID = ContentHierarchyID(ContentHierarchyType.FILE)
        contentHierarchyID.path = file.uri.path.toString()
        val builder = MediaDescriptionCompat.Builder().apply {
            setTitle(file.name)
            setIconUri(Uri.parse(
                RESOURCE_ROOT_URI + context.resources.getResourceEntryName(R.drawable.baseline_insert_drive_file_24)
            ))
            setMediaId(ContentHierarchyElement.serialize(contentHierarchyID))
            setMediaUri(file.uri)
        }
        return builder.build()
    }

    /**
     * This will determine what directories in the browse tree will look like in the GUI
     */
    fun createDirectoryDescription(directory: Directory): MediaDescriptionCompat {
        val contentHierarchyID = ContentHierarchyID(ContentHierarchyType.DIRECTORY)
        contentHierarchyID.path = directory.uri.path.toString()
        val builder = MediaDescriptionCompat.Builder().apply {
            setTitle(directory.name)
            setIconUri(Uri.parse(
                RESOURCE_ROOT_URI + context.resources.getResourceEntryName(R.drawable.baseline_folder_24)
            ))
            setMediaId(ContentHierarchyElement.serialize(contentHierarchyID))
            setMediaUri(directory.uri)
        }
        return builder.build()
    }

    fun createPlaylistFileDescription(file: PlaylistFile): MediaDescriptionCompat {
        val contentHierarchyID = ContentHierarchyID(ContentHierarchyType.PLAYLIST)
        contentHierarchyID.path = file.uri.path.toString()
        val builder = MediaDescriptionCompat.Builder().apply {
            setTitle(file.name)
            setIconUri(Uri.parse(
                RESOURCE_ROOT_URI + context.resources.getResourceEntryName(R.drawable.baseline_description_24)
            ))
            setMediaId(ContentHierarchyElement.serialize(contentHierarchyID))
            setMediaUri(file.uri)
        }
        return builder.build()
    }

    fun areAnyStoragesAvail(): Boolean {
        return audioFileStorageLocations.isNotEmpty()
    }

    fun getAlbumArtFileInDirectoryForURI(uri: Uri): FileLike? {
        val directory = Util.getParentPath(AudioFile(uri).path)
        logger.debug(TAG, "Looking for album art in directory: $directory")
        if (recentDirToAlbumArtMap.containsKey(directory)) {
            val albumArtInCache = recentDirToAlbumArtMap[directory]
            logger.verbose(TAG, "Returning album art for $directory from cache: ${albumArtInCache?.uri}")
            return albumArtInCache
        }
        val storageLocation = getStorageLocationForURI(uri)
        val directoryURI = Util.createURIForPath(storageLocation.storageID, directory)
        val directoryContents: List<FileLike> = storageLocation.getDirectoryContents(Directory(directoryURI))
        val imageFiles =
            directoryContents.filter { it.name.matches(Regex(".*\\.(jpg|png)$", RegexOption.IGNORE_CASE)) }.reversed()
        val albumArtFile = imageFiles.firstOrNull {
            it.name.matches(Regex("^(cover|folder|front|index|albumart.*|art\\.).*", RegexOption.IGNORE_CASE))
        }
        recentDirToAlbumArtMap[directory] = albumArtFile
        return albumArtFile
    }

    fun cleanAlbumArtCache() {
        recentDirToAlbumArtMap.clear()
    }

    suspend fun getByteArrayForURI(uri: Uri): ByteArray {
        val storageLocation = getStorageLocationForURI(uri)
        return storageLocation.getByteArrayForURI(uri)
    }

    suspend fun getInputStream(uri: Uri): LockableInputStream {
        val storageLocation = getStorageLocationForURI(uri)
        return storageLocation.getInputStreamForURI(uri)
    }

    fun requestUSBPermissionIfMissing() {
        usbDeviceConnections.requestUSBPermissionIfMissing()
    }

}
