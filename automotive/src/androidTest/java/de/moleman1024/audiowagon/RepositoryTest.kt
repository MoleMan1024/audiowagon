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
import org.junit.Assert
import org.junit.Before
import org.junit.Test


private const val TAG = "RepositoryTest"
private const val ALBUM_ARTIST_ID = 1L

@ExperimentalCoroutinesApi
class RepositoryTest {

    private lateinit var serviceFixture: ServiceFixture
    private lateinit var browser: MediaBrowserCompat
    private lateinit var audioBrowserService: AudioBrowserService
    private lateinit var mockUSBDeviceFixture: MockUSBDeviceFixture

    @Before
    fun setUp() {
        // TODO: duplicated code
        Logger.debug(TAG, "setUp()")
        serviceFixture = ServiceFixture()
        browser = serviceFixture.createMediaBrowser()
        audioBrowserService = serviceFixture.getAudioBrowserService()
        audioBrowserService.exposeUSBInternalBroadcastReceiver()
        audioBrowserService.setUseInMemoryDatabase()
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

    @After
    fun tearDown() {
        Logger.debug(TAG, "tearDown()")
        serviceFixture.shutdown()
        Logger.debug(TAG, "tearDown() has ended")
    }

    @Test
    fun database_default_createsUnknownArtist() {
        runBlocking {
            val unknownArtist = audioBrowserService.getPrimaryRepo()?.getAudioItemForUnknownArtist()
            Assert.assertNotNull(unknownArtist)
        }
    }

    @Test
    fun database_default_createsUnknownAlbum() {
        runBlocking {
            val unknownAlbum = audioBrowserService.getPrimaryRepo()?.getAudioItemForUnknownAlbum()
            Assert.assertNotNull(unknownAlbum)
        }
    }

    @Test
    fun database_default_createsPseudoCompilationArtist() {
        runBlocking {
            val pseudoCompArtistID = audioBrowserService.getPrimaryRepo()?.getPseudoCompilationArtistID()
            Assert.assertNotNull(pseudoCompArtistID)
        }
    }

    @Test
    fun database_default_createsAlbumArtist() {
        runBlocking {
            val tracksForAlbum = audioBrowserService.getPrimaryRepo()?.getTracksForAlbum(ALBUM_ARTIST_ID)
            Assert.assertNotNull(tracksForAlbum)
            Assert.assertEquals(2, tracksForAlbum?.size)
        }
    }

}
