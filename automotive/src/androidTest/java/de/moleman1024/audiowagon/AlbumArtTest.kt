/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.content.Intent
import android.content.res.AssetManager
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
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

private const val TAG = "AlbumArtTest"
private const val TEMPLATE_MP3_NAME = "test.mp3"
private const val TEMPLATE_MP3_PATH = "/.template/$TEMPLATE_MP3_NAME"
private const val MUSIC_ROOT = "/Music"
private const val ALBUM_ART = "cover.jpg"
private const val STORAGE_ID = "\"storage\":\"123456789ABC-5118-7715\""

@ExperimentalCoroutinesApi
class AlbumArtTest {
    private lateinit var serviceFixture: ServiceFixture
    private lateinit var browser: MediaBrowserCompat
    private lateinit var audioBrowserService: AudioBrowserService
    private lateinit var mockUSBDevice: MockUSBDevice

    // TODO: remove code duplication

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
        mp3File.save(mockUSBDevice.fileSystem.getPath(filePath))
    }

    /**
     * Regression test for https://github.com/MoleMan1024/audiowagon/issues/88
     */
    @Test
    fun browse_jpgCoverArtInDir_doesNotDeadlock() {
        createMP3().apply {
            id3v2Tag.title = "Track1"
            id3v2Tag.album = "fooAlbum"
        }.also { storeMP3(it, "$MUSIC_ROOT/folder1/track1.mp3") }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assetManager: AssetManager = context.assets
        mockUSBDevice.fileSystem.copyToFile(assetManager.open(ALBUM_ART), "${MUSIC_ROOT}/folder1/$ALBUM_ART")
        attachUSBDevice(mockUSBDevice)
        TestUtils.waitForIndexingCompleted(audioBrowserService)
        val traversal = MediaBrowserTraversal(browser)
        val albumsRoot = "{\"type\":\"ALBUM\",$STORAGE_ID,\"alb\":\"1\"}"
        traversal.start(albumsRoot)
        val albumArtURI = traversal.hierarchy[albumsRoot]?.get(1)?.description?.iconUri
            ?: throw AssertionError("No album art URI")
        val resolver = context.contentResolver
        val proxyFileDescriptor: ParcelFileDescriptor? = resolver.openFile(albumArtURI, "r", null)
        val resizedAlbumArtSize = 3530
        Assert.assertEquals(resizedAlbumArtSize, proxyFileDescriptor?.statSize?.toInt())
        proxyFileDescriptor?.close()
    }
}
