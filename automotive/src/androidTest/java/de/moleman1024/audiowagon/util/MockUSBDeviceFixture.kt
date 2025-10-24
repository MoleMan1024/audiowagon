/*
SPDX-FileCopyrightText: 2021-2024 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.util

import android.content.Intent
import android.content.res.AssetManager
import android.hardware.usb.UsbManager
import androidx.test.platform.app.InstrumentationRegistry
import com.mpatric.mp3agic.Mp3File
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.mocks.InMemoryFileSystem
import de.moleman1024.audiowagon.mocks.MockUSBDevice
import java.io.File

private const val TAG = "MockUSBDeviceFixture"
private const val TEMPLATE_MP3_NAME = "test.mp3"
private const val TEMPLATE_MP3_PATH = "/.template/$TEMPLATE_MP3_NAME"
private const val ACTION_USB_ATTACHED = "de.moleman1024.audiowagon.USB_ATTACHED"

class MockUSBDeviceFixture {
    val mockUSBDevice: MockUSBDevice = MockUSBDevice()
    val fileSystem: InMemoryFileSystem
        get() {
            return mockUSBDevice.fileSystem
        }

    fun init() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        mockUSBDevice.initFilesystem(context)
        val assetManager: AssetManager = context.assets
        val assetFileInputStream = assetManager.open(TEMPLATE_MP3_NAME)
        mockUSBDevice.fileSystem.copyToFile(assetFileInputStream, TEMPLATE_MP3_PATH)
    }

    fun attachUSBDevice() {
        val deviceAttachedIntent = Intent(ACTION_USB_ATTACHED)
        deviceAttachedIntent.putExtra(UsbManager.EXTRA_DEVICE, mockUSBDevice)
        Logger.info(TAG, "Sending broadcast to attach USB device: $deviceAttachedIntent")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.sendBroadcast(deviceAttachedIntent)
    }

    fun createMP3(): Mp3File {
        val tempPath = mockUSBDevice.fileSystem.getPath(TEMPLATE_MP3_PATH)
        return Mp3File(tempPath)
    }

    fun storeMP3(mp3File: Mp3File, filePath: String) {
        Logger.debug(TAG, "storeMP3(mp3File=$mp3File, filePath=$filePath)")
        mockUSBDevice.fileSystem.createDirectories(filePath.split("/").dropLast(1).joinToString("/"))
        mp3File.save(mockUSBDevice.fileSystem.getPath(filePath))
    }

}
