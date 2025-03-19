/*
SPDX-FileCopyrightText: 2021-2022 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.support.v4.media.MediaBrowserCompat
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.util.MediaBrowserTraversal
import de.moleman1024.audiowagon.util.MockUSBDeviceFixture
import de.moleman1024.audiowagon.util.ServiceFixture
import de.moleman1024.audiowagon.util.TestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test

private const val TAG = "DatabaseTest"
private const val MUSIC_ROOT = "/Music"
private const val STORAGE_ID = "\"storage\":\"123456789ABC-5118-7715\""
private const val PLAY_ALL = "Play all"

@ExperimentalCoroutinesApi
class DatabaseTest {
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
        mockUSBDeviceFixture = MockUSBDeviceFixture()
        mockUSBDeviceFixture.init()
    }

    @After
    fun tearDown() {
        Logger.debug(TAG, "tearDown()")
        serviceFixture.shutdown()
    }

    /**
     * https://github.com/MoleMan1024/audiowagon/issues/62
     */
    @Test
    fun databaseSorting_hasDiscNumbers_sortedByDiscNumber() {
        val albumName = "ALBUM"
        val disc1Track1 = "Disc1Track1"
        val disc1Track2 = "Disc1Track2"
        val disc2Track1 = "Disc2Track1"
        mockUSBDeviceFixture.createMP3().apply {
            id3v2Tag.title = disc1Track1
            id3v2Tag.album = albumName
            id3v2Tag.track = "1"
            id3v2Tag.partOfSet = "1"
        }.also { mockUSBDeviceFixture.storeMP3(it, "$MUSIC_ROOT/disc1File1.mp3") }
        mockUSBDeviceFixture.createMP3().apply {
            id3v2Tag.title = disc1Track2
            id3v2Tag.album = albumName
            id3v2Tag.track = "2"
            id3v2Tag.partOfSet = "1"
        }.also { mockUSBDeviceFixture.storeMP3(it, "$MUSIC_ROOT/disc1File2.mp3") }
        mockUSBDeviceFixture.createMP3().apply {
            id3v2Tag.title = disc2Track1
            id3v2Tag.album = albumName
            id3v2Tag.track = "1"
            id3v2Tag.partOfSet = "2"
        }.also { mockUSBDeviceFixture.storeMP3(it, "$MUSIC_ROOT/disc2File1.mp3") }
        mockUSBDeviceFixture.attachUSBDevice()
        TestUtils.waitForIndexingCompleted(audioBrowserService)
        val traversal = MediaBrowserTraversal(browser)
        val albumsRoot = "{\"type\":\"ALBUM\",$STORAGE_ID,\"alb\":\"1\"}"
        traversal.start(albumsRoot)
        Assert.assertEquals(PLAY_ALL, traversal.hierarchy[albumsRoot]?.get(0)?.description?.title)
        Assert.assertEquals(disc1Track1, traversal.hierarchy[albumsRoot]?.get(1)?.description?.title)
        Assert.assertEquals(disc1Track2, traversal.hierarchy[albumsRoot]?.get(2)?.description?.title)
        Assert.assertEquals(disc2Track1, traversal.hierarchy[albumsRoot]?.get(3)?.description?.title)
    }

    /**
     * https://github.com/MoleMan1024/audiowagon/issues/60
     */
    @Test
    fun databaseSorting_artistWithArticle_ignoresArticle() {
        val artists = listOf("Beach Boys", "The Beatles", "Beatsteaks")
        artists.forEachIndexed { index, artist ->
            mockUSBDeviceFixture.createMP3().apply {
                id3v2Tag.artist = artist
            }.also { mockUSBDeviceFixture.storeMP3(it, "$MUSIC_ROOT/$index.mp3") }
        }
        mockUSBDeviceFixture.attachUSBDevice()
        TestUtils.waitForIndexingCompleted(audioBrowserService)
        val traversal = MediaBrowserTraversal(browser)
        val artistsRoot = "{\"type\":\"ROOT_ARTISTS\"}"
        traversal.start(artistsRoot)
        artists.forEachIndexed { index, artist ->
            Assert.assertEquals(artist, traversal.hierarchy[artistsRoot]?.get(index)?.description?.title)
        }
    }

    /**
     * https://github.com/MoleMan1024/audiowagon/issues/122
     */
    @Test
    fun artistGroups_hasLotsOfAlbumArtists_createsGroupsProperly() {
        val numAlbumArtists = 500
        for (index in 0 until numAlbumArtists) {
            val indexStrPadded = index.toString().padStart(4, '0')
            mockUSBDeviceFixture.createMP3().apply {
                id3v2Tag.artist = "ARTIST_$indexStrPadded"
                id3v2Tag.albumArtist = "ALBUMARTIST_$indexStrPadded"
                id3v2Tag.album = "ALBUM_$indexStrPadded"
                id3v2Tag.title = "TITLE_$indexStrPadded"
            }.also { mockUSBDeviceFixture.storeMP3(it, "$MUSIC_ROOT/ARTIST_$indexStrPadded/ALBUM_$indexStrPadded/TITLE_$indexStrPadded.mp3") }
        }
        val lastAlbumArtist = "ZEBRA"
        mockUSBDeviceFixture.createMP3().apply {
            id3v2Tag.artist = lastAlbumArtist
            id3v2Tag.albumArtist = lastAlbumArtist
            id3v2Tag.album = "ZEBRA_ALBUM"
            id3v2Tag.title = "ZEBRA_TITLE"
        }.also { mockUSBDeviceFixture.storeMP3(it, "$MUSIC_ROOT/ZEBRA/ZEBRA_ALBUM/ZEBRA_TITLE.mp3") }
        mockUSBDeviceFixture.createMP3().apply {
            id3v2Tag.artist = "XYLOPHONE"
            id3v2Tag.albumArtist = lastAlbumArtist
            id3v2Tag.album = "XYLO_ALBUM"
            id3v2Tag.title = "XYLO_TITLE"
        }.also { mockUSBDeviceFixture.storeMP3(it, "$MUSIC_ROOT/XYLO/XYLO_ALBUM/XYLOPHONE.mp3") }
        mockUSBDeviceFixture.attachUSBDevice()
        TestUtils.waitForIndexingCompleted(audioBrowserService, 1000 * 30)
        val traversal = MediaBrowserTraversal(browser)
        val artistsRoot = "{\"type\":\"ROOT_ARTISTS\"}"
        traversal.start(artistsRoot)
        // should get 2 artist groups
        Assert.assertEquals(2, traversal.hierarchy[artistsRoot]?.size)
        val secondArtistGroup = traversal.hierarchy["{\"type\":\"ARTIST_GROUP\",\"artGrp\":1}"]
        Assert.assertEquals(numAlbumArtists - 400 + 1, secondArtistGroup?.size)
        Assert.assertEquals(lastAlbumArtist, secondArtistGroup?.get(secondArtistGroup.size-1)?.description?.title)
    }

    /**
     * https://github.com/MoleMan1024/audiowagon/issues/137
     */
    @Test
    fun rootAlbums_tracksHaveArtistAndTitleMissingAlbum_unknownAlbumContainsTracks() {
        for (i in 0 until 2) {
            mockUSBDeviceFixture.createMP3().apply {
                id3v2Tag.artist = "ARTIST_$i"
                id3v2Tag.title = "TITLE_$i"
                id3v2Tag.album = ""
            }.also {
                mockUSBDeviceFixture.storeMP3(it, "$MUSIC_ROOT/ARTIST_$i/unknown/TITLE_$i.mp3")
            }
        }
        mockUSBDeviceFixture.attachUSBDevice()
        TestUtils.waitForIndexingCompleted(audioBrowserService)
        val traversal = MediaBrowserTraversal(browser)
        val albumsRoot = "{\"type\":\"ROOT_ALBUMS\"}"
        traversal.start(albumsRoot)
        Assert.assertEquals(1, traversal.hierarchy[albumsRoot]?.size)
        Assert.assertEquals("Unknown album", traversal.hierarchy[albumsRoot]?.get(0)?.description?.title)
        Assert.assertEquals(null, traversal.hierarchy[albumsRoot]?.get(0)?.description?.subtitle)
        val unknownAlbumContents = traversal.hierarchy["{\"type\":\"UNKNOWN_ALBUM\",$STORAGE_ID,\"alb\":-1}"]
        // "play all" + 2 tracks
        Assert.assertEquals(3, unknownAlbumContents?.size)
    }
}
