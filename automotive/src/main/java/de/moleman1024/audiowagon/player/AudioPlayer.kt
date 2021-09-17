/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.player

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.net.Uri
import android.os.PersistableBundle
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.preference.PreferenceManager
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.activities.PREF_ENABLE_EQUALIZER
import de.moleman1024.audiowagon.activities.PREF_EQUALIZER_PRESET
import de.moleman1024.audiowagon.exceptions.AlreadyStoppedException
import de.moleman1024.audiowagon.exceptions.NoItemsInQueueException
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.*
import java.io.IOException
import java.util.concurrent.Executors

private const val TAG = "AudioPlayer"
private val logger = Logger
const val MEDIA_ERROR_INVALID_STATE = -38
// arbitrary number
const val MEDIA_ERROR_AUDIO_FOCUS_DENIED = -1249

/**
 * Manages two [MediaPlayer] instances + equalizer etc.
 *
 * API: https://developer.android.com/reference/android/media/MediaPlayer
 * Guide: https://developer.android.com/guide/topics/media/mediaplayer
 *
 * For valid/invalid state transitions of [MediaPlayer] see:
 * https://developer.android.com/reference/android/media/MediaPlayer#valid-and-invalid-states
 */
class AudioPlayer(
    private val audioFileStorage: AudioFileStorage,
    private val audioFocus: AudioFocus,
    private val scope: CoroutineScope,
    private val context: Context,
) {
    // currentMediaPlayer shall point to currently playing media player
    private var currentMediaPlayer: MediaPlayer? = null
    private var playerStatus: AudioPlayerStatus = AudioPlayerStatus(PlaybackStateCompat.STATE_NONE)
    // TODO: turn this into another class with two instances?
    private var mediaPlayerFlip: MediaPlayer? = null
    private var mediaPlayerFlipState: AudioPlayerState = AudioPlayerState.IDLE
    // the other media player object is used when skipping to next track for seamless playback
    private var mediaPlayerFlop: MediaPlayer? = null
    private var mediaPlayerFlopState: AudioPlayerState = AudioPlayerState.IDLE
    private var equalizerFlip: Equalizer? = null
    private var equalizerFlop: Equalizer? = null
    val playerStatusObservers = mutableListOf<(AudioPlayerStatus) -> Unit>()
    private var isRecoveringFromIOError: Boolean = false
    // A single thread executor is used to confine access to queue and other shared state variables to a single
    // thread when called from coroutines. MediaPlayer is not thread-safe, must be accessed from a thread that has
    // a Looper.
    // See https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html#thread-confinement-fine-grained
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val playbackQueue = PlaybackQueue(dispatcher)
    private val playerStatusDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var setupNextPlayerJob: Job? = null

    init {
        launchInScopeSafely {
            logger.debug(TAG, "Using single thread dispatcher for AudioPlayer: ${android.os.Process.myTid()}")
            initMediaPlayers()
            initEqualizers()
        }
    }

    private suspend fun initMediaPlayers() {
        mediaPlayerFlip = MediaPlayer()
        logger.debug(TAG, "Init media player: $mediaPlayerFlip")
        setState(mediaPlayerFlip, AudioPlayerState.IDLE)
        currentMediaPlayer = mediaPlayerFlip
        mediaPlayerFlop = MediaPlayer()
        logger.debug(TAG, "Init next media player: $mediaPlayerFlop")
        setState(mediaPlayerFlop, AudioPlayerState.IDLE)
        setCompletionListener(mediaPlayerFlip)
        setCompletionListener(mediaPlayerFlop)
        setInfoListener(mediaPlayerFlip)
        setInfoListener(mediaPlayerFlop)
        setErrorListener()
    }

    private fun initEqualizers() {
        equalizerFlip = mediaPlayerFlip?.audioSessionId?.let { Equalizer(it) }
        equalizerFlop = mediaPlayerFlop?.audioSessionId?.let { Equalizer(it) }
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val equalizerPresetPreference = sharedPreferences.getString(PREF_EQUALIZER_PRESET, EqualizerPreset.LESS_BASS.name)
        equalizerPresetPreference?.let {
            try {
                val equalizerPreset = EqualizerPreset.valueOf(it)
                equalizerFlip?.setPreset(equalizerPreset)
                equalizerFlop?.setPreset(equalizerPreset)
            } catch (exc: IllegalArgumentException) {
                logger.exception(TAG, "Could not convert preference value to equalizer preset", exc)
            }
        }
        val enableEQPreference = sharedPreferences.getBoolean(PREF_ENABLE_EQUALIZER, false)
        if (enableEQPreference) {
            equalizerFlip?.enable()
            equalizerFlop?.enable()
        }
    }

    private suspend fun setCompletionListener(mediaPlayer: MediaPlayer?) {
        mediaPlayer?.setOnCompletionListener { completedMediaPlayer ->
            logger.debug(TAG, "onCompletion($mediaPlayer)")
            scope.launch(dispatcher) {
                setState(completedMediaPlayer, AudioPlayerState.PLAYBACK_COMPLETED)
                logger.debug(TAG, "Metrics: ${getMetrics()}")
                if (playbackQueue.hasEnded()) {
                    stop()
                    playbackQueue.clear()
                    return@launch
                }
                playbackQueue.incrementIndex()
                currentMediaPlayer = getNextPlayer()
                notifyPlayerStatusChange()
                setupNextPlayer()
            }
        }
    }

    private suspend fun setInfoListener(mediaPlayer: MediaPlayer?) {
        mediaPlayer?.setOnInfoListener { mediaPlayerWithInfo, what, extra ->
            logger.debug(TAG, "onInfo($mediaPlayerWithInfo): $what $extra")
            when (what) {
                MediaPlayer.MEDIA_INFO_STARTED_AS_NEXT -> {
                    scope.launch(dispatcher) {
                        setState(mediaPlayerWithInfo, AudioPlayerState.STARTED)
                    }
                }
                else -> {
                    // ignore
                }
            }
            return@setOnInfoListener true
        }
    }

    private suspend fun setErrorListener() {
        currentMediaPlayer?.setOnErrorListener { player, what, extra ->
            logger.debug(TAG, "onError(player=$player,what=$what,extra=$extra")
            scope.launch(dispatcher) {
                if (isRecoveringFromIOError) {
                    logger.warning(TAG, "Recovery from previous error in progress")
                    return@launch
                }
                playerStatus.errorCode = what
                when (what) {
                    MEDIA_ERROR_INVALID_STATE ->
                        playerStatus.errorMsg = context.getString(R.string.toast_error_invalid_state
                    )
                    MediaPlayer.MEDIA_ERROR_IO -> playerStatus.errorMsg = context.getString(R.string.toast_error_IO)
                    MediaPlayer.MEDIA_ERROR_MALFORMED ->
                        playerStatus.errorMsg = context.getString(R.string.toast_error_malformed_data)
                    MediaPlayer.MEDIA_ERROR_TIMED_OUT ->
                        playerStatus.errorMsg = context.getString(R.string.toast_error_timeout)
                    MediaPlayer.MEDIA_ERROR_UNSUPPORTED ->
                        playerStatus.errorMsg = context.getString(R.string.toast_error_not_supported)
                    else -> {
                        val numConnectedDevices = audioFileStorage.getNumConnectedDevices()
                        if (numConnectedDevices <= 0) {
                            playerStatus.errorMsg = context.getString(R.string.toast_error_no_USB_device)
                        } else {
                            playerStatus.errorMsg = context.getString(R.string.toast_error_unknown)
                        }
                    }
                }
                if (extra != Int.MIN_VALUE) {
                    playerStatus.errorMsg += " ($extra)"
                }
                playerStatus.playbackState = PlaybackStateCompat.STATE_ERROR
                logger.error(TAG, "Error in player $player: $playerStatus")
                setState(player, AudioPlayerState.ERROR)
                notifyPlayerStatusChange()
            }
            return@setOnErrorListener true
        }
    }

    suspend fun getCurrentPositionMilliSec(): Long = withContext(dispatcher) {
        logger.debug(TAG, "getCurrentPositionMilliSec()")
        if (!isAtLeastPrepared(currentMediaPlayer)) {
            return@withContext PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
        }
        return@withContext currentMediaPlayer?.currentPosition?.toLong()
            ?: PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
    }

    @Suppress("RedundantSuspendModifier")
    private suspend fun getMetrics(): PersistableBundle {
        return currentMediaPlayer?.metrics ?: PersistableBundle.EMPTY
    }

    suspend fun pause() {
        withContext(dispatcher) {
            logger.debug(TAG, "pause()")
            val state = getState(currentMediaPlayer)
            val validStates =
                listOf(AudioPlayerState.STARTED, AudioPlayerState.PAUSED, AudioPlayerState.PLAYBACK_COMPLETED)
            if (state !in validStates) {
                logger.warning(TAG, "Invalid call to pause() in state: $state")
                return@withContext
            }
            currentMediaPlayer?.pause()
            setState(currentMediaPlayer, AudioPlayerState.PAUSED)
            playerStatus.playbackState = PlaybackStateCompat.STATE_PAUSED
            notifyPlayerStatusChange()
            logger.flushToUSB()
        }
    }

    private suspend fun prepare() {
        logger.debug(TAG, "prepare(currentMediaPlayer=$currentMediaPlayer)")
        val validStates = listOf(AudioPlayerState.INITIALIZED, AudioPlayerState.STOPPED)
        val state = getState(currentMediaPlayer)
        if (state !in validStates) {
            logger.warning(TAG, "Invalid call to prepare() in state: $state")
            return
        }
        playerStatus.playbackState = PlaybackStateCompat.STATE_BUFFERING
        notifyPlayerStatusChange()
        @Suppress("BlockingMethodInNonBlockingContext")
        currentMediaPlayer?.prepare()
    }

    private suspend fun reset(player: MediaPlayer?) {
        logger.debug(TAG, "reset(player=$player)")
        val state = getState(player)
        if (state == AudioPlayerState.IDLE) {
            logger.debug(TAG, "No reset necessary")
            return
        }
        player?.reset()
        setState(player, AudioPlayerState.IDLE)
        playerStatus.playbackState = PlaybackStateCompat.STATE_NONE
        playerStatus.positionInMilliSec = PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
    }

    suspend fun seekTo(millisec: Int) {
        withContext(dispatcher) {
            logger.debug(TAG, "seekTo(millisec=$millisec)")
            val invalidStatesForSeek = listOf(
                AudioPlayerState.IDLE, AudioPlayerState.INITIALIZED, AudioPlayerState.STOPPED, AudioPlayerState.ERROR,
                AudioPlayerState.END
            )
            val state = getState(currentMediaPlayer)
            if (state in invalidStatesForSeek) {
                logger.warning(TAG, "Invalid call to seekTo() in state: $state")
                return@withContext
            }
            currentMediaPlayer?.setOnSeekCompleteListener {
                scope.launch(dispatcher) {
                    notifyPlayerStatusChange()
                }
            }
            currentMediaPlayer?.seekTo(millisec)
        }
    }

    private suspend fun setDataSource(mediaDataSource: MediaDataSource) {
        logger.debug(TAG, "setDataSource(currentMediaPlayer=$currentMediaPlayer)")
        val validStates = listOf(AudioPlayerState.IDLE)
        val state = getState(currentMediaPlayer)
        if (state !in validStates) {
            logger.warning(TAG, "Invalid call to setDataSource() in state: $state")
            return
        }
        currentMediaPlayer?.setDataSource(mediaDataSource)
        setState(currentMediaPlayer, AudioPlayerState.INITIALIZED)
    }

    suspend fun skipNextTrack() {
        cancelSetupNextPlayerJob()
        withContext(dispatcher) {
            logger.debug(TAG, "skipNextTrack()")
            val nextQueueIndex = playbackQueue.getNextIndex()
            if (nextQueueIndex <= -1) {
                logger.warning(TAG, "No next track for skipping")
                // need to set pause here, because Android Automotive media browser client will change transport
                // controls to paused in STATE_ERROR it seems
                pause()
                val endOfQueueStatus = AudioPlayerStatus(playerStatus.playbackState)
                endOfQueueStatus.positionInMilliSec = getCurrentPositionMilliSec()
                endOfQueueStatus.queueItemID = playbackQueue.getCurrentItem()?.queueId ?: -1
                endOfQueueStatus.errorCode = PlaybackStateCompat.ERROR_CODE_END_OF_QUEUE
                endOfQueueStatus.errorMsg = context.getString(R.string.toast_no_next_track)
                endOfQueueStatus.playbackState = PlaybackStateCompat.STATE_ERROR
                notifyPlayerStatus(endOfQueueStatus)
                return@withContext
            }
            // TODO: there sometimes is a small audio glitch when skipping, need to do sth with media data source to
            //  skip more nicely? Don't know why MediaPlayer does not do that internally, would expect that it e.g. only
            //  skips near zero crossings
            playerStatus.playbackState = PlaybackStateCompat.STATE_SKIPPING_TO_NEXT
            notifyPlayerStatusChange()
            playQueueAtIndex(nextQueueIndex)
        }
    }

    suspend fun skipPreviousTrack() {
        cancelSetupNextPlayerJob()
        withContext(dispatcher) {
            logger.debug(TAG, "skipPreviousTrack()")
            val prevQueueIndex = playbackQueue.getPrevIndex()
            if (prevQueueIndex <= -1) {
                logger.warning(TAG, "No previous track for skipping")
                pause()
                val endOfQueueStatus = AudioPlayerStatus(playerStatus.playbackState)
                endOfQueueStatus.positionInMilliSec = getCurrentPositionMilliSec()
                endOfQueueStatus.queueItemID = playbackQueue.getCurrentItem()?.queueId ?: -1
                endOfQueueStatus.errorCode = PlaybackStateCompat.ERROR_CODE_END_OF_QUEUE
                endOfQueueStatus.errorMsg = context.getString(R.string.toast_no_prev_track)
                endOfQueueStatus.playbackState = PlaybackStateCompat.STATE_ERROR
                notifyPlayerStatus(endOfQueueStatus)
                return@withContext
            }
            playerStatus.playbackState = PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS
            notifyPlayerStatusChange()
            playQueueAtIndex(prevQueueIndex)
        }
    }

    suspend fun setShuffleOn() {
        withContext(dispatcher) {
            setShuffle(true)
            setupNextPlayer()
            notifyPlayerStatusChange()
        }
    }

    suspend fun setShuffleOff() {
        withContext(dispatcher) {
            setShuffle(false)
            setupNextPlayer()
            notifyPlayerStatusChange()
        }
    }

    suspend fun setShuffle(isOn: Boolean) {
        withContext(dispatcher) {
            logger.debug(TAG, "setShuffle(isOn=$isOn)")
            if (isOn) {
                if (playerStatus.isShuffling) {
                    logger.debug(TAG, "Shuffle is already turned on")
                    return@withContext
                }
                playbackQueue.setShuffleOn()
            } else {
                if (!playerStatus.isShuffling) {
                    logger.debug(TAG, "Shuffle is already turned off")
                    return@withContext
                }
                playbackQueue.setShuffleOff()
            }
            playerStatus.isShuffling = isOn
        }
    }

    suspend fun setRepeatOn() {
        withContext(dispatcher) {
            setRepeat(true)
            if (playbackQueue.isLastTrack()) {
                setupNextPlayer()
            }
            notifyPlayerStatusChange()
        }
    }

    suspend fun setRepeatOff() {
        withContext(dispatcher) {
            setRepeat(false)
            if (playbackQueue.isLastTrack()) {
                removeNextPlayer()
            }
            notifyPlayerStatusChange()
        }
    }

    suspend fun setRepeat(isOn: Boolean) {
        withContext(dispatcher) {
            logger.debug(TAG, "setRepeat(isOn=$isOn)")
            if (isOn) {
                if (playerStatus.isRepeating) {
                    logger.debug(TAG, "Repeat is already turned on")
                    return@withContext
                }
                playbackQueue.setRepeatOn()
            } else {
                if (!playerStatus.isRepeating) {
                    logger.debug(TAG, "Repeat is already turned off")
                    return@withContext
                }
                playbackQueue.setRepeatOff()
            }
            playerStatus.isRepeating = isOn
        }
    }

    suspend fun start() {
        withContext(dispatcher) {
            logger.debug(TAG, "start()")
            val invalidStatesForStart = listOf(
                AudioPlayerState.STARTED, AudioPlayerState.IDLE, AudioPlayerState.STOPPED, AudioPlayerState.ERROR,
                AudioPlayerState.END
            )
            val state = getState(currentMediaPlayer)
            if (state in invalidStatesForStart) {
                logger.warning(TAG, "Invalid call to start() in state: $state")
                return@withContext
            }
            val audioFocusRequestResult = audioFocus.request()
            if (audioFocusRequestResult == AudioFocusRequestResult.DENIED) {
                logger.warning(TAG, "Cannot start playback, audio focus request was denied")
                val audioFocusDeniedStatus = AudioPlayerStatus(playerStatus.playbackState)
                audioFocusDeniedStatus.positionInMilliSec = getCurrentPositionMilliSec()
                audioFocusDeniedStatus.queueItemID = playbackQueue.getCurrentItem()?.queueId ?: -1
                audioFocusDeniedStatus.errorCode = MEDIA_ERROR_AUDIO_FOCUS_DENIED
                audioFocusDeniedStatus.errorMsg = context.getString(R.string.toast_error_audio_focus_denied)
                audioFocusDeniedStatus.playbackState = PlaybackStateCompat.STATE_ERROR
                notifyPlayerStatus(audioFocusDeniedStatus)
                return@withContext
            }
            currentMediaPlayer?.start()
            setState(currentMediaPlayer, AudioPlayerState.STARTED)
            playerStatus.playbackState = PlaybackStateCompat.STATE_PLAYING
            notifyPlayerStatusChange()
        }
    }

    suspend fun stop() {
        withContext(dispatcher) {
            logger.debug(TAG, "stop(currentMediaPlayer=$currentMediaPlayer)")
            try {
                stopCurrentPlayer()
            } catch (exc: AlreadyStoppedException) {
                logger.debug(TAG, "Already stopped")
                return@withContext
            } catch (exc: IllegalStateException) {
                logger.warning(TAG, exc.message.toString())
                return@withContext
            }
            setState(currentMediaPlayer, AudioPlayerState.STOPPED)
            playerStatus.playbackState = PlaybackStateCompat.STATE_STOPPED
            notifyPlayerStatusChange()
        }
    }

    private suspend fun stopCurrentPlayer() {
        val state = getState(currentMediaPlayer)
        if (state == AudioPlayerState.STOPPED) {
            throw AlreadyStoppedException()
        }
        val invalidStatesForStop = listOf(
            AudioPlayerState.STOPPED, AudioPlayerState.IDLE, AudioPlayerState.INITIALIZED,
            AudioPlayerState.ERROR, AudioPlayerState.END, AudioPlayerState.PREPARING
        )
        if (state in invalidStatesForStop) {
            throw IllegalStateException("Invalid call to stop() in state: $state")
        }
        currentMediaPlayer?.stop()
        audioFocus.release()
    }

    suspend fun notifyPlayerStatusChange() {
        if (isRecoveringFromIOError) {
            logger.warning(TAG, "Recovery from previous error still in progress")
            return
        }
        playerStatus.positionInMilliSec = getCurrentPositionMilliSec()
        playerStatus.queueItemID = playbackQueue.getCurrentItem()?.queueId ?: -1
        notifyPlayerStatus(playerStatus)
    }

    @Suppress("RedundantSuspendModifier")
    private suspend fun notifyPlayerStatus(status: AudioPlayerStatus) {
        // this needs to run in a different dispatcher than the single thread used for this AudioPlayer to avoid
        // deadlocks
        scope.launch(playerStatusDispatcher) {
            playerStatusObservers.forEach { it(status) }
        }
    }

    suspend fun startPlayFromQueue() {
        withContext(dispatcher) {
            logger.debug(TAG, "startPlayFromQueue()")
            try {
                stopCurrentPlayer()
            } catch (exc: AlreadyStoppedException) {
                logger.debug(TAG, "Already stopped")
            } catch (exc: IllegalStateException) {
                logger.warning(TAG, exc.message.toString())
            }
            reset(currentMediaPlayer)
            val queueItem: MediaSessionCompat.QueueItem =
                playbackQueue.getCurrentItem() ?: throw NoItemsInQueueException()
            val uri: Uri = queueItem.description.mediaUri ?: throw RuntimeException("No URI for queue item: $queueItem")
            val mediaDataSource: MediaDataSource
            try {
                mediaDataSource = getDataSourceForURI(uri)
            } catch (exc: UnsupportedOperationException) {
                return@withContext
            }
            setDataSource(mediaDataSource)
            prepare()
            onPreparedPlayFromQueue(currentMediaPlayer)
        }
    }

    private fun onPreparedPlayFromQueue(mediaPlayer: MediaPlayer?) {
        launchInScopeSafely {
            logger.debug(TAG, "onPreparedListener(mediaPlayer=$mediaPlayer)")
            setState(mediaPlayer, AudioPlayerState.PREPARED)
            // first start the current player so the user can already listen to music, prepare next player in
            // the background afterwards while music is playing
            start()
            // TODO: this can make skipping tracks slow (maybe wait setting up next player until some seconds into
            //  first song? or some seconds before end of song?)
            setupNextPlayer()
        }
    }

    suspend fun preparePlayFromQueue(queueIndex: Int = 0, startPositionMS: Int = 0) {
        withContext(dispatcher) {
            logger.debug(TAG, "preparePlayFromQueue()")
            playbackQueue.setIndex(queueIndex)
            playbackQueue.notifyQueueChanged()
            try {
                stopCurrentPlayer()
            } catch (exc: AlreadyStoppedException) {
                logger.debug(TAG, "Already stopped")
            } catch (exc: IllegalStateException) {
                logger.warning(TAG, exc.message.toString())
            }
            reset(currentMediaPlayer)
            val queueItem: MediaSessionCompat.QueueItem =
                playbackQueue.getCurrentItem() ?: throw NoItemsInQueueException()
            val uri: Uri = queueItem.description.mediaUri ?: throw RuntimeException("No URI for queue item: $queueItem")
            val mediaDataSource: MediaDataSource
            try {
                mediaDataSource = getDataSourceForURI(uri)
            } catch (exc: UnsupportedOperationException) {
                return@withContext
            } catch (exc: IOException) {
                logger.exception(TAG, "I/O exception when getting media data source", exc)
                // TODO: handle this better
                return@withContext
            }
            setDataSource(mediaDataSource)
            prepare()
            onPreparePlayFromQueueReady(currentMediaPlayer, startPositionMS)
        }
    }

    private suspend fun onPreparePlayFromQueueReady(mediaPlayer: MediaPlayer?, startPositionMS: Int) {
        logger.debug(TAG, "onPreparePlayFromQueueReady(mediaPlayer=$mediaPlayer)")
        setState(mediaPlayer, AudioPlayerState.PREPARED)
        if (startPositionMS > 0) {
            seekTo(startPositionMS)
        }
        playerStatus.playbackState = PlaybackStateCompat.STATE_PAUSED
        setupNextPlayer()
    }

    private suspend fun setupNextPlayer() {
        setupNextPlayerJob = scope.launch(dispatcher) {
            logger.debug(TAG, "setupNextPlayer()")
            val nextQueueItem: MediaSessionCompat.QueueItem? = playbackQueue.getNextItem()
            if (nextQueueItem == null) {
                logger.debug(TAG, "This is the last track, not using next player")
                removeNextPlayer()
                return@launch
            }
            val nextMediaPlayer: MediaPlayer =
                getNextPlayer() ?: throw RuntimeException("Next media player not initialized")
            logger.debug(TAG, "Setting up next media player: $nextMediaPlayer")
            nextMediaPlayer.reset()
            val nextURI: Uri =
                nextQueueItem.description.mediaUri
                    ?: throw RuntimeException("No URI for next queue item: $nextQueueItem")
            val nextMediaDataSource: MediaDataSource
            try {
                nextMediaDataSource = getDataSourceForURI(nextURI)
            } catch (exc: UnsupportedOperationException) {
                return@launch
            }
            nextMediaPlayer.setDataSource(nextMediaDataSource)
            logger.debug(TAG, "prepare() next player")
            @Suppress("BlockingMethodInNonBlockingContext")
            nextMediaPlayer.prepare()
            onPreparedNextPlayer(nextMediaPlayer)
        }
    }

    private suspend fun cancelSetupNextPlayerJob() {
        if (setupNextPlayerJob == null) {
            return
        }
        logger.debug(TAG, "cancelSetupNextPlayerJob()")
        setupNextPlayerJob?.cancelAndJoin()
    }

    private suspend fun onPreparedNextPlayer(mediaPlayer: MediaPlayer?) {
        logger.debug(TAG, "onPreparedListener(nextMediaPlayer=$mediaPlayer)")
        setState(mediaPlayer, AudioPlayerState.PREPARED)
        val nextItem: MediaSessionCompat.QueueItem? = playbackQueue.getNextItem()
        if (nextItem == null) {
            logger.debug(TAG, "This is the last track, no next player necessary")
            return
        }
        currentMediaPlayer?.setNextMediaPlayer(mediaPlayer)
    }

    @Suppress("RedundantSuspendModifier")
    private suspend fun removeNextPlayer() {
        if (!isAtLeastPrepared(currentMediaPlayer)) {
            return
        }
        currentMediaPlayer?.setNextMediaPlayer(null)
    }

    private suspend fun playQueueAtIndex(index: Int) {
        withContext(dispatcher) {
            logger.debug(TAG, "playQueueAtIndex($index)")
            val playbackQueueSize = playbackQueue.getSize()
            if (index >= playbackQueueSize) {
                throw IndexOutOfBoundsException("Index $index is larger than play queue size: $playbackQueueSize")
            }
            if (index < 0) {
                throw IndexOutOfBoundsException("Index is negative")
            }
            playerStatus.playbackState = PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM
            notifyPlayerStatusChange()
            playbackQueue.setIndex(index)
            playbackQueue.notifyQueueChanged()
            startPlayFromQueue()
        }
    }

    suspend fun playFromQueueID(queueID: Long) {
        withContext(dispatcher) {
            logger.debug(TAG, "playFromQueueID($queueID)")
            val index = playbackQueue.getIndexForQueueID(queueID)
            playQueueAtIndex(index)
        }
    }

    suspend fun setPlayQueueAndNotify(items: List<MediaSessionCompat.QueueItem>) {
        withContext(dispatcher) {
            setPlayQueue(items)
            playbackQueue.notifyQueueChanged()
        }
    }

    suspend fun setPlayQueue(items: List<MediaSessionCompat.QueueItem>) {
        withContext(dispatcher) {
            playbackQueue.setItems(items)
        }
    }

    suspend fun maybeShuffleQueue() {
        withContext(dispatcher) {
            if (isShuffling()) {
                playbackQueue.setShuffleOn()
            }
        }
    }

    @Suppress("RedundantSuspendModifier")
    private suspend fun getDataSourceForURI(uri: Uri): MediaDataSource {
        logger.debug(TAG, "getDataSourceForURI($uri)")
        try {
            return audioFileStorage.getBufferedDataSourceForURI(uri)
        } catch (exc: UnsupportedOperationException) {
            logger.exception(TAG, "Cannot get data source for URI: $uri", exc)
            playerStatus.errorCode = PlaybackStateCompat.ERROR_CODE_APP_ERROR
            playerStatus.errorMsg = "No datasource"
            playerStatus.playbackState = PlaybackStateCompat.STATE_ERROR
            notifyPlayerStatusChange()
            throw exc
        }
    }

    private suspend fun resetAllPlayers() {
        logger.debug(TAG, "resetAllPlayers()")
        reset(mediaPlayerFlip)
        reset(mediaPlayerFlop)
        notifyPlayerStatusChange()
    }

    suspend fun shutdown() {
        withContext(dispatcher) {
            logger.debug(TAG, "shutdown()")
            equalizerFlip?.disable()
            equalizerFlip?.shutdown()
            equalizerFlop?.disable()
            equalizerFlop?.shutdown()
            cancelSetupNextPlayerJob()
            resetAllPlayers()
            releaseAllPlayers()
        }
    }

    suspend fun reset() {
        withContext(dispatcher) {
            logger.debug(TAG, "reset()")
            stop()
            setPlayQueueAndNotify(listOf())
            cancelSetupNextPlayerJob()
            reInitAllPlayers()
            resetPlayerStatus()
        }
    }

    private suspend fun releaseAllPlayers() {
        mediaPlayerFlip?.release()
        setState(mediaPlayerFlip, AudioPlayerState.END)
        mediaPlayerFlop?.release()
        setState(mediaPlayerFlop, AudioPlayerState.END)
        mediaPlayerFlip = null
        mediaPlayerFlop = null
        equalizerFlip = null
        equalizerFlop = null
        currentMediaPlayer = null
    }

    suspend fun prepareForEject() {
        withContext(dispatcher) {
            stop()
            setPlayQueueAndNotify(listOf())
            reInitAllPlayers()
            resetPlayerStatus()
        }
    }

    suspend fun reInitAfterError() {
        withContext(dispatcher) {
            isRecoveringFromIOError = true
            reInitAllPlayers()
            isRecoveringFromIOError = false
            resetPlayerStatus()
        }
    }

    private suspend fun reInitAllPlayers() {
        withContext(dispatcher) {
            resetAllPlayers()
            releaseAllPlayers()
            initMediaPlayers()
            initEqualizers()
        }
    }

    private suspend fun resetPlayerStatus() {
        playerStatus = AudioPlayerStatus(PlaybackStateCompat.STATE_NONE)
        notifyPlayerStatusChange()
    }

    @Suppress("RedundantSuspendModifier")
    private suspend fun setState(player: MediaPlayer?, state: AudioPlayerState) {
        if (player == null) {
            return
        }
        logger.debug(TAG, "setState($player) to $state")
        if (player == mediaPlayerFlip) {
            mediaPlayerFlipState = state
        } else if (player == mediaPlayerFlop) {
            mediaPlayerFlopState = state
        }
    }

    @Suppress("RedundantSuspendModifier")
    private suspend fun getState(player: MediaPlayer?): AudioPlayerState {
        var state: AudioPlayerState = AudioPlayerState.IDLE
        if (player == mediaPlayerFlip) {
            state = mediaPlayerFlipState
        } else if (player == mediaPlayerFlop) {
            state = mediaPlayerFlopState
        }
        logger.debug(TAG, "getState($player) is $state")
        return state
    }

    @Suppress("RedundantSuspendModifier")
    private suspend fun getNextPlayer(): MediaPlayer? {
        return if (currentMediaPlayer == mediaPlayerFlip) {
            mediaPlayerFlop
        } else {
            mediaPlayerFlip
        }
    }

    private suspend fun isAtLeastPrepared(player: MediaPlayer?): Boolean {
        val state = getState(player)
        return state in listOf(
            AudioPlayerState.PREPARED, AudioPlayerState.STARTED, AudioPlayerState.PAUSED, AudioPlayerState.STOPPED,
            AudioPlayerState.PLAYBACK_COMPLETED
        )
    }

    fun addPlaybackQueueObserver(func: (PlaybackQueueChange) -> Unit) {
        playbackQueue.observers.add(func)
    }

    fun clearPlaybackQueueObservers() {
        playbackQueue.observers.clear()
    }

    suspend fun getPlaybackQueueIDs(): List<String> {
        return withContext(dispatcher) {
            return@withContext playbackQueue.getIDs()
        }
    }

    suspend fun getPlaybackQueueIndex(): Int {
        return withContext(dispatcher) {
            return@withContext playbackQueue.getIndex()
        }
    }

    fun isShuffling(): Boolean {
        return playerStatus.isShuffling
    }

    fun isRepeating(): Boolean {
        return playerStatus.isRepeating
    }

    suspend fun enableEqualizer() {
        withContext(dispatcher) {
            if (equalizerFlip == null) {
                logger.error(TAG, "Equalizer does not exist!")
                return@withContext
            }
            equalizerFlip?.enable()
            equalizerFlop?.enable()
        }
    }

    suspend fun disableEqualizer() {
        withContext(dispatcher) {
            if (equalizerFlip == null) {
                logger.error(TAG, "Equalizer does not exist!")
                return@withContext
            }
            equalizerFlip?.disable()
            equalizerFlop?.disable()
        }
    }

    suspend fun setEqualizerPreset(preset: EqualizerPreset) {
        withContext(dispatcher) {
            if (equalizerFlip == null) {
                logger.error(TAG, "Equalizer does not exist!")
                return@withContext
            }
            equalizerFlip?.setPreset(preset)
            equalizerFlop?.setPreset(preset)
        }
    }

    private fun launchInScopeSafely(func: suspend () -> Unit) {
        scope.launch(dispatcher) {
            try {
                func()
            } catch (exc: Exception) {
                logger.exception(TAG, exc.message.toString(), exc)
            }
        }
    }

    suspend fun isPlaybackQueueEmpty(): Boolean = withContext(dispatcher) {
        return@withContext playbackQueue.getCurrentItem() == null
    }

    suspend fun isIdle(): Boolean = withContext(dispatcher) {
        return@withContext getState(currentMediaPlayer) == AudioPlayerState.IDLE
    }

}
