/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.runner.RunWith
import org.junit.runners.Suite

// TODO: room database tests: https://developer.android.com/training/data-storage/room/testing-db#android

@ExperimentalCoroutinesApi
@RunWith(Suite::class)
@Suite.SuiteClasses(AudioBrowserServiceTest::class, MediaBrowserTest::class, AudioItemLibraryTest::class)
class InstrumentedTestSuite
