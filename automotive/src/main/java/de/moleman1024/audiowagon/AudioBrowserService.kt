/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later


AudioWagon - Android Automotive OS USB media player
Copyright (C) 2021 by MoleMan1024 <moleman1024dev@gmail.com>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.


Any product names, brands, and other trademarks (e.g. Polestar™, 
Google™, Android™) referred to are the property of their respective 
trademark holders. AudioWagon is not affiliated with, endorsed by, 
or sponsored by any trademark holders mentioned in the source code.
*/

package de.moleman1024.audiowagon

import android.app.ForegroundServiceStartNotAllowedException
import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.session.PlaybackStateCompat
import android.view.Display
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import de.moleman1024.audiowagon.activities.LegalDisclaimerActivity
import de.moleman1024.audiowagon.authorization.PackageValidation
import de.moleman1024.audiowagon.authorization.USBDevicePermissions
import de.moleman1024.audiowagon.broadcast.ACTION_USB_ATTACHED
import de.moleman1024.audiowagon.broadcast.BroadcastReceiverManager
import de.moleman1024.audiowagon.broadcast.MediaBroadcastReceiver
import de.moleman1024.audiowagon.broadcast.SystemBroadcastReceiver
import de.moleman1024.audiowagon.broadcast.USBExternalBroadcastReceiver
import de.moleman1024.audiowagon.broadcast.USBInternalBroadcastReceiver
import de.moleman1024.audiowagon.enums.AlbumStyleSetting
import de.moleman1024.audiowagon.enums.AudioPlayerState
import de.moleman1024.audiowagon.enums.ContentHierarchyType
import de.moleman1024.audiowagon.enums.CustomAction
import de.moleman1024.audiowagon.enums.IndexingStatus
import de.moleman1024.audiowagon.enums.LifecycleEvent
import de.moleman1024.audiowagon.enums.MetadataReadSetting
import de.moleman1024.audiowagon.enums.ServicePriority
import de.moleman1024.audiowagon.enums.ServiceStartStopReason
import de.moleman1024.audiowagon.enums.SettingKey
import de.moleman1024.audiowagon.enums.SingletonCoroutineBehaviour
import de.moleman1024.audiowagon.enums.StorageAction
import de.moleman1024.audiowagon.enums.ViewTabSetting
import de.moleman1024.audiowagon.exceptions.CannotRecoverUSBException
import de.moleman1024.audiowagon.exceptions.MissingNotifChannelException
import de.moleman1024.audiowagon.exceptions.NoChangesCancellationException
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import de.moleman1024.audiowagon.filestorage.AudioFileStorageLocation
import de.moleman1024.audiowagon.filestorage.MediaDevice
import de.moleman1024.audiowagon.log.CrashReporting
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary
import de.moleman1024.audiowagon.medialibrary.CONTENT_STYLE_SUPPORTED
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyElement
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyID
import de.moleman1024.audiowagon.persistence.PersistentPlaybackState
import de.moleman1024.audiowagon.persistence.PersistentStorage
import de.moleman1024.audiowagon.player.AudioSession
import de.moleman1024.audiowagon.player.data.AudioPlayerEvent
import de.moleman1024.audiowagon.player.data.AudioPlayerStatus
import de.moleman1024.audiowagon.player.data.CustomActionEvent
import de.moleman1024.audiowagon.repository.AudioItemRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.collections.mutableSetOf
import kotlin.coroutines.coroutineContext
import kotlin.system.exitProcess


private const val TAG = "AudioBrowserService"

@ExperimentalCoroutinesApi
/**
 * This is the main entry point of the app.
 *
 * We implement a [MediaBrowserServiceCompat] that AAOS MediaBrowser GUI client can connect to and retrieve media
 * items from.
 *
 * See
 * https://developer.android.com/training/cars/media
 * https://developer.android.com/training/cars/media/automotive-os
 * https://developers.google.com/cars/design/automotive-os/apps/media/interaction-model/playing-media
 *
 */
// We need to use MediaBrowserServiceCompat instead of MediaBrowserService because the latter does not support
// searching
// TODO: class getting too large
class AudioBrowserService : MediaBrowserServiceCompat(), LifecycleOwner {
    companion object {
        @Volatile
        var servicePriority: ServicePriority = ServicePriority.BACKGROUND
        val origDefaultUncaughtExceptionhandler: Thread.UncaughtExceptionHandler? =
            Thread.getDefaultUncaughtExceptionHandler()
    }

    private var lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
    private lateinit var audioItemLibrary: AudioItemLibrary
    private lateinit var audioFileStorage: AudioFileStorage
    private lateinit var usbDevicePermissions: USBDevicePermissions
    private lateinit var packageValidation: PackageValidation
    private lateinit var audioSession: AudioSession
    private lateinit var gui: GUI
    private lateinit var persistentStorage: PersistentStorage
    private var broadcastReceiverManager: BroadcastReceiverManager? = null
    private lateinit var crashReporting: CrashReporting
    private lateinit var sharedPrefs: SharedPrefs
    private var dispatcher: CoroutineDispatcher = Dispatchers.IO
    private var loadChildrenJobs: ConcurrentHashMap<String, Job> = ConcurrentHashMap<String, Job>()
    private lateinit var storePlaybackStateSingletonCoroutine: SingletonCoroutine
    private lateinit var cleanPersistentSingletonCoroutine: SingletonCoroutine
    private lateinit var restoreFromPersistentSingletonCoroutine: SingletonCoroutine
    private lateinit var libraryCreationSingletonCoroutine: SingletonCoroutine
    private lateinit var audioSessionCloseSingletonCoroutine: SingletonCoroutine
    private lateinit var suspendSingletonCoroutine: SingletonCoroutine
    private lateinit var updateDevicesSingletonCoroutine: SingletonCoroutine
    private lateinit var notifyIdleSingletonCoroutine: SingletonCoroutine
    private lateinit var cleanSingletonCoroutine: SingletonCoroutine
    private lateinit var requestPermissionSingletonCoroutine: SingletonCoroutine
    private val isServiceStarted = AtomicBoolean(false)
    private val isShuttingDown = AtomicBoolean(false)
    private val isSuspended = AtomicBoolean(false)
    private val lastServiceStartReason: AtomicReference<ServiceStartStopReason> =
        AtomicReference<ServiceStartStopReason>(ServiceStartStopReason.UNKNOWN)
    private var deferredUntilServiceInForeground: CompletableDeferred<Unit> = CompletableDeferred()
    private var audioSessionNotification: Notification? = null
    private var latestContentHierarchyIDRequested: String = ""
    private var lastAudioPlayerState: AudioPlayerState = AudioPlayerState.IDLE
    private var metadataReadNowRequested: Boolean = false
    private val contentHierarchyFilesRoot =
        ContentHierarchyElement.serialize(ContentHierarchyID(ContentHierarchyType.ROOT_FILES))
    private val contentHierarchyTracksRoot =
        ContentHierarchyElement.serialize(ContentHierarchyID(ContentHierarchyType.ROOT_TRACKS))
    private val contentHierarchyAlbumsRoot =
        ContentHierarchyElement.serialize(ContentHierarchyID(ContentHierarchyType.ROOT_ALBUMS))
    private val contentHierarchyArtistsRoot =
        ContentHierarchyElement.serialize(ContentHierarchyID(ContentHierarchyType.ROOT_ARTISTS))
    private val contentHierarchyRoot =
        ContentHierarchyElement.serialize(ContentHierarchyID(ContentHierarchyType.ROOT))
    // needs to be public for accessing logging from testcases
    val logger = Logger
    private val localBinder = LocalBinder()
    private val lifecycleObserversMap = mutableMapOf<String, (event: LifecycleEvent) -> Unit>()
    private val isUSBNotRecoverable = AtomicBoolean()
    private var usbExternalBroadcastReceiver: USBExternalBroadcastReceiver? = null
    private var usbInternalBroadcastReceiver: USBInternalBroadcastReceiver? = null
    // list of clients to reject during instrumented tests
    private val clientPackagesToReject = mutableListOf<String>()
    private val binderClients = mutableSetOf<Int>()
    private val systemBroadcastReceiver = SystemBroadcastReceiver()

