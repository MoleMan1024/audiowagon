/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.content.SharedPreferences
import android.support.v4.media.MediaBrowserCompat
import androidx.test.platform.app.InstrumentationRegistry
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.enums.EqualizerPreset
import de.moleman1024.audiowagon.util.ServiceFixture
import de.moleman1024.audiowagon.util.TestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test

private const val TAG = "SharedPrefsTest"

/**
 * This class contains tests for the "shared preferences" which are written to disk in xml format.
 * In case the shared preferences are changed in future versions of the app we want to make sure that the data from
 * old versions is still compatible when upgrading to latest app version.
 */
@ExperimentalCoroutinesApi
class SharedPrefsTest {
    private lateinit var sharedPreferencesVersion110: SharedPreferences
    private val sharedPrefs = SharedPrefs()
    private lateinit var serviceFixture: ServiceFixture
    private lateinit var browser: MediaBrowserCompat
    private lateinit var audioBrowserService: AudioBrowserService

    @Before
    fun setUp() {
        Logger.debug(TAG, "setUp()")
        sharedPreferencesVersion110 =
            TestUtils.createSharedPrefsVersion110(InstrumentationRegistry.getInstrumentation().context)
        serviceFixture = ServiceFixture()
        browser = serviceFixture.createMediaBrowser()
        audioBrowserService = serviceFixture.getAudioBrowserService()
    }

    @After
    fun tearDown() {
        Logger.debug(TAG, "tearDown()")
        serviceFixture.shutdown()
    }

    @Test
    fun isLegalDisclaimerAgreed_sharedPrefAppVersion110_isCompatible() {
        Assert.assertFalse(sharedPrefs.isLegalDisclaimerAgreed(sharedPreferencesVersion110))
    }

    @Test
    fun setLegalDisclaimerAgreed_legalDisclNotAgreed_agreementIsStored() {
        val sharedPreferences =
            TestUtils.createSharedPrefsNotAgreedLegal(InstrumentationRegistry.getInstrumentation().context)
        Assert.assertFalse(sharedPrefs.isLegalDisclaimerAgreed(sharedPreferences))
        sharedPrefs.setLegalDisclaimerAgreed(sharedPreferences)
        Assert.assertTrue(sharedPrefs.isLegalDisclaimerAgreed(sharedPreferences))
    }

    @Test
    fun isReplayGainEnabled_sharedPrefAppVersion110_isCompatible() {
        Assert.assertTrue(sharedPrefs.isReplayGainEnabled(sharedPreferencesVersion110))
    }

    @Test
    fun getEQPreset_sharedPrefAppVersion110_isCompatible() {
        Assert.assertTrue(EqualizerPreset.values().any { it.name == sharedPrefs.getEQPreset(sharedPreferencesVersion110) })
    }

    @Test
    fun isEQEnabled_sharedPrefAppVersion110_isCompatible() {
        Assert.assertTrue(sharedPrefs.isEQEnabled(sharedPreferencesVersion110))
    }

    @Test
    fun getMetadataReadSetting_sharedPrefAppVersion110_isCompatible() {
        Assert.assertEquals("WHEN_USB_CONNECTED", sharedPrefs.getMetadataReadSetting(sharedPreferencesVersion110))
    }

    @Test
    fun getAudioFocusSetting_sharedPrefAppVersion110_isCompatible() {
        Assert.assertEquals("PAUSE", sharedPrefs.getAudioFocusSetting(sharedPreferencesVersion110))
    }

    @Test
    fun isLogToUSBEnabled_sharedPrefAppVersion110_isCompatible() {
        Assert.assertTrue(sharedPrefs.isLogToUSBEnabled(sharedPreferencesVersion110))
    }

    @Test
    fun isCrashReportingEnabled_sharedPrefAppVersion110_isCompatible() {
        Assert.assertFalse(sharedPrefs.isCrashReportingEnabled(sharedPreferencesVersion110))
    }

    @Test
    fun getUSBStatusResID_sharedPrefAppVersion110_isCompatible() {
        Assert.assertEquals(2131689640, sharedPrefs.getUSBStatusResID(sharedPreferencesVersion110))
    }

    @Test
    fun setUSBStatusResID_default_changesResourceID() {
        Assert.assertEquals(2131689640, sharedPrefs.getUSBStatusResID(sharedPreferencesVersion110))
        val resID = 12345
        sharedPrefs.setUSBStatusResID(sharedPreferencesVersion110, resID)
        Assert.assertEquals(resID, sharedPrefs.getUSBStatusResID(sharedPreferencesVersion110))
    }

}
