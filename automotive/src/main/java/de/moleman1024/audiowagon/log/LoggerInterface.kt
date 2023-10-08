/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.log

interface LoggerInterface {
    fun verbose(tag: String?, msg: String)
    fun debug(tag: String?, msg: String)
    fun error(tag: String?, msg: String)
    fun exception(tag: String?, msg: String, exc: Throwable)
    fun exceptionLogcatOnly(tag: String?, msg: String, exc: Throwable)
    fun info(tag: String?, msg: String)
    fun warning(tag: String?, msg: String)
    fun setStoreLogs(isStoreLogs: Boolean)
    fun getStoredLogs(): List<String>
}
