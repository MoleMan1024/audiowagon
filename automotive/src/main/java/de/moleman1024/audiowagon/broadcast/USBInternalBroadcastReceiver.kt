package de.moleman1024.audiowagon.broadcast

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import de.moleman1024.audiowagon.authorization.ACTION_USB_PERMISSION_CHANGE
import de.moleman1024.audiowagon.exceptions.DeviceIgnoredException
import de.moleman1024.audiowagon.filestorage.usb.USBDeviceConnections
import de.moleman1024.audiowagon.filestorage.usb.USBMediaDevice
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi

private const val TAG = "USBIntBCRecv"
private val logger = Logger
const val ACTION_USB_ATTACHED = "de.moleman1024.audiowagon.USB_ATTACHED"
const val ACTION_USB_UPDATE = "de.moleman1024.audiowagon.USB_UPDATE"
private val USB_ACTIONS_INTERNAL = listOf(
    ACTION_USB_ATTACHED,
    ACTION_USB_UPDATE,
    ACTION_USB_PERMISSION_CHANGE
)

@ExperimentalCoroutinesApi
class USBInternalBroadcastReceiver() : ManagedBroadcastReceiver() {
    var usbDeviceConnections: USBDeviceConnections? = null
    var isExported = false

    override fun getIntentFilter(): IntentFilter {
        return IntentFilter().apply {
            addAction(ACTION_USB_ATTACHED)
            addAction(ACTION_USB_UPDATE)
            addAction(ACTION_USB_PERMISSION_CHANGE)
        }
    }

    override fun getFlags(): Int {
        return if (!isExported) {
            getNotExportedFlags()
        } else {
            getExportedFlags()
        }
    }

    override fun getType(): BroadcastReceiverType {
        return BroadcastReceiverType.USB_INTERNAL
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) {
            return
        }
        logger.debug(TAG, "onReceive($intent)")
        if (intent.action !in USB_ACTIONS_INTERNAL) {
            return
        }
        if (intent.action == ACTION_USB_UPDATE) {
            usbDeviceConnections?.updateAttachedDevices()
            return
        }
        val usbMediaDevice: USBMediaDevice?
        try {
            usbMediaDevice = usbDeviceConnections?.getUSBMassStorageDeviceFromIntent(intent)
            Logger.debug(TAG, "Broadcast with action ${intent.action} received for USB device: $usbMediaDevice")
        } catch (_: DeviceIgnoredException) {
            // one of the built-in USB devices (e.g. bluetooth dongle) has attached/detached, ignore these
            return
        } catch (exc: RuntimeException) {
            Logger.exception(TAG, exc.message.toString(), exc)
            return
        }
        if (usbMediaDevice == null) {
            return
        }
        try {
            when (intent.action) {
                ACTION_USB_ATTACHED -> {
                    usbDeviceConnections?.onBroadcastUSBAttached(usbMediaDevice)
                }
                ACTION_USB_PERMISSION_CHANGE -> {
                    usbDeviceConnections?.onBroadcastUSBPermissionChange(intent, usbMediaDevice)
                }
                else -> {
                    logger.warning(TAG, "Ignoring unhandled action: ${intent.action}")
                }
            }
        } catch (exc: RuntimeException) {
            Logger.exception(TAG, exc.message.toString(), exc)
        }
    }

    override fun toString(): String {
        return "${USBInternalBroadcastReceiver::class.simpleName}{type=${getType()}, " +
                "hashCode=${Integer.toHexString(hashCode())}}"
    }

}
