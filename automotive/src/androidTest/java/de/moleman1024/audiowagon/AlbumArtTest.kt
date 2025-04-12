/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.content.res.AssetManager
import android.os.ParcelFileDescriptor
import android.support.v4.media.MediaBrowserCompat
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.util.MediaBrowserTraversal
import de.moleman1024.audiowagon.util.MockUSBDeviceFixture
import de.moleman1024.audiowagon.util.ServiceFixture
import de.moleman1024.audiowagon.util.TestUtils
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.io.FileInputStream


private const val TAG = "AlbumArtTest"
private const val MUSIC_ROOT = "/Music"
private const val ALBUM_ART = "cover.jpg"
private const val STORAGE_ID = "\"storage\":\"123456789ABC-5118-7715\""

@ExperimentalCoroutinesApi
class AlbumArtTest {
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
     * Regression test for https://github.com/MoleMan1024/audiowagon/issues/88
     */
    @Test
    fun browse_jpgCoverArtInDir_doesNotDeadlock() {
        mockUSBDeviceFixture.createMP3().apply {
            id3v2Tag.title = "Track1"
            id3v2Tag.album = "fooAlbum"
        }.also { mockUSBDeviceFixture.storeMP3(it, "$MUSIC_ROOT/folder1/track1.mp3") }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assetManager: AssetManager = context.assets
        mockUSBDeviceFixture.fileSystem.copyToFile(assetManager.open(ALBUM_ART), "${MUSIC_ROOT}/folder1/$ALBUM_ART")
        mockUSBDeviceFixture.attachUSBDevice()
        TestUtils.waitForIndexingCompleted(audioBrowserService)
        val traversal = MediaBrowserTraversal(browser)
        val albumsRoot = "{\"type\":\"ALBUM\",$STORAGE_ID,\"alb\":\"1\"}"
        traversal.start(albumsRoot)
        val albumArtURI = traversal.hierarchy[albumsRoot]?.get(1)?.description?.iconUri
            ?: throw AssertionError("No album art URI")
        val resolver = context.contentResolver
        val parcelFileDescriptor: ParcelFileDescriptor? = resolver.openFileDescriptor(albumArtURI, "r", null)
        val fileInputStream = FileInputStream(parcelFileDescriptor?.fileDescriptor)
        val buffer = ByteArray(1024)
        var numBytesRead: Int
        var numBytesReadTotal = 0
        while (fileInputStream.read(buffer).also { numBytesRead = it } != -1) {
            numBytesReadTotal += numBytesRead
        }
        val resizedAlbumArtSize = 3530
        Assert.assertEquals(resizedAlbumArtSize, numBytesReadTotal)
        fileInputStream.close()
        parcelFileDescriptor?.close()
    }

}
