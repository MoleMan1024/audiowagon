/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.support.v4.media.MediaBrowserCompat
import androidx.test.platform.app.InstrumentationRegistry
import de.moleman1024.audiowagon.enums.ViewTabSetting
import de.moleman1024.audiowagon.filestorage.usb.USBMediaDevice
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.util.MediaBrowserTraversal
import de.moleman1024.audiowagon.util.MockUSBDeviceFixture
import de.moleman1024.audiowagon.util.ServiceFixture
import de.moleman1024.audiowagon.util.TestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.*

private const val TAG = "MediaBrowserTest"
private const val STORAGE_ID = "123456789ABC-5118-7715"

// check https://github.com/googlesamples/android-media-controller/blob/master/mediacontroller/src/main/java/com/example/android/mediacontroller/MediaAppTestDetails.kt
// for some implementation ideas
@ExperimentalCoroutinesApi
class MediaBrowserTest {

    companion object {
        private lateinit var serviceFixture: ServiceFixture
        private lateinit var browser: MediaBrowserCompat
        private lateinit var audioBrowserService: AudioBrowserService
        private lateinit var mockUSBDeviceFixture: MockUSBDeviceFixture

        @BeforeClass
        @JvmStatic
        fun setUp() {
            Logger.debug(TAG, "setUp()")
            serviceFixture = ServiceFixture()
            browser = serviceFixture.createMediaBrowser()
            audioBrowserService = serviceFixture.getAudioBrowserService()
            // use in-memory database to speed-up tests
            audioBrowserService.setUseInMemoryDatabase()
            mockUSBDeviceFixture = MockUSBDeviceFixture()
            mockUSBDeviceFixture.init()
            setupAudioFiles(mockUSBDeviceFixture)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val mediaDevice = USBMediaDevice(context, mockUSBDeviceFixture.mockUSBDevice)
            runBlocking {
                mediaDevice.initFilesystem()
                audioBrowserService.setMediaDeviceForTest(mediaDevice)
                audioBrowserService.updateAttachedDevices()
            }
            // This will take a minute or so
            TestUtils.waitForIndexingCompleted(audioBrowserService, 1000 * 90)
            Logger.info(TAG, "Indexing was completed")
        }

        private fun setupAudioFiles(mockUSBDeviceFixture: MockUSBDeviceFixture) {
            for (artistNum in 0 until 10) {
                for (albumNum in 0 until 10) {
                    for (trackNum in 0 until 20) {
                        mockUSBDeviceFixture.createMP3().also {
                            mockUSBDeviceFixture.storeMP3(
                                it,
                                "/ARTIST_$artistNum/ALBUM_$albumNum/TRACK_${trackNum}_FOR_ART_${artistNum}_IN_ALB_${albumNum}.mp3"
                            )
                        }
                    }
                }
            }
            for (i in 0 until 500) {
                mockUSBDeviceFixture.createMP3().also {
                    mockUSBDeviceFixture.storeMP3(it, "/dirWithManyFiles/TRACK_$i.mp3")
                }
            }
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            Logger.debug(TAG, "tearDown()")
            serviceFixture.shutdown()
        }
    }

    @Test
    fun onLoadChildren_manyFiles_createsArtistHierarchy() {
        val traversal = MediaBrowserTraversal(browser)
        val artistsRoot = "{\"type\":\"ROOT_ARTISTS\"}"
        traversal.start(artistsRoot)
        val unknownArtist = "{\"type\":\"ARTIST\",\"storage\":\"$STORAGE_ID\",\"art\":-1}"
        Assert.assertEquals(unknownArtist, traversal.hierarchy[artistsRoot]?.get(0)?.mediaId)
        Assert.assertEquals(
            listOf(
                "{\"type\":\"ALL_TRACKS_FOR_ARTIST\",\"storage\":\"$STORAGE_ID\",\"art\":-1}",
                "{\"type\":\"UNKNOWN_ALBUM\",\"storage\":\"$STORAGE_ID\",\"art\":-1,\"alb\":-1}"
            ),
            listOf(
                traversal.hierarchy[unknownArtist]?.get(0)?.mediaId,
                traversal.hierarchy[unknownArtist]?.get(1)?.mediaId
            )
        )
        Assert.assertEquals(
            400,
            traversal.hierarchy[
                    "{\"type\":\"TRACK_GROUP\",\"storage\":\"$STORAGE_ID\",\"art\":-1,\"alb\":-1," +
                            "\"trkGrp\":5}"
            ]?.size
        )
        Assert.assertEquals(
            100,
            traversal.hierarchy[
                    "{\"type\":\"TRACK_GROUP\",\"storage\":\"$STORAGE_ID\",\"art\":-1,\"alb\":-1," +
                            "\"trkGrp\":6}"
            ]?.size
        )
    }

