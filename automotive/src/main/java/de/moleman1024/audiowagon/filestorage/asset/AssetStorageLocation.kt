/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.asset

import android.media.MediaDataSource
import android.net.Uri
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.filestorage.*
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import java.util.*

private const val TEST_MP3_FILENAME = "test.mp3"

/**
 * NOTE: assets bundled with the app are only used to pass Google's automatic reviews which require some demo data
 */
class AssetStorageLocation(override val device: AssetMediaDevice) : AudioFileStorageLocation {
    override val TAG = "AssetStorLoc"
    override val logger = Logger
    override val storageID: String
        get() = device.getID()
    override var indexingStatus: IndexingStatus = IndexingStatus.NOT_INDEXED
    override var isDetached: Boolean = false
    override var isIndexingCancelled: Boolean = false
    // does not need a thread confinement
    override var libaumsDispatcher: CoroutineDispatcher? = null

    override fun walkTopDown(startDirectory: Any, scope: CoroutineScope): Sequence<Any> {
        return sequenceOf(createTestAudioFile())
    }

    override fun getDirectoryContents(directory: Directory): List<FileLike> {
        return listOf(createTestAudioFile())
    }

    override fun getDirectoryContentsPlayable(directory: Directory): List<FileLike> {
        return getDirectoryContents(directory)
    }

    private fun createTestAudioFile(): AudioFile {
        val uri = Util.createURIForPath(storageID, TEST_MP3_FILENAME)
        val audioFile = AudioFile(uri)
        audioFile.lastModifiedDate = Date()
        return audioFile
    }

    override fun getDataSourceForURI(uri: Uri): MediaDataSource {
        return device.getDataSourceForURI(uri)
    }

    override fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource {
        return device.getBufferedDataSourceForURI(uri)
    }

    override fun getByteArrayForURI(uri: Uri): ByteArray {
        TODO("Not yet implemented")
    }

    override fun getInputStreamForURI(uri: Uri): LockableInputStream {
        TODO("Not yet implemented")
    }

    override fun close() {
        device.close()
    }

    override fun setDetached() {
        device.isClosed = true
        isDetached = true
    }

    override fun getRootURI(): Uri {
        return Util.createURIForPath(storageID, device.getRoot())
    }

}
