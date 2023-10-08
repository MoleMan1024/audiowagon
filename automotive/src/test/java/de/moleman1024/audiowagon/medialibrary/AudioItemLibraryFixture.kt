/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary

import android.content.Context
import de.moleman1024.audiowagon.GUI
import de.moleman1024.audiowagon.SharedPrefs
import de.moleman1024.audiowagon.enums.AlbumStyleSetting
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.mockito.Mockito

@ExperimentalCoroutinesApi
class AudioItemLibraryFixture {
    private val dispatcher = Dispatchers.IO
    private val context: Context = Mockito.mock(Context::class.java)
    private val mockAudioFileStorage: AudioFileStorage = Mockito.mock(AudioFileStorage::class.java)
    private val scope = TestScope()
    private val mockGUI: GUI = Mockito.mock(GUI::class.java)
    private val sharedPrefs: SharedPrefs = Mockito.mock(SharedPrefs::class.java)
    var audioItemLibrary: AudioItemLibrary

    /**
     * https://stackoverflow.com/questions/49148801/mock-object-in-android-unit-test-with-kotlin-any-gives-null
     * Returns Mockito.any() as nullable type to avoid java.lang.IllegalStateException when null is returned.
     */
    private fun <T> any(): T = Mockito.any()

    init {
        Mockito.`when`(sharedPrefs.getAlbumStyleSetting(this.any<Context>())).thenReturn(AlbumStyleSetting.GRID.name)
        audioItemLibrary = AudioItemLibrary(context, mockAudioFileStorage, scope, dispatcher, mockGUI, sharedPrefs)
    }
}
