/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.content.Context
import android.content.SharedPreferences
import de.moleman1024.audiowagon.enums.ViewTabSetting
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.enums.AlbumStyleSetting
import de.moleman1024.audiowagon.enums.MetadataReadSetting
import de.moleman1024.audiowagon.enums.AudioFocusSetting
import de.moleman1024.audiowagon.enums.EqualizerPreset

const val SHARED_PREF_LEGAL_DISCLAIMER_AGREED = "agreedLegalVersion"
const val SHARED_PREF_LEGAL_DISCLAIMER_VERSION = "1.1"
const val SHARED_PREF_LOG_TO_USB = "logToUSB"
const val SHARED_PREF_USB_STATUS = "usbStatusResID"
const val SHARED_PREF_READ_METADATA = "readMetaData"
const val SHARED_PREF_ENABLE_EQUALIZER = "enableEqualizer"
const val SHARED_PREF_EQUALIZER_PRESET = "equalizerPreset"
const val SHARED_PREF_EQUALIZER_BAND60 = "eqBand60"
const val SHARED_PREF_EQUALIZER_BAND230 = "eqBand230"
const val SHARED_PREF_EQUALIZER_BAND910 = "eqBand910"
const val SHARED_PREF_EQUALIZER_BAND3600 = "eqBand3600"
const val SHARED_PREF_EQUALIZER_BAND14K = "eqBand14000"
const val SHARED_PREF_ENABLE_REPLAYGAIN = "enableReplayGain"
const val SHARED_PREF_AUDIOFOCUS = "audioFocus"
const val SHARED_PREF_ALBUM_STYLE = "albumStyle"
const val SHARED_PREF_CRASH_REPORTING = "crashReporting"
const val SHARED_PREF_VIEW_TAB_PREFIX = "viewTab"
const val SHARED_PREF_SHOW_ALBUM_ART = "showAlbumArt"
val SHARED_PREF_EQUALIZER_PRESET_DEFAULT = EqualizerPreset.LESS_BASS.name

private const val TAG = "SharedPrefs"

open class SharedPrefs {
    val logger = Logger

    companion object {
        private val sharedPrefsStorage = SharedPrefsStorage()

        fun getDefaultSharedPreferences(context: Context): SharedPreferences {
            return sharedPrefsStorage.getDefaultStorage(context)
        }
    }

    fun isLegalDisclaimerAgreed(context: Context): Boolean {
        return try {
            isLegalDisclaimerAgreed(getDefaultSharedPreferences(context))
        } catch (exc: IllegalStateException) {
            // this might happen when user has not yet unlocked the device
            logger.exception(TAG, exc.message.toString(), exc)
            false
        }
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
        return try {
            getEQPreset(getDefaultSharedPreferences(context))
        } catch (exc: IllegalStateException) {
            // this might happen when user has not yet unlocked the device
            logger.exception(TAG, exc.message.toString(), exc)
            SHARED_PREF_EQUALIZER_PRESET_DEFAULT
        }
    }

    fun getEQPreset(sharedPreferences: SharedPreferences?): String {
        return sharedPreferences?.getString(SHARED_PREF_EQUALIZER_PRESET, SHARED_PREF_EQUALIZER_PRESET_DEFAULT)
            ?: SHARED_PREF_EQUALIZER_PRESET_DEFAULT
    }

    fun getEQBandValues(context: Context): FloatArray {
        return try {
            getEQBandValues(getDefaultSharedPreferences(context))
        } catch (exc: IllegalStateException) {
            // this might happen when user has not yet unlocked the device
            logger.exception(TAG, exc.message.toString(), exc)
            floatArrayOf(0f, 0f, 0f, 0f, 0f)
        }
    }

    fun getEQBandValues(sharedPreferences: SharedPreferences?): FloatArray {
        return floatArrayOf(
            getEQBandValue60Float(sharedPreferences),
            getEQBandValue230Float(sharedPreferences),
            getEQBandValue910Float(sharedPreferences),
            getEQBandValue3600Float(sharedPreferences),
            getEQBandValue14KFloat(sharedPreferences),
        )
    }

    fun getEQBandValue60Float(sharedPreferences: SharedPreferences?): Float {
        return getEQBandValueRaw(sharedPreferences, SHARED_PREF_EQUALIZER_BAND60).toFloat() / 10f
    }

    fun getEQBandValue230Float(sharedPreferences: SharedPreferences?): Float {
        return getEQBandValueRaw(sharedPreferences, SHARED_PREF_EQUALIZER_BAND230).toFloat() / 10f
    }

    fun getEQBandValue910Float(sharedPreferences: SharedPreferences?): Float {
        return getEQBandValueRaw(sharedPreferences, SHARED_PREF_EQUALIZER_BAND910).toFloat() / 10f
    }

