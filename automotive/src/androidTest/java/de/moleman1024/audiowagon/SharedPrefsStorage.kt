/*
SPDX-FileCopyrightText: 2021-2022 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.content.Context
import android.content.SharedPreferences

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
    }

    @Suppress("UNUSED_PARAMETER")
    fun getDefaultStorage(context: Context): SharedPreferences {
        return object : SharedPreferences {
            override fun getAll(): MutableMap<String, *> {
                TODO("Not yet implemented")
            }

            override fun getString(key: String?, value: String?): String? {
                return mockPreferencesMap[key] as String
            }

            override fun getStringSet(p0: String?, p1: MutableSet<String>?): MutableSet<String>? {
                TODO("Not yet implemented")
            }

            override fun getInt(key: String?, value: Int): Int {
                return mockPreferencesMap[key] as Int
            }

            override fun getLong(p0: String?, p1: Long): Long {
                TODO("Not yet implemented")
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
