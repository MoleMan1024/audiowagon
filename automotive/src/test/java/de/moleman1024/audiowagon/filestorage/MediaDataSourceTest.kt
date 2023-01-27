/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage

import android.media.MediaDataSource
import de.moleman1024.audiowagon.filestorage.usb.USBAudioDataSource
import de.moleman1024.audiowagon.filestorage.usb.USBMetaDataSource
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.Executors

private const val TAG = "MediaDataSrcTest"

@RunWith(Parameterized::class)
class MediaDataSourceTest(
    private val position: Int,
    private val offset: Int,
    private val sizeToRead: Int,
    private val outBufSize: Int,
    private val chunkSize: Int
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters
        fun getReadAtConfigs(): List<Array<Int>> {
            val configs = mutableListOf<ReadAtConfig>()
            configs.add(ReadAtConfig(position = 0, offset = 0, sizeToRead = 4, outBufSize = 8, chunkSize = 10))
            configs.add(ReadAtConfig(position = 0, offset = 0, sizeToRead = 4, outBufSize = 8, chunkSize = 20))
            configs.add(ReadAtConfig(position = 5, offset = 0, sizeToRead = 4, outBufSize = 8, chunkSize = 10))
            configs.add(ReadAtConfig(position = 12, offset = 0, sizeToRead = 4, outBufSize = 8, chunkSize = 10))
            configs.add(ReadAtConfig(position = 5, offset = 8, sizeToRead = 4, outBufSize = 16, chunkSize = 10))
            configs.add(ReadAtConfig(position = 14, offset = 12, sizeToRead = 4, outBufSize = 16, chunkSize = 10))
            configs.add(ReadAtConfig(position = 8, offset = 4, sizeToRead = 20, outBufSize = 30, chunkSize = 10))
            configs.add(ReadAtConfig(position = 0, offset = 0, sizeToRead = 30, outBufSize = 30, chunkSize = 10))
            configs.add(ReadAtConfig(position = 0, offset = 0, sizeToRead = 30, outBufSize = 30, chunkSize = 30))
            configs.add(ReadAtConfig(position = 6, offset = 0, sizeToRead = 8, outBufSize = 16, chunkSize = 10))
            return configs.map {
                it.getArray()
            }
        }
    }

    data class ReadAtConfig(
        private val position: Int,
        private val offset: Int,
        private val sizeToRead: Int,
        private val outBufSize: Int,
        private val chunkSize: Int
    ) {
        fun getArray(): Array<Int> {
            return arrayOf(position, offset, sizeToRead, outBufSize, chunkSize)
        }
    }

    init {
        Logger.info(
            TAG, "position=$position, offset=$offset, sizeToRead=$sizeToRead, outBufSize=$outBufSize, " +
                "chunkSize=$chunkSize")
    }

    @Test
    fun usbAudioDataSourceReadAt_multipleConfigs_readsData() {
        val mockUSBFile = createMockUSBFile(100)
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        testMediaDataSource(USBAudioDataSource(mockUSBFile, chunkSize, dispatcher), mockUSBFile.bytes)
    }

    @Test
    fun usbAudioCachedDataSourceReadAt_multipleConfigs_readsData() {
        val mockUSBFile = createMockUSBFile(100)
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        testMediaDataSource(USBAudioDataSource(mockUSBFile, chunkSize, dispatcher), mockUSBFile.bytes)
    }

    @Test
    fun usbMetaDataSourceReadAt_multipleConfigs_readsData() {
        val mockUSBFile = createMockUSBFile(100)
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        testMediaDataSource(USBMetaDataSource(mockUSBFile, chunkSize, dispatcher), mockUSBFile.bytes)
    }

    private fun testMediaDataSource(dataSource: MediaDataSource, bytes: ByteArray) {
        val outBuffer = ByteArray(outBufSize)
        val numBytesRead = dataSource.readAt(position.toLong(), outBuffer, offset, sizeToRead)
        assertEquals(sizeToRead, numBytesRead)
        val expectedBytes = bytes.slice(position until position + sizeToRead)
        assertEquals(expectedBytes, outBuffer.toList().slice(offset until offset + numBytesRead))
    }

    @Suppress("SameParameterValue")
    private fun createMockUSBFile(size: Int): MockUSBFile {
        val bytes = ByteArray(size)
        for (i in 0 until size) {
            bytes[i] = i.toByte()
        }
        val mockUSBFile = MockUSBFile()
        mockUSBFile.setBytes(bytes)
        return mockUSBFile
    }
}
