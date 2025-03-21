/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary.contenthierarchy

import android.annotation.SuppressLint
import de.moleman1024.audiowagon.enums.ContentHierarchyType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ContentHierarchyID(
    var type: ContentHierarchyType
) {
    @SerialName("storage")
    var storageID: String = ""
    @SerialName("trk")
    var trackID: Long = -1
    // we use -99 instead of -1 here because default values are not serialized to JSON and we use -1 for "unknown
    // artist/album"
    @SerialName("art")
    var artistID: Long = -99
    @SerialName("albArt")
    var albumArtistID: Long = -99
    @SerialName("alb")
    var albumID: Long = -99
    @SerialName("trkGrp")
    var trackGroupIndex: Int = -1
    @SerialName("artGrp")
    var artistGroupIndex: Int = -1
    @SerialName("albGrp")
    var albumGroupIndex: Int = -1
    var path: String = ""
    @SerialName("dirGrp")
    var directoryGroupIndex: Int = -1

    override fun toString(): String {
        return "ContentHierarchyID(type=$type, storage=$storageID, trk=$trackID, art=$artistID, " +
                "albArt=$albumArtistID, " +
                "alb=$albumID, trkGrp=$trackGroupIndex, artGrp=$artistGroupIndex, " +
                "albGrp=$albumGroupIndex, path=$path, dirGrp=$directoryGroupIndex)"
    }
}
