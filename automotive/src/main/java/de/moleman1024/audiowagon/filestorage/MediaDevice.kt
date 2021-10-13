/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage

import android.media.MediaDataSource
import android.net.Uri

interface MediaDevice {
   fun getDataSourceForURI(uri: Uri): MediaDataSource
   fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource
   fun getID(): String
   fun getName(): String
}
