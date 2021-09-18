/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.support.v4.media.MediaMetadataCompat
import de.moleman1024.audiowagon.*
import de.moleman1024.audiowagon.filestorage.AudioFile
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import de.moleman1024.audiowagon.filestorage.usb.USBAudioDataSource
import de.moleman1024.audiowagon.log.Logger
import java.io.ByteArrayOutputStream
import java.io.IOException

private const val TAG = "AudioMetadataMaker"
private val logger = Logger
const val RESOURCE_ROOT_URI = "android.resource://de.moleman1024.audiowagon/drawable/"
const val METADATA_KEY_SAMPLERATE = 38
const val METADATA_KEY_BITS_PER_SAMPLE = 39

/**
 * Extract metadata from audio files.
 *
 * See also https://developer.android.com/reference/kotlin/android/media/MediaMetadataRetriever
 */
class AudioMetadataMaker(private val audioFileStorage: AudioFileStorage) {

    fun extractMetadataFrom(audioFile: AudioFile): AudioItem {
        logger.debug(TAG, "Extracting metadata for: $audioFile")
        val metadataRetriever = MediaMetadataRetriever()
        val dataSource = audioFileStorage.getDataSourceForAudioFile(audioFile)
        // TODO: find out reason for
        //  java.lang.RuntimeException: setDataSourceCallback failed: status = 0x80000000
        //  that sometimes happens for some files
        metadataRetriever.setDataSource(dataSource)
        if (dataSource is USBAudioDataSource && dataSource.isClosed) {
            throw IOException("Data source was closed while extracting metadata")
        }
        // sometimes this will take values from unexpected places, e.g. it prefers APE tags in MP3s over ID3 tags
        val artist: String? = metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
        val album: String? = metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
        val title: String? = metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
        val genre: String? = metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
        val trackNum: Short? = extractTrackNumFromMetadata(metadataRetriever)
        val year: Short? = extractYearFromMetadata(metadataRetriever)
        var durationMS: Int? = null
        val durationMSAsString: String? =
            metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val isInCompilation: Boolean = extractIsCompilation(metadataRetriever)
        metadataRetriever.release()
        metadataRetriever.close()
        try {
            durationMS = durationMSAsString?.let { Util.convertStringToInt(it) }
        } catch (exc: NumberFormatException) {
            logger.error(TAG, "$exc for duration: $durationMSAsString")
        }
        val audioItemForMetadata = AudioItem()
        artist?.let { audioItemForMetadata.artist = it.trim() }
        album?.let { audioItemForMetadata.album = it.trim() }
        if (title?.isNotBlank() == true) {
            audioItemForMetadata.title = title.trim()
        } else {
            audioItemForMetadata.title = audioFile.getFileName().trim()
        }
        genre?.let { audioItemForMetadata.genre = it.trim() }
        trackNum?.let { audioItemForMetadata.trackNum = it }
        year?.let { audioItemForMetadata.year = it }
        durationMS?.let { audioItemForMetadata.durationMS = it }
        audioItemForMetadata.isInCompilation = isInCompilation
        logger.debug(TAG, "Extracted metadata: $audioItemForMetadata")
        return audioItemForMetadata
    }

