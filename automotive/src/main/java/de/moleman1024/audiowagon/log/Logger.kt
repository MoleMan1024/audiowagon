/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.log

import android.annotation.SuppressLint
import android.util.Log
import android.util.Log.getStackTraceString
import androidx.annotation.VisibleForTesting
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Custom logger to intercept all logging and write it to USB flash drive if possible.
 */
private const val TAG = "Logger"
const val NUM_BYTES_CRASH_REPORT = 65536
private const val USB_LOGFILE_WRITE_PERIOD_MS: Long = 1000L

object Logger : LoggerInterface {
    var lifecycleScope: CoroutineScope? = null
    private var usbFile: UsbFile? = null
    private var outStream: OutputStream? = null
    private var usbFileHasError: Boolean = false
    private var chunkSize: Int = 32768
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private var isStoreLogs = false
    private val observers = mutableListOf<(String) -> Unit>()
    private val buffer = LogBuffer()
    private var libaumsDispatcher: CoroutineDispatcher? = null
    private val writeToFileMutex: Mutex = Mutex()
    private var isFlushOnNextWrite: Boolean = false
    private var usbLogFileWriteJob: Job? = null
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    private val storedLogs = Collections.synchronizedList<String>(mutableListOf())

    // confined by libaumsDispatcher
    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun setUSBFile(usbFileForLogging: UsbFile, chunkSize: Int, libaumsDispatcher: CoroutineDispatcher?) {
        this.libaumsDispatcher = libaumsDispatcher
        usbFileHasError = false
        this.chunkSize = chunkSize
        usbFile = usbFileForLogging
        try {
            // do not use a a BufferedOutputStream here, libaums has issues with that
            outStream = UsbFileOutputStream(usbFileForLogging)
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
        libaumsDispatcher?.let {
            runBlocking(it) {
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
        }
    }

    fun setFlushToUSBFlag() {
        isFlushOnNextWrite = true
    }

    private suspend fun flushToUSB() {
        if (usbFileHasError) {
            return
        }
        verbose(TAG, "flushToUSB()")
        isFlushOnNextWrite = false
        libaumsDispatcher?.let {
            val exceptionHandler = CoroutineExceptionHandler { coroutineContext, exc ->
                exceptionLogcatOnly(TAG, "$coroutineContext threw ${exc.message} when flushing to USB", exc)
            }
            withContext(it + exceptionHandler) {
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
    }

    override fun verbose(tag: String?, msg: String) {
        Log.v(tag, msg)
        storeLogLine(msg)
        // do not log verbose messages to USB, will be too much
    }

    override fun debug(tag: String?, msg: String) {
        Log.d(tag, msg)
        storeLogLine(msg)
        logToBuffer(LogData(LoggerInterface.LogLevel.DEBUG, tag, msg))
    }

    override fun error(tag: String?, msg: String) {
        Log.e(tag, msg)
        storeLogLine(msg)
        logToBuffer(LogData(LoggerInterface.LogLevel.ERROR, tag, msg))
        isFlushOnNextWrite = true
    }

    override fun exception(tag: String?, msg: String, exc: Throwable) {
        val stackTrace = exc.stackTraceToString()
        val logLine = if (msg != "null") "$msg\n$stackTrace" else stackTrace
        Log.e(tag, logLine)
        storeLogLine(logLine)
        logToBuffer(LogData(LoggerInterface.LogLevel.ERROR, tag, msg, stackTrace))
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
        logToBuffer(LogData(LoggerInterface.LogLevel.INFO, tag, msg))
    }

    override fun warning(tag: String?, msg: String) {
        Log.w(tag, msg)
        storeLogLine(msg)
        logToBuffer(LogData(LoggerInterface.LogLevel.WARNING, tag, msg))
    }

    private fun logToBuffer(logData: LogData) {
        logData.timestamp = LocalDateTime.now().format(formatter)
        logData.threadID = android.os.Process.myTid()
        logData.processID = android.os.Process.myPid()
        if (observers.isNotEmpty()) {
            // TODO: not optimal, calls function twice when we have observers
            notifyObservers(LogBuffer.formatLogData(logData))
        }
        lifecycleScope?.launch(Dispatchers.IO) {
            buffer.append(logData)
        }
    }

    suspend fun writeBufferedLogToUSBFile() {
        if (usbFileHasError || usbFile == null) {
            return
        }
        val logLines = buffer.getNewestEntriesForLogFile()
        if (logLines.isNotEmpty()) {
            libaumsDispatcher?.let { dispatcher ->
                withContext(dispatcher) {
                    if (outStream == null) {
                        return@withContext
                    }
                    verbose(TAG, "Writing ${logLines.size} log lines to USB")
                    try {
                        // we yield to other coroutines that want to read from, but we use a mutex to prevent
                        // interleaved writing to logfile on USB
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
                    verbose(TAG, "Finished log write to USB")
                }
            }
            if (isFlushOnNextWrite) {
                flushToUSB()
            }
        }
    }

    fun setUSBFileStreamToNull() {
        Log.d(TAG, "Setting USB log file stream to null")
        libaumsDispatcher?.let {
            runBlocking(it) {
                outStream = null
                usbFile = null
            }
        }
    }

    fun launchLogFileWriteJob() {
        usbLogFileWriteJob?.cancel()
        usbLogFileWriteJob = lifecycleScope?.launch(Dispatchers.IO) {
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
