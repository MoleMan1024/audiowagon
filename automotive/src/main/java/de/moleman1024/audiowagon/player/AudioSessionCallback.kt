/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.player

import android.net.Uri
import android.os.Bundle
import android.os.ResultReceiver
import android.provider.MediaStore
import android.support.v4.media.session.MediaSessionCompat
import de.moleman1024.audiowagon.*
import de.moleman1024.audiowagon.log.CrashReporting
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.enums.AlbumStyleSetting
import de.moleman1024.audiowagon.enums.AudioItemType
import de.moleman1024.audiowagon.enums.MetadataReadSetting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import android.support.v4.media.session.MediaControllerCompat
import de.moleman1024.audiowagon.activities.SettingsActivity
import de.moleman1024.audiowagon.enums.AudioFocusSetting
import de.moleman1024.audiowagon.enums.AudioSessionChangeType
import de.moleman1024.audiowagon.enums.EqualizerPreset
import de.moleman1024.audiowagon.enums.ViewTabSetting
import de.moleman1024.audiowagon.player.data.AudioSessionChange

private const val TAG = "AudioSessCB"
private val logger = Logger

/**
 * This class will handle callbacks that are called by Android when e.g. user haptically interacts with the
 * MediaBrowser GUI. It will also handle [MediaControllerCompat] commands sent from e.g. the [SettingsActivity]
 *
 * See https://developer.android.com/reference/android/support/v4/media/session/MediaSessionCompat.Callback
 */
