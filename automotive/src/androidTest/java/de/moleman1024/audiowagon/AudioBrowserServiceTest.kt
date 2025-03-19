/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.util.ServiceFixture
import de.moleman1024.audiowagon.util.TestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

private const val TAG = "AudioBrowserServiceTest"

@ExperimentalCoroutinesApi
class AudioBrowserServiceTest {
    private lateinit var serviceFixture: ServiceFixture

    @Before
    fun setUp() {
        Logger.debug(TAG, "setUp()")
        serviceFixture = ServiceFixture()
    }

    @After
    fun tearDown() {
        Logger.debug(TAG, "tearDown()")
        serviceFixture.shutdown()
    }

    // What to test:
    // https://stuff.mit.edu/afs/sipb/project/android/docs/tools/testing/service_testing.html
    @Test
    fun onBind_default_bindsService() {
        serviceFixture.bindToServiceAndWait()
        assertTrue(serviceFixture.isBound)
    }

    @Test
    fun onCreate_default_createsService() {
        serviceFixture.createMediaBrowser()
        val audioBrowserService = serviceFixture.getAudioBrowserService()
        assertEquals(Lifecycle.State.CREATED, audioBrowserService.lifecycle.currentState)
    }

    @Test
    fun onStartCommand_default_startsService() {
        serviceFixture.createMediaBrowser()
        val audioBrowserService = serviceFixture.getAudioBrowserService()
        serviceFixture.startService()
        TestUtils.waitForTrueOrFail({ audioBrowserService.lifecycle.currentState == Lifecycle.State.STARTED }, 200,
            "lifecycle started")
    }

    @Test
    fun onStartCommand_serviceAlreadyStarted_callsOnStartCommandAgain() {
        serviceFixture.createMediaBrowser()
        val audioBrowserService = serviceFixture.getAudioBrowserService()
        serviceFixture.startService()
        TestUtils.waitForTrueOrFail({ audioBrowserService.lifecycle.currentState == Lifecycle.State.STARTED }, 200,
            "lifecycle started")
        serviceFixture.startService()
        // TODO: improve
        Thread.sleep(2000)
        val logLines = audioBrowserService.logger.getStoredLogs()
        assertEquals(2, logLines.count { it.contains("onStartCommand") })
    }

    @Test
    fun onDestroy_default_destroysService() {
        serviceFixture.createMediaBrowser()
        val audioBrowserService = serviceFixture.getAudioBrowserService()
        var isDestroyed = false
        getInstrumentation().runOnMainSync {
            audioBrowserService.lifecycle.addObserver(object : LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    if (event == Lifecycle.Event.ON_DESTROY) {
                        isDestroyed = true
                    }
                }
            })
        }
        serviceFixture.mediaBrowser?.disconnect()
        serviceFixture.unbindService()
        serviceFixture.stopService()
        TestUtils.waitForTrueOrFail({ isDestroyed }, 1000, "isDestroyed")
    }

}
