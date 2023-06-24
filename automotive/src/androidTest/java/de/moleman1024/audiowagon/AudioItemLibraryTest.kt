/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.content.Intent
import android.hardware.usb.UsbManager
import androidx.test.platform.app.InstrumentationRegistry
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.mocks.MockUSBDevice
import de.moleman1024.audiowagon.util.ServiceFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

const val ACTION_USB_ATTACHED = "de.moleman1024.audiowagon.authorization.USB_ATTACHED"
private const val TAG = "AudioItemLibraryTest"

@ExperimentalCoroutinesApi
class AudioItemLibraryTest {
    private lateinit var serviceFixture: ServiceFixture
    private lateinit var audioBrowserService: AudioBrowserService

    @Before
    fun setUp() {
        Logger.debug(TAG, "setUp()")
        serviceFixture = ServiceFixture()
        serviceFixture.startService()
        audioBrowserService = serviceFixture.createAudioBrowserService()
    }

    @After
    fun tearDown() {
        Logger.debug(TAG, "tearDown()")
        serviceFixture.stopService()
        serviceFixture.shutdown()
    }

    private fun attachUSBDevice() {
        // UsbDevice class in Android is almost untestable, so I introduced lots of interfaces and wrote my own
        // mock USB device class
        val mockUSBDevice = MockUSBDevice()
        val deviceAttachedIntent = Intent(ACTION_USB_ATTACHED)
        deviceAttachedIntent.putExtra(UsbManager.EXTRA_DEVICE, mockUSBDevice)
        Logger.info(TAG, "Sending broadcast to attach USB device")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.sendBroadcast(deviceAttachedIntent)
        Thread.sleep(200)
    }

    @Test
    fun usbDeviceAttachedBroadcast_default_isHandled() {
        attachUSBDevice()
        val audioItemLibrary = audioBrowserService.getAudioItemLibrary()
        runBlocking {
            assertTrue("No repositories", audioItemLibrary.areAnyReposAvail())
        }
    }
}
