/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.player

import android.media.audiofx.DynamicsProcessing
import de.moleman1024.audiowagon.log.Logger

private const val TAG = "Effects"
private val logger = Logger
private const val NUM_EQ_BANDS: Int = 5
private const val NUM_RETRIES = 3
private const val NUM_CHANNELS = 1
private val CUTOFF_FREQS = listOf(120f, 460f, 1800f, 7000f, 20000f)

/**
 * See https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing
 */
class Effects(audioSessionID: Int) {
    var inputGainDecibel: Float = 0f
    set(value) {
        field = value
        dynamicsProcessing.setInputGainAllChannelsTo(value)
    }
    private var isEQEnabled = false
    private var isGainEnabled = false
    private val normalPrio = 0
    // https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing.Config.Builder
    private val config = DynamicsProcessing.Config.Builder(
        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
        NUM_CHANNELS,
        true, NUM_EQ_BANDS,
        false, 0,
        false, 0,
        false
    ).build()
    private val dynamicsProcessing = DynamicsProcessing(normalPrio, audioSessionID, config)
    private var currentPreset: EqualizerPreset = EqualizerPreset.LESS_BASS
    // Polestar 2 and Pixel 3 XL have 5 band EQs, with Q approx 0.6
    // center 60 from 30 til 120 Hz
    // center 230 from 120 til 460 Hz
    // center 910 from 460 til 1800 Hz
    // center 3600 from 1800 til 7000 Hz
    // center 14000 from 7000 til 20000 Hz
    // TODO: It seems DynamicsProcessing allows for more bands
    private val presetLevels = mapOf(
        EqualizerPreset.LESS_BASS to floatArrayOf(-3f, 0f, 0f, 0f, 0f),
        EqualizerPreset.MORE_BASS to floatArrayOf(3f, 0f, 0f, 0f, 0f),
        EqualizerPreset.LESS_HIGHS to floatArrayOf(0f, 0f, 0f, 0f, -4f),
        EqualizerPreset.MORE_HIGHS to floatArrayOf(0f, 0f, 0f, 0f, 4f),
        EqualizerPreset.P2 to floatArrayOf(-2f, -0.5f, -0.7f, 0.7f, -1f),
        EqualizerPreset.P2_PLUS to floatArrayOf(-4f, -1f, -1.5f, 1.4f, -2f),
        EqualizerPreset.V_SHAPE to floatArrayOf(3f, 1f, -1f, 1.5f, 5f)
    )

    init {
        logger.debug(TAG, "Init Effects for audio session id: $audioSessionID")
    }

    fun setEQPreset(preset: EqualizerPreset) {
        logger.debug(TAG, "Setting equalizer preset $preset on $dynamicsProcessing")
        currentPreset = preset
        applyEQPreset()
    }

    private fun applyEQPreset() {
        presetLevels[currentPreset]?.forEachIndexed { index, levelInDecibel ->
            val eqBand = DynamicsProcessing.EqBand(true, CUTOFF_FREQS[index], levelInDecibel)
            dynamicsProcessing.setPreEqBandAllChannelsTo(index, eqBand)
        }
    }

    fun enableEQ() {
        logger.debug(TAG, "Enabling EQ: $dynamicsProcessing")
        val equalizer = DynamicsProcessing.Eq(true, true, NUM_EQ_BANDS)
        dynamicsProcessing.setPreEqAllChannelsTo(equalizer)
        applyEQPreset()
        if (!isEQEnabled && !isGainEnabled) {
            enableEffects()
        }
        isEQEnabled = true
    }

    fun disableEQ() {
        logger.debug(TAG, "Disabling EQ: $dynamicsProcessing")
        val equalizer = DynamicsProcessing.Eq(true, false, NUM_EQ_BANDS)
        dynamicsProcessing.setPreEqAllChannelsTo(equalizer)
        isEQEnabled = false
        if (!isGainEnabled) {
            disableEffects()
        }
    }

    fun enableGain() {
        logger.debug(TAG, "Enabling gain: $dynamicsProcessing")
        dynamicsProcessing.setInputGainAllChannelsTo(inputGainDecibel)
        if (!isEQEnabled && !isGainEnabled) {
            enableEffects()
        }
        isGainEnabled = true
    }

    fun disableGain() {
        logger.debug(TAG, "Disabling gain: $dynamicsProcessing")
        dynamicsProcessing.setInputGainAllChannelsTo(0f)
        isGainEnabled = false
        if (!isEQEnabled) {
            disableEffects()
        }
    }

    private fun enableEffects() {
        logger.debug(TAG, "Enabling effects: $dynamicsProcessing")
        var retryCount = 0
        while (retryCount < NUM_RETRIES) {
            val returnCode = dynamicsProcessing.setEnabled(true)
            if (returnCode == DynamicsProcessing.SUCCESS) {
                break
            }
            // this sometimes fails with ERROR_INVALID_OPERATION but works on next try, no clue why...
            logger.error(TAG, "Enabling effects failed with return code: $returnCode")
            retryCount++
        }
    }

    private fun disableEffects() {
        logger.debug(TAG, "Disabling effects: $dynamicsProcessing")
        var retryCount = 0
        while (retryCount < NUM_RETRIES) {
            val returnCode = dynamicsProcessing.setEnabled(false)
            if (returnCode == DynamicsProcessing.SUCCESS) {
                break
            }
            logger.error(TAG, "Disabling effects failed with return code: $returnCode")
            retryCount++
        }
    }

    fun shutdown() {
        disableEffects()
        dynamicsProcessing.release()
    }

}
