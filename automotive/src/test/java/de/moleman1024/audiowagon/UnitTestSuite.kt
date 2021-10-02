/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import de.moleman1024.audiowagon.filestorage.MediaDataSourceTest
import de.moleman1024.audiowagon.medialibrary.AudioItemLibraryTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.runner.RunWith
import org.junit.runners.Suite

// need to run "gradle test" to compile this

@ExperimentalCoroutinesApi
@RunWith(Suite::class)
@Suite.SuiteClasses(MediaDataSourceTest::class, AudioItemLibraryTest::class, UtilTest::class)
class UnitTestSuite
