/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.MetadataReadSetting
import de.moleman1024.audiowagon.player.AudioFocusSetting
import de.moleman1024.audiowagon.player.EqualizerPreset

const val SHARED_PREF_LEGAL_DISCLAIMER_AGREED = "agreedLegalVersion"
const val SHARED_PREF_LEGAL_DISCLAIMER_VERSION = "1.1"
const val SHARED_PREF_LOG_TO_USB = "logToUSB"
const val SHARED_PREF_USB_STATUS = "usbStatusResID"
const val SHARED_PREF_READ_METADATA = "readMetaData"
const val SHARED_PREF_ENABLE_EQUALIZER = "enableEqualizer"
const val SHARED_PREF_EQUALIZER_PRESET = "equalizerPreset"
const val SHARED_PREF_ENABLE_REPLAYGAIN = "enableReplayGain"
const val SHARED_PREF_AUDIOFOCUS = "audioFocus"
const val SHARED_PREF_CRASH_REPORTING = "crashReporting"
val SHARED_PREF_EQUALIZER_PRESET_DEFAULT = EqualizerPreset.LESS_BASS.name

class SharedPrefs {
    companion object {

        fun isLegalDisclaimerAgreed(context: Context): Boolean {
            return isLegalDisclaimerAgreed(getDefaultSharedPreferences(context))
        }

        fun isLegalDisclaimerAgreed(sharedPreferences: SharedPreferences?): Boolean {
            val legalDisclaimerAgreedVersion = sharedPreferences?.getString(SHARED_PREF_LEGAL_DISCLAIMER_AGREED, "")
            return legalDisclaimerAgreedVersion == SHARED_PREF_LEGAL_DISCLAIMER_VERSION
        }

        fun setLegalDisclaimerAgreed(context: Context) {
            setLegalDisclaimerAgreed(getDefaultSharedPreferences(context))
        }

        fun setLegalDisclaimerAgreed(sharedPreferences: SharedPreferences?) {
            sharedPreferences?.edit()
                ?.putString(SHARED_PREF_LEGAL_DISCLAIMER_AGREED, SHARED_PREF_LEGAL_DISCLAIMER_VERSION)
                ?.apply()
        }

        fun getEQPreset(context: Context): String {
            return getEQPreset(getDefaultSharedPreferences(context))
        }

        fun getEQPreset(sharedPreferences: SharedPreferences?): String {
            return sharedPreferences?.getString(SHARED_PREF_EQUALIZER_PRESET, SHARED_PREF_EQUALIZER_PRESET_DEFAULT)
                ?: SHARED_PREF_EQUALIZER_PRESET_DEFAULT
        }

        fun isEQEnabled(context: Context): Boolean {
            return isEQEnabled(getDefaultSharedPreferences(context))
        }

        fun isEQEnabled(sharedPreferences: SharedPreferences?): Boolean {
            return sharedPreferences?.getBoolean(SHARED_PREF_ENABLE_EQUALIZER, false) == true
        }

        fun setEQEnabled(context: Context, isEnabled: Boolean) {
            setEQEnabled(getDefaultSharedPreferences(context), isEnabled)
        }

        fun setEQEnabled(sharedPreferences: SharedPreferences?, isEnabled: Boolean) {
            sharedPreferences?.edit()?.putBoolean(SHARED_PREF_ENABLE_EQUALIZER, isEnabled)?.apply()
        }

        fun isReplayGainEnabled(context: Context): Boolean {
            return isReplayGainEnabled(getDefaultSharedPreferences(context))
        }

        fun isReplayGainEnabled(sharedPreferences: SharedPreferences?): Boolean {
            return sharedPreferences?.getBoolean(SHARED_PREF_ENABLE_REPLAYGAIN, false) == true
        }

        fun setReplayGainEnabled(context: Context, isEnabled: Boolean) {
            setReplayGainEnabled(getDefaultSharedPreferences(context), isEnabled)
        }

        fun setReplayGainEnabled(sharedPreferences: SharedPreferences?, isEnabled: Boolean) {
            sharedPreferences?.edit()?.putBoolean(SHARED_PREF_ENABLE_REPLAYGAIN, isEnabled)?.apply()
        }

        fun getMetadataReadSetting(context: Context): String {
            return getMetadataReadSetting(getDefaultSharedPreferences(context))
        }

        fun getMetadataReadSetting(sharedPreferences: SharedPreferences?): String {
            return sharedPreferences?.getString(
                SHARED_PREF_READ_METADATA, MetadataReadSetting.WHEN_USB_CONNECTED.name
            ) ?: MetadataReadSetting.WHEN_USB_CONNECTED.name
        }

        fun getMetadataReadSettingEnum(context: Context, logger: Logger, tag: String): MetadataReadSetting {
            val metadataReadSettingStr = getMetadataReadSetting(context)
            var metadataReadSetting: MetadataReadSetting = MetadataReadSetting.WHEN_USB_CONNECTED
            try {
                metadataReadSetting = MetadataReadSetting.valueOf(metadataReadSettingStr)
            } catch (exc: IllegalArgumentException) {
                logger.exception(tag, exc.message.toString(), exc)
            }
            return metadataReadSetting
        }

        fun getAudioFocusSetting(context: Context): String {
            return getAudioFocusSetting(getDefaultSharedPreferences(context))
        }

        fun getAudioFocusSetting(sharedPreferences: SharedPreferences?): String {
            return sharedPreferences?.getString(SHARED_PREF_AUDIOFOCUS, AudioFocusSetting.PAUSE.name) ?:
            AudioFocusSetting.PAUSE.name
        }

        fun isLogToUSBEnabled(context: Context): Boolean {
            return isLogToUSBEnabled(getDefaultSharedPreferences(context))
        }

        fun isLogToUSBEnabled(sharedPreferences: SharedPreferences?): Boolean {
            return sharedPreferences?.getBoolean(SHARED_PREF_LOG_TO_USB, false) == true
        }

        fun isCrashReportingEnabled(context: Context): Boolean {
            return isCrashReportingEnabled(getDefaultSharedPreferences(context))
        }

        fun isCrashReportingEnabled(sharedPreferences: SharedPreferences?): Boolean {
            return sharedPreferences?.getBoolean(SHARED_PREF_CRASH_REPORTING, false) == true
        }

        fun getUSBStatusResID(context: Context): Int {
            return getUSBStatusResID(getDefaultSharedPreferences(context))
        }

        fun getUSBStatusResID(sharedPreferences: SharedPreferences?): Int {
            return sharedPreferences?.getInt(SHARED_PREF_USB_STATUS, R.string.setting_USB_status_unknown) ?: -1
        }

        fun setUSBStatusResID(context: Context, resID: Int) {
            setUSBStatusResID(getDefaultSharedPreferences(context), resID)
        }

        fun setUSBStatusResID(sharedPreferences: SharedPreferences?, resID: Int) {
            sharedPreferences?.edit()?.putInt(SHARED_PREF_USB_STATUS, resID)?.apply()
        }

        private fun getDefaultSharedPreferences(context: Context): SharedPreferences {
            return PreferenceManager.getDefaultSharedPreferences(context)
        }
    }
}
