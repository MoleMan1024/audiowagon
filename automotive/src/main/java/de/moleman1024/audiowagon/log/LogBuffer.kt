/*
SPDX-FileCopyrightText: 2021-2022 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.log

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.annotation.VisibleForTesting

private const val NUM_MAX_LOG_ENTRIES = 4000
private const val TAG = "LogBuffer"

class LogBuffer(private val numMaxEntries: Int = NUM_MAX_LOG_ENTRIES) {
    // newest entries at the end, oldest at the front
    private val logDataEntries = mutableListOf<LogData>()
    private val mutex = Mutex()
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    var numEntriesNotRead = 0
    var scope: CoroutineScope? = null
    private val channel = Channel<LogData>()
    private var recvChannelJob: Job? = null

    fun init(scope: CoroutineScope) {
        this.scope = scope
        recvChannelJob = scope.launch(Dispatchers.IO) {
            while (!channel.isClosedForReceive) {
                val logData = channel.receive()
                append(logData)
            }
            Log.d(TAG, "Channel for log was closed")
        }
    }

    suspend fun sendLogDataToChannel(logData: LogData) {
        channel.send(logData)
    }

    private suspend fun append(logData: LogData) {
        mutex.withLock {
            logDataEntries.add(logData)
            if (logDataEntries.size > numMaxEntries) {
                logDataEntries.removeAt(0)
            }
            numEntriesNotRead++
        }
    }

    suspend fun getNewestEntriesForLogFile(): List<String> {
        val lines = mutableListOf<String>()
        mutex.withLock {
            if (numEntriesNotRead > 0) {
                logDataEntries.takeLast(numEntriesNotRead).forEach {
                    val lineFormatted = formatLogData(it)
                    lines.add(lineFormatted)
                    if (it.stackTrace?.isBlank() == false) {
                        it.stackTrace.lines().forEach { stackTraceLine ->
                            if (stackTraceLine.isNotBlank()) {
                                it.msg = stackTraceLine
                                val formattedStackTraceLine = formatLogData(it)
                                lines.add(formattedStackTraceLine)
                            }
                        }
                    }
                }
                numEntriesNotRead = 0
            }
        }
        return lines
    }

    /**
     * Used for Crashlytics to get most recent 64k of logging
     */
    suspend fun getNewestEntriesFormattedForCrashlytics(numMaxBytes: Int): List<String> {
        return getNewestEntriesFormatted(numMaxBytes, isReplaceNewlines = true)
    }

    suspend fun getNewestEntriesFormatted(numMaxBytes: Int): List<String> {
        return getNewestEntriesFormatted(numMaxBytes, isReplaceNewlines = false)
    }

    /**
     * Retrieves numMaxBytes most recent formatted log lines from buffer.
     * Newest at returned at the end, oldest in front
     */
    private suspend fun getNewestEntriesFormatted(numMaxBytes: Int, isReplaceNewlines: Boolean = true): List<String> {
        val lines = mutableListOf<String>()
        var numBytes = 0
        mutex.withLock {
            val iterator = logDataEntries.listIterator(logDataEntries.size)
            while (iterator.hasPrevious()) {
                if (numBytes >= numMaxBytes) {
                    break
                }
                val logData = iterator.previous()
                if (logData.stackTrace?.isBlank() == false) {
                    logData.stackTrace.lines().forEach { stackTraceLine ->
                        if (stackTraceLine.isNotBlank()) {
                            logData.msg = stackTraceLine
                            val formattedStackTraceLine = formatLogData(logData)
                            numBytes += formattedStackTraceLine.toByteArray().size
                            lines.add(0, formattedStackTraceLine)
                        }
                    }
                }
                var lineFormatted = formatLogData(logData)
                if (isReplaceNewlines) {
                    lineFormatted = replaceNewlines(lineFormatted)
                }
                numBytes += lineFormatted.toByteArray().size
                lines.add(0, lineFormatted)
                yield()
            }
        }
        return lines
    }

    companion object {
        fun formatLogData(logData: LogData): String {
            return "%s [%-8s][%-11s][%-24s] %s\n".format(
                logData.timestamp,
                logData.level.name,
                "${logData.processID}-${logData.threadID}",
                logData.tag,
                logData.msg
            )
        }
    }

    /**
     * Crashlytics will only store up to the first newline in a log message, so we need to replace those to keep
     * stacktraces and similar
     */
    private fun replaceNewlines(line: String): String {
        return line.replace("\n", "↲")
    }

    @Suppress("RedundantSuspendModifier")
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    suspend fun shutdown() {
        recvChannelJob?.cancel()
        channel.close()
    }

}
