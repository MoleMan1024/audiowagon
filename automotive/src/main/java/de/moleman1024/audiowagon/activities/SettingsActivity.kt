/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.activities

import android.content.ComponentName
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import de.moleman1024.audiowagon.*
import de.moleman1024.audiowagon.authorization.SDCardDevicePermissions
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi


private const val TAG = "SettingsAct"
private val logger = Logger

@ExperimentalCoroutinesApi
/**
 * See https://developer.android.com/guide/topics/ui/settings
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var mediaBrowser: MediaBrowserCompat
    private lateinit var mediaController: MediaControllerCompat
    private val connectionCallbacks = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            logger.debug(TAG, "onConnected() for MediaBrowser")
            mediaBrowser.sessionToken.also { token ->
                mediaController = MediaControllerCompat(this@SettingsActivity, token)
                MediaControllerCompat.setMediaController(this@SettingsActivity, mediaController)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logger.debug(TAG, "onCreate()")
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
        toolbar.setNavigationIcon(R.drawable.baseline_west_24)
        supportFragmentManager.beginTransaction().replace(R.id.settings_container, SettingsFragment()).commit()
    }

    fun enableLogToUSB() {
        assertMediaControllerInitialized()
        mediaController.sendCommand(CMD_ENABLE_LOG_TO_USB, null, null)
    }

    fun disableLogToUSB() {
        assertMediaControllerInitialized()
        mediaController.sendCommand(CMD_DISABLE_LOG_TO_USB, null, null)
    }

    fun enableReadMetadata() {
        assertMediaControllerInitialized()
        mediaController.sendCommand(CMD_ENABLE_READ_METADATA, null, null)
    }

    fun disableReadMetadata() {
        assertMediaControllerInitialized()
        mediaController.sendCommand(CMD_DISABLE_READ_METADTA, null, null)
    }

    fun enableEqualizer() {
        assertMediaControllerInitialized()
        mediaController.sendCommand(CMD_ENABLE_EQUALIZER, null, null)
    }

    fun disableEqualizer() {
        assertMediaControllerInitialized()
        mediaController.sendCommand(CMD_DISABLE_EQUALIZER, null, null)
    }

    fun updateEqualizerPreset(presetValue: String) {
        assertMediaControllerInitialized()
        val bundle = Bundle()
        bundle.putString(EQUALIZER_PRESET_KEY, presetValue)
        mediaController.sendCommand(CMD_SET_EQUALIZER_PRESET, bundle, null)
    }

    fun eject() {
        assertMediaControllerInitialized()
        mediaController.sendCommand(CMD_EJECT, null, null)
    }

    private fun assertMediaControllerInitialized() {
        if (!this::mediaController.isInitialized) {
            throw RuntimeException("MediaController was not initialized")
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun onDestroy() {
        logger.debug(TAG, "onDestroy()")
        mediaBrowser.disconnect()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        sdCardDevicePermissions?.onRequestPermissionsResult(requestCode, grantResults)
    }

}
