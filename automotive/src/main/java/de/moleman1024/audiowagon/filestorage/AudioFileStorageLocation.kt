/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage

import android.media.MediaDataSource
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel

interface AudioFileStorageLocation {
    val device: MediaDevice
    val storageID: String
    var indexingStatus: IndexingStatus
    var isDetached: Boolean

    @ExperimentalCoroutinesApi
    fun indexAudioFiles(scope: CoroutineScope): ReceiveChannel<AudioFile>
    fun getDataSourceForURI(uri: Uri): MediaDataSource
    fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource
    fun close()
    fun setDetached()
    fun getDirectoriesWithIndexingIssues(): List<String>
}
