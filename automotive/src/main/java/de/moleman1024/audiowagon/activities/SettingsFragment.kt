/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.activities

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi

private const val TAG = "SettingsFragm"
private val logger = Logger
// TODO: add testcase for backward compatibility of old settings files
const val PREF_LOG_TO_USB_KEY = "logToUSB"
const val PREF_USB_STATUS ="usbStatusResID"
const val PREF_LEGAL_DISCLAIMER = "legalDisclaimer"
const val PREF_READ_METADATA = "readMetadata"
const val PREF_ENABLE_EQUALIZER = "enableEqualizer"
const val PREF_EQUALIZER_PRESET = "equalizerPreset"
const val PREF_EQUALIZER_PRESET_DEFAULT = "LESS_BASS"
const val PREF_EJECT = "eject"

@ExperimentalCoroutinesApi
class SettingsFragment : PreferenceFragmentCompat() {
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        run {
            logger.debug(TAG, "sharedPrefChanged()")
            when (key) {
                PREF_USB_STATUS -> {
                    updateUSBAndEjectStatus(sharedPreferences)
                }
                PREF_EQUALIZER_PRESET -> {
                    updateEqualizerPreset(sharedPreferences)
                    val eqPreset = getEqualizerPresetFromPreferences(sharedPreferences)
                    (activity as SettingsActivity).updateEqualizerPreset(eqPreset)
                }
                else -> {
                    // ignore
                }
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        logger.debug(TAG, "onCreatePreferences(rootKey=${rootKey})")
        setPreferencesFromResource(R.xml.preferences, rootKey)
        val sharedPreferences = preferenceManager.sharedPreferences
        updateEqualizerSwitch(sharedPreferences)
        updateEqualizerPreset(sharedPreferences)
        updateReadMetadataSwitch(sharedPreferences)
        updateUSBAndEjectStatus(sharedPreferences)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        logger.debug(TAG, "onCreate()")
        super.onCreate(savedInstanceState)
        preferenceManager.sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun updateUSBAndEjectStatus(sharedPreferences: SharedPreferences) {
        val value = sharedPreferences.getInt(PREF_USB_STATUS, R.string.setting_USB_status_unknown)
        var text = context?.getString(R.string.setting_USB_status_unknown)
        try {
            text = context?.getString(value)
            logger.debug(TAG, "updateUSBStatus(): $text")
        } catch (exc: RuntimeException) {
            logger.exception(TAG, "Invalid resource id: $value", exc)
        }
        if (text == null) {
            logger.warning(TAG, "text is null")
            return
        }
        val pref = findPreference<Preference>(PREF_USB_STATUS)
        pref?.summary = text
        if (value in listOf(R.string.setting_USB_status_ejected, R.string.setting_USB_status_not_connected)) {
            disableEject()
        } else {
            enableEject()
        }
    }

    private fun enableEject() {
        val pref = findPreference<Preference>(PREF_EJECT)
        pref?.isEnabled = true
    }

    private fun disableEject() {
        val pref = findPreference<Preference>(PREF_EJECT)
        pref?.isEnabled = false
    }

    private fun updateEqualizerSwitch(sharedPreferences: SharedPreferences) {
        val value = sharedPreferences.getBoolean(PREF_ENABLE_EQUALIZER, false)
        findPreference<SwitchPreferenceCompat>(PREF_ENABLE_EQUALIZER)?.isChecked = value
        findPreference<ListPreference>(PREF_EQUALIZER_PRESET)?.isEnabled = value
    }

    private fun updateEqualizerPreset(sharedPreferences: SharedPreferences) {
        val eqPreset = getEqualizerPresetFromPreferences(sharedPreferences)
        findPreference<ListPreference>(PREF_EQUALIZER_PRESET)?.value = eqPreset
    }

    private fun getEqualizerPresetFromPreferences(sharedPreferences: SharedPreferences): String {
        return sharedPreferences.getString(PREF_EQUALIZER_PRESET, PREF_EQUALIZER_PRESET_DEFAULT)
            ?: PREF_EQUALIZER_PRESET_DEFAULT
    }

    private fun updateReadMetadataSwitch(sharedPreferences: SharedPreferences) {
        val value = sharedPreferences.getBoolean(PREF_READ_METADATA, true)
        findPreference<SwitchPreferenceCompat>(PREF_READ_METADATA)?.isChecked = value
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
        // We register the listeners in onCreate and onDestroy() instead of in onResume() and onPause() because the
        // latter two will be triggered by the transparent USBDummyActivity. The user would still see the preferences
        // screen but it would not update in realtime.
        preferenceManager.sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
        super.onDestroy()
    }

    override fun onPreferenceTreeClick(preference: Preference?): Boolean {
        logger.debug(TAG, "onPreferenceTreeClick(preference=$preference)")
        try {
            when (preference?.key) {
                PREF_LOG_TO_USB_KEY -> {
                    if ((preference as SwitchPreferenceCompat).isChecked) {
                        (activity as SettingsActivity).enableLogToUSB()
                    } else {
                        (activity as SettingsActivity).disableLogToUSB()
                    }
                }
                PREF_LEGAL_DISCLAIMER -> {
                    val showLegalDisclaimerIntent = Intent(context, LegalDisclaimerActivity::class.java)
                    startActivity(showLegalDisclaimerIntent)
                }
                PREF_EJECT -> {
                    (activity as SettingsActivity).eject()
                }
                PREF_ENABLE_EQUALIZER -> {
                    if ((preference as SwitchPreferenceCompat).isChecked) {
                        findPreference<ListPreference>(PREF_EQUALIZER_PRESET)?.isEnabled = true
                        (activity as SettingsActivity).enableEqualizer()
                    } else {
                        findPreference<ListPreference>(PREF_EQUALIZER_PRESET)?.isEnabled = false
                        (activity as SettingsActivity).disableEqualizer()
                    }
                }
                PREF_READ_METADATA -> {
                    if ((preference as SwitchPreferenceCompat).isChecked) {
                        (activity as SettingsActivity).enableReadMetadata()
                    } else {
                        (activity as SettingsActivity).disableReadMetadata()
                    }
                }
            }
        } catch (exc: RuntimeException) {
            logger.exception(TAG, exc.message.toString(), exc)
            if (preference != null) {
                revertSwitch(preference)
                val error = requireContext().getString(
                    R.string.toast_error, requireContext().getString(R.string.toast_error_invalid_state)
                )
                val toast = Toast.makeText(context, error, Toast.LENGTH_SHORT)
                toast.show()
            }
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun revertSwitch(preference: Preference) {
        when (preference.key) {
            PREF_LOG_TO_USB_KEY, PREF_ENABLE_EQUALIZER, PREF_READ_METADATA -> {
                val switch = (preference as SwitchPreferenceCompat)
                switch.isChecked = !switch.isChecked
            }
        }
    }
}
