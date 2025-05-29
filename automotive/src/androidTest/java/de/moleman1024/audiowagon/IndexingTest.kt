/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.support.v4.media.MediaBrowserCompat
import androidx.test.platform.app.InstrumentationRegistry
import de.moleman1024.audiowagon.enums.IndexingStatus
import de.moleman1024.audiowagon.filestorage.usb.USBMediaDevice
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.util.MockUSBDeviceFixture
import de.moleman1024.audiowagon.util.ServiceFixture
import de.moleman1024.audiowagon.util.TestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test

private const val TAG = "IndexingTest"
private const val MUSIC_ROOT = "/Music"

@ExperimentalCoroutinesApi
class IndexingTest {
    private lateinit var serviceFixture: ServiceFixture
    private lateinit var browser: MediaBrowserCompat
    private lateinit var audioBrowserService: AudioBrowserService
    private lateinit var mockUSBDeviceFixture: MockUSBDeviceFixture

    @Before
    fun setUp() {
        Logger.debug(TAG, "setUp()")
        serviceFixture = ServiceFixture()
        browser = serviceFixture.createMediaBrowser()
        audioBrowserService = serviceFixture.getAudioBrowserService()
        mockUSBDeviceFixture = MockUSBDeviceFixture()
        mockUSBDeviceFixture.init()
    }

    @After
    fun tearDown() {
        Logger.debug(TAG, "tearDown()")
        serviceFixture.shutdown()
    }

    /**
     * Regression test for https://github.com/MoleMan1024/audiowagon/issues/46
     */
    @Test
    fun reindex_indexingRunning_abortsAndStartsAgain() {
        for (i in 0 until 6) {
            mockUSBDeviceFixture.createMP3().apply {
                id3v2Tag.title = "Track$i"
            }.also { mockUSBDeviceFixture.storeMP3(it, "$MUSIC_ROOT/folder$i/track$i.mp3") }
        }
        TestUtils.deleteDatabaseDirectory()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val mediaDevice = USBMediaDevice(context, mockUSBDeviceFixture.mockUSBDevice)
        runBlocking {
            mediaDevice.initFilesystem()
            audioBrowserService.setMediaDeviceForTest(mediaDevice)
            audioBrowserService.updateAttachedDevices()
        }
        val waitForIndexingTimeoutMS = 1000 * 5
        TestUtils.waitForTrueOrFail(
            { audioBrowserService.getIndexingStatus().any { it == IndexingStatus.INDEXING } },
            waitForIndexingTimeoutMS, "indexing"
        )
        Logger.debug(TAG, "Indexing is ongoing")
        Thread.sleep(100)
        Logger.debug(TAG, "Will re-index now")
        audioBrowserService.setMediaDeviceForTest(mediaDevice)
        runBlocking {
            audioBrowserService.updateAttachedDevices()
        }
        val waitForStoppedTimeoutMS = 1000 * 5
        TestUtils.waitForTrueOrFail(
            { audioBrowserService.getIndexingStatus().any { it != IndexingStatus.INDEXING } },
            waitForStoppedTimeoutMS, "stopped"
        )
        TestUtils.waitForTrueOrFail(
            { audioBrowserService.getIndexingStatus().any { it == IndexingStatus.INDEXING } },
            waitForIndexingTimeoutMS, "indexing"
        )
        TestUtils.waitForIndexingCompleted(audioBrowserService)
        runBlocking {
            // The number of tracks in database was messed up when multiple indexing coroutines were running in
            // parallel. Assert we have the correct amount now that parallel coroutines are avoided
            Assert.assertEquals(6, audioBrowserService.getPrimaryRepo()?.getNumTracks())
        }
    }
}
