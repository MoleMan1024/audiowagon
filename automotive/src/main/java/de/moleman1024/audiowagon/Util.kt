/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Insets
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Size
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import de.moleman1024.audiowagon.enums.PlaylistType
import de.moleman1024.audiowagon.exceptions.NoAudioItemException
import de.moleman1024.audiowagon.filestorage.*
import de.moleman1024.audiowagon.filestorage.asset.AssetFile
import de.moleman1024.audiowagon.filestorage.data.AudioFile
import de.moleman1024.audiowagon.filestorage.data.Directory
import de.moleman1024.audiowagon.filestorage.data.PlaylistFile
import de.moleman1024.audiowagon.filestorage.usb.LOG_DIRECTORY
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.USBFile
import de.moleman1024.audiowagon.log.CrashReporting
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.*
import java.io.File
import java.net.URLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
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
            // USB Ethernet 0x9E08
            Pair(1060, 40456),
            // MicroChip OS81118 network interface card 0x0424
            Pair(1060, 53016),
            // Microchip Tech USB49XX NCM/IAP Bridge
            Pair(1060, 18704),
            // Microchip USB4913
            Pair(1060, 18707),
            // Microchip USB high-speed hub (0x0424, 0x4914)
            Pair(1060, 18708),
            // Microchip USB hub (0x0424, 0x4915)
            Pair(1060, 18709),
            // Microchip Tech USB2 Controller Hub
            Pair(1060, 18752),
            // Microchip MCP2200 USB to UART converter 0x04D8
            Pair(1240, 223),
            // Samsung Galaxy (MTP mode)
            Pair(1256, 26720),
            // Apple Watch charger
            Pair(1452, 1283),
            // Apple iPod
            Pair(1452, 4766),
            // Apple iPhone 5/5C/5S/6/SE/7/8/X/XR
            Pair(1452, 4776),
            // Mitsubishi USB to Modem Bridge 0x06D3
            Pair(1747, 10272),
            // Cambridge Silicon Radio Bluetooth dongle 0x0A12
            Pair(2578, 1),
            // Cambridge Silicon Radio Bluetooth dongle 0x0A12
            Pair(2578, 3),
            // ASIX Electronics Corp AX88179 Gigabit Ethernet 0xb95 / 0x1790
            Pair(2965, 6032),
            // Realtek Semiconductor Corp. RTL8153 Gigabit Ethernet Adapter 0x0BDA / 0x8153
            Pair(3034, 33107),
            // Android Open Accessory device 0x18D1 / 0x2D00
            Pair(6353, 11520),
            // Google Inc. Nexus/Pixel Device (MTP) 0x18D1 / 0x4EE1
            Pair(6353, 20193),
            // Linux xHCI Host Controller
            Pair(7531, 2),
            // Linux xHCI Host Controller
            Pair(7531, 3),
            // VIA Labs Inc. USB Billboard device 0x2109 / 0x8817
            Pair(8457, 34839),
            // Aptiv H2H Bridge
            Pair(10646, 261),
            // Aptiv Vendor
            Pair(10646, 288),
            // Aptiv GM V10 E2 PD
            Pair(10646, 306),
            // Delphi Host to Host Bridge
            Pair(11336, 261),
            // Delphi Vendor
            Pair(11336, 288),
            // Delphi Hub
            Pair(11336, 306),
            // Microchip USB2 Controller Hub
            Pair(18752, 1060),
            // Microchip USB2 Controller Hub (Chevy Equinox EV 2025)
            Pair(18848, 1060),
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
                is AssetFile -> {
                    if (file.isRoot || file.isDirectory) {
                        return Directory()
                    }
                    return determinePlayableFileType(file.name)
                }
                else -> {
                    throw RuntimeException("Cannot determine playable file type of: ${file.javaClass.name}")
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
            // We want positive integers only
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

        private fun isUnitTest(): Boolean {
            return Build.BRAND == null && Build.DEVICE == null
        }

        private fun getScreenWidthHeightPixels(context: Context): Size {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowMetrics: WindowMetrics = windowManager.currentWindowMetrics
                val insets: Insets =
                    windowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
                val width = windowMetrics.bounds.width() - insets.left - insets.right
                val height = windowMetrics.bounds.height() - insets.top - insets.bottom
                Size(width, height)
            } else {
                val displayMetrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getMetrics(displayMetrics)
                Size(displayMetrics.widthPixels, displayMetrics.heightPixels)
            }
        }

        fun getMaxCharsForScreenWidth(context: Context): Int {
            val screenWidthHeightPixels = getScreenWidthHeightPixels(context)
            // Only use a percentage of the screen since there are also icons/padding that will take up space etc.
            // A value of 70% seems to work okay for Polestar 2 and Volvo XC40 vehicles with a portrait mode screen.
            var reductionPercentage = 0.7
            if (isLandscapeGeneralMotorsScreen(screenWidthHeightPixels)) {
                reductionPercentage = 0.5
            }
            val availablePixelsForText = floor(reductionPercentage * screenWidthHeightPixels.width).toInt()
            val paint = Paint()
            val fontBodyPixels = context.resources.getDimension(R.dimen.car_body1_size)
            paint.textSize = fontBodyPixels
            val commonLettersEnglish = "etaoin srhldcu"
            val avgLetterWidth: Float = ceil(paint.measureText(commonLettersEnglish) / commonLettersEnglish.length)
            if (avgLetterWidth <= 0) {
                return -1
            }
            return floor(availablePixelsForText.toFloat() / avgLetterWidth).toInt()
        }

        fun isLandscapeGeneralMotorsScreen(screenSize: Size): Boolean {
            // General Motors AAOS implementations mostly use landscape screens, for example the Blazer EV.
            // Most of the screen is taken up by the Now Playing widget, there is very little room to scroll through
            // lists. We will have less characters to work with for media item titles in this case
            return (Build.MANUFACTURER == "gm" || Build.BRAND == "gm") && (screenSize.width > screenSize.height)
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

        fun getLocalDateTimeNow(): LocalDateTime {
            // Watch out: this clock will not tick when car is in sleep mode. Also these timestamps might change when
            // the clock gets updated (e.g. from GPS) after the car wakes from sleep.
            return LocalDateTime.now()
        }

        fun getLocalDateTimeNowInstant(): Instant {
            return getLocalDateTimeNow().toInstant(ZoneOffset.UTC)
        }

        fun getMillisNow(): Long {
            return if (!isUnitTest()) {
                // This clock will return the elapsed milliseconds since boot (monotonic)
                SystemClock.elapsedRealtime()
            } else {
                System.currentTimeMillis()
            }
        }

        fun getDifferenceInSecondsForInstants(oldInstant: Instant, newInstant: Instant): Long {
            return abs(Duration.between(oldInstant, newInstant).seconds)
        }

        fun getLocalDateTimeStringNow(): String {
            val date = ZonedDateTime.now()
            return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(date)
        }

        fun getUptimeString(): String {
            return "uptimeMillis=${SystemClock.uptimeMillis()} elapsedRealtime=${getMillisNow()}"
        }

        @Suppress("FunctionName")
        fun TAGCRT(tag: String, coroutineContext: CoroutineContext): String {
            val coroutineName = coroutineContext[CoroutineName.Key]?.name
            return if (coroutineName == null) {
                tag
            } else {
                "${tag}-${coroutineName}"
            }
        }

        fun createHashFromIntent(intent: Intent): Int {
            return abs(abs(intent.action.hashCode()) + abs(intent.component.hashCode()))
        }

        /**
         * Calculate the smallest value of x that is closest to given number n
         */
        fun closestMultiple(n: Int, x: Int): Int {
            var n2 = n
            if (x > n) {
                return x
            }
            n2 = n2 + x / 2
            n2 = n2 - (n2 % x)
            return n2
        }

    }
}
