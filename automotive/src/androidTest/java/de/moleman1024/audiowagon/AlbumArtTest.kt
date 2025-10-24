/*
SPDX-FileCopyrightText: 2021-2025 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.content.res.AssetManager
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.support.v4.media.MediaBrowserCompat
import android.util.Log
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.platform.app.InstrumentationRegistry
import de.moleman1024.audiowagon.activities.SettingsFragment
import de.moleman1024.audiowagon.filestorage.data.GeneralFile
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
import java.io.File
import java.io.FileInputStream


private const val TAG = "AlbumArtTest"
private const val MUSIC_ROOT = "/Music"
private const val COVER_JPG = "cover.jpg"
private const val STORAGE_ID = "123456789ABC-5118-7715"

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
        audioBrowserService.exposeUSBInternalBroadcastReceiver()
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
        mockUSBDeviceFixture.fileSystem.copyToFile(assetManager.open(COVER_JPG), "${MUSIC_ROOT}/folder1/$COVER_JPG")
        mockUSBDeviceFixture.attachUSBDevice()
        TestUtils.waitForIndexingCompleted(audioBrowserService)
        val traversal = MediaBrowserTraversal(browser)
        val albumsRoot = "{\"type\":\"ALBUM\",\"storage\":\"$STORAGE_ID\",\"alb\":\"1\"}"
        traversal.start(albumsRoot)
        val albumArtURI = traversal.hierarchy[albumsRoot]?.get(1)?.description?.iconUri
            ?: throw AssertionError("No album art URI")
        val resolver = context.contentResolver
        val parcelFileDescriptor: ParcelFileDescriptor? = resolver.openFileDescriptor(albumArtURI, "r", null)
        Logger.debug(TAG, "parcelFileDescriptor=$parcelFileDescriptor")
        val fileInputStream = FileInputStream(parcelFileDescriptor?.fileDescriptor)
        try {
            val buffer = ByteArray(1024)
            var numBytesRead: Int
            var numBytesReadTotal = 0
            while (fileInputStream.read(buffer).also { numBytesRead = it } != -1) {
                numBytesReadTotal += numBytesRead
            }
            val resizedAlbumArtSize = 3450
            Assert.assertEquals(resizedAlbumArtSize, numBytesReadTotal)
        } finally {
            fileInputStream.close()
            parcelFileDescriptor?.close()
        }
    }

    @Test
    fun indexAudioFiles_jpgCoverArtInDir_createsResizedAlbumArtCacheOnDisk() {
        mockUSBDeviceFixture.createMP3().apply {
            id3v2Tag.title = "Track1"
            id3v2Tag.album = "Album1"
        }.also { mockUSBDeviceFixture.storeMP3(it, "$MUSIC_ROOT/folder1/track1.mp3") }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assetManager: AssetManager = context.assets
        mockUSBDeviceFixture.fileSystem.copyToFile(assetManager.open(COVER_JPG), "${MUSIC_ROOT}/folder1/$COVER_JPG")
        mockUSBDeviceFixture.attachUSBDevice()
        TestUtils.waitForIndexingCompleted(audioBrowserService)
        val albumArtURI = Uri.parse("usbAudio://$STORAGE_ID/Music%2Ffolder1%2F$COVER_JPG")
        val albumArtCacheDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "albumart")
        val albumArtCacheForStorageDir = File(albumArtCacheDir, STORAGE_ID)
        val albumArtHash = Util.createIDForAlbumArtForFile(GeneralFile(albumArtURI).path)
        Log.d(TAG, "Expecting cached album art at $albumArtCacheForStorageDir with name $albumArtHash")
        Assert.assertTrue(File(albumArtCacheForStorageDir, "$albumArtHash.jpg").exists())
        Assert.assertTrue(File(albumArtCacheForStorageDir, "$albumArtHash.md5").exists())
    }

    @Test
    fun deleteDatabaseAndAlbumArt_cachedAlbumArtExists_deletesFiles() {
        mockUSBDeviceFixture.createMP3().apply {
            id3v2Tag.title = "Track1"
            id3v2Tag.album = "Album1"
        }.also { mockUSBDeviceFixture.storeMP3(it, "$MUSIC_ROOT/folder1/track1.mp3") }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assetManager: AssetManager = context.assets
        mockUSBDeviceFixture.fileSystem.copyToFile(assetManager.open(COVER_JPG), "${MUSIC_ROOT}/folder1/$COVER_JPG")
        mockUSBDeviceFixture.attachUSBDevice()
        TestUtils.waitForIndexingCompleted(audioBrowserService)
        val albumArtURI = Uri.parse("usbAudio://$STORAGE_ID/Music%2Ffolder1%2F$COVER_JPG")
        val albumArtCacheDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "albumart")
        val albumArtCacheForStorageDir = File(albumArtCacheDir, STORAGE_ID)
        val albumArtHash = Util.createIDForAlbumArtForFile(GeneralFile(albumArtURI).path)
        val albumArtFile = File(albumArtCacheForStorageDir, "$albumArtHash.jpg")
        val albumArtHashFile = File(albumArtCacheForStorageDir, "$albumArtHash.jpg")
        Assert.assertTrue(albumArtFile.exists())
        Assert.assertTrue(albumArtHashFile.exists())
        val scenario = launchFragmentInContainer<SettingsFragment>()
        try {
            scenario.onFragment { fragment ->
                val databaseFile = File(context?.dataDir.toString() + "/databases/audioItemRepo_$STORAGE_ID.sqlite")
                fragment.deleteDatabaseAndAlbumArt(databaseFile)
                Assert.assertFalse(albumArtFile.exists())
                Assert.assertFalse(albumArtHashFile.exists())
            }
        } finally {
            scenario.close()
        }
    }

}
