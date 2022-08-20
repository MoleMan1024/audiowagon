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
private const val NUM_LOG_BYTES_TO_SHOW = 32768

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
        logger.debug(TAG, "onStart()")
        super.onStart()
    }

    override fun onResume() {
        logger.debug(TAG, "onResume()")
        super.onResume()
        initTextViewWithLastLogLines()
        logger.addObserver(showLineCallback)
    }

    private fun initTextViewWithLastLogLines() {
        Util.launchInScopeSafely(lifecycleScope, Dispatchers.Main, logger, TAG, crashReporting = null) {
            val logTextView = findViewById<TextView>(R.id.logText)
            logTextView.text = ""
            val logLines = logger.getLogs(NUM_LOG_BYTES_TO_SHOW)
            logLines.forEach {
                logTextView.append(it)
            }
        }
    }

    override fun onPause() {
        logger.debug(TAG, "onPause()")
        logger.removeObserver(showLineCallback)
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
