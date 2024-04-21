/*
SPDX-FileCopyrightText: 2021-2022 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.content.Context
import android.content.SharedPreferences
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.persistence.*

// TODO: duplicated for unit tests and Android instrumented tests

private const val TAG = "SharedPrefsStorage"

@Suppress("unused")
open class SharedPrefsStorage {
    val mockPreferencesMap = mutableMapOf<String, Any>()

    init {
        mockPreferencesMap[SHARED_PREF_CRASH_REPORTING] = false
        mockPreferencesMap[SHARED_PREF_LOG_TO_USB] = false
        mockPreferencesMap[SHARED_PREF_USB_STATUS] = -1
        mockPreferencesMap[SHARED_PREF_ALBUM_STYLE] = "GRID"
        mockPreferencesMap[SHARED_PREF_ENABLE_REPLAYGAIN] = true
        mockPreferencesMap[SHARED_PREF_ENABLE_EQUALIZER] = true
        mockPreferencesMap[SHARED_PREF_AUDIOFOCUS] = "PAUSE"
        mockPreferencesMap[SHARED_PREF_LEGAL_DISCLAIMER_AGREED] = "1.1"
        mockPreferencesMap[SHARED_PREF_READ_METADATA] = "WHEN_USB_CONNECTED"
        mockPreferencesMap[SHARED_PREF_EQUALIZER_PRESET] = "LESS_BASS"
        mockPreferencesMap["${SHARED_PREF_VIEW_TAB_PREFIX}0"] = "TRACKS"
        mockPreferencesMap["${SHARED_PREF_VIEW_TAB_PREFIX}1"] = "ALBUMS"
        mockPreferencesMap["${SHARED_PREF_VIEW_TAB_PREFIX}2"] = "ARTISTS"
        mockPreferencesMap["${SHARED_PREF_VIEW_TAB_PREFIX}3"] = "FILES"
        mockPreferencesMap[PERSISTENT_STORAGE_CURRENT_TRACK_ID] = ""
        mockPreferencesMap[PERSISTENT_STORAGE_CURRENT_TRACK_POS] = 0L
        mockPreferencesMap[PERSISTENT_STORAGE_QUEUE_INDEX] = 0
        mockPreferencesMap[PERSISTENT_STORAGE_QUEUE_IDS] = ""
        mockPreferencesMap[PERSISTENT_STORAGE_IS_SHUFFLING] = false
        mockPreferencesMap[PERSISTENT_STORAGE_REPEAT_MODE] = "OFF"
        mockPreferencesMap[PERSISTENT_STORAGE_LAST_CONTENT_HIERARCHY_ID] = ""
    }

    @Suppress("UNUSED_PARAMETER")
    fun getDefaultStorage(context: Context): SharedPreferences {
        return object : SharedPreferences {
            override fun getAll(): MutableMap<String, *> {
                TODO("Not yet implemented")
            }

            override fun getString(key: String?, value: String?): String? {
                Logger.debug(TAG, "getString($key)")
                return mockPreferencesMap[key] as String
            }

            override fun getStringSet(p0: String?, p1: MutableSet<String>?): MutableSet<String>? {
                TODO("Not yet implemented")
            }

            override fun getInt(key: String?, value: Int): Int {
                Logger.debug(TAG, "getInt($key)")
                return mockPreferencesMap[key] as Int
            }

            override fun getLong(key: String?, value: Long): Long {
                Logger.debug(TAG, "getLong($key)")
                return mockPreferencesMap[key] as Long
            }

            override fun getFloat(p0: String?, p1: Float): Float {
                TODO("Not yet implemented")
            }

            override fun getBoolean(key: String?, value: Boolean): Boolean {
                return mockPreferencesMap[key] as Boolean
            }

            override fun contains(p0: String?): Boolean {
                TODO("Not yet implemented")
            }

            override fun edit(): SharedPreferences.Editor {
                return object : SharedPreferences.Editor {
                    override fun putString(p0: String?, p1: String?): SharedPreferences.Editor {
                        return this
                    }

                    override fun putStringSet(p0: String?, p1: MutableSet<String>?): SharedPreferences.Editor {
                        return this
                    }

                    override fun putInt(p0: String?, p1: Int): SharedPreferences.Editor {
                        return this
                    }

                    override fun putLong(p0: String?, p1: Long): SharedPreferences.Editor {
                        return this
                    }

                    override fun putFloat(p0: String?, p1: Float): SharedPreferences.Editor {
                        return this
                    }

                    override fun putBoolean(p0: String?, p1: Boolean): SharedPreferences.Editor {
                        return this
                    }

                    override fun remove(p0: String?): SharedPreferences.Editor {
                        return this
                    }

                    override fun clear(): SharedPreferences.Editor {
                        return this
                    }

                    override fun commit(): Boolean {
                        return true
                    }

                    override fun apply() {
                        // no op
                    }

                }
            }

            override fun registerOnSharedPreferenceChangeListener(p0: SharedPreferences.OnSharedPreferenceChangeListener?) {
                TODO("Not yet implemented")
            }

            override fun unregisterOnSharedPreferenceChangeListener(p0: SharedPreferences.OnSharedPreferenceChangeListener?) {
                TODO("Not yet implemented")
            }

        }
    }
}