@ExperimentalCoroutinesApi
class AudioSessionCallback(
    private val audioPlayer: AudioPlayer,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    crashReporting: CrashReporting,
) : MediaSessionCompat.Callback() {
    val observers = mutableListOf<(AudioSessionChange) -> Unit>()
    private val skipToNextSingletonCoroutine =
        SingletonCoroutine("skipToNext", dispatcher, scope.coroutineContext, crashReporting)
    private val skipToPrevSingletonCoroutine =
        SingletonCoroutine("skipToPrev", dispatcher, scope.coroutineContext, crashReporting)
    private val stopSingletonCoroutine = SingletonCoroutine("stop", dispatcher, scope.coroutineContext, crashReporting)
    private val playSingletonCoroutine = SingletonCoroutine("play", dispatcher, scope.coroutineContext, crashReporting)
    private val pauseSingletonCoroutine =
        SingletonCoroutine("pause", dispatcher, scope.coroutineContext, crashReporting)
    private val seekSingletonCoroutine = SingletonCoroutine("seek", dispatcher, scope.coroutineContext, crashReporting)
    private val playFromSearchSingletonCoroutine =
        SingletonCoroutine("playFromSearch", dispatcher, scope.coroutineContext, crashReporting)
    private val shuffleSingletonCoroutine =
        SingletonCoroutine("shuffle", dispatcher, scope.coroutineContext, crashReporting)
    private val repeatSingletonCoroutine =
        SingletonCoroutine("repeat", dispatcher, scope.coroutineContext, crashReporting)
    private val ejectSingletonCoroutine =
        SingletonCoroutine("eject", dispatcher, scope.coroutineContext, crashReporting)
    private val rewindSingletonCoroutine =
        SingletonCoroutine("rewind", dispatcher, scope.coroutineContext, crashReporting)

    override fun onCommand(command: String, args: Bundle?, cb: ResultReceiver?) {
        logger.debug(TAG, "onCommand(command=$command,args=$args)")
        when (command) {
            CMD_ENABLE_LOG_TO_USB -> {
                notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_ENABLE_LOG_TO_USB))
            }
            CMD_DISABLE_LOG_TO_USB -> {
                notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_DISABLE_LOG_TO_USB))
            }
            CMD_ENABLE_CRASH_REPORTING -> {
                notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_ENABLE_CRASH_REPORTING))
            }
            CMD_DISABLE_CRASH_REPORTING -> {
                notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_DISABLE_CRASH_REPORTING))
            }
            CMD_ENABLE_EQUALIZER -> {
                notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_ENABLE_EQUALIZER))
            }
            CMD_DISABLE_EQUALIZER -> {
                notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_DISABLE_EQUALIZER))
            }
            CMD_SET_EQUALIZER_PRESET -> {
                val audioSessionChange = AudioSessionChange(AudioSessionChangeType.ON_SET_EQUALIZER_PRESET)
                audioSessionChange.equalizerPreset =
                    args?.getString(EQUALIZER_PRESET_KEY, EqualizerPreset.LESS_BASS.name).toString()
                notifyObservers(audioSessionChange)
            }
            CMD_SET_METADATAREAD_SETTING -> {
                val audioSessionChange = AudioSessionChange(AudioSessionChangeType.ON_SET_METADATAREAD_SETTING)
                audioSessionChange.metadataReadSetting = args?.getString(METADATAREAD_SETTING_KEY,
                    MetadataReadSetting.WHEN_USB_CONNECTED.name).toString()
                notifyObservers(audioSessionChange)
            }
            CMD_READ_METADATA_NOW -> {
                notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_READ_METADATA_NOW))
            }
            CMD_ENABLE_REPLAYGAIN -> {
                notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_ENABLE_REPLAYGAIN))
            }
            CMD_DISABLE_REPLAYGAIN -> {
                notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_DISABLE_REPLAYGAIN))
            }
            CMD_SET_AUDIOFOCUS_SETTING -> {
                val audioSessionChange = AudioSessionChange(AudioSessionChangeType.ON_SET_AUDIOFOCUS_SETTING)
                audioSessionChange.audioFocusSetting =
                    args?.getString(AUDIOFOCUS_SETTING_KEY, AudioFocusSetting.PAUSE.name).toString()
                notifyObservers(audioSessionChange)
            }
            CMD_SET_ALBUM_STYLE_SETTING -> {
                val audioSessionChange = AudioSessionChange(AudioSessionChangeType.ON_SET_ALBUM_STYLE)
                audioSessionChange.albumStyleSetting =
                    args?.getString(ALBUM_STYLE_KEY, AlbumStyleSetting.GRID.name).toString()
                notifyObservers(audioSessionChange)
            }
            CMD_SET_VIEW_TABS -> {
                // https://github.com/MoleMan1024/audiowagon/issues/124
                val audioSessionChange = AudioSessionChange(AudioSessionChangeType.ON_SET_VIEW_TABS)
                val viewTabsStr = args?.getStringArray(VIEW_TABS_SETTING_KEY)?.toList() ?: listOf()
                viewTabsStr.forEach { viewTab ->
                    val viewTabEnumValue = ViewTabSetting.valueOf(viewTab)
                    audioSessionChange.viewTabs.add(viewTabEnumValue)
                }
                notifyObservers(audioSessionChange)
            }
            CMD_EJECT -> {
                notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_EJECT))
            }
            CMD_REQUEST_USB_PERMISSION -> {
                notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_REQUEST_USB_PERMISSION))
            }
            else -> {
                logger.warning(TAG, "Unhandled command: $command")
            }
        }
        super.onCommand(command, args, cb)
    }

    override fun onCustomAction(action: String, extras: Bundle?) {
        logger.debug(TAG, "onCustomAction($action, $extras)")
        when (action) {
            ACTION_SHUFFLE_ON -> {
                shuffleSingletonCoroutine.launch {
                    audioPlayer.setShuffleOn()
                }
            }
            ACTION_SHUFFLE_OFF -> {
                shuffleSingletonCoroutine.launch {
                    audioPlayer.setShuffleOff()
                }
            }
            ACTION_REPEAT_ONE_ON -> {
                repeatSingletonCoroutine.launch {
                    audioPlayer.setRepeatOneOn()
                }
            }
            ACTION_REPEAT_ALL_ON -> {
                repeatSingletonCoroutine.launch {
                    audioPlayer.setRepeatAllOn()
                }
            }
            ACTION_REPEAT_OFF -> {
                repeatSingletonCoroutine.launch {
                    audioPlayer.setRepeatOff()
                }
            }
            ACTION_EJECT -> {
                ejectSingletonCoroutine.launch {
                    notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_EJECT))
                }
            }
            ACTION_REWIND_10 -> {
                rewindSingletonCoroutine.launch {
                    audioPlayer.rewind(10000)
                }
            }
            else -> {
                logger.warning(TAG, "Unhandled custom action: $action")
            }
        }
        super.onCustomAction(action, extras)
    }

    override fun onPrepare() {
        logger.debug(TAG, "onPrepare()")
        // TODO: check if we can prepare recent items here already if USB device already connected?
        // TODO: called multiple times at startup? multiple clients?
        super.onPrepare()
    }

    override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) {
        logger.debug(TAG, "onPrepareFromMediaId()")
        super.onPrepareFromMediaId(mediaId, extras)
    }

    override fun onPrepareFromSearch(query: String?, extras: Bundle?) {
        logger.debug(TAG, "onPrepareFromSearch(query=$query,extras=$extras)")
        super.onPrepareFromSearch(query, extras)
    }

    override fun onPlay() {
        logger.debug(TAG, "onPlay()")
        super.onPlay()
        playSingletonCoroutine.launch {
            notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_PLAY))
        }
    }

    override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
        super.onPlayFromMediaId(mediaId, extras)
        logger.debug(TAG, "onPlayFromMediaId(mediaId=$mediaId,extras=$extras)")
        if (mediaId == null) {
            return
        }
        val change = AudioSessionChange(AudioSessionChangeType.ON_PLAY_FROM_MEDIA_ID)
        change.contentHierarchyID = mediaId
        notifyObservers(change)
    }

    /**
     * This is used for Google voice assistant
     * See https://developer.android.com/guide/topics/media-apps/interacting-with-assistant
     *
     * We do not implement an intent filter for MEDIA_PLAY_FROM_SEARCH as written here:
     * https://developer.android.com/guide/components/intents-common#PlaySearch
     * because we don't have an Activity and we don't need to in AAOS:
     * https://developer.android.com/guide/topics/media-apps/interacting-with-assistant#declare_legacy_support_for_voice_actions
     */
    override fun onPlayFromSearch(query: String?, extras: Bundle?) {
        logger.debug(TAG, "onPlayFromSearch(query=$query,extras=$extras)")
        super.onPlayFromSearch(query, extras)
        playFromSearchSingletonCoroutine.launch {
            val audioSessionChange = AudioSessionChange(AudioSessionChangeType.ON_PLAY_FROM_SEARCH)
            if (!query.isNullOrEmpty()) {
                audioSessionChange.queryToPlay = query
                // Google has fixed EXTRA_MEDIA_FOCUS meanwhile ( https://github.com/MoleMan1024/audiowagon/issues/119 )
                val extraMediaFocus = extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)
                extraMediaFocus?.let {
                    when (it) {
                        MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> audioSessionChange.queryFocus = AudioItemType.ARTIST
                        MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> audioSessionChange.queryFocus = AudioItemType.ALBUM
                        MediaStore.Audio.Media.ENTRY_CONTENT_TYPE -> audioSessionChange.queryFocus = AudioItemType.TRACK
                        else -> audioSessionChange.queryFocus = AudioItemType.UNSPECIFIC
                    }
                }
                // these extras from Google Assistant are strange, I have no idea where this data comes from, it fills it
                // with data from other sources (Spotify? Youtube Music?) so it will not match local USB database ...
                val artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST)
                artist?.let { audioSessionChange.artistToPlay = it }
                val album = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM)
                album?.let { audioSessionChange.albumToPlay = it }
                val track = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE)
                track?.let { audioSessionChange.trackToPlay = it }
            }
            notifyObservers(audioSessionChange)
        }
    }

    override fun onPause() {
        logger.debug(TAG, "onPause()")
        super.onPause()
        pauseSingletonCoroutine.launch {
            audioPlayer.pause()
            notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_PAUSE))
        }
    }

    override fun onSeekTo(pos: Long) {
        logger.debug(TAG, "onSeekTo(pos=$pos)")
        super.onSeekTo(pos)
        seekSingletonCoroutine.launch {
            audioPlayer.seekTo(pos.toInt())
        }
    }

    override fun onSkipToNext() {
        logger.debug(TAG, "onSkipToNext()")
        super.onSkipToNext()
        skipToPrevSingletonCoroutine.cancel()
        skipToNextSingletonCoroutine.launch {
            val audioSessionChange = AudioSessionChange(AudioSessionChangeType.ON_SKIP_TO_NEXT)
            notifyObservers(audioSessionChange)
            audioPlayer.skipNextTrack()
        }
    }

    override fun onSkipToPrevious() {
        logger.debug(TAG, "onSkipToPrevious()")
        super.onSkipToPrevious()
        skipToNextSingletonCoroutine.cancel()
        skipToPrevSingletonCoroutine.launch {
            val audioSessionChange = AudioSessionChange(AudioSessionChangeType.ON_SKIP_TO_PREVIOUS)
            notifyObservers(audioSessionChange)
            audioPlayer.handlePrevious()
        }
    }

    override fun onSkipToQueueItem(id: Long) {
        logger.debug(TAG, "onSkipToQueueItem($id)")
        super.onSkipToQueueItem(id)
        val audioSessionChange = AudioSessionChange(AudioSessionChangeType.ON_SKIP_TO_QUEUE_ITEM)
        audioSessionChange.queueID = id
        notifyObservers(audioSessionChange)
    }

    /**
     * This is called for example when you navigate to a different media app (e.g. Bluetooth) or after your exit the
     * car and close all doors
     */
    override fun onStop() {
        logger.debug(TAG, "onStop()")
        // the call to super.onStop() will trigger to release audio focus
        super.onStop()
        stopSingletonCoroutine.launch {
            audioPlayer.stop()
            notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_STOP))
        }
    }

    private fun notifyObservers(change: AudioSessionChange) {
        logger.debug(TAG, "notifyObservers($change)")
        observers.forEach { it(change) }
    }

    override fun onPlayFromUri(uri: Uri?, extras: Bundle?) {
        logger.debug(TAG, "onPlayFromUri(uri=$uri)")
        // not supported, but it seems this is needed for Google Assistant?
        super.onPlayFromUri(uri, extras)
    }

    override fun onPrepareFromUri(uri: Uri?, extras: Bundle?) {
        logger.debug(TAG, "onPrepareFromUri(uri=$uri)")
        // not supported, but it seems this is needed for Google Assistant?
        super.onPrepareFromUri(uri, extras)
    }

    // we don't use onSetShuffleMode and onSetRepeatMode here because AAOS does not display the GUI icons for these
}
