/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.log

import android.content.Context
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase
import de.moleman1024.audiowagon.SharedPrefs
import de.moleman1024.audiowagon.Util
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.tasks.await

private const val TAG = "CrashReporting"
private val logger = Logger

class CrashReporting(
    context: Context,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    sharedPrefs: SharedPrefs
) {
    private val crashlytics = Firebase.crashlytics
    private var isEnabled: Boolean = false
    private var isDebugBuild: Boolean = false
    private var hasCheckedForPendingReports: Boolean = false

    init {
        if (Util.isDebugBuild(context) || Util.isRunningInEmulator()) {
            isDebugBuild = true
        }
        if (sharedPrefs.isCrashReportingEnabled(context)) {
            enable()
        } else {
            disable()
        }
        sendPendingReports()
    }

    private fun sendPendingReports() {
        if (!isEnabled || hasCheckedForPendingReports) {
            return
        }
        if (!isDebugBuild) {
            launchInScopeSafely {
                val isAnyReportPending = crashlytics.checkForUnsentReports().await()
                if (isAnyReportPending) {
                    logger.debug(TAG, "Sending pending crash reports")
                    crashlytics.sendUnsentReports()
                }
                // we only need to check for pending crash reports once each time the OS process is started
                hasCheckedForPendingReports = true
            }
        } else {
            logger.verbose(TAG, "sendPendingReports()")
        }
    }

    fun enable() {
        if (isEnabled) {
            return
        }
        logger.debug(TAG, "enable()")
        if (!isDebugBuild) {
            crashlytics.setCrashlyticsCollectionEnabled(true)
        }
        isEnabled = true
    }

    fun disable() {
        logger.debug(TAG, "disable()")
        if (!isDebugBuild) {
            crashlytics.deleteUnsentReports()
            crashlytics.setCrashlyticsCollectionEnabled(false)
        }
        isEnabled = false
    }

    fun recordException(exc: Throwable) {
        if (!isEnabled) {
            return
        }
        if (!isDebugBuild) {
            crashlytics.recordException(exc)
        } else {
            logger.verbose(TAG, "Recording exception: $exc")
        }
    }

    fun logMessage(msg: String) {
        if (!isEnabled) {
            return
        }
        if (!isDebugBuild) {
            crashlytics.log(msg)
        } else {
            logger.verbose(TAG, "Logging: $msg")
        }
    }

    fun logLastLogMessages() {
        if (!isEnabled || isDebugBuild) {
            return
        }
        launchInScopeSafely {
            val messages = logger.getLogsForCrashReporting()
            messages.forEach {
                crashlytics.log(it)
            }
        }
    }

    private fun launchInScopeSafely(func: suspend (CoroutineScope) -> Unit) {
        Util.launchInScopeSafely(scope, dispatcher, logger, TAG, crashReporting = null, func)
    }

}
