/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.app.ActivityManager
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
import de.moleman1024.audiowagon.exceptions.NoAudioItemException
import de.moleman1024.audiowagon.filestorage.*
import de.moleman1024.audiowagon.filestorage.usb.LOG_DIRECTORY
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.USBFile
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
        private val sortNameRegex = Regex("^((The|A|An) |¡|!|\\?|¿|\\.+|\\(|'|#|\")", RegexOption.IGNORE_CASE)
        val DIRECTORIES_TO_IGNORE_REGEX = ("(.RECYCLE\\.BIN" +
                "|$LOG_DIRECTORY.*" +
                "|System Volume Information" +
                "|LOST\\.DIR" +
                "|FOUND\\.[0-9][0-9][0-9]" +
                "|^\\..*)").toRegex()
        // https://github.com/MoleMan1024/audiowagon/issues/107 : ignore Apple OSX resource fork files
        val FILES_TO_IGNORE_REGEX = ("^\\._.*").toRegex()
        private const val URI_SCHEME = "usbAudio"
        const val BUILD_VARIANT_EMULATOR_SD_CARD = "emulatorSDCard"
        val USB_DEVICES_TO_IGNORE = listOf(
            // Generic USB device (unknown vendor)
            Pair(17, 30600),
            // Microchip AN20021 USB to UART Bridge with USB 2.0 hub 0x2530
            Pair(1060, 9520),
            // USB Ethernet 0X9E08
            Pair(1060, 40456),
            // MicroChip OS81118 network interface card 0x0424
            Pair(1060, 53016),
            // Microchip Tech USB49XX NCM/IAP Bridge
            Pair(1060, 18704),
            // Microchip USB4913
            Pair(1060, 18707),
            // Microchip Tech USB2 Controller Hub
            Pair(1060, 18752),
            // Microchip MCP2200 USB to UART converter 0x04D8
            Pair(1240, 223),
            // Mitsubishi USB to Modem Bridge 0x06D3
            Pair(1747, 10272),
            // Cambridge Silicon Radio Bluetooth dongle 0x0A12
            Pair(2578, 1),
            // Cambridge Silicon Radio Bluetooth dongle 0x0A12
            Pair(2578, 3),
            // Linux xHCI Host Controller
            Pair(7531, 2),
            // Linux xHCI Host Controller
            Pair(7531, 3),
            // Aptiv H2H Bridge
            Pair(10646, 261),
            // Aptiv Vendor
            Pair(10646, 288),
            // Aptiv GM V10  E2 PD
            Pair(10646, 306),
            // Delphi Host to Host Bridge
            Pair(11336, 261),
            // Delphi Vendor
            Pair(11336, 288),
            // Delphi Hub
            Pair(11336, 306),
            // Microchip USB2 Controller Hub
            Pair(18752, 1060),
        )

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

        /**
         * Create a string with articles like "The", "A", removed. Will also remove some symbols at beginning of string
         * (e.g. for "Green Day" - "¡Dos!"). This will be used for sorting entries in lists.
         */
        fun getSortNameOrBlank(name: String): String {
            val nameReplaced = getSortName(name)
            return if (name != nameReplaced) {
                nameReplaced
            } else {
                ""
            }
        }

        private fun getSortName(name: String): String {
            return name.replace(sortNameRegex, "")
                .replace(Regex("^ü"), "u")
                .replace(Regex("^ä"), "a")
                .replace(Regex("^ö"), "o")
                .replace(Regex("^Ü"), "U")
                .replace(Regex("^Ä"), "A")
                .replace(Regex("^Ö"), "O")
                .trim()
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

        fun yearShortToEpochTime(year: Short): Long {
            if (year < 0) {
                return -1
            }
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            calendar.clear()
            calendar.set(year.toInt(), Calendar.JUNE, 1)
            return calendar.timeInMillis
        }

        fun yearEpochTimeToShort(yearEpochTime: Long): Short {
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            calendar.time = Date(yearEpochTime)
            return calendar.get(Calendar.YEAR).toShort()
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

        fun createAudioFileFromFile(file: File, storageID: String): AudioFile {
            val uri = createURIForPath(storageID, file.absolutePath)
            val audioFile = AudioFile(uri)
            audioFile.lastModifiedDate = Date(file.lastModified())
            return audioFile
        }

        fun determinePlayableFileType(file: Any): FileLike? {
            when (file) {
                is File -> {
                    if (file.isDirectory) {
                        return Directory()
                    }
                    return determinePlayableFileType(file.name)
                }
                is USBFile -> {
                    if (file.isDirectory || file.isRoot) {
                        return Directory()
                    }
                    return determinePlayableFileType(file.name)
                }
                else -> {
                    throw RuntimeException("Cannot determine playable file type of: $file")
                }
            }
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

        fun createIDForAlbumArtForFile(path: String): Int {
            return path.hashCode() and 0xFFFFFFF
        }

        fun launchInScopeSafely(
            scope: CoroutineScope, dispatcher: CoroutineDispatcher,
            logger: Logger, tag: String, crashReporting: CrashReporting?, func: suspend (CoroutineScope) -> Unit
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
                        crashReporting?.logMessage(msg)
                        crashReporting?.logLastMessagesAndRecordException(exc)
                    }
                }
            }
            return CoroutineScope(scope.coroutineContext + exceptionHandler + dispatcher).run {
                launch {
                    try {
                        func(this)
                    } catch (exc: NoAudioItemException) {
                        logger.exception(tag, exc.message.toString(), exc)
                    } catch (exc: CancellationException) {
                        logger.warning(tag, "CancellationException (msg=${exc.message} exc=$exc)")
                    } catch (exc: Exception) {
                        crashReporting?.logLastMessagesAndRecordException(exc)
                        logger.exception(tag, exc.message.toString(), exc)
                    }
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

        private fun getAvailableMemory(context: Context): String {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memory = ActivityManager.MemoryInfo().also { memoryInfo ->
                activityManager.getMemoryInfo(memoryInfo)
            }
            return "availMem=${memory.availMem / 0x100000L} MB " +
                    "totalMem=${memory.totalMem / 0x100000L} MB " +
                    "threshold=${memory.threshold / 0x100000L} MB " +
                    "lowMemory=${memory.lowMemory}"
        }

        private fun getAppMemory(): String {
            val runtime: Runtime = Runtime.getRuntime()
            val totalMemory = runtime.totalMemory() / 0x100000L
            val freeMemory = runtime.freeMemory() / 0x100000L
            val maxMemory = runtime.maxMemory() / 0x100000L
            return "totalMemory=$totalMemory MB " +
                    "freeMemory=$freeMemory MB " +
                    "maxMemory=$maxMemory MB"
        }

        fun logMemory(context: Context, logger: Logger, tag: String) {
            logger.debug(tag, "System memory: ${getAvailableMemory(context)}")
            logger.debug(tag, "JVM memory: ${getAppMemory()}")
        }

    }
}
