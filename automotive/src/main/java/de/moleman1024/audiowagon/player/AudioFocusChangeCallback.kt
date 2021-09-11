/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.player

import android.media.AudioManager
import android.support.v4.media.session.PlaybackStateCompat
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.*

private const val TAG = "AudioFocusChangeCB"
private val logger = Logger

class AudioFocusChangeCallback(
    private val audioPlayer: AudioPlayer,
    val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) : AudioManager.OnAudioFocusChangeListener {
    private var audioFocusLossJob: Job? = null
    @get:Synchronized @set:Synchronized
    private var isAudioFocusLostTransient = false
    var playbackState: Int = PlaybackStateCompat.STATE_NONE
    var lastUserRequestedStateChange: AudioSessionChangeType = AudioSessionChangeType.ON_STOP

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                logger.debug(TAG, "Audio focus loss")
                audioFocusLossJob = scope.launch(dispatcher) {
                    if (playbackState == PlaybackStateCompat.STATE_STOPPED) {
                        logger.debug(TAG, "Nothing to do for audio focus since player already stopped")
                        return@launch
                    }
                    audioPlayer.pause()
                    logger.debug(TAG, "Stopping player in 30 seconds")
                    delay(1000 * 30)
                    audioPlayer.stop()
                    audioFocusLossJob = null
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                logger.debug(TAG, "Audio focus transient loss")
                isAudioFocusLostTransient = true
                scope.launch(dispatcher) {
                    if (playbackState == PlaybackStateCompat.STATE_PAUSED) {
                        logger.debug(TAG, "Nothing to do for audio focus since player already paused")
                        return@launch
                    }
                    audioPlayer.pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                logger.debug(TAG, "Audio focus gain")
                scope.launch(dispatcher) {
                    cancelAudioFocusLossJob()
                    if (isAudioFocusLostTransient) {
                        logger.debug(TAG, "Audio focus re-gained")
                        isAudioFocusLostTransient = false
                        if (lastUserRequestedStateChange != AudioSessionChangeType.ON_PLAY) {
                            logger.debug(TAG, "User has previously paused/stopped playback, ignore audio focus gain")
                            return@launch
                        }
                        audioPlayer.start()
                    }
                }
            }
        }
    }

    suspend fun cancelAudioFocusLossJob() {
        if (audioFocusLossJob == null) {
            return
        }
        logger.debug(TAG, "cancelAudioFocusLossJob()")
        audioFocusLossJob?.cancelAndJoin()
        audioFocusLossJob = null
    }

}
