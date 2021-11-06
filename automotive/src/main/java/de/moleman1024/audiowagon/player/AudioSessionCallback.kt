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
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.AudioItemType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

private const val TAG = "AudioSessCB"
private val logger = Logger

/**
 * See https://developer.android.com/reference/android/support/v4/media/session/MediaSessionCompat.Callback
 */
class AudioSessionCallback(
    private val audioPlayer: AudioPlayer,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher
) : MediaSessionCompat.Callback() {

    val observers = mutableListOf<(AudioSessionChange) -> Unit>()

    override fun onCommand(command: String, args: Bundle?, cb: ResultReceiver?) {
        logger.debug(TAG, "onCommand(command=$command,args=$args)")
        when (command) {
            CMD_ENABLE_LOG_TO_USB -> {
                notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_ENABLE_LOG_TO_USB))
            }
            CMD_DISABLE_LOG_TO_USB -> {
                notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_DISABLE_LOG_TO_USB))
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
            CMD_ENABLE_READ_METADATA -> {
                notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_ENABLE_READ_METADATA))
            }
            CMD_DISABLE_READ_METADTA -> {
                notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_DISABLE_READ_METADATA))
            }
            CMD_ENABLE_REPLAYGAIN -> {
                notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_ENABLE_REPLAYGAIN))
            }
            CMD_DISABLE_REPLAYGAIN -> {
                notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_DISABLE_REPLAYGAIN))
            }
            CMD_EJECT -> {
                notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_EJECT))
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
                launchInScopeSafely {
                    audioPlayer.setShuffleOn()
                }
            }
            ACTION_SHUFFLE_OFF -> {
                launchInScopeSafely {
                    audioPlayer.setShuffleOff()
                }
            }
            ACTION_REPEAT_ON -> {
                launchInScopeSafely {
                    audioPlayer.setRepeatOn()
                }
            }
            ACTION_REPEAT_OFF -> {
                launchInScopeSafely {
                    audioPlayer.setRepeatOff()
                }
            }
            ACTION_EJECT -> {
                launchInScopeSafely {
                    notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_EJECT))
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
        notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_PLAY))
        super.onPlay()
    }

    override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
        super.onPlayFromMediaId(mediaId, extras)
        logger.debug(TAG, "onPlayFromMediaId(mediaId=$mediaId;extras=$extras")
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
     * because because we don't have an activity and we don't need to in AAOS:
     * https://developer.android.com/guide/topics/media-apps/interacting-with-assistant#declare_legacy_support_for_voice_actions
     */
    override fun onPlayFromSearch(query: String?, extras: Bundle?) {
        logger.debug(TAG, "onPlayFromSearch(query=$query;extras=$extras)")
        super.onPlayFromSearch(query, extras)
        val audioSessionChange = AudioSessionChange(AudioSessionChangeType.ON_PLAY_FROM_SEARCH)
        if (!query.isNullOrEmpty()) {
            audioSessionChange.queryToPlay = query
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
            // genre and playlist not supported
            val genre = extras?.getString(MediaStore.EXTRA_MEDIA_GENRE)
            if (!genre.isNullOrEmpty()) {
                logger.warning(TAG, "Extra genre, not supported: $genre")
            }
            val playlist = extras?.getString(MediaStore.EXTRA_MEDIA_PLAYLIST)
            if (!playlist.isNullOrEmpty()) {
                logger.warning(TAG, "Extra playlist, not supported: $playlist")
            }
        }
        launchInScopeSafely {
            notifyObservers(audioSessionChange)
        }
    }

    override fun onPause() {
        logger.debug(TAG, "onPause()")
        super.onPause()
        launchInScopeSafely {
            audioPlayer.pause()
            notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_PAUSE))
        }
    }

    override fun onSeekTo(pos: Long) {
        logger.debug(TAG, "onSeekTo(pos=$pos)")
        super.onSeekTo(pos)
        launchInScopeSafely {
            audioPlayer.seekTo(pos.toInt())
        }
    }

    override fun onSkipToNext() {
        logger.debug(TAG, "onSkipToNext()")
        super.onSkipToNext()
        launchInScopeSafely {
            audioPlayer.skipNextTrack()
        }
    }

    override fun onSkipToPrevious() {
        logger.debug(TAG, "onSkipToPrevious()")
        super.onSkipToPrevious()
        launchInScopeSafely {
            audioPlayer.skipPreviousTrack()
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
        // the call to super.onStop() will release audio focus
        super.onStop()
        launchInScopeSafely {
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

    private fun launchInScopeSafely(func: suspend () -> Unit) {
        Util.launchInScopeSafely(scope, dispatcher, logger, TAG, func)
    }

    // we don't use onSetShuffleMode and onSetRepeatMode here because AAOS does not display the GUI icons for these
}
