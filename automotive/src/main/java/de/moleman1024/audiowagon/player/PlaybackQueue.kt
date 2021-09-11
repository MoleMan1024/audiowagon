/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.player

import android.support.v4.media.session.MediaSessionCompat
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

private const val TAG = "PlaybackQueue"
private val logger = Logger

class PlaybackQueue(private val dispatcher: CoroutineDispatcher) {
    private var playbackQueue = mutableListOf<MediaSessionCompat.QueueItem>()
    private var originalQueueOrder: Map<Long, Int> = mapOf()
    private var queueIndex: Int = -1
    val observers = mutableListOf<(PlaybackQueueChange) -> Unit>()
    var isRepeating: Boolean = false

    suspend fun setItems(items: List<MediaSessionCompat.QueueItem>) {
        withContext(dispatcher) {
            logger.debug(TAG, "setItems(num items=${items.size})")
            playbackQueue.clear()
            playbackQueue.addAll(items)
            originalQueueOrder = playbackQueue.withIndex().associate { it.value.queueId to it.index }
            queueIndex = 0
        }
    }

    suspend fun incrementIndex() {
        withContext(dispatcher) {
            queueIndex = getNextIndex()
            notifyQueueChanged()
        }
    }

    suspend fun getNextIndex(): Int {
        return withContext(dispatcher) {
            if (!isRepeating && isLastTrack()) {
                return@withContext -1
            }
            if (playbackQueue.size <= 0 || playbackQueue.size <= 0) {
                return@withContext -1
            }
            return@withContext (queueIndex + 1) % playbackQueue.size
        }
    }

    suspend fun setIndex(index: Int) {
        return withContext(dispatcher) {
            queueIndex = index
        }
    }

    suspend fun getPrevIndex(): Int {
        return withContext(dispatcher) {
            var prevIndex = queueIndex - 1
            if (prevIndex < 0) {
                prevIndex = if (isRepeating) {
                    playbackQueue.size - 1
                } else {
                    -1
                }
            }
            return@withContext prevIndex
        }
    }

    suspend fun isLastTrack(): Boolean {
        return withContext(dispatcher) {
            if (queueIndex == playbackQueue.size - 1) {
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
            return@withContext queueIndex
        }
    }

    suspend fun getIndexForQueueID(queueID: Long): Int = withContext(dispatcher) {
        return@withContext playbackQueue.indexOfFirst { it.queueId == queueID }
    }

    suspend fun getCurrentItem(): MediaSessionCompat.QueueItem? {
        return withContext(dispatcher) {
            if (queueIndex <= -1 || playbackQueue.size <= 0) {
                return@withContext null
            }
            return@withContext playbackQueue[queueIndex]
        }
    }

    suspend fun getNextItem(): MediaSessionCompat.QueueItem? {
        return withContext(dispatcher) {
            val nextIndex = getNextIndex()
            if (nextIndex <= -1 || playbackQueue.size <= 0) {
                return@withContext null
            }
            return@withContext playbackQueue[nextIndex]
        }
    }

    suspend fun hasEnded(): Boolean {
        return withContext(dispatcher) {
            if (!isRepeating && isLastTrack()) {
                return@withContext true
            }
            return@withContext false
        }
    }

    suspend fun setShuffleOn() {
        withContext(dispatcher) {
            val currentItem = getCurrentItem() ?: return@withContext
            playbackQueue.removeAt(queueIndex)
            playbackQueue.shuffle()
            playbackQueue.add(queueIndex, currentItem)
            notifyQueueChanged()
        }
    }

    suspend fun setShuffleOff() {
        withContext(dispatcher) {
            val currentItem = getCurrentItem() ?: return@withContext
            playbackQueue =
                playbackQueue.sortedBy { originalQueueOrder[it.queueId] }.toMutableList()
            val newIndex = originalQueueOrder[currentItem.queueId]
            newIndex?.let { queueIndex = it }
            notifyQueueChanged()
        }
    }

    suspend fun setRepeatOn() {
        withContext(dispatcher) {
            isRepeating = true
        }
    }

    suspend fun setRepeatOff() {
        withContext(dispatcher) {
            isRepeating = false
        }
    }

    suspend fun clear() {
        withContext(dispatcher) {
            playbackQueue.clear()
            originalQueueOrder = mapOf()
            queueIndex = -1
            notifyQueueChanged()
        }
    }

    suspend fun notifyQueueChanged() {
        val currentQueueItem: MediaSessionCompat.QueueItem? = getCurrentItem()
        val queueChange = PlaybackQueueChange(currentQueueItem, playbackQueue)
        observers.forEach { it(queueChange) }
    }

}
