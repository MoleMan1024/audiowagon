/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.activities

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.format.Formatter.formatShortFileSize
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import de.moleman1024.audiowagon.*
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.filestorage.DataSourceSetting
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.MetadataReadSetting
import de.moleman1024.audiowagon.repository.AUDIOITEM_REPO_DB_PREFIX
import kotlinx.coroutines.*
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "SettingsFragm"
private val logger = Logger
private const val PREF_EJECT = "eject"
private const val PREF_DATABASE_STATUS = "databaseStatus"
private const val PREF_DELETE_DATABASES = "deleteDatabases"
private const val PREF_LEGAL_DISCLAIMER = "legalDisclaimer"
private const val PREF_READ_METADATA_NOW = "readMetaDataNow"
private const val PREF_SYNC_FILES = "syncFiles"
private const val PREF_VERSION = "version"

@ExperimentalCoroutinesApi
class SettingsFragment : PreferenceFragmentCompat() {
    private var updateDatabaseStatusJob: Job? = null
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        run {
            logger.debug(TAG, "sharedPrefChanged(key=$key)")
            when (key) {
                SHARED_PREF_USB_STATUS -> {
                    updateUSBAndEjectStatus(sharedPreferences)
                    updateDatabaseStatusJob?.cancelChildren()
                    updateDatabaseStatusJob = lifecycleScope.launch(dispatcher) {
                        // the database is marked as in-use a bit later
                        delay(100)
                        updateDatabaseStatus()
                        updateDatabaseStatusJob = null
                    }
                }
                SHARED_PREF_ENABLE_EQUALIZER -> {
                    updateEqualizerSwitch(sharedPreferences)
                }
                SHARED_PREF_EQUALIZER_PRESET -> {
                    updateEqualizerPreset(sharedPreferences)
                    val eqPreset = SharedPrefs.getEQPreset(sharedPreferences)
                    (activity as SettingsActivity).updateEqualizerPreset(eqPreset)
                }
                SHARED_PREF_ENABLE_REPLAYGAIN -> {
                    updateReplayGainSwitch(sharedPreferences)
                }
                SHARED_PREF_READ_METADATA -> {
                    updateReadMetadataList(sharedPreferences)
                    updateReadMetadataNowButton(sharedPreferences)
                    val metadataReadSettingStr = SharedPrefs.getMetadataReadSetting(sharedPreferences)
                    (activity as SettingsActivity).setMetadataReadSetting(metadataReadSettingStr)
                }
                SHARED_PREF_AUDIOFOCUS -> {
                    updateAudioFocusList(sharedPreferences)
                    val audioFocusSettingStr = SharedPrefs.getAudioFocusSetting(sharedPreferences)
                    (activity as SettingsActivity).setAudioFocusSetting(audioFocusSettingStr)
                }
                SHARED_PREF_ALBUM_STYLE -> {
                    updateAlbumStyleList(sharedPreferences)
                    val albumStyleStr = SharedPrefs.getAlbumStyleSetting(sharedPreferences)
                    (activity as SettingsActivity).setAlbumStyleSetting(albumStyleStr)
                }
                SHARED_PREF_DATA_SOURCE -> {
                    updateAlbumStyleList(sharedPreferences)
                    updateReadMetadataList(sharedPreferences)
                    updateReadMetadataNowButton(sharedPreferences)
                    updateUSBAndEjectStatus(sharedPreferences)
                    updateLogToUSB(sharedPreferences)
                    updateSyncLocalFilesNowButton(sharedPreferences)
                    val dataSourceStr = SharedPrefs.getDataSourceSetting(sharedPreferences)
                    (activity as SettingsActivity).setDataSourceSetting(dataSourceStr)
                }
                else -> {
                    // ignore
                }
            }
        }
    }
    private val databaseDeleteListener = Preference.OnPreferenceChangeListener { _, newValue ->
        val entriesToDelete = newValue as HashSet<*>
        logger.debug(TAG, entriesToDelete.toString())
        entriesToDelete.forEach {
            logger.debug(TAG, "User marked database file for deletion: $it")
            val databaseFile = File(it as String)
            try {
                deleteFile(databaseFile)
            } catch (exc: IOException) {
                logger.exception(TAG, exc.message.toString(), exc)
            }
        }
        // return false to not store the checked entries
        false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        logger.debug(TAG, "onCreate()")
        super.onCreate(savedInstanceState)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(listener)
        val multiListPref = findPreference<MultiSelectListPreference>(PREF_DELETE_DATABASES)
        multiListPref?.onPreferenceChangeListener = databaseDeleteListener
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        logger.debug(TAG, "onCreatePreferences(rootKey=${rootKey})")
        setPreferencesFromResource(R.xml.preferences, rootKey)
        setSummaryProviderDisplayAlbumSetting()
        updateDataSource()
        updateFromSharedPrefs(preferenceManager.sharedPreferences)
        updateDatabaseStatus()
        updateDatabaseFiles()
    }

    private fun setSummaryProviderDisplayAlbumSetting() {
        val pref = findPreference<ListPreference>(SHARED_PREF_ALBUM_STYLE)
        pref?.summaryProvider = Preference.SummaryProvider<ListPreference> { preference ->
            "${preference.entry}\n${context?.getString(R.string.setting_requires_media_center_restart)}"
        }
    }

    private fun updateFromSharedPrefs(sharedPreferences: SharedPreferences?) {
        updateLogToUSB(sharedPreferences)
        updateCrashReporting(sharedPreferences)
        updateEqualizerSwitch(sharedPreferences)
        updateEqualizerPreset(sharedPreferences)
        updateReplayGainSwitch(sharedPreferences)
        updateAudioFocusList(sharedPreferences)
        updateAlbumStyleList(sharedPreferences)
        updateDataSourceList(sharedPreferences)
        updateReadMetadataList(sharedPreferences)
        updateReadMetadataNowButton(sharedPreferences)
        updateSyncLocalFilesNowButton(sharedPreferences)
        updateUSBAndEjectStatus(sharedPreferences)
    }

    private fun updateDataSource() {
        val dataSourcePref = findPreference<ListPreference>(SHARED_PREF_DATA_SOURCE)
        if (BuildConfig.ALLOW_LOCAL_FILES) {
            dataSourcePref?.isVisible = true
            return
        }
        dataSourcePref?.isVisible = false
    }

    private fun updateLogToUSB(sharedPreferences: SharedPreferences?) {
        val logToUSBPref = findPreference<SwitchPreferenceCompat>(SHARED_PREF_LOG_TO_USB)
        if (isDataSourceLocal(sharedPreferences)) {
            logToUSBPref?.isVisible = false
            return
        } else {
            logToUSBPref?.isVisible = true
        }
        val value = SharedPrefs.isLogToUSBEnabled(sharedPreferences)
        logToUSBPref?.isChecked = value
    }

    private fun updateCrashReporting(sharedPreferences: SharedPreferences?) {
        val value = SharedPrefs.isCrashReportingEnabled(sharedPreferences)
        findPreference<SwitchPreferenceCompat>(SHARED_PREF_CRASH_REPORTING)?.isChecked = value
    }

    private fun updateEqualizerSwitch(sharedPreferences: SharedPreferences?) {
        val value = SharedPrefs.isEQEnabled(sharedPreferences)
        findPreference<SwitchPreferenceCompat>(SHARED_PREF_ENABLE_EQUALIZER)?.isChecked = value
        findPreference<ListPreference>(SHARED_PREF_EQUALIZER_PRESET)?.isEnabled = value
    }

    private fun updateEqualizerPreset(sharedPreferences: SharedPreferences?) {
        val eqPreset = SharedPrefs.getEQPreset(sharedPreferences)
        findPreference<ListPreference>(SHARED_PREF_EQUALIZER_PRESET)?.value = eqPreset
    }

    private fun updateReplayGainSwitch(sharedPreferences: SharedPreferences?) {
        val value = SharedPrefs.isReplayGainEnabled(sharedPreferences)
        findPreference<SwitchPreferenceCompat>(SHARED_PREF_ENABLE_REPLAYGAIN)?.isChecked = value
    }

    private fun updateReadMetadataList(sharedPreferences: SharedPreferences?) {
        val metadataReadSetting = SharedPrefs.getMetadataReadSetting(sharedPreferences)
        findPreference<ListPreference>(SHARED_PREF_READ_METADATA)?.value = metadataReadSetting
    }

    private fun updateReadMetadataNowButton(sharedPreferences: SharedPreferences?) {
        val dataSourceSetting = SharedPrefs.getDataSourceSetting(sharedPreferences)
        if (dataSourceSetting == DataSourceSetting.USB.name) {
            val usbStatusValue = SharedPrefs.getUSBStatusResID(sharedPreferences)
            if (usbStatusValue in listOf(
                    R.string.setting_USB_status_ejected,
                    R.string.setting_USB_status_not_connected
                )
            ) {
                findPreference<Preference>(PREF_READ_METADATA_NOW)?.isEnabled = false
                return
            }
        } else {
            findPreference<Preference>(PREF_READ_METADATA_NOW)?.isEnabled = true
        }
        val isMetadataReadSetToManual = isMetadataReadSetToManual(sharedPreferences)
        findPreference<Preference>(PREF_READ_METADATA_NOW)?.isEnabled = isMetadataReadSetToManual
    }

    private fun updateSyncLocalFilesNowButton(sharedPreferences: SharedPreferences?) {
        findPreference<Preference>(PREF_SYNC_FILES)?.isVisible = isDataSourceLocal(sharedPreferences)
    }

    private fun isMetadataReadSetToManual(sharedPreferences: SharedPreferences?): Boolean {
        val metadataReadSetting = SharedPrefs.getMetadataReadSetting(sharedPreferences)
        return metadataReadSetting == MetadataReadSetting.MANUALLY.name
    }

    private fun updateAudioFocusList(sharedPreferences: SharedPreferences?) {
        val audioFocusSetting = SharedPrefs.getAudioFocusSetting(sharedPreferences)
        findPreference<ListPreference>(SHARED_PREF_AUDIOFOCUS)?.value = audioFocusSetting
    }

    private fun updateAlbumStyleList(sharedPreferences: SharedPreferences?) {
        val albumStyleSetting = SharedPrefs.getAlbumStyleSetting(sharedPreferences)
        findPreference<ListPreference>(SHARED_PREF_ALBUM_STYLE)?.value = albumStyleSetting
    }

    private fun updateDataSourceList(sharedPreferences: SharedPreferences?) {
        val dataSourceSetting = SharedPrefs.getDataSourceSetting(sharedPreferences)
        findPreference<ListPreference>(SHARED_PREF_DATA_SOURCE)?.value = dataSourceSetting
    }

    private fun updateUSBAndEjectStatus(sharedPreferences: SharedPreferences?) {
        val value = SharedPrefs.getUSBStatusResID(sharedPreferences)
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
        val usbStatusPref = findPreference<Preference>(SHARED_PREF_USB_STATUS)
        val ejectPref = findPreference<Preference>(PREF_EJECT)
        if (isDataSourceLocal(sharedPreferences)) {
            usbStatusPref?.isVisible = false
            ejectPref?.isVisible = false
            return
        } else {
            usbStatusPref?.isVisible = true
            ejectPref?.isVisible = true
        }
        usbStatusPref?.summary = text
        if (value in listOf(R.string.setting_USB_status_ejected, R.string.setting_USB_status_not_connected)) {
            disableSettingsThatNeedUSBDrive()
        } else {
            enableSettingsThatNeedUSBDrive(sharedPreferences)
        }
    }

    private fun enableSettingsThatNeedUSBDrive(sharedPreferences: SharedPreferences?) {
        val ejectPref = findPreference<Preference>(PREF_EJECT)
        ejectPref?.isEnabled = true
        val isMetadataReadSetToManual = isMetadataReadSetToManual(sharedPreferences)
        if (!isMetadataReadSetToManual) {
            return
        }
        val readMetadataNowPref = findPreference<Preference>(PREF_READ_METADATA_NOW)
        readMetadataNowPref?.isEnabled = true
    }

    private fun disableSettingsThatNeedUSBDrive() {
        val ejectPref = findPreference<Preference>(PREF_EJECT)
        ejectPref?.isEnabled = false
        val readMetadataNowPref = findPreference<Preference>(PREF_READ_METADATA_NOW)
        readMetadataNowPref?.isEnabled = false
    }

    private fun updateDatabaseFiles() {
        logger.debug(TAG, "updateDatabaseFiles()")
        val multiListPref = findPreference<MultiSelectListPreference>(PREF_DELETE_DATABASES)
        val entries = mutableListOf<Pair<String, String>>()
        var databaseFiles: List<File> = listOf()
        try {
            databaseFiles = getDatabaseFilesNotInUse()
        } catch (exc: IOException) {
            logger.exception(TAG, exc.message.toString(), exc)
        } catch (exc: RuntimeException) {
            logger.exception(TAG, exc.message.toString(), exc)
        }
        databaseFiles.forEach {
            entries.add(Pair(it.absolutePath, createGUIListEntryFromFile(it)))
        }
        multiListPref?.entryValues = entries.map { it.first }.toTypedArray()
        multiListPref?.entries = entries.map { it.second }.toTypedArray()
    }

    private fun createGUIListEntryFromFile(file: File): String {
        var fileName = file.name
        try {
            fileName = shortenDatabaseName(fileName)
        } catch (exc: Exception) {
            logger.exception(TAG, exc.message.toString(), exc)
        }
        var fileSizeStr = "???"
        var lastModifiedDateStr = "???"
        try {
            val sizeBytes = file.length()
            fileSizeStr = formatShortFileSize(context, sizeBytes)
        } catch (exc: Exception) {
            logger.exception(TAG, exc.message.toString(), exc)
        }
        try {
            val lastModifiedMS = file.lastModified()
            val lastModifiedDate = Date(lastModifiedMS)
            lastModifiedDateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(lastModifiedDate)
        } catch (exc: Exception) {
            logger.exception(TAG, exc.message.toString(), exc)
        }
        return "\u26C3 ${fileName}\n     $fileSizeStr \u2014 $lastModifiedDateStr"
    }

    private fun deleteFile(file: File) {
        logger.debug(TAG, "Deleting file: $file")
        if (!file.exists()) {
            throw IOException("Database file does not exist: $file")
        }
        if (!file.isFile) {
            throw IOException("Not a database file: $file")
        }
        val success = file.delete()
        if (!success) {
            throw IOException("Could not delete database file: $file")
        }
    }

    private fun getDatabaseFilesNotInUse(): List<File> {
        val databaseFiles = getDatabasesFiles()
        return databaseFiles.filterNot { isDatabaseFileInUse(it) }
    }

    private fun getDatabaseNameInUse(): String {
        val databaseFiles = getDatabasesFiles()
        val databasesInUse = databaseFiles.filter { isDatabaseFileInUse(it) }
        if (databasesInUse.isEmpty()) {
            return context?.getString(R.string.setting_USB_status_not_connected) ?: ""
        }
        return shortenDatabaseName(databasesInUse.first().name)
    }

    private fun shortenDatabaseName(fileName: String): String {
        val shortName = fileName.replace("^${AUDIOITEM_REPO_DB_PREFIX}(aw-)?".toRegex(), "")
        return shortName.replace("\\.sqlite.*$".toRegex(), "")
    }

    private fun getDatabasesFiles(): List<File> {
        val databasesDir = File(context?.dataDir.toString() + "/databases")
        if (!databasesDir.exists()) {
            throw FileNotFoundException("No databases directory")
        }
        return databasesDir.listFiles { _, name -> name.endsWith(".sqlite") }?.toList()?.sorted()
            ?: throw RuntimeException("Could not get database files")
    }

    private fun isDatabaseFileInUse(file: File): Boolean {
        val writeHeadLogFilePath = file.absolutePath + "-wal"
        return File(writeHeadLogFilePath).exists()
    }

    private fun isDataSourceLocal(sharedPreferences: SharedPreferences?): Boolean {
        val dataSourceSettingStr = SharedPrefs.getDataSourceSetting(sharedPreferences)
        return dataSourceSettingStr == DataSourceSetting.LOCAL.name
    }

    private fun updateDatabaseStatus() {
        val pref = findPreference<Preference>(PREF_DATABASE_STATUS)
        try {
            pref?.summary = getDatabaseNameInUse()
        } catch (exc: RuntimeException) {
            logger.exception(TAG, exc.message.toString(), exc)
        } catch (exc: IOException) {
            logger.exception(TAG, exc.message.toString(), exc)
        }
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
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(listener)
        super.onDestroy()
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        logger.debug(TAG, "onPreferenceTreeClick(preference=$preference)")
        try {
            when (preference.key) {
                SHARED_PREF_LOG_TO_USB -> {
                    if ((preference as SwitchPreferenceCompat).isChecked) {
                        (activity as SettingsActivity).enableLogToUSB()
                    } else {
                        (activity as SettingsActivity).disableLogToUSB()
                    }
                }
                SHARED_PREF_CRASH_REPORTING -> {
                    if((preference as SwitchPreferenceCompat).isChecked) {
                        (activity as SettingsActivity).enableCrashReporting()
                    } else {
                        (activity as SettingsActivity).disableCrashReporting()
                    }
                }
                PREF_LEGAL_DISCLAIMER -> {
                    val showLegalDisclaimerIntent = Intent(context, LegalDisclaimerActivity::class.java)
                    startActivity(showLegalDisclaimerIntent)
                }
                PREF_EJECT -> {
                    (activity as SettingsActivity).eject()
                }
                PREF_READ_METADATA_NOW -> {
                    (activity as SettingsActivity).readMetadataNow()
                }
                SHARED_PREF_ENABLE_EQUALIZER -> {
                    if ((preference as SwitchPreferenceCompat).isChecked) {
                        findPreference<ListPreference>(SHARED_PREF_EQUALIZER_PRESET)?.isEnabled = true
                        (activity as SettingsActivity).enableEqualizer()
                    } else {
                        findPreference<ListPreference>(SHARED_PREF_EQUALIZER_PRESET)?.isEnabled = false
                        (activity as SettingsActivity).disableEqualizer()
                    }
                }
                SHARED_PREF_ENABLE_REPLAYGAIN -> {
                    if ((preference as SwitchPreferenceCompat).isChecked) {
                        (activity as SettingsActivity).enableReplayGain()
                    } else {
                        (activity as SettingsActivity).disableReplayGain()
                    }
                }
                SHARED_PREF_DATA_SOURCE -> {
                    (activity as SettingsActivity).eject()
                }
                PREF_DELETE_DATABASES -> {
                    updateDatabaseFiles()
                }
                PREF_VERSION -> {
                    (activity as SettingsActivity).showLog()
                }
            }
        } catch (exc: RuntimeException) {
            logger.exception(TAG, exc.message.toString(), exc)
            revertSwitch(preference)
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun revertSwitch(preference: Preference) {
        when (preference.key) {
            SHARED_PREF_LOG_TO_USB,
            SHARED_PREF_CRASH_REPORTING,
            SHARED_PREF_ENABLE_EQUALIZER,
            SHARED_PREF_ENABLE_REPLAYGAIN -> {
                val switch = (preference as SwitchPreferenceCompat)
                switch.isChecked = !switch.isChecked
            }
        }
    }
}
