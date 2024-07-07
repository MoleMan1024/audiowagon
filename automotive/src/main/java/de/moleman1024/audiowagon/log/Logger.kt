/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.log

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.util.Log.getStackTraceString
import androidx.annotation.VisibleForTesting
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.enums.LogLevel
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.USBFile
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.USBFileOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Custom logger to intercept all logging and write it to USB flash drive if possible.
 */
private const val TAG = "Logger"
const val NUM_BYTES_CRASH_REPORT = 65536
private const val USB_LOGFILE_WRITE_PERIOD_MS: Long = 500L

object Logger : LoggerInterface {
    private var scope: CoroutineScope? = null
    private var usbFile: USBFile? = null
    private var outStream: USBFileOutputStream? = null
    private var usbFileHasError: Boolean = false
    private var chunkSize: Int = 32768
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private var isStoreLogs = false
    private val observers = mutableListOf<(String) -> Unit>()
    private val buffer = LogBuffer()
    private val writeToFileMutex: Mutex = Mutex()
    private var isFlushOnNextWrite: Boolean = false
    private var usbLogFileWriteJob: Job? = null
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    private val storedLogs = Collections.synchronizedList<String>(mutableListOf())

    fun init(scope: CoroutineScope) {
        this.scope = scope
        buffer.init(scope)
    }

    // confined by usbLibDispatcher
    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun setUSBFile(usbFileForLogging: USBFile, chunkSize: Int) {
        usbFileHasError = false
        this.chunkSize = chunkSize
        usbFile = usbFileForLogging
        try {
            // do not use a a BufferedOutputStream here, libaums has issues with that
            outStream = usbFileForLogging.getOutputStream()
            val logLines = buffer.getNewestEntriesForLogFile()
            if (logLines.isNotEmpty()) {
                outStream?.write("Flushing ${logLines.size} lines from buffer to log file\n".toByteArray())
                logLines.forEach {
                    outStream?.write(it.toByteArray())
                }
                outStream?.flush()
            }
        } catch (exc: IOException) {
            exceptionLogcatOnly(TAG, "Could not write to USB log file", exc)
            usbFileHasError = true
        }
    }

    fun closeUSBFile() {
        try {
            outStream?.close()
        } catch (exc: IOException) {
            exceptionLogcatOnly(TAG, "I/O exception when closing buffered output stream on USB drive", exc)
            usbFileHasError = true
            throw exc
        }
        try {
            usbFile?.close()
            Log.d(TAG, "USB log file was closed")
        } catch (exc: IOException) {
            exceptionLogcatOnly(TAG, "I/O exception when closing log file on USB drive", exc)
            usbFileHasError = true
            throw exc
        } finally {
            outStream = null
            usbFile = null
        }
    }

    fun setFlushToUSBFlag() {
        isFlushOnNextWrite = true
    }

    fun logVersion(context: Context) {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        info(TAG, "Version: ${packageInfo.versionName} (code: ${packageInfo.longVersionCode})")
        info(
            TAG, "Running on Android: ${Build.VERSION.CODENAME} " +
                    "(release: ${Build.VERSION.RELEASE}, securityPatch: ${Build.VERSION.SECURITY_PATCH}, " +
                    "incremental: ${Build.VERSION.INCREMENTAL})"
        )
    }

    private suspend fun flushToUSB() {
        if (usbFileHasError) {
            return
        }
        verbose(TAG, "flushToUSB()")
        isFlushOnNextWrite = false
        val exceptionHandler = CoroutineExceptionHandler { coroutineContext, exc ->
            exceptionLogcatOnly(TAG, "$coroutineContext threw ${exc.message} when flushing to USB", exc)
        }
        withContext(exceptionHandler) {
            try {
                try {
                    outStream?.flush()
                } catch (exc: IOException) {
                    exceptionLogcatOnly(TAG, "I/O exception when flushing buffered output stream on USB drive", exc)
                }
                usbFile?.flush()
            } catch (exc: IOException) {
                exceptionLogcatOnly(TAG, "I/O exception when flushing log to USB device", exc)
                usbFileHasError = true
            } catch (exc: RuntimeException) {
                exceptionLogcatOnly(TAG, "Runtime exception when flushing log to USB device", exc)
                usbFileHasError = true
            }
        }
    }

    override fun verbose(tag: String?, msg: String) {
        Log.v(tag, msg)
        storeLogLine(msg)
        // do not log verbose messages to USB, will be too much
    }

    override fun debug(tag: String?, msg: String) {
        Log.d(tag, msg)
        storeLogLine(msg)
        logToBuffer(LogData(LogLevel.DEBUG, tag, msg))
    }

