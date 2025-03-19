/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.runner.RunWith
import org.junit.runners.Suite

// TODO: room database tests: https://developer.android.com/training/data-storage/room/testing-db#android
// TODO: audio player + queue tests (select item from shuffle playback queue etc.)
// TODO: test shutdown/closing of resources
// TODO: test audio focus (also with manual user "overrides")
// TODO: test equalizer not working after ejecting
// TODO: test no permission for serial number(?)
// TODO: test turning on logging without agreed legal disclaimer
// TODO: test #12 filenames with percent signs
// TODO: test onPlay during initialization is latched for later
// TODO: test #44 order of items returned in some SQL queries
// TODO: test that we avoid duplicate tracks when creating random playback queue
// TODO: measure memory again, see where I use too much: others 15MB, Code 15MB, Stack 0.08MB, Graphics 7 MB, Native
//  23 MB, Java 7MB

@ExperimentalCoroutinesApi
@RunWith(Suite::class)
@Suite.SuiteClasses(
    AudioBrowserServiceTest::class,
    AudioItemLibraryTest::class,
    RepositoryTest::class,
    IndexingTest::class,
    SharedPrefsTest::class,
    DatabaseMigrationTest::class,
    DatabaseTest::class,
    MediaSearchTest::class,
    PlaybackTest::class,
    AlbumArtTest::class,
    // TODO: tests are slow
    MediaBrowserTest::class,
    SettingsFragmentTest::class,
)
class InstrumentedTestSuite
