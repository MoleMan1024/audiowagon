/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.util

import android.content.Context
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import de.moleman1024.audiowagon.log.Logger
import org.junit.Assert
import java.io.File

private const val TAG = "TestUtils"

object TestUtils {
    fun waitForTrueOrFail(func: () -> Boolean, timeoutMS: Int) {
        var timeout = 0
        while (!func()) {
            if (timeout > timeoutMS) {
                Assert.fail("Timed out waiting for $func")
            }
            Thread.sleep(10)
            timeout += 10
        }
    }

    fun deleteDatabaseDirectory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databasesDir = File(context?.dataDir.toString() + "/databases")
        databasesDir.listFiles()?.forEach {
            Logger.debug(TAG, "Deleting: $it")
            it.delete()
        }
        databasesDir.delete()
    }

    fun createSharedPrefsVersion110(context: Context): SharedPreferences {
        val sharedPreferences = context.getSharedPreferences("v110", Context.MODE_PRIVATE)
        // We don't have permission to write shared preferences when using the emulator, however we can use commit()
        // here to have those changes in memory only and ignore that they will not be written to disk
        sharedPreferences.edit().apply {
            putInt("usbStatusResID", 2131689640)
            putBoolean("enableReplayGain", true)
            putString("agreedLegalVersion", "1.0")
            putString("equalizerPreset", "LESS_BASS")
            putBoolean("readMetadata", true)
            putBoolean("logToUSB", true)
            putBoolean("enableEqualizer", true)
        }.commit()
        return sharedPreferences
    }

    fun createSharedPrefsNotAgreedLegal(context: Context): SharedPreferences {
        val sharedPreferences = context.getSharedPreferences("notAgreedLegal", Context.MODE_PRIVATE)
        sharedPreferences.edit().apply {
            putString("agreedLegalVersion", "")
        }.commit()
        return sharedPreferences
    }

}

