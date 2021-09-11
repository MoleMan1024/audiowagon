/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.authorization

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import java.util.*
import kotlin.math.abs

class SDCardDevicePermissions(private val context: Context) {
    // see https://developer.android.com/training/data-storage/shared/media#direct-file-paths
    private val neededPermissions : Array<String> = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    private val permissionRequestUUID : Int = abs(UUID.randomUUID().hashCode())

    fun requestPermissions(activity: Activity) {
        activity.requestPermissions(neededPermissions, permissionRequestUUID)
    }

    fun isPermitted() : Boolean {
        return neededPermissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != permissionRequestUUID) {
            return
        }
        if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            throw RuntimeException("Permission denied")
        }
    }

}