    private fun extractIsCompilation(metadataRetriever: MediaMetadataRetriever): Boolean {
        val compilationStr: String? =
            metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPILATION)
        if (compilationStr?.trim()?.matches(Regex("(?i)true|1")) == true) {
            return true
        }
        val albumArtist: String? = metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
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
            trackNum = Util.convertStringToShort(trackNumAsString)
        } catch (exc: NumberFormatException) {
            logger.error(TAG, "$exc for track number: $trackNumAsString")
        }
        return trackNum
    }

    private fun extractYearFromMetadata(metadataRetriever: MediaMetadataRetriever): Short? {
        var year: Short? = null
        var yearAsString: String =
            metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: return null
        try {
            // sometimes this is returned as "2008 / 2014", remove that
            yearAsString = yearAsString.replace("/.*".toRegex(), "").trim()
            year = Util.convertStringToShort(yearAsString)
        } catch(exc: java.lang.NumberFormatException) {
            logger.error(TAG, "$exc for year: $yearAsString")
        }
        return year
    }

    fun createMetadataForItem(audioItem: AudioItem): MediaMetadataCompat {
        val type: AudioItemType = ContentHierarchyElement.getType(audioItem.id)
        val databaseID: Long = ContentHierarchyElement.getDatabaseID(audioItem.id)
        val albumArtBytes = getArtForAudioItem(audioItem)
        if (albumArtBytes != null) {
            logger.debug(TAG, "Got album art with size: ${albumArtBytes.size}")
        } else {
            logger.warning(TAG, "Could not retrieve any album art, using default art")
        }
        AlbumArtContentProvider.setAlbumArtByteArray(albumArtBytes)
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
            if (audioItem.genre.isNotBlank()) {
                putString(MediaMetadataCompat.METADATA_KEY_GENRE, audioItem.genre)
            }
            if (audioItem.year >= 0) {
                putLong(MediaMetadataCompat.METADATA_KEY_YEAR, audioItem.year.toLong())
            }
            putLong(MediaMetadataCompat.METADATA_KEY_DURATION, audioItem.durationMS.toLong())
            when (type) {
                AudioItemType.ARTIST -> {
                    putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, audioItem.artist)
                }
                AudioItemType.ALBUM,
                AudioItemType.UNKNOWN_ALBUM,
                AudioItemType.COMPILATION -> {
                    putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, audioItem.album)
                    putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, audioItem.artist)
                }
                AudioItemType.TRACK,
                AudioItemType.TRACKS_FOR_ARTIST,
                AudioItemType.TRACKS_FOR_UNKN_ALBUM,
                AudioItemType.TRACKS_FOR_ALBUM,
                AudioItemType.TRACKS_FOR_COMPILATION -> {
                    putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, audioItem.title)
                    putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, audioItem.artist)
                }
                AudioItemType.GROUP_ALBUMS, AudioItemType.GROUP_ARTISTS, AudioItemType.GROUP_TRACKS -> {
                    throw AssertionError("createMetadataForItem() not supported for groups")
                }
            }
            // This icon will be shown in the "Now Playing" widget
            // We need to always adapt the ID of the album art to produce different content URIs even though we only
            // store the album art for a single track (i.e. the current track). If we don't do this the media
            // browser GUI client will cache the album art becuase of the same URI and re-use it always
            val albumArtContentUri = Uri.parse("content://$AUTHORITY/$TRACK_ART_PATH/${databaseID}")
            putString(MediaMetadataCompat.METADATA_KEY_ART_URI, albumArtContentUri.toString())
            putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, albumArtContentUri.toString())
        }.build()
    }

    private fun getArtForAudioItem(audioItem: AudioItem): ByteArray? {
        val metadataRetriever = MediaMetadataRetriever()
        val mediaDataSource = audioFileStorage.getDataSourceForURI(audioItem.uri)
        logger.debug(TAG, "Retrieving embedded image")
        metadataRetriever.setDataSource(mediaDataSource)
        val embeddedImage = metadataRetriever.embeddedPicture ?: return null
        // TODO: move this log call elsewhere
        logAudioDataMetadata(metadataRetriever)
        metadataRetriever.close()
        val resizedBitmap: Bitmap?
        try {
            logger.debug(TAG, "Decoding embedded image")
            val decodedBitmap = BitmapFactory.decodeByteArray(embeddedImage, 0, embeddedImage.size) ?: return null
            logger.debug(TAG, "Scaling image")
            val widthHeightForResize = 400
            resizedBitmap =
                if (decodedBitmap.width < widthHeightForResize || decodedBitmap.height < widthHeightForResize) {
                    decodedBitmap
                } else {
                    Bitmap.createScaledBitmap(decodedBitmap, widthHeightForResize, widthHeightForResize, false)
                }
        } catch (exc: NullPointerException) {
            logger.exception(TAG, "Exception when decoding image", exc)
            return null
        }
        val stream = ByteArrayOutputStream()
        logger.debug(TAG, "Compressing resized image")
        val quality = 90
        // TODO: I am getting some warnings in log on Pixel 3 XL AAOS that this takes some time
        resizedBitmap?.compress(Bitmap.CompressFormat.JPEG, quality, stream) ?: return null
        return stream.toByteArray()
    }

    private fun logAudioDataMetadata(metadataRetriever: MediaMetadataRetriever) {
        val sampleRate = metadataRetriever.extractMetadata(METADATA_KEY_SAMPLERATE)
        // TODO: shows wrong bitrate for variable bitrate MP3s?
        val bitRate = metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
        // TODO: bitsPerSample is null sometimes
        val bitsPerSample = metadataRetriever.extractMetadata(METADATA_KEY_BITS_PER_SAMPLE)
        val kbpsStr = "%.2f".format(bitRate?.toFloat()?.div(1000.0))
        // When playing back files with samplerates > 48 kHz on the default AAOS build for Pixel 3 XL phone the are
        // resampling artifacts in some files. These do not appear in a Polestar 2 car however, likely a different
        // resampler is used.
        // Also on Pixel 3 XL with AAOS default build some MP3s sound bad, also sounds like the resampler
        logger.info(TAG, "Audio metadata: " +
                "sampleRate=$sampleRate Hz, " +
                "bitsPerSample=$bitsPerSample bits, " +
                "bitRate=$kbpsStr kbps")
    }

}
