package de.moleman1024.audiowagon.broadcast

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import de.moleman1024.audiowagon.exceptions.DeviceIgnoredException
import de.moleman1024.audiowagon.filestorage.usb.USBDeviceConnections
import de.moleman1024.audiowagon.filestorage.usb.USBMediaDevice
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi

private const val TAG = "USBExtBCRecv"
private val logger = Logger
private val USB_ACTIONS_EXTERNAL = listOf(
    UsbManager.ACTION_USB_DEVICE_ATTACHED,
    UsbManager.ACTION_USB_DEVICE_DETACHED,
)

@ExperimentalCoroutinesApi
class USBExternalBroadcastReceiver : ManagedBroadcastReceiver() {
    var usbDeviceConnections: USBDeviceConnections? = null

    override fun getIntentFilter(): IntentFilter {
        return IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
    }

    override fun getFlags(): Int {
        return getExportedFlags()
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) {
            return
        }
        logger.debug(TAG, "onReceive($intent)")
        if (intent.action !in USB_ACTIONS_EXTERNAL) {
            return
        }
        val usbMediaDevice: USBMediaDevice?
        try {
            usbMediaDevice = usbDeviceConnections?.getUSBMassStorageDeviceFromIntent(intent)
            logger.debug(TAG, "Broadcast with action ${intent.action} received for USB device: $usbMediaDevice")
        } catch (_: DeviceIgnoredException) {
            // one of the built-in USB devices (e.g. bluetooth dongle) has attached/detached, ignore these
            return
        } catch (exc: RuntimeException) {
            logger.exception(TAG, exc.message.toString(), exc)
            return
        }
        if (usbMediaDevice == null) {
            return
        }
        try {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    usbDeviceConnections?.onBroadcastUSBDeviceAttached(usbMediaDevice)
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    usbDeviceConnections?.onBroadcastUSBDeviceDetached(usbMediaDevice)
                }
                else -> {
                    logger.warning(TAG, "Ignoring unhandled action: ${intent.action}")
                }
            }
        } catch (exc: RuntimeException) {
            logger.exception(TAG, exc.message.toString(), exc)
        }
    }
}
