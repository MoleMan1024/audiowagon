/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.preference.PreferenceManager
import de.moleman1024.audiowagon.activities.PERSISTENT_STORAGE_LEGAL_DISCLAIMER_AGREED
import de.moleman1024.audiowagon.activities.PERSISTENT_STORAGE_LEGAL_DISCLAIMER_VERSION
import de.moleman1024.audiowagon.medialibrary.AudioItemType
import java.net.URLEncoder
import java.util.*

class Util {
    companion object {

        fun convertStringToShort(numberAsString: String): Short {
            return convertStringToInt(numberAsString).toShort()
        }

        fun convertStringToInt(numberAsString: String): Int {
            if (numberAsString.isBlank()) {
                throw NumberFormatException("Cannot convert empty string to number")
            }
            val number: Int
            try {
                number = numberAsString.toInt()
            } catch (exc: NumberFormatException) {
                throw NumberFormatException("String is not a number: $numberAsString")
            }
            return number
        }

        fun generateUUID(): String {
            val uuid = UUID.randomUUID()
            return uuid.toString()
        }

        fun isLegalDisclaimerAgreed(context: Context): Boolean {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            val legalDisclaimerAgreedVersion =
                sharedPreferences.getString(PERSISTENT_STORAGE_LEGAL_DISCLAIMER_AGREED, "")
            return legalDisclaimerAgreedVersion == PERSISTENT_STORAGE_LEGAL_DISCLAIMER_VERSION
        }

        fun isDebugBuild(context: Context): Boolean {
            return context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        }

        fun sanitizeVolumeLabel(volumeLabel: String): String {
            return "aw-" + URLEncoder.encode(volumeLabel.trim(), "UTF-8").replace("+", "_")
        }

        fun createAudioItemID(storageID: String, id: Long, type: AudioItemType, extraID: Long? = null): String {
            var audioItemID = "${storageID}/${type.name}/${id}"
            extraID?.let { audioItemID += "+${extraID}" }
            return audioItemID
        }

    }
}
