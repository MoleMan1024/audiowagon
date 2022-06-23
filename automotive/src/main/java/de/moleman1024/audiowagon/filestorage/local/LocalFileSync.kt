/*
SPDX-FileCopyrightText: 2021-2022 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.local

import android.app.DownloadManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.SharedPrefs
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.log.CrashReporting
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import java.net.*
import java.util.concurrent.ConcurrentHashMap


const val SYNC_FILES_URL_DEFAULT = "http://192.168.1.42:8080"
const val DOWNLOAD_DIRECTORY = "aw"
private const val HTTP_TIMEOUT_SEC = 6
private val LOCAL_IP_REGEX =
    Regex("^http://((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?).*$")
private const val TAG = "LocalFileSync"
private val logger = Logger

/**
 * A class to sync audio files hosted on a HTTP server at a given location into local file storage
 *
 * Uses
 * https://developer.android.com/reference/android/app/DownloadManager
 */
class LocalFileSync(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val crashReporting: CrashReporting,
    private val context: Context
) {
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager?
    private val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
    private var isRunDownloadMonitorFlow = false
    private val idsToDownload = ConcurrentHashMap<Long, Unit>()
    private val idsDownloaded = ConcurrentHashMap<Long, Unit>()
    private var numFilesToCopy = -1
    var rootURL: String = ""

    init {
        SharedPrefs.setSyncNumFilesCopied(context, 0)
        SharedPrefs.setSyncNumFilesTotal(context, 0)
        SharedPrefs.setSyncStatus(context, context.getString(R.string.sync_status_idle))
        updateFreeSpace()
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    fun start() {
        launchInScopeSafely {
            cleanupDownloads()
            updateFreeSpace()
            assertValidURL()
            logger.debug(TAG, "rootURL=(${rootURL})")
            SharedPrefs.setSyncStatus(context, context.getString(R.string.sync_status_syncing))
            try {
                val allAudioFiles = collectAudioFileURLs(rootURL)
                numFilesToCopy = allAudioFiles.size
                logger.debug(TAG, "numFilesToCopy=$numFilesToCopy")
                SharedPrefs.setSyncNumFilesTotal(context, numFilesToCopy)
                isRunDownloadMonitorFlow = true
                val downloadMonitorFlow = createDownloadMonitorFlow()
                launchInScopeSafely {
                    collectDownloadMonitorFlow(downloadMonitorFlow)
                }
                allAudioFiles.forEach {
                    queueAudioFileForDownload(it)
                }
            } catch (exc: SocketTimeoutException) {
                SharedPrefs.setSyncStatus(context, context.getString(R.string.sync_status_error_connection))
            }
        }
    }

    private suspend fun createDownloadMonitorFlow(): Flow<Unit> = flow {
        logger.debug(TAG, "Monitoring download")
        while (isRunDownloadMonitorFlow) {
            downloadManager?.query(DownloadManager.Query().apply {
                setFilterByStatus(DownloadManager.STATUS_FAILED or DownloadManager.STATUS_SUCCESSFUL)
                setFilterById(*idsToDownload.keys.toLongArray())
            })?.use { cursor ->
                logger.debug(TAG, "query returned num results: ${cursor.count}")
                while (cursor.moveToNext()) {
                    val idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                    idsDownloaded[cursor.getLong(idIndex)] = Unit
                }
            }
            emit(Unit)
            delay(1000)
        }
        logger.debug(TAG, "Stopped download monitor")
    }

    private suspend fun collectDownloadMonitorFlow(flow: Flow<Unit>) {
        logger.debug(TAG, "collectDownloadMonitorFlow()")
        flow.collect {
            SharedPrefs.setSyncNumFilesCopied(context, idsDownloaded.size)
            var status = context.getString(R.string.sync_status_syncing)
            if (idsDownloaded.size >= numFilesToCopy) {
                logger.debug(TAG, "No more pending downloads")
                status = context.getString(R.string.sync_status_done)
                stopMonitorDownload()
            }
            SharedPrefs.setSyncStatus(context, status)
            updateFreeSpace()
        }
        logger.debug(TAG, "Download flow collection has finished")
    }

    private fun stopMonitorDownload() {
        logger.debug(TAG, "Stopping download monitor")
        isRunDownloadMonitorFlow = false
    }

    private fun assertValidURL() {
        logger.debug(TAG, "assertValidURL()")
        if (!LOCAL_IP_REGEX.matches(rootURL)) {
            throw RuntimeException("Not a local IP address")
        }
    }

    @Suppress("RedundantSuspendModifier", "BlockingMethodInNonBlockingContext")
    private suspend fun collectAudioFileURLs(rootURLStr: String): List<URI> {
        logger.info(TAG, "traverseAndDownload(${rootURLStr})")
        val stack = ArrayDeque<String>()
        val allURLs = mutableMapOf<URI, Unit>()
        val audioFiles = mutableListOf<URI>()
        stack.add("/")
        while (stack.isNotEmpty()) {
            val path = stack.removeLast()
            val uri = URI(rootURLStr).resolve(path)
            if (!allURLs.containsKey(uri)) {
                allURLs[uri] = Unit
                if (uri.path.endsWith("/")) {
                    val html = getHTML(uri)
                    val links = parseLinksInHTMLPage(html)
                    links.forEach {
                        stack.add(path + it)
                    }
                } else {
                    logger.verbose(TAG, "Found audio file: $uri")
                    audioFiles += uri
                }
            }
        }
        logger.info(TAG, "Traversal completed")
        return audioFiles
    }

    private fun getHTML(uri: URI): String {
        logger.verbose(TAG, "getHTML(${uri}")
        var tries = 0
        val lines = mutableListOf<String>()
        while (tries < 3) {
            val urlConnection: HttpURLConnection = uri.toURL().openConnection() as HttpURLConnection
            urlConnection.connectTimeout = HTTP_TIMEOUT_SEC * 1000
            urlConnection.connect()
            logger.verbose(
                TAG,
                "Response headers: ${urlConnection.headerFields.map { "${it.key}=${it.value}" }.joinToString(", ")}"
            )
            try {
                urlConnection.inputStream.bufferedReader(Charsets.UTF_8).use {
                    lines += it.readText()
                }
                break
            } catch (exc: ProtocolException) {
                logger.exception(TAG, exc.message.toString(), exc)
                lines.clear()
            } finally {
                urlConnection.disconnect()
            }
            tries += 1
        }
        return lines.joinToString("\n")
    }

    private fun queueAudioFileForDownload(uri: URI) {
        logger.verbose(TAG, "queueAudioFileForDownload(uri=${uri}")
        val androidURI = Uri.parse(uri.toString())
        val path = sanitizePathForDownloadMgr(uri.path)
        val guessedMimeType = Util.guessContentType(path)
        val request = DownloadManager.Request(androidURI).apply {
            setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "${DOWNLOAD_DIRECTORY}${path}")
            setMimeType(guessedMimeType)
        }
        val downloadID = downloadManager?.enqueue(request)
        if (downloadID != null) {
            idsToDownload[downloadID] = Unit
        }
    }

    private fun sanitizePathForDownloadMgr(path: String): String {
        return path.replace(Regex("[:*?\"<>|%#()]"), "-").replace("...", "…").replace("..", "._.")
    }

    private fun cleanupDownloads() {
        logger.debug(TAG, "cleanupDownloads()")
        val idsToClean = mutableListOf<Long>()
        queryAllDownloads()?.use { cursor ->
            logger.debug(TAG, "Query returned num results to be cleaned: ${cursor.count}")
            while (cursor.moveToNext()) {
                val idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                idsToClean += cursor.getLong(idIndex)
            }
        }
        runBlocking(dispatcher) {
            // TODO: ANR
            // downloadManager has a limit for IDs
            idsToClean.chunked(100).forEach { chunk ->
                downloadManager?.remove(*chunk.toLongArray())
            }
            while (true) {
                val query = queryAllDownloads()
                if (query != null && query.count <= 0) {
                    break
                }
                logger.debug(TAG, "Waiting for downloads to be cleaned up")
                delay(200)
            }
        }
        logger.debug(TAG, "Download cleanup done")
    }

    private fun queryAllDownloads(): Cursor? {
        return downloadManager?.query(DownloadManager.Query().apply {
            setFilterByStatus(
                DownloadManager.STATUS_FAILED
                        or DownloadManager.STATUS_SUCCESSFUL
                        or DownloadManager.STATUS_PENDING
                        or DownloadManager.STATUS_RUNNING
                        or DownloadManager.STATUS_PAUSED
            )
        })
    }

    private fun updateFreeSpace() {
        val extDirs = context.getExternalFilesDirs(null)
        val gigaBytesInBytes = 1000L * 1000L * 1000L
        extDirs[0].let { file ->
            val storageVolume: StorageVolume? = storageManager.getStorageVolume(file)
            if (storageVolume == null) {
                logger.debug(TAG, "Could not determinate StorageVolume for ${file.path}")
            } else {
                val totalSpaceBytes: Long
                val usedSpaceBytes: Long
                if (storageVolume.isPrimary) {
                    val uuid = StorageManager.UUID_DEFAULT
                    val storageStatsManager = context.getSystemService(Context.STORAGE_STATS_SERVICE) as
                            StorageStatsManager
                    totalSpaceBytes = storageStatsManager.getTotalBytes(uuid)
                    usedSpaceBytes = totalSpaceBytes - storageStatsManager.getFreeBytes(uuid)
                } else {
                    totalSpaceBytes = file.totalSpace
                    usedSpaceBytes = totalSpaceBytes - file.freeSpace
                }
                SharedPrefs.setSyncFreeSpace(
                    context,
                    "%.2f GB / %.2f GB".format(
                        usedSpaceBytes.toDouble() / gigaBytesInBytes,
                        totalSpaceBytes.toDouble() / gigaBytesInBytes
                    )
                )
            }
        }
    }

    private fun parseLinksInHTMLPage(html: String): List<String> {
        val links = mutableListOf<String>()
        html.lines().forEach { line ->
            "a href=\"(.*?)\"".toRegex().find(line)?.groupValues?.get(1)?.let { links.add(it) }
        }
        return links
    }

    private fun launchInScopeSafely(func: suspend (CoroutineScope) -> Unit) {
        Util.launchInScopeSafely(scope, dispatcher, logger, TAG, crashReporting, func)
    }

}
