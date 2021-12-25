/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.player

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.session.MediaButtonReceiver
import de.moleman1024.audiowagon.*
import de.moleman1024.audiowagon.broadcast.*
import de.moleman1024.audiowagon.exceptions.CannotReadFileException
import de.moleman1024.audiowagon.exceptions.DriveAlmostFullException
import de.moleman1024.audiowagon.exceptions.NoItemsInQueueException
import de.moleman1024.audiowagon.filestorage.AudioFile
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import de.moleman1024.audiowagon.log.CrashReporting
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary
import de.moleman1024.audiowagon.medialibrary.AudioItemType
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyElement
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyID
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyType
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.DATABASE_ID_UNKNOWN
import de.moleman1024.audiowagon.medialibrary.descriptionForLog
import de.moleman1024.audiowagon.persistence.PersistentPlaybackState
import de.moleman1024.audiowagon.persistence.PersistentStorage
import kotlinx.coroutines.*
import java.util.concurrent.Executors

private const val TAG = "AudioSession"
private val logger = Logger
const val SESSION_TAG = "AudioSession"
const val PLAYBACK_SPEED: Float = 1.0f
// arbitrary number
const val REQUEST_CODE: Int = 25573
const val ACTION_SHUFFLE_ON = "de.moleman1024.audiowagon.ACTION_SHUFFLE_ON"
const val ACTION_SHUFFLE_OFF = "de.moleman1024.audiowagon.ACTION_SHUFFLE_OFF"
const val ACTION_REPEAT_ON = "de.moleman1024.audiowagon.ACTION_REPEAT_ON"
const val ACTION_REPEAT_OFF = "de.moleman1024.audiowagon.ACTION_REPEAT_OFF"
const val ACTION_EJECT = "de.moleman1024.audiowagon.ACTION_EJECT"
private const val REPLAYGAIN_NOT_FOUND: Float = -99.0f
private const val NUM_BYTES_METADATA = 1024

