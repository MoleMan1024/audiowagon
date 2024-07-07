/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.player

import android.media.audiofx.DynamicsProcessing
import de.moleman1024.audiowagon.EQUALIZER_PRESET_MAPPING
import de.moleman1024.audiowagon.enums.EqualizerPreset
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
    private val currentEqualizerBandValues = floatArrayOf(0f, 0f, 0f, 0f, 0f)

    init {
        logger.debug(TAG, "Init Effects for audio session id: $audioSessionID")
    }

    fun storeAndApplyEQPreset(preset: EqualizerPreset) {
        logger.debug(TAG, "Setting equalizer preset $preset on $dynamicsProcessing")
        EQUALIZER_PRESET_MAPPING[preset]?.forEachIndexed { index, levelInDecibel ->
            currentEqualizerBandValues[index] = levelInDecibel
        }
        applyAllEqualizerBandValues()
    }

    private fun applyAllEqualizerBandValues() {
        currentEqualizerBandValues.forEachIndexed { index, levelInDecibel ->
            applyEqualizerBandValue(index, levelInDecibel)
        }
    }

    fun storeAndApplyEqualizerBandValue(index: Int, levelInDecibel: Float) {
        currentEqualizerBandValues[index] = levelInDecibel
        applyEqualizerBandValue(index, levelInDecibel)
    }

    private fun applyEqualizerBandValue(index: Int, levelInDecibel: Float) {
        logger.verbose(TAG, "Applying equalizer band $index value: $levelInDecibel")
        val eqBand = DynamicsProcessing.EqBand(true, CUTOFF_FREQS[index], levelInDecibel)
        dynamicsProcessing.setPreEqBandAllChannelsTo(index, eqBand)
    }

    fun enableEQ() {
        logger.debug(TAG, "Enabling EQ: $dynamicsProcessing")
        val equalizer = DynamicsProcessing.Eq(true, true, NUM_EQ_BANDS)
        dynamicsProcessing.setPreEqAllChannelsTo(equalizer)
        applyAllEqualizerBandValues()
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
