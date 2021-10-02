/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media.session.MediaButtonReceiver
import de.moleman1024.audiowagon.*
import de.moleman1024.audiowagon.broadcast.*
import de.moleman1024.audiowagon.exceptions.DriveAlmostFullException
import de.moleman1024.audiowagon.exceptions.NoItemsInQueueException
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.AudioItem
import de.moleman1024.audiowagon.medialibrary.AudioItemLibrary
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyElement
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyID
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyType
import de.moleman1024.audiowagon.medialibrary.descriptionForLog
import de.moleman1024.audiowagon.persistence.PersistentPlaybackState
import de.moleman1024.audiowagon.persistence.PersistentStorage
import kotlinx.coroutines.*
import java.util.concurrent.Executors

private const val TAG = "AudioSession"
private val logger = Logger
const val SESSION_TAG = "AudioSession"
const val PLAYBACK_SPEED: Float = 1.0f
const val AUDIO_SESS_NOTIF_CHANNEL: String = "AudioSessNotifChan"
// arbitrary number
const val REQUEST_CODE: Int = 25573
const val ACTION_SHUFFLE_ON = "de.moleman1024.audiowagon.ACTION_SHUFFLE_ON"
const val ACTION_SHUFFLE_OFF = "de.moleman1024.audiowagon.ACTION_SHUFFLE_OFF"
const val ACTION_REPEAT_ON = "de.moleman1024.audiowagon.ACTION_REPEAT_ON"
const val ACTION_REPEAT_OFF = "de.moleman1024.audiowagon.ACTION_REPEAT_OFF"
const val ACTION_EJECT = "de.moleman1024.audiowagon.ACTION_EJECT"

