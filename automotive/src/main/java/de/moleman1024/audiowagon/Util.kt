/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Insets
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import com.github.mjdev.libaums.fs.UsbFile
import de.moleman1024.audiowagon.exceptions.NoAudioItemException
import de.moleman1024.audiowagon.filestorage.AudioFile
import de.moleman1024.audiowagon.filestorage.FileLike
import de.moleman1024.audiowagon.filestorage.PlaylistFile
import de.moleman1024.audiowagon.filestorage.PlaylistType
import de.moleman1024.audiowagon.filestorage.usb.LOG_DIRECTORY
import de.moleman1024.audiowagon.log.CrashReporting
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.*
import java.io.File
import java.net.URLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.math.ceil
import kotlin.math.floor


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

        fun determinePlayableFileType(file: File): FileLike? {
            if (file.isDirectory) {
                return null
            }
            return determinePlayableFileType(file.name)
        }

        fun determinePlayableFileType(usbFile: UsbFile): FileLike? {
            if (usbFile.isDirectory || usbFile.isRoot) {
                return null
            }
            return determinePlayableFileType(usbFile.name)
        }

        fun guessContentType(fileName: String): String? {
            val guessedContentType: String
            try {
                val safeFileName = makeFileNameSafeForContentTypeGuessing(fileName)
                guessedContentType = URLConnection.guessContentTypeFromName(safeFileName) ?: return null
            } catch (exc: StringIndexOutOfBoundsException) {
                return null
            }
            return guessedContentType
        }

        fun determinePlayableFileType(fileName: String): FileLike? {
            val guessedContentType = guessContentType(fileName) ?: return null
            val isPlaylistFile = isSupportedContentTypePlaylist(guessedContentType)
            if (isPlaylistFile) {
                return PlaylistFile()
            }
            val isAudioFile = isSupportedContentTypeAudioFile(guessedContentType)
            if (isAudioFile) {
                return AudioFile()
            }
            return null
        }

        /**
         * Check if Android MediaPlayer supports the given content type.
         * See https://source.android.com/compatibility/10/android-10-cdd#5_1_3_audio_codecs_details
         * For example .wma is not supported.
         */
        private fun isSupportedContentTypeAudioFile(contentType: String): Boolean {
            if (!listOf("audio").any { contentType.startsWith(it) }) {
                return false
            }
            if (listOf(
                    "3gp", "aac", "amr", "flac", "m4a", "matroska", "mid", "mp3", "mp4", "mpeg", "mpg", "ogg", "opus",
                    "vorbis", "wav", "xmf"
                ).any {
                    it in contentType
                }
            ) {
                return true
            }
            return false
        }

        private fun isSupportedContentTypePlaylist(contentType: String): Boolean {
            if (!listOf("audio", "application").any { contentType.startsWith(it) }) {
                return false
            }
            if (isPlaylistFile(contentType)) {
                return true
            }
            return false
        }

        /**
         * Checks if the given audio content/MIME type string represents a playlist (e.g. "mpegurl" is for .m3u
         * playlists)
         *
         * @param contentType the content/MIME type string
         */
        private fun isPlaylistFile(contentType: String): Boolean {
            return PlaylistType.values().any { it.mimeType == contentType }
        }

        /**
         * The method guessContentTypeFromName() throws errors when certain characters appear in the name, remove those
         */
        private fun makeFileNameSafeForContentTypeGuessing(fileName: String): String {
            return fileName.replace("#", "")
        }

        fun launchInScopeSafely(
            scope: CoroutineScope, dispatcher: CoroutineDispatcher,
            logger: Logger, tag: String, crashReporting: CrashReporting, func: suspend (CoroutineScope) -> Unit
        ): Job {
            val exceptionHandler = CoroutineExceptionHandler { coroutineContext, exc ->
                val msg = "$coroutineContext threw $exc"
                when (exc) {
                    is NoAudioItemException -> {
                        // this happens often when persistent data does not match USB drive contents, do not report this
                        // crash in crashlytics
                        logger.exception(tag, msg, exc)
                    }
                    is CancellationException -> {
                        // cancelling suspending jobs is not an error
                        logger.warning(tag, "CancellationException (msg=$msg)")
                    }
                    else -> {
                        logger.exception(tag, msg, exc)
                        crashReporting.logMessage(msg)
                        crashReporting.logMessages(logger.getLastLogLines(NUM_LOG_LINES_CRASH_REPORT))
                        crashReporting.recordException(exc)
                    }
                }
            }
            return scope.launch(exceptionHandler + dispatcher) {
                try {
                    func(this)
                } catch (exc: NoAudioItemException) {
                    logger.exception(tag, exc.message.toString(), exc)
                } catch (exc: CancellationException) {
                    logger.warning(tag, "CancellationException (msg=${exc.message} exc=$exc)")
                } catch (exc: Exception) {
                    crashReporting.logMessages(logger.getLastLogLines(NUM_LOG_LINES_CRASH_REPORT))
                    crashReporting.recordException(exc)
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

        private fun getScreenWidthPixels(context: Context): Int {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowMetrics: WindowMetrics = windowManager.currentWindowMetrics
                val insets: Insets =
                    windowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
                windowMetrics.bounds.width() - insets.left - insets.right
            } else {
                val displayMetrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getMetrics(displayMetrics)
                displayMetrics.widthPixels
            }
        }

        fun getMaxCharsForScreenWidth(context: Context): Int {
            val screenWidthPixels = getScreenWidthPixels(context)
            // only use a percentage of the screen since there are also icons/padding that will take up space etc.
            val availablePixelsForText = floor(0.7 * screenWidthPixels).toInt()
            val paint = Paint()
            val fontBodyPixels = context.resources.getDimension(R.dimen.car_body1_size)
            paint.textSize = fontBodyPixels
            val commonLettersEnglish = "etaoin srhldcu"
            val avgLetterWidth: Float = ceil(paint.measureText(commonLettersEnglish) / commonLettersEnglish.length)
            if (avgLetterWidth <= 0) {
                return -1
            }
            return floor(availablePixelsForText / avgLetterWidth).toInt()
        }

    }
}
