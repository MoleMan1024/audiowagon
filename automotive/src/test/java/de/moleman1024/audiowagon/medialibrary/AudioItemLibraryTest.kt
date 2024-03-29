/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@ExperimentalCoroutinesApi
class AudioItemLibraryTest {

    @Test
    fun initRepository_default_createsRepository() {
        val audioItemLibrary = AudioItemLibraryFixture().audioItemLibrary
        val storageID = "FOOBAR"
        runBlocking {
            audioItemLibrary.initRepository(storageID)
            assertTrue(audioItemLibrary.areAnyReposAvail())
        }
    }

    @Test
    fun removeRepository_repositoryExists_deletesRepository() {
        val audioItemLibrary = AudioItemLibraryFixture().audioItemLibrary
        val storageID = "FOOBAR"
        runBlocking {
            audioItemLibrary.initRepository(storageID)
            audioItemLibrary.removeRepository(storageID)
            assertFalse(audioItemLibrary.areAnyReposAvail())
        }
    }

}
