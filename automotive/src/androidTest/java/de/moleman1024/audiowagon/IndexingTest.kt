/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.support.v4.media.MediaBrowserCompat
import de.moleman1024.audiowagon.filestorage.IndexingStatus
import de.moleman1024.audiowagon.filestorage.sd.SDCardMediaDevice
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.util.ServiceFixture
import de.moleman1024.audiowagon.util.TestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test

private const val TAG = "IndexingTest"
private const val ROOT_DIR = "/metadata"

@ExperimentalCoroutinesApi
class IndexingTest {

    private lateinit var serviceFixture: ServiceFixture
    private lateinit var browser: MediaBrowserCompat
    private lateinit var audioBrowserService: AudioBrowserService

    @Before
    fun setUp() {
        Logger.debug(TAG, "setUp()")
        serviceFixture = ServiceFixture()
        browser = serviceFixture.createMediaBrowser()
        browser.connect()
        audioBrowserService = serviceFixture.waitForAudioBrowserService()
    }

    @After
    fun tearDown() {
        Logger.debug(TAG, "tearDown()")
        browser.unsubscribe(browser.root)
        browser.disconnect()
        serviceFixture.shutdown()
    }

    /**
     * Regression test for https://github.com/MoleMan1024/audiowagon/issues/46
     */
    @Test
    fun reindex_indexingRunning_abortsAndStartsAgain() {
        TestUtils.deleteDatabaseDirectory()
        val sdCardMediaDevice = SDCardMediaDevice(SD_CARD_ID, ROOT_DIR)
        audioBrowserService.setMediaDeviceForTest(sdCardMediaDevice)
        audioBrowserService.updateConnectedDevices()
        val waitForIndexingTimeoutMS = 1000 * 5
        TestUtils.waitForTrueOrFail(
            { audioBrowserService.getIndexingStatus().any { it == IndexingStatus.INDEXING } },
            waitForIndexingTimeoutMS
        )
        Logger.debug(TAG, "Indexing is ongoing")
        Thread.sleep(100)
        Logger.debug(TAG, "Will re-index now")
        audioBrowserService.setMediaDeviceForTest(sdCardMediaDevice)
        audioBrowserService.updateConnectedDevices()
        val waitForCompletedTimeoutMS = 1000 * 20
        TestUtils.waitForTrueOrFail(
            { audioBrowserService.getIndexingStatus().any { it == IndexingStatus.COMPLETED } },
            waitForCompletedTimeoutMS
        )
        runBlocking {
            // The number of tracks in database was messed up when multiple indexing coroutines were running in
            // parallel. Assert we have the correct amount now that parallel coroutines are avoided
            Assert.assertEquals(6, audioBrowserService.getPrimaryRepo()?.getNumTracks())
        }
    }
}
