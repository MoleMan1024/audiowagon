/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.support.v4.media.MediaBrowserCompat
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.lifecycle.Lifecycle
import de.moleman1024.audiowagon.activities.SettingsFragment
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.util.ServiceFixture
import de.moleman1024.audiowagon.util.TestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.concurrent.thread

private const val TAG = "SettingsFragmentTest"

@ExperimentalCoroutinesApi
class SettingsFragmentTest {
    private lateinit var serviceFixture: ServiceFixture
    private lateinit var browser: MediaBrowserCompat
    private lateinit var audioBrowserService: AudioBrowserService

    @Before
    fun setUp() {
        Logger.debug(TAG, "setUp()")
        serviceFixture = ServiceFixture()
        browser = serviceFixture.createMediaBrowser()
        audioBrowserService = serviceFixture.getAudioBrowserService()
    }

    @After
    fun tearDown() {
        Logger.debug(TAG, "tearDown()")
        serviceFixture.shutdown()
    }

    /**
     * Regression test for https://github.com/MoleMan1024/audiowagon/issues/41
     */
    @Test
    fun onCreate_noDatabaseDir_doesNotCrash() {
        TestUtils.deleteDatabaseDirectory()
        // FIXME: intent
        val scenario = launchFragmentInContainer<SettingsFragment>()
        thread(start=true) {
            scenario.moveToState(Lifecycle.State.DESTROYED)
        }
        scenario.close()
    }

}
