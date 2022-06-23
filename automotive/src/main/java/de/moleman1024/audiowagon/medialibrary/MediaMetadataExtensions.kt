/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.medialibrary

import android.support.v4.media.MediaMetadataCompat

inline val MediaMetadataCompat.descriptionForLog
    get(): String {
        val stringBuilder = StringBuilder()
        stringBuilder.append("{\"mediaID\":\"" + getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID) + "\", ")
        stringBuilder.append("\"mediaURI\":\"" + getString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI) + "\", ")
        stringBuilder.append("\"artist\":\"" + getString(MediaMetadataCompat.METADATA_KEY_ARTIST) + "\" ,")
        stringBuilder.append("\"album\":\"" + getString(MediaMetadataCompat.METADATA_KEY_ALBUM) + "\" ,")
        stringBuilder.append("\"title\":\"" + getString(MediaMetadataCompat.METADATA_KEY_TITLE) + "\" ,")
        stringBuilder.append("\"trackNum\":" + getLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER) + ", ")
        stringBuilder.append("\"discNum\":" + getLong(MediaMetadataCompat.METADATA_KEY_DISC_NUMBER) + ", ")
        stringBuilder.append("\"year\":" + getLong(MediaMetadataCompat.METADATA_KEY_YEAR) + ", ")
        stringBuilder.append("\"duration\":" + getLong(MediaMetadataCompat.METADATA_KEY_DURATION) + ", ")
        stringBuilder.append("\"displayTitle\":\"" + getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE) + "\", ")
        stringBuilder.append(
            "\"displaySubtitle\":\"" + getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE) + "\", "
        )
        stringBuilder.append("\"artURI\":\"" + getString(MediaMetadataCompat.METADATA_KEY_ART_URI) + "\", ")
        stringBuilder.append("\"iconURI\":\"" + getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI) + "\"}")
        return stringBuilder.toString()
    }


