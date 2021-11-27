/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.persistence

import android.content.Context
import androidx.preference.PreferenceManager
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.persistence.PersistentPlaybackState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

const val PERSISTENT_STORAGE_CURRENT_TRACK_ID = "current_track_id"
const val PERSISTENT_STORAGE_CURRENT_TRACK_POS = "current_track_pos"
const val PERSISTENT_STORAGE_QUEUE_INDEX = "queue_index"
const val PERSISTENT_STORAGE_QUEUE_IDS = "queue_ids"
const val PERSISTENT_STORAGE_IS_SHUFFLING = "is_shuffling"
const val PERSISTENT_STORAGE_IS_REPEATING = "is_repeating"
const val PERSISTENT_STORAGE_LAST_CONTENT_HIERARCHY_ID = "last_content_hierarch_id"

private const val TAG = "PersistentStorage"
private val logger = Logger
private const val QUEUE_ID_SEPARATOR = ";"

class PersistentStorage(context: Context, private val dispatcher: CoroutineDispatcher) {
    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    suspend fun store(state: PersistentPlaybackState) {
        logger.debug(TAG, "Storing playback state: $state")
        val queueIDSConcat = state.queueIDs.joinToString(QUEUE_ID_SEPARATOR)
        withContext(dispatcher) {
            sharedPreferences.edit()
                .putString(PERSISTENT_STORAGE_CURRENT_TRACK_ID, state.trackID)
                .putLong(PERSISTENT_STORAGE_CURRENT_TRACK_POS, state.trackPositionMS)
                .putInt(PERSISTENT_STORAGE_QUEUE_INDEX, state.queueIndex)
                .putString(PERSISTENT_STORAGE_QUEUE_IDS, queueIDSConcat)
                .putBoolean(PERSISTENT_STORAGE_IS_SHUFFLING, state.isShuffling)
                .putBoolean(PERSISTENT_STORAGE_IS_REPEATING, state.isRepeating)
                .putString(PERSISTENT_STORAGE_LAST_CONTENT_HIERARCHY_ID, state.lastContentHierarchyID)
                .apply()
        }
    }

    suspend fun retrieve(): PersistentPlaybackState = withContext(dispatcher) {
        val trackID: String = sharedPreferences.getString(PERSISTENT_STORAGE_CURRENT_TRACK_ID, "") ?: ""
        val trackPos: Long = sharedPreferences.getLong(PERSISTENT_STORAGE_CURRENT_TRACK_POS, 0)
        val queueIndex: Int = sharedPreferences.getInt(PERSISTENT_STORAGE_QUEUE_INDEX, 0)
        val queueIDsConcat: String = sharedPreferences.getString(PERSISTENT_STORAGE_QUEUE_IDS, "") ?: ""
        val queueIDs: List<String> = queueIDsConcat.split(QUEUE_ID_SEPARATOR)
        val isShuffling: Boolean = sharedPreferences.getBoolean(PERSISTENT_STORAGE_IS_SHUFFLING, false)
        val isRepeating: Boolean = sharedPreferences.getBoolean(PERSISTENT_STORAGE_IS_REPEATING, false)
        val lastContentHierarchyID: String =
            sharedPreferences.getString(PERSISTENT_STORAGE_LAST_CONTENT_HIERARCHY_ID, "") ?: ""
        val state = PersistentPlaybackState(trackID)
        state.trackPositionMS = trackPos
        state.queueIndex = queueIndex
        state.queueIDs = queueIDs
        state.isShuffling = isShuffling
        state.isRepeating = isRepeating
        state.lastContentHierarchyID = lastContentHierarchyID
        logger.debug(TAG, "Retrieved persisted state: $state")
        return@withContext state
    }

    suspend fun clean() {
        logger.debug(TAG, "Cleaning persisted playback state")
        withContext(dispatcher) {
            sharedPreferences.edit()
                .putString(PERSISTENT_STORAGE_CURRENT_TRACK_ID, "")
                .putLong(PERSISTENT_STORAGE_CURRENT_TRACK_POS, 0)
                .putInt(PERSISTENT_STORAGE_QUEUE_INDEX, 0)
                .putString(PERSISTENT_STORAGE_QUEUE_IDS, "")
                .putString(PERSISTENT_STORAGE_LAST_CONTENT_HIERARCHY_ID, "")
                .apply()
        }
    }

}
