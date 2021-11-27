/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.support.v4.media.MediaBrowserCompat
import de.moleman1024.audiowagon.filestorage.IndexingStatus
import de.moleman1024.audiowagon.filestorage.sd.SDCardMediaDevice
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.util.MediaBrowserTraversal
import de.moleman1024.audiowagon.util.ServiceFixture
import de.moleman1024.audiowagon.util.TestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert

private const val TAG = "MediaBrowserTest"
// TODO: this timing will change if database was previously indexed or not
private const val TIMEOUT_MS_LIBRARY_CREATION = 30 * 1000
// this SD card image needs to contain directories for each of our test modules
const val SD_CARD_ID = "1404-9F0B"
private const val ROOT_DIR = "/many_files"
private val SD_CARD_ID_WITH_ROOT = "$SD_CARD_ID${ROOT_DIR.replace("/","_")}"

// check https://github.com/googlesamples/android-media-controller/blob/master/mediacontroller/src/main/java/com/example/android/mediacontroller/MediaAppTestDetails.kt
// for some implementation ideas
@ExperimentalCoroutinesApi
class MediaBrowserTest {

    private lateinit var serviceFixture: ServiceFixture
    private lateinit var browser: MediaBrowserCompat

    @Before
    fun setUp() {
        Logger.debug(TAG, "setUp()")
        serviceFixture = ServiceFixture()
        browser = serviceFixture.createMediaBrowser()
        browser.connect()
        val audioBrowserService = serviceFixture.waitForAudioBrowserService()
        // TODO: find a way to provide .img files, too big for repository
        val sdCardMediaDevice = SDCardMediaDevice(SD_CARD_ID, ROOT_DIR)
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
    fun onLoadChildren_manyFilesSDCardImage_createsArtistHierarchy() {
        val traversal = MediaBrowserTraversal(browser)
        val artistsRoot = "{\"type\":\"ROOT_ARTISTS\"}"
        traversal.start(artistsRoot)
        val unknownArtist = "{\"type\":\"ARTIST\",\"storage\":\"$SD_CARD_ID_WITH_ROOT\",\"art\":-1}"
        Assert.assertEquals(listOf(unknownArtist), traversal.hierarchy[artistsRoot])
        Assert.assertEquals(
            listOf(
                "{\"type\":\"ALL_TRACKS_FOR_ARTIST\",\"storage\":\"$SD_CARD_ID_WITH_ROOT\",\"art\":-1}",
                "{\"type\":\"UNKNOWN_ALBUM\",\"storage\":\"$SD_CARD_ID_WITH_ROOT\",\"art\":-1,\"alb\":-1}"
            ),
            traversal.hierarchy[unknownArtist]
        )
        Assert.assertEquals(
            400,
            traversal.hierarchy[
                    "{\"type\":\"TRACK_GROUP\",\"storage\":\"$SD_CARD_ID_WITH_ROOT\",\"art\":-1,\"alb\":-1,\"trkGrp\":4}"
            ]?.size
        )
        Assert.assertEquals(
            140,
            traversal.hierarchy[
                    "{\"type\":\"TRACK_GROUP\",\"storage\":\"$SD_CARD_ID_WITH_ROOT\",\"art\":-1,\"alb\":-1,\"trkGrp\":5}"
            ]?.size
        )
    }

    @Test
    fun onLoadChildren_manyFilesSDCardImage_createsAlbumHierarchy() {
        val traversal = MediaBrowserTraversal(browser)
        val albumsRoot = "{\"type\":\"ROOT_ALBUMS\"}"
        traversal.start(albumsRoot)
        val unknownAlbum = "{\"type\":\"UNKNOWN_ALBUM\",\"storage\":\"$SD_CARD_ID_WITH_ROOT\",\"alb\":-1}"
        Assert.assertEquals(listOf(unknownAlbum), traversal.hierarchy[albumsRoot])
        Assert.assertEquals(6, traversal.hierarchy[unknownAlbum]?.size)
        Assert.assertEquals(
            400,
            traversal.hierarchy["{\"type\":\"TRACK_GROUP\",\"storage\":\"$SD_CARD_ID_WITH_ROOT\",\"alb\":-1,\"trkGrp\":4}"]?.size
        )
        Assert.assertEquals(
            140,
            traversal.hierarchy["{\"type\":\"TRACK_GROUP\",\"storage\":\"$SD_CARD_ID_WITH_ROOT\",\"alb\":-1,\"trkGrp\":5}"]?.size
        )
    }

    @Test
    fun onLoadChildren_manyFilesSDCardImage_createsTrackHierarchy() {
        val traversal = MediaBrowserTraversal(browser)
        val tracksRoot = "{\"type\":\"ROOT_TRACKS\"}"
        traversal.start(tracksRoot)
        Assert.assertEquals("{\"type\":\"SHUFFLE_ALL_TRACKS\"}", traversal.hierarchy[tracksRoot]?.get(0))
        Assert.assertEquals(7, traversal.hierarchy[tracksRoot]?.size)
        Assert.assertEquals(400, traversal.hierarchy["{\"type\":\"TRACK_GROUP\",\"trkGrp\":4}"]?.size)
        Assert.assertEquals(140, traversal.hierarchy["{\"type\":\"TRACK_GROUP\",\"trkGrp\":5}"]?.size)
    }

    @Test
    fun onLoadChildren_manyFilesSDCardImage_createsFilesHierarchy() {
        val traversal = MediaBrowserTraversal(browser)
        val filesRoot = "{\"type\":\"ROOT_FILES\"}"
        traversal.start(filesRoot)
        Assert.assertEquals(11, traversal.hierarchy[filesRoot]?.size)
        Assert.assertEquals(
            "{\"type\":\"DIRECTORY\",\"path\":\"/storage/$SD_CARD_ID$ROOT_DIR/ARTIST_2\"}",
            traversal.hierarchy[filesRoot]?.get(2)
        )
    }

    @Test
    fun onLoadChildren_manyFilesSDCardImage_createsDirectoryHierarchy() {
        val traversal = MediaBrowserTraversal(browser)
        val directoryRoot = "{\"type\":\"DIRECTORY\",\"path\":\"/storage/$SD_CARD_ID$ROOT_DIR/ARTIST_0\"}"
        traversal.start(directoryRoot)
        Assert.assertEquals(10, traversal.hierarchy[directoryRoot]?.size)
    }

}
