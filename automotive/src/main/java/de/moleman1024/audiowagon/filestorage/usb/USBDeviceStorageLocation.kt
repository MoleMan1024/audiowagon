/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb

import android.media.MediaDataSource
import android.net.Uri
import com.github.mjdev.libaums.fs.UsbFile
import de.moleman1024.audiowagon.filestorage.AudioFile
import de.moleman1024.audiowagon.filestorage.AudioFileStorageLocation
import de.moleman1024.audiowagon.filestorage.IndexingStatus
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import java.net.URLConnection
import java.util.*

private const val URI_SCHEME = "usbAudio"
private const val TAG = "USBDevStorLoc"
private val logger = Logger

class USBDeviceStorageLocation(override val device: USBMediaDevice) : AudioFileStorageLocation {
    override val storageID: String
        get() = device.getID()
    override var indexingStatus: IndexingStatus = IndexingStatus.NOT_INDEXED
    override var isDetached: Boolean = false

    @ExperimentalCoroutinesApi
    override fun indexAudioFiles(scope: CoroutineScope): ReceiveChannel<AudioFile> {
        logger.debug(TAG, "indexAudioFiles(${device.getShortName()})")
        return scope.produce {
            for (usbFile in device.walkTopDown(device.getRoot())) {
                if (!isPlayableAudioFile(usbFile)) {
                    logger.debug(TAG, "Skipping non-audio file: $usbFile")
                    continue
                }
                val builder: Uri.Builder = Uri.Builder()
                builder.scheme(URI_SCHEME).authority(storageID).appendEncodedPath(
                    usbFile.absolutePath.removePrefix("/")
                )
                val audioFile = AudioFile(builder.build())
                audioFile.lastModifiedDate = Date(usbFile.lastModified())
                send(audioFile)
            }
        }
    }

    override fun getDataSourceForURI(uri: Uri): MediaDataSource {
        return device.getDataSourceForURI(uri)
    }

    override fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource {
        return device.getBufferedDataSourceForURI(uri)
    }

    override fun close() {
        device.close()
    }

    override fun setDetached() {
        device.preventLoggingToDetachedDevice()
        device.closeMassStorageFilesystem()
        isDetached = true
    }

    // TODO: might want to check also if audio file format is compatible with player
    // see https://source.android.com/compatibility/10/android-10-cdd#5_1_3_audio_codecs_details
    private fun isPlayableAudioFile(usbFile: UsbFile): Boolean {
        if (usbFile.isDirectory || usbFile.isRoot) {
            return false
        }
        val guessedContentType: String
        try {
            val safeFileName = makeFileNameSafeForContentTypeGuessing(usbFile.name)
            guessedContentType = URLConnection.guessContentTypeFromName(safeFileName) ?: return false
        } catch (exc: StringIndexOutOfBoundsException) {
            logger.exception(TAG, "Error when guessing content type of: ${usbFile.name}", exc)
            return false
        }
        if (!guessedContentType.startsWith("audio")) {
            return false
        }
        if (isPlaylistFile(guessedContentType)) {
            return false
        }
        return true
    }

    /**
     * The method guessContentTypeFromName() throws errors when certain characters appear in the name, remove those
     */
    private fun makeFileNameSafeForContentTypeGuessing(fileName: String): String {
        return fileName.replace("#", "")
    }

    // TODO: check for other things that are not playable audio file
    /**
     * Checks if the given audio content/MIME type string represents a playlist (e.g. "mpegurl" is for .m3u
     * playlists)
     *
     * @param contentType the content/MIME type string
     */
    private fun isPlaylistFile(contentType: String): Boolean {
        return listOf("mpequrl", "mpegurl").any { it in contentType }
    }

    override fun toString(): String {
        return "USBDeviceStorageLocation{${device.getID()}}"
    }

}
