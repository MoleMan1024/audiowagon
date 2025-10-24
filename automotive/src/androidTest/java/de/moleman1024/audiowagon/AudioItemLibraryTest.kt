/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.support.v4.media.MediaBrowserCompat
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.util.MockUSBDeviceFixture
import de.moleman1024.audiowagon.util.ServiceFixture
import de.moleman1024.audiowagon.util.TestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val TAG = "AudioItemLibraryTest"
private const val MUSIC_ROOT = "/Music"

@ExperimentalCoroutinesApi
class AudioItemLibraryTest {
    private lateinit var serviceFixture: ServiceFixture
    private lateinit var browser: MediaBrowserCompat
    private lateinit var audioBrowserService: AudioBrowserService
    private lateinit var mockUSBDeviceFixture: MockUSBDeviceFixture

    @Before
    fun setUp() {
        Logger.debug(TAG, "setUp()")
        TestUtils.deleteDatabaseDirectory()
        serviceFixture = ServiceFixture()
        browser = serviceFixture.createMediaBrowser()
        audioBrowserService = serviceFixture.getAudioBrowserService()
        audioBrowserService.exposeUSBInternalBroadcastReceiver()
        mockUSBDeviceFixture = MockUSBDeviceFixture()
        mockUSBDeviceFixture.init()
    }

    @After
    fun tearDown() {
        Logger.debug(TAG, "tearDown()")
        serviceFixture.shutdown()
    }

    @Test
    fun usbDeviceAttachedBroadcast_default_isHandled() {
        mockUSBDeviceFixture.createMP3().apply {
            id3v2Tag.title = "Track1"
        }.also { mockUSBDeviceFixture.storeMP3(it, "$MUSIC_ROOT/folder1/track1.mp3") }
        mockUSBDeviceFixture.attachUSBDevice()
        TestUtils.waitForIndexingCompleted(audioBrowserService)
        val audioItemLibrary = audioBrowserService.getAudioItemLibrary()
        runBlocking {
            assertTrue("No repositories", audioItemLibrary.areAnyReposAvail())
        }
    }
}
