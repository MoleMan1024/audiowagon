/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.SharedPrefs
import de.moleman1024.audiowagon.SingletonCoroutine
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.authorization.USBDevicePermissions
import de.moleman1024.audiowagon.enums.DeviceAction
import de.moleman1024.audiowagon.enums.USBPermission
import de.moleman1024.audiowagon.exceptions.DeviceIgnoredException
import de.moleman1024.audiowagon.exceptions.DeviceNotApplicableException
import de.moleman1024.audiowagon.exceptions.DeviceNotCompatible
import de.moleman1024.audiowagon.exceptions.DriveAlmostFullException
import de.moleman1024.audiowagon.exceptions.NoPartitionsException
import de.moleman1024.audiowagon.exceptions.NoSuchDeviceException
import de.moleman1024.audiowagon.filestorage.data.DeviceChange
import de.moleman1024.audiowagon.log.CrashReporting
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

private const val TAG = "USBDevConn"
private val logger = Logger

/**
 * The USB device connection is done via the USB host mode, see
 * https://developer.android.com/guide/topics/connectivity/usb/host
 * supported by the libaums library to achieve FAT32 filesystem access without rooting the Android device,
 * see https://github.com/magnusja/libaums
 */
class USBDeviceConnections(
    private val context: Context,
    val scope: CoroutineScope,
    private val usbDevicePermissions: USBDevicePermissions,
    private val sharedPrefs: SharedPrefs,
    private val crashReporting: CrashReporting
) {
    val deviceObservers = mutableListOf<suspend (DeviceChange) -> Unit>()
    private var isLogToUSBPreferenceSet = false
    private val attachedAndPermittedDevices = mutableListOf<USBMediaDevice>()
    var isSuspended = false
    val isUpdatingDevices = AtomicBoolean()
    val isAnyDeviceAttached = AtomicBoolean()
    val isAnyDevicePermitted = AtomicBoolean()

    // use a single thread here to avoid race conditions updating the USB attached/detached status from multiple threads
    private val singleThreadDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val updateAttachedDevicesSingletonCoroutine =
        SingletonCoroutine("updAttdDevices", singleThreadDispatcher, scope.coroutineContext, crashReporting)
    private val usbAttachedSingletonCoroutine =
        SingletonCoroutine("USBAttached", singleThreadDispatcher, scope.coroutineContext, crashReporting)
    private val usbAttachedDelayedSingletonCoroutine =
        SingletonCoroutine("USBAttachedDelay", singleThreadDispatcher, scope.coroutineContext, crashReporting)
    private val usbDetachedSingletonCoroutine =
        SingletonCoroutine("USBDetached", singleThreadDispatcher, scope.coroutineContext, crashReporting)
    private val usbPermissionSingletonCoroutine =
        SingletonCoroutine("USBPermission", singleThreadDispatcher, scope.coroutineContext, crashReporting)

    init {
        logger.debug(TAG, "Init USBDeviceConnections()")
        isUpdatingDevices.set(false)
        val logToUSBPreference = sharedPrefs.isLogToUSBEnabled(context)
        if (logToUSBPreference) {
            isLogToUSBPreferenceSet = true
        }
        updateUSBStatusInSettings(R.string.setting_USB_status_not_connected)
    }

    fun onBroadcastUSBDeviceAttached(usbMediaDevice: USBMediaDevice) {
        isAnyDeviceAttached.set(true)
        usbAttachedDelayedSingletonCoroutine.launch {
            if (isUpdatingDevices.get()) {
                logger.debug(TAG, "Cancelling onBroadcastUSBDeviceAttached($usbMediaDevice), " +
                        "USB devices already updating")
                return@launch
            }
            notifyObservers(DeviceChange(null, DeviceAction.REFRESH))
            // wait a bit to for the USB driver to settle down in case of issues and to give
            // USBDummyActivity a chance to catch this instead and send ACTION_USB_ATTACHED
            val delayTimeMS = 4000L
            val now = Util.getLocalDateTimeNowInstant()
            logger.debug(
                Util.TAGCRT(TAG, coroutineContext),
                "Delaying to process USB device attached intent until ${
                    Util.getLocalDateTimeNowInstant().plusMillis(delayTimeMS)
                }"
            )
            delay(delayTimeMS)
            if (isUpdatingDevices.get()) {
                logger.debug(TAG, "Cancelling onBroadcastUSBDeviceAttached($usbMediaDevice) after delay, " +
                        "USB devices already updating")
                return@launch
            }
            isUpdatingDevices.set(true)
            logger.debug(
                Util.TAGCRT(TAG, coroutineContext),
                "Handling USB device attached event that was delayed at $now"
            )
            onAttachedUSBMassStorageDeviceFound(usbMediaDevice)
            // isUpdatingDevices will be updated somewhere inside above method
        }
    }

    fun onBroadcastUSBDeviceDetached(usbMediaDevice: USBMediaDevice) {
        isUpdatingDevices.set(false)
        isAnyDeviceAttached.set(false)
        usbDetachedSingletonCoroutine.launch {
            usbAttachedDelayedSingletonCoroutine.cancel()
            usbAttachedSingletonCoroutine.cancel()
            updateAttachedDevicesSingletonCoroutine.cancel()
            onUSBDeviceDetached(usbMediaDevice)
        }
    }

    fun onBroadcastUSBAttached(usbMediaDevice: USBMediaDevice) {
        usbAttachedSingletonCoroutine.launch {
            usbAttachedDelayedSingletonCoroutine.cancel()
            onAttachedUSBMassStorageDeviceFound(usbMediaDevice)
        }
    }

    fun onBroadcastUSBPermissionChange(intent: Intent, usbMediaDevice: USBMediaDevice) {
        usbPermissionSingletonCoroutine.launch {
            if (!isDeviceAttached(usbMediaDevice)) {
                logger.warning(
                    Util.TAGCRT(TAG, coroutineContext),
                    "Received permission change for USB device that is not attached"
                )
                notifyObservers(DeviceChange(null, DeviceAction.REFRESH))
                return@launch
            }
            usbDevicePermissions.onUSBPermissionChanged(intent, usbMediaDevice)
            onUSBPermissionChanged(usbMediaDevice)
        }
    }

    fun updateAttachedDevices() {
        if (isUpdatingDevices.get()) {
            logger.debug(TAG, "Ignoring updateAttachedDevices(), USB devices already updating")
            return
        }
        updateAttachedDevicesSingletonCoroutine.launch {
            isUpdatingDevices.set(true)
            for (usbMediaDevice in getAttachedUSBMassStorageDevices()) {
                onAttachedUSBMassStorageDeviceFound(usbMediaDevice)
            }
            isUpdatingDevices.set(false)
            notifyObservers(DeviceChange(null, DeviceAction.REFRESH))
        }
    }

    private fun getAttachedUSBMassStorageDevices(): List<USBMediaDevice> {
        val filteredUSBDevices = getAttachedFilteredDevicesFromUSBManager()
        val filteredAndCompatibleUSBDevices = mutableListOf<USBMediaDevice>()
        for (usbMediaDevice in filteredUSBDevices) {
            try {
                checkIsCompatibleUSBMassStorage(usbMediaDevice)
                filteredAndCompatibleUSBDevices.add(usbMediaDevice)
            } catch (exc: Exception) {
                when (exc) {
                    is DeviceNotApplicableException, is DeviceNotCompatible -> {
                        logger.debug(TAG, exc.toString())
                    }

                    is RuntimeException -> {
                        logger.exception(TAG, "Exception when checking for compatible USB mass storage device", exc)
                    }

                    else -> throw exc
                }
            }
        }
        return filteredAndCompatibleUSBDevices
    }

    private fun checkIsCompatibleUSBMassStorage(usbDeviceWrapper: USBMediaDevice) {
        if (!usbDeviceWrapper.isMassStorageDevice()) {
            throw DeviceNotApplicableException("Not a mass storage device: $usbDeviceWrapper")
        }
        if (!usbDeviceWrapper.isCompatible()) {
            throw DeviceNotCompatible("Not compatible device: $usbDeviceWrapper")
        }
    }

    fun getUSBMassStorageDeviceFromIntent(intent: Intent): USBMediaDevice {
        val usbMediaDevice: USBMediaDevice? = try {
            val androidUSBDevice: UsbDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    ?: throw NoSuchDeviceException("No USB device in intent")
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    ?: throw NoSuchDeviceException("No USB device in intent")
            }
            val usbDeviceProxy = AndroidUSBDeviceProxy(androidUSBDevice, getUSBManager())
            USBMediaDevice(context, usbDeviceProxy)
        } catch (_: Exception) {
            val mockUSBDevice: USBDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, USBDevice::class.java)
                    ?: throw NoSuchDeviceException("Cannot extract mock USB device for test")
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    ?: throw NoSuchDeviceException("Cannot extract mock USB device for test")
            }
            USBMediaDevice(context, mockUSBDevice)
        }
        if (usbMediaDevice?.isToBeIgnored() == true) {
            throw DeviceIgnoredException("USB device will be ignored")
        }
        checkIsCompatibleUSBMassStorage(usbMediaDevice!!)
        return usbMediaDevice
    }

    private fun getUSBManager(): UsbManager {
        return context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    private suspend fun onAttachedUSBMassStorageDeviceFound(device: USBMediaDevice) {
        logger.debug(Util.TAGCRT(TAG, coroutineContext), "onAttachedUSBMassStorageDeviceFound(device=$device)")
        val permission: USBPermission = usbDevicePermissions.getCurrentPermissionForDevice(device)
        if (permission == USBPermission.UNKNOWN) {
            updateUSBStatusInSettings(R.string.setting_USB_status_connected_no_permission)
            if (isSuspended) {
                logger.warning(
                    Util.TAGCRT(TAG, coroutineContext),
                    "Still suspended, can not show permission popup to user when screen is off"
                )
                return
            }
            usbDevicePermissions.requestPermissionForDevice(device)
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "Waiting for user to grant permission")
            return
        }
        onUSBDeviceWithPermissionAttached(device)
    }

    fun updateUSBStatusInSettings(resID: Int) {
        logger.debug(TAG, "updateUSBStatusInSettings(resID=$resID)")
        val currentID = sharedPrefs.getUSBStatusResID(context)
        if (currentID == resID) {
            logger.debug(TAG, "updateUSBStatusInSettings(): currentID=$currentID")
            return
        }
        sharedPrefs.setUSBStatusResID(context, resID)
    }

    /**
     * Prepare the filesystem on the attached USB device and notify that it is ready for usage
     */
    private suspend fun onUSBDeviceWithPermissionAttached(device: USBMediaDevice) {
        var attachedPermittedDevice = device
        if (!isDeviceInAttachedPermittedList(device)) {
            appendAttachedPermittedDevice(device)
        } else {
            attachedPermittedDevice = getAttachedPermittedDevice(device)
            logger.debug(
                Util.TAGCRT(TAG, coroutineContext),
                "Device already attached and permitted: $attachedPermittedDevice"
            )
        }
        // TODO: improve this, does not look nice with so many catch statements
        try {
            attachedPermittedDevice.initFilesystem()
        } catch (exc: IOException) {
            logger.exception(Util.TAGCRT(TAG, coroutineContext), "I/O exception when attaching USB drive", exc)
            updateUSBStatusInSettings(R.string.setting_USB_status_error)
            val deviceChange = if (exc.message != null) {
                DeviceChange(error = exc.message!!)
            } else {
                DeviceChange(error = context.getString(R.string.error_USB_init))
            }
            notifyObservers(deviceChange)
            crashReporting.logLastMessagesAndRecordException(exc)
            return
        } catch (exc: NoPartitionsException) {
            // libaums library only supports FAT32 (4 GB file size limit per file)
            logger.exception(Util.TAGCRT(TAG, coroutineContext), "No (supported) partitions on USB drive", exc)
            val deviceChange = DeviceChange(error = context.getString(R.string.error_no_filesystem))
            updateUSBStatusInSettings(R.string.setting_USB_status_not_compatible)
            notifyObservers(deviceChange)
            crashReporting.logLastMessagesAndRecordException(exc)
            return
        } catch (exc: IllegalStateException) {
            logger.exception(
                Util.TAGCRT(TAG, coroutineContext),
                "Illegal state when initiating filesystem, missing permission?",
                exc
            )
            notifyUSBInitError()
            crashReporting.logLastMessagesAndRecordException(exc)
            return
        } catch (exc: IndexOutOfBoundsException) {
            // I saw this once in Google Play Console, came from
            // com.github.mjdev.libaums.fs.fat32.FAT.getChain$libaums_release (FAT.kt:132)
            logger.exception(Util.TAGCRT(TAG, coroutineContext), exc.message.toString(), exc)
            notifyUSBInitError()
            crashReporting.logLastMessagesAndRecordException(exc)
            return
        }
        if (isLogToUSBPreferenceSet) {
            try {
                attachedPermittedDevice.enableLogging()
            } catch (exc: DriveAlmostFullException) {
                logger.exceptionLogcatOnly(Util.TAGCRT(TAG, coroutineContext), exc.message.toString(), exc)
            } catch (exc: RuntimeException) {
                logger.exception(Util.TAGCRT(TAG, coroutineContext), exc.message.toString(), exc)
            }
        }
        updateUSBStatusInSettings(R.string.setting_USB_status_ok)
        isUpdatingDevices.set(false)
        logger.info(
            Util.TAGCRT(TAG, coroutineContext),
            "Successfully initialized filesystem at: ${Util.getLocalDateTimeStringNow()} (${Util.getUptimeString()})"
        )
        val deviceChange = DeviceChange(attachedPermittedDevice, DeviceAction.CONNECT)
        notifyObservers(deviceChange)
    }

    private suspend fun notifyUSBInitError() {
        val deviceChange = DeviceChange(error = context.getString(R.string.error_USB_init))
        notifyObservers(deviceChange)
    }

    /**
     * Received after a USB device is unplugged
     */
    private suspend fun onUSBDeviceDetached(device: USBMediaDevice) {
        try {
            device.preventLoggingToDetachedDevice()
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "onUSBDeviceDetached: $device")
            val deviceChange = DeviceChange(device, DeviceAction.DISCONNECT)
            notifyObservers(deviceChange)
        } catch (exc: IOException) {
            val errorMsg = context.getString(R.string.error_IO)
            val deviceChange = DeviceChange(error = errorMsg)
            notifyObservers(deviceChange)
        } finally {
            device.closeMassStorageFilesystem()
            removeAttachedPermittedDevice(device)
            updateUSBStatusInSettings(R.string.setting_USB_status_not_connected)
            notifyObservers(DeviceChange(null, DeviceAction.REFRESH))
        }
    }

    private fun removeAttachedPermittedDevice(device: USBMediaDevice) {
        if (!isDeviceInAttachedPermittedList(device)) {
            logger.warning(TAG, "Device not in list of attached+permitted devices: ${device.getName()}")
            return
        }
        attachedAndPermittedDevices.remove(device)
    }

    private fun isDeviceInAttachedPermittedList(device: USBMediaDevice): Boolean {
        return attachedAndPermittedDevices.contains(device)
    }

    private fun getAttachedPermittedDevice(device: USBMediaDevice): USBMediaDevice {
        return attachedAndPermittedDevices.first { it == device }
    }

    private fun isDeviceAttached(usbMediaDevice: USBMediaDevice): Boolean {
        getAttachedFilteredDevicesFromUSBManager().forEach {
            if (it == usbMediaDevice) {
                logger.debug(TAG, "Device is attached: $usbMediaDevice")
                return true
            }
        }
        return false
    }

    private fun getAttachedFilteredDevicesFromUSBManager(): List<USBMediaDevice> {
        val filteredUSBDevices = mutableListOf<USBMediaDevice>()
        getUSBManager().deviceList.values.forEach { usbDevice ->
            logger.debug(TAG, "USBManager.deviceList has device @${usbDevice.hashCode()}: $usbDevice")
            val deviceProxy = AndroidUSBDeviceProxy(usbDevice, getUSBManager())
            val usbMediaDevice = USBMediaDevice(context, deviceProxy)
            if (!usbMediaDevice.isToBeIgnored()) {
                filteredUSBDevices.add(usbMediaDevice)
            }
        }
        logger.debug(TAG, "Found ${filteredUSBDevices.size} attached, not built-in USB device(s)")
        return filteredUSBDevices
    }

    /**
     * Received after user has granted/denied access to USB device
     */
    private suspend fun onUSBPermissionChanged(device: USBMediaDevice) {
        val permission: USBPermission = usbDevicePermissions.getCurrentPermissionForDevice(device)
        if (permission == USBPermission.DENIED) {
            logger.info(Util.TAGCRT(TAG, coroutineContext), "User has denied access to: ${device.getName()}")
            isUpdatingDevices.set(false)
            isAnyDevicePermitted.set(false)
            notifyObservers(DeviceChange(null, DeviceAction.REFRESH))
            return
        } else if (permission == USBPermission.UNKNOWN) {
            logger.error(Util.TAGCRT(TAG, coroutineContext), "Permission has not been updated for: ${device.getName()}")
            isUpdatingDevices.set(false)
            isAnyDevicePermitted.set(false)
            notifyObservers(DeviceChange(null, DeviceAction.REFRESH))
            return
        }
        isAnyDevicePermitted.set(true)
        onUSBDeviceWithPermissionAttached(device)
    }

    private fun appendAttachedPermittedDevice(device: USBMediaDevice) {
        if (isDeviceInAttachedPermittedList(device)) {
            logger.warning(TAG, "USB device already in list of attached+permitted devices: ${device.getName()}")
            return
        }
        attachedAndPermittedDevices.add(device)
    }

    // this will notify the AudioFileStorage
    private suspend fun notifyObservers(deviceChange: DeviceChange) {
        deviceObservers.forEach { it(deviceChange) }
    }

    fun getNumAttachedPermittedDevices(): Int {
        return attachedAndPermittedDevices.size
    }

    suspend fun enableLogToUSBPreference() {
        isLogToUSBPreferenceSet = true
        enableLogToUSB()
    }

    private suspend fun enableLogToUSB() {
        logger.verbose(Util.TAGCRT(TAG, coroutineContext), "enableLogToUSB()")
        if (attachedAndPermittedDevices.isEmpty()) {
            logger.warning(Util.TAGCRT(TAG, coroutineContext), "No USB device, cannot create logfile")
            return
        }
        try {
            attachedAndPermittedDevices[0].enableLogging()
        } catch (exc: DriveAlmostFullException) {
            throw exc
        } catch (exc: RuntimeException) {
            logger.exception(Util.TAGCRT(TAG, coroutineContext), exc.message.toString(), exc)
        }
    }

    fun disableLogToUSBPreference() {
        isLogToUSBPreferenceSet = false
        disableLogToUSB()
    }

    fun disableLogToUSB() {
        logger.verbose(TAG, "disableLogToUSB()")
        if (attachedAndPermittedDevices.isEmpty()) {
            logger.warning(TAG, "No USB device, cannot use a log file")
            return
        }
        attachedAndPermittedDevices[0].disableLogging()
    }

    fun requestUSBPermissionIfMissing() {
        if (isUpdatingDevices.get()) {
            logger.debug(TAG, "Cancelling requestUSBPermissionIfMissing(), USB devices already updating")
            return
        }
        val attachedDevices = getAttachedUSBMassStorageDevices()
        if (attachedDevices.isEmpty()) {
            logger.warning(TAG, "No USB device attached, cannot ask for permission")
            return
        }
        if (usbDevicePermissions.getCurrentPermissionForDevice(attachedDevices[0])
            in listOf(USBPermission.DENIED, USBPermission.UNKNOWN)
        ) {
            usbDevicePermissions.requestPermissionForDevice(attachedDevices[0])
        } else {
            logger.debug(TAG, "USB device already has permission")
        }
    }

    fun cancelCoroutines() {
        logger.debug(TAG, "cancelCoroutines()")
        updateAttachedDevicesSingletonCoroutine.cancel()
        usbAttachedSingletonCoroutine.cancel()
        usbAttachedDelayedSingletonCoroutine.cancel()
        usbDetachedSingletonCoroutine.cancel()
        usbPermissionSingletonCoroutine.cancel()
    }

}
