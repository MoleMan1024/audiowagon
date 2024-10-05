/*
SPDX-FileCopyrightText: 2021-2024 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.activities

import android.content.SharedPreferences
import android.os.Bundle
import androidx.annotation.Keep
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import de.moleman1024.audiowagon.EQUALIZER_PRESET_MAPPING
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.SHARED_PREF_ENABLE_EQUALIZER
import de.moleman1024.audiowagon.SHARED_PREF_EQUALIZER_BAND14K
import de.moleman1024.audiowagon.SHARED_PREF_EQUALIZER_BAND230
import de.moleman1024.audiowagon.SHARED_PREF_EQUALIZER_BAND3600
import de.moleman1024.audiowagon.SHARED_PREF_EQUALIZER_BAND60
import de.moleman1024.audiowagon.SHARED_PREF_EQUALIZER_BAND910
import de.moleman1024.audiowagon.SHARED_PREF_EQUALIZER_PRESET
import de.moleman1024.audiowagon.SharedPrefs
import de.moleman1024.audiowagon.enums.EqualizerPreset
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi

private const val TAG = "EQSettingsFragm"
private val logger = Logger
private const val EQUALIZER_PRESET_NAME_CUSTOM = "CUSTOM"

@ExperimentalCoroutinesApi
@Keep
class EqualizerSettingsFragment : PreferenceFragmentCompat() {
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        run {
            logger.debug(TAG, "sharedPrefChanged(key=$key)")
            when (key) {
                SHARED_PREF_ENABLE_EQUALIZER -> {
                    updateEqualizerSwitch(sharedPreferences)
                    updateEqualizerBands(sharedPreferences)
                }

                SHARED_PREF_EQUALIZER_PRESET -> {
                    updateEqualizerPreset(sharedPreferences)
                    updateEqualizerBands(sharedPreferences)
                    val eqPreset = getSharedPrefs().getEQPreset(sharedPreferences)
                    getParentSettingsActivity().updateEqualizerPreset(eqPreset)
                }

                SHARED_PREF_EQUALIZER_BAND60 -> {
                    val eqBandValue = getSharedPrefs().getEQBandValue60Float(sharedPreferences)
                    getParentSettingsActivity().updateEqualizerBandValue(0, eqBandValue)
                }

                SHARED_PREF_EQUALIZER_BAND230 -> {
                    val eqBandValue = getSharedPrefs().getEQBandValue230Float(sharedPreferences)
                    getParentSettingsActivity().updateEqualizerBandValue(1, eqBandValue)
                }

                SHARED_PREF_EQUALIZER_BAND910 -> {
                    val eqBandValue = getSharedPrefs().getEQBandValue910Float(sharedPreferences)
                    getParentSettingsActivity().updateEqualizerBandValue(2, eqBandValue)
                }

                SHARED_PREF_EQUALIZER_BAND3600 -> {
                    val eqBandValue = getSharedPrefs().getEQBandValue3600Float(sharedPreferences)
                    getParentSettingsActivity().updateEqualizerBandValue(3, eqBandValue)
                }

                SHARED_PREF_EQUALIZER_BAND14K -> {
                    val eqBandValue = getSharedPrefs().getEQBandValue14KFloat(sharedPreferences)
                    getParentSettingsActivity().updateEqualizerBandValue(4, eqBandValue)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        logger.debug(TAG, "onCreate()")
        super.onCreate(savedInstanceState)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        logger.debug(TAG, "onCreatePreferences(rootKey=${rootKey})")
        setPreferencesFromResource(R.xml.preferences_equalizer, rootKey)
        updateFromSharedPrefs(preferenceManager.sharedPreferences)
    }

    override fun onResume() {
        logger.debug(TAG, "onResume()")
        super.onResume()
    }

    override fun onPause() {
        logger.debug(TAG, "onPause()")
        super.onPause()
    }

    override fun onDestroy() {
        logger.debug(TAG, "onDestroy()")
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(listener)
        super.onDestroy()
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        // this shows the state when the user clicks the preference, before it is being updated via SharedPreferences
        logger.debug(TAG, "onPreferenceTreeClick(preference=$preference)")
        val settingsActivity = getParentSettingsActivity()
        when (preference.key) {
            SHARED_PREF_ENABLE_EQUALIZER -> {
                if ((preference as SwitchPreferenceCompat).isChecked) {
                    findPreference<ListPreference>(SHARED_PREF_EQUALIZER_PRESET)?.isEnabled = true
                    settingsActivity.enableEqualizer()
                } else {
                    findPreference<ListPreference>(SHARED_PREF_EQUALIZER_PRESET)?.isEnabled = false
                    settingsActivity.disableEqualizer()
                }
            }
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun updateFromSharedPrefs(sharedPreferences: SharedPreferences?) {
        updateEqualizerSwitch(sharedPreferences)
        updateEqualizerPreset(sharedPreferences)
        updateEqualizerBands(sharedPreferences)
    }

    private fun updateEqualizerSwitch(sharedPreferences: SharedPreferences?) {
        val value = getSharedPrefs().isEQEnabled(sharedPreferences)
        findPreference<SwitchPreferenceCompat>(SHARED_PREF_ENABLE_EQUALIZER)?.isChecked = value
        findPreference<ListPreference>(SHARED_PREF_EQUALIZER_PRESET)?.isEnabled = value
        logger.debug(TAG, "$SHARED_PREF_ENABLE_EQUALIZER=${value}")
    }

    private fun updateEqualizerPreset(sharedPreferences: SharedPreferences?) {
        val eqPresetName = getSharedPrefs().getEQPreset(sharedPreferences)
        findPreference<ListPreference>(SHARED_PREF_EQUALIZER_PRESET)?.value = eqPresetName
        logger.debug(TAG, "$SHARED_PREF_EQUALIZER_PRESET=${eqPresetName}")
    }

    private fun updateEqualizerBands(sharedPreferences: SharedPreferences?) {
        val isEQEnabled = getSharedPrefs().isEQEnabled(sharedPreferences)
        if (isEQEnabled) {
            val eqPresetName = getSharedPrefs().getEQPreset(sharedPreferences)
            if (eqPresetName == EQUALIZER_PRESET_NAME_CUSTOM) {
                val equalizerValues = getSharedPrefs().getEQBandValues(sharedPreferences)
                setEqualizerBandValuesInGUI(equalizerValues)
                enableEqualizerBandsInGUI()
                logger.debug(TAG, "equalizerValues=[${equalizerValues.joinToString(",")}]")
            } else {
                setEqualizerBandsInGUIForPreset(eqPresetName)
                disableEqualizerBandsInGUI()
            }
        } else {
            disableEqualizerBandsInGUI()
        }
    }

    private fun enableEqualizerBandsInGUI() {
        for (band in listOf(
            SHARED_PREF_EQUALIZER_BAND60, SHARED_PREF_EQUALIZER_BAND230,
            SHARED_PREF_EQUALIZER_BAND910, SHARED_PREF_EQUALIZER_BAND3600, SHARED_PREF_EQUALIZER_BAND14K
        )) {
            findPreference<EqualizerSeekbarPreference>(band)?.isEnabled = true
        }
    }

    private fun disableEqualizerBandsInGUI() {
        for (band in listOf(
            SHARED_PREF_EQUALIZER_BAND60, SHARED_PREF_EQUALIZER_BAND230,
            SHARED_PREF_EQUALIZER_BAND910, SHARED_PREF_EQUALIZER_BAND3600, SHARED_PREF_EQUALIZER_BAND14K
        )) {
            findPreference<EqualizerSeekbarPreference>(band)?.isEnabled = false
        }
    }

    private fun setEqualizerBandsInGUIForPreset(presetName: String) {
        val equalizerValues = EQUALIZER_PRESET_MAPPING[EqualizerPreset.valueOf(presetName)]
            ?: throw AssertionError("Invalid equalizer preset name: $presetName")
        setEqualizerBandValuesInGUI(equalizerValues)
    }

    private fun setEqualizerBandValuesInGUI(equalizerValues: FloatArray) {
        listOf(
            SHARED_PREF_EQUALIZER_BAND60, SHARED_PREF_EQUALIZER_BAND230,
            SHARED_PREF_EQUALIZER_BAND910, SHARED_PREF_EQUALIZER_BAND3600, SHARED_PREF_EQUALIZER_BAND14K
        ).forEachIndexed { index, band ->
            findPreference<EqualizerSeekbarPreference>(band)?.setValue(((equalizerValues[index] * 10f).toInt()))
        }
    }

    private fun getParentSettingsActivity(): SettingsActivity {
        return activity as SettingsActivity
    }

    private fun getSharedPrefs(): SharedPrefs {
        return if (activity is SettingsActivity) {
            getParentSettingsActivity().getSharedPrefs()
        } else {
            // used by test cases
            SharedPrefs()
        }
    }

}
