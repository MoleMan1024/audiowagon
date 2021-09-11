/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.player

// TODO: rework this, these items are kind of unrelated
data class AudioSessionChange(val type: AudioSessionChangeType) {
    var queueID: Long = -1L
    var contentHierarchyID: String = ""
    var artistToPlay: String = ""
    var albumToPlay: String = ""
    var trackToPlay: String = ""
    var unspecificToPlay: String = ""
    var equalizerPreset: String = ""

    override fun toString(): String {
        return "AudioSessionChange(type=$type, queueID=$queueID, " +
                "contentHierarchyID='$contentHierarchyID', artistToPlay='$artistToPlay', albumToPlay='$albumToPlay', " +
                "trackToPlay='$trackToPlay', unspecificToPlay='$unspecificToPlay', equalizerPreset='$equalizerPreset')"
    }

}