    init {
        isShuttingDown.set(false)
        lifecycleRegistry.currentState = Lifecycle.State.INITIALIZED
        logger.init(lifecycleScope)
        logger.info(TAG, "init (instance=${this})")
        setUncaughtExceptionHandler()
    }

    override fun onCreate() {
        logger.debug(TAG, "onCreate() at: ${Util.getLocalDateTimeStringNow()}")
        logger.logVersion(applicationContext)
        isUSBNotRecoverable.set(false)
        isShuttingDown.set(false)
        super.onCreate()
        sharedPrefs = SharedPrefs()
        crashReporting = CrashReporting(applicationContext, lifecycleScope, dispatcher, sharedPrefs)
        storePlaybackStateSingletonCoroutine =
            SingletonCoroutine("StorePlaybState", dispatcher, lifecycleScope.coroutineContext, crashReporting)
        cleanPersistentSingletonCoroutine =
            SingletonCoroutine("CleanPers", dispatcher, lifecycleScope.coroutineContext, crashReporting)
        restoreFromPersistentSingletonCoroutine =
            SingletonCoroutine("RestoreFromPers", dispatcher, lifecycleScope.coroutineContext, crashReporting)
        libraryCreationSingletonCoroutine =
            SingletonCoroutine("LibraryCreation", dispatcher, lifecycleScope.coroutineContext, crashReporting)
        audioSessionCloseSingletonCoroutine =
            SingletonCoroutine("AudioSessClose", dispatcher, lifecycleScope.coroutineContext, crashReporting)
        suspendSingletonCoroutine =
            SingletonCoroutine("Suspend", dispatcher, lifecycleScope.coroutineContext, crashReporting)
        suspendSingletonCoroutine.behaviour = SingletonCoroutineBehaviour.PREFER_FINISH
        updateDevicesSingletonCoroutine =
            SingletonCoroutine("UpdateDevices", dispatcher, lifecycleScope.coroutineContext, crashReporting)
        notifyIdleSingletonCoroutine =
            SingletonCoroutine("NotifyIdle", dispatcher, lifecycleScope.coroutineContext, crashReporting)
        requestPermissionSingletonCoroutine =
            SingletonCoroutine("RequestPermission", dispatcher, lifecycleScope.coroutineContext, crashReporting)
        requestPermissionSingletonCoroutine.behaviour = SingletonCoroutineBehaviour.PREFER_FINISH
        cleanSingletonCoroutine =
            SingletonCoroutine("Clean", dispatcher, lifecycleScope.coroutineContext, crashReporting)
        cleanSingletonCoroutine.behaviour = SingletonCoroutineBehaviour.PREFER_FINISH
        if (broadcastReceiverManager != null) {
            logger.error(TAG, "broadcastReceiverManager is not null in onCreate()")
            broadcastReceiverManager?.shutdown()
        }
        broadcastReceiverManager = BroadcastReceiverManager(applicationContext)
        systemBroadcastReceiver.audioBrowserService = this
        broadcastReceiverManager?.register(systemBroadcastReceiver)
        startup()
        if (!isScreenOn()) {
            logger.debug(TAG, "onCreate() called while screen was off, suspending again")
            suspend()
        }
    }

    @ExperimentalCoroutinesApi
    fun startup() {
        logger.debug(TAG, "startup()")
        persistentStorage = PersistentStorage(this, dispatcher)
        gui = GUI(lifecycleScope, applicationContext, crashReporting)
        usbDevicePermissions = USBDevicePermissions(this)
        audioFileStorage =
            AudioFileStorage(this, lifecycleScope, dispatcher, usbDevicePermissions, sharedPrefs, crashReporting)
        audioItemLibrary = AudioItemLibrary(this, audioFileStorage, lifecycleScope, dispatcher, gui, sharedPrefs)
        audioItemLibrary.libraryExceptionObservers.clear()
        audioItemLibrary.libraryExceptionObservers.add { exc ->
            when (exc) {
                is CannotRecoverUSBException -> {
                    if (isUSBNotRecoverable.compareAndSet(false, true)) {
                        logger.error(TAG, "CannotRecoverUSBException thrown in startup()")
                        cancelMostJobs()
                        notifyLibraryCreationFailure()
                        crashReporting.logLastMessagesAndRecordException(exc)
                    }
                }
            }
        }
        if (sessionToken == null) {
            audioSession = AudioSession(
                this, audioItemLibrary, audioFileStorage, lifecycleScope, dispatcher, persistentStorage,
                crashReporting, sharedPrefs
            )
            sessionToken = audioSession.sessionToken
            logger.debug(TAG, "New media session token: $sessionToken")
            audioSessionNotification = audioSession.getNotification()
            val mediaBroadcastReceiver = MediaBroadcastReceiver()
            mediaBroadcastReceiver.audioPlayer = audioSession.audioPlayer
            broadcastReceiverManager?.register(mediaBroadcastReceiver)
            audioSession.setLastWakeupTimeMillis(Util.getMillisNow())
        }
        // We should try to move the service to foreground here during creation already, because it is not
        // guaranteed that we can reach onStartCommand() in time to e.g. handle the startForegroundService() from
        // MediaButtonReceiver. If startForegroundService() has not been called previously, startForeground() should
        // do nothing(?). However we must check for exception ForegroundServiceStartNotAllowedException because this
        // could be called during boot phase also
        logger.debug(TAG, "Moving service to foreground during startup for safety")
        moveServiceToForegroundSafely()
        packageValidation = PackageValidation(this, R.xml.allowed_media_browser_callers)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        logger.verbose(TAG, "Lifecycle set to CREATED")
        observeAudioFileStorage()
        observeAudioSessionStateChanges()
        if (isScreenOn() && !isScreenLocked()) {
            updateDevicesAfterUnlock()
        }
        logger.debug(TAG, "startup() ended")
    }

    private fun isScreenLocked(): Boolean {
        return try {
            val keyguardManager = this.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val isKeyGuardLocked = keyguardManager.isKeyguardLocked
            logger.debug(TAG, "isKeyGuardLocked: $isKeyGuardLocked")
            isKeyGuardLocked
        } catch (exc: RuntimeException) {
            logger.exception(TAG, exc.message.toString(), exc)
            false
        }
    }

