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

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
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
import de.moleman1024.audiowagon.filestorage.*
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.*
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyElement
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyID
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyType
import de.moleman1024.audiowagon.persistence.PersistentPlaybackState
import de.moleman1024.audiowagon.persistence.PersistentStorage
import de.moleman1024.audiowagon.player.AudioPlayerState
import de.moleman1024.audiowagon.player.AudioSession
import kotlinx.coroutines.*
import java.io.IOException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

private const val TAG = "AudioBrowserService"
const val NOTIFICATION_ID: Int = 25575
const val ACTION_RESTART_SERVICE: String = "de.moleman1024.audiowagon.ACTION_RESTART_SERVICE"
const val ACTION_QUICKBOOT_POWEROFF: String = "android.intent.action.QUICKBOOT_POWEROFF"
const val CMD_ENABLE_LOG_TO_USB = "de.moleman1024.audiowagon.CMD_ENABLE_LOG_TO_USB"
const val CMD_DISABLE_LOG_TO_USB = "de.moleman1024.audiowagon.CMD_DISABLE_LOG_TO_USB"
const val CMD_ENABLE_EQUALIZER = "de.moleman1024.audiowagon.CMD_ENABLE_EQUALIZER"
const val CMD_DISABLE_EQUALIZER = "de.moleman1024.audiowagon.CMD_DISABLE_EQUALIZER"
const val CMD_SET_EQUALIZER_PRESET = "de.moleman1024.audiowagon.CMD_SET_EQUALIZER_PRESET"
const val EQUALIZER_PRESET_KEY = "preset"

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
class AudioBrowserService : MediaBrowserServiceCompat(), LifecycleOwner {
    private var lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)
    private lateinit var audioItemLibrary: AudioItemLibrary
    private lateinit var audioFileStorage: AudioFileStorage
    private lateinit var usbDevicePermissions: USBDevicePermissions
    private lateinit var packageValidation: PackageValidation
    private lateinit var audioSession: AudioSession
    private lateinit var gui: GUI
    private lateinit var persistentStorage: PersistentStorage
    private lateinit var powerEventReceiver: PowerEventReceiver
    private var dispatcher: CoroutineDispatcher = Dispatchers.IO
    private var libraryCreationJob: Job? = null
    private var restoreFromPersistentJob: Job? = null
    private var loadChildrenJobs: ConcurrentHashMap<String, Job> = ConcurrentHashMap<String, Job>()
    private var searchJob: Job? = null
    private var isServiceStarted: Boolean = false
    private var isServiceForeground: Boolean = false
    private var latestContentHierarchyIDRequested: String = ""
    private var lastAudioPlayerState: AudioPlayerState = AudioPlayerState.IDLE
    private var isShuttingDown: Boolean = false
    private var isSuspended: Boolean = false
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

    init {
        lifecycleRegistry.currentState = Lifecycle.State.INITIALIZED
        instance = this
        setUncaughtExceptionHandler()
    }

    override fun onCreate() {
        logger.debug(TAG, "onCreate()")
        super.onCreate()
        powerEventReceiver = PowerEventReceiver()
        powerEventReceiver.audioBrowserService = this
        val shutdownFilter = IntentFilter().apply {
            addAction(ACTION_QUICKBOOT_POWEROFF)
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_REBOOT)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SHUTDOWN)
            addAction(Intent.ACTION_MY_PACKAGE_REPLACED)
            addAction(Intent.ACTION_MY_PACKAGE_SUSPENDED)
            addAction(Intent.ACTION_MY_PACKAGE_UNSUSPENDED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        registerReceiver(powerEventReceiver, shutdownFilter)
        startup()
    }

    @ExperimentalCoroutinesApi
    fun startup() {
        logger.debug(TAG, "startup()")
        persistentStorage = PersistentStorage(this, dispatcher)
        gui = GUI(lifecycleScope, applicationContext)
        usbDevicePermissions = USBDevicePermissions(this)
        audioFileStorage = AudioFileStorage(this, lifecycleScope, dispatcher, usbDevicePermissions, gui)
        audioItemLibrary = AudioItemLibrary(this, audioFileStorage, lifecycleScope, dispatcher, gui)
        audioItemLibrary.libraryExceptionObservers.add { exc ->
            when (exc) {
                is CannotRecoverUSBException -> {
                    notifyLibraryCreationFailure()
                }
            }
        }
        // TODO: we have no launch activity, we can probably remove this
        val sessionActivityIntent =
            packageManager?.getLaunchIntentForPackage(packageName)?.let { sessionIntent ->
                PendingIntent.getActivity(this, 0, sessionIntent, 0)
            }
        logger.debug(TAG, "sessionActivityIntent=${sessionActivityIntent.toString()}")
        logger.debug(TAG, "packageName=${packageName}")
        if (sessionToken == null) {
            audioSession =
                AudioSession(this, audioItemLibrary, audioFileStorage, lifecycleScope, dispatcher, gui,
                    persistentStorage, sessionActivityIntent)
            sessionToken = audioSession.sessionToken
            logger.debug(TAG, "New media session token: $sessionToken")
        }
        packageValidation = PackageValidation(this, R.xml.allowed_media_browser_callers)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        observeAudioFileStorage()
        observePlayerStateChanges()
        updateConnectedDevices()
    }

    @ExperimentalCoroutinesApi
    private fun observeAudioFileStorage() {
        audioFileStorage.storageObservers.add { storageChange ->
            val allStorageIDs = audioItemLibrary.getAllStorageIDs()
            logger.debug(TAG, "Storage IDs in library before change: $allStorageIDs")
            if (storageChange.error.isNotBlank()) {
                gui.showErrorToastMsg(getString(R.string.toast_error_USB, storageChange.error))
                audioSession.stopPlayer()
                // TODO: this needs to change to properly support multiple storages
                allStorageIDs.forEach { onStorageLocationRemoved(it) }
                return@add
            }
            when (storageChange.action) {
                StorageAction.ADD -> onStorageLocationAdded(storageChange.id)
                StorageAction.REMOVE -> onStorageLocationRemoved(storageChange.id)
            }
            val allStorageIDsAfter = audioItemLibrary.getAllStorageIDs()
            logger.debug(TAG, "Storage IDs in library after change: $allStorageIDsAfter")
        }
    }

    private fun updateConnectedDevices() {
        try {
            audioFileStorage.updateConnectedDevices()
        } catch (exc: IOException) {
            logger.exception(TAG, "I/O Error during update of connected USB devices", exc)
            gui.showErrorToastMsg(this.getString(R.string.toast_error_USB_init))
        } catch (exc: RuntimeException) {
            logger.exception(TAG, "Runtime error during update of connected USB devices", exc)
            gui.showErrorToastMsg(this.getString(R.string.toast_error_USB_init))
        }
    }

    private fun observePlayerStateChanges() {
        // TODO: clean up the notification handling
        // https://developer.android.com/guide/topics/media-apps/audio-app/building-a-mediabrowserservice#service-lifecycle
        audioSession.observePlayer { stateChange ->
            logger.debug(TAG, "stateChange=$stateChange")
            lastAudioPlayerState = stateChange.state
            when (stateChange.state) {
                AudioPlayerState.STARTED -> {
                    restoreFromPersistentJob?.let {
                        runBlocking(dispatcher) {
                            cancelRestoreFromPersistent()
                        }
                    }
                    if (isServiceStarted) {
                        if (!isServiceForeground) {
                            logger.debug(TAG, "Moving service to foreground")
                            startForeground(NOTIFICATION_ID, audioSession.getNotification())
                            isServiceForeground = true
                        }
                        return@observePlayer
                    }
                    logger.debug(TAG, "Starting foreground service")
                    // this page says to start music player as foreground service
                    // https://developer.android.com/guide/topics/media-apps/audio-app/building-a-mediabrowserservice#mediastyle-notifications
                    // however this FAQ says foreground services are not allowed?
                    // https://developer.android.com/training/cars/media/automotive-os#can_i_use_a_foreground_service
                    // "foreground" is in terms of memory/priority, not in terms of a GUI window
                    startForegroundService(Intent(this, AudioBrowserService::class.java))
                    startForeground(NOTIFICATION_ID, audioSession.getNotification())
                    isServiceForeground = true
                    isServiceStarted = true
                }
                AudioPlayerState.PAUSED -> {
                    logger.debug(TAG, "Moving service to background")
                    stopForeground(false)
                    isServiceForeground = false
                }
                AudioPlayerState.STOPPED -> {
                    stopService()
                }
                AudioPlayerState.ERROR -> {
                    if (stateChange.errorCode == PlaybackStateCompat.ERROR_CODE_END_OF_QUEUE) {
                        // Android Automotive media browser client will show a notification to user by itself in
                        // case of skipping beyond end of queue, no need for us to send a toast message
                        return@observePlayer
                    }
                    gui.showErrorToastMsg(stateChange.errorMsg)
                }
                else -> {
                    // ignore
                }
            }
        }
    }

    @ExperimentalCoroutinesApi
    private fun onStorageLocationAdded(storageID: String) {
        logger.info(TAG, "Storage location was added: $storageID")
        cancelRestoreFromPersistent()
        cancelLoadChildren()
        cancelLibraryCreation()
        if (!Util.isLegalDisclaimerAgreed(this)) {
            logger.info(TAG, "User did not agree to legal disclaimer yet")
            notifyBrowserChildrenChangedAllLevels()
            return
        }
        gui.showIndexingNotification()
        // https://kotlinlang.org/docs/exception-handling.html#coroutineexceptionhandler
        // https://www.lukaslechner.com/why-exception-handling-with-kotlin-coroutines-is-so-hard-and-how-to-successfully-master-it/
        // https://medium.com/android-news/coroutine-in-android-working-with-lifecycle-fc9c1a31e5f3
        // TODO: the error handling is all over the place, need more structure
        val libraryCreationExceptionHandler = CoroutineExceptionHandler { _, exc ->
            notifyLibraryCreationFailure()
            when (exc) {
                is IOException -> {
                    logger.exception(TAG, "I/O exception while building library", exc)
                }
                else -> {
                    logger.exception(TAG, "Exception while building library", exc)
                }
            }
        }
        libraryCreationJob = lifecycleScope.launch(libraryCreationExceptionHandler + dispatcher) {
            createLibraryFromStorages(listOf(storageID))
            libraryCreationJob = null
            gui.showIndexingFinishedNotification()
            if (lastAudioPlayerState in listOf(
                    AudioPlayerState.IDLE, AudioPlayerState.PAUSED, AudioPlayerState.STOPPED
                )
            ) {
                restoreFromPersistentJob = lifecycleScope.launch(dispatcher) {
                    val persistentPlaybackState = persistentStorage.retrieve()
                    try {
                        restoreFromPersistent(persistentPlaybackState)
                    } catch (exc: RuntimeException) {
                        logger.exception(TAG, "Restoring from persistent failed", exc)
                    }
                    restoreFromPersistentJob = null
                }
            } else {
                logger.debug(TAG, "Not restoring from persistent state, user has already requested new item")
            }
        }
    }

    @ExperimentalCoroutinesApi
    private suspend fun createLibraryFromStorages(storageIDs: List<String>) {
        logger.debug(TAG, "createLibraryFromStorages(storageIDs=${storageIDs})")
        val storageLocations: List<AudioFileStorageLocation> = storageIDs.map {
            audioFileStorage.getStorageLocationForID(it)
        }
        // TODO: stop when device detached while this is ongoing
        storageLocations.forEach { audioItemLibrary.initRepository(it.storageID) }
        val audioFileProducerChannel = audioFileStorage.indexStorageLocations(storageIDs)
        audioItemLibrary.buildLibrary(audioFileProducerChannel) { notifyBrowserChildrenChangedAllLevels() }
        storageIDs.forEach {
            audioFileStorage.setIndexingStatus(it, IndexingStatus.COMPLETED)
        }
        logger.info(TAG, "Audio library has been built for storages: $storageIDs")
        notifyBrowserChildrenChangedAllLevels()
        audioFileStorage.notifyIndexingIssues()
    }

    private suspend fun restoreFromPersistent(state: PersistentPlaybackState) {
        if (state.trackID.isBlank()) {
            logger.debug(TAG, "No recent track ID to restore")
            return
        }
        val contentHierarchyIDTrack = ContentHierarchyElement.deserialize(state.trackID)
        if (audioItemLibrary.getRepoForContentHierarchyID(contentHierarchyIDTrack) == null) {
            logger.warning(TAG, "Cannot restore recent track, storage repository mismatch")
            return
        }
        if (state.queueIDs.isEmpty()) {
            logger.warning(TAG, "Found persistent recent track, but no queue items")
            return
        }
        audioSession.prepareFromPersistent(state)
    }

    private fun onStorageLocationRemoved(storageID: String) {
        logger.info(TAG, "Storage location was removed: $storageID")
        if (isShuttingDown) {
            logger.debug(TAG, "Shutdown is in progress")
            return
        }
        cancelRestoreFromPersistent()
        cancelLoadChildren()
        cancelLibraryCreation()
        audioSession.storePlaybackState()
        audioSession.reset()
        if (storageID.isNotBlank()) {
            try {
                val storageLocation = audioFileStorage.getStorageLocationForID(storageID)
                audioItemLibrary.removeRepository(storageLocation.storageID)
            } catch (exc: IllegalArgumentException) {
                logger.warning(TAG, exc.toString())
            }
        }
        notifyBrowserChildrenChangedAllLevels()
        stopService()
    }

    private fun notifyBrowserChildrenChangedAllLevels() {
        notifyChildrenChanged(contentHierarchyTracksRoot)
        notifyChildrenChanged(contentHierarchyAlbumsRoot)
        notifyChildrenChanged(contentHierarchyArtistsRoot)
        notifyChildrenChanged(contentHierarchyRoot)
        if (latestContentHierarchyIDRequested.isNotBlank()
            && latestContentHierarchyIDRequested !in listOf(
                contentHierarchyTracksRoot,
                contentHierarchyAlbumsRoot,
                contentHierarchyArtistsRoot,
                contentHierarchyRoot
            )
        ) {
            notifyChildrenChanged(latestContentHierarchyIDRequested)
        }
    }

    /**
     * See https://developer.android.com/guide/components/services#Lifecycle
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logger.debug(TAG, "onStartCommand(intent=$intent, flags=$flags, startid=$startId)")
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        isServiceStarted = true
        return super.onStartCommand(intent, flags, startId)
    }

    @Suppress("RedundantNullableReturnType")
    /**
     * https://developer.android.com/guide/components/bound-services#Lifecycle
     */
    override fun onBind(intent: Intent?): IBinder? {
        logger.debug(TAG, "onBind()")
        return super.onBind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        logger.debug(TAG, "onUnbind()")
        return super.onUnbind(intent)
    }

    /**
     * This is called when app is swiped away from "recents". It is only called when service was started previously.
     * The "recents" app function does not seem to exist on Android Automotive
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        logger.debug(TAG, "onTaskRemoved()")
        audioSession.shutdown()
        super.onTaskRemoved(rootIntent)
    }

    // TODO: stop service when player errors because USB device unplugged
    private fun stopService() {
        logger.debug(TAG, "Stopping service")
        if (!isServiceStarted) {
            logger.warning(TAG, "Service is not running")
            return
        }
        stopForeground(true)
        isServiceForeground = false
        stopSelf()
        isServiceStarted = false
    }

    override fun onDestroy() {
        // FIXME: this will not close the mediabrowser client GUI, make sure it will still work when service destroyed
        // TODO: check "Stopping service due to app idle", happens sometimes but does not call any method of service?
        logger.debug(TAG, "onDestroy()")
        shutdownAndDestroy()
        unregisterReceiver(powerEventReceiver)
        super.onDestroy()
    }

    @Synchronized
    fun shutdown() {
        if (isShuttingDown) {
            logger.warning(TAG, "Already shutting down")
        }
        isShuttingDown = true
        logger.info(TAG, "shutdown()")
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
            return
        }
        audioFileStorage.cancelIndexing()
        audioItemLibrary.cancelBuildLibrary()
        cancelRestoreFromPersistent()
        cancelLibraryCreation()
        cancelLoadChildren()
        audioSession.storePlaybackState()
        gui.shutdown()
        audioSession.shutdown()
        audioFileStorage.shutdown()
        audioItemLibrary.shutdown()
        stopService()
        isShuttingDown = false
    }

    private fun shutdownAndDestroy() {
        shutdown()
        destroyLifecycleScope()
        // process should be stopped soon afterwards
    }

    fun suspend() {
        logger.info(TAG, "suspend()")
        isSuspended = true
        audioFileStorage.cancelIndexing()
        audioItemLibrary.cancelBuildLibrary()
        cancelRestoreFromPersistent()
        cancelLibraryCreation()
        cancelLoadChildren()
        audioSession.storePlaybackState()
        gui.suspend()
        audioSession.suspend()
        audioFileStorage.suspend()
        audioItemLibrary.suspend()
        stopService()
        logger.info(TAG, "end of suspend() reached")
    }

    fun wakeup() {
        logger.info(TAG, "wakeup()")
        isSuspended = false
        usbDevicePermissions = USBDevicePermissions(this)
        updateConnectedDevices()
    }

    private fun destroyLifecycleScope() {
        // since we use lifecycle scope almost everywhere, this should cancel all pending coroutines
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    private fun cancelLibraryCreation() {
        logger.debug(TAG, "Cancelling audio library creation")
        libraryCreationJob?.cancelChildren()
        logger.debug(TAG, "Cancelled audio library creation")
    }

    private fun cancelRestoreFromPersistent() {
        logger.debug(TAG, "Cancelling restoring from persistent state")
        restoreFromPersistentJob?.cancelChildren()
        logger.debug(TAG, "Cancelled restoring from persistent state")
    }

    private fun cancelLoadChildren() {
        logger.debug(TAG, "Cancelling handling of onLoadChildren()")
        loadChildrenJobs.forEach { (_, job) ->
            job.cancelChildren()
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
        logger.debug(TAG, "onGetRoot($rootHints)")
        if (!packageValidation.isClientValid(clientPackageName, clientUid)) {
            logger.warning(TAG, "Client ${clientPackageName}(${clientUid}) could not be validated for browsing")
            return null
        }
        val maximumRootChildLimit = rootHints?.getInt(MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT, 4)
        logger.debug(TAG, "maximumRootChildLimit=$maximumRootChildLimit")
        val supportedRootChildFlags = rootHints?.getInt(
            MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_SUPPORTED_FLAGS,
            MediaItem.FLAG_BROWSABLE
        )
        logger.debug(TAG, "supportedRootChildFlags=$supportedRootChildFlags")
        val hints = Bundle().apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST_ITEM_HINT_VALUE)
            putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST_ITEM_HINT_VALUE)
            putBoolean(MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED, true)
        }
        return BrowserRoot(contentHierarchyRoot, hints)
    }

    /**
     * Returns list of [MediaItem] depending on the given content hierarchy ID
     * Returning null as result will show no results.
     *
     * The result is sent via Binder RPC to the media browser client process and its GUI, that means it
     * must be limited in size (maximum size of the parcel is 512 kB it seems).
     */
    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        logger.debug(TAG, "onLoadChildren(parentId=$parentId)")
        latestContentHierarchyIDRequested = parentId
        result.detach()
        val jobID = Util.generateUUID()
        val loadChildrenJob = lifecycleScope.launch(dispatcher) {
            try {
                val contentHierarchyID = ContentHierarchyElement.deserialize(parentId)
                val mediaItems: List<MediaItem> = audioItemLibrary.getMediaItemsStartingFrom(contentHierarchyID)
                logger.debug(TAG, "Got ${mediaItems.size} mediaItems in onLoadChildren(parentId=$parentId)")
                result.sendResult(mediaItems.toMutableList())
            } catch (exc: RuntimeException) {
                logger.exception(TAG, exc.message.toString(), exc)
                gui.showErrorToastMsg(getString(R.string.toast_error_unknown))
                result.sendResult(mutableListOf())
            } finally {
                loadChildrenJobs.remove(jobID)
            }
        }
        loadChildrenJobs[jobID] = loadChildrenJob
    }

    override fun onSearch(query: String, extras: Bundle?, result: Result<MutableList<MediaItem>>) {
        logger.debug(TAG, "onSearch(query='$query', extras=$extras)")
        result.detach()
        searchJob = lifecycleScope.launch(dispatcher) {
            val mediaItems: MutableList<MediaItem> = audioItemLibrary.searchMediaItems(query)
            logger.debug(TAG, "Got ${mediaItems.size} mediaItems in onSearch()")
            result.sendResult(mediaItems)
        }
    }

    override fun getLifecycle(): Lifecycle {
        return lifecycleRegistry
    }

    private fun setUncaughtExceptionHandler() {
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // only log uncaught exceptions if we still have a USB device
            if ("(USB_DEVICE_DETACHED|did you unplug)".toRegex().containsMatchIn(throwable.stackTraceToString())) {
                logger.exceptionLogcatOnly(TAG, "Uncaught exception (USB failed)", throwable)
            } else {
                logger.exception(TAG, "Uncaught exception (USB is OK)", throwable)
                logger.flushToUSB()
                audioFileStorage.shutdown()
            }
            stopService()
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
            gui.showErrorToastMsg(getString(R.string.toast_error_library_creation_fail))
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun getAudioItemLibrary(): AudioItemLibrary {
        return audioItemLibrary
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

}
