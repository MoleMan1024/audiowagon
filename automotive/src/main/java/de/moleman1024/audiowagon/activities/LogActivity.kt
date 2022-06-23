/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.activities

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope

private const val TAG = "LogActivity"
private val logger = Logger
private const val NUM_LAST_LOG_LINES_TO_SHOW = 100

class LogActivity : AppCompatActivity() {
    private val showLineCallback = { line: String ->
        Util.launchInScopeSafely(lifecycleScope, Dispatchers.Main, logger, TAG, crashReporting = null) {
            val logTextView = findViewById<TextView>(R.id.logText)
            logTextView.append(line)
        }.discard()
    }

    @Suppress("unused")
    private fun Any?.discard() = Unit

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        val toolbar = findViewById<Toolbar>(R.id.logToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setHomeButtonEnabled(true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationIcon(R.drawable.baseline_west_24)
    }

    override fun onStart() {
        super.onStart()
        Util.launchInScopeSafely(lifecycleScope, Dispatchers.Main, logger, TAG, crashReporting = null) {
            val logTextView = findViewById<TextView>(R.id.logText)
            Logger.getLastLogLines(NUM_LAST_LOG_LINES_TO_SHOW).forEach {
                logTextView.append(it)
            }
        }
    }

    override fun onResume() {
        logger.debug(TAG, "onResume()")
        super.onResume()
        Logger.addObserver(showLineCallback)
    }

    override fun onPause() {
        logger.debug(TAG, "onPause()")
        Logger.removeObserver(showLineCallback)
        super.onPause()
    }

    override fun onSupportNavigateUp(): Boolean {
        onCancel()
        return super.onSupportNavigateUp()
    }

    private fun onCancel() {
        logger.debug(TAG, "onCancel()")
        finish()
    }

    override fun onDestroy() {
        logger.debug(TAG, "onDestroy()")
        super.onDestroy()
    }

}