    override fun error(tag: String?, msg: String) {
        Log.e(tag, msg)
        storeLogLine(msg)
        logToBuffer(LogData(LogLevel.ERROR, tag, msg))
        isFlushOnNextWrite = true
    }

    override fun exception(tag: String?, msg: String, exc: Throwable) {
        val stackTrace = exc.stackTraceToString()
        val logLine = if (msg != "null") "$msg\n$stackTrace" else stackTrace
        Log.e(tag, logLine)
        storeLogLine(logLine)
        logToBuffer(LogData(LogLevel.ERROR, tag, msg, stackTrace))
        isFlushOnNextWrite = true
    }

    /**
     * Logs to logcat only, not to USB device. This should be used after an exception where the USB device has an
     * error or has been removed and can no longer be used for logging
     */
    override fun exceptionLogcatOnly(tag: String?, msg: String, exc: Throwable) {
        Log.e(tag, msg, exc)
        val logLine = msg + " " + getStackTraceString(exc)
        storeLogLine(logLine)
    }

    override fun info(tag: String?, msg: String) {
        Log.i(tag, msg)
        storeLogLine(msg)
        logToBuffer(LogData(LogLevel.INFO, tag, msg))
    }

    override fun warning(tag: String?, msg: String) {
        Log.w(tag, msg)
        storeLogLine(msg)
        logToBuffer(LogData(LogLevel.WARNING, tag, msg))
    }

    private fun logToBuffer(logData: LogData) {
        logData.timestamp = Util.getLocalDateTimeNow().format(formatter)
        logData.timestampMonotonic = Util.getMillisNow()
        logData.threadID = android.os.Process.myTid()
        logData.processID = android.os.Process.myPid()
        if (observers.isNotEmpty()) {
            // TODO: not optimal, calls function twice when we have observers
            notifyObservers(LogBuffer.formatLogData(logData))
        }
        scope?.launch(Dispatchers.IO) {
            buffer.sendLogDataToChannel(logData)
        }
    }

    suspend fun writeBufferedLogToUSBFile() {
        if (usbFileHasError || usbFile == null) {
            return
        }
        val logLines = buffer.getNewestEntriesForLogFile()
        if (logLines.isNotEmpty()) {
            if (outStream == null) {
                return
            }
            verbose(TAG, "Writing ${logLines.size} log lines to USB")
            try {
                // we yield to other coroutines that want to read from, but we use a mutex to prevent interleaved
                // writing to logfile on USB
                writeToFileMutex.withLock {
                    logLines.forEachIndexed { index, line ->
                        outStream?.write(line.toByteArray())
                        if (index % 100 == 0) {
                            yield()
                        }
                    }
                }
            } catch (exc: IOException) {
                exceptionLogcatOnly(TAG, "Could not write to USB buffered output stream for logging", exc)
                usbFileHasError = true
            }
            if (isFlushOnNextWrite) {
                flushToUSB()
            }
        }
    }

    fun setUSBFileStreamToNull() {
        Log.d(TAG, "Setting USB log file stream to null")
        outStream = null
        usbFile = null
    }

    fun launchLogFileWriteJob() {
        usbLogFileWriteJob?.cancel()
        usbLogFileWriteJob = scope?.launch(Dispatchers.IO) {
            while (true) {
                writeBufferedLogToUSBFile()
                delay(USB_LOGFILE_WRITE_PERIOD_MS)
            }
        }
    }

    fun cancelLogFileWriteJob() {
        usbLogFileWriteJob?.cancel()
        usbLogFileWriteJob = null
    }

    suspend fun getLogsForCrashReporting(): List<String> {
        return buffer.getNewestEntriesFormattedForCrashlytics(NUM_BYTES_CRASH_REPORT)
    }

    suspend fun getLogs(numMaxBytes: Int): List<String> {
        return buffer.getNewestEntriesFormatted(numMaxBytes)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    override fun setStoreLogs(isStoreLogs: Boolean) {
        this.isStoreLogs = isStoreLogs
        if (!isStoreLogs) {
            storedLogs.clear()
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    override fun getStoredLogs(): List<String> {
        return storedLogs
    }

    @SuppressLint("RestrictedApi")
    private fun storeLogLine(line: String) {
        if (!isStoreLogs) {
            return
        }
        storedLogs.add(line)
    }

    private fun notifyObservers(line: String) {
        observers.forEach { it(line) }
    }

    fun addObserver(func: (String) -> Unit) {
        debug(TAG, "Adding observer")
        observers.add(func)
    }

    fun removeObserver(func: (String) -> Unit) {
        debug(TAG, "Removing observer")
        observers.remove(func)
    }

}
