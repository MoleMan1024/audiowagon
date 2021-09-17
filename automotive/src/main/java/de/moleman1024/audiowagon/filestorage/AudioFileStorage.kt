/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import de.moleman1024.audiowagon.GUI
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.authorization.SDCardDevicePermissions
import de.moleman1024.audiowagon.authorization.USBDevicePermissions
import de.moleman1024.audiowagon.filestorage.sd.SDCardAudioDataSource
import de.moleman1024.audiowagon.filestorage.sd.SDCardMediaDevice
import de.moleman1024.audiowagon.filestorage.sd.SDCardStorageLocation
import de.moleman1024.audiowagon.filestorage.usb.USBAudioDataSource
import de.moleman1024.audiowagon.filestorage.usb.USBDeviceConnections
import de.moleman1024.audiowagon.filestorage.usb.USBDeviceStorageLocation
import de.moleman1024.audiowagon.filestorage.usb.USBMediaDevice
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce


private const val TAG = "AudioFileStor"
private val logger = Logger

/**
 * This class manages storage locations and provides functions to index them for audio files
 *
 * We need to implement this ourselves, none of the stuff in https://developer.android.com/training/data-storage will
 * work for USB
 */
open class AudioFileStorage(
    private val context: Context,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    usbDevicePermissions: USBDevicePermissions,
    private val gui: GUI
) {
    private val audioFileStorageLocations: MutableList<AudioFileStorageLocation> = mutableListOf()
    private var usbDeviceConnections: USBDeviceConnections = USBDeviceConnections(
        context, scope, dispatcher, usbDevicePermissions, gui
    )
    private var dataSources = mutableListOf<MediaDataSource>()
    val storageObservers = mutableListOf<(StorageChange) -> Unit>()
    val mediaDevicesForTest = mutableListOf<MediaDevice>()

    init {
        initUSBObservers()
        initSDCardForDebugBuild()
    }

    private fun initUSBObservers() {
        usbDeviceConnections.registerForUSBIntents()
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
            }
        }
    }

    private fun initSDCardForDebugBuild() {
        if (Util.isDebugBuild(context)) {
            try {
                val sdCardMediaDevice = SDCardMediaDevice("A749-5ABA")
                mediaDevicesForTest.add(sdCardMediaDevice)
            } catch (exc: RuntimeException) {
                logger.warning(TAG, exc.message.toString())
            }
        }
    }

    private fun notifyObservers(storageChange: StorageChange) {
        storageObservers.forEach { it(storageChange) }
    }

    private fun addDevice(device: MediaDevice) {
        logger.debug(TAG, "Adding device to storage: ${device.getLongName()}")
        try {
            val storageLocation = createStorageLocationForDevice(device)
            logger.debug(TAG, "Created storage location: $storageLocation")
            if (audioFileStorageLocations.contains(storageLocation)) {
                logger.debug(TAG, "Device is already in storage: ${device.getLongName()}")
                return
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
            else -> {
                throw RuntimeException("Unhandled device type when creating storage location: $device")
            }
        }
    }

    private fun removeDevice(device: MediaDevice) {
        logger.debug(TAG, "Removing device from storage: ${device.getLongName()}")
        val storageLocations: List<AudioFileStorageLocation> = audioFileStorageLocations.filter { it.device == device }
        logger.debug(TAG, "audioFileStorageLocations at start of removeDevice(): $audioFileStorageLocations")
        if (storageLocations.isEmpty()) {
            logger.warning(TAG, "Device ${device.getID()} is not in storage (storage is empty)")
            val storageChange = StorageChange(id = "", StorageAction.REMOVE)
            notifyObservers(storageChange)
            return
        }
        val storageLocation = storageLocations.first()
        // we first notify the observers before remove the storage location, the observer will access it
        val storageChange = StorageChange(storageLocation.storageID, StorageAction.REMOVE)
        notifyObservers(storageChange)
        // TODO: the initial design of the app allowed for multiple audio file storage locations in parallel, I
        //  kinda of gave up on that midway, should re-design the classes a bit
        // (check why calling audioFileStorageLocations.remove(storageLocation) does not work)
        audioFileStorageLocations.clear()
        logger.debug(TAG, "audioFileStorageLocations cleared at end of removeDevice()")
    }

    private fun detachStorageForDevice(device: MediaDevice) {
        logger.debug(TAG, "Setting storage location for device as already detached: ${device.getLongName()}")
        val storageLocations: List<AudioFileStorageLocation> = audioFileStorageLocations.filter { it.device == device }
        if (storageLocations.isEmpty()) {
            return
        }
        storageLocations.first().setDetached()
    }

    fun updateConnectedDevices() {
        usbDeviceConnections.updateConnectedDevices()
        if (Util.isDebugBuild(context)) {
            val sdCardDevicePermissions = SDCardDevicePermissions(context)
            if (!sdCardDevicePermissions.isPermitted()) {
                logger.warning(TAG, "We do not yet have permission to access an SD card")
                return
            }
            mediaDevicesForTest.forEach {
                addDevice(it)
            }
        }
    }

    /**
     * Traverses all files/directories in all storages and produces a list of audio files
     */
    @ExperimentalCoroutinesApi
    fun indexStorageLocations(storageIDs: List<String>): ReceiveChannel<AudioFile> {
        if (!storageIDs.all { isStorageIDKnown(it) }) {
            throw RuntimeException("Unknown storage id(s) given in: $storageIDs")
        }
        val audioFileProducerChannels = mutableListOf<ReceiveChannel<AudioFile>>()
        storageIDs.forEach {
            logger.debug(TAG, "Creating audio file producer channel for storage: $it")
            val storageLoc = getStorageLocationForID(it)
            val audioFileChannel = storageLoc.indexAudioFiles(scope)
            storageLoc.indexingStatus = IndexingStatus.INDEXING
            audioFileProducerChannels.add(audioFileChannel)
        }
        logger.debug(TAG, "Merging audio file producer channels: $audioFileProducerChannels")
        // read about coroutines scope/context: https://elizarov.medium.com/coroutine-context-and-scope-c8b255d59055
        return scope.produce(dispatcher) {
            audioFileProducerChannels.forEach { channel ->
                channel.consumeEach {
                    logger.verbose(TAG, "Channel produced audiofile: $it")
                    if (isClosedForSend) {
                        logger.error(TAG, "Audio file producer channel was closed")
                        return@consumeEach
                    }
                    send(it)
                }
            }
        }
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

    fun getDataSourceForAudioFile(audioFile: AudioFile): MediaDataSource {
        cleanClosedDataSources()
        val dataSource = getStorageLocationForID(audioFile.getStorageID()).getDataSourceForURI(audioFile.uri)
        dataSources.add(dataSource)
        return dataSource
    }

    fun getDataSourceForURI(uri: Uri): MediaDataSource {
        cleanClosedDataSources()
        val dataSource = getStorageLocationForURI(uri).getDataSourceForURI(uri)
        dataSources.add(dataSource)
        return dataSource
    }

    fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource {
        cleanClosedDataSources()
        val dataSource = getStorageLocationForURI(uri).getBufferedDataSourceForURI(uri)
        dataSources.add(dataSource)
        return dataSource
    }

    @Synchronized
    private fun cleanClosedDataSources() {
        dataSources = dataSources.filterNot {
            when (it) {
                is USBAudioDataSource -> it.isClosed
                is SDCardAudioDataSource -> it.isClosed
                else -> true
            }
        }.toMutableList()
    }

    @Synchronized
    private fun setAllDataSourcesClosed() {
        dataSources.forEach {
            when (it) {
                is USBAudioDataSource -> it.isClosed = true
                is SDCardAudioDataSource -> it.isClosed = true
                else -> throw NotImplementedError()
            }
        }
        dataSources = mutableListOf()
    }

    private fun getStorageLocationForURI(uri: Uri): AudioFileStorageLocation {
        val audioFile = AudioFile(uri)
        return getStorageLocationForID(audioFile.getStorageID())
    }

    fun removeAllDevicesFromStorage() {
        audioFileStorageLocations.forEach {
            val storageChange = StorageChange(it.storageID, StorageAction.REMOVE)
            notifyObservers(storageChange)
        }
        audioFileStorageLocations.clear()
        usbDeviceConnections.updateUSBStatusInSettings(R.string.setting_USB_status_ejected)
    }

    fun enableLogToUSB() {
        usbDeviceConnections.enableLogToUSB()
    }

    fun disableLogToUSB() {
        usbDeviceConnections.disableLogToUSB()
    }

    fun getNumConnectedDevices(): Int {
        return usbDeviceConnections.getNumConnectedDevices()
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
            it.close()
        }
    }

    fun notifyIndexingIssues() {
        val directoriesWithIndexingIssues = mutableListOf<String>()
        audioFileStorageLocations.forEach {
            directoriesWithIndexingIssues += it.getDirectoriesWithIndexingIssues()
        }
        if (directoriesWithIndexingIssues.size > 0) {
            gui.showErrorToastMsg(context.getString(R.string.setting_USB_status_too_many_files_in_dir))
            usbDeviceConnections.updateUSBStatusInSettings(R.string.setting_USB_status_too_many_files_in_dir)
            directoriesWithIndexingIssues.forEach {
                logger.warning(TAG, "Could not index audio files in directory (too many files in directory): $it")
            }
        }
    }

    @Synchronized
    private fun closeDataSources() {
        dataSources.forEach {
            it.close()
        }
        dataSources.clear()
    }

}
