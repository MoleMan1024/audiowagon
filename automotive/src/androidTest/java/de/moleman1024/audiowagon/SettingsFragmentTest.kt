/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import androidx.fragment.app.testing.launchFragmentInContainer
import de.moleman1024.audiowagon.activities.SettingsFragment
import de.moleman1024.audiowagon.util.TestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test

@ExperimentalCoroutinesApi
class SettingsFragmentTest {

    /**
     * Regression test for https://github.com/MoleMan1024/audiowagon/issues/41
     */
    @Test
    fun onCreate_noDatabaseDir_doesNotCrash() {
        TestUtils.deleteDatabaseDirectory()
        launchFragmentInContainer<SettingsFragment>()
    }

}
