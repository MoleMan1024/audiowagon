/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import de.moleman1024.audiowagon.log.Logger

private const val TAG = "AudioFocus"
private val logger = Logger

/**
 * See https://developer.android.com/guide/topics/media-apps/audio-focus#audio-focus-8-0
 * and https://source.android.com/devices/automotive
 * and https://developer.android.com/guide/topics/media-apps/audio-app/mediasession-callbacks
 */
class AudioFocus(context: Context) {
    private var audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var audioFocusRequest: AudioFocusRequest? = null
    // the user can choose the behaviour for audio focus e.g. during route guidance prompts (pausing / ducking)
    // ( see https://github.com/MoleMan1024/audiowagon/issues/24 )
    private var behaviour = AudioFocusSetting.PAUSE
    var audioFocusChangeListener: AudioFocusChangeCallback? = null

    fun request(): AudioFocusRequestResult {
        logger.debug(TAG, "request()")
        if (audioFocusChangeListener == null) {
            throw RuntimeException("Audio focus change callback not initialized")
        }
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).run {
            setAudioAttributes(AudioAttributes.Builder().run {
                setUsage(AudioAttributes.USAGE_MEDIA)
                setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                build()
            })
            setAcceptsDelayedFocusGain(false)
            // we rely on automatic ducking from Android system
            // (see https://developer.android.com/guide/topics/media-apps/audio-focus#automatic-ducking )
            setWillPauseWhenDucked(behaviour == AudioFocusSetting.PAUSE)
            setOnAudioFocusChangeListener(audioFocusChangeListener!!, handler)
            build()
        }
        audioFocusRequest = focusRequest
        val result = when (audioManager.requestAudioFocus(focusRequest)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> AudioFocusRequestResult.GRANTED
            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> AudioFocusRequestResult.DENIED
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> AudioFocusRequestResult.DELAYED
            else -> AudioFocusRequestResult.DENIED
        }
        logger.debug(TAG, "result=$result")
        return result
    }

    fun release() {
        audioFocusRequest?.let {
            logger.debug(TAG, "abandonAudioFocusRequest()")
            audioManager.abandonAudioFocusRequest(it)
            audioFocusRequest = null
        }
    }

    fun setBehaviour(audioFocusSettingStr: String) {
        logger.debug(TAG, "setBehaviour($audioFocusSettingStr)")
        behaviour = AudioFocusSetting.valueOf(audioFocusSettingStr)
        audioFocusChangeListener?.behaviour = behaviour
    }
}
