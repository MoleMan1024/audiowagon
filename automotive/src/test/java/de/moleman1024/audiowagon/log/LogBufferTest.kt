/*
SPDX-FileCopyrightText: 2021-2022 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.log

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NUM_MAX_LOG_ENTRIES = 10

class LogBufferTest {

    @Test
    fun getNewestEntriesForLogFile_bufferNotFull_returnsNoDuplicates() {
        val logBuffer = LogBuffer(NUM_MAX_LOG_ENTRIES)
        runBlocking {
            val numLogLines = NUM_MAX_LOG_ENTRIES / 2
            for (i in 0 until numLogLines) {
                val logData = LogData(LoggerInterface.LogLevel.DEBUG, "TAG", "MSG$i")
                logBuffer.append(logData)
            }
            val entries = logBuffer.getNewestEntriesForLogFile()
            for (i in 0 until numLogLines) {
                print(entries[i])
                assertTrue(entries[i].endsWith("MSG$i\n"))
            }
            assertTrue(entries.size == numLogLines)
            val entriesAgain = logBuffer.getNewestEntriesForLogFile()
            assertTrue(entriesAgain.isEmpty())
        }
    }

    @Test
    fun getNewestEntriesForLogFile_bufferFull_returnsNoDuplicates() {
        val logBuffer = LogBuffer(NUM_MAX_LOG_ENTRIES)
        runBlocking {
            for (i in 0 until NUM_MAX_LOG_ENTRIES + 1) {
                val logData = LogData(LoggerInterface.LogLevel.DEBUG, "TAG", "MSG$i")
                logBuffer.append(logData)
            }
            val entries = logBuffer.getNewestEntriesForLogFile()
            for (i in 0 until NUM_MAX_LOG_ENTRIES) {
                print(entries[i])
                assertTrue(entries[i].endsWith("MSG${i+1}\n"))
            }
            assertTrue(entries.size == NUM_MAX_LOG_ENTRIES)
            val entriesAgain = logBuffer.getNewestEntriesForLogFile()
            assertTrue(entriesAgain.isEmpty())
        }
    }

    @Test
    fun getNewestEntriesForLogFile_appendOne_returnsNoDuplicates() {
        val logBuffer = LogBuffer(NUM_MAX_LOG_ENTRIES)
        runBlocking {
            logBuffer.append(LogData(LoggerInterface.LogLevel.DEBUG, "TAG", "FOO"))
            val entries = logBuffer.getNewestEntriesForLogFile()
            println("entries: $entries")
            assertTrue(entries.size == 1)
            assertTrue(entries[0].endsWith("FOO\n"))
            logBuffer.append(LogData(LoggerInterface.LogLevel.DEBUG, "TAG", "BAR"))
            val moreEntries = logBuffer.getNewestEntriesForLogFile()
            println("moreEntries: $moreEntries")
            assertTrue(moreEntries.size == 1)
            assertTrue(moreEntries[0].endsWith("BAR\n"))
        }
    }

}
