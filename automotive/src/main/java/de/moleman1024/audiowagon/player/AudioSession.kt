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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.annotation.VisibleForTesting
import androidx.media.session.MediaButtonReceiver
import de.moleman1024.audiowagon.*
import de.moleman1024.audiowagon.enums.AudioItemType
import de.moleman1024.audiowagon.enums.AudioPlayerState
import de.moleman1024.audiowagon.enums.AudioSessionChangeType
import de.moleman1024.audiowagon.enums.CustomAction
import de.moleman1024.audiowagon.enums.EqualizerPreset
import de.moleman1024.audiowagon.enums.SettingKey
import de.moleman1024.audiowagon.enums.SingletonCoroutineBehaviour
import de.moleman1024.audiowagon.exceptions.*
import de.moleman1024.audiowagon.filestorage.data.AudioFile
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import de.moleman1024.audiowagon.log.CrashReporting
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.*
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyElement
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyID
import de.moleman1024.audiowagon.enums.ContentHierarchyType
import de.moleman1024.audiowagon.enums.RepeatMode
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.DATABASE_ID_UNKNOWN
import de.moleman1024.audiowagon.persistence.PersistentPlaybackState
import de.moleman1024.audiowagon.persistence.PersistentStorage
import de.moleman1024.audiowagon.player.data.AudioPlayerEvent
import de.moleman1024.audiowagon.player.data.AudioPlayerStatus
import de.moleman1024.audiowagon.player.data.AudioSessionChange
import de.moleman1024.audiowagon.player.data.CustomActionEvent
import kotlinx.coroutines.*
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

private const val TAG = "AudioSession"
private val logger = Logger
const val SESSION_TAG = "AudioSession"
const val PLAYBACK_SPEED: Float = 1.0f

// arbitrary number
const val REQUEST_CODE: Int = 25573
const val ACTION_SHUFFLE_ON = "de.moleman1024.audiowagon.ACTION_SHUFFLE_ON"
const val ACTION_SHUFFLE_OFF = "de.moleman1024.audiowagon.ACTION_SHUFFLE_OFF"
const val ACTION_REPEAT_ALL_ON = "de.moleman1024.audiowagon.ACTION_REPEAT_ALL_ON"
const val ACTION_REPEAT_ONE_ON = "de.moleman1024.audiowagon.ACTION_REPEAT_ONE_ON"
const val ACTION_REPEAT_OFF = "de.moleman1024.audiowagon.ACTION_REPEAT_OFF"
const val ACTION_EJECT = "de.moleman1024.audiowagon.ACTION_EJECT"
const val ACTION_REWIND_10 = "de.moleman1024.audiowagon.ACTION_REWIND_10"

/**
 * This class is used to handle the current high-level session state of the [AudioPlayer] and handle events from media
 * session callbacks that will trigger e.g. playback queue creation and playback start of items coming from
 * [AudioItemLibrary]
 */
