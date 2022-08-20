/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.log

import android.util.Log

/**
 * Alternate logger class used in unit tests where Android logger is not available
 */
object Logger : LoggerInterface {

    override fun verbose(tag: String?, msg: String) {
        println("[VERBOSE] $tag: $msg")
    }

    override fun debug(tag: String?, msg: String) {
        println("[  DEBUG] $tag: $msg")
    }

    override fun error(tag: String?, msg: String) {
        println("[  ERROR] $tag: $msg")
    }

    override fun exception(tag: String?, msg: String, exc: Throwable) {
        println("[  ERROR] $tag: $msg, ${Log.getStackTraceString(exc)}")
    }

    override fun exceptionLogcatOnly(tag: String?, msg: String, exc: Throwable) {
        exception(tag, msg, exc)
    }

    override fun info(tag: String?, msg: String) {
        println("[   INFO] $tag: $msg")
    }

    override fun warning(tag: String?, msg: String) {
        println("[   WARN] $tag: $msg")
    }

    override fun setStoreLogs(isStoreLogs: Boolean) {

    }

    override fun getStoredLogs(): List<String> {
        return listOf()
    }
}
