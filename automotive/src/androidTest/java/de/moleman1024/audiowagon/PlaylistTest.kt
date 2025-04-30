/*
SPDX-FileCopyrightText: 2025 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.PlaybackStateCompat
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.util.MockUSBDeviceFixture
import de.moleman1024.audiowagon.util.ServiceFixture
import de.moleman1024.audiowagon.util.TestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test

private const val TAG = "PlaylistTest"
private const val MUSIC_ROOT = "/Music"

@ExperimentalCoroutinesApi
class PlaylistTest {
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
     * Parsing playlist files with URL encoded paths
     * https://github.com/MoleMan1024/audiowagon/issues/170
     */
    @Test
    fun playPlaylist_playlistPathsURLEncoded_parsesPlaylistProperly() {
        mockUSBDeviceFixture.createMP3().apply {
            id3v2Tag.title = "Track1"
        }.also { mockUSBDeviceFixture.storeMP3(it, "$MUSIC_ROOT/folder1/track A.mp3") }
        mockUSBDeviceFixture.createMP3().apply {
            id3v2Tag.title = "Track2"
        }.also { mockUSBDeviceFixture.storeMP3(it, "$MUSIC_ROOT/folder 2/track B.mp3") }
        val playlistContent = """
            #EXTM3U
            #EXTINF:255,track A
            file:///D:/Music/folder1/track%20A.mp3
            #EXTINF:232,track B
            file:///D:/Music/folder%202/track%20B.mp3
            """.trimIndent()
        mockUSBDeviceFixture.fileSystem.writeTextToFile("${MUSIC_ROOT}/playlist.m3u", playlistContent)
        mockUSBDeviceFixture.attachUSBDevice()
        TestUtils.waitForIndexingCompleted(audioBrowserService)
        val playPlaylistID = "{\"type\":\"PLAYLIST\",\"path\":\"$MUSIC_ROOT/playlist.m3u\"}"
        serviceFixture.transportControls?.playFromMediaId(playPlaylistID, null)
        TestUtils.waitForAudioPlayerState(PlaybackStateCompat.STATE_PLAYING, audioBrowserService)
        Assert.assertEquals(2, serviceFixture.playbackQueue?.size)
    }

    /**
     * Parsing playlist files with regular file paths
     */
    @Test
    fun playPlaylist_windowsPlaylistPaths_parsesPlaylistProperly() {
        mockUSBDeviceFixture.createMP3().apply {
            id3v2Tag.title = "Track1"
        }.also { mockUSBDeviceFixture.storeMP3(it, "$MUSIC_ROOT/folder1/track A.mp3") }
        mockUSBDeviceFixture.createMP3().apply {
            id3v2Tag.title = "Track2"
        }.also { mockUSBDeviceFixture.storeMP3(it, "$MUSIC_ROOT/folder 2/track B.mp3") }
        mockUSBDeviceFixture.createMP3().apply {
            id3v2Tag.title = "Track 3"
        }.also { mockUSBDeviceFixture.storeMP3(it, "$MUSIC_ROOT/folder 3/track C.mp3") }
        val playlistContent = """
            #EXTM3U
            #EXTINF:255,track A
            \Music\folder1\track A.mp3
            #EXTINF:232,track B
            \Music\folder 2/track B.mp3
            #EXTINF:42,track C
            \Music\folder 3/track C.mp3
            """.trimIndent()
        mockUSBDeviceFixture.fileSystem.writeTextToFile("${MUSIC_ROOT}/playlist.m3u", playlistContent)
        mockUSBDeviceFixture.attachUSBDevice()
        TestUtils.waitForIndexingCompleted(audioBrowserService)
        val playPlaylistID = "{\"type\":\"PLAYLIST\",\"path\":\"$MUSIC_ROOT/playlist.m3u\"}"
        serviceFixture.transportControls?.playFromMediaId(playPlaylistID, null)
        TestUtils.waitForAudioPlayerState(PlaybackStateCompat.STATE_PLAYING, audioBrowserService)
        Assert.assertEquals(3, serviceFixture.playbackQueue?.size)
    }

    /**
     * Issue #173 ( https://github.com/MoleMan1024/audiowagon/issues/173 )
     */
    @Test
    fun playPlaylist_nulCharacterInFilepath_parsesPlaylistProperly() {
        mockUSBDeviceFixture.createMP3().apply {
            id3v2Tag.title = "Track1"
        }.also { mockUSBDeviceFixture.storeMP3(it, "$MUSIC_ROOT/folder1/trackA.mp3") }
        mockUSBDeviceFixture.createMP3().apply {
            id3v2Tag.title = "Track2"
        }.also { mockUSBDeviceFixture.storeMP3(it, "$MUSIC_ROOT/folder 2/trackB.mp3") }
        val playlistContent = "#EXTM3U\n" +
                "#EXTINF:255,track A\n" +
                "\\Music\\folder1\\track0" + 0.toChar().toString() + "A.mp3\n" +
                "#EXTINF:232,track B\n" +
                "\\Music\\folder 2/trackB.mp3".trimIndent()
        mockUSBDeviceFixture.fileSystem.writeTextToFile("${MUSIC_ROOT}/playlist.m3u", playlistContent)
        mockUSBDeviceFixture.attachUSBDevice()
        TestUtils.waitForIndexingCompleted(audioBrowserService)
        val playPlaylistID = "{\"type\":\"PLAYLIST\",\"path\":\"$MUSIC_ROOT/playlist.m3u\"}"
        serviceFixture.transportControls?.playFromMediaId(playPlaylistID, null)
        TestUtils.waitForAudioPlayerState(PlaybackStateCompat.STATE_PLAYING, audioBrowserService)
        // The file with NUL in filepath should be ignored
        Assert.assertEquals(1, serviceFixture.playbackQueue?.size)
    }

}
