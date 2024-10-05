/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.util

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import androidx.test.platform.app.InstrumentationRegistry
import de.moleman1024.audiowagon.AudioBrowserService
import de.moleman1024.audiowagon.enums.IndexingStatus
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert
import java.io.File
import java.io.FileOutputStream


private const val TAG = "TestUtils"

object TestUtils {
    fun waitForTrueOrFail(func: () -> Boolean, timeoutMS: Int, name: String) {
        var timeout = 0
        while (!func()) {
            if (timeout > timeoutMS) {
                Assert.fail("Timed out waiting for: $name")
            }
            Thread.sleep(10)
            timeout += 10
        }
    }

    fun assertFalseForMillisecOrFail(func: () -> Boolean, timeoutMS: Int) {
        var timeout = 0
        while (timeout <= timeoutMS) {
            val result = func()
            if (result) {
                Assert.fail("Assertion which should have been false for $timeoutMS ms turned true")
            }
            Thread.sleep(10)
            timeout += 10
        }
    }

    @ExperimentalCoroutinesApi
    fun waitForIndexingCompleted(audioBrowserService: AudioBrowserService, timeoutMS: Int = 1000 * 10) {
        Logger.debug(TAG, "waitForIndexingCompleted()")
        waitForTrueOrFail(
            {
                val isIndexingCompleted = audioBrowserService.getIndexingStatus().any {
                    it == IndexingStatus.COMPLETED
                }
                Thread.sleep(100)
                return@waitForTrueOrFail isIndexingCompleted
            },
            timeoutMS, "waitForIndexingCompleted()"
        )
    }

    @ExperimentalCoroutinesApi
    fun waitForAudioPlayerState(state: Int, audioBrowserService: AudioBrowserService, timeoutMS: Int = 1000 * 10) {
        waitForTrueOrFail({ audioBrowserService.getAudioPlayerStatus().playbackState == state }, timeoutMS,
            "waitForAudioPlayerState($state)")
    }

    fun deleteDatabaseDirectory() {
        val databasesDir = getDatabaseDirectory()
        databasesDir.listFiles()?.forEach {
            Logger.debug(TAG, "Deleting: $it")
            it.delete()
        }
        databasesDir.delete()
    }

    private fun getDatabaseDirectory(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context?.dataDir.toString() + "/databases")
    }

    fun copyAssetToDatbaseDirectory(context: Context, assetFileName: File, outFileName: String) {
        Logger.debug(TAG, "Copying to database directory: $assetFileName")
        val databasesDir = getDatabaseDirectory()
        val assetManager: AssetManager = context.assets
        val assetFile = assetManager.open(assetFileName.name)
        if (!databasesDir.exists()) {
            databasesDir.mkdirs()
        }
        val outFile = File("$databasesDir/$outFileName")
        val outStream = FileOutputStream(outFile)
        val buffer = ByteArray(1024)
        var read: Int
        while (assetFile.read(buffer).also { read = it } != -1) {
            outStream.write(buffer, 0, read)
        }
        assetFile.close()
        outStream.flush()
        outStream.close()
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

