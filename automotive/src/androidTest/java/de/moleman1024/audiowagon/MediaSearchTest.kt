/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.support.v4.media.MediaBrowserCompat
import androidx.test.platform.app.InstrumentationRegistry
import de.moleman1024.audiowagon.enums.IndexingStatus
import de.moleman1024.audiowagon.filestorage.sd.SDCardMediaDevice
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.util.MediaBrowserSearch
import de.moleman1024.audiowagon.util.MockUSBDeviceFixture
import de.moleman1024.audiowagon.util.ServiceFixture
import de.moleman1024.audiowagon.util.TestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.*

private const val TAG = "MediaSearchTest"

@ExperimentalCoroutinesApi
class MediaSearchTest {

    companion object {
        private lateinit var serviceFixture: ServiceFixture
        private lateinit var browser: MediaBrowserCompat
        private lateinit var mockUSBDeviceFixture: MockUSBDeviceFixture

        @BeforeClass
        @JvmStatic
        fun setUp() {
            Logger.debug(TAG, "setUp()")
            serviceFixture = ServiceFixture()
            browser = serviceFixture.createMediaBrowser()
            val audioBrowserService = serviceFixture.getAudioBrowserService()
            mockUSBDeviceFixture = MockUSBDeviceFixture()
            mockUSBDeviceFixture.init()
            mockUSBDeviceFixture.createMP3().apply {
                id3v2Tag.title = "TITLE_X"
                id3v2Tag.artist = "ARTIST_0"
                id3v2Tag.albumArtist = "ALBUM_ARTIST"
                id3v2Tag.album = "ALBUM_X"
            }.also { mockUSBDeviceFixture.storeMP3(it, "/album_artist_0.mp3") }
            mockUSBDeviceFixture.createMP3().apply {
                id3v2Tag.title = "TITLE_Y"
                id3v2Tag.artist = "ARTIST_1"
                id3v2Tag.albumArtist = "ALBUM_ARTIST"
                id3v2Tag.album = "ALBUM_X"
            }.also { mockUSBDeviceFixture.storeMP3(it, "/album_artist_1.mp3") }
            mockUSBDeviceFixture.createMP3().apply {
                id3v2Tag.title = "COMPILATION_TITLE_0"
                id3v2Tag.artist = "COMPILATION_ARTIST_0"
                id3v2Tag.albumArtist = "Various artists"
                id3v2Tag.album = "ALBUM_Y"
            }.also { mockUSBDeviceFixture.storeMP3(it, "/comp_0.mp3") }
            mockUSBDeviceFixture.createMP3().apply {
                id3v2Tag.title = "COMPILATION_TITLE_1"
                id3v2Tag.artist = "COMPILATION_ARTIST_1"
                id3v2Tag.albumArtist = "Various artists"
                id3v2Tag.album = "ALBUM_Y"
            }.also { mockUSBDeviceFixture.storeMP3(it, "/comp_1.mp3") }
            mockUSBDeviceFixture.createMP3().apply {
                id3v2Tag.title = "UNKNOWN_ALBUM_TITLE"
                id3v2Tag.artist = "UNKNOWN_ALBUM_ARTIST"
            }.also { mockUSBDeviceFixture.storeMP3(it, "/unknown_album.mp3") }
            mockUSBDeviceFixture.createMP3().apply {
                id3v2Tag.title = "UNKNOWN_ARTIST_TITLE"
                id3v2Tag.album = "UNKNOWN_ARTIST_ALBUM"
            }.also { mockUSBDeviceFixture.storeMP3(it, "/unknown_artist.mp3") }
            mockUSBDeviceFixture.attachUSBDevice()
            TestUtils.waitForIndexingCompleted(audioBrowserService)
            Logger.info(TAG, "Indexing was completed")
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            Logger.debug(TAG, "tearDown()")
            serviceFixture.shutdown()
        }
    }

    @Test
    fun searchFilename_default_returnsFile() {
        val search = MediaBrowserSearch(browser)
        val filename = "album_artist_1.mp3"
        search.start(filename)
        Assert.assertEquals(1, search.items.size)
        Assert.assertEquals(filename, search.items[0].description.title)
    }

    @Test
    fun searchUnspecific_default_returnsMixedResults() {
        val search = MediaBrowserSearch(browser)
        val filename = "album_artist"
        search.start(filename)
        Assert.assertEquals(4, search.items.size)
        Assert.assertEquals("ALBUM_ARTIST", search.items[0].description.title)
        Assert.assertEquals("UNKNOWN_ALBUM_ARTIST", search.items[1].description.title)
        Assert.assertEquals("album_artist_0.mp3", search.items[2].description.title)
        Assert.assertEquals("album_artist_1.mp3", search.items[3].description.title)
    }

    @Test
    fun searchArtist_default_returnsArtist() {
        val search = MediaBrowserSearch(browser)
        val filename = "UNKNOWN_ALBUM_ARTIST"
        search.start(filename)
        Assert.assertEquals(1, search.items.size)
    }

    @Test
    fun searchAlbum_default_returnsAlbum() {
        val search = MediaBrowserSearch(browser)
        val filename = "ALBUM_X"
        search.start(filename)
        Assert.assertEquals(1, search.items.size)
    }

}
