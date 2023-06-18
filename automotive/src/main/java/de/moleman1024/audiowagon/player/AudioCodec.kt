package de.moleman1024.audiowagon.player

import android.net.Uri
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

private const val TAG = "AudioCodec"
private val logger = Logger
private const val NUM_BYTES_METADATA = 1024

@ExperimentalCoroutinesApi
class AudioCodec(private val audioFileStorage: AudioFileStorage) {

    suspend fun isSupported(uri: Uri): Boolean {
        logger.debug(TAG, "Checking if codec is supported for: $uri")
        val dataSource = audioFileStorage.getDataSourceForURI(uri)
        val dataFront = ByteArray(NUM_BYTES_METADATA)
        dataSource.readAt(0L, dataFront, 0, dataFront.size)
        withContext(Dispatchers.IO) {
            dataSource.close()
        }
        if (String(dataFront).contains("alac")) {
            // Apple Lossless Audio is not supported by Android
            return false
        }
        return true
    }

}
