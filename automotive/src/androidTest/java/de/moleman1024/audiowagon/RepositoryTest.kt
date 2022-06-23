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

private const val TAG = "RepositoryTest"
private const val TIMEOUT_MS_LIBRARY_CREATION = 10 * 1000
private const val ALBUM_ARTIST_ID = 1L
private const val PSEUDO_COMPILATION_ARTIST_ID = 5L

@ExperimentalCoroutinesApi
class RepositoryTest {

    private lateinit var serviceFixture: ServiceFixture
    private lateinit var browser: MediaBrowserCompat
    private lateinit var audioBrowserService: AudioBrowserService

    @Before
    fun setUp() {
        // TODO: duplicated code
        Logger.debug(TAG, "setUp()")
        serviceFixture = ServiceFixture()
        browser = serviceFixture.createMediaBrowser()
        browser.connect()
        audioBrowserService = serviceFixture.waitForAudioBrowserService()
        audioBrowserService.setUseInMemoryDatabase()
        val sdCardMediaDevice = SDCardMediaDevice(SD_CARD_ID, "/metadata")
        audioBrowserService.setMediaDeviceForTest(sdCardMediaDevice)
        audioBrowserService.updateConnectedDevices()
        TestUtils.waitForTrueOrFail(
            { audioBrowserService.getIndexingStatus().any { it == IndexingStatus.COMPLETED } },
            TIMEOUT_MS_LIBRARY_CREATION
        )
        Logger.info(TAG, "Indexing was completed")
    }

    @After
    fun tearDown() {
        Logger.debug(TAG, "tearDown()")
        browser.unsubscribe(browser.root)
        browser.disconnect()
        serviceFixture.shutdown()
    }

    @Test
    fun database_metadataSDCardImage_createsUnknownArtist() {
        runBlocking {
            val unknownArtist = audioBrowserService.getPrimaryRepo()?.getAudioItemForUnknownArtist()
            Assert.assertNotNull(unknownArtist)
        }
    }

    @Test
    fun database_metadataSDCardImage_createsUnknownAlbum() {
        runBlocking {
            val unknownAlbum = audioBrowserService.getPrimaryRepo()?.getAudioItemForUnknownAlbum()
            Assert.assertNotNull(unknownAlbum)
        }
    }

    @Test
    fun database_metadataSDCardImage_createsPseudoCompilationArtist() {
        runBlocking {
            val pseudoCompArtistID = audioBrowserService.getPrimaryRepo()?.getPseudoCompilationArtistID()
            Assert.assertNotNull(pseudoCompArtistID)
            Assert.assertEquals(PSEUDO_COMPILATION_ARTIST_ID, pseudoCompArtistID)
        }
    }

    @Test
    fun database_metadataSDCardImage_createsAlbumArtist() {
        runBlocking {
            val tracksForAlbum = audioBrowserService.getPrimaryRepo()?.getTracksForAlbum(ALBUM_ARTIST_ID)
            Assert.assertNotNull(tracksForAlbum)
            Assert.assertEquals(2, tracksForAlbum?.size)
        }
    }

}