class AudioSession(
    private val context: Context,
    private val audioItemLibrary: AudioItemLibrary,
    private val audioFileStorage: AudioFileStorage,
    val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val gui: GUI,
    private val persistentStorage: PersistentStorage,
    private val crashReporting: CrashReporting
) {
    private var mediaSession: MediaSessionCompat
    private var audioSessionCallback: AudioSessionCallback
    var sessionToken: MediaSessionCompat.Token
    // See https://developer.android.com/reference/android/support/v4/media/session/PlaybackStateCompat
    private lateinit var playbackState: PlaybackStateCompat
    private var currentQueueItem: MediaSessionCompat.QueueItem? = null
    private val observers = mutableListOf<(Any) -> Unit>()
    // media session must be accessed from same thread always
    private val mediaSessionDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var lastContentHierarchyIDPlayed: String = ""
    private val audioFocus: AudioFocus = AudioFocus(context)
    private val audioPlayer: AudioPlayer = AudioPlayer(audioFileStorage, audioFocus, scope, context, crashReporting)
    private val audioSessionNotifications =
        AudioSessionNotifications(context, scope, dispatcher, audioPlayer, crashReporting)
    private val audioFocusChangeListener: AudioFocusChangeCallback =
        AudioFocusChangeCallback(audioPlayer, scope, dispatcher, crashReporting)
    private var storePersistentJob: Job? = null
    private var onPlayFromMediaIDJob: Job? = null
    private var isFirstOnPlayEventAfterReset: Boolean = true
    private var onPlayCalledBeforeUSBReady: Boolean = false
    private var isShuttingDown: Boolean = false
    private var extractReplayGain: Boolean = false
    private val replayGainRegex = "replaygain_track_gain.*?([-0-9][^ ]+?) ?dB".toRegex(RegexOption.IGNORE_CASE)

    init {
        logger.debug(TAG, "Init AudioSession()")
        isShuttingDown = false
        initAudioFocus()
        // to send play/pause button event "adb shell input keyevent 85"
        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
        mediaButtonIntent.setClass(context, MediaButtonReceiver::class.java)
        // TODO: unclear which is the correct mutability flag to use
        //  https://developer.android.com/reference/android/app/PendingIntent#FLAG_MUTABLE
        val pendingMediaBtnIntent: PendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE, mediaButtonIntent, 0
        )
        audioSessionCallback = AudioSessionCallback(audioPlayer, scope, dispatcher, crashReporting)
        mediaSession = MediaSessionCompat(context, SESSION_TAG).apply {
            setCallback(audioSessionCallback, Handler(Looper.getMainLooper()))
            // TODO: remove queue title? does not seem to be used in AAOS
            setQueueTitle(context.getString(R.string.queue_title))
            setMediaButtonReceiver(pendingMediaBtnIntent)
            // we cannot launch a UI activity in AAOS
            setSessionActivity(null)
            isActive = true
        }
        sessionToken = mediaSession.sessionToken
        initPlaybackState()
        audioSessionNotifications.init(mediaSession)
        extractReplayGain = SharedPrefs.isReplayGainEnabled(context)
        observePlaybackStatus()
        observePlaybackQueue()
        observeSessionCallbacks()
    }

    private fun initAudioFocus() {
        audioFocus.audioFocusChangeListener = audioFocusChangeListener
        try {
            val audioFocusSettingStr = SharedPrefs.getAudioFocusSetting(context)
            audioFocus.setBehaviour(audioFocusSettingStr)
        } catch (exc: IllegalArgumentException) {
            logger.exception(TAG, exc.message.toString(), exc)
        }
    }

    /**
     * See https://developers.google.com/cars/design/automotive-os/apps/media/create-your-app/customize-playback
     */
    private fun initPlaybackState() {
        val actions = createPlaybackActions()
        val customActionShuffleOn = createCustomActionShuffleIsOff()
        val customActionRepeatOn = createCustomActionRepeatIsOff()
        val customActionEject = createCustomActionEject()
        playbackState = PlaybackStateCompat.Builder().setState(
            PlaybackStateCompat.STATE_NONE,
            PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
            PLAYBACK_SPEED
        ).setActions(actions).addCustomAction(customActionEject).addCustomAction(customActionShuffleOn)
            .addCustomAction(customActionRepeatOn).build()
        runBlocking(dispatcher) {
            setMediaSessionPlaybackState(playbackState)
        }
    }

    private fun createPlaybackActions(): Long {
        return PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
                // we do not support PLAY_FROM_URI, but it seems to be needed for Google Assistant?
                PlaybackStateCompat.ACTION_PLAY_FROM_URI or
                PlaybackStateCompat.ACTION_PREPARE or
                PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH or
                PlaybackStateCompat.ACTION_PREPARE_FROM_URI
        // we don't use ACTION_SET_SHUFFLE_MODE and ACTION_SET_REPEAT_MODE here because AAOS does not display them
    }

    /**
     * Create custom actions for shuffle and repeat because AAOS will not display the default Android actions
     */
    private fun createCustomActionShuffleIsOff(): PlaybackStateCompat.CustomAction {
        return PlaybackStateCompat.CustomAction.Builder(
            ACTION_SHUFFLE_ON, context.getString(R.string.action_shuffle_turn_on), R.drawable.baseline_shuffle_24
        ).build()
    }

    private fun createCustomActionShuffleIsOn(): PlaybackStateCompat.CustomAction {
        return PlaybackStateCompat.CustomAction.Builder(
            ACTION_SHUFFLE_OFF, context.getString(R.string.action_shuffle_turn_off), R.drawable.baseline_shuffle_on_24
        ).build()
    }

    private fun createCustomActionRepeatIsOff(): PlaybackStateCompat.CustomAction {
        return PlaybackStateCompat.CustomAction.Builder(
            ACTION_REPEAT_ON, context.getString(R.string.action_repeat_turn_on), R.drawable.baseline_repeat_24
        ).build()
    }

    private fun createCustomActionRepeatIsOn(): PlaybackStateCompat.CustomAction {
        return PlaybackStateCompat.CustomAction.Builder(
            ACTION_REPEAT_OFF, context.getString(R.string.action_repeat_turn_off), R.drawable.baseline_repeat_on_24
        ).build()
    }

    private fun createCustomActionEject(): PlaybackStateCompat.CustomAction {
        return PlaybackStateCompat.CustomAction.Builder(
            ACTION_EJECT, context.getString(R.string.action_eject), R.drawable.baseline_eject_24
        ).build()
    }

    private fun observePlaybackStatus() {
        audioPlayer.playerStatusObservers.clear()
        audioPlayer.playerStatusObservers.add { audioPlayerStatus ->
            logger.debug(TAG, "Playback status changed: $audioPlayerStatus")
            val newPlaybackState: PlaybackStateCompat
            // TODO: check if I need to add the non-changing actions again always or not
            val customActionShuffle =
                if (audioPlayerStatus.isShuffling) createCustomActionShuffleIsOn() else createCustomActionShuffleIsOff()
            val customActionRepeat =
                if (audioPlayerStatus.isRepeating) createCustomActionRepeatIsOn() else createCustomActionRepeatIsOff()
            // TODO: builders should be kept as members for performance reasons, expensive to build
            //  (see https://developer.android.com/guide/topics/media-apps/working-with-a-media-session )
            val playbackStateBuilder = PlaybackStateCompat.Builder().apply {
                setActions(createPlaybackActions())
                addCustomAction(createCustomActionEject())
                addCustomAction(customActionShuffle)
                addCustomAction(customActionRepeat)
                setState(audioPlayerStatus.playbackState, audioPlayerStatus.positionInMilliSec, PLAYBACK_SPEED)
                setActiveQueueItemId(audioPlayerStatus.queueItemID)
            }
            if (audioPlayerStatus.errorCode == 0) {
                newPlaybackState =
                    playbackStateBuilder.setErrorMessage(PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR, "").build()
                if (audioPlayerStatus.playbackState == PlaybackStateCompat.STATE_PLAYING) {
                    launchInScopeSafely("cancelAudioFocusLossJob()") {
                        audioFocusChangeListener.cancelAudioFocusLossJob()
                    }
                }
                if (audioPlayerStatus.hasPlaybackQueueEnded) {
                    val audioPlayerEvt = AudioPlayerEvent(AudioPlayerState.PLAYBACK_COMPLETED)
                    notifyObservers(audioPlayerEvt)
                }
                notifyStartPauseStop(audioPlayerStatus.playbackState)
            } else {
                newPlaybackState = playbackStateBuilder.setErrorMessage(
                    audioPlayerStatus.errorCode, audioPlayerStatus.errorMsg
                ).build()
                val audioPlayerEvt = AudioPlayerEvent(AudioPlayerState.ERROR)
                audioPlayerEvt.errorMsg = audioPlayerStatus.errorMsg
                audioPlayerEvt.errorCode = audioPlayerStatus.errorCode
                notifyObservers(audioPlayerEvt)
                handlePlayerError(audioPlayerStatus.errorCode)
            }
            playbackState = newPlaybackState
            audioFocusChangeListener.playbackState = newPlaybackState.state
            logger.debug(TAG, "newPlaybackState=$newPlaybackState")
            sendNotificationBasedOnPlaybackState()
            launchInScopeSafely("setMediaSessionPlaybackState()")  {
                setMediaSessionPlaybackState(playbackState)
            }
        }
    }

    private fun notifyStartPauseStop(playbackState: Int) {
        val playerEvent: AudioPlayerEvent = when (playbackState) {
            PlaybackStateCompat.STATE_PLAYING -> AudioPlayerEvent(AudioPlayerState.STARTED)
            PlaybackStateCompat.STATE_PAUSED -> AudioPlayerEvent(AudioPlayerState.PAUSED)
            PlaybackStateCompat.STATE_STOPPED -> AudioPlayerEvent(AudioPlayerState.STOPPED)
            else -> {
                return
            }
        }
        notifyObservers(playerEvent)
    }

    private fun handlePlayerError(errorCode: Int) {
        if (errorCode in listOf(
                MediaPlayer.MEDIA_ERROR_MALFORMED,
                MediaPlayer.MEDIA_ERROR_TIMED_OUT,
                MediaPlayer.MEDIA_ERROR_UNSUPPORTED,
                PlaybackStateCompat.ERROR_CODE_END_OF_QUEUE
            )
        ) {
            // no need to recover from this error
            return
        }
        if (errorCode in listOf(MEDIA_ERROR_INVALID_STATE)) {
            recoverFromError()
            return
        }
        // I/O errors or device unplugged
        logger.warning(TAG, "I/O error or USB device unplugged, recovering")
        initPlaybackState()
        launchInScopeSafely("reInitAfterError()") {
            audioPlayer.reInitAfterError()
        }
    }

    private fun observePlaybackQueue() {
        audioPlayer.clearPlaybackQueueObservers()
        audioPlayer.addPlaybackQueueObserver { queueChange ->
            logger.debug(TAG, "queueChange=$queueChange")
            val contentHierarchyIDStr: String? = queueChange.currentItem?.description?.mediaId
            logger.debug(TAG, "Content hierarchy ID to change to: $contentHierarchyIDStr")
            logger.flushToUSB()
            launchInScopeSafely("observePlaybackQueue()") {
                if (contentHierarchyIDStr != null && contentHierarchyIDStr.isNotBlank()) {
                    val contentHierarchyID = ContentHierarchyElement.deserialize(contentHierarchyIDStr)
                    val audioItem: AudioItem
                    when (contentHierarchyID.type) {
                        ContentHierarchyType.TRACK -> {
                            logger.debug(TAG, "getAudioItemForTrack($contentHierarchyID)")
                            audioItem = audioItemLibrary.getAudioItemForTrack(contentHierarchyID)
                        }
                        ContentHierarchyType.FILE -> {
                            var storageID = contentHierarchyID.storageID
                            if (storageID.isBlank()) {
                                storageID = audioFileStorage.getPrimaryStorageLocation().storageID
                            }
                            contentHierarchyID.storageID = storageID
                            val audioFile = AudioFile(Util.createURIForPath(storageID, contentHierarchyID.path))
                            // If we are playing files directly, extract the metadata here. This will slow down the
                            // start of playback but without it we will not be able to show the duration of file,
                            // album art etc.
                            audioItem = audioItemLibrary.extractMetadataFrom(audioFile)
                            audioItem.id = ContentHierarchyElement.serialize(contentHierarchyID)
                            audioItem.uri = audioFile.uri
                        }
                        else ->  {
                            throw IllegalArgumentException("Invalid content hierarchy: $contentHierarchyID")
                        }
                    }
                    val metadata: MediaMetadataCompat = audioItemLibrary.createMetadataForItem(audioItem)
                    extractAndSetReplayGain(audioItem)
                    setCurrentQueueItem(
                        MediaSessionCompat.QueueItem(
                            metadata.description, queueChange.currentItem.queueId
                        )
                    )
                    setMediaSessionMetadata(metadata)
                } else {
                    setCurrentQueueItem(null)
                    setMediaSessionMetadata(null)
                }
                setMediaSessionQueue(queueChange.items)
                sendNotificationBasedOnPlaybackState()
            }
        }
    }

    private suspend fun extractAndSetReplayGain(audioItem: AudioItem) {
        if (!extractReplayGain) {
            return
        }
        val replayGain: Float
        try {
            replayGain = extractReplayGain(audioItem.uri)
            logger.debug(TAG, "Setting ReplayGain: $replayGain dB")
            audioPlayer.setReplayGain(replayGain)
        } catch (exc: Exception) {
            logger.exception(TAG, exc.message.toString(), exc)
        }
    }

    // TODO: move this elsewhere
    private fun extractReplayGain(uri: Uri): Float {
        logger.debug(TAG, "extractReplayGain($uri)")
        var replayGain: Float
        val dataSource = audioFileStorage.getDataSourceForURI(uri)
        val dataFront = ByteArray(NUM_BYTES_METADATA)
        // IDv3 tags are at the beginning of the file
        dataSource.readAt(0L, dataFront, 0, dataFront.size)
        replayGain = findReplayGainInBytes(dataFront)
        if (replayGain != REPLAYGAIN_NOT_FOUND) {
            dataSource.close()
            return replayGain
        }
        val dataBack = ByteArray(NUM_BYTES_METADATA)
        // APE tags are at the end of the file
        dataSource.readAt(dataSource.size - dataBack.size, dataBack, 0, dataBack.size)
        replayGain = findReplayGainInBytes(dataBack)
        if (replayGain == REPLAYGAIN_NOT_FOUND) {
            replayGain = 0f
        }
        dataSource.close()
        return replayGain
    }

    private fun findReplayGainInBytes(bytes: ByteArray): Float {
        val bytesStr = String(bytes)
        var replayGain = REPLAYGAIN_NOT_FOUND
        val replayGainMatch = replayGainRegex.find(bytesStr)
        if (replayGainMatch?.groupValues?.size == 2) {
            val replayGainStr = replayGainMatch.groupValues[1].trim()
            try {
                replayGain = replayGainStr.toFloat()
            } catch (exc: NumberFormatException) {
                return REPLAYGAIN_NOT_FOUND
            }
        }
        return replayGain
    }

    private fun sendNotificationBasedOnPlaybackState() {
        when {
            playbackState.isPlaying -> audioSessionNotifications.sendIsPlayingNotification()
            playbackState.isPaused -> audioSessionNotifications.sendIsPausedNotification()
        }
    }

    suspend fun playAnything() {
        logger.debug(TAG, "playAnything()")
        if (currentQueueItem != null) {
            if (playbackState.isStopped) {
                val queueIndex = audioPlayer.getPlaybackQueueIndex()
                audioPlayer.preparePlayFromQueue(queueIndex, playbackState.position.toInt())
            }
            audioPlayer.start()
        } else {
            val contentHierarchyIDShuffleAll = ContentHierarchyID(ContentHierarchyType.SHUFFLE_ALL_TRACKS)
            val shuffledItems = audioItemLibrary.getAudioItemsStartingFrom(contentHierarchyIDShuffleAll)
            createQueueAndPlay(shuffledItems)
        }
    }

    // https://developer.android.com/reference/androidx/media/session/MediaButtonReceiver
    fun handleMediaButtonIntent(intent: Intent) {
        logger.debug(TAG, "Handling media button intent")
        MediaButtonReceiver.handleIntent(mediaSession, intent)
    }

    fun stopPlayer() {
        logger.debug(TAG, "stopPlayer()")
        runBlocking(dispatcher) {
            audioPlayer.stop()
        }
    }

    fun shutdown() {
        logger.debug(TAG, "shutdown()")
        if (isShuttingDown) {
            return
        }
        isShuttingDown = true
        clearSession()
        runBlocking(dispatcher) {
            onPlayFromMediaIDJob?.cancelAndJoin()
            audioFocusChangeListener.cancelAudioFocusLossJob()
            audioPlayer.shutdown()
            audioFocus.release()
            releaseMediaSession()
        }
        audioSessionNotifications.shutdown()
    }

    fun suspend() {
        logger.debug(TAG, "suspend()")
        runBlocking(dispatcher) {
            audioPlayer.pause()
            audioFocus.release()
            onPlayFromMediaIDJob?.cancelAndJoin()
            audioFocusChangeListener.cancelAudioFocusLossJob()
        }
        setCurrentQueueItem(null)
        audioSessionNotifications.sendEmptyNotification()
        clearPlaybackState()
        runBlocking(dispatcher) {
            setMediaSessionQueue(listOf())
            setMediaSessionPlaybackState(playbackState)
        }
    }

    fun reset() {
        logger.debug(TAG, "reset()")
        isFirstOnPlayEventAfterReset = true
        runBlocking(dispatcher) {
            audioPlayer.reset()
        }
        clearSession()
    }

    private fun clearSession() {
        logger.debug(TAG, "clearSession()")
        runBlocking(dispatcher) {
            audioFocusChangeListener.cancelAudioFocusLossJob()
        }
        setCurrentQueueItem(null)
        audioSessionNotifications.sendEmptyNotification()
        clearPlaybackState()
        runBlocking(dispatcher) {
            clearMediaSession()
        }
        audioSessionNotifications.removeNotification()
    }

    private fun observeSessionCallbacks() {
        audioSessionCallback.observers.clear()
        audioSessionCallback.observers.add { audioSessionChange ->
            logger.debug(TAG, "audioSessionChange=$audioSessionChange")
            when (audioSessionChange.type) {
                AudioSessionChangeType.ON_PLAY -> {
                    audioFocusChangeListener.lastUserRequestedStateChange = audioSessionChange.type
                    handleOnPlay()
                }
                AudioSessionChangeType.ON_PLAY_FROM_MEDIA_ID -> {
                    audioFocusChangeListener.lastUserRequestedStateChange = AudioSessionChangeType.ON_PLAY
                    runBlocking(dispatcher) {
                        onPlayFromMediaIDJob?.cancelAndJoin()
                    }
                    onPlayFromMediaIDJob = launchInScopeSafely(audioSessionChange.type.name) {
                        playFromContentHierarchyID(audioSessionChange.contentHierarchyID)
                        onPlayFromMediaIDJob = null
                    }
                }
                AudioSessionChangeType.ON_SKIP_TO_QUEUE_ITEM -> {
                    launchInScopeSafely(audioSessionChange.type.name) {
                        audioPlayer.playFromQueueID(audioSessionChange.queueID)
                    }
                }
                AudioSessionChangeType.ON_STOP -> {
                    audioFocusChangeListener.lastUserRequestedStateChange = audioSessionChange.type
                    launchInScopeSafely(audioSessionChange.type.name) {
                        storePlaybackState()
                        // TODO: release some more things in audiosession/player, e.g. queue/index in queue?
                    }
                }
                AudioSessionChangeType.ON_ENABLE_LOG_TO_USB -> {
                    launchInScopeSafely(audioSessionChange.type.name) {
                        audioFileStorage.enableLogToUSB()
                    }
                }
                AudioSessionChangeType.ON_DISABLE_LOG_TO_USB -> {
                    launchInScopeSafely(audioSessionChange.type.name) {
                        audioFileStorage.disableLogToUSB()
                    }
                }
                AudioSessionChangeType.ON_ENABLE_CRASH_REPORTING -> {
                    launchInScopeSafely(audioSessionChange.type.name) {
                        crashReporting.enable()
                    }
                }
                AudioSessionChangeType.ON_DISABLE_CRASH_REPORTING -> {
                    launchInScopeSafely(audioSessionChange.type.name) {
                        crashReporting.disable()
                    }
                }
                AudioSessionChangeType.ON_ENABLE_EQUALIZER -> {
                    launchInScopeSafely(audioSessionChange.type.name) {
                        audioPlayer.enableEqualizer()
                    }
                }
                AudioSessionChangeType.ON_DISABLE_EQUALIZER -> {
                    launchInScopeSafely(audioSessionChange.type.name) {
                        audioPlayer.disableEqualizer()
                    }
                }
                AudioSessionChangeType.ON_ENABLE_REPLAYGAIN -> {
                    extractReplayGain = true
                    launchInScopeSafely(audioSessionChange.type.name) {
                        val currentTrackID = getCurrentTrackID()
                        if (currentTrackID.isNotBlank()) {
                            val contentHierarchyID = ContentHierarchyElement.deserialize(currentTrackID)
                            val audioItem = when (contentHierarchyID.type) {
                                ContentHierarchyType.TRACK -> {
                                    logger.debug(TAG, "getAudioItemForTrack($contentHierarchyID)")
                                    audioItemLibrary.getAudioItemForTrack(contentHierarchyID)
                                }
                                ContentHierarchyType.FILE -> {
                                    logger.debug(TAG, "getAudioItemForFile($contentHierarchyID)")
                                    audioItemLibrary.getAudioItemForFile(contentHierarchyID)
                                }
                                else -> {
                                    logger.error(TAG, "Cannot handle type: $contentHierarchyID")
                                    return@launchInScopeSafely
                                }
                            }
                            extractAndSetReplayGain(audioItem)
                        }
                        audioPlayer.enableReplayGain()
                    }
                }
                AudioSessionChangeType.ON_DISABLE_REPLAYGAIN -> {
                    extractReplayGain = false
                    launchInScopeSafely(audioSessionChange.type.name) {
                        audioPlayer.disableReplayGain()
                    }
                }
                AudioSessionChangeType.ON_SET_EQUALIZER_PRESET -> {
                    launchInScopeSafely(audioSessionChange.type.name) {
                        val equalizerPreset = EqualizerPreset.valueOf(audioSessionChange.equalizerPreset)
                        audioPlayer.setEqualizerPreset(equalizerPreset)
                    }
                }
                AudioSessionChangeType.ON_SET_METADATAREAD_SETTING -> {
                    launchInScopeSafely(audioSessionChange.type.name) {
                        notifyObservers(
                            SettingChangeEvent(SettingKey.READ_METADATA_SETTING, audioSessionChange.metadataReadSetting)
                        )
                    }
                }
                AudioSessionChangeType.ON_READ_METADATA_NOW -> {
                    launchInScopeSafely(audioSessionChange.type.name) {
                        notifyObservers(SettingChangeEvent(SettingKey.READ_METADATA_NOW))
                    }
                }
                AudioSessionChangeType.ON_SET_AUDIOFOCUS_SETTING -> {
                    launchInScopeSafely(audioSessionChange.type.name) {
                        audioPlayer.pause()
                        audioFocus.setBehaviour(audioSessionChange.audioFocusSetting)
                    }
                }
                AudioSessionChangeType.ON_EJECT -> {
                    audioFocusChangeListener.lastUserRequestedStateChange = AudioSessionChangeType.ON_STOP
                    launchInScopeSafely(audioSessionChange.type.name) {
                        notifyObservers(CustomActionEvent(CustomAction.EJECT))
                        storePlaybackState()
                        audioPlayer.prepareForEject()
                        audioFileStorage.disableLogToUSB()
                        audioFileStorage.removeAllDevicesFromStorage()
                        gui.showToastMsg(context.getString(R.string.toast_eject_completed))
                    }
                }
                AudioSessionChangeType.ON_PLAY_FROM_SEARCH -> {
                    audioFocusChangeListener.lastUserRequestedStateChange = AudioSessionChangeType.ON_PLAY
                    launchInScopeSafely(audioSessionChange.type.name) {
                        playFromSearch(audioSessionChange)
                    }
                }
                AudioSessionChangeType.ON_PAUSE -> {
                    audioFocusChangeListener.lastUserRequestedStateChange = audioSessionChange.type
                }
            }
        }
    }

    private fun handleOnPlay() {
        launchInScopeSafely("handleOnPlay()") {
            if (isFirstOnPlayEventAfterReset) {
                isFirstOnPlayEventAfterReset = false
                if (audioPlayer.isIdle() || audioPlayer.isPlaybackQueueEmpty()) {
                    // The user probably just started the car and the car is trying to resume playback. This
                    // is a feature of e.g. Polestar 2 with software >= P2127. However this event will come too
                    // early, before the USB drive permission is granted. Latch this onPlay() here and perform it
                    // later.
                    // If the user tried to start the playback haptically the playback queue would not be
                    // empty and the media player should be prepared already as well.
                    onPlayCalledBeforeUSBReady = true
                    logger.debug(TAG, "Storing onPlay() call for later")
                    return@launchInScopeSafely
                }
            }
            try {
                if (playbackState.isStopped) {
                    val queueIndex = audioPlayer.getPlaybackQueueIndex()
                    audioPlayer.preparePlayFromQueue(queueIndex, playbackState.position.toInt())
                }
                audioPlayer.start()
            } catch (exc: Exception) {
                logger.exception(TAG, "Exception in handleOnPlay()", exc)
                when (exc) {
                    is NoItemsInQueueException -> gui.showErrorToastMsg(context.getString(R.string.toast_error_no_tracks))
                    is DriveAlmostFullException -> {
                        logger.exceptionLogcatOnly(TAG, "Drive is almost full, cannot log to USB", exc)
                        gui.showErrorToastMsg(context.getString(R.string.toast_error_not_enough_space_for_log))
                    }
                    else -> {
                        // no special exception handling
                    }
                }
            }
        }
    }

    private fun recoverFromError() {
        logger.debug(TAG, "recoverFromError()")
        launchInScopeSafely("recoverFromError()") {
            // TODO: naming
            audioPlayer.prepareForEject()
        }
    }

    private suspend fun playFromSearch(change: AudioSessionChange) {
        logger.debug(TAG, "playFromSearch(audioSessionChange=$change)")
        var audioItems: List<AudioItem> = listOf()
        // We show the search query to the user so that hopefully they will adjust their voice input if something does
        // not work
        var searchQueryForGUI: String = change.queryToPlay
        // "track on album by artist" is not supported
        when (change.queryFocus) {
            AudioItemType.TRACK -> {
                if (change.trackToPlay.isNotBlank() && change.artistToPlay.isNotBlank()) {
                    searchQueryForGUI = "${change.trackToPlay} + ${change.artistToPlay}"
                    audioItems = audioItemLibrary.searchTrackByArtist(change.trackToPlay, change.artistToPlay)
                } else if (change.trackToPlay.isNotBlank() && change.albumToPlay.isNotBlank()) {
                    searchQueryForGUI = "${change.trackToPlay} + ${change.albumToPlay}"
                    audioItems = audioItemLibrary.searchTrackByAlbum(change.trackToPlay, change.albumToPlay)
                } else if (change.trackToPlay.isNotBlank()) {
                    searchQueryForGUI = change.trackToPlay
                    audioItems = audioItemLibrary.searchTracks(change.trackToPlay)
                }
            }
            AudioItemType.ALBUM -> {
                if (change.albumToPlay.isNotBlank() && change.artistToPlay.isNotBlank()) {
                    searchQueryForGUI = "${change.albumToPlay} + ${change.artistToPlay}"
                    audioItems = audioItemLibrary.searchAlbumByArtist(change.albumToPlay, change.artistToPlay)
                } else if (change.albumToPlay.isNotBlank()) {
                    searchQueryForGUI = change.albumToPlay
                    audioItems = audioItemLibrary.searchAlbums(change.albumToPlay)
                }
            }
            AudioItemType.ARTIST -> {
                if (change.artistToPlay.isNotBlank()) {
                    searchQueryForGUI = change.artistToPlay
                    audioItems = audioItemLibrary.searchArtists(change.artistToPlay)
                }
            }
            AudioItemType.UNSPECIFIC -> {
                // handled below
            }
        }
        if (audioItems.isNotEmpty()) {
            gui.showToastMsg(context.getString(R.string.toast_searching_voice_input, searchQueryForGUI))
            createQueueAndPlay(audioItems)
            return
        }
        // if the more specific fields above did not yield any results, try searching using the raw query
        if (change.queryToPlay.isNotBlank()) {
            searchQueryForGUI = change.queryToPlay
            gui.showToastMsg(context.getString(R.string.toast_searching_voice_input, searchQueryForGUI))
            audioItems = audioItemLibrary.searchUnspecific(change.queryToPlay)
            createQueueAndPlay(audioItems)
        } else {
            // User spoke sth like "play music", query text will be empty
            // see https://developer.android.com/guide/topics/media-apps/interacting-with-assistant#empty-queries
            playAnything()
        }
    }

    private suspend fun playFromContentHierarchyID(contentHierarchyIDStr: String) {
        val contentHierarchyID = ContentHierarchyElement.deserialize(contentHierarchyIDStr)
        logger.debug(TAG, "playFromContentHierarchyID($contentHierarchyID)")
        val audioItems: List<AudioItem> = audioItemLibrary.getAudioItemsStartingFrom(contentHierarchyID)
        lastContentHierarchyIDPlayed = contentHierarchyIDStr
        var startIndex = 0
        when (contentHierarchyID.type) {
            ContentHierarchyType.TRACK -> {
                startIndex = audioItems.indexOfFirst {
                    ContentHierarchyElement.deserialize(it.id).trackID == contentHierarchyID.trackID
                }
                if (contentHierarchyID.artistID <= DATABASE_ID_UNKNOWN
                    && contentHierarchyID.albumID <= DATABASE_ID_UNKNOWN
                ) {
                    // User tapped on an entry in track view, we are playing a random selection of tracks afterwards.
                    // Treat this the same as SHUFFLE_ALL_TRACKS
                    lastContentHierarchyIDPlayed = ContentHierarchyElement.serialize(
                        ContentHierarchyID(ContentHierarchyType.SHUFFLE_ALL_TRACKS)
                    )
                }
            }
            ContentHierarchyType.FILE -> {
                startIndex = audioItems.indexOfFirst {
                    ContentHierarchyElement.deserialize(it.id).path == contentHierarchyID.path
                }
            }
            ContentHierarchyType.ROOT -> {
                if (currentQueueItem != null) {
                    playAnything()
                    return
                }
            }
            else -> {
                logger.warning(TAG, "Ignoring playFromContentHierarchyID($contentHierarchyID)")
            }
        }
        if (startIndex < 0) {
            startIndex = 0
        }
        createQueueAndPlay(audioItems, startIndex)
    }

    private suspend fun createQueueAndPlay(audioItems: List<AudioItem>, startIndex: Int = 0) {
        // see https://developer.android.com/reference/android/media/session/MediaSession#setQueue(java.util.List%3Candroid.media.session.MediaSession.QueueItem%3E)
        // TODO: check how many is "too many" tracks and use sliding window instead
        val queue: MutableList<MediaSessionCompat.QueueItem> = mutableListOf()
        for ((queueIndex, audioItem) in audioItems.withIndex()) {
            val description = audioItemLibrary.createAudioItemDescription(audioItem)
            val queueItem = MediaSessionCompat.QueueItem(description, queueIndex.toLong())
            queue.add(queueItem)
        }
        audioPlayer.setPlayQueueAndNotify(queue, startIndex)
        audioPlayer.maybeShuffleQueue()
        setMediaSessionQueue(queue)
        try {
            audioPlayer.startPlayFromQueue()
        } catch (exc: NoItemsInQueueException) {
            logger.warning(TAG, "No items in play queue")
        }
    }

    suspend fun prepareFromPersistent(state: PersistentPlaybackState) {
        logger.debug(TAG, "prepareFromPersistent($state)")
        val queue: MutableList<MediaSessionCompat.QueueItem> = mutableListOf()
        for ((index, contentHierarchyIDStr) in state.queueIDs.withIndex()) {
            if (isShuttingDown) {
                logger.debug(TAG, "Stopping prepareFromPersistent() because of shutdown")
                return
            }
            try {
                scope.ensureActive()
                val contentHierarchyID = ContentHierarchyElement.deserialize(contentHierarchyIDStr)
                // TODO: same lines at 470
                val audioItem = when (contentHierarchyID.type) {
                    ContentHierarchyType.TRACK -> {
                        audioItemLibrary.getAudioItemForTrack(contentHierarchyID)
                    }
                    ContentHierarchyType.FILE -> {
                        audioItemLibrary.getAudioItemForFile(contentHierarchyID)
                    }
                    else -> {
                        throw IllegalArgumentException("Unhandled content hiearchy type: $contentHierarchyID")
                    }
                }
                val description = audioItemLibrary.createAudioItemDescription(audioItem)
                val queueItem = MediaSessionCompat.QueueItem(description, index.toLong())
                queue.add(queueItem)
            } catch (exc: IllegalArgumentException) {
                logger.warning(TAG, "Issue with persistent content hierarchy ID: $contentHierarchyIDStr: $exc")
            } catch (exc: CancellationException) {
                logger.warning(TAG, "Preparation from persistent has been cancelled")
                return
            } catch (exc: RuntimeException) {
                logger.exception(TAG, "Issue with persistent data", exc)
            }
        }
        if (queue.size <= 0) {
            return
        }
        if (state.lastContentHierarchyID.isNotBlank()) {
            val lastContentHierarchyID = ContentHierarchyElement.deserialize(state.lastContentHierarchyID)
            if (lastContentHierarchyID.type == ContentHierarchyType.SHUFFLE_ALL_TRACKS) {
                // In this case we restored only the last playing track/file from persistent storage above. Now we
                // create a new shuffled playback queue for all remaining items
                val shuffledItems = audioItemLibrary.getAudioItemsStartingFrom(lastContentHierarchyID)
                for ((queueIndex, audioItem) in shuffledItems.withIndex()) {
                    if (audioItem.id == queue[0].description.mediaId) {
                        continue
                    }
                    val description = audioItemLibrary.createAudioItemDescription(audioItem)
                    val queueItem = MediaSessionCompat.QueueItem(description, queueIndex.toLong() + 1L)
                    queue.add(queueItem)
                }
            }
        }
        audioPlayer.setShuffle(state.isShuffling)
        audioPlayer.setRepeat(state.isRepeating)
        audioPlayer.setPlayQueue(queue)
        setMediaSessionQueue(queue)
        try {
            audioPlayer.preparePlayFromQueue(state.queueIndex, state.trackPositionMS.toInt())
            // TODO: not nice (same player status might have been sent shortly before)
            audioPlayer.notifyPlayerStatusChange()
        } catch (exc: NoItemsInQueueException) {
            logger.warning(TAG, "No items in play queue")
        }
        if (onPlayCalledBeforeUSBReady) {
            onPlayCalledBeforeUSBReady = false
            logger.debug(TAG, "Starting playback that was requested during startup before")
            audioPlayer.start()
        }
    }

    suspend fun cleanPersistent() {
        logger.debug(TAG, "cleanPersistent()")
        persistentStorage.clean()
    }

    private fun notifyObservers(event: Any) {
        observers.forEach { it(event) }
    }

    fun observe(func: (Any) -> Unit) {
        observers.add(func)
    }

    private suspend fun setMediaSessionQueue(queue: List<MediaSessionCompat.QueueItem>) {
        withContext(mediaSessionDispatcher) {
            mediaSession.setQueue(queue)
        }
    }

    private suspend fun setMediaSessionPlaybackState(state: PlaybackStateCompat) {
        withContext(mediaSessionDispatcher) {
            mediaSession.setPlaybackState(state)
        }
    }

    private suspend fun setMediaSessionMetadata(metadata: MediaMetadataCompat?) {
        withContext(mediaSessionDispatcher) {
            logger.debug(TAG, "Setting media session metadata: ${metadata?.descriptionForLog}")
            // TODO: does this scale the album art bitmap by itself?
            mediaSession.setMetadata(metadata)
        }
    }

    private suspend fun releaseMediaSession() {
        withContext(mediaSessionDispatcher) {
            mediaSession.isActive = false
            mediaSession.release()
        }
    }

    private fun clearPlaybackState() {
        playbackState = PlaybackStateCompat.Builder().setState(
            PlaybackStateCompat.STATE_NONE,
            PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
            PLAYBACK_SPEED
        ).build()
        audioFocusChangeListener.playbackState = playbackState.state
    }

    private suspend fun clearMediaSession() {
        withContext(mediaSessionDispatcher) {
            setMediaSessionQueue(listOf())
            setMediaSessionMetadata(null)
            setMediaSessionPlaybackState(playbackState)
        }
    }

    fun storePlaybackState() {
        logger.debug(TAG, "storePlaybackState()")
        val currentTrackID = getCurrentTrackID()
        if (currentTrackID.isBlank()) {
            logger.debug(TAG, "No current track to store")
            return
        }
        val currentTrackPosMS = getCurrentTrackPosMS()
        val playbackStateToPersist = PersistentPlaybackState(currentTrackID)
        playbackStateToPersist.trackPositionMS = currentTrackPosMS
        val queueIDs = getQueueIDs()
        var queueIndex = getQueueIndex()
        if (queueIndex < 0) {
            queueIndex = 0
        }
        // We only store playback queue items that are upcoming. Previous items are discarded to save some storage space
        playbackStateToPersist.queueIDs = queueIDs.subList(queueIndex, queueIDs.size)
        playbackStateToPersist.queueIndex = 0
        playbackStateToPersist.isShuffling = isShuffling()
        playbackStateToPersist.isRepeating = isRepeating()
        playbackStateToPersist.lastContentHierarchyID = lastContentHierarchyIDPlayed
        if (lastContentHierarchyIDPlayed.isNotBlank()) {
            val lastContentHierarchyID = ContentHierarchyElement.deserialize(lastContentHierarchyIDPlayed)
            if (lastContentHierarchyID.type == ContentHierarchyType.SHUFFLE_ALL_TRACKS) {
                // When the user is shuffling all tracks, we don't store the queue, we will recreate a new shuffled queue
                // when restoring from persistent state instead (to save storage space)
                playbackStateToPersist.queueIDs = queueIDs.subList(queueIndex, queueIndex + 1)
            }
        }
        runBlocking(dispatcher) {
            storePersistentJob?.cancelAndJoin()
        }
        storePersistentJob = launchInScopeSafely("storePlaybackState()") {
            persistentStorage.store(playbackStateToPersist)
            storePersistentJob = null
        }
    }

    private fun getCurrentTrackID(): String {
        return currentQueueItem?.description?.mediaId ?: ""
    }

    private fun getCurrentTrackPosMS(): Long {
        var currentPosMS: Long
        runBlocking(dispatcher) {
            currentPosMS = audioPlayer.getCurrentPositionMilliSec()
        }
        return currentPosMS
    }

    private fun isShuffling(): Boolean = runBlocking(dispatcher) {
        return@runBlocking audioPlayer.isShuffling()
    }

    private fun isRepeating(): Boolean = runBlocking(dispatcher) {
        return@runBlocking audioPlayer.isRepeating()
    }

    private fun getQueueIDs(): List<String> {
        var queueIDs: List<String>
        runBlocking(dispatcher) {
            queueIDs = audioPlayer.getPlaybackQueueIDs()
        }
        return queueIDs
    }

    private fun getQueueIndex(): Int {
        var queueIndex: Int
        runBlocking(dispatcher) {
            queueIndex = audioPlayer.getPlaybackQueueIndex()
        }
        return queueIndex
    }

    private fun setCurrentQueueItem(item: MediaSessionCompat.QueueItem?) {
        currentQueueItem = item
        audioSessionNotifications.currentQueueItem = item
    }

    private fun launchInScopeSafely(message: String, func: suspend () -> Unit): Job {
        val exceptionHandler = CoroutineExceptionHandler { coroutineContext, exc ->
            handleException("$message ($coroutineContext threw ${exc.message})", exc)
        }
        return scope.launch(exceptionHandler + dispatcher) {
            try {
                func()
            } catch (exc: Exception) {
                handleException("$message (${exc.message})", exc)
            }
        }
    }

    private fun handleException(msg: String, exc: Throwable) {
        when (exc) {
            is NoItemsInQueueException -> gui.showErrorToastMsg(context.getString(R.string.toast_error_no_tracks))
            is DriveAlmostFullException -> {
                logger.exceptionLogcatOnly(TAG, "Drive is almost full, cannot log to USB", exc)
                gui.showErrorToastMsg(context.getString(R.string.toast_error_not_enough_space_for_log))
            }
            is CancellationException -> {
                logger.warning(TAG, exc.message.toString())
            }
            is CannotReadFileException -> {
                gui.showErrorToastMsg(context.getString(R.string.toast_error_cannot_read_file, exc.fileName))
                crashReporting.logMessages(logger.getLastLogLines(NUM_LOG_LINES_CRASH_REPORT))
                crashReporting.logMessage(msg)
                crashReporting.recordException(exc)
                logger.exception(TAG, msg, exc)
            }
            else -> {
                crashReporting.logMessages(logger.getLastLogLines(NUM_LOG_LINES_CRASH_REPORT))
                crashReporting.logMessage(msg)
                crashReporting.recordException(exc)
                logger.exception(TAG, msg, exc)
            }
        }
    }

    fun getNotification(): Notification {
        return audioSessionNotifications.getNotification()
    }

    // TODO: split file, too long

}
