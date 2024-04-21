/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.persistence

import de.moleman1024.audiowagon.enums.RepeatMode

data class PersistentPlaybackState(val trackID: String) {
    var trackPositionMS: Long = 0
    var queueIndex: Int = 0
    var queueIDs: List<String> = listOf()
    var isShuffling: Boolean = false
    var repeatMode: RepeatMode = RepeatMode.OFF
    var lastContentHierarchyID: String = ""

    override fun toString(): String {
        val numLimitItems = 4
        val queueIDsString: String = if (queueIDs.size > numLimitItems) {
            queueIDs.take(numLimitItems).joinToString(";") + " ..."
        } else {
            queueIDs.joinToString(";")
        }
        return "PersistentPlaybackState(" +
                "trackID='$trackID', " +
                "trackPositionMS=$trackPositionMS, " +
                "isShuffling=$isShuffling, " +
                "repeatMode=$repeatMode, " +
                "lastContentHierarchyID=$lastContentHierarchyID, " +
                "queueIndex=$queueIndex, " +
                "queueIDs=${queueIDsString})"
    }
}
