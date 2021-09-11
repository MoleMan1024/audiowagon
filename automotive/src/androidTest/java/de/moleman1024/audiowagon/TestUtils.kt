/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import org.junit.Assert

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
}