    fun getEQBandValue3600Float(sharedPreferences: SharedPreferences?): Float {
        return getEQBandValueRaw(sharedPreferences, SHARED_PREF_EQUALIZER_BAND3600).toFloat() / 10f
    }

    fun getEQBandValue14KFloat(sharedPreferences: SharedPreferences?): Float {
        return getEQBandValueRaw(sharedPreferences, SHARED_PREF_EQUALIZER_BAND14K).toFloat() / 10f
    }

    private fun getEQBandValueRaw(sharedPreferences: SharedPreferences?, equalizerBandKey: String): Int {
        return sharedPreferences?.getInt(equalizerBandKey, 0) ?: 0
    }

    fun isEQEnabled(context: Context): Boolean {
        return try {
            return isEQEnabled(getDefaultSharedPreferences(context))
        } catch (exc: IllegalStateException) {
            // this might happen when user has not yet unlocked the device
            logger.exception(TAG, exc.message.toString(), exc)
            false
        }
    }

    fun isEQEnabled(sharedPreferences: SharedPreferences?): Boolean {
        return sharedPreferences?.getBoolean(SHARED_PREF_ENABLE_EQUALIZER, false) == true
    }

    fun setEQEnabled(context: Context, isEnabled: Boolean) {
        setEQEnabled(getDefaultSharedPreferences(context), isEnabled)
    }

    private fun setEQEnabled(sharedPreferences: SharedPreferences?, isEnabled: Boolean) {
        sharedPreferences?.edit()?.putBoolean(SHARED_PREF_ENABLE_EQUALIZER, isEnabled)?.apply()
    }

    fun isReplayGainEnabled(context: Context): Boolean {
        return try {
            isReplayGainEnabled(getDefaultSharedPreferences(context))
        } catch (exc: IllegalStateException) {
            // this might happen when user has not yet unlocked the device
            logger.exception(TAG, exc.message.toString(), exc)
            false
        }
    }

    fun isReplayGainEnabled(sharedPreferences: SharedPreferences?): Boolean {
        return sharedPreferences?.getBoolean(SHARED_PREF_ENABLE_REPLAYGAIN, false) == true
    }

    fun setReplayGainEnabled(context: Context, isEnabled: Boolean) {
        setReplayGainEnabled(getDefaultSharedPreferences(context), isEnabled)
    }

    private fun setReplayGainEnabled(sharedPreferences: SharedPreferences?, isEnabled: Boolean) {
        sharedPreferences?.edit()?.putBoolean(SHARED_PREF_ENABLE_REPLAYGAIN, isEnabled)?.apply()
    }

    fun isShowAlbumArtEnabled(context: Context): Boolean {
        return try {
            isShowAlbumArtEnabled(getDefaultSharedPreferences(context))
        } catch (exc: IllegalStateException) {
            logger.exception(TAG, exc.message.toString(), exc)
            true
        }
    }

    fun isShowAlbumArtEnabled(sharedPreferences: SharedPreferences?): Boolean {
        return sharedPreferences?.getBoolean(SHARED_PREF_SHOW_ALBUM_ART, true) == true
    }

    fun setShowAlbumArtEnabled(context: Context, isEnabled: Boolean) {
        setShowAlbumArtEnabled(getDefaultSharedPreferences(context), isEnabled)
    }

    fun setShowAlbumArtEnabled(sharedPreferences: SharedPreferences?, isEnabled: Boolean) {
        sharedPreferences?.edit()?.putBoolean(SHARED_PREF_SHOW_ALBUM_ART, isEnabled)?.apply()
    }

    private fun getMetadataReadSetting(context: Context): String {
        return try {
            getMetadataReadSetting(getDefaultSharedPreferences(context))
        } catch (exc: IllegalStateException) {
            // this might happen when user has not yet unlocked the device
            logger.exception(TAG, exc.message.toString(), exc)
            MetadataReadSetting.WHEN_USB_CONNECTED.name
        }
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
        return try {
            getAudioFocusSetting(getDefaultSharedPreferences(context))
        } catch (exc: IllegalStateException) {
            // this might happen when user has not yet unlocked the device
            logger.exception(TAG, exc.message.toString(), exc)
            AudioFocusSetting.PAUSE.name
        }
    }

    fun getAudioFocusSetting(sharedPreferences: SharedPreferences?): String {
        return sharedPreferences?.getString(SHARED_PREF_AUDIOFOCUS, AudioFocusSetting.PAUSE.name)
            ?: AudioFocusSetting.PAUSE.name
    }

    open fun getAlbumStyleSetting(context: Context): String {
        return try {
            getAlbumStyleSetting(getDefaultSharedPreferences(context))
        } catch (exc: IllegalStateException) {
            // this might happen when user has not yet unlocked the device
            logger.exception(TAG, exc.message.toString(), exc)
            return AlbumStyleSetting.GRID.name
        }
    }

