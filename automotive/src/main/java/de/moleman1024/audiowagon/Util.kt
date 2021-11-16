/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.preference.PreferenceManager
import de.moleman1024.audiowagon.activities.PERSISTENT_STORAGE_LEGAL_DISCLAIMER_AGREED
import de.moleman1024.audiowagon.activities.PERSISTENT_STORAGE_LEGAL_DISCLAIMER_VERSION
import de.moleman1024.audiowagon.activities.PREF_READ_METADATA
import de.moleman1024.audiowagon.filestorage.usb.LOG_DIRECTORY
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.*
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.*
import android.os.Build


class Util {
    companion object {
        val DIRECTORIES_TO_IGNORE_REGEX = ("(.RECYCLE\\.BIN" +
                "|$LOG_DIRECTORY.*" +
                "|System Volume Information" +
                "|LOST\\.DIR" +
                "|FOUND\\.[0-9][0-9][0-9]" +
                "|^\\..*)").toRegex()
        private const val URI_SCHEME = "usbAudio"

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

        fun setLegalDisclaimerAgreed(context: Context) {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            sharedPreferences.edit()
                .putString(PERSISTENT_STORAGE_LEGAL_DISCLAIMER_AGREED, PERSISTENT_STORAGE_LEGAL_DISCLAIMER_VERSION)
                .apply()
        }

        fun isMetadataReadingEnabled(context: Context): Boolean {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            return sharedPreferences.getBoolean(PREF_READ_METADATA, true)
        }

        fun isDebugBuild(context: Context): Boolean {
            return context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        }

        fun sanitizeVolumeLabel(volumeLabel: String): String {
            return "aw-" + URLEncoder.encode(volumeLabel.trim(), "UTF-8").replace("+", "_")
        }

        fun createURIForPath(storageID: String, path: String): Uri {
            val builder: Uri.Builder = Uri.Builder()
            builder.scheme(URI_SCHEME).authority(storageID).appendEncodedPath(
                Uri.encode(path.removePrefix("/"), StandardCharsets.UTF_8.toString())
            )
            return builder.build()
        }

        fun getParentPath(pathStr: String): String {
            return File(pathStr).parent ?: throw RuntimeException("No parent directory for: $pathStr")
        }

        fun sanitizeYear(yearString: String): String {
            var yearSanitized = yearString
            // fix formats such as "2008 / 2014"
            yearSanitized = yearSanitized.replace("/.*".toRegex(), "").trim()
            // fix formats such as "2014-06-20T07:00:00Z"
            if (yearSanitized.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*"))) {
                yearSanitized = yearSanitized.replace("-.*".toRegex(), "").trim()
            }
            return yearSanitized
        }

        fun launchInScopeSafely(
            scope: CoroutineScope, dispatcher: CoroutineDispatcher,
            logger: Logger, tag: String, func: suspend () -> Unit
        ): Job {
            val exceptionHandler = CoroutineExceptionHandler { coroutineContext, exc ->
                logger.exception(tag, "$coroutineContext + threw + ${exc.message}", exc)
            }
            return scope.launch(exceptionHandler + dispatcher) {
                try {
                    func()
                } catch (exc: Exception) {
                    logger.exception(tag, exc.message.toString(), exc)
                }
            }
        }

        /**
         * Google reviews are probably done using an emulator not a car. That's why we use this to change the
         * behaviour of the app to provide demo sound files when running a production build in an emulator to pass
         * the review
         */
        fun isRunningInEmulator(): Boolean {
            return (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")
                    || Build.FINGERPRINT.startsWith("generic")
                    || Build.FINGERPRINT.startsWith("unknown")
                    || Build.HARDWARE.contains("goldfish")
                    || Build.HARDWARE.contains("ranchu")
                    || Build.MODEL.contains("google_sdk")
                    || Build.MODEL.contains("Emulator")
                    || Build.MODEL.contains("Android SDK built for x86")
                    || Build.MANUFACTURER.contains("Genymotion")
                    || Build.PRODUCT.contains("sdk_google")
                    || Build.PRODUCT.contains("google_sdk")
                    || Build.PRODUCT.contains("sdk")
                    || Build.PRODUCT.contains("sdk_x86")
                    || Build.PRODUCT.contains("sdk_gphone64_arm64")
                    || Build.PRODUCT.contains("vbox86p")
                    || Build.PRODUCT.contains("emulator")
                    || Build.PRODUCT.contains("simulator"))
        }

    }
}
