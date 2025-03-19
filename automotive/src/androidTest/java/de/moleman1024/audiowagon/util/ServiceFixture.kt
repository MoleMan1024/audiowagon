/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media.MediaBrowserServiceCompat
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import de.moleman1024.audiowagon.AudioBrowserService
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "ServiceFixture"

@ExperimentalCoroutinesApi
class ServiceFixture {
    var isBound = false
    private val targetContext = getInstrumentation().targetContext
    // We connect to the AudioBrowserService twice. Once as a MediaBrowserService client for media related
    // functionality, same as how Android framework does it, and once using a regular local binder to access other
    // methods
    private val mediaServiceIntent = Intent(
        MediaBrowserServiceCompat.SERVICE_INTERFACE, Uri.EMPTY, targetContext, AudioBrowserService::class.java
    )
    private val audioBrowserServiceIntent = Intent(
        "de.moleman1024.audiowagon.ACTION_BIND_FROM_TEST_FIXTURE",
        Uri.EMPTY,
        targetContext,
        AudioBrowserService::class.java
    )
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Logger.debug(TAG, "onServiceConnected(name=$name, binder=$binder)")
            audioBrowserService = (binder as AudioBrowserService.LocalBinder).getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Logger.debug(TAG, "onServiceDisconnected(name=$name)")
        }
    }
    var mediaBrowser: MediaBrowserCompat? = null
    private var audioBrowserService: AudioBrowserService? = null
    var mediaController: MediaControllerCompat? = null
    val transportControls get() = mediaController?.transportControls
    val playbackQueue: MutableList<MediaSessionCompat.QueueItem>? get() = mediaController?.queue
    val metadata get() = mediaController?.metadata
    private val mediaBrowserConnCB: MediaConnCB = MediaConnCB()
    var isDestroyed = false
    val onDestroyObserver = object : LifecycleEventObserver {
        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            if (event == Lifecycle.Event.ON_DESTROY) {
                isDestroyed = true
            }
        }
    }

    inner class MediaConnCB : MediaBrowserCompat.ConnectionCallback() {
        val connectedLatch = CountDownLatch(1)

        override fun onConnected() {
            Logger.debug(TAG, "onConnected()")
            connectedLatch.countDown()
        }

        override fun onConnectionSuspended() {
            Logger.debug(TAG, "onConnectionSuspended()")
        }

        override fun onConnectionFailed() {
            Logger.debug(TAG, "onConnectionFailed()")
        }
    }

    init {
        Logger.debug(TAG, "init")
    }

    fun bindToServiceAndWait() {
        Logger.debug(TAG, "Binding service with connection: $connection")
        isBound = targetContext.bindService(audioBrowserServiceIntent, connection, Context.BIND_AUTO_CREATE)
        Logger.debug(TAG, "Waiting for service connection")
        TestUtils.waitForTrueOrFail({ audioBrowserService != null }, 5000, "audioBrowserService != null")
    }

    fun startService() {
        Logger.debug(TAG, "Starting service")
        targetContext.startService(mediaServiceIntent)
    }

    fun createMediaBrowser(): MediaBrowserCompat {
        Logger.debug(TAG, "createMediaBrowser()")
        getInstrumentation().runOnMainSync {
            mediaBrowser = MediaBrowserCompat(
                targetContext,
                ComponentName(targetContext, AudioBrowserService::class.java),
                mediaBrowserConnCB,
                null
            )
        }
        mediaBrowser?.connect()
        Logger.debug(TAG, "Waiting for media browser connection")
        mediaBrowserConnCB.connectedLatch.await(5000, TimeUnit.MILLISECONDS)
        mediaBrowser?.isConnected?.let {
            if (!it) {
                throw AssertionError("Media browser did not connect in time")
            }
        }
        createAudioBrowserService()
        mediaController = MediaControllerCompat(targetContext, mediaBrowser!!.sessionToken)
        return mediaBrowser!!
    }

    private fun createAudioBrowserService() {
        bindToServiceAndWait()
        TestUtils.waitForTrueOrFail({ audioBrowserService != null }, 2000, "audioBrowserService != null")
        audioBrowserService?.logger?.setStoreLogs(true)
        // We need to reject bind requests from apps outside our control. If this is not done, our service can not
        // shut down due to still bound clients
        audioBrowserService?.addClientPackageToReject("com.android.car.media")
    }

    fun getAudioBrowserService(): AudioBrowserService {
        if (mediaBrowser == null) {
            // for some reason we need a MediaBrowser before this will work
            throw AssertionError("Please call createMediaBrowser() first before retrieving AudioBrowserService")
        }
        return audioBrowserService!!
    }

    fun unbindService() {
        if (!isBound) {
            Logger.warning(TAG, "Service is not bound")
            return
        }
        try {
            Logger.debug(TAG, "unbindService(${connection})")
            targetContext.unbindService(connection)
        } catch (exc: IllegalArgumentException) {
            Logger.exception(TAG, "Already unbound", exc)
        }
    }

    /**
     * Watch out to not call this when there service still needs to switch to foreground, it will lead to exceptions
     * 5 seconds later: "Context.startForegroundService() did not then call Service.startForeground()"
     */
    fun stopService() {
        targetContext.stopService(mediaServiceIntent)
    }

    fun shutdown() {
        Logger.debug(TAG, "Shutting down service fixture")
        if (mediaController != null) {
            Logger.debug(TAG, "transportControls.stop()")
            transportControls?.stop()
            mediaController = null
        }
        if (mediaBrowser != null) {
            Logger.debug(TAG, "Disconnect media browser")
            if (mediaBrowser?.isConnected == true) {
                mediaBrowser?.unsubscribe(mediaBrowser!!.root)
                mediaBrowser?.disconnect()
            }
            mediaBrowser = null
        }
        if (audioBrowserService != null) {
            audioBrowserService?.logger?.setStoreLogs(false)
            isDestroyed = audioBrowserService?.lifecycle?.currentState == Lifecycle.State.DESTROYED
            if (!isDestroyed) {
                getInstrumentation().runOnMainSync {
                    audioBrowserService?.lifecycle?.addObserver(onDestroyObserver)
                }
            }
        }
        Logger.debug(TAG, "Unbind from service")
        stopService()
        unbindService()
        // TODO: need to ensure in each test that service is destroyed
        // FIXME: this fails sometimes, unclear why
        if (audioBrowserService != null) {
            Logger.debug(TAG, "Shutting down and destroying AudioBrowserService")
            audioBrowserService?.shutdown()
            Logger.debug(TAG, "Waiting for service to be destroyed")
            TestUtils.waitForTrueOrFail(
                { isDestroyed },
                4000, "isDestroyed"
            )
            getInstrumentation().runOnMainSync {
                audioBrowserService?.lifecycle?.removeObserver(onDestroyObserver)
            }
            audioBrowserService = null
        }
        Logger.debug(TAG, "Service fixture was shut down")
    }
}
