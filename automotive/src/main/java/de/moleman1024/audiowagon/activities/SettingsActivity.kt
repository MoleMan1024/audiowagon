/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.activities

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import de.moleman1024.audiowagon.*
import de.moleman1024.audiowagon.authorization.SDCardDevicePermissions
import de.moleman1024.audiowagon.filestorage.local.DOWNLOAD_DIRECTORY
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi


private const val TAG = "SettingsAct"
private val logger = Logger

@ExperimentalCoroutinesApi
/**
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
    private val deleteResultLauncher = registerForActivityResult(StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            logger.debug(TAG, "User has agreed to delete all local audio files")
        }
    }

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

    fun setAlbumStyleSetting(albumStyleStr: String) {
        assertMediaControllerInitialized()
        val bundle = Bundle()
        bundle.putString(ALBUM_STYLE_KEY, albumStyleStr)
        mediaController.sendCommand(CMD_SET_ALBUM_STYLE_SETTING, bundle, null)
    }

    fun setDataSourceSetting(dataSourceStr: String) {
        assertMediaControllerInitialized()
        val bundle = Bundle()
        bundle.putString(DATA_SOURCE_KEY, dataSourceStr)
        mediaController.sendCommand(CMD_SET_DATA_SOURCE_SETTING, bundle, null)
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

    fun showLog() {
        val showLogIntent = Intent(this, LogActivity::class.java)
        startActivity(showLogIntent)
    }

    fun syncFiles(url: String?) {
        assertMediaControllerInitialized()
        val bundle = Bundle()
        bundle.putString(SYNC_FILES_URL_KEY, url)
        mediaController.sendCommand(CMD_SYNC_FILES, bundle, null)
    }

    private fun assertMediaControllerInitialized() {
        if (!this::mediaController.isInitialized) {
            throw RuntimeException("MediaController was not initialized")
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        goBack()
    }

    override fun onSupportNavigateUp(): Boolean {
        goBack()
        return true
    }

    private fun goBack() {
        logger.debug(TAG, "${supportFragmentManager.backStackEntryCount}")
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

    fun deleteFiles() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw RuntimeException("Bulk deletion of audio files not available until Android 11")
        }
        val deletionCandidates = getAllAudioFileURIsForDeletion()
        if (deletionCandidates.isEmpty()) {
            logger.debug("TAG", "Nothing to delete")
            return
        }
        val deleteRequest = MediaStore.createDeleteRequest(applicationContext.contentResolver, deletionCandidates)
        val senderRequest = IntentSenderRequest.Builder(deleteRequest).setFillInIntent(null)
            .setFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION, 0).build()
        deleteResultLauncher.launch(senderRequest)
    }

    private fun getAllAudioFileURIsForDeletion(): List<Uri> {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.RELATIVE_PATH)
        val selection =
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE '${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_DIRECTORY/%'"
        val sortOrder = "${MediaStore.Audio.Media.RELATIVE_PATH} ASC"
        val uriList = mutableListOf<Uri>()
        applicationContext.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                val uri = Uri.parse("${MediaStore.Audio.Media.EXTERNAL_CONTENT_URI}/${id}")
                uriList.add(uri)
            }
        }
        return uriList
    }

}
