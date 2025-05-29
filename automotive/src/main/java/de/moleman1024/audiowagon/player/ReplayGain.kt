package de.moleman1024.audiowagon.player

import android.content.Context
import android.net.Uri
import de.moleman1024.audiowagon.SharedPrefs
import de.moleman1024.audiowagon.exceptions.MissingEffectsException
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.AudioItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

private const val TAG = "ReplayGain"
private val logger = Logger
private const val REPLAYGAIN_NOT_FOUND: Float = -99.0f
private const val NUM_BYTES_METADATA = 1024

// loudness normalization
// https://en.wikipedia.org/wiki/ReplayGain
@ExperimentalCoroutinesApi
class ReplayGain(
    private val context: Context,
    private val sharedPrefs: SharedPrefs,
    private val audioPlayer: AudioPlayer,
    private val audioFileStorage: AudioFileStorage
) {
    private var extractReplayGain: Boolean = false
    private val replayGainRegex = "replaygain_track_gain.*?([-\\d][^ ]+?) ?dB".toRegex(RegexOption.IGNORE_CASE)
    private val replayGainOpusRegex = "R128_TRACK_GAIN=([-\\d]+)".toRegex()

    init {
        extractReplayGain = sharedPrefs.isReplayGainEnabled(context)
    }

    suspend fun extractAndSetReplayGain(audioItem: AudioItem) {
        if (!extractReplayGain) {
            return
        }
        val replayGain: Float
        try {
            replayGain = extractReplayGain(audioItem.uri)
            logger.debug(TAG, "Setting ReplayGain: $replayGain dB")
            audioPlayer.setReplayGain(replayGain)
        } catch (exc: MissingEffectsException) {
            sharedPrefs.setReplayGainEnabled(context, false)
            extractReplayGain = false
            throw exc
        } catch (exc: Exception) {
            logger.exception(TAG, exc.message.toString(), exc)
        }
    }

    fun enable() {
        extractReplayGain = true
    }

    fun disable() {
        extractReplayGain = false
    }

    // TODO: move this elsewhere
    private suspend fun extractReplayGain(uri: Uri): Float {
        logger.debug(TAG, "extractReplayGain($uri)")
        var replayGain: Float
        val dataSource = audioFileStorage.getDataSourceForURI(uri)
        val dataFront = ByteArray(NUM_BYTES_METADATA)
        // IDv3 and opus tags are at the beginning of the file
        dataSource.readAt(0L, dataFront, 0, dataFront.size)
        replayGain = findReplayGainInBytes(dataFront)
        if (replayGain != REPLAYGAIN_NOT_FOUND) {
            withContext(Dispatchers.IO) {
                dataSource.close()
            }
            return replayGain
        }
        // supported containers for opus files: https://developer.android.com/guide/topics/media/media-formats
        if (listOf(".opus", ".ogg", ".mkv").any { extension -> uri.toString().lowercase().endsWith(extension) }) {
            replayGain = findReplayGainOpusInBytes(dataFront)
            if (replayGain != REPLAYGAIN_NOT_FOUND) {
                withContext(Dispatchers.IO) {
                    dataSource.close()
                }
                return replayGain
            }
        }
        val dataBack = ByteArray(NUM_BYTES_METADATA)
        // APE tags are at the end of the file
        dataSource.readAt(dataSource.size - dataBack.size, dataBack, 0, dataBack.size)
        replayGain = findReplayGainInBytes(dataBack)
        if (replayGain == REPLAYGAIN_NOT_FOUND) {
            replayGain = 0f
        }
        withContext(Dispatchers.IO) {
            dataSource.close()
        }
        return replayGain
    }

    private fun findReplayGainInBytes(bytes: ByteArray): Float {
        val bytesStr = String(bytes)
        var replayGain = REPLAYGAIN_NOT_FOUND
        val replayGainMatch = replayGainRegex.find(bytesStr)
        if (replayGainMatch?.groupValues?.size == 2) {
            val replayGainStr = replayGainMatch.groupValues[1].trim()
            try {
                replayGain = replayGainStr.toFloat()
            } catch (_: NumberFormatException) {
                return REPLAYGAIN_NOT_FOUND
            }
        }
        return replayGain
    }

    // #99: Extract .opus replaygain. The additional "opus header gain" should be automatically applied by Android when
    // decoding, if I read https://android.googlesource.com/platform/frameworks/av/+/master/media/codec2/components/opus/C2SoftOpusDec.cpp#297
    // correctly and this is used in cars
    private fun findReplayGainOpusInBytes(bytes: ByteArray): Float {
        val bytesStr = String(bytes)
        var replayGain = REPLAYGAIN_NOT_FOUND
        val replayGainOpusMatch = replayGainOpusRegex.find(bytesStr)
        if (replayGainOpusMatch?.groupValues?.size == 2) {
            val replayGainStr = replayGainOpusMatch.groupValues[1].trim()
            try {
                // opus replay gain is stored as 8-bit signed integer
                val replayGainQ78 = replayGainStr.toInt()
                replayGain = replayGainQ78.toFloat() / 256f
            } catch (_: java.lang.NumberFormatException) {
                return REPLAYGAIN_NOT_FOUND
            }
        }
        return replayGain
    }

}
