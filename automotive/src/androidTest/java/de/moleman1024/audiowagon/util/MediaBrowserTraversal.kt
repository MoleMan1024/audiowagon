/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.util

import android.support.v4.media.MediaBrowserCompat
import de.moleman1024.audiowagon.log.Logger
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val TAG = "MediaBrowserTraversal"

class MediaBrowserTraversal(private val browser: MediaBrowserCompat) {
    val hierarchy = mutableMapOf<String, MutableList<MediaBrowserCompat.MediaItem>>()
    val nodes = Stack<String>()
    val future = CompletableFuture<Unit>()
    private val callback = object : MediaBrowserCompat.SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowserCompat.MediaItem>) {
            Logger.debug(TAG, "Received onChildrenLoaded() for num items: ${children.size}")
            children.forEach {
                if (parentId in hierarchy) {
                    hierarchy[parentId]?.add(it)
                } else {
                    hierarchy[parentId] = mutableListOf(it)
                }
                if (it.isBrowsable) {
                    nodes.push(it.mediaId)
                }
            }
            Logger.debug(TAG, "nodes=$nodes")
            if (!nodes.empty()) {
                val node = nodes.pop()
                traverse(node)
            } else {
                future.complete(Unit)
            }
        }
    }

    private fun traverse(key: String) {
        Logger.debug(TAG, "traverse(key=$key)")
        browser.subscribe(key, callback)
    }

    fun start(root: String) {
        Logger.debug(TAG, "Starting media traversal from: $root")
        browser.subscribe(root, callback)
        try {
            future.get(20, TimeUnit.SECONDS)
        } catch (exc: TimeoutException) {
            Logger.error(TAG, "MediaBrowser traversal did not finish in time")
            throw exc
        }
    }

}
