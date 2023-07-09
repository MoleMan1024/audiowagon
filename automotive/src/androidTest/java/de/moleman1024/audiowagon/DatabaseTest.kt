/*
SPDX-FileCopyrightText: 2021-2022 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.content.Intent
import android.content.res.AssetManager
import android.hardware.usb.UsbManager
import android.support.v4.media.MediaBrowserCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.mpatric.mp3agic.Mp3File
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.mocks.MockUSBDevice
import de.moleman1024.audiowagon.util.MediaBrowserTraversal
import de.moleman1024.audiowagon.util.ServiceFixture
import de.moleman1024.audiowagon.util.TestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test

private const val TAG = "DatabaseTest"
private const val TEMPLATE_MP3_NAME = "test.mp3"
private const val TEMPLATE_MP3_PATH = "/.template/$TEMPLATE_MP3_NAME"
private const val MUSIC_ROOT = "/Music"
private const val STORAGE_ID = "\"storage\":\"123456789ABC-5118-7715\""
private const val PLAY_ALL = "Play all"

@ExperimentalCoroutinesApi
class DatabaseTest {
    private lateinit var serviceFixture: ServiceFixture
    private lateinit var browser: MediaBrowserCompat
    private lateinit var audioBrowserService: AudioBrowserService
    private lateinit var mockUSBDevice: MockUSBDevice

    @Before
    fun setUp() {
        Logger.debug(TAG, "setUp()")
        TestUtils.deleteDatabaseDirectory()
        serviceFixture = ServiceFixture()
        browser = serviceFixture.createMediaBrowser()
        browser.connect()
        audioBrowserService = serviceFixture.waitForAudioBrowserService()
        mockUSBDevice = MockUSBDevice()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        mockUSBDevice.initFilesystem(context)
        val assetManager: AssetManager = context.assets
        val assetFileInputStream = assetManager.open(TEMPLATE_MP3_NAME)
        mockUSBDevice.fileSystem.copyToFile(assetFileInputStream, TEMPLATE_MP3_PATH)
    }

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

    private fun attachUSBDevice(usbDevice: MockUSBDevice) {
        val deviceAttachedIntent = Intent(ACTION_USB_ATTACHED)
        deviceAttachedIntent.putExtra(UsbManager.EXTRA_DEVICE, usbDevice)
        Logger.info(TAG, "Sending broadcast to attach USB device")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.sendBroadcast(deviceAttachedIntent)
    }

    private fun createMP3(): Mp3File {
        val tempPath = mockUSBDevice.fileSystem.getPath(TEMPLATE_MP3_PATH)
        return Mp3File(tempPath)
    }

    private fun storeMP3(mp3File: Mp3File, filePath: String) {
        mockUSBDevice.fileSystem.createDirectories(filePath.split("/").dropLast(1).joinToString("/"))
        Logger.debug(TAG, "Storing MP3: $filePath")
        mp3File.save(mockUSBDevice.fileSystem.getPath(filePath))
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
        createMP3().apply {
            id3v2Tag.title = disc1Track1
            id3v2Tag.album = albumName
            id3v2Tag.track = "1"
            id3v2Tag.partOfSet = "1"
        }.also { storeMP3(it, "$MUSIC_ROOT/disc1File1.mp3") }
        createMP3().apply {
            id3v2Tag.title = disc1Track2
            id3v2Tag.album = albumName
            id3v2Tag.track = "2"
            id3v2Tag.partOfSet = "1"
        }.also { storeMP3(it, "$MUSIC_ROOT/disc1File2.mp3") }
        createMP3().apply {
            id3v2Tag.title = disc2Track1
            id3v2Tag.album = albumName
            id3v2Tag.track = "1"
            id3v2Tag.partOfSet = "2"
        }.also { storeMP3(it, "$MUSIC_ROOT/disc2File1.mp3") }
        attachUSBDevice(mockUSBDevice)
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
            createMP3().apply {
                id3v2Tag.artist = artist
            }.also { storeMP3(it, "$MUSIC_ROOT/$index.mp3") }
        }
        attachUSBDevice(mockUSBDevice)
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
            createMP3().apply {
                id3v2Tag.artist = "ARTIST_$indexStrPadded"
                id3v2Tag.albumArtist = "ALBUMARTIST_$indexStrPadded"
                id3v2Tag.album = "ALBUM_$indexStrPadded"
                id3v2Tag.title = "TITLE_$indexStrPadded"
            }.also { storeMP3(it, "$MUSIC_ROOT/ARTIST_$indexStrPadded/ALBUM_$indexStrPadded/TITLE_$indexStrPadded.mp3") }
        }
        val lastAlbumArtist = "ZEBRA"
        createMP3().apply {
            id3v2Tag.artist = lastAlbumArtist
            id3v2Tag.albumArtist = lastAlbumArtist
            id3v2Tag.album = "ZEBRA_ALBUM"
            id3v2Tag.title = "ZEBRA_TITLE"
        }.also { storeMP3(it, "$MUSIC_ROOT/ZEBRA/ZEBRA_ALBUM/ZEBRA_TITLE.mp3") }
        createMP3().apply {
            id3v2Tag.artist = "XYLOPHONE"
            id3v2Tag.albumArtist = lastAlbumArtist
            id3v2Tag.album = "XYLO_ALBUM"
            id3v2Tag.title = "XYLO_TITLE"
        }.also { storeMP3(it, "$MUSIC_ROOT/XYLO/XYLO_ALBUM/XYLOPHONE.mp3") }
        attachUSBDevice(mockUSBDevice)
        TestUtils.waitForIndexingCompleted(audioBrowserService)
        val traversal = MediaBrowserTraversal(browser)
        val artistsRoot = "{\"type\":\"ROOT_ARTISTS\"}"
        traversal.start(artistsRoot)
        // should get 2 artist groups
        Assert.assertEquals(2, traversal.hierarchy[artistsRoot]?.size)
        val secondArtistGroup = traversal.hierarchy["{\"type\":\"ARTIST_GROUP\",\"artGrp\":1}"]
        Assert.assertEquals(numAlbumArtists - 400 + 1, secondArtistGroup?.size)
        Assert.assertEquals(lastAlbumArtist, secondArtistGroup?.get(secondArtistGroup.size-1)?.description?.title)
    }
}
