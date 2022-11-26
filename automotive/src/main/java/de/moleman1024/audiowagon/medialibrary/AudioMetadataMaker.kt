/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.support.v4.media.MediaMetadataCompat
import de.moleman1024.audiowagon.AUTHORITY
import de.moleman1024.audiowagon.AlbumArtContentProvider
import de.moleman1024.audiowagon.DEFAULT_JPEG_QUALITY_PERCENTAGE
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.filestorage.AudioFile
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import de.moleman1024.audiowagon.filestorage.usb.USBAudioDataSource
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyElement
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

private const val TAG = "AudioMetadataMaker"
private val logger = Logger
const val RESOURCE_ROOT_URI = "android.resource://de.moleman1024.audiowagon/drawable/"
const val ART_URI_PART = "art"
const val ART_URI_PART_ALBUM = "album"
const val ART_URI_PART_TRACK = "track"
const val ART_URI_PART_FILE = "file"

/**
 * Extract metadata from audio files.
 *
 * See also https://developer.android.com/reference/kotlin/android/media/MediaMetadataRetriever
 */
@ExperimentalCoroutinesApi
class AudioMetadataMaker(private val audioFileStorage: AudioFileStorage) {

    suspend fun extractMetadataFrom(audioFile: AudioFile): AudioItem {
        val startTime = System.nanoTime()
        logger.debug(TAG, "Extracting metadata for: ${audioFile.name}")
        val metadataRetriever = MediaMetadataRetriever()
        val dataSource = audioFileStorage.getDataSourceForAudioFile(audioFile)
        // TODO: find out reason for
        //  java.lang.RuntimeException: setDataSourceCallback failed: status = 0x80000000
        //  that sometimes happens for some files
        // This can fail on invalid UTF-8 strings in metadata with a JNI error which will cause app to crash.
        //  Needs to be fixed inside Android Automotive itself.
        metadataRetriever.setDataSource(dataSource)
        if (dataSource is USBAudioDataSource && dataSource.hasError) {
            throw IOException("DataSource error")
        }
        if (dataSource is USBAudioDataSource && dataSource.isClosed) {
            throw IOException("Data source was closed while extracting metadata")
        }
        logger.verbose(TAG, "Starting metadata extraction")
        // sometimes this will take values from unexpected places, e.g. it prefers APE tags in MP3s over ID3 tags
        val title: String? = metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
        val artist: String? = metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
        val album: String? = metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
        val year: Short? = extractYearFromMetadata(metadataRetriever)
        val trackNum: Short? = extractTrackNumFromMetadata(metadataRetriever)
        val discNum: Short? = extractDiscNumFromMetadata(metadataRetriever)
        var durationMS: Int? = null
        val durationMSAsString: String? =
            metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val albumArtist: String? = metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
        val compilationStr: String? = metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPILATION)
        val isInCompilation: Boolean = extractIsCompilation(compilationStr, albumArtist)
        metadataRetriever.release()
        if (dataSource is USBAudioDataSource && dataSource.hasError) {
            throw IOException("DataSource error")
        }
        metadataRetriever.close()
        if (dataSource is USBAudioDataSource && dataSource.hasError) {
            throw IOException("DataSource error")
        }
        try {
            durationMS = durationMSAsString?.let { Util.convertStringToInt(it) }
        } catch (exc: NumberFormatException) {
            logger.error(TAG, "$exc for duration: $durationMSAsString")
        }
        val audioItemForMetadata = AudioItem()
        artist?.let { audioItemForMetadata.artist = it.trim() }
        album?.let { audioItemForMetadata.album = it.trim() }
        albumArtist?.let { audioItemForMetadata.albumArtist = it.trim() }
        if (title?.isNotBlank() == true) {
            audioItemForMetadata.title = title.trim()
        } else {
            audioItemForMetadata.title = audioFile.name.trim()
        }
        trackNum?.let { audioItemForMetadata.trackNum = it }
        discNum?.let { audioItemForMetadata.discNum = it }
        year?.let { audioItemForMetadata.year = it }
        durationMS?.let { audioItemForMetadata.durationMS = it }
        audioItemForMetadata.isInCompilation = isInCompilation
        val endTime = System.nanoTime()
        val timeTakenMS = (endTime - startTime) / 1000000L
        logger.verbose(TAG, "Extracted metadata in ${timeTakenMS}ms: $audioItemForMetadata")
        return audioItemForMetadata
    }

    private fun extractIsCompilation(compilationStr: String?, albumArtist: String?): Boolean {
        if (compilationStr?.trim()?.matches(Regex("(?i)true|1")) == true) {
            return true
        }
        if (albumArtist?.trim()?.matches(Regex("(?i)^(various.*artist.*|compilation)$")) == true) {
            return true
        }
        return false
    }

    private fun extractTrackNumFromMetadata(metadataRetriever: MediaMetadataRetriever): Short? {
        var trackNum: Short? = null
        var trackNumAsString: String =
            metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER) ?: return null
        try {
            // sometimes this is returned as e.g. "5/11" (five out of eleven tracks), remove that
            trackNumAsString = trackNumAsString.replace("/.*".toRegex(), "").trim()
            if (trackNumAsString.isBlank()) {
                return null
            }
            trackNum = Util.convertStringToShort(trackNumAsString)
        } catch (exc: NumberFormatException) {
            logger.error(TAG, "$exc for track number: $trackNumAsString")
        }
        return trackNum
    }

    private fun extractDiscNumFromMetadata(metadataRetriever: MediaMetadataRetriever): Short? {
        var discNum: Short? = null
        var discNumAsString: String =
            metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER) ?: return null
        try {
            // sometimes this is returned as e.g. "1/1" (one out of one discs), remove that
            discNumAsString = discNumAsString.replace("(CD|/.*)".toRegex(), "").trim()
            if (discNumAsString.isBlank()) {
                return null
            }
            discNum = Util.convertStringToShort(discNumAsString)
        } catch (exc: NumberFormatException) {
            logger.error(TAG, "$exc for disc number: $discNumAsString")
        }
        return discNum
    }

    private fun extractYearFromMetadata(metadataRetriever: MediaMetadataRetriever): Short? {
        var year: Short? = null
        var yearAsString: String =
            metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: return null
        try {
            yearAsString = Util.sanitizeYear(yearAsString)
            year = Util.convertStringToShort(yearAsString)
        } catch (exc: java.lang.NumberFormatException) {
            logger.error(TAG, "$exc for year: $yearAsString")
        }
        return year
    }

    /**
     * Takes metadata from a given [AudioItem] and converts it to a [MediaMetadataCompat] so that the media
     * controller GUI will display it (e.g. to to display duration, title, subtitle in the "now playing" view)
     */
    fun createMetadataForItem(audioItem: AudioItem): MediaMetadataCompat {
        val contentHierarchyID = ContentHierarchyElement.deserialize(audioItem.id)
        // TODO: store this? https://developer.android.com/guide/topics/media-apps/working-with-a-media-session
        return MediaMetadataCompat.Builder().apply {
            putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, audioItem.id)
            putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, audioItem.uri.toString())
            if (audioItem.artist.isNotBlank()) {
                putString(MediaMetadataCompat.METADATA_KEY_ARTIST, audioItem.artist)
            }
            if (audioItem.album.isNotBlank()) {
                putString(MediaMetadataCompat.METADATA_KEY_ALBUM, audioItem.album)
            }
            if (audioItem.title.isNotBlank()) {
                putString(MediaMetadataCompat.METADATA_KEY_TITLE, audioItem.title)
            }
            if (audioItem.trackNum >= 0) {
                putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, audioItem.trackNum.toLong())
            }
            if (audioItem.discNum >= 0) {
                putLong(MediaMetadataCompat.METADATA_KEY_DISC_NUMBER, audioItem.discNum.toLong())
            }
            if (audioItem.year >= 0) {
                putLong(MediaMetadataCompat.METADATA_KEY_YEAR, audioItem.year.toLong())
            }
            putLong(MediaMetadataCompat.METADATA_KEY_DURATION, audioItem.durationMS.toLong())
            when (contentHierarchyID.type) {
                ContentHierarchyType.ARTIST -> {
                    putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, audioItem.artist)
                }
                ContentHierarchyType.ALBUM,
                ContentHierarchyType.UNKNOWN_ALBUM,
                ContentHierarchyType.COMPILATION -> {
                    putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, audioItem.album)
                    putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, audioItem.artist)
                }
                ContentHierarchyType.TRACK,
                ContentHierarchyType.ALL_TRACKS_FOR_ARTIST,
                ContentHierarchyType.ALL_TRACKS_FOR_UNKN_ALBUM,
                ContentHierarchyType.ALL_TRACKS_FOR_ALBUM,
                ContentHierarchyType.ALL_TRACKS_FOR_COMPILATION -> {
                    putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, audioItem.title)
                    putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, audioItem.artist)
                }
                ContentHierarchyType.FILE -> {
                    putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, audioItem.title)
                }
                else -> {
                    throw AssertionError("createMetadataForItem() not supported for: $contentHierarchyID")
                }
            }
            putString(MediaMetadataCompat.METADATA_KEY_ART_URI, audioItem.albumArtURI.toString())
            // The METADATA_KEY_DISPLAY_ICON_URI will be shown in the "Now Playing" widget
            putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, audioItem.albumArtURI.toString())
        }.build()
    }

    suspend fun getEmbeddedAlbumArtForAudioItem(audioItem: AudioItem): ByteArray? {
        val mediaDataSource = audioFileStorage.getDataSourceForURI(audioItem.uri)
        logger.debug(TAG, "Retrieving embedded image for: ${audioItem.uri}")
        return getAlbumArtFromMediaDataSource(mediaDataSource)
    }

    private fun getAlbumArtFromMediaDataSource(mediaDataSource: MediaDataSource): ByteArray? {
        val metadataRetriever = MediaMetadataRetriever()
        if (mediaDataSource.size <= 0) {
            logger.error(TAG, "Media data source for album art is empty")
            return null
        }
        metadataRetriever.setDataSource(mediaDataSource)
        val embeddedImage = metadataRetriever.embeddedPicture ?: return null
        metadataRetriever.close()
        return embeddedImage
    }

    suspend fun hasEmbeddedAlbumArt(audioItem: AudioItem): Boolean {
        logger.verbose(TAG, "hasAlbumArt(audioItem=$audioItem)")
        val mediaDataSource = audioFileStorage.getDataSourceForURI(audioItem.uri)
        return getAlbumArtFromMediaDataSource(mediaDataSource) != null
    }

    fun resizeAlbumArt(albumArtBytes: ByteArray): ByteArray? {
        val resizedBitmap: Bitmap
        try {
            logger.verbose(TAG, "Decoding image")
            // https://developer.android.com/topic/performance/graphics/load-bitmap
            val bitmapOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(albumArtBytes, 0, albumArtBytes.size, bitmapOptions)
            logger.verbose(
                TAG,
                "Got original bitmap bounds: width=${bitmapOptions.outWidth},height=${bitmapOptions.outHeight}"
            )
            val widthHeightForResize = AlbumArtContentProvider.getAlbumArtSizePixels()
            bitmapOptions.inSampleSize = calculateBitmapInSampleSize(
                bitmapOptions.outWidth,
                bitmapOptions.outHeight,
                widthHeightForResize,
                widthHeightForResize
            )
            logger.verbose(TAG, "Using bitmap inSampleSize: ${bitmapOptions.inSampleSize}")
            bitmapOptions.inJustDecodeBounds = false
            logger.verbose(TAG, "Scaling image")
            resizedBitmap =
                BitmapFactory.decodeByteArray(albumArtBytes, 0, albumArtBytes.size, bitmapOptions) ?: return null
        } catch (exc: NullPointerException) {
            logger.exception(TAG, "Exception when decoding image", exc)
            return null
        }
        val stream = ByteArrayOutputStream()
        logger.verbose(TAG, "Compressing resized image")
        val quality = DEFAULT_JPEG_QUALITY_PERCENTAGE
        // TODO: I am getting some warnings in log on Pixel 3 XL AAOS that this takes some time
        resizedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    // https://developer.android.com/topic/performance/graphics/load-bitmap
    private fun calculateBitmapInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    companion object {
        fun createURIForAlbumArtForAlbum(id: Int): Uri {
            return Uri.parse("content://$AUTHORITY/$ART_URI_PART/$ART_URI_PART_ALBUM/${id}")
        }

        fun createURIForAlbumArtForTrack(id: Int): Uri {
            return Uri.parse("content://$AUTHORITY/$ART_URI_PART/$ART_URI_PART_TRACK/${id}")
        }

        fun createURIForAlbumArtForFile(path: String): Uri {
            val pathEncoded = Uri.encode(path)
            return Uri.parse("content://$AUTHORITY/$ART_URI_PART/$ART_URI_PART_FILE${pathEncoded}")
        }
    }

}
