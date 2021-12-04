/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import de.moleman1024.audiowagon.player.EqualizerPreset
import de.moleman1024.audiowagon.util.TestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert
import org.junit.Before
import org.junit.Test

/**
 * This class contains tests for the "shared preferences" which are written to disk in xml format.
 * In case the shared preferences are changed in future versions of the app we want to make sure that the data from
 * old versions is still compatible when upgrading to latest app version.
 */
@ExperimentalCoroutinesApi
class SharedPrefsTest {

    private lateinit var sharedPrefsVersion110: SharedPreferences

    @Before
    fun setUp() {
        sharedPrefsVersion110 =
            TestUtils.createSharedPrefsVersion110(InstrumentationRegistry.getInstrumentation().context)
    }

    @Test
    fun isLegalDisclaimerAgreed_sharedPrefAppVersion110_isCompatible() {
        Assert.assertFalse(SharedPrefs.isLegalDisclaimerAgreed(sharedPrefsVersion110))
    }

    @Test
    fun setLegalDisclaimerAgreed_legalDisclNotAgreed_agreementIsStored() {
        val sharedPrefs =
            TestUtils.createSharedPrefsNotAgreedLegal(InstrumentationRegistry.getInstrumentation().context)
        Assert.assertFalse(SharedPrefs.isLegalDisclaimerAgreed(sharedPrefs))
        SharedPrefs.setLegalDisclaimerAgreed(sharedPrefs)
        Assert.assertTrue(SharedPrefs.isLegalDisclaimerAgreed(sharedPrefs))
    }

    @Test
    fun isReplayGainEnabled_sharedPrefAppVersion110_isCompatible() {
        Assert.assertTrue(SharedPrefs.isReplayGainEnabled(sharedPrefsVersion110))
    }

    @Test
    fun getEQPreset_sharedPrefAppVersion110_isCompatible() {
        Assert.assertTrue(EqualizerPreset.values().any { it.name == SharedPrefs.getEQPreset(sharedPrefsVersion110) })
    }

    @Test
    fun isEQEnabled_sharedPrefAppVersion110_isCompatible() {
        Assert.assertTrue(SharedPrefs.isEQEnabled(sharedPrefsVersion110))
    }

    @Test
    fun getMetadataReadSetting_sharedPrefAppVersion110_isCompatible() {
        Assert.assertEquals("WHEN_USB_CONNECTED", SharedPrefs.getMetadataReadSetting(sharedPrefsVersion110))
    }

    @Test
    fun getAudioFocusSetting_sharedPrefAppVersion110_isCompatible() {
        Assert.assertEquals("PAUSE", SharedPrefs.getAudioFocusSetting(sharedPrefsVersion110))
    }

    @Test
    fun isLogToUSBEnabled_sharedPrefAppVersion110_isCompatible() {
        Assert.assertTrue(SharedPrefs.isLogToUSBEnabled(sharedPrefsVersion110))
    }

    @Test
    fun isCrashReportingEnabled_sharedPrefAppVersion110_isCompatible() {
        Assert.assertFalse(SharedPrefs.isCrashReportingEnabled(sharedPrefsVersion110))
    }

    @Test
    fun getUSBStatusResID_sharedPrefAppVersion110_isCompatible() {
        Assert.assertEquals(2131689640, SharedPrefs.getUSBStatusResID(sharedPrefsVersion110))
    }

    @Test
    fun setUSBStatusResID_default_changesResourceID() {
        Assert.assertEquals(2131689640, SharedPrefs.getUSBStatusResID(sharedPrefsVersion110))
        val resID = 12345
        SharedPrefs.setUSBStatusResID(sharedPrefsVersion110, resID)
        Assert.assertEquals(resID, SharedPrefs.getUSBStatusResID(sharedPrefsVersion110))
    }

}
