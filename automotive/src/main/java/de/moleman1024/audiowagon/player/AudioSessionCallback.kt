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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "AudioSessCB"
private val logger = Logger

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
                scope.launch(dispatcher) {
                    audioPlayer.setShuffleOn()
                }
            }
            ACTION_SHUFFLE_OFF -> {
                scope.launch(dispatcher) {
                    audioPlayer.setShuffleOff()
                }
            }
            ACTION_REPEAT_ON -> {
                scope.launch(dispatcher) {
                    audioPlayer.setRepeatOn()
                }
            }
            ACTION_REPEAT_OFF -> {
                scope.launch(dispatcher) {
                    audioPlayer.setRepeatOff()
                }
            }
            ACTION_EJECT -> {
                scope.launch(dispatcher) {
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
     * See https://developer.android.com/guide/topics/media-apps/interacting-with-assistant#playback-from-service
     * TODO: Not triggered by voice assistant, no clue why
     */
    override fun onPlayFromSearch(query: String?, extras: Bundle?) {
        logger.debug(TAG, "onPlayFromSearch(query=$query;extras=$extras)")
        super.onPlayFromSearch(query, extras)
        val audioSessionChange = AudioSessionChange(AudioSessionChangeType.ON_PLAY_FROM_SEARCH)
        if (!query.isNullOrEmpty()) {
            when (extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)) {
                MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> {
                    val artist = extras.getString(MediaStore.EXTRA_MEDIA_ARTIST)
                    artist?.let { audioSessionChange.artistToPlay = it }
                }
                MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> {
                    val album = extras.getString(MediaStore.EXTRA_MEDIA_ALBUM)
                    album?.let { audioSessionChange.albumToPlay = it }
                }
                MediaStore.Audio.Media.ENTRY_CONTENT_TYPE -> {
                    val track = extras.getString(MediaStore.EXTRA_MEDIA_TITLE)
                    track?.let { audioSessionChange.trackToPlay = it }
                }
                // genre and playlist not supported
                else -> {
                    audioSessionChange.unspecificToPlay = query
                }
            }
        }
        scope.launch(dispatcher) {
            notifyObservers(audioSessionChange)
        }
    }

    override fun onPause() {
        logger.debug(TAG, "onPause()")
        super.onPause()
        scope.launch(dispatcher) {
            audioPlayer.pause()
            // TODO: maybe release audio source, when started again start from stored position? To prevent issue:
            //  when USB device unplugged when media data source is still referenced it cannot be closed
            notifyObservers(AudioSessionChange(AudioSessionChangeType.ON_PAUSE))
        }
    }

    override fun onSeekTo(pos: Long) {
        logger.debug(TAG, "onSeekTo(pos=$pos)")
        super.onSeekTo(pos)
        scope.launch(dispatcher) {
            audioPlayer.seekTo(pos.toInt())
        }
    }

    override fun onSkipToNext() {
        logger.debug(TAG, "onSkipToNext()")
        super.onSkipToNext()
        scope.launch(dispatcher) {
            audioPlayer.skipNextTrack()
        }
    }

    override fun onSkipToPrevious() {
        logger.debug(TAG, "onSkipToPrevious()")
        super.onSkipToPrevious()
        scope.launch(dispatcher) {
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
        // super.onStop() will release audio focus
        super.onStop()
        scope.launch(dispatcher) {
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
