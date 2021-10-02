/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.widget.Toast
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.*

private const val TAG = "AudioBrowserGUI"
private val logger = Logger
const val INDEXING_NOTIF_CHANNEL: String = "IndexingNotifChan"
const val INDEXING_NOTIFICATION_ID: Int = 25468

/**
 * See https://developers.google.com/cars/design/automotive-os/components/toast
 */
open class GUI(private val scope: CoroutineScope, private val context: Context) {
    private var changeIndexingNotifJob: Job? = null
    private var notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        deleteNotificationChannel()
        createNotificationChannel()
    }

    // TODO: almost duplicated code with AudioSession
    private fun createNotificationChannel() {
        if (notificationManager.getNotificationChannel(INDEXING_NOTIF_CHANNEL) != null) {
            logger.debug(TAG, "Indexing notification channel already exists")
            notificationManager.cancelAll()
            return
        }
        logger.debug(TAG, "Creating indexing notification channel")
        val importance = NotificationManager.IMPORTANCE_LOW
        // this appears in the notification area (pull down vertical line at top of screen in Polestar 2 while indexing)
        val notifChannelName = context.getString(R.string.notif_channel_name_indexing)
        val notifChannelDesc = context.getString(R.string.notif_channel_desc_indexing)
        val channel = NotificationChannel(INDEXING_NOTIF_CHANNEL, notifChannelName, importance).apply {
            description = notifChannelDesc
            setShowBadge(true)
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun showErrorToastMsg(text: String) {
        showToastMsg(context.getString(R.string.toast_error, text))
    }

    fun showToastMsg(text: String) {
        logger.debug(TAG, "Showing toast message: $text")
        scope.launch(Dispatchers.Main) {
            val toast = Toast.makeText(context, text, Toast.LENGTH_LONG)
            toast.show()
        }
    }

    fun showIndexingNotification() {
        changeIndexingNotifJob = scope.launch(Dispatchers.Main) {
            logger.debug(TAG, "Showing indexing notification")
            val builder = getIndexingNotificationBuilder()
            builder.setContentText(context.getString(R.string.notif_indexing_text_in_progress))
            builder.setProgress(0, 0, true)
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(INDEXING_NOTIFICATION_ID, builder.build())
        }
    }

    fun updateIndexingNotification(numItems: Int) {
        changeIndexingNotifJob = scope.launch(Dispatchers.Main) {
            logger.debug(TAG, "Updating indexing notification: numItems=$numItems")
            val builder = getIndexingNotificationBuilder()
            builder.setContentText(context.getString(R.string.notif_indexing_text_in_progress_num_items, numItems))
            builder.setProgress(0, 0, true)
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(INDEXING_NOTIFICATION_ID, builder.build())
        }
    }

    fun showIndexingFinishedNotification() {
        scope.launch(Dispatchers.Main) {
            logger.debug(TAG, "Showing indexing finished notification")
            changeIndexingNotifJob?.cancelAndJoin()
            val builder = getIndexingNotificationBuilder()
            builder.setContentText(context.getString(R.string.notif_indexing_text_completed))
            builder.setProgress(0, 0, false)
            builder.setTimeoutAfter(4000)
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(INDEXING_NOTIFICATION_ID, builder.build())
        }
    }

    private fun getIndexingNotificationBuilder(): Notification.Builder {
        return Notification.Builder(context, INDEXING_NOTIF_CHANNEL).apply {
            setContentTitle(context.getString(R.string.notif_indexing_title))
            setSmallIcon(R.drawable.ic_notif)
        }
    }

    fun removeIndexingNotification() {
        scope.launch(Dispatchers.Main) {
            logger.debug(TAG, "Removing indexing notification")
            changeIndexingNotifJob?.cancelAndJoin()
            notificationManager.cancel(INDEXING_NOTIFICATION_ID)
        }
    }

    private fun deleteNotificationChannel() {
        if (notificationManager.getNotificationChannel(INDEXING_NOTIF_CHANNEL) == null) {
            return
        }
        logger.debug(TAG, "Deleting indexing notification channel")
        notificationManager.deleteNotificationChannel(INDEXING_NOTIF_CHANNEL)
    }

    fun shutdown() {
        removeIndexingNotification()
        deleteNotificationChannel()
    }

    fun suspend() {
        removeIndexingNotification()
    }

}
