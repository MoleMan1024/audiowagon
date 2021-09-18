/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.player

import android.media.audiofx.Equalizer
import de.moleman1024.audiowagon.log.Logger

private const val TAG = "Equalizer"
private val logger = Logger
private const val NUM_EQ_BANDS: Short = 5
private const val NUM_RETRIES = 3

/**
 * See https://developer.android.com/reference/kotlin/android/media/audiofx/Equalizer
 */
class Equalizer(audioSessionID: Int) {
    private val equalizerNormalPriority = 0
    private val equalizer = Equalizer(equalizerNormalPriority, audioSessionID)
    private var currentPreset: EqualizerPreset = EqualizerPreset.LESS_BASS
    // Polestar 2 and Pixel 3 XL have 5 bands EQs, with Q approx 0.6
    // center 60 from 30 til 120 -1500 mB til 1500 mB
    // center 230 from 120 til 460 -1500 mB til 1500 mB
    // center 910 from 460 til 1800 -1500 mB til 1500 mB
    // center 3600 from 1800 til 7000 -1500 mB til 1500 mB
    // center 14000 from 7000 til 20000 -1500 mB til 1500 mB
    private val presetLevels = mapOf(
        EqualizerPreset.LESS_BASS to shortArrayOf(-300, 0, 0, 0, 0),
        EqualizerPreset.MORE_BASS to shortArrayOf(300, 0, 0, 0, 0),
        EqualizerPreset.LESS_HIGHS to shortArrayOf(0, 0, 0, 0, -400),
        EqualizerPreset.MORE_HIGHS to shortArrayOf(0, 0, 0, 0, 400),
        EqualizerPreset.P2 to shortArrayOf(-200, -50, -70, 70, -100),
        EqualizerPreset.P2_PLUS to shortArrayOf(-400, -100, -150, 140, -200),
        EqualizerPreset.V_SHAPE to shortArrayOf(300, 100, -100, 150, 500)
    )

    init {
        logger.debug(TAG, "Init Equalizer for audio session id: $audioSessionID")
    }

    private fun isSupported(): Boolean {
        return equalizer.numberOfBands == NUM_EQ_BANDS
    }

    fun setPreset(preset: EqualizerPreset) {
        logger.debug(TAG, "Setting equalizer preset $preset on $equalizer")
        if (!isSupported()) {
            logger.warning(TAG, "Equalizer presets not supported")
            return
        }
        currentPreset = preset
        presetLevels[currentPreset]?.forEachIndexed { index, levelInMillibel ->
            equalizer.setBandLevel(index.toShort(), levelInMillibel)
        }
    }

    fun enable() {
        logger.debug(TAG, "Enabling equalizer: $equalizer")
        if (!isSupported()) {
            logger.warning(TAG, "Equalizer presets not supported")
            return
        }
        var retryCount = 0
        while (retryCount < NUM_RETRIES) {
            val returnCode = equalizer.setEnabled(true)
            if (returnCode == Equalizer.SUCCESS) {
                break
            }
            // this sometimes fails with ERROR_INVALID_OPERATION but works on next try, no clue why...
            logger.error(TAG, "Enabling equalizer failed with return code: $returnCode")
            retryCount++
        }
    }

    fun disable() {
        logger.debug(TAG, "Disabling equalizer: $equalizer")
        var retryCount = 0
        while (retryCount < NUM_RETRIES) {
            val returnCode = equalizer.setEnabled(false)
            if (returnCode == Equalizer.SUCCESS) {
                break
            }
            logger.error(TAG, "Disabling equalizer failed with return code: $returnCode")
            retryCount++
        }
    }

    fun shutdown() {
        equalizer.release()
    }

}
