/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.persistence

data class PersistentPlaybackState(val trackID: String) {
    var trackPositionMS: Long = 0
    var queueIndex: Int = 0
    var queueIDs: List<String> = listOf()
    var isShuffling: Boolean = false
    var isRepeating: Boolean = false
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
                "isRepeating=$isRepeating, " +
                "lastContentHierarchyID=$lastContentHierarchyID, " +
                "queueIndex=$queueIndex, " +
                "queueIDs=${queueIDsString})"
    }
}