    fun getAlbumStyleSetting(sharedPreferences: SharedPreferences?): String {
        return sharedPreferences?.getString(SHARED_PREF_ALBUM_STYLE, AlbumStyleSetting.GRID.name)
            ?: AlbumStyleSetting.GRID.name
    }

    fun getAlbumStyleSettingEnum(context: Context, logger: Logger, tag: String): AlbumStyleSetting {
        val albumStyleSettingStr = getAlbumStyleSetting(context)
        var albumStyleSetting: AlbumStyleSetting = AlbumStyleSetting.GRID
        try {
            albumStyleSetting = AlbumStyleSetting.valueOf(albumStyleSettingStr)
        } catch (exc: IllegalArgumentException) {
            logger.exception(tag, exc.message.toString(), exc)
        }
        return albumStyleSetting
    }

    private fun getViewTabSetting(tabNum: Int, context: Context): String {
        return try {
            getViewTabSetting(tabNum, getDefaultSharedPreferences(context))
        } catch (exc: IllegalStateException) {
            // this might happen when user has not yet unlocked the device
            logger.exception(TAG, exc.message.toString(), exc)
            getViewTabSettingDefault(tabNum)
        }
    }

    // https://github.com/MoleMan1024/audiowagon/issues/124
    fun getViewTabSetting(tabNum: Int, sharedPreferences: SharedPreferences?): String {
        val defaultValue = getViewTabSettingDefault(tabNum)
        return sharedPreferences?.getString("${SHARED_PREF_VIEW_TAB_PREFIX}${tabNum}", defaultValue) ?: defaultValue
    }

    private fun getViewTabSettingDefault(tabNum: Int): String {
        val defaultValue: String
        when (tabNum) {
            0 -> {
                defaultValue = ViewTabSetting.TRACKS.name
            }
            1 -> {
                defaultValue = ViewTabSetting.ALBUMS.name
            }
            2 -> {
                defaultValue = ViewTabSetting.ARTISTS.name
            }
            3 -> {
                defaultValue = ViewTabSetting.FILES.name
            }
            else -> {
                throw AssertionError("Invalid view tab number: $tabNum")
            }
        }
        return defaultValue
    }

    fun getViewTabSettings(context: Context, logger: Logger, tag: String): List<ViewTabSetting> {
        val viewTabs = mutableListOf<ViewTabSetting>()
        for (tabNum in 0 until NUM_VIEW_TABS) {
            val viewTabSettingStr = getViewTabSetting(tabNum, context)
            var viewTabSetting: ViewTabSetting = ViewTabSetting.FILES
            try {
                viewTabSetting = ViewTabSetting.valueOf(viewTabSettingStr)
            } catch (exc: IllegalArgumentException) {
                logger.exception(tag, exc.message.toString(), exc)
            }
            viewTabs.add(viewTabSetting)
        }
        return viewTabs
    }

    fun isLogToUSBEnabled(context: Context): Boolean {
        return try {
            isLogToUSBEnabled(getDefaultSharedPreferences(context))
        } catch (exc: IllegalStateException) {
            // this might happen when user has not yet unlocked the device
            logger.exception(TAG, exc.message.toString(), exc)
            false
        }
    }

    fun isLogToUSBEnabled(sharedPreferences: SharedPreferences?): Boolean {
        return sharedPreferences?.getBoolean(SHARED_PREF_LOG_TO_USB, false) == true
    }

    fun isCrashReportingEnabled(context: Context): Boolean {
        return try {
            isCrashReportingEnabled(getDefaultSharedPreferences(context))
        } catch (exc: IllegalStateException) {
            // this might happen when user has not yet unlocked the device
            logger.exception(TAG, exc.message.toString(), exc)
            false
        }
    }

    fun isCrashReportingEnabled(sharedPreferences: SharedPreferences?): Boolean {
        return sharedPreferences?.getBoolean(SHARED_PREF_CRASH_REPORTING, false) == true
    }

    // TODO: add setting to disable album art

    fun getUSBStatusResID(context: Context): Int {
        return try {
            getUSBStatusResID(getDefaultSharedPreferences(context))
        } catch (exc: IllegalStateException) {
            // this might happen when user has not yet unlocked the device
            logger.exception(TAG, exc.message.toString(), exc)
            R.string.setting_USB_status_unknown
        }
    }

    fun getUSBStatusResID(sharedPreferences: SharedPreferences?): Int {
        return sharedPreferences?.getInt(SHARED_PREF_USB_STATUS, R.string.setting_USB_status_unknown)
            ?: R.string.setting_USB_status_unknown
    }

    fun setUSBStatusResID(context: Context, resID: Int) {
        setUSBStatusResID(getDefaultSharedPreferences(context), resID)
    }

    fun setUSBStatusResID(sharedPreferences: SharedPreferences?, resID: Int) {
        sharedPreferences?.edit()?.putInt(SHARED_PREF_USB_STATUS, resID)?.apply()
    }

}
