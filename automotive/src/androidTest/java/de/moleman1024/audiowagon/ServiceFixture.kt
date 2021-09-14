/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.support.v4.media.MediaBrowserCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.test.platform.app.InstrumentationRegistry
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi

private const val TAG = "ServiceFixture"

@ExperimentalCoroutinesApi
class ServiceFixture {
    var isBound = false
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val intent = Intent(
        MediaBrowserServiceCompat.SERVICE_INTERFACE, Uri.EMPTY, targetContext, AudioBrowserService::class.java
    )
    private val connection = object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Logger.debug(TAG, "onServiceConnected(name=$name, $service=$service)")
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Logger.debug(TAG, "onServiceDisconnected(name=$name)")
            isBound = false
        }
    }
    lateinit var mediaBrowser: MediaBrowserCompat
    lateinit var audioBrowserService: AudioBrowserService

    fun bind() {
        Logger.debug(TAG, "Binding service")
        targetContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        TestUtils.waitForTrueOrFail({ isBound }, 1000)
    }

    fun startService() {
        Logger.debug(TAG, "Starting service")
        targetContext.startService(intent)
    }

    private fun createMediaBrowser(): MediaBrowserCompat {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            mediaBrowser = MediaBrowserCompat(
                targetContext,
                ComponentName(targetContext, AudioBrowserService::class.java),
                object : MediaBrowserCompat.ConnectionCallback() {
                    override fun onConnected() {
                        Logger.debug(TAG, "onConnected()")
                        audioBrowserService = AudioBrowserService.getInstance()
                    }

                    override fun onConnectionSuspended() {
                        Logger.debug(TAG, "onConnectionSuspended()")
                    }

                    override fun onConnectionFailed() {
                        Logger.debug(TAG, "onConnectionFailed()")
                    }
                },
                null
            )
        }
        return mediaBrowser
    }

    private fun waitForAudioBrowserService(): AudioBrowserService {
        TestUtils.waitForTrueOrFail({ this::audioBrowserService.isInitialized }, 1000)
        audioBrowserService.logger.setStoreLogs(true)
        return audioBrowserService
    }

    fun createAudioBrowserService(): AudioBrowserService {
        val mediaBrowser = createMediaBrowser()
        mediaBrowser.connect()
        return waitForAudioBrowserService()
    }

    fun unbindService() {
        if (!isBound) {
            return
        }
        try {
            targetContext.unbindService(connection)
        } catch (exc: IllegalArgumentException) {
            Logger.exception(TAG, "Already unbound", exc)
        }
    }

    fun stopService() {
        targetContext.stopService(intent)
    }

    fun shutdown() {
        Logger.debug(TAG, "Shutting down service fixture")
        if (this::audioBrowserService.isInitialized) {
            audioBrowserService.logger.setStoreLogs(false)
        }
        if (this::mediaBrowser.isInitialized) {
            mediaBrowser.disconnect()
        }
        unbindService()
        stopService()
        Logger.debug(TAG, "Shut down service fixture")
    }
}
