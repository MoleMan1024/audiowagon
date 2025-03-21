/*
SPDX-FileCopyrightText: 2021-2022 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import de.moleman1024.audiowagon.enums.SingletonCoroutineBehaviour
import de.moleman1024.audiowagon.exceptions.NoAudioItemException
import de.moleman1024.audiowagon.log.CrashReporting
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.*

private val logger = Logger

/**
 * Allows to launch a coroutine multiple times while ensuring that previous invocations of the coroutine are either
 * - first cancelled and joined before starting this "singleton coroutine" again
 * - or new invocations are ignored until the already running coroutine has finished
 */
// Some general topics on coroutines:
// https://kotlinlang.org/docs/exception-handling.html#coroutineexceptionhandler
// https://www.lukaslechner.com/why-exception-handling-with-kotlin-coroutines-is-so-hard-and-how-to-successfully-master-it/
// https://medium.com/android-news/coroutine-in-android-working-with-lifecycle-fc9c1a31e5f3
// https://elizarov.medium.com/coroutine-context-and-scope-c8b255d59055
class SingletonCoroutine(
    name: String,
    private val dispatcher: CoroutineDispatcher,
    private val coroutineContext: CoroutineContext? = null,
    private val crashReporting: CrashReporting? = null
) {
    private val tag = "SglCoRt|${name}"
    private val instancesMap = ConcurrentHashMap<String, Job>()
    private var tagWithID = ""
    var exceptionHandler: CoroutineExceptionHandler = createExceptionHandler()
    var behaviour: SingletonCoroutineBehaviour = SingletonCoroutineBehaviour.CANCEL_OTHER_ON_LAUNCH

    fun launch(func: suspend (CoroutineScope) -> Unit) {
        val currentID = UUID.randomUUID().toString()
        tagWithID = "${tag}-${truncateUUID(currentID)}"
        if (behaviour == SingletonCoroutineBehaviour.CANCEL_OTHER_ON_LAUNCH) {
            cancel()
        } else if (behaviour == SingletonCoroutineBehaviour.PREFER_FINISH) {
            if (instancesMap.isNotEmpty()) {
                logger.debug(tagWithID, "Ignoring new coroutine, prefer to finish running coroutine")
                return
            }
        }
        var coRtContext = exceptionHandler + dispatcher + CoroutineName(truncateUUID(currentID))
        coroutineContext?.let { coRtContext = it + coRtContext }
        logger.debug(tagWithID, "Launching ${truncateUUID(currentID)}")
        val job =
            CoroutineScope(coRtContext).launch(start = CoroutineStart.LAZY) {
            try {
                logger.debug(tagWithID, "Launched ${truncateUUID(currentID)}")
                if (behaviour == SingletonCoroutineBehaviour.CANCEL_OTHER_ON_LAUNCH) {
                    // wait for all previous instances to finish cancellation
                    instancesMap.keys.filter { it != currentID }.forEach {
                        logger.debug(tagWithID, "Waiting for cancellation of $it (instancesMap=$instancesMap)")
                        instancesMap[it]?.join()
                        instancesMap.remove(it)
                        logger.debug(tagWithID, "Cancelled $it (instancesMap=$instancesMap)")
                    }
                }
                func(this)
            } catch (exc: NoAudioItemException) {
                logger.exception(tagWithID, exc.message.toString(), exc)
            } catch (exc: CancellationException) {
                logger.warning(tagWithID, "CancellationException ${truncateUUID(currentID)} (exc=$exc)")
            } catch (exc: Exception) {
                crashReporting?.logLastMessagesAndRecordException(exc)
                logger.exception(tagWithID, exc.message.toString(), exc)
            } finally {
                instancesMap.remove(currentID)
                logger.debug(tagWithID, "Ended ${truncateUUID(currentID)} (instancesMap=$instancesMap)")
            }
        }
        instancesMap[currentID] = job
        job.start()
    }

    private fun createExceptionHandler(): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { coroutineContext, exc ->
            val msg = "$coroutineContext threw $exc"
            when (exc) {
                is NoAudioItemException -> {
                    // this happens often when persistent data does not match USB drive contents, do not report this
                    // exception in crashlytics
                    logger.exception(tagWithID, msg, exc)
                }
                is CancellationException -> {
                    // cancelling suspending jobs is not an error
                    logger.warning(tagWithID, "CancellationException (msg=$msg)")
                }
                else -> {
                    logger.exception(tagWithID, msg, exc)
                    crashReporting?.logMessage(msg)
                    crashReporting?.logLastMessagesAndRecordException(exc)
                }
            }
        }
    }

    fun cancel() {
        instancesMap.forEach { (id, job) ->
            logger.debug(tagWithID, "Cancelling ${truncateUUID(id)}")
            job.cancel()
        }
    }

    suspend fun join() {
        instancesMap.forEach { (id, job) ->
            logger.debug(tagWithID, "Joining $id")
            if (job.isActive) {
                job.join()
            }
        }
    }

    fun isInProgress(): Boolean {
        return instancesMap.isNotEmpty()
    }

    private fun truncateUUID(uuid: String): String {
        return uuid.take(7)
    }
}
