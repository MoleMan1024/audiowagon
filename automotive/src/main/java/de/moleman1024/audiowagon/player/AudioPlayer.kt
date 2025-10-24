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
import androidx.annotation.VisibleForTesting
import de.moleman1024.audiowagon.DEFAULT_PLAYBACK_SPEED
import de.moleman1024.audiowagon.POPUP_TIMEOUT_MS
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.SharedPrefs
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.enums.AudioFocusRequestResult
import de.moleman1024.audiowagon.enums.AudioPlayerState
import de.moleman1024.audiowagon.enums.EqualizerPreset
import de.moleman1024.audiowagon.enums.IncreasedPlaybackSpeedSetting
import de.moleman1024.audiowagon.enums.RepeatMode
import de.moleman1024.audiowagon.exceptions.AlreadyStoppedException
import de.moleman1024.audiowagon.exceptions.CannotReadFileException
import de.moleman1024.audiowagon.exceptions.MissingEffectsException
import de.moleman1024.audiowagon.exceptions.NoItemsInQueueException
import de.moleman1024.audiowagon.filestorage.data.AudioFile
import de.moleman1024.audiowagon.filestorage.AudioFileStorage
import de.moleman1024.audiowagon.log.CrashReporting
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.player.data.AudioPlayerStatus
import de.moleman1024.audiowagon.player.data.PlaybackQueueChange
import kotlinx.coroutines.*
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

private const val TAG = "AudioPlayer"
private val logger = Logger
const val MEDIA_ERROR_INVALID_STATE = -38

// arbitrary number
const val MEDIA_ERROR_AUDIO_FOCUS_DENIED = -1249
const val SKIP_PREVIOUS_THRESHOLD_MSEC = 10000L
private const val ALREADY_STOPPED = "Already stopped"

/**
 * Manages two [MediaPlayer] instances and an equalizer
 *
 * API: https://developer.android.com/reference/android/media/MediaPlayer
 * Guide: https://developer.android.com/guide/topics/media/mediaplayer
 *
 * For valid/invalid state transitions of [MediaPlayer] see:
 * https://developer.android.com/reference/android/media/MediaPlayer#valid-and-invalid-states
 */