    @Test
    fun onLoadChildren_manyFiles_createsAlbumHierarchy() {
        val traversal = MediaBrowserTraversal(browser)
        val albumsRoot = "{\"type\":\"ROOT_ALBUMS\"}"
        traversal.start(albumsRoot)
        val unknownAlbum = "{\"type\":\"UNKNOWN_ALBUM\",\"storage\":\"$STORAGE_ID\",\"alb\":-1}"
        Assert.assertEquals(unknownAlbum, traversal.hierarchy[albumsRoot]?.get(0)?.mediaId)
        Assert.assertEquals(7, traversal.hierarchy[unknownAlbum]?.size)
        Assert.assertEquals(
            400,
            traversal.hierarchy["{\"type\":\"TRACK_GROUP\",\"storage\":\"$STORAGE_ID\",\"alb\":-1," +
                    "\"trkGrp\":5}"]?.size
        )
        Assert.assertEquals(
            100,
            traversal.hierarchy["{\"type\":\"TRACK_GROUP\",\"storage\":\"$STORAGE_ID\",\"alb\":-1," +
                    "\"trkGrp\":6}"]?.size
        )
    }

    @Test
    fun onLoadChildren_manyFiles_createsTrackHierarchy() {
        val traversal = MediaBrowserTraversal(browser)
        val tracksRoot = "{\"type\":\"ROOT_TRACKS\"}"
        traversal.start(tracksRoot)
        Assert.assertEquals("{\"type\":\"SHUFFLE_ALL_TRACKS\"}", traversal.hierarchy[tracksRoot]?.get(0)?.mediaId)
        Assert.assertEquals(8, traversal.hierarchy[tracksRoot]?.size)
        Assert.assertEquals(400, traversal.hierarchy["{\"type\":\"TRACK_GROUP\",\"trkGrp\":5}"]?.size)
        Assert.assertEquals(100, traversal.hierarchy["{\"type\":\"TRACK_GROUP\",\"trkGrp\":6}"]?.size)
    }

    @Test
    fun onLoadChildren_manyFiles_createsFilesHierarchy() {
        val traversal = MediaBrowserTraversal(browser)
        val filesRoot = "{\"type\":\"ROOT_FILES\"}"
        traversal.start(filesRoot)
        Assert.assertEquals(12, traversal.hierarchy[filesRoot]?.size)
        Assert.assertEquals(
            "{\"type\":\"DIRECTORY\",\"path\":\"/ARTIST_2\"}",
            traversal.hierarchy[filesRoot]?.get(3)?.mediaId
        )
    }

    @Test
    fun onLoadChildren_manyFiles_createsDirectoryHierarchy() {
        val traversal = MediaBrowserTraversal(browser)
        val directoryRoot = "{\"type\":\"DIRECTORY\",\"path\":\"/ARTIST_0\"}"
        traversal.start(directoryRoot)
        Assert.assertEquals(11, traversal.hierarchy[directoryRoot]?.size)
    }

    /**
     * Regression test for https://github.com/MoleMan1024/audiowagon/issues/54
     */
    @Test
    fun onLoadChildren_manyFiles500FilesInDir_createsGroups() {
        val traversal = MediaBrowserTraversal(browser)
        val directoryRoot = "{\"type\":\"DIRECTORY\",\"path\":\"/dirWithManyFiles\"}"
        traversal.start(directoryRoot)
        Assert.assertEquals(3, traversal.hierarchy[directoryRoot]?.size)
    }

    /**
     * Tests for customized browse view tabs order ( https://github.com/MoleMan1024/audiowagon/issues/124 )
     */
    @Test
    fun onLoadChildren_defaultCategoryOrder_showsTracksAlbumsArtistsFiles() {
        val traversal = MediaBrowserTraversal(browser)
        val root = "{\"type\":\"ROOT\"}"
        traversal.start(root)
        Assert.assertEquals(4, traversal.hierarchy[root]?.size)
        listOf("Tracks", "Albums", "Artists", "Files").forEachIndexed { index, category ->
            Assert.assertEquals(category, traversal.hierarchy[root]?.get(index)?.description?.title)
        }
    }

    @Test
    fun onLoadChildren_viewTabsOrderFilesNoneAlbumsNone_hasGivenViewTabs() {
        val viewTabs = listOf(ViewTabSetting.FILES, ViewTabSetting.NONE, ViewTabSetting.ALBUMS, ViewTabSetting.NONE)
        audioBrowserService.getAudioItemLibrary().setViewTabs(viewTabs)
        val traversal = MediaBrowserTraversal(browser)
        val root = "{\"type\":\"ROOT\"}"
        traversal.start(root)
        Assert.assertEquals(4, traversal.hierarchy[root]?.size)
        listOf("Files", null, "Albums", null).forEachIndexed { index, category ->
            Assert.assertEquals(category, traversal.hierarchy[root]?.get(index)?.description?.title)
        }
    }

}
