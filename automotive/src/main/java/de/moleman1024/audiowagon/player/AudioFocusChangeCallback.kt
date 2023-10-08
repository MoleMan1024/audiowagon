/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.player

import android.media.AudioManager
import android.support.v4.media.session.PlaybackStateCompat
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.enums.AudioFocusSetting
import de.moleman1024.audiowagon.enums.AudioSessionChangeType
import de.moleman1024.audiowagon.log.CrashReporting
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.*

private const val TAG = "AudioFocusChangeCB"
private val logger = Logger

@ExperimentalCoroutinesApi
class AudioFocusChangeCallback(
    private val audioPlayer: AudioPlayer,
    val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val crashReporting: CrashReporting
) : AudioManager.OnAudioFocusChangeListener {
    private var audioFocusLossJob: Job? = null
    @get:Synchronized @set:Synchronized
    private var isAudioFocusLostTransient = false
    var behaviour: AudioFocusSetting = AudioFocusSetting.PAUSE
    private var isDucked = false
    var playbackState: Int = PlaybackStateCompat.STATE_NONE
    var lastUserRequestedStateChange: AudioSessionChangeType = AudioSessionChangeType.ON_STOP

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                logger.debug(TAG, "Audio focus loss")
                audioFocusLossJob = launchInScopeSafely {
                    if (playbackState == PlaybackStateCompat.STATE_STOPPED) {
                        logger.debug(TAG, "Nothing to do for audio focus since player already stopped")
                        return@launchInScopeSafely
                    }
                    audioPlayer.pause()
                    // same implementation as in
                    // https://developer.android.com/guide/topics/media-apps/audio-focus#audio-focus-change
                    logger.debug(TAG, "Stopping player in 30 seconds")
                    delay(1000L * 30L)
                    audioPlayer.stop()
                    audioFocusLossJob = null
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                logger.debug(TAG, "Audio focus transient loss (behaviour=$behaviour)")
                isAudioFocusLostTransient = true
                launchInScopeSafely {
                    if (playbackState == PlaybackStateCompat.STATE_PAUSED) {
                        logger.debug(TAG, "Nothing to do for audio focus since player already paused")
                        return@launchInScopeSafely
                    }
                    audioPlayer.pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                logger.debug(TAG, "Audio focus transient loss (can duck) (behaviour=$behaviour)")
                isAudioFocusLostTransient = true
                when (behaviour) {
                    AudioFocusSetting.PAUSE -> {
                        launchInScopeSafely {
                            if (playbackState == PlaybackStateCompat.STATE_PAUSED) {
                                logger.debug(TAG, "Nothing to do for audio focus since player already paused")
                                return@launchInScopeSafely
                            }
                            audioPlayer.pause()
                        }
                    }
                    AudioFocusSetting.DUCK -> {
                        isDucked = true
                    }
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                logger.debug(TAG, "Audio focus gain (behaviour=$behaviour)")
                launchInScopeSafely {
                    cancelAudioFocusLossJob()
                    if (isAudioFocusLostTransient) {
                        logger.debug(TAG, "Audio focus re-gained")
                        isAudioFocusLostTransient = false
                        if (behaviour == AudioFocusSetting.PAUSE
                            || (behaviour == AudioFocusSetting.DUCK && !isDucked)) {
                            if (lastUserRequestedStateChange != AudioSessionChangeType.ON_PLAY) {
                                logger.debug(
                                    TAG, "User has previously paused/stopped playback, ignore audio focus gain"
                                )
                                return@launchInScopeSafely
                            }
                            audioPlayer.start()
                        }
                        isDucked = false
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

    private fun launchInScopeSafely(func: suspend (CoroutineScope) -> Unit): Job {
        return Util.launchInScopeSafely(scope, dispatcher, logger, TAG, crashReporting, func)
    }

}
