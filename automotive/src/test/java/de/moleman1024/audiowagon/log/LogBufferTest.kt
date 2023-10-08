/*
SPDX-FileCopyrightText: 2021-2022 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.log

import de.moleman1024.audiowagon.enums.LogLevel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NUM_MAX_LOG_ENTRIES = 10

class LogBufferTest {

    @Test
    fun getNewestEntriesForLogFile_bufferNotFull_returnsNoDuplicates() {
        val logBuffer = LogBuffer(NUM_MAX_LOG_ENTRIES)
        runBlocking {
            logBuffer.init(this)
            val numLogLines = NUM_MAX_LOG_ENTRIES / 2
            for (i in 0 until numLogLines) {
                val logData = LogData(LogLevel.DEBUG, "TAG", "MSG$i")
                logBuffer.sendLogDataToChannel(logData)
            }
            val entries = logBuffer.getNewestEntriesForLogFile()
            for (i in 0 until numLogLines) {
                print(entries[i])
                assertTrue(entries[i].endsWith("MSG$i\n"))
            }
            assertTrue(entries.size == numLogLines)
            val entriesAgain = logBuffer.getNewestEntriesForLogFile()
            assertTrue(entriesAgain.isEmpty())
            logBuffer.shutdown()
        }
    }

    @Test
    fun getNewestEntriesForLogFile_bufferFull_returnsNoDuplicates() {
        val logBuffer = LogBuffer(NUM_MAX_LOG_ENTRIES)
        runBlocking {
            logBuffer.init(this)
            for (i in 0 until NUM_MAX_LOG_ENTRIES + 1) {
                val logData = LogData(LogLevel.DEBUG, "TAG", "MSG$i")
                logBuffer.sendLogDataToChannel(logData)
            }
            val entries = logBuffer.getNewestEntriesForLogFile()
            for (i in 0 until NUM_MAX_LOG_ENTRIES) {
                print(entries[i])
                assertTrue(entries[i].endsWith("MSG${i+1}\n"))
            }
            assertTrue(entries.size == NUM_MAX_LOG_ENTRIES)
            val entriesAgain = logBuffer.getNewestEntriesForLogFile()
            assertTrue(entriesAgain.isEmpty())
            logBuffer.shutdown()
        }
    }

    @Test
    fun getNewestEntriesForLogFile_appendOne_returnsNoDuplicates() {
        val logBuffer = LogBuffer(NUM_MAX_LOG_ENTRIES)
        runBlocking {
            logBuffer.init(this)
            logBuffer.sendLogDataToChannel(LogData(LogLevel.DEBUG, "TAG", "FOO"))
            while (logBuffer.numEntriesNotRead <= 0) {
                yield()
            }
            val entries = logBuffer.getNewestEntriesForLogFile()
            println("entries: $entries")
            assertTrue(entries.size == 1)
            assertTrue(entries[0].endsWith("FOO\n"))
            logBuffer.sendLogDataToChannel(LogData(LogLevel.DEBUG, "TAG", "BAR"))
            while (logBuffer.numEntriesNotRead <= 0) {
                yield()
            }
            val moreEntries = logBuffer.getNewestEntriesForLogFile()
            println("moreEntries: $moreEntries")
            assertTrue(moreEntries.size == 1)
            assertTrue(moreEntries[0].endsWith("BAR\n"))
            logBuffer.shutdown()
        }
    }

    @Test
    fun getNewestEntriesForLogFile_appendMultipleSameTimestamp_returnsCorrectOrder() {
        val logBuffer = LogBuffer(NUM_MAX_LOG_ENTRIES)
        runBlocking {
            logBuffer.init(this)
            val timestamp = "2023-01-11 07:03:40.153"
            for (i in 0..5) {
                logBuffer.sendLogDataToChannel(
                    LogData(
                        LogLevel.DEBUG,
                        "TAG",
                        "FOO${i}",
                        timestamp = timestamp
                    )
                )
            }
            while (logBuffer.numEntriesNotRead <= 0) {
                yield()
            }
            val entries = logBuffer.getNewestEntriesForLogFile()
            println("entries: $entries")
            assertTrue(entries.size == 6)
            for (i in 0 .. 5) {
                assertTrue(entries[i].endsWith("FOO${i}\n"))
            }
            logBuffer.shutdown()
        }
    }

}
