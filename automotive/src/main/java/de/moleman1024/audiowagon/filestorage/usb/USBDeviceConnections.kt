/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.SharedPrefs
import de.moleman1024.audiowagon.authorization.ACTION_USB_PERMISSION_CHANGE
import de.moleman1024.audiowagon.authorization.USBDevicePermissions
import de.moleman1024.audiowagon.authorization.USBPermission
import de.moleman1024.audiowagon.exceptions.*
import de.moleman1024.audiowagon.filestorage.DeviceAction
import de.moleman1024.audiowagon.filestorage.DeviceChange
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.*
import java.io.IOException

private const val TAG = "USBDevConn"
const val ACTION_USB_ATTACHED = "de.moleman1024.audiowagon.authorization.USB_ATTACHED"
const val ACTION_USB_UPDATE = "de.moleman1024.audiowagon.authorization.USB_UPDATE"
private val USB_ACTIONS = listOf(
    ACTION_USB_ATTACHED,
    ACTION_USB_UPDATE,
    UsbManager.ACTION_USB_DEVICE_ATTACHED,
    UsbManager.ACTION_USB_DEVICE_DETACHED,
    ACTION_USB_PERMISSION_CHANGE
)
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
    private val dispatcher: CoroutineDispatcher,
    private val usbDevicePermissions: USBDevicePermissions
) {
    val deviceObservers = mutableListOf<(DeviceChange) -> Unit>()
    private var isLogToUSB = false
    private val connectedDevices = mutableListOf<USBMediaDevice>()
    private var onAttachedUSBDeviceFoundJob: Job? = null
    var isSuspended = false

    /**
     * Received when a USB device is (un)plugged. This can take a few seconds after plugging in the USB flash drive.
     * Runs in main thread, do not perform long running tasks here:
     * https://developer.android.com/guide/components/broadcasts#security-and-best-practices
     */
    private val usbBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            logger.debug(TAG, "onReceive($context; $intent)")
            if (intent == null) {
                throw RuntimeException("No intent to retrieve USB device from")
            }
            if (intent.action !in USB_ACTIONS) {
                return
            }
            if (intent.action == ACTION_USB_UPDATE) {
                updateConnectedDevices()
                return
            }
            val usbDeviceWrapper: USBMediaDevice
            try {
                usbDeviceWrapper = getUSBMassStorageDeviceFromIntent(intent)
            } catch (exc: DeviceIgnoredException) {
                // one of the built-in USB devices (e.g. bluetooth dongle) has attached/detached, ignore these
                return
            } catch (exc: RuntimeException) {
                logger.exception(TAG, exc.message.toString(), exc)
                return
            }
            try {
                when (intent.action) {
                    ACTION_USB_ATTACHED -> {
                        runBlocking(dispatcher) {
                            onAttachedUSBDeviceFoundJob?.let {
                                logger.debug(TAG, "Cancelling onAttachedUSBDeviceFoundJob")
                                it.cancelAndJoin()
                            }
                            onAttachedUSBDeviceFound(usbDeviceWrapper)
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        onAttachedUSBDeviceFoundJob = scope.launch(dispatcher) {
                            // wait a bit to give USBDummyActivity a chance to catch this instead and send
                            // ACTION_USB_ATTACHED (see above)
                            delay(400)
                            onAttachedUSBDeviceFound(usbDeviceWrapper)
                            onAttachedUSBDeviceFoundJob = null
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> onUSBDeviceDetached(usbDeviceWrapper)
                    ACTION_USB_PERMISSION_CHANGE -> {
                        if (!isDeviceConnected(usbDeviceWrapper)) {
                            logger.warning(TAG, "Received permission change for USB device that is not connected")
                            return
                        }
                        usbDevicePermissions.onUSBPermissionChanged(intent, usbDeviceWrapper)
                        onUSBPermissionChanged(usbDeviceWrapper)
                    }
                }
            } catch (exc: RuntimeException) {
                logger.exception(TAG, exc.message.toString(), exc)
                return
            }
        }
    }

    init {
        val logToUSBPreference = SharedPrefs.isLogToUSBEnabled(context)
        if (logToUSBPreference) {
            isLogToUSB = true
        }
    }

    fun updateConnectedDevices() {
        val filteredUSBDevices = getConnectedFilteredDevicesFromUSBManager()
        if (filteredUSBDevices.isEmpty()) {
            updateUSBStatusInSettings(R.string.setting_USB_status_not_connected)
            return
        }
        for (usbMediaDevice in filteredUSBDevices) {
            try {
                checkIsCompatibleUSBMassStorage(usbMediaDevice)
            } catch (exc: Exception) {
                when (exc) {
                    is DeviceNotApplicableException, is DeviceNotCompatible -> {
                        logger.debug(TAG, exc.toString())
                        continue
                    }
                    is RuntimeException -> {
                        logger.exception(TAG, "Exception when checking for compatible USB mass storage device", exc)
                    }
                    else -> throw exc
                }
            }
            onAttachedUSBDeviceFound(usbMediaDevice)
        }
    }

    private fun checkIsCompatibleUSBMassStorage(usbDeviceWrapper: USBMediaDevice) {
        if (!usbDeviceWrapper.isMassStorageDevice()) {
            throw DeviceNotApplicableException("Not a mass storage device: $usbDeviceWrapper")
        }
        if (!usbDeviceWrapper.isCompatibleWithLib()) {
            throw DeviceNotCompatible("Not compatible device: $usbDeviceWrapper")
        }
    }

    private fun getUSBMassStorageDeviceFromIntent(intent: Intent): USBMediaDevice {
        val usbMediaDevice: USBMediaDevice? = try {
            val androidUSBDevice: UsbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                ?: throw NoSuchDeviceException("No USB device in intent")
            val usbDeviceProxy = USBDeviceProxy(androidUSBDevice, getUSBManager())
            USBMediaDevice(context, usbDeviceProxy)
        } catch (exc: ClassCastException) {
            val mockUSBDevice: USBDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                ?: throw NoSuchDeviceException("Cannot extract mock USB device for test")
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

    private fun onAttachedUSBDeviceFound(device: USBMediaDevice) {
        logger.debug(TAG, "onAttachedUSBDeviceFound: $device")
        val permission: USBPermission = usbDevicePermissions.getCurrentPermissionForDevice(device)
        usbDevicePermissions.setPermissionForDevice(device, permission)
        if (permission == USBPermission.UNKNOWN) {
            updateUSBStatusInSettings(R.string.setting_USB_status_connected_no_permission)
            if (isSuspended) {
                logger.warning(TAG, "Still suspended, can not show permission popup to user when screen is off")
                return
            }
            usbDevicePermissions.requestPermissionForDevice(device)
            logger.debug(TAG, "Waiting for user to grant permission")
            return
        }
        onUSBDeviceWithPermissionAttached(device)
    }

    fun updateUSBStatusInSettings(resID: Int) {
        logger.debug(TAG, "updateUSBStatusInSettings(resID=$resID)")
        val currentID = SharedPrefs.getUSBStatusResID(context)
        if (currentID == resID) {
            logger.debug(TAG, "updateUSBStatusInSettings(): currentID=$currentID")
            return
        }
        SharedPrefs.setUSBStatusResID(context, resID)
    }

    /**
     * Prepare the filesystem on the attached USB device and notify that it is ready for usage
     */
    private fun onUSBDeviceWithPermissionAttached(device: USBMediaDevice) {
        var connectedDevice = device
        if (!isDeviceInConnectedList(device)) {
            appendConnectedUSBDevice(device)
        } else {
            connectedDevice = getConnectedDevice(device)
        }
        // TODO: improve this, does not look nice with so many catch statements
        try {
            connectedDevice.initFilesystem()
        } catch (exc: IOException) {
            logger.exception(TAG, "I/O exception when attaching USB drive", exc)
            updateUSBStatusInSettings(R.string.setting_USB_status_error)
            val deviceChange = exc.message?.let { DeviceChange(error = it) }
            deviceChange?.let { notifyObservers(it) }
            return
        } catch (exc: NoPartitionsException) {
            // libaums library only supports FAT32 (4 GB file size limit per file)
            logger.exception(TAG, "No (supported) partitions on USB drive", exc)
            val deviceChange = DeviceChange(error = context.getString(R.string.error_no_filesystem))
            updateUSBStatusInSettings(R.string.setting_USB_status_not_compatible)
            notifyObservers(deviceChange)
            return
        } catch (exc: IllegalStateException) {
            logger.exception(TAG, "Illegal state when initiating filesystem, missing permission?", exc)
            val deviceChange = DeviceChange(error = context.getString(R.string.error_USB_init))
            notifyObservers(deviceChange)
            return
        }
        if (isLogToUSB) {
            try {
                connectedDevice.enableLogging()
            } catch (exc: DriveAlmostFullException) {
                logger.exceptionLogcatOnly(TAG, exc.message.toString(), exc)
            } catch (exc: RuntimeException) {
                logger.exception(TAG, exc.message.toString(), exc)
            }
        }
        updateUSBStatusInSettings(R.string.setting_USB_status_ok)
        val deviceChange = DeviceChange(connectedDevice, DeviceAction.CONNECT)
        notifyObservers(deviceChange)
    }

    /**
     * Received after a USB device is unplugged
     */
    private fun onUSBDeviceDetached(device: USBMediaDevice) {
        try {
            device.preventLoggingToDetachedDevice()
            logger.debug(TAG, "onUSBDeviceDetached: $device")
            val deviceChange = DeviceChange(device, DeviceAction.DISCONNECT)
            notifyObservers(deviceChange)
        } catch (exc: IOException) {
            val errorMsg = context.getString(R.string.error_IO)
            val deviceChange = DeviceChange(error = errorMsg)
            notifyObservers(deviceChange)
        } finally {
            device.closeMassStorageFilesystem()
            removeConnectedUSBDevice(device)
            usbDevicePermissions.removeDevice(device)
            updateUSBStatusInSettings(R.string.setting_USB_status_not_connected)
        }
    }

    private fun removeConnectedUSBDevice(device: USBMediaDevice) {
        if (!isDeviceInConnectedList(device)) {
            logger.warning(TAG, "Device not in list of connected devices: ${device.getName()}")
            return
        }
        connectedDevices.remove(device)
    }

    private fun isDeviceInConnectedList(device: USBMediaDevice): Boolean {
        return connectedDevices.contains(device)
    }

    private fun getConnectedDevice(device: USBMediaDevice): USBMediaDevice {
        return connectedDevices.first { it == device }
    }

    private fun isDeviceConnected(usbMediaDevice: USBMediaDevice): Boolean {
        getConnectedFilteredDevicesFromUSBManager().forEach {
            if (it == usbMediaDevice) {
                return true
            }
        }
        return false
    }

    private fun getConnectedFilteredDevicesFromUSBManager(): List<USBMediaDevice> {
        val filteredUSBDevices = mutableListOf<USBMediaDevice>()
        getUSBManager().deviceList.values.forEach {
            val deviceProxy = USBDeviceProxy(it, getUSBManager())
            val usbMediaDevice = USBMediaDevice(context, deviceProxy)
            if (!usbMediaDevice.isToBeIgnored()) {
                filteredUSBDevices.add(usbMediaDevice)
            }
        }
        logger.debug(TAG, "Found ${filteredUSBDevices.size} connected, not built-in USB device(s)")
        return filteredUSBDevices
    }

    /**
     * Received after user has granted/denied access to USB device
     */
    private fun onUSBPermissionChanged(device: USBMediaDevice) {
        val permission: USBPermission = usbDevicePermissions.getCurrentPermissionForDevice(device)
        usbDevicePermissions.setPermissionForDevice(device, permission)
        if (permission == USBPermission.DENIED) {
            logger.info(TAG, "User has denied access to: ${device.getName()}")
            return
        } else if (permission == USBPermission.UNKNOWN) {
            logger.error(TAG, "Permission has not been updated for: ${device.getName()}")
            return
        }
        onUSBDeviceWithPermissionAttached(device)
    }

    fun registerForUSBIntents() {
        logger.debug(TAG, "Registering at context to receive broadcast intents")
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_ATTACHED)
            addAction(ACTION_USB_UPDATE)
            // USB_DEVICE_ATTACHED is not desired here, it should be handled by manifest. However that does not work
            // on Polestar 2 car. Works fine on Pixel 3 XL with AAOS though...
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION_CHANGE)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTABLE)
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_NOFS)
        }
        context.registerReceiver(usbBroadcastReceiver, filter)
    }

    fun unregisterForUSBIntents() {
        logger.debug(TAG, "Unregistering for broadcast intents")
        try {
            context.unregisterReceiver(usbBroadcastReceiver)
        } catch (exc: IllegalArgumentException) {
            logger.exceptionLogcatOnly(TAG, exc.message.toString(), exc)
        }
    }

    private fun appendConnectedUSBDevice(device: USBMediaDevice) {
        if (isDeviceInConnectedList(device)) {
            logger.warning(TAG, "USB device already in list of connected devices: ${device.getName()}")
            return
        }
        connectedDevices.add(device)
    }

    private fun notifyObservers(deviceChange: DeviceChange) {
        deviceObservers.forEach { it(deviceChange) }
    }

    fun getNumConnectedDevices(): Int {
        return connectedDevices.size
    }

    fun enableLogToUSB() {
        isLogToUSB = true
        if (connectedDevices.size <= 0) {
            logger.warning(TAG, "No USB device, cannot create logfile")
            return
        }
        try {
            connectedDevices[0].enableLogging()
        } catch (exc: DriveAlmostFullException) {
            throw exc
        }
        catch (exc: RuntimeException) {
            logger.exception(TAG, exc.message.toString(), exc)
        }
    }

    fun disableLogToUSB() {
        isLogToUSB = false
        if (connectedDevices.size <= 0) {
            logger.warning(TAG, "No USB device, cannot use a logfile")
            return
        }
        connectedDevices[0].disableLogging()
    }

}
