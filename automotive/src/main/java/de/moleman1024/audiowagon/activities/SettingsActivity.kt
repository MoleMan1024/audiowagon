/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.activities

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import de.moleman1024.audiowagon.*
import de.moleman1024.audiowagon.authorization.SDCardDevicePermissions
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi


private const val TAG = "SettingsAct"
private val logger = Logger

@ExperimentalCoroutinesApi
/**
 * Shows the settings screen
 *
 * See https://developer.android.com/guide/topics/ui/settings
 */
class SettingsActivity : AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    private lateinit var mediaBrowser: MediaBrowserCompat
    private lateinit var mediaController: MediaControllerCompat
    private val connectionCallbacks = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            logger.debug(TAG, "onConnected() for MediaBrowser")
            mediaBrowser.sessionToken.also { token ->
                mediaController = MediaControllerCompat(this@SettingsActivity, token)
                MediaControllerCompat.setMediaController(this@SettingsActivity, mediaController)
                mediaController.sendCommand(CMD_REQUEST_USB_PERMISSION, null, null)
            }
        }

        override fun onConnectionSuspended() {
            logger.debug(TAG, "onConnectionSuspended()")
        }

        override fun onConnectionFailed() {
            logger.debug(TAG, "onConnectionFailed()")
        }
    }
    private var sdCardDevicePermissions: SDCardDevicePermissions? = null
    private val sharedPrefs = SharedPrefs()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logger.debug(TAG, "onCreate(savedInstanceState=$savedInstanceState)")
        mediaBrowser = MediaBrowserCompat(
            this, ComponentName(this, AudioBrowserService::class.java), connectionCallbacks, null
        )
        mediaBrowser.connect()
        if (Util.isDebugBuild(this)) {
            sdCardDevicePermissions = SDCardDevicePermissions(this)
            sdCardDevicePermissions?.requestPermissions(this)
        }
        setContentView(R.layout.activity_settings)
        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setHomeButtonEnabled(true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationIcon(R.drawable.west)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settings_container, SettingsFragment()).commit()
        }
    }

    fun enableLogToUSB() {
        // FIXME: depending on timing, these calls can fail when mediacontroller does not connect quickly
        assertMediaControllerInitialized()
        mediaController.sendCommand(CMD_ENABLE_LOG_TO_USB, null, null)
    }

    fun disableLogToUSB() {
        assertMediaControllerInitialized()
        mediaController.sendCommand(CMD_DISABLE_LOG_TO_USB, null, null)
    }

    fun enableCrashReporting() {
        assertMediaControllerInitialized()
        mediaController.sendCommand(CMD_ENABLE_CRASH_REPORTING, null, null)
    }

    fun disableCrashReporting() {
        assertMediaControllerInitialized()
        mediaController.sendCommand(CMD_DISABLE_CRASH_REPORTING, null, null)
    }

    fun setMetadataReadSetting(metadataReadSetting: String) {
        assertMediaControllerInitialized()
        val bundle = Bundle()
        bundle.putString(METADATAREAD_SETTING_KEY, metadataReadSetting)
        mediaController.sendCommand(CMD_SET_METADATAREAD_SETTING, bundle, null)
    }

    fun readMetadataNow() {
        assertMediaControllerInitialized()
        mediaController.sendCommand(CMD_READ_METADATA_NOW, null, null)
    }

    fun setAudioFocusSetting(audioFocusSettingStr: String) {
        assertMediaControllerInitialized()
        val bundle = Bundle()
        bundle.putString(AUDIOFOCUS_SETTING_KEY, audioFocusSettingStr)
        mediaController.sendCommand(CMD_SET_AUDIOFOCUS_SETTING, bundle, null)
    }

    fun notifyIncreasedPlaybackSpeedSettingChanged() {
        assertMediaControllerInitialized()
        mediaController.sendCommand(CMD_NOTIFY_INCREASED_PLAYBACK_SPEED_SETTING_CHANGED, null, null)
    }

    fun setAlbumStyleSetting(albumStyleStr: String) {
        assertMediaControllerInitialized()
        val bundle = Bundle()
        bundle.putString(ALBUM_STYLE_KEY, albumStyleStr)
        mediaController.sendCommand(CMD_SET_ALBUM_STYLE_SETTING, bundle, null)
    }

    fun setViewTabSettings(viewTabs: List<String>) {
        assertMediaControllerInitialized()
        val bundle = Bundle()
        bundle.putStringArray(VIEW_TABS_SETTING_KEY, viewTabs.toTypedArray())
        mediaController.sendCommand(CMD_SET_VIEW_TABS, bundle, null)
    }

    fun enableEqualizer() {
        assertMediaControllerInitialized()
        mediaController.sendCommand(CMD_ENABLE_EQUALIZER, null, null)
    }

    fun disableEqualizer() {
        assertMediaControllerInitialized()
        mediaController.sendCommand(CMD_DISABLE_EQUALIZER, null, null)
    }

    fun enableReplayGain() {
        assertMediaControllerInitialized()
        mediaController.sendCommand(CMD_ENABLE_REPLAYGAIN, null, null)
    }

    fun disableReplayGain() {
        assertMediaControllerInitialized()
        mediaController.sendCommand(CMD_DISABLE_REPLAYGAIN, null, null)
    }

    fun enableShowAlbumArt() {
        assertMediaControllerInitialized()
        mediaController.sendCommand(CMD_ENABLE_SHOW_ALBUM_ART, null, null)
    }

    fun disableShowAlbumArt() {
        assertMediaControllerInitialized()
        mediaController.sendCommand(CMD_DISABLE_SHOW_ALBUM_ART, null, null)
    }

    fun updateEqualizerPreset(presetValue: String) {
        assertMediaControllerInitialized()
        val bundle = Bundle()
        bundle.putString(EQUALIZER_PRESET_KEY, presetValue)
        mediaController.sendCommand(CMD_SET_EQUALIZER_PRESET, bundle, null)
    }

    fun updateEqualizerBandValue(eqBandIndex: Int, eqBandValue: Float) {
        assertMediaControllerInitialized()
        val bundle = Bundle()
        bundle.putInt(EQUALIZER_BAND_INDEX_KEY, eqBandIndex)
        bundle.putFloat(EQUALIZER_BAND_VALUE_KEY, eqBandValue)
        mediaController.sendCommand(CMD_SET_EQUALIZER_BAND, bundle, null)
    }

    fun eject() {
        assertMediaControllerInitialized()
        mediaController.sendCommand(CMD_EJECT, null, null)
    }

    fun showLog() {
        val showLogIntent = Intent(this, LogActivity::class.java)
        startActivity(showLogIntent)
    }

    private fun assertMediaControllerInitialized() {
        if (!this::mediaController.isInitialized) {
            throw RuntimeException("MediaController was not initialized")
        }
    }

    @Deprecated("")
    override fun onBackPressed() {
        super.onBackPressed()
        goBack()
    }

    override fun onSupportNavigateUp(): Boolean {
        goBack()
        return true
    }

    private fun goBack() {
        logger.debug(TAG, "goBack(backStackEntryCount=${supportFragmentManager.backStackEntryCount})")
        if (supportFragmentManager.backStackEntryCount <= 0) {
            finish()
            return
        }
        supportFragmentManager.popBackStackImmediate()
    }

    override fun onDestroy() {
        logger.debug(TAG, "onDestroy()")
        mediaBrowser.disconnect()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        try {
            sdCardDevicePermissions?.onRequestPermissionsResult(requestCode, grantResults)
        } catch (exc: RuntimeException) {
            logger.exception(TAG, exc.message.toString(), exc)
        }
    }

    /**
     * https://developer.android.com/guide/topics/ui/settings/organize-your-settings#split_your_hierarchy_into_multiple_screens
     */
    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        logger.debug(TAG, "onPreferenceStartFragment(pref=$pref)")
        val args = pref.extras
        val fragment = supportFragmentManager.fragmentFactory.instantiate(
            classLoader, pref.fragment.toString()
        )
        fragment.arguments = args
        fragment.setTargetFragment(caller, 0)
        supportFragmentManager.beginTransaction().replace(R.id.settings_container, fragment).addToBackStack(null)
            .commit()
        return true
    }

    fun getSharedPrefs(): SharedPrefs {
        return sharedPrefs
    }

}
