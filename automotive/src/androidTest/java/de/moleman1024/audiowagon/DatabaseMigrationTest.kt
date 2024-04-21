/*
SPDX-FileCopyrightText: 2021-2022 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.support.v4.media.MediaBrowserCompat
import androidx.test.platform.app.InstrumentationRegistry
import de.moleman1024.audiowagon.enums.IndexingStatus
import de.moleman1024.audiowagon.filestorage.sd.SDCardMediaDevice
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.util.ServiceFixture
import de.moleman1024.audiowagon.util.TestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.*
import java.io.File

private const val TAG = "DatabaseMigrationTest"
private const val ROOT_DIR = "/metadata"

@ExperimentalCoroutinesApi
class DatabaseMigrationTest {

    private lateinit var serviceFixture: ServiceFixture
    private lateinit var browser: MediaBrowserCompat
    private lateinit var audioBrowserService: AudioBrowserService

    @After
    fun tearDown() {
        Logger.debug(TAG, "tearDown()")
        if (this::browser.isInitialized) {
            browser.unsubscribe(browser.root)
            browser.disconnect()
        }
        if (this::serviceFixture.isInitialized) {
            serviceFixture.shutdown()
        }
    }

    @Test
    fun destructiveMigration_databaseVersion112_createsNewDB() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        TestUtils.deleteDatabaseDirectory()
        TestUtils.copyAssetToDatbaseDirectory(
            context,
            File("database_ver_112.sqlite"),
            "audioItemRepo_${SD_CARD_ID}_metadata.sqlite"
        )
        serviceFixture = ServiceFixture()
        browser = serviceFixture.createMediaBrowser()
        browser.connect()
        audioBrowserService = serviceFixture.waitForAudioBrowserService()
        val sdCardMediaDevice = SDCardMediaDevice(SD_CARD_ID, ROOT_DIR)
        audioBrowserService.setMediaDeviceForTest(sdCardMediaDevice)
        runBlocking {
            audioBrowserService.updateAttachedDevices()
        }
        val waitForCompletedTimeoutMS = 1000 * 10
        TestUtils.waitForTrueOrFail(
            { audioBrowserService.getIndexingStatus().any { it == IndexingStatus.COMPLETED } },
            waitForCompletedTimeoutMS, "indexing completed"
        )
    }

}
