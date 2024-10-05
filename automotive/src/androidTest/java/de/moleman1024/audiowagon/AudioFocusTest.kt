/*
SPDX-FileCopyrightText: 2021-2024 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.mpatric.mp3agic.Mp3File
import de.moleman1024.audiowagon.enums.AudioPlayerState
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.mocks.MockUSBDevice
import de.moleman1024.audiowagon.util.ServiceFixture
import de.moleman1024.audiowagon.util.TestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val TAG = "AudioFocusTest"
private const val TEMPLATE_MP3_NAME = "test.mp3"
private const val TEMPLATE_MP3_PATH = "/.template/$TEMPLATE_MP3_NAME"
private const val MUSIC_ROOT = "/Music"
private const val PLAY_ALL_ID = "{\"type\":\"ALL_FILES_FOR_DIRECTORY\",\"path\":\"$MUSIC_ROOT\"}"

@OptIn(ExperimentalCoroutinesApi::class)
class AudioFocusTest {
    private val audioManager: AudioManager =
        InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
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
        releaseAudioFocus()
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
     * https://github.com/MoleMan1024/audiowagon/issues/151
     */
    @Test
    fun audioFocusBlocked_duringStartup_doesNotTriggerErrorMessage() {
        blockAudioFocus()
        createMP3().also { storeMP3(it, "$MUSIC_ROOT/folder1/track1.mp3") }
        attachUSBDevice(mockUSBDevice)
        TestUtils.waitForIndexingCompleted(audioBrowserService)
        Logger.info(TAG, "Will try to start playback (which should be rejected)")
        serviceFixture.transportControls?.playFromMediaId(PLAY_ALL_ID, null)
        Logger.info(TAG, "Assert that we don't see an audio focus error message")
        TestUtils.assertFalseForMillisecOrFail({
            audioBrowserService.getPlaybackState().state == PlaybackStateCompat.STATE_ERROR
        }, 500)
    }

    @Test
    fun audioFocusBlocked_duringStartup_callsOnPlayAgainAfterDelay() {
        blockAudioFocus()
        createMP3().also { storeMP3(it, "$MUSIC_ROOT/folder1/track1.mp3") }
        attachUSBDevice(mockUSBDevice)
        TestUtils.waitForIndexingCompleted(audioBrowserService)
        Logger.info(TAG, "Will try to start playback (which should be rejected)")
        serviceFixture.transportControls?.playFromMediaId(PLAY_ALL_ID, null)
        Thread.sleep(1000)
        releaseAudioFocus()
        TestUtils.waitForAudioPlayerState(PlaybackStateCompat.STATE_PLAYING, audioBrowserService, 5000)
    }

    @Test
    fun audioFocusBlocked_afterStartup_triggersErrorMessage() {
        blockAudioFocus()
        createMP3().also { storeMP3(it, "$MUSIC_ROOT/folder1/track1.mp3") }
        attachUSBDevice(mockUSBDevice)
        TestUtils.waitForIndexingCompleted(audioBrowserService)
        audioBrowserService.setLastWakeupTimeMillis(SystemClock.elapsedRealtime() - 15 * 1000)
        Logger.info(TAG, "Will try to start playback (which should be rejected)")
        serviceFixture.transportControls?.playFromMediaId(PLAY_ALL_ID, null)
        Logger.info(TAG, "Assert that we see an audio focus error message")
        TestUtils.waitForTrueOrFail({
            audioBrowserService.getPlaybackState().state == PlaybackStateCompat.STATE_ERROR
        }, 5000, "Audio focus denial should show an error message")
    }

    private fun blockAudioFocus() {
        // https://source.android.com/docs/automotive/audio/audio-focus#interaction
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).run {
            setAudioAttributes(AudioAttributes.Builder().run {
                setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                setContentType(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                build()
            })
            build()
        }
        audioFocusRequest?.let {
            Logger.info(TAG, "Requesting audio focus")
            val result = audioManager.requestAudioFocus(it)
            Logger.info(TAG, "Audio focus result: $result")
        }
    }

    private fun releaseAudioFocus() {
        audioFocusRequest?.let {
            Logger.info(TAG, "Releasing audio focus")
            audioManager.abandonAudioFocusRequest(it)
            audioFocusRequest = null
        }
    }
}