// TODO: class getting too big, find a way to split
@ExperimentalCoroutinesApi
class AudioPlayer(
    private val audioFileStorage: AudioFileStorage,
    private val audioFocus: AudioFocus,
    private val scope: CoroutineScope,
    private val context: Context,
    private val crashReporting: CrashReporting,
    private val sharedPrefs: SharedPrefs
) {
    // currentMediaPlayer shall point to currently playing media player
    private var currentMediaPlayer: MediaPlayer? = null
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    var playerStatus: AudioPlayerStatus = AudioPlayerStatus(PlaybackStateCompat.STATE_NONE)
    // TODO: turn this into another class with two instances?
    private var mediaPlayerFlip: MediaPlayer? = null
    private var mediaPlayerFlipState: AudioPlayerState = AudioPlayerState.IDLE
    // the other media player object is used when skipping to next track for seamless playback
    private var mediaPlayerFlop: MediaPlayer? = null
    private var mediaPlayerFlopState: AudioPlayerState = AudioPlayerState.IDLE
    private var effectsFlip: Effects? = null
    private var effectsFlop: Effects? = null
    val playerStatusObservers = mutableListOf<suspend (AudioPlayerStatus) -> Unit>()
    private var isRecoveringFromIOError: Boolean = false
    // A single thread executor is used to confine access to queue and other shared state variables to a single
    // thread when called from coroutines. MediaPlayer is not thread-safe, must be accessed from a thread that has
    // a Looper.
    // See https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html#thread-confinement-fine-grained
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val playbackQueue = PlaybackQueue(dispatcher)
    private val playerStatusDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var setupNextPlayerJob: Job? = null
    private var onCompletionJob: Job? = null
    private var numFilesNotFound: Int = 0
    private var playbackSpeed: Float = DEFAULT_PLAYBACK_SPEED

    init {
        launchInScopeSafely {
            logger.debug(TAG, "Using single thread dispatcher for AudioPlayer: ${android.os.Process.myTid()}")
            initMediaPlayers()
            initEffects()
        }
    }

    private suspend fun initMediaPlayers() {
        mediaPlayerFlip = MediaPlayer()
        logger.debug(Util.TAGCRT(TAG, coroutineContext), "Init media player: $mediaPlayerFlip")
        setState(mediaPlayerFlip, AudioPlayerState.IDLE)
        currentMediaPlayer = mediaPlayerFlip
        mediaPlayerFlop = MediaPlayer()
        logger.debug(Util.TAGCRT(TAG, coroutineContext), "Init next media player: $mediaPlayerFlop")
        setState(mediaPlayerFlop, AudioPlayerState.IDLE)
        setCompletionListener(mediaPlayerFlip)
        setCompletionListener(mediaPlayerFlop)
        setInfoListener(mediaPlayerFlip)
        setInfoListener(mediaPlayerFlop)
        setErrorListener()
    }

    private fun initEffects() {
        effectsFlip = mediaPlayerFlip?.audioSessionId?.let {
            logger.debug(TAG, "Init effects for audio session ID: ${mediaPlayerFlip?.audioSessionId}")
            Effects(it)
        }
        effectsFlop = mediaPlayerFlop?.audioSessionId?.let {
            logger.debug(TAG, "Init effects for audio session ID: ${mediaPlayerFlop?.audioSessionId}")
            Effects(it)
        }
        val equalizerPresetPreference = sharedPrefs.getEQPreset(context)
        equalizerPresetPreference.let {
            try {
                val equalizerPreset = EqualizerPreset.valueOf(it)
                if (equalizerPreset != EqualizerPreset.CUSTOM) {
                    effectsFlip?.storeAndApplyEQPreset(equalizerPreset)
                    effectsFlop?.storeAndApplyEQPreset(equalizerPreset)
                } else {
                    logger.debug(TAG, "Using custom equalizer settings")
                    val eqBandValues = sharedPrefs.getEQBandValues(context)
                    eqBandValues.forEachIndexed { index, value ->
                        effectsFlip?.storeAndApplyEqualizerBandValue(index, value)
                        effectsFlop?.storeAndApplyEqualizerBandValue(index, value)
                    }
                }
            } catch (exc: IllegalArgumentException) {
                logger.exception(TAG, "Could not convert preference value to equalizer preset", exc)
            }
        }
        val enableEQPreference = sharedPrefs.isEQEnabled(context)
        if (enableEQPreference) {
            effectsFlip?.enableEQ()
            effectsFlop?.enableEQ()
        }
        val enableReplayGainPreference = sharedPrefs.isReplayGainEnabled(context)
        if (enableReplayGainPreference) {
            effectsFlip?.enableGain()
            effectsFlop?.enableGain()
        }
    }

    private fun setCompletionListener(mediaPlayer: MediaPlayer?) {
        mediaPlayer?.setOnCompletionListener { completedMediaPlayer ->
            logger.debug(TAG, "onCompletion($mediaPlayer)")
            onCompletionJob = launchInScopeSafely {
                if (isErrorInAnyMediaPlayer()) {
                    return@launchInScopeSafely
                }
                yield()
                setState(completedMediaPlayer, AudioPlayerState.PLAYBACK_COMPLETED)
                logger.debug(TAG, "Metrics: ${getMetrics()}")
                if (playbackQueue.hasEnded()) {
                    logger.debug(TAG, "Playback queue has ended")
                    playerStatus.hasPlaybackQueueEnded = true
                    stop()
                    playbackQueue.clear()
                    return@launchInScopeSafely
                }
                cancelSetupNextPlayerJob()
                yield()
                playbackQueue.incrementIndex()
                currentMediaPlayer = getNextPlayer()
                notifyPlayerStatusChange()
                it.ensureActive()
                try {
                    setupNextPlayer()
                } catch (exc: IOException) {
                    logger.exception(TAG, exc.message.toString(), exc)
                    playerStatus.errorCode = PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR
                    playerStatus.errorMsg = "Could not set next player"
                    playerStatus.playbackState = PlaybackStateCompat.STATE_ERROR
                    notifyPlayerStatusChange()
                }
            }
        }
    }

    private suspend fun isErrorInAnyMediaPlayer(): Boolean {
        if (getState(mediaPlayerFlip) == AudioPlayerState.ERROR) {
            return true
        }
        if (getState(mediaPlayerFlop) == AudioPlayerState.ERROR) {
            return true
        }
        return false
    }

    private fun setInfoListener(mediaPlayer: MediaPlayer?) {
        mediaPlayer?.setOnInfoListener { mediaPlayerWithInfo, what, extra ->
            logger.debug(TAG, "onInfo($mediaPlayerWithInfo): $what $extra")
            when (what) {
                MediaPlayer.MEDIA_INFO_STARTED_AS_NEXT -> {
                    launchInScopeSafely {
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

    private fun setErrorListener() {
        currentMediaPlayer?.setOnErrorListener { player, what, extra ->
            logger.debug(TAG, "onError(player=$player,what=$what,extra=$extra")
            launchInScopeSafely {
                if (isRecoveringFromIOError) {
                    logger.warning(TAG, "Error recovery still in progress")
                    return@launchInScopeSafely
                }
                cancelOnCompletionJob()
                setState(player, AudioPlayerState.ERROR)
                playerStatus.errorCode = what
                when (what) {
                    MEDIA_ERROR_INVALID_STATE -> playerStatus.errorMsg = context.getString(
                        R.string.error_invalid_state
                    )
                    MediaPlayer.MEDIA_ERROR_IO -> playerStatus.errorMsg = context.getString(R.string.error_IO)
                    MediaPlayer.MEDIA_ERROR_MALFORMED -> playerStatus.errorMsg =
                        context.getString(R.string.error_malformed_data)
                    MediaPlayer.MEDIA_ERROR_TIMED_OUT -> playerStatus.errorMsg =
                        context.getString(R.string.error_timeout)
                    MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> playerStatus.errorMsg =
                        context.getString(R.string.error_not_supported)
                    else -> {
                        val numAvailableDevices = audioFileStorage.getNumAttachedPermittedDevices()
                        if (numAvailableDevices <= 0) {
                            playerStatus.errorMsg = context.getString(R.string.error_no_USB_device)
                        } else {
                            playerStatus.errorMsg = context.getString(R.string.error_unknown)
                        }
                    }
                }
                if (extra != Int.MIN_VALUE) {
                    playerStatus.errorMsg += " ($extra)"
                }
                playerStatus.playbackState = PlaybackStateCompat.STATE_ERROR
                // Issue #145: do not update the current queue item and track position when player goes into an error
                // state. We prefer to persistently store the last known good state instead
                notifyPlayerStatusChange(playerStatus)
            }
            return@setOnErrorListener true
        }
    }

    private suspend fun getCurrentPositionMilliSec(): Long = withContext(dispatcher) {
        logger.debug(Util.TAGCRT(TAG, coroutineContext), "getCurrentPositionMilliSec()")
        if (!isAtLeastPrepared(currentMediaPlayer)) {
            return@withContext PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
        }
        return@withContext currentMediaPlayer?.currentPosition?.toLong()
            ?: PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
    }

    suspend fun getCurrentPositionMilliSecIgnorePlayerError(): Long = withContext(dispatcher) {
        logger.debug(Util.TAGCRT(TAG, coroutineContext), "getCurrentPositionMilliSecIgnorePlayerError()")
        return@withContext if (getState(currentMediaPlayer) != AudioPlayerState.ERROR) {
            getCurrentPositionMilliSec()
        } else {
            playerStatus.positionInMilliSec
        }
    }

    @Suppress("RedundantSuspendModifier")
    private suspend fun getMetrics(): PersistableBundle {
        return currentMediaPlayer?.metrics ?: PersistableBundle.EMPTY
    }

    suspend fun pause() {
        withContext(dispatcher) {
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "pause()")
            val state = getState(currentMediaPlayer)
            val validStates =
                listOf(AudioPlayerState.STARTED, AudioPlayerState.PAUSED, AudioPlayerState.PLAYBACK_COMPLETED)
            if (state !in validStates) {
                logger.warning(Util.TAGCRT(TAG, coroutineContext), "Invalid call to pause() in state: $state")
                return@withContext
            }
            currentMediaPlayer?.pause()
            setState(currentMediaPlayer, AudioPlayerState.PAUSED)
            playerStatus.playbackState = PlaybackStateCompat.STATE_PAUSED
            notifyPlayerStatusChange()
            logger.setFlushToUSBFlag()
        }
    }

    private suspend fun prepare() {
        logger.debug(Util.TAGCRT(TAG, coroutineContext), "prepare(currentMediaPlayer=$currentMediaPlayer)")
        val validStates = listOf(AudioPlayerState.INITIALIZED, AudioPlayerState.STOPPED)
        val state = getState(currentMediaPlayer)
        if (state !in validStates) {
            logger.warning(Util.TAGCRT(TAG, coroutineContext), "Invalid call to prepare() in state: $state")
            return
        }
        playerStatus.hasPlaybackQueueEnded = false
        playerStatus.playbackState = PlaybackStateCompat.STATE_BUFFERING
        notifyPlayerStatusChange()
        currentMediaPlayer?.prepare()
    }

    private suspend fun reset(player: MediaPlayer?) {
        logger.debug(Util.TAGCRT(TAG, coroutineContext), "reset(player=$player)")
        val state = getState(player)
        if (state == AudioPlayerState.IDLE) {
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "No reset necessary")
            return
        }
        player?.reset()
        setState(player, AudioPlayerState.IDLE)
        playerStatus.hasPlaybackQueueEnded = false
        playerStatus.playbackState = PlaybackStateCompat.STATE_NONE
        playerStatus.queueItem = null
        playerStatus.positionInMilliSec = PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
        playerStatus.errorMsg = ""
        playerStatus.errorCode = 0
    }

    suspend fun seekTo(millisec: Int) {
        withContext(dispatcher) {
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "seekTo(millisec=$millisec)")
            val invalidStatesForSeek = listOf(
                AudioPlayerState.IDLE,
                AudioPlayerState.INITIALIZED,
                AudioPlayerState.STOPPED,
                AudioPlayerState.ERROR,
                AudioPlayerState.END
            )
            val state = getState(currentMediaPlayer)
            if (state in invalidStatesForSeek) {
                logger.warning(Util.TAGCRT(TAG, coroutineContext), "Invalid call to seekTo() in state: $state")
                return@withContext
            }
            currentMediaPlayer?.setOnSeekCompleteListener {
                launchInScopeSafely {
                    notifyPlayerStatusChange()
                }
            }
            currentMediaPlayer?.seekTo(millisec)
        }
    }

    suspend fun rewind(millisec: Int) {
        withContext(dispatcher) {
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "rewind(millisec=$millisec)")
            val currentPosMilliSec = getCurrentPositionMilliSec()
            var newPosMilliSec = currentPosMilliSec - millisec
            if (newPosMilliSec < 0) {
                newPosMilliSec = 0
            }
            seekTo(newPosMilliSec.toInt())
        }
    }

    private suspend fun setDataSource(mediaDataSource: MediaDataSource) {
        logger.debug(Util.TAGCRT(TAG, coroutineContext), "setDataSource(currentMediaPlayer=$currentMediaPlayer)")
        val validStates = listOf(AudioPlayerState.IDLE)
        val state = getState(currentMediaPlayer)
        if (state !in validStates) {
            logger.warning(Util.TAGCRT(TAG, coroutineContext), "Invalid call to setDataSource() in state: $state")
            return
        }
        currentMediaPlayer?.setDataSource(mediaDataSource)
        setState(currentMediaPlayer, AudioPlayerState.INITIALIZED)
    }

    suspend fun skipNextTrack() {
        cancelSetupNextPlayerJob()
        withContext(dispatcher) {
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "skipNextTrack()")
            val nextQueueIndex = playbackQueue.getNextIndexIgnoreRepeatOne()
            if (nextQueueIndex <= -1) {
                logger.warning(Util.TAGCRT(TAG, coroutineContext), "No next track for skipping")
                // need to set pause here, because Android Automotive media browser client will change transport
                // controls to paused in STATE_ERROR it seems
                pause()
                val endOfQueueStatus = AudioPlayerStatus(playerStatus.playbackState)
                endOfQueueStatus.positionInMilliSec = getCurrentPositionMilliSec()
                endOfQueueStatus.queueItem = playbackQueue.getCurrentItem()
                endOfQueueStatus.errorCode = PlaybackStateCompat.ERROR_CODE_END_OF_QUEUE
                endOfQueueStatus.errorMsg = context.getString(R.string.no_next_track)
                endOfQueueStatus.playbackState = PlaybackStateCompat.STATE_ERROR
                notifyPlayerStatus(endOfQueueStatus)
                return@withContext
            }
            playerStatus.playbackState = PlaybackStateCompat.STATE_SKIPPING_TO_NEXT
            notifyPlayerStatusChange()
            playQueueAtIndex(nextQueueIndex)
        }
    }

    suspend fun skipPreviousTrack() {
        cancelSetupNextPlayerJob()
        withContext(dispatcher) {
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "skipPreviousTrack()")
            val prevQueueIndex = playbackQueue.getPrevIndex()
            if (prevQueueIndex <= -1) {
                logger.warning(Util.TAGCRT(TAG, coroutineContext), "No previous track for skipping")
                pause()
                val endOfQueueStatus = AudioPlayerStatus(playerStatus.playbackState)
                endOfQueueStatus.positionInMilliSec = getCurrentPositionMilliSec()
                endOfQueueStatus.queueItem = playbackQueue.getCurrentItem()
                endOfQueueStatus.errorCode = PlaybackStateCompat.ERROR_CODE_END_OF_QUEUE
                endOfQueueStatus.errorMsg = context.getString(R.string.no_prev_track)
                endOfQueueStatus.playbackState = PlaybackStateCompat.STATE_ERROR
                notifyPlayerStatus(endOfQueueStatus)
                return@withContext
            }
            playerStatus.playbackState = PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS
            notifyPlayerStatusChange()
            playQueueAtIndex(prevQueueIndex)
        }
    }

    /**
     * Handle "previous" action (e.g. from steering wheel button) depending on current track time position:
     * If currently playing track is at >= x seconds, restart the track from beginning.
     * Else if currently playing track is at < x seconds, go to previous track instead.
     * Requested in https://github.com/MoleMan1024/audiowagon/issues/45
     */
    suspend fun handlePrevious() {
        withContext(dispatcher) {
            val currentPosMilliSec = getCurrentPositionMilliSec()
            if (currentPosMilliSec < SKIP_PREVIOUS_THRESHOLD_MSEC) {
                skipPreviousTrack()
            } else {
                seekTo(0)
            }
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
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "setShuffle(isOn=$isOn)")
            if (isOn) {
                if (playerStatus.isShuffling) {
                    logger.debug(Util.TAGCRT(TAG, coroutineContext), "Shuffle is already turned on")
                    return@withContext
                }
                playbackQueue.shuffleExceptCurrentItem()
            } else {
                if (!playerStatus.isShuffling) {
                    logger.debug(Util.TAGCRT(TAG, coroutineContext), "Shuffle is already turned off")
                    return@withContext
                }
                playbackQueue.unshuffle()
            }
            playerStatus.isShuffling = isOn
        }
    }

    suspend fun setRepeatOneOn() {
        withContext(dispatcher) {
            setRepeatMode(RepeatMode.REPEAT_ONE)
            setupNextPlayer()
            notifyPlayerStatusChange()
        }
    }

    suspend fun setRepeatAllOn() {
        withContext(dispatcher) {
            setRepeatMode(RepeatMode.REPEAT_ALL)
            setupNextPlayer()
            notifyPlayerStatusChange()
        }
    }

    suspend fun setRepeatOff() {
        withContext(dispatcher) {
            setRepeatMode(RepeatMode.OFF)
            if (playbackQueue.isLastTrack()) {
                removeNextPlayer()
            } else {
                setupNextPlayer()
            }
            notifyPlayerStatusChange()
        }
    }

    suspend fun setRepeatMode(mode: RepeatMode) {
        withContext(dispatcher) {
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "setRepeatMode(mode=$mode)")
            if (playerStatus.repeatMode == mode) {
                logger.debug(Util.TAGCRT(TAG, coroutineContext), "Repeat mode is already: $mode")
                return@withContext
            }
            playbackQueue.setRepeatMode(mode)
            playerStatus.repeatMode = mode
        }
    }

    suspend fun start() {
        withContext(dispatcher) {
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "start()")
            val invalidStatesForStart = listOf(
                AudioPlayerState.STARTED,
                AudioPlayerState.IDLE,
                AudioPlayerState.STOPPED,
                AudioPlayerState.ERROR,
                AudioPlayerState.END
            )
            val state = getState(currentMediaPlayer)
            if (state in invalidStatesForStart) {
                logger.warning(Util.TAGCRT(TAG, coroutineContext), "Invalid call to start() in state: $state")
                return@withContext
            }
            val audioFocusRequestResult = audioFocus.request()
            if (audioFocusRequestResult == AudioFocusRequestResult.DENIED) {
                logger.warning(Util.TAGCRT(TAG, coroutineContext), "Cannot start playback, audio focus request was denied")
                val audioFocusDeniedStatus = AudioPlayerStatus(playerStatus.playbackState)
                audioFocusDeniedStatus.positionInMilliSec = getCurrentPositionMilliSec()
                audioFocusDeniedStatus.queueItem = playbackQueue.getCurrentItem()
                audioFocusDeniedStatus.errorCode = MEDIA_ERROR_AUDIO_FOCUS_DENIED
                audioFocusDeniedStatus.errorMsg = context.getString(R.string.error_audio_focus_denied)
                audioFocusDeniedStatus.playbackState = PlaybackStateCompat.STATE_ERROR
                notifyPlayerStatus(audioFocusDeniedStatus)
                return@withContext
            }
            updatePlaybackSpeedInPlayer(currentMediaPlayer)
            currentMediaPlayer?.start()
            setState(currentMediaPlayer, AudioPlayerState.STARTED)
            playerStatus.hasPlaybackQueueEnded = false
            playerStatus.playbackState = PlaybackStateCompat.STATE_PLAYING
            notifyPlayerStatusChange()
        }
    }

    suspend fun stop() {
        withContext(dispatcher) {
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "stop(currentMediaPlayer=$currentMediaPlayer)")
            try {
                stopCurrentPlayer()
            } catch (_: AlreadyStoppedException) {
                logger.debug(Util.TAGCRT(TAG, coroutineContext), ALREADY_STOPPED)
                return@withContext
            } catch (exc: IllegalStateException) {
                logger.warning(Util.TAGCRT(TAG, coroutineContext), exc.message.toString())
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
            AudioPlayerState.STOPPED,
            AudioPlayerState.IDLE,
            AudioPlayerState.INITIALIZED,
            AudioPlayerState.ERROR,
            AudioPlayerState.END,
            AudioPlayerState.PREPARING
        )
        if (state in invalidStatesForStop) {
            throw IllegalStateException("Invalid call to stop() in state: $state")
        }
        currentMediaPlayer?.stop()
        audioFocus.release()
    }

    suspend fun notifyPlayerStatusChange(status: AudioPlayerStatus? = null) {
        if (isRecoveringFromIOError) {
            logger.warning(Util.TAGCRT(TAG, coroutineContext), "Error recovery is still in progress")
            return
        }
        if (status == null) {
            playerStatus.positionInMilliSec = getCurrentPositionMilliSec()
            playerStatus.queueItem = playbackQueue.getCurrentItem()
            playerStatus.playbackSpeed = playbackSpeed
            notifyPlayerStatus(playerStatus)
        } else {
            notifyPlayerStatus(status)
        }
    }

    private suspend fun notifyPlayerStatus(status: AudioPlayerStatus) {
        // this needs to run in a different dispatcher than the single thread used for this AudioPlayer to avoid
        // deadlocks
        val exceptionHandler = CoroutineExceptionHandler { coroutineContext, exc ->
            logger.exception(Util.TAGCRT(TAG, coroutineContext), "$coroutineContext threw ${exc.message}", exc)
        }
        logger.debug(Util.TAGCRT(TAG, coroutineContext), "notifyPlayerStatus(): $status")
        scope.launch(exceptionHandler + playerStatusDispatcher) {
            playerStatusObservers.forEach { it(status) }
        }
    }

    suspend fun startPlayFromQueue() {
        withContext(dispatcher) {
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "startPlayFromQueue()")
            try {
                stopCurrentPlayer()
            } catch (_: AlreadyStoppedException) {
                logger.debug(Util.TAGCRT(TAG, coroutineContext), ALREADY_STOPPED)
            } catch (exc: IllegalStateException) {
                logger.warning(Util.TAGCRT(TAG, coroutineContext), exc.message.toString())
            }
            reset(currentMediaPlayer)
            val queueItem: MediaSessionCompat.QueueItem =
                playbackQueue.getCurrentItem() ?: throw NoItemsInQueueException()
            val uri: Uri = queueItem.description.mediaUri ?: throw RuntimeException("No URI for queue item: $queueItem")
            val mediaDataSource: MediaDataSource
            try {
                mediaDataSource = getDataSourceForURI(uri)
                setDataSource(mediaDataSource)
                updatePlaybackSpeedInPlayer(currentMediaPlayer)
                try {
                    prepare()
                    numFilesNotFound = 0
                } catch (_: IOException) {
                    val audioFile = AudioFile(uri)
                    throw CannotReadFileException(audioFile.name)
                }
                onPreparedPlayFromQueue(currentMediaPlayer)
            } catch (_: UnsupportedOperationException) {
                return@withContext
            } catch (_: FileNotFoundException) {
                // If file is missing, try next file in queue. This can happen if audio item library does not match
                // the USB filesystem (e.g. due to manual indexing)
                val status = AudioPlayerStatus(PlaybackStateCompat.STATE_BUFFERING)
                status.errorCode = PlaybackStateCompat.ERROR_CODE_APP_ERROR
                val audioFile = AudioFile(uri)
                status.errorMsg = context.getString(R.string.cannot_read_file, audioFile.name)
                notifyPlayerStatusChange(status)
                numFilesNotFound += 1
                if (numFilesNotFound < 5) {
                    logger.debug(
                        TAG, "File not found, trying to play next item in queue instead: ${AudioFile(uri).name}"
                    )
                    delay(POPUP_TIMEOUT_MS)
                    logger.debug(TAG, "Skipping next track after delay for file not found")
                    skipNextTrack()
                } else {
                    logger.error(TAG, "Too many files not found, update metadata or check your playlist")
                    delay(POPUP_TIMEOUT_MS)
                    playerStatus.playbackState = PlaybackStateCompat.STATE_ERROR
                    notifyPlayerStatusChange()
                }
            }
        }
    }

    private fun onPreparedPlayFromQueue(mediaPlayer: MediaPlayer?) {
        launchInScopeSafely {
            logger.debug(TAG, "onPreparedListener(mediaPlayer=$mediaPlayer)")
            setState(mediaPlayer, AudioPlayerState.PREPARED)
            // First start the current player so the user can already listen to music, prepare next player in
            // the background afterwards while music is playing
            start()
            // TODO: this can make skipping tracks slow (maybe wait setting up next player until some seconds into
            //  first song? or some seconds before end of song?)
            setupNextPlayer()
        }
    }

    suspend fun preparePlayFromQueue(queueIndex: Int = 0, startPositionMS: Int = 0) {
        withContext(dispatcher) {
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "preparePlayFromQueue()")
            playbackQueue.setIndex(queueIndex)
            playbackQueue.notifyQueueChanged()
            try {
                stopCurrentPlayer()
            } catch (_: AlreadyStoppedException) {
                logger.debug(Util.TAGCRT(TAG, coroutineContext), ALREADY_STOPPED)
            } catch (exc: IllegalStateException) {
                logger.warning(Util.TAGCRT(TAG, coroutineContext), exc.message.toString())
            }
            reset(currentMediaPlayer)
            val queueItem: MediaSessionCompat.QueueItem =
                playbackQueue.getCurrentItem() ?: throw NoItemsInQueueException()
            val uri: Uri = queueItem.description.mediaUri ?: throw RuntimeException("No URI for queue item: $queueItem")
            val mediaDataSource: MediaDataSource
            try {
                mediaDataSource = getDataSourceForURI(uri)
            } catch (_: UnsupportedOperationException) {
                return@withContext
            } catch (exc: FileNotFoundException) {
                throw exc
            } catch (exc: IOException) {
                logger.exception(
                    Util.TAGCRT(TAG, coroutineContext), "I/O exception when getting media data source", exc
                )
                // TODO: handle this better
                return@withContext
            }
            setDataSource(mediaDataSource)
            updatePlaybackSpeedInPlayer(currentMediaPlayer)
            prepare()
            onPreparePlayFromQueueReady(currentMediaPlayer, startPositionMS)
        }
    }

    private suspend fun onPreparePlayFromQueueReady(mediaPlayer: MediaPlayer?, startPositionMS: Int) {
        logger.debug(Util.TAGCRT(TAG, coroutineContext), "onPreparePlayFromQueueReady(mediaPlayer=$mediaPlayer)")
        setState(mediaPlayer, AudioPlayerState.PREPARED)
        if (startPositionMS > 0) {
            seekTo(startPositionMS)
        }
        playerStatus.playbackState = PlaybackStateCompat.STATE_PAUSED
        setupNextPlayer()
    }

    private fun setupNextPlayer() {
        setupNextPlayerJob = launchInScopeSafely {
            logger.debug(TAG, "setupNextPlayer()")
            val nextQueueItem: MediaSessionCompat.QueueItem? = playbackQueue.getNextItem()
            if (nextQueueItem == null) {
                logger.debug(TAG, "This is the last track, not using next player")
                removeNextPlayer()
                return@launchInScopeSafely
            }
            val nextMediaPlayer: MediaPlayer =
                getNextPlayer() ?: throw RuntimeException("Next media player not initialized")
            logger.debug(TAG, "Setting up next media player: $nextMediaPlayer")
            nextMediaPlayer.reset()
            val nextURI: Uri = nextQueueItem.description.mediaUri
                ?: throw RuntimeException("No URI for next queue item: $nextQueueItem")
            val nextMediaDataSource: MediaDataSource
            try {
                nextMediaDataSource = getDataSourceForURI(nextURI)
            } catch (exc: UnsupportedOperationException) {
                logger.exception(TAG, exc.message.toString(), exc)
                return@launchInScopeSafely
            } catch (exc: IOException) {
                logger.exception(TAG, exc.message.toString(), exc)
                return@launchInScopeSafely
            }
            nextMediaPlayer.setDataSource(nextMediaDataSource)
            updatePlaybackSpeedInPlayer(nextMediaPlayer)
            logger.debug(TAG, "prepare() next player")
            nextMediaPlayer.prepare()
            onPreparedNextPlayer(nextMediaPlayer)
        }
    }

    private suspend fun cancelSetupNextPlayerJob() {
        if (setupNextPlayerJob == null) {
            return
        }
        logger.debug(Util.TAGCRT(TAG, coroutineContext), "cancelSetupNextPlayerJob()")
        setupNextPlayerJob?.cancelAndJoin()
        setupNextPlayerJob = null
    }

    private suspend fun cancelOnCompletionJob() {
        if (onCompletionJob == null) {
            return
        }
        logger.debug(Util.TAGCRT(TAG, coroutineContext), "cancelOnCompletionJob()")
        onCompletionJob?.cancelAndJoin()
        onCompletionJob = null
    }

    private suspend fun onPreparedNextPlayer(mediaPlayer: MediaPlayer?) {
        logger.debug(Util.TAGCRT(TAG, coroutineContext), "onPreparedListener(nextMediaPlayer=$mediaPlayer)")
        setState(mediaPlayer, AudioPlayerState.PREPARED)
        val nextItem: MediaSessionCompat.QueueItem? = playbackQueue.getNextItem()
        if (nextItem == null) {
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "This is the last track, no next player necessary")
            return
        }
        try {
            currentMediaPlayer?.setNextMediaPlayer(mediaPlayer)
        } catch (exc: IllegalArgumentException) {
            logger.exception(
                Util.TAGCRT(TAG, coroutineContext),
                "Exception setting next media player $mediaPlayer for current media player: $currentMediaPlayer",
                exc
            )
        }
        // check for available memory once in a while
        Util.logMemory(context, logger, TAG)
    }

    private suspend fun removeNextPlayer() {
        if (!isAtLeastPrepared(currentMediaPlayer)) {
            return
        }
        try {
            currentMediaPlayer?.setNextMediaPlayer(null)
        } catch (exc: IllegalArgumentException) {
            logger.exception(TAG, exc.message.toString(), exc)
        }
    }

    private suspend fun playQueueAtIndex(index: Int) {
        withContext(dispatcher) {
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "playQueueAtIndex($index)")
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
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "playFromQueueID($queueID)")
            var index = playbackQueue.getIndexForQueueID(queueID)
            if (index >= playbackQueue.getSize()) {
                index = playbackQueue.getSize() - 1
            }
            if (index < 0) {
                index = 0
            }
            playQueueAtIndex(index)
        }
    }

    suspend fun setPlayQueueAndNotify(items: List<MediaSessionCompat.QueueItem>, startIndex: Int = 0) {
        withContext(dispatcher) {
            setPlayQueue(items)
            val queueIndex: Int = when {
                startIndex < 0 -> 0
                startIndex > items.size - 1 -> items.size - 1
                else -> startIndex
            }
            playbackQueue.setIndex(queueIndex)
            playbackQueue.notifyQueueChanged()
        }
    }

    suspend fun setPlayQueue(items: List<MediaSessionCompat.QueueItem>) {
        withContext(dispatcher) {
            playbackQueue.setItems(items)
        }
    }

    suspend fun maybeShuffleNewQueue(startIndex: Int) {
        withContext(dispatcher) {
            if (isShuffling()) {
                playbackQueue.shuffle(startIndex)
            }
        }
    }

    private suspend fun getDataSourceForURI(uri: Uri): MediaDataSource {
        logger.debug(Util.TAGCRT(TAG, coroutineContext), "getDataSourceForURI($uri)")
        try {
            return audioFileStorage.getBufferedDataSourceForURI(uri)
        } catch (exc: UnsupportedOperationException) {
            logger.exception(Util.TAGCRT(TAG, coroutineContext), "Cannot get data source for URI: $uri", exc)
            playerStatus.errorCode = PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR
            playerStatus.errorMsg = "No datasource"
            playerStatus.playbackState = PlaybackStateCompat.STATE_ERROR
            notifyPlayerStatusChange()
            throw exc
        }
    }

    private suspend fun resetAllPlayers() {
        logger.debug(Util.TAGCRT(TAG, coroutineContext), "resetAllPlayers()")
        reset(mediaPlayerFlip)
        reset(mediaPlayerFlop)
        notifyPlayerStatusChange()
    }

    suspend fun shutdown() {
        withContext(dispatcher) {
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "shutdown()")
            effectsFlip?.shutdown()
            effectsFlop?.shutdown()
            cancelSetupNextPlayerJob()
            resetAllPlayers()
            releaseAllPlayers()
        }
    }

    suspend fun reset() {
        withContext(dispatcher) {
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "reset()")
            stop()
            setPlayQueueAndNotify(listOf())
            reInitAllPlayers()
            resetPlayerStatus()
            notifyPlayerStatusChange()
        }
    }

    private suspend fun releaseAllPlayers() {
        logger.debug(Util.TAGCRT(TAG, coroutineContext), "releaseAllPlayers()")
        mediaPlayerFlip?.release()
        setState(mediaPlayerFlip, AudioPlayerState.END)
        mediaPlayerFlop?.release()
        setState(mediaPlayerFlop, AudioPlayerState.END)
        mediaPlayerFlip = null
        mediaPlayerFlop = null
        effectsFlip = null
        effectsFlop = null
        currentMediaPlayer = null
    }

    suspend fun prepareForEject() {
        withContext(dispatcher) {
            stop()
            setPlayQueueAndNotify(listOf())
            reInitAllPlayers()
            resetPlayerStatus()
            notifyPlayerStatusChange()
        }
    }

    suspend fun reInitAfterError() {
        withContext(dispatcher) {
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "reInitAfterError()")
            delay(4000)
            isRecoveringFromIOError = true
            reInitAllPlayers()
            isRecoveringFromIOError = false
            resetPlayerStatus()
            notifyPlayerStatusChange()
        }
    }

    private suspend fun reInitAllPlayers() {
        withContext(dispatcher) {
            cancelSetupNextPlayerJob()
            // depending on error the player might continue playing buffered data for some time. We can't stop it
            resetAllPlayers()
            releaseAllPlayers()
            initMediaPlayers()
            initEffects()
        }
    }

    private fun resetPlayerStatus() {
        playerStatus = AudioPlayerStatus(PlaybackStateCompat.STATE_NONE)
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
            AudioPlayerState.PREPARED,
            AudioPlayerState.STARTED,
            AudioPlayerState.PAUSED,
            AudioPlayerState.STOPPED,
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

    fun getRepeatMode(): RepeatMode {
        return playerStatus.repeatMode
    }

    suspend fun enableEqualizer() {
        withContext(dispatcher) {
            assertEffectsObjectsExist()
            effectsFlip?.enableEQ()
            effectsFlop?.enableEQ()
        }
    }

    suspend fun disableEqualizer() {
        withContext(dispatcher) {
            assertEffectsObjectsExist()
            effectsFlip?.disableEQ()
            effectsFlop?.disableEQ()
        }
    }

    suspend fun enableReplayGain() {
        withContext(dispatcher) {
            assertEffectsObjectsExist()
            effectsFlip?.enableGain()
            effectsFlop?.enableGain()
        }
    }

    suspend fun disableReplayGain() {
        withContext(dispatcher) {
            assertEffectsObjectsExist()
            effectsFlip?.disableGain()
            effectsFlop?.disableGain()
        }
    }

    suspend fun setReplayGain(gain: Float) {
        withContext(dispatcher) {
            assertEffectsObjectsExist()
            effectsFlip?.inputGainDecibel = gain
            effectsFlop?.inputGainDecibel = gain
        }
    }

    suspend fun setEqualizerPreset(preset: EqualizerPreset) {
        withContext(dispatcher) {
            assertEffectsObjectsExist()
            effectsFlip?.storeAndApplyEQPreset(preset)
            effectsFlop?.storeAndApplyEQPreset(preset)
        }
    }

    suspend fun setEqualizerBandValue(equalizerBandIndex: Int, equalizerBandValue: Float) {
        withContext(dispatcher) {
            assertEffectsObjectsExist()
            effectsFlip?.storeAndApplyEqualizerBandValue(equalizerBandIndex, equalizerBandValue)
            effectsFlop?.storeAndApplyEqualizerBandValue(equalizerBandIndex, equalizerBandValue)
        }
    }

    suspend fun onIncreasedPlaybackSpeedSettingChanged() {
        withContext(dispatcher) {
            if (playbackSpeed != DEFAULT_PLAYBACK_SPEED) {
                updateIncreasedPlaybackSpeedFromSharedPrefs()
                if (isPlaying()) {
                    updatePlaybackSpeedInPlayer(currentMediaPlayer)
                }
                notifyPlayerStatusChange()
            }
        }
    }

    suspend fun increasePlaybackSpeed() {
        withContext(dispatcher) {
            updateIncreasedPlaybackSpeedFromSharedPrefs()
            if (isPlaying()) {
                updatePlaybackSpeedInPlayer(currentMediaPlayer)
            }
            notifyPlayerStatusChange()
        }
    }

    private suspend fun updateIncreasedPlaybackSpeedFromSharedPrefs() {
        val increasedPlaybackSpeedSetting = sharedPrefs.getIncreasedPlaybackSpeedSetting(context)
        logger.debug(
            Util.TAGCRT(TAG, coroutineContext),
            "updateIncreasedPlaybackSpeedFromSharedPrefs(): $increasedPlaybackSpeedSetting"
        )
        playbackSpeed = when (increasedPlaybackSpeedSetting) {
            IncreasedPlaybackSpeedSetting.ONE_DOT_TWO -> {
                1.2F
            }
            IncreasedPlaybackSpeedSetting.ONE_DOT_FIVE -> {
                1.5F
            }
            IncreasedPlaybackSpeedSetting.TWO_DOT_ZERO -> {
                2.0F
            }
        }
    }

    suspend fun setNormalPlaybackSpeed() {
        withContext(dispatcher) {
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "setNormalPlaybackSpeed()")
            playbackSpeed = 1.0F
            if (isPlaying()) {
                updatePlaybackSpeedInPlayer(currentMediaPlayer)
            }
            notifyPlayerStatusChange()
        }
    }

    private fun updatePlaybackSpeedInPlayer(mediaPlayer: MediaPlayer?) {
        try {
            // Must be called after setting datasource on player, otherwise will throw IllegalStateException.
            // This will start the player in case it is paused.
            mediaPlayer?.apply {
                playbackParams = playbackParams.setSpeed(playbackSpeed)
            }
        } catch (exc: IllegalStateException) {
            logger.exception(TAG, exc.message.toString(), exc)
        }
    }

    fun onBroadcastPlay() {
        launchInScopeSafely { start() }
    }

    fun onBroadcastPause() {
        launchInScopeSafely { pause() }
    }

    fun onBroadcastNext() {
        launchInScopeSafely { skipNextTrack() }
    }

    fun onBroadcastPrev() {
        launchInScopeSafely { skipPreviousTrack() }
    }

    private fun assertEffectsObjectsExist() {
        if (effectsFlip == null || effectsFlop == null) {
            throw MissingEffectsException()
        }
    }

    private fun launchInScopeSafely(func: suspend (CoroutineScope) -> Unit): Job {
        return Util.launchInScopeSafely(scope, dispatcher, logger, TAG, crashReporting, func)
    }

    suspend fun isPlaybackQueueEmpty(): Boolean = withContext(dispatcher) {
        return@withContext playbackQueue.getCurrentItem() == null
    }

    suspend fun isIdle(): Boolean = withContext(dispatcher) {
        return@withContext getState(currentMediaPlayer) == AudioPlayerState.IDLE
    }

    suspend fun isPlaying(): Boolean = withContext(dispatcher) {
        return@withContext getState(currentMediaPlayer) == AudioPlayerState.STARTED
    }

}
