/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import de.moleman1024.audiowagon.enums.SingletonCoroutineBehaviour
import de.moleman1024.audiowagon.log.CrashReporting
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

private const val TAG = "AudioBrowserGUI"
private val logger = Logger
const val INDEXING_NOTIFICATION_ID: Int = 25468
const val INDEXING_NOTIF_CHANNEL: String = "IndexingNotifChan"

open class GUI(
    private val scope: CoroutineScope, private val context: Context, crashReporting:
    CrashReporting
) {
    private var changeIndexingNotifJob: Job? = null
    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var isChannelCreated: AtomicBoolean = AtomicBoolean()
    private val singleThreadDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val notificationSingletonCoroutine =
        SingletonCoroutine("GUINotif", singleThreadDispatcher, scope.coroutineContext, crashReporting)

    init {
        notificationSingletonCoroutine.behaviour = SingletonCoroutineBehaviour.PREFER_FINISH
        isChannelCreated.set(false)
        deleteNotificationChannel()
        createNotificationChannel()
    }

    // TODO: almost duplicated code with AudioSession
    private fun createNotificationChannel() {
        if (notificationManager.getNotificationChannel(INDEXING_NOTIF_CHANNEL) != null) {
            isChannelCreated.set(true)
            logger.debug(TAG, "Indexing notification channel already exists")
            notificationSingletonCoroutine.launch {
                notificationManager.cancelAll()
            }
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
        isChannelCreated.set(true)
    }

    fun showIndexingNotification() {
        changeIndexingNotifJob = scope.launch(Dispatchers.Main) {
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "Showing indexing notification")
            val builder = getIndexingNotificationBuilder()
            builder.setContentText(context.getString(R.string.notif_indexing_text_in_progress))
            builder.setProgress(0, 0, true)
            sendNotification(builder)
        }
    }

    fun updateIndexingNotification(numItems: Int) {
        changeIndexingNotifJob = scope.launch(Dispatchers.Main) {
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "Updating indexing notification: numItems=$numItems")
            val builder = getIndexingNotificationBuilder()
            builder.setContentText(context.getString(R.string.notif_indexing_text_in_progress_num_items, numItems))
            builder.setProgress(0, 0, true)
            sendNotification(builder)
        }
    }

    fun showIndexingFinishedNotification() {
        scope.launch(Dispatchers.Main) {
            logger.debug(Util.TAGCRT(TAG, coroutineContext), "Showing indexing finished notification")
            changeIndexingNotifJob?.cancelAndJoin()
            val builder = getIndexingNotificationBuilder()
            builder.setContentText(context.getString(R.string.notif_indexing_text_completed))
            builder.setProgress(0, 0, false)
            builder.setTimeoutAfter(POPUP_TIMEOUT_MS)
            sendNotification(builder)
        }
    }

    private fun sendNotification(builder: Notification.Builder) {
        notificationSingletonCoroutine.launch {
            if (!isChannelCreated.get()) {
                logger.warning(Util.TAGCRT(TAG, coroutineContext), "Cannot send notification, no channel available: ${builder.extras}")
                return@launch
            }
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
            notificationSingletonCoroutine.launch {
                notificationManager.cancel(INDEXING_NOTIFICATION_ID)
            }
        }
    }

    private fun deleteNotificationChannel() {
        if (notificationManager.getNotificationChannel(INDEXING_NOTIF_CHANNEL) == null) {
            isChannelCreated.set(false)
            return
        }
        logger.debug(TAG, "Deleting indexing notification channel")
        isChannelCreated.set(false)
        notificationManager.deleteNotificationChannel(INDEXING_NOTIF_CHANNEL)
    }

    fun shutdown() {
        runBlocking {
            removeIndexingNotification()
        }
        deleteNotificationChannel()
    }

    fun suspend() {
        runBlocking {
            removeIndexingNotification()
        }
    }

}
