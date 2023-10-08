/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.support.v4.media.MediaBrowserCompat
import de.moleman1024.audiowagon.enums.IndexingStatus
import de.moleman1024.audiowagon.filestorage.sd.SDCardMediaDevice
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.util.MediaBrowserSearch
import de.moleman1024.audiowagon.util.ServiceFixture
import de.moleman1024.audiowagon.util.TestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.*

private const val TAG = "MediaSearchTest"
private const val TIMEOUT_MS_LIBRARY_CREATION = 20 * 1000
private const val ROOT_DIR = "/metadata"

@ExperimentalCoroutinesApi
class MediaSearchTest {

    companion object {
        private lateinit var serviceFixture: ServiceFixture
        private lateinit var browser: MediaBrowserCompat

        @BeforeClass
        @JvmStatic
        fun setUp() {
            Logger.debug(TAG, "setUp()")
            serviceFixture = ServiceFixture()
            browser = serviceFixture.createMediaBrowser()
            browser.connect()
            val audioBrowserService = serviceFixture.waitForAudioBrowserService()
            // use in-memory database to speed-up tests
            audioBrowserService.setUseInMemoryDatabase()
            val sdCardMediaDevice = SDCardMediaDevice(SD_CARD_ID, ROOT_DIR)
            audioBrowserService.setMediaDeviceForTest(sdCardMediaDevice)
            audioBrowserService.updateAttachedDevices()
            TestUtils.waitForTrueOrFail(
                { audioBrowserService.getIndexingStatus().any { it == IndexingStatus.COMPLETED } },
                TIMEOUT_MS_LIBRARY_CREATION, "indexing completed"
            )
            Logger.info(TAG, "Indexing was completed")
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            Logger.debug(TAG, "tearDown()")
            browser.unsubscribe(browser.root)
            browser.disconnect()
            serviceFixture.shutdown()
        }
    }

    @Test
    fun searchFilename_metadataSDCardImage_returnsFile() {
        val search = MediaBrowserSearch(browser)
        val filename = "album_artist_1.mp3"
        search.start(filename)
        Assert.assertEquals(1, search.items.size)
        Assert.assertEquals(filename, search.items[0].description.title)
    }

    @Test
    fun searchUnspecific_metadataSDCardImage_returnsMixedResults() {
        val search = MediaBrowserSearch(browser)
        val filename = "album_artist"
        search.start(filename)
        Assert.assertEquals(4, search.items.size)
        Assert.assertEquals("ALBUM_ARTIST", search.items[0].description.title)
        Assert.assertEquals("UNKNOWN_ALBUM__ARTIST", search.items[1].description.title)
        Assert.assertEquals("album_artist_0.mp3", search.items[2].description.title)
        Assert.assertEquals("album_artist_1.mp3", search.items[3].description.title)
    }

    @Test
    fun searchArtist_metadataSDCardImage_returnsArtist() {
        val search = MediaBrowserSearch(browser)
        val filename = "UNKNOWN_ALBUM__ARTIST"
        search.start(filename)
        Assert.assertEquals(1, search.items.size)
    }

    @Test
    fun searchAlbum_metadataSDCardImage_returnsAlbum() {
        val search = MediaBrowserSearch(browser)
        val filename = "ALBUM_X"
        search.start(filename)
        Assert.assertEquals(1, search.items.size)
    }

}
