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

import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.session.PlaybackStateCompat
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import de.moleman1024.audiowagon.authorization.PackageValidation
import de.moleman1024.audiowagon.authorization.USBDevicePermissions
import de.moleman1024.audiowagon.broadcast.PowerEventReceiver
import de.moleman1024.audiowagon.exceptions.CannotRecoverUSBException
import de.moleman1024.audiowagon.exceptions.MissingNotifChannelException
import de.moleman1024.audiowagon.exceptions.NoChangesCancellationException
import de.moleman1024.audiowagon.filestorage.*
import de.moleman1024.audiowagon.log.CrashReporting
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.AlbumStyleSetting
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary
import de.moleman1024.audiowagon.medialibrary.CONTENT_STYLE_SUPPORTED
import de.moleman1024.audiowagon.medialibrary.MetadataReadSetting
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyElement
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyID
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyType
import de.moleman1024.audiowagon.persistence.PersistentPlaybackState
import de.moleman1024.audiowagon.persistence.PersistentStorage
import de.moleman1024.audiowagon.player.*
import de.moleman1024.audiowagon.repository.AudioItemRepository
import kotlinx.coroutines.*
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.system.exitProcess

private const val TAG = "AudioBrowserService"

@ExperimentalCoroutinesApi
/**
 * This is the main entry point of the app.
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
    private lateinit var powerEventReceiver: PowerEventReceiver
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
    private lateinit var wakeSingletonCoroutine: SingletonCoroutine
    private lateinit var notifyIdleSingletonCoroutine: SingletonCoroutine
    private lateinit var powerManager: PowerManager
    @Volatile
    private var isServiceStarted: Boolean = false
    @Volatile
    private var servicePriority: ServicePriority = ServicePriority.BACKGROUND
    @Volatile
    private var lastServiceStartReason: ServiceStartStopReason = ServiceStartStopReason.UNKNOWN
    private var deferredUntilServiceInForeground: CompletableDeferred<Unit> = CompletableDeferred()
    private var audioSessionNotification: Notification? = null
    private var latestContentHierarchyIDRequested: String = ""
    private var lastAudioPlayerState: AudioPlayerState = AudioPlayerState.IDLE
    private var isShuttingDown: Boolean = false
    private var isSuspended: Boolean = false
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
    private val binder = LocalBinder()
    private val lifecycleObserversMap = mutableMapOf<String, (event: LifecycleEvent) -> Unit>()
    private val isUSBNotRecoverable = AtomicBoolean()

    init {
        isShuttingDown = false
        lifecycleRegistry.currentState = Lifecycle.State.INITIALIZED
        instance = this
        logger.init(lifecycleScope)
        setUncaughtExceptionHandler()
    }

    override fun onCreate() {
        logger.debug(TAG, "onCreate()")
        isUSBNotRecoverable.set(false)
        isShuttingDown = false
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
        wakeSingletonCoroutine =
            SingletonCoroutine("Wakeup", dispatcher, lifecycleScope.coroutineContext, crashReporting)
        wakeSingletonCoroutine.behaviour = SingletonCoroutineBehaviour.PREFER_FINISH
        notifyIdleSingletonCoroutine =
            SingletonCoroutine("NotifyIdle", dispatcher, lifecycleScope.coroutineContext, crashReporting)
        powerEventReceiver = PowerEventReceiver()
        powerEventReceiver.audioBrowserService = this
        val shutdownFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        registerReceiver(powerEventReceiver, shutdownFilter)
        powerManager = this.getSystemService(Context.POWER_SERVICE) as PowerManager
        startup()
        if (!powerManager.isInteractive) {
            logger.debug(TAG, "onCreate() called while screen was off, suspending again")
            suspend()
        }
    }

    @ExperimentalCoroutinesApi
    fun startup() {
        logger.debug(TAG, "startup()")
        persistentStorage = PersistentStorage(this, dispatcher)
        gui = GUI(lifecycleScope, applicationContext)
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
            // This avoids "RemoteServiceException: Context.startForegroundService() did not then call
            // Service.startForeground" when previously started foreground service is restarted.
            // If the service was not started previously, startForeground() should do nothing
            audioSessionNotification = audioSession.getNotification()
            startForeground(NOTIFICATION_ID, audioSessionNotification)
            servicePriority = ServicePriority.FOREGROUND
        }
        packageValidation = PackageValidation(this, R.xml.allowed_media_browser_callers)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        logger.verbose(TAG, "Lifecycle set to CREATED")
        observeAudioFileStorage()
        observeAudioSessionStateChanges()
        if (powerManager.isInteractive) {
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
                if (!isSuspended) {
                    runBlocking(dispatcher) {
                        audioSession.showError(getString(R.string.error_USB, storageChange.error))
                    }
                }
                audioSession.stopPlayer()
                allStorageIDs.forEach { onStorageLocationRemoved(it) }
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
            runBlocking(dispatcher) {
                audioSession.showError(this@AudioBrowserService.getString(R.string.error_USB_init))
            }
            crashReporting.logLastMessagesAndRecordException(exc)
        } catch (exc: RuntimeException) {
            logger.exception(TAG, "Runtime error during update of connected USB devices", exc)
            runBlocking(dispatcher) {
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
                        updateAttachedDevices()
                    }
                }
            }
            SettingKey.READ_METADATA_NOW -> {
                metadataReadNowRequested = true
                audioFileStorage.cancelIndexing()
                audioItemLibrary.cancelBuildLibrary()
                cancelLibraryCreation()
                updateAttachedDevices()
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
        }
    }

    private fun handleCustomActionEvent(event: CustomActionEvent) {
        when (event.action) {
            CustomAction.EJECT -> {
                cancelMostJobs()
            }
            CustomAction.MAYBE_SHOW_USB_PERMISSION_POPUP -> {
                try {
                    audioFileStorage.requestUSBPermissionIfMissing()
                } catch (exc: Exception) {
                    logger.exception(TAG, exc.message.toString(), exc)
                }
            }
            CustomAction.STOP_CB_CALLED -> {
                if (isServiceStarted
                    || isShuttingDown
                    || isSuspended
                    || servicePriority != ServicePriority.BACKGROUND) {
                    logger.verbose(TAG, "Ignore STOP_CB_CALLED: " +
                            "isServiceStarted=$isServiceStarted," +
                            "isShuttingDown=$isShuttingDown," +
                            "isSuspended=$isSuspended," +
                            "servicePrio=$servicePriority")
                    return
                }
                notifyIdleSingletonCoroutine.launch {
                    logger.debug(TAG, "Stop callback was called, will notify idle state in: ${IDLE_TIMEOUT_MS}ms")
                    delay(IDLE_TIMEOUT_MS)
                    logger.debug(TAG, "Player is stopped and service is idle")
                    // notify the AlbumArtContentProvider so it will unbind, so Android can free resources if nothing
                    // else is bound either
                    notifyLifecycleObservers(LifecycleEvent.IDLE)
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
        if (isServiceStarted) {
            if (servicePriority == ServicePriority.BACKGROUND) {
                logger.debug(TAG, "Moving already started service to foreground")
                try {
                    audioSessionNotification = audioSession.getNotification()
                    startForeground(NOTIFICATION_ID, audioSessionNotification)
                    servicePriority = ServicePriority.FOREGROUND
                    lastServiceStartReason = reason
                } catch (exc: MissingNotifChannelException) {
                    logger.exception(TAG, exc.message.toString(), exc)
                    crashReporting.logLastMessagesAndRecordException(exc)
                }
            }
            return
        }
        logger.debug(TAG, "startServiceInForeground(reason=$reason)")
        servicePriority = ServicePriority.FOREGROUND_REQUESTED
        if (deferredUntilServiceInForeground.isCompleted) {
            deferredUntilServiceInForeground = CompletableDeferred()
        }
        try {
            audioSessionNotification = audioSession.getNotification()
            if (lastServiceStartReason <= reason) {
                lastServiceStartReason = reason
            }
            startForegroundService(Intent(this, AudioBrowserService::class.java))
            logger.debug(TAG, "startForegroundService() called")
        } catch (exc: MissingNotifChannelException) {
            logger.exception(TAG, exc.message.toString(), exc)
            servicePriority = ServicePriority.FOREGROUND
            crashReporting.logLastMessagesAndRecordException(exc)
        }
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
        // TODO: might need to remove this for Android 12
        //  https://developer.android.com/guide/components/foreground-services#background-start-restrictions
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
            if (isShuttingDown) {
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
        logger.debug(TAG, "createLibraryFromStorages(storageIDs=${storageIDs})")
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
            logger.debug(TAG, "No recent content hierarchy ID to restore from")
            return
        }
        val contentHierarchyID = ContentHierarchyElement.deserialize(state.trackID)
        when (contentHierarchyID.type) {
            ContentHierarchyType.TRACK -> {
                if (audioItemLibrary.getRepoForContentHierarchyID(contentHierarchyID) == null) {
                    logger.warning(TAG, "Cannot restore recent track, storage repository mismatch")
                    return
                }
            }
            ContentHierarchyType.FILE -> {
                if (audioFileStorage.getPrimaryStorageLocation().storageID != contentHierarchyID.storageID) {
                    logger.warning(TAG, "Cannot restore recent file, storage id mismatch")
                    return
                }
            }
            else -> {
                logger.error(TAG, "Not supported to restore from type: $contentHierarchyID")
                return
            }
        }
        if (state.queueIDs.isEmpty()) {
            logger.warning(TAG, "Found persistent recent track, but no queue items")
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
        if (isShuttingDown) {
            logger.debug(TAG, "Shutdown is in progress")
            return
        }
        cancelMostJobs()
        audioSessionCloseSingletonCoroutine.launch {
            audioSession.storePlaybackState()
            audioSession.reset()
        }
        if (storageID.isNotBlank()) {
            try {
                val storageLocation = audioFileStorage.getStorageLocationForID(storageID)
                runBlocking(dispatcher) {
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
        isServiceStarted = true
        if (intent?.action == ACTION_MEDIA_BUTTON) {
            lastServiceStartReason = ServiceStartStopReason.MEDIA_BUTTON
        }
        if (intent?.action == ACTION_RESTART_SERVICE) {
            servicePriority = ServicePriority.FOREGROUND_REQUESTED
        }
        logger.debug(TAG, "servicePriority=$servicePriority, lastServiceStartReason=$lastServiceStartReason")
        if (intent?.component == ComponentName(this, this.javaClass)
            && intent.action != ACTION_MEDIA_BUTTON
            && servicePriority == ServicePriority.FOREGROUND_REQUESTED
        ) {
            logger.debug(TAG, "Need to move service to foreground")
            if (audioSessionNotification == null) {
                val msg = "Missing audioSessionNotification for foreground service"
                logger.error(TAG, msg)
                crashReporting.logLastMessagesAndRecordException(RuntimeException(msg))
            } else {
                startForeground(NOTIFICATION_ID, audioSessionNotification)
                logger.debug(TAG, "Moved service to foreground")
                servicePriority = ServicePriority.FOREGROUND
                deferredUntilServiceInForeground.complete(Unit)
            }
        }
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
        return Service.START_STICKY
    }

    @Suppress("RedundantNullableReturnType")
    /**
     * See https://developer.android.com/guide/components/bound-services#Lifecycle
     */
    override fun onBind(intent: Intent?): IBinder? {
        logger.debug(TAG, "onBind(intent=$intent)")
        return if (intent?.action == AudioBrowserService::class.java.name) {
            binder
        } else {
            super.onBind(intent)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        logger.debug(TAG, "onUnbind(intent=$intent)")
        return super.onUnbind(intent)
    }

    /**
     * This is called when app is swiped away from "recents". It is only called when service was started previously.
     * The "recents" app function does not seem to exist on Android Automotive
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        logger.debug(TAG, "onTaskRemoved()")
        audioSessionCloseSingletonCoroutine.launch {
            audioSession.shutdown()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun stopService(reason: ServiceStartStopReason) {
        logger.debug(TAG, "stopService()")
        if (!isServiceStarted) {
            logger.warning(TAG, "Service is not running")
            return
        }
        if (!shouldStopServiceFor(reason)) {
            logger.debug(TAG, "Will not stop service")
            return
        }
        if (servicePriority != ServicePriority.FOREGROUND_REQUESTED) {
            moveServiceToBackground()
            // We can't stop service before we have moved it to background. Without this sleep() this fails often in
            // instrumented tests
            Thread.sleep(10)
            stopSelf()
            lastServiceStartReason = ServiceStartStopReason.UNKNOWN
            isServiceStarted = false
        } else {
            // in this case we need to wait until the service priority has changed, otherwise we will see a
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
                lastServiceStartReason = ServiceStartStopReason.UNKNOWN
                isServiceStarted = false
            }
        }
    }

    private fun shouldStopServiceFor(reason: ServiceStartStopReason): Boolean {
        logger.debug(TAG, "shouldStopServiceFor(reason=$reason), lastServiceStartReason=$lastServiceStartReason")
        if (reason == ServiceStartStopReason.INDEXING) {
            // if a higher priority reason previously started the service, do not stop service when indexing ends
            if (lastServiceStartReason in listOf(
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
            if (lastServiceStartReason == ServiceStartStopReason.INDEXING) {
                return false
            }
        }
        return true
    }

    private fun delayedMoveServiceToBackground() {
        logger.debug(TAG, "delayedMoveServiceToBackground()")
        if (servicePriority != ServicePriority.FOREGROUND_REQUESTED) {
            if (!isServiceStarted) {
                logger.warning(TAG, "Service is not running")
                servicePriority = ServicePriority.BACKGROUND
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
        unregisterReceiver(powerEventReceiver)
        super.onDestroy()
    }

    fun shutdown() {
        if (isShuttingDown) {
            logger.warning(TAG, "Already shutting down")
        }
        isShuttingDown = true
        metadataReadNowRequested = false
        logger.info(TAG, "shutdown()")
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
            logger.verbose(TAG, "Lifecycle already destroyed")
            return
        }
        cancelMostJobs()
        // before removing audio session notification the service must be in background
        stopForeground(STOP_FOREGROUND_REMOVE)
        audioSessionCloseSingletonCoroutine.launch {
            audioSession.storePlaybackState()
            audioSession.shutdown()
        }
        runBlocking(dispatcher) {
            try {
                withTimeout(4000) {
                    audioSessionCloseSingletonCoroutine.join()
                }
            } catch (exc: TimeoutCancellationException) {
                logger.exception(TAG, "Could not close audio session in time", exc)
            }
            try {
                audioItemLibrary.removeRepository(audioFileStorage.getPrimaryStorageLocation().storageID)
            } catch (exc: (NoSuchElementException)) {
                logger.warning(TAG, exc.toString())
            } catch (exc: (IllegalArgumentException)) {
                logger.warning(TAG, exc.toString())
            }
        }
        gui.shutdown()
        stopService(ServiceStartStopReason.LIFECYCLE)
        audioFileStorage.shutdown()
        isUSBNotRecoverable.set(false)
        logger.debug(TAG, "shutdown() is done")
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
        isSuspended = true
        cancelMostJobs()
        wakeSingletonCoroutine.cancel()
        suspendSingletonCoroutine.launch {
            audioSessionCloseSingletonCoroutine.launch {
                audioSession.storePlaybackState()
                audioSession.suspend()
            }
            audioSessionCloseSingletonCoroutine.join()
            gui.suspend()
            audioFileStorage.suspend()
            audioItemLibrary.suspend()
            stopService(ServiceStartStopReason.LIFECYCLE)
            notifyLifecycleObservers(LifecycleEvent.SUSPEND)
            logger.info(TAG, "end of suspend() reached")
        }
    }

    fun wakeup() {
        logger.info(TAG, "wakeup()")
        if (!isSuspended) {
            logger.warning(TAG, "System is not suspended, ignoring wakeup()")
            return
        }
        isSuspended = false
        wakeSingletonCoroutine.launch {
            suspendSingletonCoroutine.join()
            audioFileStorage.wakeup()
            updateAttachedDevices()
        }
    }

    private fun destroyLifecycleScope() {
        // since we use lifecycle scope almost everywhere, this should cancel all pending coroutines
        notifyLifecycleObservers(LifecycleEvent.DESTROY)
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
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
        if (!packageValidation.isClientValid(clientPackageName, clientUid)) {
            logger.warning(TAG, "Client ${clientPackageName}(${clientUid}) could not be validated for browsing")
            return null
        }
        notifyIdleSingletonCoroutine.cancel()
        val maximumRootChildLimit = rootHints?.getInt(MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT, 4)
        logger.debug(TAG, "maximumRootChildLimit=$maximumRootChildLimit")
        val supportedRootChildFlags = rootHints?.getInt(
            MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_SUPPORTED_FLAGS,
            MediaItem.FLAG_BROWSABLE
        )
        val albumArtNumPixels = rootHints?.getInt(MediaConstants.BROWSER_ROOT_HINTS_KEY_MEDIA_ART_SIZE_PIXELS, -1)
        if (albumArtNumPixels != null && albumArtNumPixels > ALBUM_ART_MIN_NUM_PIXELS) {
            logger.debug(TAG, "Setting album art size: $albumArtNumPixels")
            AlbumArtContentProvider.setAlbumArtSizePixels(albumArtNumPixels)
        }
        // Implementing EXTRA_RECENT here would likely not work as we won't have permission to access USB drive yet
        // when this is called during boot phase
        // ( https://developer.android.com/guide/topics/media/media-controls )
        logger.debug(TAG, "supportedRootChildFlags=$supportedRootChildFlags")
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
    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        logger.debug(TAG, "onLoadChildren(parentId=$parentId) from package ${currentBrowserInfo.packageName}")
        latestContentHierarchyIDRequested = parentId
        result.detach()
        if (isSuspended) {
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
                if (!isShuttingDown) {
                    audioSession.showError(getString(R.string.error_unknown))
                }
                result.sendResult(null)
            } finally {
                loadChildrenJobs.remove(jobID)
            }
        }
        loadChildrenJobs[jobID] = loadChildrenJob
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

    private fun setUncaughtExceptionHandler() {
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // only log uncaught exceptions if we still have a USB device
            if ("(USB_DEVICE_DETACHED|did you unplug)".toRegex().containsMatchIn(throwable.stackTraceToString())) {
                logger.exceptionLogcatOnly(TAG, "Uncaught exception (USB failed)", throwable)
            } else {
                logger.exception(TAG, "Uncaught exception (USB is OK)", throwable)
                logger.setFlushToUSBFlag()
                try {
                    if (this::audioFileStorage.isInitialized) {
                        audioFileStorage.shutdown()
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
            oldHandler?.uncaughtException(thread, throwable)
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
        if (!isSuspended) {
            runBlocking(dispatcher) {
                audioSession.showError(getString(R.string.error_library_creation_fail))
            }
        }
    }

    private fun launchInScopeSafely(func: suspend (CoroutineScope) -> Unit): Job {
        return Util.launchInScopeSafely(lifecycleScope, dispatcher, logger, TAG, crashReporting, func)
    }

    suspend fun getAlbumArtForURI(uri: Uri): ByteArray? {
        logger.debug(TAG, "getAlbumArtForURI($uri)")
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
            logger.warning(TAG, "No album art because lifecycle destroyed")
            return null
        }
        try {
            return audioItemLibrary.getAlbumArtForArtURI(uri)
        } catch (exc: FileNotFoundException) {
            logger.warning(TAG, exc.message.toString())
        } catch (exc: RuntimeException) {
            if (!exc.message.toString().contains("Unknown storage id")) {
                logger.exception(TAG, exc.message.toString(), exc)
            }
        } catch (exc: IOException) {
            logger.exception(TAG, exc.message.toString(), exc)
        }
        return null
    }

    override fun onTrimMemory(level: Int) {
        logger.debug(TAG, "onTrimMemory(level=$level)")
        audioItemLibrary.clearAlbumArtCache()
        audioFileStorage.cleanAlbumArtCache()
        super.onTrimMemory(level)
    }

    private fun isAudioPlayerDormant(): Boolean {
        return lastAudioPlayerState in listOf(AudioPlayerState.IDLE, AudioPlayerState.PAUSED, AudioPlayerState.STOPPED)
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

    inner class LocalBinder : Binder() {
        fun getService(): AudioBrowserService = this@AudioBrowserService
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
    companion object {
        private lateinit var instance: AudioBrowserService

        @JvmStatic
        fun getInstance() = instance
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
    fun setMediaDeviceForTest(mediaDevice: MediaDevice) {
        logger.debug(TAG, "setMediaDeviceForTest($mediaDevice)")
        audioFileStorage.mediaDevicesForTest.clear()
        audioFileStorage.mediaDevicesForTest.add(mediaDevice)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun setUseInMemoryDatabase() {
        audioItemLibrary.useInMemoryDatabase = true
    }

}