class AudioSession(
    private val context: Context,
    private val audioItemLibrary: AudioItemLibrary,
    private val audioFileStorage: AudioFileStorage,
    val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val gui: GUI,
    private val persistentStorage: PersistentStorage,
    private val sessionActivityIntent: PendingIntent?
) {
    private var mediaSession: MediaSessionCompat
    private var audioSessionCallback: AudioSessionCallback
    var sessionToken: MediaSessionCompat.Token
    // See https://developer.android.com/reference/android/support/v4/media/session/PlaybackStateCompat
    private lateinit var playbackState: PlaybackStateCompat
    private var notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private lateinit var isPlayingNotificationBuilder: NotificationCompat.Builder
    private lateinit var isPausedNotificationBuilder: NotificationCompat.Builder
    private var currentQueueItem: MediaSessionCompat.QueueItem? = null
    private val playerObservers = mutableListOf<(AudioPlayerStateChange) -> Unit>()
    private var isShowingNotification: Boolean = false
    // media session must be accessed from same thread always
    private val mediaSessionDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var lastContentHierarchyIDPlayed: String = ""
    private val audioFocus: AudioFocus = AudioFocus(context)
    private val audioPlayer: AudioPlayer = AudioPlayer(audioFileStorage, audioFocus, scope, context)
    private val audioFocusChangeListener: AudioFocusChangeCallback =
        AudioFocusChangeCallback(audioPlayer, scope, dispatcher)
    private val notificationReceiver: NotificationReceiver = NotificationReceiver(audioPlayer, scope, dispatcher)
    private var storePersistentJob: Job? = null
    private var isFirstOnPlayEventAfterReset: Boolean = true
    private var onPlayCalledBeforeUSBReady: Boolean = false

    init {
        logger.debug(TAG, "Init AudioSession()")
        audioFocus.audioFocusChangeListener = audioFocusChangeListener
        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
        mediaButtonIntent.setClass(context, MediaButtonReceiver::class.java)
        val pendingMediaBtnIntent: PendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE, mediaButtonIntent, 0
        )
        audioSessionCallback = AudioSessionCallback(audioPlayer, scope, dispatcher)
        mediaSession = MediaSessionCompat(context, SESSION_TAG).apply {
            setCallback(audioSessionCallback, Handler(Looper.getMainLooper()))
            // TODO: remove queue title? does not seem to be used in AAOS
            setQueueTitle(context.getString(R.string.queue_title))
            setMediaButtonReceiver(pendingMediaBtnIntent)
            setSessionActivity(sessionActivityIntent)
            isActive = true
        }
        sessionToken = mediaSession.sessionToken
        initPlaybackState()
        deleteNotificationChannel()
        createNotificationChannel()
        prepareMediaNotifications()
        observePlaybackStatus()
        observePlaybackQueue()
        observeSessionCallbacks()
    }

    // TODO: move notification stuff to its own class
    private fun createNotificationChannel() {
        if (notificationManager.getNotificationChannel(AUDIO_SESS_NOTIF_CHANNEL) != null) {
            logger.debug(TAG, "Notification channel already exists")
            notificationManager.cancelAll()
            return
        }
        logger.debug(TAG, "Creating notification channel")
        val importance = NotificationManager.IMPORTANCE_LOW
        val notifChannelName = context.getString(R.string.notif_channel_name)
        val notifChannelDesc = context.getString(R.string.notif_channel_desc)
        val channel = NotificationChannel(AUDIO_SESS_NOTIF_CHANNEL, notifChannelName, importance).apply {
            description = notifChannelDesc
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun prepareMediaNotifications() {
        // TODO: this seems to have no effect in Android Automotive
        val playIcon = IconCompat.createWithResource(context, R.drawable.baseline_play_arrow_24)
        val playAction = createNotificationAction(ACTION_PLAY, playIcon, R.string.notif_action_play)
        val pauseIcon = IconCompat.createWithResource(context, R.drawable.baseline_pause_24)
        val pauseAction = createNotificationAction(ACTION_PAUSE, pauseIcon, R.string.notif_action_pause)
        val nextIcon = IconCompat.createWithResource(context, R.drawable.baseline_skip_next_24)
        val nextAction = createNotificationAction(ACTION_NEXT, nextIcon, R.string.notif_action_next)
        val prevIcon = IconCompat.createWithResource(context, R.drawable.baseline_skip_previous_24)
        val prevAction = createNotificationAction(ACTION_PREV, prevIcon, R.string.notif_action_prev)
        isPlayingNotificationBuilder = NotificationCompat.Builder(context, AUDIO_SESS_NOTIF_CHANNEL).apply {
            addAction(prevAction)
            addAction(pauseAction)
            addAction(nextAction)
        }
        isPausedNotificationBuilder = NotificationCompat.Builder(context, AUDIO_SESS_NOTIF_CHANNEL).apply {
            addAction(prevAction)
            addAction(playAction)
            addAction(nextAction)
        }
    }

    private fun createNotificationAction(action: String, icon: IconCompat, stringID: Int): NotificationCompat.Action {
        val intent =
            PendingIntent.getBroadcast(context, REQUEST_CODE, Intent(action), PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Action.Builder(icon, context.getString(stringID), intent).build()
    }

    /**
     * A notification to appear at the top of the screen (Android) or at the bottom of the screen (Android
     * Automotive in Polestar 2) showing player action icons, timestamp, seekbar etc.
     *
     * See https://developers.google.com/cars/design/automotive-os/apps/media/interaction-model/playing-media
     */
    private fun sendNotification(notifBuilder: NotificationCompat.Builder) {
        val notification = prepareNotification(notifBuilder)
        logger.debug(TAG, "Sending notification: $notification")
        notificationManager.notify(NOTIFICATION_ID, notification)
        if (!isShowingNotification) {
            registerNotifRecv()
        }
        isShowingNotification = true
    }

    private fun prepareNotification(notifBuilder: NotificationCompat.Builder): Notification {
        val style = androidx.media.app.NotificationCompat.MediaStyle()
        style.setMediaSession(sessionToken)
        return notifBuilder.apply {
            setStyle(style)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            setContentTitle(currentQueueItem?.description?.title)
            setContentText(currentQueueItem?.description?.subtitle)
            setSubText(currentQueueItem?.description?.description)
            setSmallIcon(R.drawable.ic_notif)
            setContentIntent(mediaSession.controller.sessionActivity)
            // TODO: can "clear all" even be used for media notification in AAOS?
            setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_STOP))
        }.build()
    }

    fun getNotification(): Notification {
        return prepareNotification(isPlayingNotificationBuilder)
    }

    private fun registerNotifRecv() {
        logger.debug(TAG, "registerNotifRecv()")
        val filter = IntentFilter()
        filter.addAction(ACTION_PREV)
        filter.addAction(ACTION_PLAY)
        filter.addAction(ACTION_PAUSE)
        filter.addAction(ACTION_NEXT)
        context.registerReceiver(notificationReceiver, filter)
    }

    private fun unregisterNotifRecv() {
        logger.debug(TAG, "unregisterNotifRecv()")
        context.unregisterReceiver(notificationReceiver)
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
                    launchInScopeSafely {
                        audioFocusChangeListener.cancelAudioFocusLossJob()
                    }
                }
                notifyStartPauseStop(audioPlayerStatus.playbackState)
            } else {
                newPlaybackState = playbackStateBuilder.setErrorMessage(
                    audioPlayerStatus.errorCode, audioPlayerStatus.errorMsg
                ).build()
                val playerStateChange = AudioPlayerStateChange(AudioPlayerState.ERROR)
                playerStateChange.errorMsg = audioPlayerStatus.errorMsg
                playerStateChange.errorCode = audioPlayerStatus.errorCode
                notifyPlayerObservers(playerStateChange)
                handlePlayerError(audioPlayerStatus.errorCode)
            }
            playbackState = newPlaybackState
            audioFocusChangeListener.playbackState = newPlaybackState.state
            logger.debug(TAG, "newPlaybackState=$newPlaybackState")
            sendNotificationBasedOnPlaybackState()
            launchInScopeSafely {
                setMediaSessionPlaybackState(playbackState)
            }
        }
    }

    private fun notifyStartPauseStop(playbackState: Int) {
        val playerStateChange: AudioPlayerStateChange = when (playbackState) {
            PlaybackStateCompat.STATE_PLAYING -> AudioPlayerStateChange(AudioPlayerState.STARTED)
            PlaybackStateCompat.STATE_PAUSED -> AudioPlayerStateChange(AudioPlayerState.PAUSED)
            PlaybackStateCompat.STATE_STOPPED -> AudioPlayerStateChange(AudioPlayerState.STOPPED)
            else -> {
                return
            }
        }
        notifyPlayerObservers(playerStateChange)
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
        launchInScopeSafely {
            audioPlayer.reInitAfterError()
        }
    }

    private fun observePlaybackQueue() {
        audioPlayer.clearPlaybackQueueObservers()
        audioPlayer.addPlaybackQueueObserver { queueChange ->
            logger.debug(TAG, "queueChange=$queueChange")
            val contentHierarchyIDStr: String? = queueChange.currentItem?.description?.mediaId
            logger.debug(TAG, "Current track content hierarchy ID: $contentHierarchyIDStr")
            logger.flushToUSB()
            launchInScopeSafely {
                if (contentHierarchyIDStr != null) {
                    val contentHierarchyID = ContentHierarchyElement.deserialize(contentHierarchyIDStr)
                    val audioItem: AudioItem = audioItemLibrary.getAudioItemForTrack(contentHierarchyID)
                    val metadata: MediaMetadataCompat = audioItemLibrary.createMetadataForItem(audioItem)
                    currentQueueItem = MediaSessionCompat.QueueItem(
                        metadata.description, queueChange.currentItem.queueId
                    )
                    setMediaSessionMetadata(metadata)
                } else {
                    currentQueueItem = null
                    setMediaSessionMetadata(null)
                }
                setMediaSessionQueue(queueChange.items)
                sendNotificationBasedOnPlaybackState()
            }
        }
    }

    private fun sendNotificationBasedOnPlaybackState() {
        when {
            playbackState.isPlaying -> sendNotification(isPlayingNotificationBuilder)
            playbackState.isPaused -> sendNotification(isPausedNotificationBuilder)
        }
    }

    fun stopPlayer() {
        logger.debug(TAG, "stopPlayer()")
        runBlocking(dispatcher) {
            audioPlayer.stop()
        }
    }

    fun shutdown() {
        logger.debug(TAG, "shutdown()")
        clearSession()
        deleteNotificationChannel()
        runBlocking(dispatcher) {
            audioFocusChangeListener.cancelAudioFocusLossJob()
            audioPlayer.shutdown()
            releaseMediaSession()
        }
    }

    fun suspend() {
        logger.debug(TAG, "suspend()")
        runBlocking(dispatcher) {
            audioFocusChangeListener.cancelAudioFocusLossJob()
        }
        currentQueueItem = null
        val notifBuilder = NotificationCompat.Builder(context, AUDIO_SESS_NOTIF_CHANNEL)
        sendNotification(notifBuilder)
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
        currentQueueItem = null
        // default notification without any content
        val notifBuilder = NotificationCompat.Builder(context, AUDIO_SESS_NOTIF_CHANNEL)
        sendNotification(notifBuilder)
        clearPlaybackState()
        runBlocking(dispatcher) {
            clearMediaSession()
        }
        removeNotification()
    }

    // FIXME: called multiple times
    private fun removeNotification() {
        logger.debug(TAG, "removeNotification()")
        if (!isShowingNotification) {
            logger.debug("TAG", "No notification is currently shown")
            return
        }
        // TODO: to actually remove the notification, the mediaSession needs to be released. However when that is
        //  done it should not be used again unless the media browser client is restarted
        notificationManager.cancel(NOTIFICATION_ID)
        unregisterNotifRecv()
        isShowingNotification = false
    }

    private fun deleteNotificationChannel() {
        if (notificationManager.getNotificationChannel(AUDIO_SESS_NOTIF_CHANNEL) == null) {
            return
        }
        logger.debug(TAG, "Deleting notification channel")
        notificationManager.deleteNotificationChannel(AUDIO_SESS_NOTIF_CHANNEL)
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
                    scope.launch(dispatcher) {
                        callSafelyAndShowErrorsOnGUI(audioSessionChange.type.name) {
                            playFromContentHierarchyID(audioSessionChange.contentHierarchyID)
                        }
                    }
                }
                AudioSessionChangeType.ON_SKIP_TO_QUEUE_ITEM -> {
                    scope.launch(dispatcher) {
                        callSafelyAndShowErrorsOnGUI(audioSessionChange.type.name) {
                            playFromQueueId(audioSessionChange.queueID)
                        }
                    }
                }
                AudioSessionChangeType.ON_STOP -> {
                    audioFocusChangeListener.lastUserRequestedStateChange = audioSessionChange.type
                    scope.launch(dispatcher) {
                        callSafelyAndShowErrorsOnGUI(audioSessionChange.type.name) {
                            storePlaybackState()
                            // TODO: release some more things in audiosession/player, e.g. queue/index in queue?
                        }
                    }
                }
                AudioSessionChangeType.ON_ENABLE_LOG_TO_USB -> {
                    scope.launch(dispatcher) {
                        callSafelyAndShowErrorsOnGUI(audioSessionChange.type.name) {
                            audioFileStorage.enableLogToUSB()
                        }
                    }
                }
                AudioSessionChangeType.ON_DISABLE_LOG_TO_USB -> {
                    scope.launch(dispatcher) {
                        callSafelyAndShowErrorsOnGUI(audioSessionChange.type.name) {
                            audioFileStorage.disableLogToUSB()
                        }
                    }
                }
                AudioSessionChangeType.ON_ENABLE_EQUALIZER -> {
                    scope.launch(dispatcher) {
                        callSafelyAndShowErrorsOnGUI(audioSessionChange.type.name) {
                            audioPlayer.enableEqualizer()
                        }
                    }
                }
                AudioSessionChangeType.ON_DISABLE_EQUALIZER -> {
                    scope.launch(dispatcher) {
                        callSafelyAndShowErrorsOnGUI(audioSessionChange.type.name) {
                            audioPlayer.disableEqualizer()
                        }
                    }
                }
                AudioSessionChangeType.ON_SET_EQUALIZER_PRESET -> {
                    scope.launch(dispatcher) {
                        callSafelyAndShowErrorsOnGUI(audioSessionChange.type.name) {
                            val equalizerPreset = EqualizerPreset.valueOf(audioSessionChange.equalizerPreset)
                            audioPlayer.setEqualizerPreset(equalizerPreset)
                        }
                    }
                }
                AudioSessionChangeType.ON_EJECT -> {
                    audioFocusChangeListener.lastUserRequestedStateChange = AudioSessionChangeType.ON_STOP
                    scope.launch(dispatcher) {
                        callSafelyAndShowErrorsOnGUI(audioSessionChange.type.name) {
                            storePlaybackState()
                            audioPlayer.prepareForEject()
                            audioFileStorage.disableLogToUSB()
                            audioFileStorage.removeAllDevicesFromStorage()
                            gui.showToastMsg(context.getString(R.string.toast_eject_completed))
                        }
                    }
                }
                AudioSessionChangeType.ON_PLAY_FROM_SEARCH -> {
                    audioFocusChangeListener.lastUserRequestedStateChange = AudioSessionChangeType.ON_PLAY
                    scope.launch(dispatcher) {
                        callSafelyAndShowErrorsOnGUI(audioSessionChange.type.name) {
                            if (audioSessionChange.unspecificToPlay.isBlank()) {
                                playFromSearch(
                                    audioSessionChange.trackToPlay,
                                    audioSessionChange.albumToPlay,
                                    audioSessionChange.artistToPlay
                                )
                            } else {
                                playUnspecificFromSearch(audioSessionChange.unspecificToPlay)
                            }
                        }
                    }
                }
                AudioSessionChangeType.ON_PAUSE -> {
                    audioFocusChangeListener.lastUserRequestedStateChange = audioSessionChange.type
                }
            }
        }
    }

    private fun handleOnPlay() {
        scope.launch(dispatcher) {
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
                    return@launch
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

    private inline fun <T> callSafelyAndShowErrorsOnGUI(exceptionMsg: String, call: () -> T) {
        try {
            call()
        } catch (exc: Exception) {
            logger.exception(TAG, "Exception in $exceptionMsg", exc)
            when (exc) {
                is NoItemsInQueueException -> gui.showErrorToastMsg(context.getString(R.string.toast_error_no_tracks))
                is DriveAlmostFullException -> {
                    logger.exceptionLogcatOnly(TAG, "Drive is almost full, cannot log to USB", exc)
                    gui.showErrorToastMsg(context.getString(R.string.toast_error_not_enough_space_for_log))
                }
                else -> {
                    // ignore
                }
            }
        }
    }

    private fun recoverFromError() {
        logger.debug(TAG, "recoverFromError()")
        launchInScopeSafely {
            // TODO: naming
            audioPlayer.prepareForEject()
        }
    }

    private suspend fun playFromSearch(track: String?, album: String?, artist: String?) {
        logger.debug(TAG, "playFromSearch(track=$track, album=$album, artist=$artist")
        var audioItems: List<AudioItem> = listOf()
        if (!artist.isNullOrBlank()) {
            audioItems = audioItemLibrary.searchArtists(artist)
        } else if (!album.isNullOrBlank()) {
            audioItems = audioItemLibrary.searchAlbums(album)
        } else if (!track.isNullOrBlank()) {
            audioItems = audioItemLibrary.searchTracks(track)
        } else {
            // user spoke sth like "play music"
            val contentHierarchyIDShuffleAll = ContentHierarchyID(ContentHierarchyType.SHUFFLE_ALL_TRACKS)
            audioItemLibrary.getAudioItemsStartingFrom(contentHierarchyIDShuffleAll)
        }
        createQueueAndPlay(audioItems)
    }

    private suspend fun playUnspecificFromSearch(searchTerm: String) {
        logger.debug(TAG, "playUnspecificFromSearch(searchTerm=$searchTerm)")
        val audioItems: List<AudioItem> = audioItemLibrary.searchUnspecific(searchTerm)
        createQueueAndPlay(audioItems)
    }

    private suspend fun playFromContentHierarchyID(contentHierarchyIDStr: String) {
        val contentHierarchyID = ContentHierarchyElement.deserialize(contentHierarchyIDStr)
        logger.debug(TAG, "playFromContentHierarchyID($contentHierarchyID)")
        val audioItems: List<AudioItem> = audioItemLibrary.getAudioItemsStartingFrom(contentHierarchyID)
        lastContentHierarchyIDPlayed = contentHierarchyIDStr
        var startIndex = 0
        if (contentHierarchyID.type == ContentHierarchyType.TRACK) {
            startIndex = audioItems.indexOfFirst {
                ContentHierarchyElement.deserialize(it.id).trackID == contentHierarchyID.trackID
            }
        }
        createQueueAndPlay(audioItems, startIndex)
    }

    private suspend fun createQueueAndPlay(audioItems: List<AudioItem>, startIndex: Int = 0) {
        // see https://developer.android.com/reference/android/media/session/MediaSession#setQueue(java.util.List%3Candroid.media.session.MediaSession.QueueItem%3E)
        // TODO: check how many is too many tracks and use sliding window instead
        val queue: MutableList<MediaSessionCompat.QueueItem> = mutableListOf()
        for ((queueID, audioItem) in audioItems.withIndex()) {
            val description = audioItemLibrary.createAudioItemDescription(audioItem)
            val queueItem = MediaSessionCompat.QueueItem(description, queueID.toLong())
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
            try {
                scope.ensureActive()
                val contentHierarchyID = ContentHierarchyElement.deserialize(contentHierarchyIDStr)
                val trackAudioItem = audioItemLibrary.getAudioItemForTrack(contentHierarchyID)
                val description = audioItemLibrary.createAudioItemDescription(trackAudioItem)
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
        val lastContentHierarchyID = ContentHierarchyElement.deserialize(state.lastContentHierarchyID)
        if (lastContentHierarchyID.type == ContentHierarchyType.SHUFFLE_ALL_TRACKS) {
            // In this case we restored only the last playing track from persistent storage above. Now we create a new
            // shuffled playback queue for all remaining items
            val shuffledTracks = audioItemLibrary.getAudioItemsStartingFrom(lastContentHierarchyID)
            for ((queueID, audioItem) in shuffledTracks.withIndex()) {
                if (audioItem.id == queue[0].description.mediaId) {
                    continue
                }
                val description = audioItemLibrary.createAudioItemDescription(audioItem)
                val queueItem = MediaSessionCompat.QueueItem(description, queueID.toLong() + 1L)
                queue.add(queueItem)
            }
        }
        audioPlayer.setShuffle(state.isShuffling)
        audioPlayer.setRepeat(state.isRepeating)
        audioPlayer.setPlayQueue(queue)
        setMediaSessionQueue(queue)
        try {
            audioPlayer.preparePlayFromQueue(state.queueIndex, state.trackPositionMS.toInt())
            // TODO: not nice
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

    private suspend fun playFromQueueId(queueId: Long) {
        audioPlayer.playFromQueueID(queueId)
    }

    private fun notifyPlayerObservers(playerStateChange: AudioPlayerStateChange) {
        playerObservers.forEach { it(playerStateChange) }
    }

    fun observePlayer(func: (AudioPlayerStateChange) -> Unit) {
        playerObservers.add(func)
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
        val queueIndex = getQueueIndex()
        // We only store playback queue items that are upcoming. Previous items are discarded to save some storage space
        playbackStateToPersist.queueIDs = queueIDs.subList(queueIndex, queueIDs.size)
        playbackStateToPersist.queueIndex = 0
        playbackStateToPersist.isShuffling = isShuffling()
        playbackStateToPersist.isRepeating = isRepeating()
        playbackStateToPersist.lastContentHierarchyID = lastContentHierarchyIDPlayed
        val lastContentHierarchyID = ContentHierarchyElement.deserialize(lastContentHierarchyIDPlayed)
        if (lastContentHierarchyID.type == ContentHierarchyType.SHUFFLE_ALL_TRACKS) {
            // When the user is shuffling all tracks, we don't store the queue, we will recreate a new shuffled queue
            // when restoring from persistent state instead (to save storage space)
            playbackStateToPersist.queueIDs = queueIDs.subList(queueIndex, queueIndex+1)
        }
        runBlocking(dispatcher) {
            if (storePersistentJob != null) {
                storePersistentJob?.cancelAndJoin()
            }
        }
        storePersistentJob = launchInScopeSafely {
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

    private fun launchInScopeSafely(func: suspend () -> Unit): Job {
        return scope.launch(dispatcher) {
            try {
                func()
            } catch (exc: Exception) {
                logger.exception(TAG, exc.message.toString(), exc)
            }
        }
    }

    // TODO: split file, too long

}
