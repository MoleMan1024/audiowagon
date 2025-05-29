package de.moleman1024.audiowagon.filestorage
import de.moleman1024.audiowagon.filestorage.usb.USBAudioCachedDataSource
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.test.TestScope
import org.junit.Assert
import org.junit.Test

private const val TAG = "USBAudioCachedDataSourceTest"

class USBAudioCachedDataSourceTest {

    @Test
    fun usbAudioCachedDataSourceReadAt_multipleBlocks_readsData() {
        val mockUSBFile = createMockUSBFile(200)
        val outBufSize = 8
        var position = 32
        val offset = 0
        val sizeToRead = 8
        val chunkSize = 16
        val dataSource = USBAudioCachedDataSource(mockUSBFile, chunkSize)
        dataSource.bufSize = chunkSize
        val outBuffer = ByteArray(outBufSize)
        for (i in 0 until 20) {
            position = 32 + (i * sizeToRead)
            Logger.info(TAG, "Client calls readAt(position=$position)")
            val numBytesRead = dataSource.readAt(position.toLong(), outBuffer, offset, sizeToRead)
            Assert.assertEquals(sizeToRead, numBytesRead)
            val expectedBytes = mockUSBFile.bytes.slice(position until position + numBytesRead)
            Assert.assertEquals(expectedBytes, outBuffer.toList().slice(offset until offset + numBytesRead))
        }
        Logger.debug(TAG, "cacheMap=" + dataSource.cacheMap)
        Assert.assertEquals(5, dataSource.cacheMap.size)
        Assert.assertArrayEquals(
            arrayListOf<Long>(128, 144, 160, 176, 192).toArray(),
            dataSource.cacheMap.keys.toTypedArray()
        )
    }

    @Suppress("SameParameterValue")
    private fun createMockUSBFile(size: Int): MockUSBFile {
        val bytes = ByteArray(size)
        for (i in 0 until size) {
            bytes[i] = (i % Byte.MAX_VALUE).toByte()
        }
        val mockUSBFile = MockUSBFile()
        mockUSBFile.setBytes(bytes)
        return mockUSBFile
    }
}
