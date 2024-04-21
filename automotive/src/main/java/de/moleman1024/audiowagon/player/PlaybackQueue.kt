/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.player

import android.support.v4.media.session.MediaSessionCompat
import de.moleman1024.audiowagon.enums.RepeatMode
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.player.data.PlaybackQueueChange
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

private const val TAG = "PlaybackQueue"
private val logger = Logger

class PlaybackQueue(private val dispatcher: CoroutineDispatcher) {
    private var playbackQueue = mutableListOf<MediaSessionCompat.QueueItem>()
    private var originalQueueOrder: Map<Long, Int> = mapOf()
    private var currentIndex: Int = -1
    private var repeatMode: RepeatMode = RepeatMode.OFF
    val observers = mutableListOf<(PlaybackQueueChange) -> Unit>()

    suspend fun setItems(items: List<MediaSessionCompat.QueueItem>) {
        withContext(dispatcher) {
            logger.debug(TAG, "setItems(num items=${items.size})")
            playbackQueue.clear()
            playbackQueue.addAll(items)
            originalQueueOrder = playbackQueue.withIndex().associate { it.value.queueId to it.index }
            currentIndex = 0
        }
    }

    /**
     * Called when a track has completed playback and next track in playback queue shall play
     */
    suspend fun incrementIndex() {
        withContext(dispatcher) {
            currentIndex = getNextIndex()
            notifyQueueChanged()
        }
    }

    /**
     * Returns the next index in the playback queue, if there is any. Takes both repeat modes into account
     */
    private suspend fun getNextIndex(): Int {
        return withContext(dispatcher) {
            return@withContext if (repeatMode == RepeatMode.REPEAT_ONE) {
                logger.debug(TAG, "Repeat one is on, returning current index in playback queue again")
                currentIndex
            } else {
                getNextIndexIgnoreRepeatOne()
            }
        }
    }

    /**
     * Returns the next index in the playback queue, if there is any. Takes REPEAT_ALL into account, but ignores
     * REPEAT_ONE. This is used for example when skipping to next/previous track manually.
     */
    suspend fun getNextIndexIgnoreRepeatOne(): Int {
        return withContext(dispatcher) {
            if (repeatMode == RepeatMode.OFF && isLastTrack()) {
                return@withContext -1
            }
            if (playbackQueue.size <= 0) {
                return@withContext -1
            }
            return@withContext (currentIndex + 1) % playbackQueue.size
        }
    }

    suspend fun setIndex(index: Int) {
        return withContext(dispatcher) {
            if (index < 0) {
                currentIndex = 0
                return@withContext
            }
            currentIndex = index
        }
    }

    suspend fun getPrevIndex(): Int {
        return withContext(dispatcher) {
            var prevIndex = currentIndex - 1
            if (prevIndex < 0) {
                // if we go beyoned start of playback queue
                prevIndex = if (repeatMode != RepeatMode.OFF) {
                    playbackQueue.size - 1
                } else {
                    -1
                }
            }
            return@withContext prevIndex
        }
    }

    /**
     * Returns true when the current track is the last track in playback queue (irregardless of repeat mode)
     */
    suspend fun isLastTrack(): Boolean {
        return withContext(dispatcher) {
            if (currentIndex == playbackQueue.size - 1) {
                return@withContext true
            }
            return@withContext false
        }
    }

    suspend fun getSize(): Int {
        return withContext(dispatcher) {
            return@withContext playbackQueue.size
        }
    }

    suspend fun getIDs(): List<String> {
        return withContext(dispatcher) {
            return@withContext playbackQueue.map { it.description?.mediaId ?: "" }
        }
    }

    suspend fun getIndex(): Int {
        return withContext(dispatcher) {
            return@withContext currentIndex
        }
    }

    suspend fun getIndexForQueueID(queueID: Long): Int = withContext(dispatcher) {
        return@withContext playbackQueue.indexOfFirst { it.queueId == queueID }
    }

    suspend fun getCurrentItem(): MediaSessionCompat.QueueItem? {
        return withContext(dispatcher) {
            if (currentIndex <= -1 || playbackQueue.size <= 0) {
                return@withContext null
            }
            if (currentIndex >= playbackQueue.size) {
                return@withContext null
            }
            return@withContext playbackQueue[currentIndex]
        }
    }

    suspend fun getNextItem(): MediaSessionCompat.QueueItem? {
        return withContext(dispatcher) {
            val nextIndex = getNextIndex()
            if (nextIndex <= -1 || playbackQueue.size <= 0) {
                return@withContext null
            }
            if (nextIndex >= playbackQueue.size) {
                return@withContext null
            }
            return@withContext playbackQueue[nextIndex]
        }
    }

    suspend fun hasEnded(): Boolean {
        return withContext(dispatcher) {
            if (repeatMode == RepeatMode.OFF && isLastTrack()) {
                return@withContext true
            }
            return@withContext false
        }
    }

    /**
     * Shuffles the playback queue.
     * The currently active item is kept and will move to first position in playback queue after shuffling
     */
    suspend fun shuffleExceptCurrentItem() {
        withContext(dispatcher) {
            val currentItem = getCurrentItem() ?: return@withContext
            playbackQueue.removeAt(currentIndex)
            playbackQueue.shuffle()
            currentIndex = 0
            playbackQueue.add(currentIndex, currentItem)
            notifyQueueChanged()
        }
    }

    /**
     * Shuffles the playback queue.
     * https://github.com/MoleMan1024/audiowagon/issues/120 : the item at the given starting index in the original
     * playback queue shall be at the beginning of the shuffled queue
     */
    suspend fun shuffle(startIndex: Int) {
        logger.debug(TAG, "shuffle(startIndex=$startIndex)")
        withContext(dispatcher) {
            val startItem = playbackQueue[startIndex]
            playbackQueue.removeAt(startIndex)
            playbackQueue.shuffle()
            currentIndex = 0
            playbackQueue.add(currentIndex, startItem)
            notifyQueueChanged()
        }
    }

    suspend fun unshuffle() {
        logger.debug(TAG, "unshuffle()")
        withContext(dispatcher) {
            val currentItem = getCurrentItem() ?: return@withContext
            playbackQueue = playbackQueue.sortedBy { originalQueueOrder[it.queueId] }.toMutableList()
            val newIndex = originalQueueOrder[currentItem.queueId]
            newIndex?.let { currentIndex = it }
            notifyQueueChanged()
        }
    }

    suspend fun setRepeatMode(mode: RepeatMode) {
        withContext(dispatcher) {
            repeatMode = mode
        }
    }

    suspend fun clear() {
        withContext(dispatcher) {
            playbackQueue.clear()
            originalQueueOrder = mapOf()
            currentIndex = -1
            notifyQueueChanged()
        }
    }

    suspend fun notifyQueueChanged() {
        val currentQueueItem: MediaSessionCompat.QueueItem? = getCurrentItem()
        val queueChange = PlaybackQueueChange(currentQueueItem, playbackQueue)
        observers.forEach { it(queueChange) }
    }

}
