/*
SPDX-FileCopyrightText: 2021-2022 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.content.Intent
import android.content.res.AssetManager
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.PlaybackStateCompat
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

private const val TAG = "PlaybackTest"
private const val TEMPLATE_MP3_NAME = "test.mp3"
private const val TEMPLATE_MP3_PATH = "/.template/$TEMPLATE_MP3_NAME"
private const val MUSIC_ROOT = "/Music"
private const val STORAGE_ID = "\"storage\":\"123456789ABC-5118-7715\""

@ExperimentalCoroutinesApi
class PlaybackTest {
    private lateinit var serviceFixture: ServiceFixture
    private lateinit var browser: MediaBrowserCompat
    private lateinit var audioBrowserService: AudioBrowserService
    private lateinit var mockUSBDevice: MockUSBDevice

    @Before
    fun setUp() {
        Logger.debug(TAG, "setUp()")
        TestUtils.deleteDatabaseDirectory()
        // TODO: delete persisted playback queue
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
     * https://github.com/MoleMan1024/audiowagon/issues/50
     */
    @Test
    fun playAll_hasSubdirectories_playRecursively() {
        createMP3().apply {
            id3v2Tag.title = "Track1"
        }.also { storeMP3(it, "$MUSIC_ROOT/folder1/track1.mp3") }
        createMP3().apply {
            id3v2Tag.title = "Track2"
        }.also { storeMP3(it, "$MUSIC_ROOT/folder1/track2.mp3") }
        createMP3().apply {
            id3v2Tag.title = "Track3"
        }.also { storeMP3(it, "$MUSIC_ROOT/folder2/track3.mp3") }
        attachUSBDevice(mockUSBDevice)
        TestUtils.waitForIndexingCompleted(audioBrowserService)
        val traversal = MediaBrowserTraversal(browser)
        val filesRoot = "{\"type\":\"DIRECTORY\",\"path\":\"$MUSIC_ROOT\"}"
        traversal.start(filesRoot)
        Assert.assertEquals("Play all", traversal.hierarchy[filesRoot]?.get(0)?.description?.title)
        val playAllID = "{\"type\":\"ALL_FILES_FOR_DIRECTORY\",\"path\":\"$MUSIC_ROOT\"}"
        serviceFixture.transportControls?.playFromMediaId(playAllID, null)
        TestUtils.waitForAudioPlayerState(PlaybackStateCompat.STATE_PLAYING, audioBrowserService)
        Assert.assertEquals(3, serviceFixture.playbackQueue?.size)
    }

    /**
     * https://github.com/MoleMan1024/audiowagon/issues/83
     */
    @Test
    fun shuffleNewPlaybackQueue_default_firstTrackIsShuffled() {
        serviceFixture.transportControls?.sendCustomAction("de.moleman1024.audiowagon.ACTION_SHUFFLE_OFF", null)
        for (index in 0..20) {
            createMP3().apply {
                id3v2Tag.title = "Track${index}"
            }.also { storeMP3(it, "$MUSIC_ROOT/folder/track${index}.mp3") }
        }
        attachUSBDevice(mockUSBDevice)
        TestUtils.waitForIndexingCompleted(audioBrowserService)
        // TODO: improve, wait for persistance
        Thread.sleep(1000)
        serviceFixture.transportControls?.stop()
        Thread.sleep(500)
        serviceFixture.transportControls?.sendCustomAction("de.moleman1024.audiowagon.ACTION_SHUFFLE_ON", null)
        Thread.sleep(500)
        val playAlbumID = "{\"type\":\"ALL_TRACKS_FOR_ALBUM\",\"alb\":1,$STORAGE_ID}"
        var lastTrack = ""
        // with shuffle on when playing back the same album multiple times we should get a random order also for
        // first item in playback queue
        var differentItemFound = false
        for (tries in 0..10) {
            serviceFixture.transportControls?.playFromMediaId(playAlbumID, null)
            TestUtils.waitForAudioPlayerState(PlaybackStateCompat.STATE_PLAYING, audioBrowserService)
            serviceFixture.transportControls?.pause()
            TestUtils.waitForAudioPlayerState(PlaybackStateCompat.STATE_PAUSED, audioBrowserService)
            val firstTrackMediaID = serviceFixture.playbackQueue?.get(0)?.description?.mediaId.toString()
            if (lastTrack.isNotBlank() && firstTrackMediaID.isNotBlank()) {
                if (lastTrack != firstTrackMediaID) {
                    differentItemFound = true
                    break
                }
            }
            lastTrack = firstTrackMediaID
        }
        if (!differentItemFound) {
            throw AssertionError("First item in playback queue was not shuffled")
        }
    }

    /**
     * https://github.com/MoleMan1024/audiowagon/issues/119
     */
    @Test
    fun onPlayFromSearch_voiceSearchWithArtist_playsArtist() {
        val artistName = "Billy Talent"
        val numTracks = 10
        for (index in 0 until numTracks) {
            createMP3().apply {
                id3v2Tag.artist = artistName
                id3v2Tag.title = "Track${index}"
            }.also { storeMP3(it, "$MUSIC_ROOT/${artistName}/track${index}.mp3")}
        }
        attachUSBDevice(mockUSBDevice)
        TestUtils.waitForIndexingCompleted(audioBrowserService)
        Thread.sleep(1000)
        serviceFixture.transportControls?.stop()
        val queryExtras = Bundle().apply {
            putString("android.intent.extra.focus", "vnd.android.cursor.item/artist")
        }
        serviceFixture.transportControls?.playFromSearch(artistName, queryExtras)
        TestUtils.waitForAudioPlayerState(PlaybackStateCompat.STATE_PLAYING, audioBrowserService)
        Assert.assertEquals(numTracks, serviceFixture.playbackQueue?.size)
    }

}
