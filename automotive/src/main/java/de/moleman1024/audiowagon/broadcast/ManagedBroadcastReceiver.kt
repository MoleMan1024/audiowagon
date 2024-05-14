/*
SPDX-FileCopyrightText: 2021-2024 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build

abstract class ManagedBroadcastReceiver : BroadcastReceiver() {
    abstract fun getIntentFilter(): IntentFilter
    abstract fun getFlags(): Int

    fun getExportedFlags(): Int {
        var flags = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = Context.RECEIVER_EXPORTED
        }
        return flags
    }

    fun getNotExportedFlags(): Int {
        var flags = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = Context.RECEIVER_NOT_EXPORTED
        }
        return flags
    }
}