@ExperimentalCoroutinesApi
class AudioSession(
    private val context: Context,
    private val audioItemLibrary: AudioItemLibrary,
    private val audioFileStorage: AudioFileStorage,
    val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val persistentStorage: PersistentStorage,
    private val crashReporting: CrashReporting,
    private val sharedPrefs: SharedPrefs
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
    private val audioPlayer: AudioPlayer = AudioPlayer(
        audioFileStorage, audioFocus, scope, context, crashReporting, sharedPrefs
    )
    private val audioSessionNotifications =
        AudioSessionNotifications(context, scope, dispatcher, audioPlayer, crashReporting)
    private val audioFocusChangeListener: AudioFocusChangeCallback =
        AudioFocusChangeCallback(audioPlayer, scope, dispatcher, crashReporting)
    private val resetSingletonCoroutine = SingletonCoroutine(
        "reset", dispatcher, scope.coroutineContext, crashReporting
    )
    private val playFromMediaIDSingletonCoroutine =
        SingletonCoroutine("PlayFromMedID", dispatcher, scope.coroutineContext, crashReporting)
    private val observePlaybackQueueSingletonCoroutine =
        SingletonCoroutine("ObservPlaybQ", dispatcher, scope.coroutineContext, crashReporting)
    private val shutdownSingletonCoroutine =
        SingletonCoroutine("AudioSessShutd", dispatcher, scope.coroutineContext, crashReporting)
    private val suspendSingletonCoroutine =
        SingletonCoroutine("AudioSessSusp", dispatcher, scope.coroutineContext, crashReporting)
    private val showPopupSingletonCoroutine =
        SingletonCoroutine("ShowMedSessPopup", mediaSessionDispatcher, scope.coroutineContext, crashReporting)
    private var isFirstOnPlayEventAfterReset: Boolean = true
    private var onPlayCalledBeforeUSBReady: Boolean = false
    private var isShuttingDown: Boolean = false
    private var isSuspending: Boolean = false
    private val replayGain = ReplayGain(context, sharedPrefs, audioPlayer, audioFileStorage)
    private val audioCodec = AudioCodec(audioFileStorage)
    private val playbackStateActions = PlaybackStateActions(context)

    init {
        logger.debug(TAG, "Init AudioSession()")
        isShuttingDown = false
        isSuspending = false
        shutdownSingletonCoroutine.behaviour = SingletonCoroutineBehaviour.PREFER_FINISH
        suspendSingletonCoroutine.behaviour = SingletonCoroutineBehaviour.PREFER_FINISH
        initAudioFocus()
        // to send play/pause button event: "adb shell input keyevent 85"
        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
        mediaButtonIntent.setClass(context, MediaButtonReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val pendingMediaBtnIntent: PendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE, mediaButtonIntent, flags
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
        observePlaybackStatus()
        observePlaybackQueue()
        observeSessionCallbacks()
    }

    private fun initAudioFocus() {
        audioFocus.audioFocusChangeListener = audioFocusChangeListener
        try {
            val audioFocusSettingStr = sharedPrefs.getAudioFocusSetting(context)
            audioFocus.setBehaviour(audioFocusSettingStr)
        } catch (exc: IllegalArgumentException) {
            logger.exception(TAG, exc.message.toString(), exc)
        }
    }

    /**
     * See https://developers.google.com/cars/design/automotive-os/apps/media/create-your-app/customize-playback
     */
    private fun initPlaybackState() {
        logger.debug(TAG, "initPlaybackState()")
        val actions = playbackStateActions.createPlaybackActions()
        val customActionShuffleOn = playbackStateActions.createCustomActionShuffleIsOff()
        val customActionRepeatOneOn = playbackStateActions.createCustomActionRepeatIsOff()
        val customActionEject = playbackStateActions.createCustomActionEject()
        val customActionRewind10 = playbackStateActions.createCustomActionRewind10()
        playbackState = PlaybackStateCompat.Builder().setState(
            PlaybackStateCompat.STATE_NONE,
            PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
            PLAYBACK_SPEED
        ).setActions(actions)
            .addCustomAction(customActionRewind10)
            .addCustomAction(customActionShuffleOn)
            .addCustomAction(customActionEject)
            .addCustomAction(customActionRepeatOneOn)
            .build()
        runBlocking(dispatcher) {
            setMediaSessionPlaybackState(playbackState)
        }
    }

    private fun observePlaybackStatus() {
        audioPlayer.playerStatusObservers.clear()
        audioPlayer.playerStatusObservers.add { audioPlayerStatus ->
            logger.debug(TAG, "Playback status changed: $audioPlayerStatus")
            val newPlaybackState: PlaybackStateCompat
            // TODO: check if I need to add the non-changing actions again always or not
            val customActionShuffle = if (audioPlayerStatus.isShuffling) {
                playbackStateActions.createCustomActionShuffleIsOn()
            } else {
                playbackStateActions.createCustomActionShuffleIsOff()
            }
            val customActionRepeat = when (audioPlayerStatus.repeatMode) {
                RepeatMode.REPEAT_ALL -> {
                    playbackStateActions.createCustomActionRepeatAllIsOn()
                }
                RepeatMode.REPEAT_ONE -> {
                    playbackStateActions.createCustomActionRepeatOneIsOn()
                }
                else -> {
                    playbackStateActions.createCustomActionRepeatIsOff()
                }
            }
            // TODO: builders should be kept as members for performance reasons, expensive to build
            //  (see https://developer.android.com/guide/topics/media-apps/working-with-a-media-session )
            val playbackStateBuilder = PlaybackStateCompat.Builder().apply {
                setActions(playbackStateActions.createPlaybackActions())
                addCustomAction(playbackStateActions.createCustomActionRewind10())
                addCustomAction(customActionShuffle)
                addCustomAction(playbackStateActions.createCustomActionEject())
                addCustomAction(customActionRepeat)
                setState(audioPlayerStatus.playbackState, audioPlayerStatus.positionInMilliSec, PLAYBACK_SPEED)
                audioPlayerStatus.queueItem?.queueId?.let { setActiveQueueItemId(it) }
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
                val audioPlayerStatusEnhanced = enhanceAudioPlayerErrorStatus(audioPlayerStatus)
                newPlaybackState = playbackStateBuilder.setErrorMessage(
                    audioPlayerStatusEnhanced.errorCode, audioPlayerStatusEnhanced.errorMsg
                ).build()
                val audioPlayerEvt = AudioPlayerEvent(AudioPlayerState.ERROR)
                audioPlayerEvt.errorMsg = audioPlayerStatusEnhanced.errorMsg
                audioPlayerEvt.errorCode = audioPlayerStatusEnhanced.errorCode
                notifyObservers(audioPlayerEvt)
                handlePlayerError(audioPlayerStatusEnhanced.errorCode)
            }
            playbackState = newPlaybackState
            audioFocusChangeListener.playbackState = newPlaybackState.state
            logger.debug(TAG, "newPlaybackState=$newPlaybackState")
            sendNotificationBasedOnPlaybackState()
            launchInScopeSafely("setMediaSessionPlaybackState()") {
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

    private suspend fun enhanceAudioPlayerErrorStatus(audioPlayerStatus: AudioPlayerStatus): AudioPlayerStatus {
        if (audioPlayerStatus.errorCode != MediaPlayer.MEDIA_ERROR_UNKNOWN) {
            // we have a specific error code already
            return audioPlayerStatus
        }
        // #94: MEDIA_ERROR_UNKNOWN is also returned in some cases where the codec is not supported
        audioPlayerStatus.queueItem?.description?.mediaUri?.let {
            if (!audioCodec.isSupported(it)) {
                val audioFile = AudioFile(it)
                audioPlayerStatus.errorCode = MediaPlayer.MEDIA_ERROR_UNSUPPORTED
                audioPlayerStatus.errorMsg =
                    "${context.getString(R.string.error_not_supported)}: ${audioFile.name}"
            }
        }
        return audioPlayerStatus
    }

    private fun handlePlayerError(errorCode: Int) {
        if (errorCode in listOf(
                MediaPlayer.MEDIA_ERROR_MALFORMED,
                MediaPlayer.MEDIA_ERROR_TIMED_OUT,
                MediaPlayer.MEDIA_ERROR_UNSUPPORTED,
                PlaybackStateCompat.ERROR_CODE_END_OF_QUEUE,
                PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                MEDIA_ERROR_AUDIO_FOCUS_DENIED
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
            crashReporting.logLastMessagesAndRecordException(AudioPlayerException("errorCode=$errorCode"))
        }
    }

    private fun recoverFromError() {
        logger.debug(TAG, "recoverFromError()")
        launchInScopeSafely("recoverFromError()") {
            // TODO: naming
            audioPlayer.prepareForEject()
        }
    }

    private fun observePlaybackQueue() {
        audioPlayer.clearPlaybackQueueObservers()
        audioPlayer.addPlaybackQueueObserver { queueChange ->
            logger.debug(TAG, "queueChange=$queueChange")
            val contentHierarchyIDStr: String? = queueChange.currentItem?.description?.mediaId
            logger.debug(TAG, "Content hierarchy ID to change to: $contentHierarchyIDStr")
            logger.setFlushToUSBFlag()
            observePlaybackQueueSingletonCoroutine.launch {
                if (!contentHierarchyIDStr.isNullOrBlank()) {
                    val contentHierarchyID = ContentHierarchyElement.deserialize(contentHierarchyIDStr)
                    var audioItem: AudioItem? = null
                    var audioFile: AudioFile? = null
                    try {
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
                                audioFile = AudioFile(Util.createURIForPath(storageID, contentHierarchyID.path))
                                // If we are playing files directly, extract the metadata here. This will slow down the
                                // start of playback but without it we will not be able to show the duration of file,
                                // album art etc.
                                audioItem = audioItemLibrary.extractMetadataFrom(audioFile)
                                audioItem.id = ContentHierarchyElement.serialize(contentHierarchyID)
                                audioItem.uri = audioFile.uri
                                audioItem.albumArtURI = AudioMetadataMaker.createURIForAlbumArtForFile(audioFile.path)
                            }
                            else -> {
                                throw IllegalArgumentException("Invalid content hierarchy: $contentHierarchyID")
                            }
                        }
                        it.ensureActive()
                        val metadata: MediaMetadataCompat = audioItemLibrary.createMetadataForItem(audioItem)
                        it.ensureActive()
                        extractAndSetReplayGain(audioItem)
                        setCurrentQueueItem(
                            MediaSessionCompat.QueueItem(metadata.description, queueChange.currentItem.queueId)
                        )
                        setMediaSessionMetadata(metadata)
                    } catch (exc: FileNotFoundException) {
                        logger.exception(TAG, "observePlaybackQueue(): ${exc.message.toString()}", exc)
                        var fileName: String = context.getString(R.string.error_unknown)
                        if (audioFile != null) {
                            fileName = audioFile.name
                        } else {
                            if (audioItem != null) {
                                audioFile = AudioFile(audioItem.uri)
                                fileName = audioFile.name
                            }
                        }
                        showError(context.getString(R.string.cannot_read_file, fileName))
                    }
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
        try {
            replayGain.extractAndSetReplayGain(audioItem)
        } catch (exc: MissingEffectsException) {
            val replayGainName = context.getString(R.string.setting_enable_replaygain)
            showError(replayGainName)
        }
    }

    private fun sendNotificationBasedOnPlaybackState() {
        when {
            playbackState.isPlaying -> audioSessionNotifications.sendIsPlayingNotification()
            playbackState.isPaused -> audioSessionNotifications.sendIsPausedNotification()
        }
    }

    // https://developer.android.com/reference/androidx/media/session/MediaButtonReceiver
    fun handleMediaButtonIntent(intent: Intent) {
        logger.debug(TAG, "Handling media button intent")
        MediaButtonReceiver.handleIntent(mediaSession, intent)
    }

    private suspend fun clearSession() {
        logger.debug(TAG, "clearSession()")
        audioFocusChangeListener.cancelAudioFocusLossJob()
        setCurrentQueueItem(null)
        audioSessionNotifications.sendEmptyNotification()
        clearPlaybackState()
        clearMediaSession()
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
                    playFromMediaIDSingletonCoroutine.launch {
                        playFromContentHierarchyID(audioSessionChange.contentHierarchyID)
                    }
                }
                AudioSessionChangeType.ON_SKIP_TO_QUEUE_ITEM -> {
                    playFromMediaIDSingletonCoroutine.cancel()
                    launchInScopeSafely(audioSessionChange.type.name) {
                        audioPlayer.playFromQueueID(audioSessionChange.queueID)
                    }
                }
                AudioSessionChangeType.ON_SKIP_TO_NEXT,
                AudioSessionChangeType.ON_SKIP_TO_PREVIOUS -> {
                    playFromMediaIDSingletonCoroutine.cancel()
                }
                AudioSessionChangeType.ON_STOP -> {
                    audioFocusChangeListener.lastUserRequestedStateChange = audioSessionChange.type
                    launchInScopeSafely(audioSessionChange.type.name) {
                        storePlaybackState()
                        notifyObservers(CustomActionEvent(CustomAction.STOP_CB_CALLED))
                    }
                }
                AudioSessionChangeType.ON_ENABLE_LOG_TO_USB -> {
                    launchInScopeSafely(audioSessionChange.type.name) {
                        audioFileStorage.enableLogToUSBPreference()
                    }
                }
                AudioSessionChangeType.ON_DISABLE_LOG_TO_USB -> {
                    launchInScopeSafely(audioSessionChange.type.name) {
                        audioFileStorage.disableLogToUSBPreference()
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
                        try {
                            audioPlayer.enableEqualizer()
                        } catch (exc: MissingEffectsException) {
                            sharedPrefs.setEQEnabled(context, false)
                            showError(context.getString(R.string.error_invalid_state))
                            throw exc
                        }
                    }
                }
                AudioSessionChangeType.ON_DISABLE_EQUALIZER -> {
                    launchInScopeSafely(audioSessionChange.type.name) {
                        audioPlayer.disableEqualizer()
                    }
                }
                AudioSessionChangeType.ON_ENABLE_REPLAYGAIN -> {
                    replayGain.enable()
                    launchInScopeSafely(audioSessionChange.type.name) {
                        val currentTrackID = getCurrentTrackID()
                        if (currentTrackID.isNotBlank()) {
                            val contentHierarchyID = ContentHierarchyElement.deserialize(currentTrackID)
                            try {
                                val audioItem = getAudioItemForContentHierarchyType(contentHierarchyID)
                                extractAndSetReplayGain(audioItem)
                            } catch (exc: IllegalArgumentException) {
                                logger.error(TAG, "Cannot handle type: $contentHierarchyID")
                                return@launchInScopeSafely
                            }
                        }
                        try {
                            audioPlayer.enableReplayGain()
                        } catch (exc: MissingEffectsException) {
                            sharedPrefs.setReplayGainEnabled(context, false)
                            showError(context.getString(R.string.error_invalid_state))
                            throw exc
                        }
                    }
                }
                AudioSessionChangeType.ON_DISABLE_REPLAYGAIN -> {
                    replayGain.disable()
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
                AudioSessionChangeType.ON_SET_ALBUM_STYLE -> {
                    notifyObservers(
                        SettingChangeEvent(SettingKey.ALBUM_STYLE_SETTING, audioSessionChange.albumStyleSetting)
                    )
                }
                AudioSessionChangeType.ON_SET_VIEW_TABS -> {
                    // https://github.com/MoleMan1024/audiowagon/issues/124
                    notifyObservers(SettingChangeEvent(SettingKey.VIEW_TABS_SETTING, audioSessionChange.viewTabs))
                }
                AudioSessionChangeType.ON_EJECT -> {
                    audioFocusChangeListener.lastUserRequestedStateChange = AudioSessionChangeType.ON_STOP
                    launchInScopeSafely(audioSessionChange.type.name) {
                        Util.logMemory(context, logger, TAG)
                        notifyObservers(CustomActionEvent(CustomAction.EJECT))
                        storePlaybackState()
                        audioPlayer.prepareForEject()
                        audioFileStorage.disableLogToUSB()
                        audioFileStorage.prepareForEject()
                        showPopup(context.getString(R.string.eject_completed))
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
                AudioSessionChangeType.ON_REQUEST_USB_PERMISSION -> {
                    notifyObservers(CustomActionEvent(CustomAction.MAYBE_SHOW_USB_PERMISSION_POPUP))
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
                    is NoItemsInQueueException -> {
                        showPopup(context.getString(R.string.error_no_tracks))
                    }
                    is DriveAlmostFullException -> {
                        logger.exceptionLogcatOnly(TAG, "Drive is almost full, cannot log to USB", exc)
                        showPopup(context.getString(R.string.error_not_enough_space_for_log))
                    }
                    else -> {
                        // no special exception handling
                    }
                }
            }
        }
    }

    /**
     * This is used by for playing items by voice input only.
     */
    private suspend fun playFromSearch(change: AudioSessionChange) {
        logger.debug(TAG, "playFromSearch(audioSessionChange=$change)")
        var audioItems: List<AudioItem> = listOf()
        // We show the search query to the user so that hopefully they will adjust their voice input if something does
        // not work
        var searchQueryForGUI: String = change.queryToPlay
        // "track on album by artist" is not supported
        when (change.queryFocus) {
            AudioItemType.TRACK -> {
                var searchTrackOnly = false
                if (change.trackToPlay.isNotBlank()) {
                    if (change.artistToPlay.isNotBlank()) {
                        searchQueryForGUI = "${change.trackToPlay} + ${change.artistToPlay}"
                        audioItems = audioItemLibrary.searchTrackByArtist(change.trackToPlay, change.artistToPlay)
                    } else if (change.albumToPlay.isNotBlank()) {
                        searchQueryForGUI = "${change.trackToPlay} + ${change.albumToPlay}"
                        audioItems = audioItemLibrary.searchTrackByAlbum(change.trackToPlay, change.albumToPlay)
                    } else {
                        searchTrackOnly = true
                    }
                    // If we find no audio items for two fields combined above, one of them might have been wrong
                    // based on Google Assistant data, try again with just the track
                    if (searchTrackOnly || audioItems.isEmpty()) {
                        searchQueryForGUI = change.trackToPlay
                        audioItems = audioItemLibrary.searchTracks(change.trackToPlay)
                    }
                }
            }
            AudioItemType.ALBUM -> {
                if (change.albumToPlay.isNotBlank()) {
                    var searchAlbumOnly = false
                    if (change.artistToPlay.isNotBlank()) {
                        searchQueryForGUI = "${change.albumToPlay} + ${change.artistToPlay}"
                        audioItems =
                            audioItemLibrary.searchTracksForAlbumAndArtist(change.albumToPlay, change.artistToPlay)
                    } else {
                        searchAlbumOnly = true
                    }
                    // If we find no audio items for two fields combined above, one of them might have been wrong
                    // based on Google Assistant data, try again with just the album
                    if (searchAlbumOnly || audioItems.isEmpty()) {
                        searchQueryForGUI = change.albumToPlay
                        audioItems = audioItemLibrary.searchTracksForAlbum(change.albumToPlay)
                    }
                }
            }
            AudioItemType.ARTIST -> {
                if (change.artistToPlay.isNotBlank()) {
                    searchQueryForGUI = change.artistToPlay
                    audioItems = audioItemLibrary.searchTracksForArtist(change.artistToPlay)
                }
            }
            AudioItemType.UNSPECIFIC -> {
                // handled below
            }
        }
        val delayBeforeVoiceSearchPopupMS = 2000L
        if (audioItems.isNotEmpty()) {
            createQueueAndPlay(audioItems)
            // because I use the MediaSession error messages for "regular" popup messages as well, I need to postpone
            // this popup until after playback started. Otherwise Google Assistant will play error prompts
            // https://github.com/MoleMan1024/audiowagon/issues/77
            delay(delayBeforeVoiceSearchPopupMS)
            showPopup(context.getString(R.string.searching_voice_input, searchQueryForGUI))
            return
        }
        // if the more specific fields above did not yield any results, try searching using the raw query
        if (change.queryToPlay.isNotBlank()) {
            searchQueryForGUI = change.queryToPlay
            audioItems = audioItemLibrary.searchUnspecificWithFallback(change.queryToPlay, change.trackToPlay)
            if (audioItems.isNotEmpty()) {
                createQueueAndPlay(audioItems)
                delay(delayBeforeVoiceSearchPopupMS)
                showPopup(context.getString(R.string.searching_voice_input, searchQueryForGUI))
            } else {
                logger.debug(TAG, "No results for voice search: $change")
                // There is no good PlaybackStateCompat error code that can indicate to Google Assistant that there are
                // no results for the given query. Google says to use
                // https://developer.android.com/media/implement/assistant#errors
                // NOT_AVAILABLE_IN_REGION however the TTS prompt is not fitting for this situation
                val nothingFoundText = context.getString(R.string.error_no_results, searchQueryForGUI)
                val noResultsPlaybackState = PlaybackStateCompat.Builder(playbackState).apply {
                    setErrorMessage(PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR, nothingFoundText)
                }.build()
                // this will indicate to Google Assistant that there are no results for the given query
                setMediaSessionPlaybackState(noResultsPlaybackState)
                delay(delayBeforeVoiceSearchPopupMS)
                audioPlayer.stop()
            }
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
                    && contentHierarchyID.albumArtistID <= DATABASE_ID_UNKNOWN
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
            ContentHierarchyType.PLAYLIST,
            ContentHierarchyType.ALL_TRACKS_FOR_ARTIST,
            ContentHierarchyType.ALL_TRACKS_FOR_ALBUM -> {
                // Make sure to also shuffle first item in playback queue when user did not specifically select an
                // item to start from ( https://github.com/MoleMan1024/audiowagon/issues/83 ). This is done here
                // using a random startIndex because startIndex=0 is never shuffled (see
                // https://github.com/MoleMan1024/audiowagon/issues/120 )
                if (isShuffling()) {
                    startIndex = Random.nextInt(0, audioItems.size)
                }
            }
            else -> {
                // ignore
            }
        }
        if (startIndex < 0) {
            startIndex = 0
        }
        createQueueAndPlay(audioItems, startIndex)
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

    private suspend fun createQueueAndPlay(audioItems: List<AudioItem>, startIndex: Int = 0) {
        logger.debug(TAG, "createQueueAndPlay(audioItems.size=${audioItems.size}, startIndex=$startIndex)")
        // see https://developer.android.com/reference/android/media/session/MediaSession#setQueue(java.util.List%3Candroid.media.session.MediaSession.QueueItem%3E)
        // TODO: check how many is "too many" tracks and use sliding window instead
        val queue: MutableList<MediaSessionCompat.QueueItem> = mutableListOf()
        for ((queueIndex, audioItem) in audioItems.withIndex()) {
            val description = audioItemLibrary.createAudioItemDescription(audioItem)
            val queueItem = MediaSessionCompat.QueueItem(description, queueIndex.toLong())
            logger.verbose(
                TAG, "adding queueItem at index $queueIndex: ${queueItem.description} " +
                        "(${queueItem.description.mediaId})"
            )
            queue.add(queueItem)
        }
        audioPlayer.setPlayQueueAndNotify(queue, startIndex)
        audioPlayer.maybeShuffleNewQueue(startIndex)
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
            if (isSuspending) {
                logger.debug(TAG, "Stopping prepareFromPersistent() because of suspend")
                return
            }
            try {
                coroutineContext.ensureActive()
                val contentHierarchyID = ContentHierarchyElement.deserialize(contentHierarchyIDStr)
                val audioItem = getAudioItemForContentHierarchyType(contentHierarchyID)
                val description = audioItemLibrary.createAudioItemDescription(audioItem)
                val queueItem = MediaSessionCompat.QueueItem(description, index.toLong())
                queue.add(queueItem)
            } catch (exc: IllegalArgumentException) {
                logger.warning(TAG, "Issue with persistent content hierarchy ID: $contentHierarchyIDStr: $exc")
            } catch (exc: CancellationException) {
                logger.warning(TAG, "Preparation from persistent has been cancelled")
                return
            } catch (exc: RuntimeException) {
                if (exc.message?.contains("No track for") == true) {
                    // If there is a mismatch for persistent data because new tracks have been added to USB drive,
                    // avoid to log an exception stacktrace for every item in persisted playback queue
                    logger.error(TAG, "Issue with persistent data: ${exc.message}")
                } else {
                    logger.exception(TAG, "Issue with persistent data", exc)
                }
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
        audioPlayer.setRepeatMode(state.repeatMode)
        audioPlayer.setPlayQueue(queue)
        setMediaSessionQueue(queue)
        try {
            audioPlayer.preparePlayFromQueue(state.queueIndex, state.trackPositionMS.toInt())
            // TODO: not nice (same player status might have been sent shortly before)
            audioPlayer.notifyPlayerStatusChange()
        } catch (exc: NoItemsInQueueException) {
            logger.warning(TAG, "No items in play queue")
        } catch (exc: FileNotFoundException) {
            logger.exception(TAG, "prepareFromPersistent(): ${exc.message.toString()}", exc)
            return
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

    private suspend fun setMediaSessionQueue(queue: List<MediaSessionCompat.QueueItem>) {
        withContext(mediaSessionDispatcher) {
            if (queue.isNotEmpty()) {
                mediaSession.setQueue(queue)
            } else {
                logger.warning(TAG, "No elements in given media session queue, setting null instead")
                mediaSession.setQueue(null)
            }
        }
    }

    private suspend fun setMediaSessionPlaybackState(state: PlaybackStateCompat) {
        logger.verbose(TAG, "setMediaSessionPlaybackState(state=$state)")
        showPopupSingletonCoroutine.join()
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

    private suspend fun clearMediaSession() {
        withContext(mediaSessionDispatcher) {
            setMediaSessionQueue(listOf())
            setMediaSessionMetadata(null)
            setMediaSessionPlaybackState(playbackState)
        }
    }

    suspend fun storePlaybackState() {
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
        playbackStateToPersist.queueIDs = queueIDs
        playbackStateToPersist.queueIndex = queueIndex
        playbackStateToPersist.isShuffling = isShuffling()
        playbackStateToPersist.repeatMode = getRepeatMode()
        playbackStateToPersist.lastContentHierarchyID = lastContentHierarchyIDPlayed
        if (lastContentHierarchyIDPlayed.isNotBlank()) {
            val lastContentHierarchyID = ContentHierarchyElement.deserialize(lastContentHierarchyIDPlayed)
            if (lastContentHierarchyID.type == ContentHierarchyType.SHUFFLE_ALL_TRACKS) {
                // When the user is shuffling all tracks, we don't store the queue, we will recreate a new shuffled queue
                // when restoring from persistent state instead (to save storage space)
                if (queueIDs.isNotEmpty()) {
                    playbackStateToPersist.queueIDs = queueIDs.subList(queueIndex, queueIndex + 1)
                    playbackStateToPersist.queueIndex = 0
                }
            }
        }
        persistentStorage.store(playbackStateToPersist)
    }

    private fun clearPlaybackState() {
        playbackState = PlaybackStateCompat.Builder().setState(
            PlaybackStateCompat.STATE_NONE,
            PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
            PLAYBACK_SPEED
        ).build()
        audioFocusChangeListener.playbackState = playbackState.state
    }

    private suspend fun getAudioItemForContentHierarchyType(contentHierarchyID: ContentHierarchyID): AudioItem {
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
        return audioItem
    }

    private fun getCurrentTrackID(): String {
        return currentQueueItem?.description?.mediaId ?: ""
    }

    private suspend fun getCurrentTrackPosMS(): Long {
        return audioPlayer.getCurrentPositionMilliSec()
    }

    private fun isShuffling(): Boolean {
        return audioPlayer.isShuffling()
    }

    private fun getRepeatMode(): RepeatMode {
        return audioPlayer.getRepeatMode()
    }

    private suspend fun getQueueIDs(): List<String> {
        return audioPlayer.getPlaybackQueueIDs()
    }

    private suspend fun getQueueIndex(): Int {
        return audioPlayer.getPlaybackQueueIndex()
    }

    private fun setCurrentQueueItem(item: MediaSessionCompat.QueueItem?) {
        currentQueueItem = item
        audioSessionNotifications.currentQueueItem = item
    }

    suspend fun showError(text: String) {
        val errorMsg = context.getString(R.string.error, text)
        logger.warning(TAG, "Showing error: $errorMsg")
        showPopup(errorMsg)
    }

    private suspend fun showPopup(text: String) {
        logger.debug(TAG, "Showing popup: $text")
        // this is not ideal, other clients might look at the error message even when error code is 0 (= OK)
        val state = PlaybackStateCompat.Builder(playbackState).apply {
            setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, text)
        }.build()
        showPopupSingletonCoroutine.launch {
            playbackState = state
            logger.verbose(TAG, "Setting media session playback state for popup: $state")
            mediaSession.setPlaybackState(state)
            // In best case this is the time the popup is shown for. However other media session playback state updates
            // might cancel it early
            delay(POPUP_TIMEOUT_MS)
        }
    }

    private fun launchInScopeSafely(message: String, func: suspend (CoroutineScope) -> Unit): Job {
        val exceptionHandler = CoroutineExceptionHandler { coroutineContext, exc ->
            handleException("$message ($coroutineContext threw ${exc.message})", exc)
        }
        logger.verbose(TAG, "Launching coroutine safely for: $message")
        return scope.launch(exceptionHandler + dispatcher) {
            try {
                func(this)
            } catch (exc: Exception) {
                if (exc.message != null) {
                    handleException("$message (${exc.message})", exc)
                } else {
                    handleException(message, exc)
                }
            }
        }
    }

    private fun handleException(msg: String, exc: Throwable) {
        when (exc) {
            is NoItemsInQueueException -> {
                launchInScopeSafely("No tracks") {
                    showError(context.getString(R.string.error_no_tracks))
                }
            }
            is DriveAlmostFullException -> {
                logger.exceptionLogcatOnly(TAG, "Drive is almost full, cannot log to USB", exc)
                launchInScopeSafely("Not enough space for log") {
                    showError(context.getString(R.string.error_not_enough_space_for_log))
                }
            }
            is CancellationException -> {
                logger.warning(TAG, "CancellationException (msg=$msg exc=$exc)")
            }
            is CannotReadFileException -> {
                launchInScopeSafely("Cannot read file") {
                    showError(context.getString(R.string.cannot_read_file, exc.fileName))
                }
                crashReporting.logMessage(msg)
                crashReporting.logLastMessagesAndRecordException(exc)
                logger.exception(TAG, msg, exc)
            }
            is FileNotFoundException -> {
                // This can happen when metadata indexing is set to false and files have changed on USB drive. No
                // need for crash reporting in this case
                logger.exception(TAG, msg, exc)
            }
            is IOException -> {
                if (exc.message?.contains("MAX_RECOVERY_ATTEMPTS") == false) {
                    crashReporting.logMessage(msg)
                    crashReporting.logLastMessagesAndRecordException(exc)
                }
                logger.exception(TAG, msg, exc)
            }
            else -> {
                crashReporting.logMessage(msg)
                crashReporting.logLastMessagesAndRecordException(exc)
                logger.exception(TAG, msg, exc)
            }
        }
    }

    fun getNotification(): Notification {
        return audioSessionNotifications.getNotification()
    }

    fun stopPlayer() {
        logger.debug(TAG, "stopPlayer()")
        runBlocking(dispatcher) {
            audioPlayer.stop()
        }
    }

    suspend fun shutdown() {
        logger.debug(TAG, "shutdown()")
        if (isShuttingDown) {
            logger.debug(TAG, "Already shutting down")
            return
        }
        isShuttingDown = true
        suspendSingletonCoroutine.cancel()
        resetSingletonCoroutine.join()
        clearSession()
        playFromMediaIDSingletonCoroutine.cancel()
        observePlaybackQueueSingletonCoroutine.cancel()
        audioFocusChangeListener.cancelAudioFocusLossJob()
        audioPlayer.shutdown()
        audioFocus.release()
        releaseMediaSession()
        audioSessionNotifications.shutdown()
    }

    suspend fun suspend() {
        logger.debug(TAG, "suspend()")
        isSuspending = true
        resetSingletonCoroutine.join()
        audioPlayer.pause()
        audioFocus.release()
        playFromMediaIDSingletonCoroutine.cancel()
        observePlaybackQueueSingletonCoroutine.cancel()
        audioFocusChangeListener.cancelAudioFocusLossJob()
        setCurrentQueueItem(null)
        audioSessionNotifications.sendEmptyNotification()
        clearPlaybackState()
        setMediaSessionQueue(listOf())
        setMediaSessionPlaybackState(playbackState)
        isSuspending = false
    }

    fun reset() {
        logger.debug(TAG, "reset()")
        isFirstOnPlayEventAfterReset = true
        resetSingletonCoroutine.launch {
            audioPlayer.reset()
            clearSession()
        }
    }

    private fun notifyObservers(event: Any) {
        observers.forEach { it(event) }
    }

    fun observe(func: (Any) -> Unit) {
        observers.add(func)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun getAudioPlayerStatus(): AudioPlayerStatus {
        return audioPlayer.playerStatus
    }

    // TODO: split file, too long

}
