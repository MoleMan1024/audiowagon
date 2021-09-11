/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary

import android.content.Context
import de.moleman1024.audiowagon.GUI
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScope
import org.mockito.Mockito

@ExperimentalCoroutinesApi
class AudioItemLibraryFixture {
    private val dispatcher = Dispatchers.IO
    val context = Mockito.mock(Context::class.java)
    val mockAudioFileStorage = Mockito.mock(AudioFileStorage::class.java)
    val scope = TestCoroutineScope()
    val mockGUI = Mockito.mock(GUI::class.java)
    val audioItemLibrary = AudioItemLibrary(context, mockAudioFileStorage, scope, dispatcher, mockGUI)
}
