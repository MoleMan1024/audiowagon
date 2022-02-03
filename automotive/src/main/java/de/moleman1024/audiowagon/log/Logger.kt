/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.log

import android.util.Log
import android.util.Log.getStackTraceString
import androidx.annotation.VisibleForTesting
import com.github.mjdev.libaums.fs.UsbFile
import com.github.mjdev.libaums.fs.UsbFileOutputStream
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.io.BufferedOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.Executors

/**
 * Custom logger to intercept all logging and write it to USB flash drive if possible.
 */
private const val TAG = "Logger"

object Logger : LoggerInterface {
    private var usbFile: UsbFile? = null
    private var bufOutStream: BufferedOutputStream? = null
    private var usbFileHasError: Boolean = false
    private var chunkSize: Int = 32768
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private var isStoreLogs = false
    private const val maxBufferLines: Int = 2000
    private val buffer = mutableListOf<String>()
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    private val storedLogs = Collections.synchronizedList<String>(mutableListOf())

    override fun setUSBFile(usbFileForLogging: UsbFile, chunkSize: Int) {
        usbFileHasError = false
        this.chunkSize = chunkSize
        usbFile = usbFileForLogging
        val outStream = UsbFileOutputStream(usbFileForLogging)
        runBlocking(dispatcher) {
            try {
                bufOutStream = BufferedOutputStream(outStream, chunkSize)
                if (buffer.size > 0) {
                    bufOutStream?.write("Flushing ${buffer.size} lines from buffer to log file\n".toByteArray())
                    buffer.forEach {
                        bufOutStream?.write(it.toByteArray())
                    }
                    bufOutStream?.flush()
                }
            } catch (exc: IOException) {
                exceptionLogcatOnly(TAG, "Could not write to USB log file", exc)
                usbFileHasError = true
            }
        }
    }

    override fun closeUSBFile() {
        if (usbFileHasError) {
            return
        }
        runBlocking(dispatcher) {
            try {
                bufOutStream?.close()
            } catch (exc: IOException) {
                exceptionLogcatOnly(TAG, "I/O exception when closing buffered output stream on USB drive", exc)
                usbFileHasError = true
                throw exc
            }
        }
        try {
            usbFile?.close()
        } catch (exc: IOException) {
            exceptionLogcatOnly(TAG, "I/O exception when closing log file on USB drive", exc)
            usbFileHasError = true
            setUSBFileStreamToNull()
            throw exc
        } finally {
            setUSBFileStreamToNull()
        }
    }

    override fun flushToUSB() {
        if (usbFileHasError) {
            return
        }
        try {
            runBlocking(dispatcher) {
                try {
                    bufOutStream?.flush()
                } catch (exc: IOException) {
                    exceptionLogcatOnly(TAG, "I/O exception when flushing buffered output stream on USB drive", exc)
                }
            }
            usbFile?.flush()
        } catch (exc: IOException) {
            exceptionLogcatOnly(TAG, "I/O exception when flushing log to USB device", exc)
            usbFileHasError = true
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
        logToUSBOrBuffer(LoggerInterface.LogLevel.DEBUG, tag, msg)
    }

    override fun error(tag: String?, msg: String) {
        Log.e(tag, msg)
        storeLogLine(msg)
        logToUSBOrBuffer(LoggerInterface.LogLevel.ERROR, tag, msg)
        flushToUSB()
    }

    override fun exception(tag: String?, msg: String, exc: Throwable) {
        Log.e(tag, msg, exc)
        val stackTrace = getStackTraceString(exc)
        storeLogLine("$msg $stackTrace")
        logToUSBOrBuffer(LoggerInterface.LogLevel.ERROR, tag, msg, stackTrace)
        flushToUSB()
    }

    /**
     * Logs to logcat only, not to USB device. This should be used after an exception where the USB device has an
     * error or has been removed and can no longer be used for logging
     */
    override fun exceptionLogcatOnly(tag: String?, msg: String, exc: Throwable) {
        Log.e(tag, msg, exc)
        storeLogLine(msg + " " + getStackTraceString(exc))
    }

    override fun info(tag: String?, msg: String) {
        Log.i(tag, msg)
        storeLogLine(msg)
        logToUSBOrBuffer(LoggerInterface.LogLevel.INFO, tag, msg)
    }

    override fun warning(tag: String?, msg: String) {
        Log.w(tag, msg)
        storeLogLine(msg)
        logToUSBOrBuffer(LoggerInterface.LogLevel.WARNING, tag, msg)
    }

    private fun logToUSBOrBuffer(
        level: LoggerInterface.LogLevel,
        tag: String?,
        msg: String,
        stackTrace: String = ""
    ) {
        if (usbFileHasError) {
            return
        }
        val timestamp = LocalDateTime.now().format(formatter)
        val threadID = android.os.Process.myTid()
        runBlocking(dispatcher) {
            val formattedLogLine = formatLogLine(timestamp, level, threadID, tag, msg)
            if (buffer.size > maxBufferLines) {
                buffer.removeAt(0)
            }
            buffer.add(formattedLogLine)
            if (bufOutStream != null) {
                // FIXME: sometimes loglines overlap partially, check threads? could also be related to
                //  https://github.com/magnusja/libaums/issues/298
                try {
                    bufOutStream?.write(formattedLogLine.toByteArray())
                    if (stackTrace.isBlank()) {
                        return@runBlocking
                    }
                    stackTrace.lines().forEach { line ->
                        val formattedStackTraceLine = formatLogLine(timestamp, level, threadID, tag, line)
                        bufOutStream?.write(formattedStackTraceLine.toByteArray())
                    }
                } catch (exc: IOException) {
                    exceptionLogcatOnly(TAG, "Could not write to USB buffered output stream for logging", exc)
                    usbFileHasError = true
                }
            }
        }
    }

    fun setUSBFileStreamToNull() {
        Log.d(TAG, "Setting USB log file stream to null")
        runBlocking(dispatcher) {
            bufOutStream = null
            usbFile = null
        }
    }

    private fun formatLogLine(
        timestamp: String,
        level: LoggerInterface.LogLevel,
        threadID: Int,
        tag: String?,
        msg: String
    ): String {
        return "%s [%-8s][%-5d][%-20s] %s\n".format(timestamp, level.name, threadID, tag, msg)
    }

    fun getLastLogLines(numLines: Int): List<String> {
        return buffer.takeLast(numLines)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    override fun setStoreLogs(isStoreLogs: Boolean) {
        runBlocking(dispatcher) {
            this@Logger.isStoreLogs = isStoreLogs
            if (!isStoreLogs) {
                storedLogs.clear()
            }
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    override fun getStoredLogs(): List<String> = runBlocking(dispatcher) {
        return@runBlocking storedLogs
    }

    private fun storeLogLine(line: String) {
        if (!isStoreLogs) {
            return
        }
        runBlocking(dispatcher) {
            storedLogs.add(line)
        }
    }

}
