/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.player.data

import de.moleman1024.audiowagon.enums.AudioSessionChangeType
import de.moleman1024.audiowagon.enums.ViewTabSetting
import de.moleman1024.audiowagon.enums.AudioItemType

const val EQUALIZER_BAND_VALUE_EMPTY = -99.0f

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
    var equalizerBand60: Float = EQUALIZER_BAND_VALUE_EMPTY
    var equalizerBand230: Float = EQUALIZER_BAND_VALUE_EMPTY
    var equalizerBand910: Float = EQUALIZER_BAND_VALUE_EMPTY
    var equalizerBand3600: Float = EQUALIZER_BAND_VALUE_EMPTY
    var equalizerBand14K: Float = EQUALIZER_BAND_VALUE_EMPTY
    var metadataReadSetting: String = ""
    var audioFocusSetting: String = ""
    var albumStyleSetting: String = ""
    // https://github.com/MoleMan1024/audiowagon/issues/124
    val viewTabs: MutableList<ViewTabSetting> = mutableListOf()
    // https://developer.android.com/reference/kotlin/androidx/media/utils/MediaConstants#TRANSPORT_CONTROLS_EXTRAS_KEY_SHUFFLE()
    var isShuffleRequested: Boolean = false

    override fun toString(): String {
        return "AudioSessionChange(type=$type, " +
                "queueID=$queueID, " +
                "contentHierarchyID='$contentHierarchyID', " +
                "artistToPlay='$artistToPlay', " +
                "albumToPlay='$albumToPlay', " +
                "trackToPlay='$trackToPlay', " +
                "queryToPlay='$queryToPlay', " +
                "queryFocus='$queryFocus' " +
                "equalizerPreset='$equalizerPreset', " +
                "equalizerBand60='$equalizerBand60', " +
                "equalizerBand230='$equalizerBand230', " +
                "equalizerBand910='$equalizerBand910', " +
                "equalizerBand3600='$equalizerBand3600', " +
                "equalizerBand14K='$equalizerBand14K', " +
                "metadataReadSetting='$metadataReadSetting', " +
                "audioFocusSetting='$audioFocusSetting', " +
                "albumStyleSetting='$albumStyleSetting', " +
                "viewTabs=$viewTabs, " +
                "shuffleRequested=$isShuffleRequested)"
    }

}