    private fun isScreenOn(): Boolean {
        return try {
            val powerManager = this.getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isInteractive) {
                logger.debug(TAG, "System is not interactive")
                return false
            }
            val displayManager = this.getSystemService(DISPLAY_SERVICE) as DisplayManager
            displayManager.displays.any {
                val anyScreenIsOn = it.state == Display.STATE_ON
                logger.debug(TAG, "Display ${it.displayId} has state: ${it.state}")
                anyScreenIsOn
            }
        } catch (exc: RuntimeException) {
            logger.exception(TAG, exc.message.toString(), exc)
            true
        }
    }

    private fun updateDevicesAfterUnlock() {
        logger.debug(TAG, "updateDevicesAfterUnlock()")
        updateDevicesSingletonCoroutine.launch {
            // We need to avoid overlapping attached device updates, because requesting e.g. permission twice for the
            // same device (when popup not yet visible) will cancel showing of the permission popup
            if (audioFileStorage.isUpdatingDevices()) {
                logger.debug(TAG, "Cancelling updateDevicesAfterUnlock(), USB devices already updating")
                return@launch
            }
            suspendSingletonCoroutine.join()
            logger.debug(
                Util.TAGCRT(TAG, coroutineContext),
                "Delaying to update attached devices until ${
                    Util.getLocalDateTimeNowInstant().plusMillis(UPDATE_ATTACHED_DEVICES_AFTER_UNLOCK_DELAY_MS)
                }"
            )
            delay(UPDATE_ATTACHED_DEVICES_AFTER_UNLOCK_DELAY_MS)
            updateAttachedDevices()
        }
    }

    private fun observeAudioFileStorage() {
        audioFileStorage.storageObservers.add { storageChange ->
            logger.verbose(TAG, "Received storageChange: $storageChange")
            val allStorageIDs = audioItemLibrary.getAllStorageIDs()
            logger.debug(TAG, "Storage IDs in library before change: $allStorageIDs")
            if (storageChange.error.isNotBlank()) {
                logger.warning(TAG, "Audio file storage notified an error")
                if (!isSuspended.get()) {
                    runBlocking(lifecycleScope.coroutineContext + dispatcher) {
                        audioSession.showError(getString(R.string.error_USB, storageChange.error))
                    }
                }
                audioSession.stopPlayerBlocking()
                allStorageIDs.forEach { onStorageLocationRemoved(it) }
                onStorageLocationRefresh()
                return@add
            }
            when (storageChange.action) {
                StorageAction.ADD -> onStorageLocationAdded(storageChange.id)
                StorageAction.REMOVE -> onStorageLocationRemoved(storageChange.id)
                StorageAction.REFRESH -> onStorageLocationRefresh()
            }
            val allStorageIDsAfter = audioItemLibrary.getAllStorageIDs()
            logger.debug(TAG, "Storage IDs in library after change: $allStorageIDsAfter")
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun updateAttachedDevices() {
        logger.debug(TAG, "updateAttachedDevices()")
        try {
            audioFileStorage.updateAttachedDevices()
        } catch (exc: IOException) {
            logger.exception(TAG, "I/O Error during update of connected USB devices", exc)
            runBlocking(lifecycleScope.coroutineContext + dispatcher) {
                audioSession.showError(this@AudioBrowserService.getString(R.string.error_USB_init))
            }
            crashReporting.logLastMessagesAndRecordException(exc)
        } catch (exc: RuntimeException) {
            logger.exception(TAG, "Runtime error during update of connected USB devices", exc)
            runBlocking(lifecycleScope.coroutineContext + dispatcher) {
                audioSession.showError(this@AudioBrowserService.getString(R.string.error_USB_init))
            }
            crashReporting.logLastMessagesAndRecordException(exc)
        }
    }

    private fun observeAudioSessionStateChanges() {
        // TODO: clean up the notification handling
        // https://developer.android.com/guide/topics/media-apps/audio-app/building-a-mediabrowserservice#service-lifecycle
        audioSession.observe { event ->
            logger.debug(TAG, "stateChange=$event")
            when (event) {
                is AudioPlayerEvent -> {
                    handleAudioPlayerEvent(event)
                }

                is SettingChangeEvent -> {
                    handleSettingChangeEvent(event)
                }

                is CustomActionEvent -> {
                    handleCustomActionEvent(event)
                }
            }
        }
    }

    private fun handleAudioPlayerEvent(event: AudioPlayerEvent) {
        logger.verbose(TAG, "handleAudioPlayerEvent(event=$event)")
        lastAudioPlayerState = event.state
        when (event.state) {
            AudioPlayerState.STARTED -> {
                restoreFromPersistentSingletonCoroutine.cancel()
                cleanPersistentSingletonCoroutine.cancel()
                notifyIdleSingletonCoroutine.cancel()
                startServiceInForeground(ServiceStartStopReason.MEDIA_SESSION_CALLBACK)
            }
            AudioPlayerState.PAUSED -> {
                notifyIdleSingletonCoroutine.cancel()
                delayedMoveServiceToBackground()
            }
            AudioPlayerState.STOPPED -> {
                // we should call stopSelf() when we have nothing left to do so Android can decide to stop the
                // process if MediaBrowser GUI is also no longer connected to the service
                // https://developer.android.com/guide/topics/media-apps/audio-app/building-a-mediabrowserservice#service-lifecycle
                stopService(ServiceStartStopReason.MEDIA_SESSION_CALLBACK)
            }
            AudioPlayerState.PLAYBACK_COMPLETED -> {
                launchCleanPersistentJob()
            }
            AudioPlayerState.ERROR -> {
                notifyIdleSingletonCoroutine.cancel()
                if (event.errorCode == PlaybackStateCompat.ERROR_CODE_END_OF_QUEUE) {
                    // Android Automotive media browser client will show a notification to user by itself in
                    // case of skipping beyond end of queue, no need for us to send a error message
                    return
                }
                logger.warning(TAG, "Player encountered an error")
            }
            else -> {
                // ignore
            }
        }
    }

    private fun handleSettingChangeEvent(event: SettingChangeEvent) {
        when (event.key) {
            SettingKey.READ_METADATA_SETTING -> {
                audioFileStorage.cancelIndexing()
                audioItemLibrary.cancelBuildLibrary()
                cancelLibraryCreation()
                when (event.value) {
                    MetadataReadSetting.WHEN_USB_CONNECTED.name,
                    MetadataReadSetting.FILEPATHS_ONLY.name -> {
                        updateDevicesSingletonCoroutine.launch {
                            updateAttachedDevices()
                        }
                    }
                }
            }
            SettingKey.READ_METADATA_NOW -> {
                metadataReadNowRequested = true
                audioFileStorage.cancelIndexing()
                audioItemLibrary.cancelBuildLibrary()
                cancelLibraryCreation()
                updateDevicesSingletonCoroutine.launch {
                    updateAttachedDevices()
                }
            }
            SettingKey.ALBUM_STYLE_SETTING -> {
                when (event.value) {
                    AlbumStyleSetting.GRID.name -> {
                        audioItemLibrary.albumArtStyleSetting = AlbumStyleSetting.GRID
                    }

                    AlbumStyleSetting.LIST.name -> {
                        audioItemLibrary.albumArtStyleSetting = AlbumStyleSetting.LIST
                    }

                    else -> {
                        throw RuntimeException("Invalid album style setting: ${event.value}")
                    }
                }
            }
            SettingKey.VIEW_TABS_SETTING -> {
                @Suppress("UNCHECKED_CAST")
                val viewTabs = event.value as? List<ViewTabSetting>
                    ?: throw RuntimeException("Invalid view tabs setting: ${event.value}")
                audioItemLibrary.setViewTabs(viewTabs)
                notifyBrowserChildrenChangedAllLevels()
            }
        }
    }

    private fun handleCustomActionEvent(event: CustomActionEvent) {
        when (event.action) {
            CustomAction.EJECT -> {
                cancelMostJobs()
            }
            CustomAction.MAYBE_SHOW_USB_PERMISSION_POPUP -> {
                requestPermissionSingletonCoroutine.launch {
                    try {
                        audioFileStorage.requestUSBPermissionIfMissing()
                    } catch (exc: Exception) {
                        logger.exception(TAG, exc.message.toString(), exc)
                    }
                }
            }
            CustomAction.STOP_CB_CALLED -> {
                if (isServiceStarted.get()
                    || isShuttingDown.get()
                    || isSuspended.get()
                    || servicePriority != ServicePriority.BACKGROUND
                ) {
                    logger.verbose(
                        TAG, "Ignore STOP_CB_CALLED: " +
                                "isServiceStarted=${isServiceStarted.get()}," +
                                "isShuttingDown=${isShuttingDown.get()}," +
                                "isSuspended=${isSuspended.get()}," +
                                "servicePrio=$servicePriority"
                    )
                    return
                }
                notifyIdleSingletonCoroutine.launch {
                    logger.debug(Util.TAGCRT(TAG, coroutineContext), "Stop callback was called, will notify idle state in: ${IDLE_TIMEOUT_MS}ms")
                    delay(IDLE_TIMEOUT_MS)
                    logger.debug(Util.TAGCRT(TAG, coroutineContext), "Player is stopped and service is idle")
                    // notify the AlbumArtContentProvider so it will unbind, so Android can free resources if nothing
                    // else is bound either
                    notifyLifecycleObservers(LifecycleEvent.IDLE)
                }
            }
            CustomAction.PLAY_CB_CALLED -> {
                logger.debug(TAG, "Play callback was called")
                launchInScopeSafely {
                    restoreFromPersistentSingletonCoroutine.join()
                    audioSession.handleOnPlay()
                }
            }
        }
    }

    /**
     * When playing back music we move the service to foreground ("foreground" is in terms of memory/priority, not in
     * terms of a GUI window). We also move it to foreground during indexing, so the user can switch to another app
     * and indexing will not be stopped automatically (Android may destroy background services at any time)
     */
    private fun startServiceInForeground(reason: ServiceStartStopReason) {
        if (isServiceStarted.get()) {
            if (servicePriority == ServicePriority.BACKGROUND) {
                logger.debug(TAG, "Moving already started service to foreground")
                try {
                    audioSessionNotification = audioSession.getNotification()
                    moveServiceToForegroundSafely()
                    lastServiceStartReason.set(reason)
                } catch (exc: MissingNotifChannelException) {
                    logger.exception(TAG, exc.message.toString(), exc)
                    crashReporting.logLastMessagesAndRecordException(exc)
                }
            }
            return
        }
        if (isShuttingDown.get()) {
            logger.debug(TAG, "Will not start service in foreground (reason=$reason) because shutting down")
            return
        }
        logger.debug(TAG, "startServiceInForeground(reason=$reason)")
        servicePriority = ServicePriority.FOREGROUND_REQUESTED
        if (deferredUntilServiceInForeground.isCompleted) {
            deferredUntilServiceInForeground = CompletableDeferred()
        }
        try {
            audioSessionNotification = audioSession.getNotification()
            if (lastServiceStartReason.get() <= reason) {
                lastServiceStartReason.set(reason)
            }
            startForegroundServiceSafely()
        } catch (exc: MissingNotifChannelException) {
            logger.exception(TAG, exc.message.toString(), exc)
            servicePriority = ServicePriority.FOREGROUND
            crashReporting.logLastMessagesAndRecordException(exc)
        }
    }

    private fun startForegroundServiceSafely() {
        // This intent will trigger callback onStartCommand()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                startForegroundService(Intent(this, AudioBrowserService::class.java))
            } catch (exc: ForegroundServiceStartNotAllowedException) {
                logger.exception(TAG, exc.message.toString(), exc)
            }
        } else {
            startForegroundService(Intent(this, AudioBrowserService::class.java))
        }
        logger.debug(TAG, "startForegroundService() called (${this})")
        // Move the service into foreground here already, otherwise we need to deal with
        // ForegroundServiceDidNotStartInTimeException. It is not necessary to wait for onStartCommand().
        moveServiceToForegroundSafely()
    }

    /**
     *  When the service is in (memory) background and no activity is using it (e.g. other media app is shown) AAOS
     *  will usually stop the service (and thus also destroy) it after one minute of idle time
     */
    private fun moveServiceToBackground() {
        logger.debug(TAG, "Moving service to background")
        stopForeground(STOP_FOREGROUND_REMOVE)
        servicePriority = ServicePriority.BACKGROUND
    }

    private fun onStorageLocationAdded(storageID: String) {
        logger.info(TAG, "onStorageLocationAdded(storageID=$storageID)")
        cancelMostJobs()
        isUSBNotRecoverable.set(false)
        if (!sharedPrefs.isLegalDisclaimerAgreed(this)) {
            logger.warning(TAG, "User did not agree to legal disclaimer yet")
            notifyBrowserChildrenChangedAllLevels()
            try {
                val showLegalDisclaimerIntent = Intent(this, LegalDisclaimerActivity::class.java)
                showLegalDisclaimerIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(showLegalDisclaimerIntent)
            } catch (exc: RuntimeException) {
                // Depending on situation, starting an activity might not be allowed
                // https://developer.android.com/guide/components/activities/background-starts
                logger.exception(TAG, exc.message.toString(), exc)
            }
            return
        }
        val metadataReadSetting = sharedPrefs.getMetadataReadSettingEnum(this, logger, TAG)
        if (metadataReadSetting == MetadataReadSetting.OFF) {
            logger.info(TAG, "Metadata extraction is disabled in settings")
            launchRestoreFromPersistentJob()
            notifyBrowserChildrenChangedAllLevels()
            return
        }
        createLibraryForStorage(storageID)
    }

    private fun createLibraryForStorage(storageID: String) {
        gui.showIndexingNotification()
        // We start the service in foreground while indexing the USB device, a notification is shown to the user.
        // This is done so the user can use other apps while the indexing keeps running in the service
        // See also https://developer.android.com/guide/components/foreground-services#background-start-restrictions
        startServiceInForeground(ServiceStartStopReason.INDEXING)
        launchLibraryCreationJobForStorage(storageID)
    }

    private fun launchLibraryCreationJobForStorage(storageID: String) {
        // TODO: messy: this exception handler is not used because CannotRecoverUSBException will be triggered before
        //  it comes into play which will cancel coroutines
        libraryCreationSingletonCoroutine.exceptionHandler = CoroutineExceptionHandler { _, exc ->
            notifyLibraryCreationFailure()
            crashReporting.logLastMessagesAndRecordException(exc)
            when (exc) {
                is IOException -> {
                    logger.exception(TAG, "I/O exception while building library", exc)
                }

                else -> {
                    logger.exception(TAG, "Exception while building library", exc)
                }
            }
            audioItemLibrary.cancelBuildLibrary()
        }
        libraryCreationSingletonCoroutine.launch {
            var cancellationException: CancellationException? = null
            try {
                createLibraryFromStorage(listOf(storageID))
            } catch (exc: CancellationException) {
                cancellationException = exc
            }
            audioFileStorage.setIndexingStatus(storageID, IndexingStatus.COMPLETED)
            audioFileStorage.cleanAlbumArtCache()
            logger.info(TAG, "Audio library has been built for storages: $storageID")
            notifyBrowserChildrenChangedAllLevels()
            gui.showIndexingFinishedNotification()
            when (lastAudioPlayerState) {
                AudioPlayerState.STARTED -> {
                    // do not change service status when indexing finishes while playback is ongoing
                }

                AudioPlayerState.PAUSED -> {
                    delayedMoveServiceToBackground()
                }

                else -> {
                    // player is currently in state STOPPED or ERROR
                    stopService(ServiceStartStopReason.INDEXING)
                }
            }
            if (cancellationException != null) {
                logger.debug(TAG, "libraryCreationJob ended early")
                throw cancellationException
            }
            if (isShuttingDown.get()) {
                logger.debug(TAG, "Shutting down, will not restore persistent playback state")
                return@launch
            }
            if (isAudioPlayerDormant()) {
                launchRestoreFromPersistentJob()
            } else {
                logger.debug(TAG, "Not restoring from persistent state, user has already requested new item")
            }
        }
    }

    private suspend fun createLibraryFromStorage(storageIDs: List<String>) {
        // TODO: remove multiple storage IDs
        logger.debug(Util.TAGCRT(TAG, coroutineContext), "createLibraryFromStorages(storageIDs=${storageIDs})")
        val storageLocations: List<AudioFileStorageLocation> = storageIDs.map {
            audioFileStorage.getStorageLocationForID(it)
        }
        storageLocations.forEach { audioItemLibrary.initRepository(it.storageID) }
        val metadataReadSetting = sharedPrefs.getMetadataReadSettingEnum(this, logger, TAG)
        if (metadataReadSetting == MetadataReadSetting.WHEN_USB_CONNECTED
            || metadataReadSetting == MetadataReadSetting.FILEPATHS_ONLY
            || (metadataReadSetting == MetadataReadSetting.MANUALLY && metadataReadNowRequested)
        ) {
            metadataReadNowRequested = false
            val fileProducerChannel = audioFileStorage.indexStorageLocations(storageIDs)
            try {
                audioItemLibrary.buildLibrary(fileProducerChannel) { notifyBrowserChildrenChangedAllLevels() }
            } catch (exc: CancellationException) {
                if (exc is NoChangesCancellationException) {
                    logger.warning(TAG, "Coroutine inside createLibraryFromStorages() cancelled because no changes")
                } else {
                    logger.warning(TAG, "Coroutine inside createLibraryFromStorages() was cancelled")
                    throw exc
                }
            }
        }
    }

    private fun launchRestoreFromPersistentJob() {
        restoreFromPersistentSingletonCoroutine.launch {
            val persistentPlaybackState = persistentStorage.retrieve()
            try {
                restoreFromPersistent(persistentPlaybackState)
            } catch (exc: RuntimeException) {
                logger.exception(TAG, "Restoring from persistent failed", exc)
            }
        }
    }

    private suspend fun restoreFromPersistent(state: PersistentPlaybackState) {
        if (state.trackID.isBlank()) {
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "No recent content hierarchy ID to restore from")
            return
        }
        val contentHierarchyID = ContentHierarchyElement.deserialize(state.trackID)
        when (contentHierarchyID.type) {
            ContentHierarchyType.TRACK -> {
                if (audioItemLibrary.getRepoForContentHierarchyID(contentHierarchyID) == null) {
                    logger.warning(Util.TAGCRT(TAG, coroutineContext), "Cannot restore recent track, storage repository mismatch")
                    return
                }
            }

            ContentHierarchyType.FILE -> {
                if (audioFileStorage.getPrimaryStorageLocation().storageID != contentHierarchyID.storageID) {
                    logger.warning(Util.TAGCRT(TAG, coroutineContext), "Cannot restore recent file, storage id mismatch")
                    return
                }
            }

            else -> {
                logger.error(Util.TAGCRT(TAG, coroutineContext), "Not supported to restore from type: $contentHierarchyID")
                return
            }
        }
        if (state.queueIDs.isEmpty()) {
            logger.warning(Util.TAGCRT(TAG, coroutineContext), "Found persistent recent track, but no queue items")
            return
        }
        audioSession.prepareFromPersistent(state)
    }

    private fun launchCleanPersistentJob() {
        cleanPersistentSingletonCoroutine.launch {
            try {
                audioSession.cleanPersistent()
            } catch (exc: RuntimeException) {
                logger.exception(TAG, "Cleaning persistent data failed", exc)
            }
        }
    }

    private fun onStorageLocationRemoved(storageID: String) {
        logger.info(TAG, "onStorageLocationRemoved(storageID=$storageID)")
        if (isShuttingDown.get()) {
            logger.debug(TAG, "Shutdown is in progress")
            return
        }
        cancelMostJobs()
        audioSessionCloseSingletonCoroutine.launch {
            audioSession.stopPlayer()
            audioSession.storePlaybackState()
            audioSession.reset()
        }
        if (storageID.isNotBlank()) {
            try {
                val storageLocation = audioFileStorage.getStorageLocationForID(storageID)
                runBlocking(lifecycleScope.coroutineContext + dispatcher) {
                    audioItemLibrary.removeRepository(storageLocation.storageID)
                }
            } catch (exc: IllegalArgumentException) {
                logger.warning(TAG, exc.toString())
            }
        }
        notifyBrowserChildrenChangedAllLevels()
        delayedMoveServiceToBackground()
    }

    private fun notifyBrowserChildrenChangedAllLevels() {
        notifyChildrenChanged(contentHierarchyFilesRoot)
        notifyChildrenChanged(contentHierarchyTracksRoot)
        notifyChildrenChanged(contentHierarchyAlbumsRoot)
        notifyChildrenChanged(contentHierarchyArtistsRoot)
        notifyChildrenChanged(contentHierarchyRoot)
        if (latestContentHierarchyIDRequested.isNotBlank()
            && latestContentHierarchyIDRequested !in listOf(
                contentHierarchyFilesRoot,
                contentHierarchyTracksRoot,
                contentHierarchyAlbumsRoot,
                contentHierarchyArtistsRoot,
                contentHierarchyRoot
            )
        ) {
            notifyChildrenChanged(latestContentHierarchyIDRequested)
        }
    }

    private fun onStorageLocationRefresh() {
        notifyBrowserChildrenChangedAllLevels()
    }

    /**
     * See https://developer.android.com/guide/components/services#Lifecycle
     * https://developer.android.com/guide/topics/media-apps/audio-app/building-a-mediabrowserservice#service-lifecycle
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logger.debug(TAG, "onStartCommand(intent=$intent, flags=$flags, startid=$startId)")
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        isServiceStarted.set(true)
        if (intent?.action == ACTION_START_SERVICE_WITH_USB_DEVICE) {
            // If the USBDummyActivity is working, we will receive this. Forward it including the USB device extra to
            // our local USB broadcast receiver
            logger.debug(TAG, "Service was started from USBDummyActivity, forwarding broadcast")
            cancelUpdateDevicesCoroutine()
            maybeRegisterUSBBroadcastReceivers()
            val usbAttachedIntent = Intent(ACTION_USB_ATTACHED)
            val usbDevice: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            usbDevice?.let {
                usbAttachedIntent.putExtra(UsbManager.EXTRA_DEVICE, usbDevice)
                sendBroadcast(usbAttachedIntent)
            }
        } else if (intent?.action == ACTION_MEDIA_BUTTON) {
            lastServiceStartReason.set(ServiceStartStopReason.MEDIA_BUTTON)
        }
        logger.debug(TAG, "servicePriority=$servicePriority, lastServiceStartReason=${lastServiceStartReason.get()}")
        maybeHandleMediaButtonIntentForForegroundService(intent)
        when (intent?.action) {
            ACTION_PLAY_USB -> {
                launchInScopeSafely {
                    audioSession.playAnything()
                }
            }
            ACTION_MEDIA_BUTTON -> {
                audioSession.handleMediaButtonIntent(intent)
            }
            else -> {
                // ignore
            }
        }
        return START_STICKY
    }

    /**
     * See https://developer.android.com/guide/components/bound-services#Lifecycle
     */
    override fun onBind(intent: Intent?): IBinder? {
        logger.debug(TAG, "onBind(intent=$intent)")
        // TODO: security: allow only my package to bind to LocalBinder?
        val binder =
            if (intent?.action in listOf(ACTION_BIND_ALBUM_ART_CONTENT_PROVIDER, ACTION_BIND_FROM_TEST_FIXTURE)) {
                localBinder
            } else {
                super.onBind(intent)
            }
        if (intent != null) {
            val binderID = Util.createHashFromIntent(intent)
            logger.debug(TAG, "Returning binder ID for action ${intent.action}: $binderID")
            binderClients.add(binderID)
            logger.debug(TAG, "Current binder clients: $binderClients")
        }
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        logger.debug(TAG, "onUnbind(intent=$intent)")
        if (intent?.action in listOf(ACTION_BIND_ALBUM_ART_CONTENT_PROVIDER, ACTION_BIND_FROM_TEST_FIXTURE)
            && servicePriority == ServicePriority.FOREGROUND_REQUESTED
        ) {
            // In this case we need to wait until the service priority has changed, otherwise we will see a
            // crash with a RemoteServiceException
            logger.debug(TAG, "Pending foreground service start, will wait before unbinding")
            runBlocking {
                deferredUntilServiceInForeground.await()
                logger.debug(TAG, "Pending foreground service start has completed in onUnbind()")
                moveServiceToBackground()
                stopSelf()
                lastServiceStartReason.set(ServiceStartStopReason.UNKNOWN)
                isServiceStarted.set(false)
            }
        }
        if (intent != null) {
            binderClients.remove(Util.createHashFromIntent(intent))
            logger.debug(TAG, "Remaining binder clients: $binderClients")
        }
        return super.onUnbind(intent)
    }

    /**
     * This is called when app is swiped away from "recents". Usually this should not appear for services but it is
     * being called e.g. in Renault ("OpenR Link") cars.
     * (Possibly triggered by the finish() in USBDummyActivity which is taking effect in those cars?)
     *
     * See also https://developer.android.com/media/media3/session/background-playback
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        logger.debug(TAG, "onTaskRemoved()")
        super.onTaskRemoved(rootIntent)
    }

    private fun stopService(reason: ServiceStartStopReason) {
        logger.debug(TAG, "stopService(reason=$reason), servicePriority=${servicePriority}")
        if (!isServiceStarted.get()) {
            logger.debug(TAG, "Service is not running, cannot stop")
            return
        }
        if (!shouldStopServiceFor(reason)) {
            logger.debug(TAG, "Will not stop service")
            return
        }
        if (servicePriority != ServicePriority.FOREGROUND_REQUESTED) {
            moveServiceToBackground()
            stopSelf()
            lastServiceStartReason.set(ServiceStartStopReason.UNKNOWN)
            isServiceStarted.set(false)
        } else {
            // In this case we need to wait until the service priority has changed, otherwise we will see a
            // crash with a RemoteServiceException
            // https://github.com/MoleMan1024/audiowagon/issues/56
            logger.debug(TAG, "Pending foreground service start, will wait before stopping service")
            launchInScopeSafely {
                deferredUntilServiceInForeground.await()
                logger.debug(TAG, "Pending foreground service start has completed")
                if (!shouldStopServiceFor(reason)) {
                    return@launchInScopeSafely
                }
                moveServiceToBackground()
                stopSelf()
                lastServiceStartReason.set(ServiceStartStopReason.UNKNOWN)
                isServiceStarted.set(false)
            }
        }
    }

    private fun shouldStopServiceFor(reason: ServiceStartStopReason): Boolean {
        logger.debug(
            TAG,
            "shouldStopServiceFor(reason=$reason), lastServiceStartReason=${lastServiceStartReason.get()}"
        )
        if (reason == ServiceStartStopReason.INDEXING) {
            // if a higher priority reason previously started the service, do not stop service when indexing ends
            if (lastServiceStartReason.get() in listOf(
                    ServiceStartStopReason.MEDIA_BUTTON,
                    ServiceStartStopReason.MEDIA_SESSION_CALLBACK,
                    ServiceStartStopReason.LIFECYCLE
                )
            ) {
                return false
            }
        } else if (reason == ServiceStartStopReason.MEDIA_SESSION_CALLBACK) {
            // do not stop indexing for a media session callback (e.g. onStop() happens when switching to other media
            // app)
            if (lastServiceStartReason.get() == ServiceStartStopReason.INDEXING) {
                return false
            }
        }
        return true
    }

    private fun delayedMoveServiceToBackground() {
        logger.debug(TAG, "delayedMoveServiceToBackground()")
        if (servicePriority != ServicePriority.FOREGROUND_REQUESTED) {
            if (!isServiceStarted.get()) {
                logger.warning(TAG, "Service is not running")
                return
            }
            moveServiceToBackground()
        } else {
            logger.debug(TAG, "Pending foreground service start, will wait before moving service to background")
            launchInScopeSafely {
                deferredUntilServiceInForeground.await()
                logger.debug(TAG, "Pending foreground service start has completed")
                moveServiceToBackground()
            }
        }
    }

    override fun onDestroy() {
        logger.debug(TAG, "onDestroy()")
        shutdownAndDestroy()
        broadcastReceiverManager?.shutdown()
        broadcastReceiverManager = null
        super.onDestroy()
    }

    fun shutdown() {
        if (isShuttingDown.get()) {
            logger.warning(TAG, "Already shutting down")
            return
        }
        isShuttingDown.set(true)
        metadataReadNowRequested = false
        logger.info(TAG, "shutdown()")
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
            logger.verbose(TAG, "Lifecycle already destroyed")
            return
        }
        // notify AlbumArtContentProvider observer so that it will unbind from this service already
        notifyLifecycleObservers(LifecycleEvent.IDLE)
        cancelMostJobs()
        suspendSingletonCoroutine.cancel()
        cancelUpdateDevicesCoroutine()
        // before removing audio session notification the service must be in background
        stopForeground(STOP_FOREGROUND_REMOVE)
        audioSessionCloseSingletonCoroutine.launch {
            audioSession.storePlaybackState()
            audioSession.shutdown()
        }
        try {
            // runBlocking() can not attach a CoroutineExceptionHandler, use regular try-catch instead
            runBlocking(lifecycleScope.coroutineContext + dispatcher) {
                withTimeout(3000) {
                    audioSessionCloseSingletonCoroutine.join()
                }
            }
        } catch (exc: TimeoutCancellationException) {
            logger.exception(TAG, "Could not close audio session in time", exc)
        } catch (exc: CancellationException) {
            logger.exception(TAG, "Audio session close was cancelled", exc)
        }
        try {
            runBlocking(lifecycleScope.coroutineContext + dispatcher) {
                try {
                    audioItemLibrary.removeRepository(audioFileStorage.getPrimaryStorageLocation().storageID)
                } catch (exc: (NoSuchElementException)) {
                    logger.warning(TAG, exc.toString())
                } catch (exc: (IllegalArgumentException)) {
                    logger.warning(TAG, exc.toString())
                }
                gui.shutdown()
                stopService(ServiceStartStopReason.LIFECYCLE)
                audioFileStorage.shutdown()
                isUSBNotRecoverable.set(false)
            }
            logger.debug(TAG, "shutdown() (instance=${this}) is done at: ${Util.getLocalDateTimeStringNow()}")
        } catch (exc: CancellationException) {
            logger.exception(TAG, "A blocking coroutine in shutdown was cancelled", exc)
        }
    }

    private fun cancelMostJobs() {
        audioFileStorage.cancelIndexing()
        audioItemLibrary.cancelBuildLibrary()
        restoreFromPersistentSingletonCoroutine.cancel()
        cleanPersistentSingletonCoroutine.cancel()
        storePlaybackStateSingletonCoroutine.cancel()
        audioSessionCloseSingletonCoroutine.cancel()
        cancelLibraryCreation()
        cancelLoadChildren()
    }

    private fun shutdownAndDestroy() {
        shutdown()
        destroyLifecycleScope()
        // process should be stopped soon afterwards
    }

    fun suspend() {
        logger.info(TAG, "suspend()")
        if (lifecycleRegistry.currentState == Lifecycle.State.INITIALIZED) {
            logger.warning(TAG, "Can not suspend when not yet created")
            return
        }
        isSuspended.set(true)
        cancelMostJobs()
        cancelUpdateDevicesCoroutine()
        suspendSingletonCoroutine.launch {
            audioSessionCloseSingletonCoroutine.launch {
                audioSession.storePlaybackState()
                audioSession.suspend()
            }
            audioSessionCloseSingletonCoroutine.join()
            gui.suspend()
            audioItemLibrary.suspend()
            audioFileStorage.suspend()
            maybeUnregisterUSBBroadcastReceivers()
            stopService(ServiceStartStopReason.LIFECYCLE)
            notifyLifecycleObservers(LifecycleEvent.SUSPEND)
            logger.info(TAG, "end of suspend() reached at: ${Util.getLocalDateTimeStringNow()}")
        }
    }

    @Synchronized
    private fun maybeUnregisterUSBBroadcastReceivers() {
        usbExternalBroadcastReceiver?.let {
            broadcastReceiverManager?.unregister(it)
            usbExternalBroadcastReceiver = null
        }
        usbInternalBroadcastReceiver?.let {
            broadcastReceiverManager?.unregister(it)
            usbInternalBroadcastReceiver = null
        }
    }

    // This will happen during initial boot and every time the car wakes of from suspend
    fun wakeup() {
        logger.info(TAG, "wakeup() at: ${Util.getLocalDateTimeStringNow()}")
        if (lifecycleRegistry.currentState == Lifecycle.State.INITIALIZED) {
            logger.warning(TAG, "Can not wake up when not yet created")
            return
        }
        audioSession.setLastWakeupTimeMillis(Util.getMillisNow())
        if (!isScreenOn()) {
            logger.info(TAG, "Screen is still off, will not wake up")
            return
        }
        if (isScreenLocked()) {
            logger.info(TAG, "Screen is still locked, will not wake up")
            return
        }
        maybeRegisterUSBBroadcastReceivers()
        if (!isSuspended.get()) {
            logger.warning(TAG, "System is not suspended, ignoring wakeup()")
            return
        }
        isSuspended.set(false)
        audioFileStorage.wakeup()
        updateDevicesAfterUnlock()
    }

    @Synchronized
    private fun maybeRegisterUSBBroadcastReceivers() {
        // We don't really want to use these broadcast receivers for USB because it will prevent the desired
        // implementation in the manifest from working. So we try to register these as late as possible. However we
        // must register these at some point because the approach with USB intents in manifest does not work for
        // Polestar/Volvo cars
        if (usbExternalBroadcastReceiver == null) {
            usbExternalBroadcastReceiver = USBExternalBroadcastReceiver()
            usbExternalBroadcastReceiver?.usbDeviceConnections = audioFileStorage.usbDeviceConnections
            broadcastReceiverManager?.register(usbExternalBroadcastReceiver!!)
        }
        if (usbInternalBroadcastReceiver == null) {
            usbInternalBroadcastReceiver = USBInternalBroadcastReceiver()
            usbInternalBroadcastReceiver?.usbDeviceConnections = audioFileStorage.usbDeviceConnections
            broadcastReceiverManager?.register(usbInternalBroadcastReceiver!!)
        }
    }

    private fun destroyLifecycleScope() {
        logger.debug(TAG, "destroyLifecycleScope()")
        // since we use lifecycle scope almost everywhere, this should cancel all pending coroutines
        notifyLifecycleObservers(LifecycleEvent.DESTROY)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        } else {
            Handler(Looper.getMainLooper()).post { lifecycleRegistry.currentState = Lifecycle.State.DESTROYED }
        }
        logger.verbose(TAG, "Lifecycle set to DESTROYED")
    }

    private fun cancelLibraryCreation() {
        logger.debug(TAG, "Cancelling audio library creation")
        libraryCreationSingletonCoroutine.cancel()
        logger.debug(TAG, "Cancelled audio library creation")
    }

    private fun cancelLoadChildren() {
        logger.debug(TAG, "Cancelling handling of onLoadChildren()")
        loadChildrenJobs.forEach { (_, job) ->
            job.cancel()
        }
        loadChildrenJobs.clear()
        logger.debug(TAG, "Cancelled handling of onLoadChildren()")
    }

    /**
     * One of the first functions called by the MediaBrowser client.
     * Returns the root ID of the media browser tree view to show to the user.
     * Returning null will disconnect the client.
     */
    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        logger.debug(TAG, "onGetRoot(clientPackageName=$clientPackageName, rootHints=$rootHints)")
        if (clientPackagesToReject.contains(clientPackageName)){
            logger.warning(TAG, "Rejecting client package during testing: $clientPackageName")
            return null
        }
        if (!packageValidation.isClientValid(clientPackageName, clientUid)) {
            logger.warning(TAG, "Client ${clientPackageName}(${clientUid}) could not be validated for browsing")
            return null
        }
        maybeRegisterUSBBroadcastReceivers()
        if (clientPackageName == "com.android.car.media"
            && lastAudioPlayerState == AudioPlayerState.STOPPED
            && !libraryCreationSingletonCoroutine.isInProgress()
            && !restoreFromPersistentSingletonCoroutine.isInProgress()
            && audioFileStorage.isAnyStorageAvailable()) {
            logger.debug(TAG, "Looks like a switch from other media app, will restore persistent data")
            launchRestoreFromPersistentJob()
        }
        notifyIdleSingletonCoroutine.cancel()
        // Polestar 2 with Android 12 does not support custom browse view actions per media item
        val customBrowserActionLimit =
            rootHints?.getInt(MediaConstants.BROWSER_ROOT_HINTS_KEY_CUSTOM_BROWSER_ACTION_LIMIT, -1)
        logger.debug(TAG, "customBrowserActionLimit=$customBrowserActionLimit")
        val maximumRootChildLimit = rootHints?.getInt(MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT, 4)
        logger.debug(TAG, "maximumRootChildLimit=$maximumRootChildLimit")
        val supportedRootChildFlags = rootHints?.getInt(
            MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_SUPPORTED_FLAGS,
            MediaItem.FLAG_BROWSABLE
        )
        logger.debug(TAG, "supportedRootChildFlags=$supportedRootChildFlags")
        val albumArtNumPixels = rootHints?.getInt(MediaConstants.BROWSER_ROOT_HINTS_KEY_MEDIA_ART_SIZE_PIXELS, -1)
        if (albumArtNumPixels != null && albumArtNumPixels > ALBUM_ART_MIN_NUM_PIXELS) {
            logger.debug(TAG, "Setting album art size: $albumArtNumPixels")
            AlbumArtContentProvider.setAlbumArtSizePixels(albumArtNumPixels)
        }
        // Implementing EXTRA_RECENT here would likely not work as we won't have permission to access USB drive yet
        // when this is called during boot phase
        // ( https://developer.android.com/guide/topics/media/media-controls )
        val containsExtraRecent = rootHints?.getBoolean(BrowserRoot.EXTRA_RECENT) == true
        logger.debug(TAG, "containsExtraRecent=$containsExtraRecent")
        val hints = Bundle().apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            )
            putInt(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            )
            putBoolean(MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED, true)
        }
        return BrowserRoot(contentHierarchyRoot, hints)
    }

    /**
     * Returns list of [MediaItem] depending on the given content hierarchy ID
     * Returning empty list will show a message "no media available here".
     * Returning null will show "something went wrong" error message.
     *
     * The result is sent via Binder RPC to the media browser client process and its GUI, that means it
     * must be limited in size (maximum size of the parcel is 512 kB it seems).
     *
     * We don't use EXTRA_PAGE ( https://developer.android.com/reference/android/media/browse/MediaBrowser#EXTRA_PAGE )
     * (IIRC the AAOS client did not send it) instead we use groups of media items to reduce the number of items on a
     * single screen.
     *
     * The media item hierarchy is described in [ContentHierarchyElement]
     */
    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>, options: Bundle) {
        logger.debug(
            TAG,
            "onLoadChildren(parentId=$parentId, options=$options) from package ${currentBrowserInfo.packageName}"
        )
        latestContentHierarchyIDRequested = parentId
        result.detach()
        if (isSuspended.get()) {
            logger.warning(TAG, "Returning empty result for onLoadChildren() because suspended")
            result.sendResult(mutableListOf())
            return
        }
        val jobID = Util.generateUUID()
        val loadChildrenJob = launchInScopeSafely {
            logger.verbose(TAG, "launch loadChildrenJob=$coroutineContext")
            try {
                val contentHierarchyID = ContentHierarchyElement.deserialize(parentId)
                val mediaItems: List<MediaItem> = audioItemLibrary.getMediaItemsStartingFrom(contentHierarchyID)
                logger.debug(TAG, "Got ${mediaItems.size} mediaItems in onLoadChildren(parentId=$parentId)")
                result.sendResult(mediaItems.toMutableList())
            } catch (exc: CancellationException) {
                logger.warning(TAG, exc.message.toString())
                result.sendResult(null)
            } catch (exc: FileNotFoundException) {
                // this can happen when some client is still trying to access the path to a meanwhile deleted file
                logger.warning(TAG, exc.message.toString())
                result.sendResult(null)
            } catch (exc: NoSuchElementException) {
                // happens when USB drive is unplugged
                logger.warning(TAG, exc.message.toString())
                result.sendResult(null)
            } catch (exc: IOException) {
                crashReporting.logLastMessagesAndRecordException(exc)
                logger.exception(TAG, exc.message.toString(), exc)
                result.sendResult(null)
            } catch (exc: RuntimeException) {
                crashReporting.logLastMessagesAndRecordException(exc)
                logger.exception(TAG, exc.message.toString(), exc)
                if (!isShuttingDown.get()) {
                    audioSession.showError(getString(R.string.error_unknown))
                }
                result.sendResult(null)
            } finally {
                loadChildrenJobs.remove(jobID)
            }
        }
        loadChildrenJobs[jobID] = loadChildrenJob
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        onLoadChildren(parentId, result, Bundle())
    }

    override fun onLoadItem(itemId: String?, result: Result<MediaItem>) {
        logger.debug(TAG, "onLoadItem(itemId=$itemId")
        super.onLoadItem(itemId, result)
    }

    override fun onSearch(query: String, extras: Bundle?, result: Result<MutableList<MediaItem>>) {
        logger.debug(TAG, "onSearch(query='$query', extras=$extras)")
        val metadataReadSetting = sharedPrefs.getMetadataReadSettingEnum(applicationContext, logger, TAG)
        if (metadataReadSetting == MetadataReadSetting.OFF) {
            result.sendResult(mutableListOf())
            return
        }
        result.detach()
        launchInScopeSafely {
            val mediaItems: MutableList<MediaItem> = audioItemLibrary.searchMediaItems(query)
            logger.debug(TAG, "Got ${mediaItems.size} mediaItems in onSearch()")
            result.sendResult(mediaItems)
        }
    }

    override fun onCustomAction(action: String, extras: Bundle?, result: Result<Bundle>) {
        logger.debug(TAG, "onCustomAction(action=$action, extras=$extras)")
        result.sendResult(Bundle())
    }

    private fun setUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // only log uncaught exceptions if we still have a USB device
            if ("(USB_DEVICE_DETACHED|did you unplug)".toRegex().containsMatchIn(throwable.stackTraceToString())) {
                logger.exceptionLogcatOnly(TAG, "Uncaught exception in service $this (USB failed)", throwable)
            } else {
                logger.exception(TAG, "Uncaught exception in service $this (USB is OK)", throwable)
                logger.setFlushToUSBFlag()
                try {
                    if (this::audioFileStorage.isInitialized) {
                        runBlocking {
                            audioFileStorage.shutdown()
                        }
                    }
                } catch (exc: Exception) {
                    logger.exceptionLogcatOnly(TAG, exc.message.toString(), exc)
                }
            }
            try {
                stopService(ServiceStartStopReason.LIFECYCLE)
            } catch (exc: Exception) {
                logger.exceptionLogcatOnly(TAG, exc.message.toString(), exc)
            }
            origDefaultUncaughtExceptionhandler?.uncaughtException(thread, throwable)
            killProcess()
        }
    }

    /**
     * End the process with non zero error code, so that we are restarted hopefully. This is used to "recover" from
     * situations where e.g. handles to USB devices that were unplugged during use still exist, they may hang the app
     * otherwise
     */
    private fun killProcess() {
        exitProcess(1)
    }

    private fun notifyLibraryCreationFailure() {
        gui.removeIndexingNotification()
        if (!isSuspended.get()) {
            runBlocking(lifecycleScope.coroutineContext + dispatcher) {
                audioSession.showError(getString(R.string.error_library_creation_fail))
            }
        }
    }

    private fun launchInScopeSafely(func: suspend (CoroutineScope) -> Unit): Job {
        return Util.launchInScopeSafely(lifecycleScope, dispatcher, logger, TAG, crashReporting, func)
    }

    suspend fun getAlbumArtForURI(uri: Uri): ByteArray? {
        logger.debug(Util.TAGCRT(TAG, coroutineContext), "getAlbumArtForURI($uri)")
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
            logger.warning(Util.TAGCRT(TAG, coroutineContext), "No album art because lifecycle destroyed")
            return null
        }
        if (!audioFileStorage.areAnyStoragesAvail()) {
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "No album art because no storages available")
            return null
        }
        try {
            return audioItemLibrary.getAlbumArtForArtURI(uri)
        } catch (exc: FileNotFoundException) {
            logger.warning(Util.TAGCRT(TAG, coroutineContext), exc.message.toString())
        } catch (exc: RuntimeException) {
            if (!exc.message.toString().contains("Unknown storage id")) {
                logger.exception(Util.TAGCRT(TAG, coroutineContext), exc.message.toString(), exc)
            }
        } catch (exc: IOException) {
            logger.exception(Util.TAGCRT(TAG, coroutineContext), exc.message.toString(), exc)
        }
        return null
    }

    override fun onTrimMemory(level: Int) {
        logger.debug(TAG, "onTrimMemory(level=$level)")
        cleanSingletonCoroutine.launch {
            audioItemLibrary.clearAlbumArtCache()
            audioFileStorage.cleanAlbumArtCache()
        }
        super.onTrimMemory(level)
    }

    private fun isAudioPlayerDormant(): Boolean {
        return lastAudioPlayerState in listOf(AudioPlayerState.IDLE, AudioPlayerState.PAUSED, AudioPlayerState
            .STOPPED, AudioPlayerState.ERROR)
    }

    fun addLifecycleObserver(id: String, callback: (event: LifecycleEvent) -> Unit) {
        logger.debug(TAG, "Adding lifecycle observer: $id")
        lifecycleObserversMap[id] = callback
    }

    fun removeLifecycleObserver(id: String) {
        logger.debug(TAG, "Removing lifecycle observer: $id")
        lifecycleObserversMap.remove(id)
    }

    private fun notifyLifecycleObservers(event: LifecycleEvent) {
        logger.debug(TAG, "Notify lifecycle observers: $event")
        lifecycleObserversMap.forEach { (_, callback) ->
            callback(event)
        }
    }

    private fun moveServiceToForegroundSafely() {
        val notification = audioSessionNotification
        if (notification != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    moveServiceToForeground(notification)
                } catch (exc: ForegroundServiceStartNotAllowedException) {
                    logger.exception(TAG, exc.message.toString(), exc)
                }
            } else {
                moveServiceToForeground(notification)
            }
        } else {
            logger.warning(TAG, "Can not move service to foreground, missing audioSessionNotification")
        }
        deferredUntilServiceInForeground.complete(Unit)
    }

    private fun moveServiceToForeground(notification: Notification) {
        // https://developer.android.com/develop/background-work/services/fg-service-types#media
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        logger.debug(TAG, "Moved service to foreground (${this})")
        servicePriority = ServicePriority.FOREGROUND
    }

    // The MediaButtonReceiver will start the service in foreground always, make sure to handle that
    private fun maybeHandleMediaButtonIntentForForegroundService(intent: Intent?) {
        if (intent?.action === ACTION_MEDIA_BUTTON) {
            logger.debug(TAG, "Need to move service to foreground due to media button")
            moveServiceToForegroundSafely()
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): AudioBrowserService = this@AudioBrowserService
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun cancelUpdateDevicesCoroutine() {
        updateDevicesSingletonCoroutine.cancel()
        audioFileStorage.setIsUpdatingDevices(false)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun getAudioItemLibrary(): AudioItemLibrary {
        return audioItemLibrary
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    suspend fun getPrimaryRepo(): AudioItemRepository? {
        return audioItemLibrary.getPrimaryRepository()
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun getIndexingStatus(): List<IndexingStatus> {
        return audioFileStorage.getIndexingStatus()
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun getAudioPlayerStatus(): AudioPlayerStatus {
        return audioSession.getAudioPlayerStatus()
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun getPlaybackState(): PlaybackStateCompat {
        return audioSession.getPlaybackState()
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun setMediaDeviceForTest(mediaDevice: MediaDevice) {
        logger.debug(TAG, "setMediaDeviceForTest($mediaDevice)")
        audioFileStorage.mediaDevicesForTest.clear()
        audioFileStorage.mediaDevicesForTest.add(mediaDevice)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun setUseInMemoryDatabase() {
        audioItemLibrary.useInMemoryDatabase = true
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun setLastWakeupTimeMillis(lastWakeupTimeMillis: Long) {
        audioSession.setLastWakeupTimeMillis(lastWakeupTimeMillis)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun addClientPackageToReject(packageName: String) {
        clientPackagesToReject.add(packageName)
    }

}
