/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.player.data

import de.moleman1024.audiowagon.enums.AudioSessionChangeType
import de.moleman1024.audiowagon.enums.ViewTabSetting
import de.moleman1024.audiowagon.enums.AudioItemType

// TODO: rework this, these items are kind of unrelated
data class AudioSessionChange(val type: AudioSessionChangeType) {
    var queueID: Long = -1L
    var contentHierarchyID: String = ""
    var artistToPlay: String = ""
    var albumToPlay: String = ""
    var trackToPlay: String = ""
    var queryToPlay: String = ""
    var queryFocus: AudioItemType = AudioItemType.UNSPECIFIC
    var equalizerPreset: String = ""
    var metadataReadSetting: String = ""
    var audioFocusSetting: String = ""
    var albumStyleSetting: String = ""
    // https://github.com/MoleMan1024/audiowagon/issues/124
    val viewTabs: MutableList<ViewTabSetting> = mutableListOf()

    override fun toString(): String {
        return "AudioSessionChange(type=$type, queueID=$queueID, " +
                "contentHierarchyID='$contentHierarchyID', artistToPlay='$artistToPlay', albumToPlay='$albumToPlay', " +
                "trackToPlay='$trackToPlay', queryToPlay='$queryToPlay', queryFocus='$queryFocus' " +
                "equalizerPreset='$equalizerPreset', metadataReadSetting='$metadataReadSetting', " +
                "audioFocusSetting='$audioFocusSetting', albumStyleSetting='$albumStyleSetting', " +
                "viewTabs=$viewTabs)"
    }

}
