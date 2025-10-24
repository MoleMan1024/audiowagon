/*
SPDX-FileCopyrightText: 2021-2022 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.support.v4.media.MediaBrowserCompat
import androidx.test.platform.app.InstrumentationRegistry
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.util.MockUSBDeviceFixture
import de.moleman1024.audiowagon.util.ServiceFixture
import de.moleman1024.audiowagon.util.TestUtils
import de.moleman1024.audiowagon.util.TestUtils.getDatabaseDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.*
import java.io.File

private const val TAG = "DatabaseMigrationTest"
private const val MUSIC_ROOT = "/Music"
private const val STORAGE_ID = "123456789ABC-5118-7715"

@ExperimentalCoroutinesApi
class DatabaseMigrationTest {
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
    fun destructiveMigration_databaseVersion112_createsNewDB() {
        mockUSBDeviceFixture.createMP3().also { mockUSBDeviceFixture.storeMP3(it, "$MUSIC_ROOT/folder1/track1.mp3") }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        TestUtils.deleteDatabaseDirectory()
        val oldFile = TestUtils.copyAssetToDatabaseDirectory(
            context,
            File("database_ver_112.sqlite"),
            "audioItemRepo_${STORAGE_ID}.sqlite"
        )
        val oldFileSize = oldFile.length()
        mockUSBDeviceFixture.createMP3().apply {
            id3v2Tag.title = "Track1"
        }.also { mockUSBDeviceFixture.storeMP3(it, "$MUSIC_ROOT/folder1/track1.mp3") }
        val databasesDir = getDatabaseDirectory()
        mockUSBDeviceFixture.attachUSBDevice()
        TestUtils.waitForIndexingCompleted(audioBrowserService)
        Logger.debug(TAG, "Listing files in database dir")
        databasesDir.listFiles()?.forEach {
            Logger.debug(TAG, "$it (size=${it.length()})")
            if (it.name.endsWith(".sqlite")) {
                Logger.debug(TAG, "new sqlite DB size: ${it.length()}")
                Assert.assertNotEquals(oldFileSize, it.length())
            }
        }
    }

}
