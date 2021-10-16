/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.support.v4.media.MediaBrowserCompat
import de.moleman1024.audiowagon.filestorage.IndexingStatus
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert

private const val TAG = "MediaBrowserTest"
private const val TIMEOUT_MS_LIBRARY_CREATION = 10 * 1000
private const val ID = "E812-A0DC"

// check https://github.com/googlesamples/android-media-controller/blob/master/mediacontroller/src/main/java/com/example/android/mediacontroller/MediaAppTestDetails.kt
// for some implementation ideas
@ExperimentalCoroutinesApi
class MediaBrowserTest {
    // TODO: add more tests
    // use manyFiles.img (TODO: find alternative to have this file available, too big for version control)

    private lateinit var serviceFixture: ServiceFixture
    private lateinit var browser: MediaBrowserCompat

    @Before
    fun setUp() {
        Logger.debug(TAG, "setUp()")
        serviceFixture = ServiceFixture()
        browser = serviceFixture.createMediaBrowser()
        waitForCompletedLibrary(browser)
    }

    private fun waitForCompletedLibrary(browser: MediaBrowserCompat) {
        browser.connect()
        val audioBrowserService = serviceFixture.waitForAudioBrowserService()
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
        val unknownArtist = "{\"type\":\"ARTIST\",\"storage\":\"$ID\",\"art\":-1}"
        Assert.assertEquals(listOf(unknownArtist), traversal.hierarchy[artistsRoot])
        Assert.assertEquals(
            listOf(
                "{\"type\":\"ALL_TRACKS_FOR_ARTIST\",\"storage\":\"$ID\",\"art\":-1}",
                "{\"type\":\"UNKNOWN_ALBUM\",\"storage\":\"$ID\",\"art\":-1,\"alb\":-1}"
            ),
            traversal.hierarchy[unknownArtist]
        )
        Assert.assertEquals(
            400,
            traversal.hierarchy[
                    "{\"type\":\"TRACK_GROUP\",\"storage\":\"$ID\",\"art\":-1,\"alb\":-1,\"trkGrp\":4}"
            ]?.size
        )
        Assert.assertEquals(
            140,
            traversal.hierarchy[
                    "{\"type\":\"TRACK_GROUP\",\"storage\":\"$ID\",\"art\":-1,\"alb\":-1,\"trkGrp\":5}"
            ]?.size
        )
    }

    @Test
    fun onLoadChildren_manyFilesSDCardImage_createsAlbumHierarchy() {
        val traversal = MediaBrowserTraversal(browser)
        val albumsRoot = "{\"type\":\"ROOT_ALBUMS\"}"
        traversal.start(albumsRoot)
        val unknownAlbum = "{\"type\":\"UNKNOWN_ALBUM\",\"storage\":\"E812-A0DC\",\"alb\":-1}"
        Assert.assertEquals(listOf(unknownAlbum), traversal.hierarchy[albumsRoot])
        Assert.assertEquals(6, traversal.hierarchy[unknownAlbum]?.size)
        Assert.assertEquals(
            400,
            traversal.hierarchy["{\"type\":\"TRACK_GROUP\",\"storage\":\"E812-A0DC\",\"alb\":-1,\"trkGrp\":4}"]?.size
        )
        Assert.assertEquals(
            140,
            traversal.hierarchy["{\"type\":\"TRACK_GROUP\",\"storage\":\"E812-A0DC\",\"alb\":-1,\"trkGrp\":5}"]?.size
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
        Assert.assertEquals(21, traversal.hierarchy[filesRoot]?.size)
        Assert.assertEquals(
            "{\"type\":\"DIRECTORY\",\"path\":\"/storage/$ID/ARTIST_0\"}", traversal.hierarchy[filesRoot]?.get(2)
        )
    }

    @Test
    fun onLoadChildren_manyFilesSDCardImage_createsDirectoryHierarchy() {
        val traversal = MediaBrowserTraversal(browser)
        val directoryRoot = "{\"type\":\"DIRECTORY\",\"path\":\"/storage/E812-A0DC/ARTIST_0\"}"
        traversal.start(directoryRoot)
        Assert.assertEquals(10, traversal.hierarchy[directoryRoot]?.size)
    }

}
