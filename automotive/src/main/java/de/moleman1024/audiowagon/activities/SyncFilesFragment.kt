package de.moleman1024.audiowagon.activities

import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import de.moleman1024.audiowagon.*
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi

const val SHARED_PREF_SYNC_FILES_NOW = "syncFilesNow"
const val SHARED_PREF_SYNC_URL = "syncURL"
const val SHARED_PREF_DELETE_FILES_NOW = "deleteFilesNow"

private const val TAG = "SyncFilesFragment"
private val logger = Logger

@ExperimentalCoroutinesApi
class SyncFilesFragment : PreferenceFragmentCompat() {
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        run {
            when (key) {
                SHARED_PREF_SYNC_STATUS -> {
                    updateSyncStatus(sharedPreferences)
                }
                SHARED_PREF_SYNC_NUM_FILES_COPIED -> {
                    updateSyncNumFilesCopied(sharedPreferences)
                }
                SHARED_PREF_SYNC_NUM_FILES_TOTAL -> {
                    updateSyncNumFilesTotal(sharedPreferences)
                }
                SHARED_PREF_SYNC_STORAGE_SPACE -> {
                    updateSyncStorageSpace(sharedPreferences)
                }
                else -> {
                    // ignore
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
        setPreferencesFromResource(R.xml.sync_preferences, rootKey)
        val sharedPreferences = preferenceManager.sharedPreferences
        updateSyncStatus(sharedPreferences)
        updateSyncNumFilesCopied(sharedPreferences)
        updateSyncNumFilesTotal(sharedPreferences)
        updateSyncStorageSpace(sharedPreferences)
    }

    private fun updateSyncStatus(sharedPreferences: SharedPreferences?) {
        val pref = findPreference<Preference>(SHARED_PREF_SYNC_STATUS)
        pref?.summary = SharedPrefs.getSyncStatus(sharedPreferences)
    }

    private fun updateSyncNumFilesCopied(sharedPreferences: SharedPreferences?) {
        val pref = findPreference<Preference>(SHARED_PREF_SYNC_NUM_FILES_COPIED)
        pref?.summary = SharedPrefs.getSyncNumFilesCopied(sharedPreferences).toString()
    }

    private fun updateSyncNumFilesTotal(sharedPreferences: SharedPreferences?) {
        val pref = findPreference<Preference>(SHARED_PREF_SYNC_NUM_FILES_TOTAL)
        pref?.summary = SharedPrefs.getSyncNumFilesTotal(sharedPreferences).toString()
    }

    private fun updateSyncStorageSpace(sharedPreferences: SharedPreferences?) {
        val pref = findPreference<Preference>(SHARED_PREF_SYNC_STORAGE_SPACE)
        pref?.summary = SharedPrefs.getSyncFreeSpace(sharedPreferences)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        logger.debug(TAG, "onPreferenceTreeClick(preference=$preference)")
        when (preference.key) {
            SHARED_PREF_SYNC_FILES_NOW -> {
                onSync()
            }
            SHARED_PREF_DELETE_FILES_NOW -> {
                (activity as SettingsActivity).deleteFiles()
            }
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onDestroy() {
        logger.debug(TAG, "onDestroy()")
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(listener)
        super.onDestroy()
    }

    private fun onSync() {
        logger.debug(TAG, "onSync()")
        val urlPref = findPreference<EditTextPreference>(SHARED_PREF_SYNC_URL)
        val url = urlPref?.text
        (activity as SettingsActivity).syncFiles(url)
    }

}
