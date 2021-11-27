/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.util

import android.support.v4.media.MediaBrowserCompat
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class MediaBrowserTraversal(private val browser: MediaBrowserCompat) {
    val hierarchy = mutableMapOf<String, MutableList<String>>()
    val nodes = Stack<String>()
    val future = CompletableFuture<Unit>()
    private val callback = object : MediaBrowserCompat.SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowserCompat.MediaItem>) {
            children.forEach {
                if (parentId in hierarchy) {
                    hierarchy[parentId]?.add(it.mediaId!!)
                } else {
                    hierarchy[parentId] = mutableListOf(it.mediaId!!)
                }
                if (it.isBrowsable) {
                    nodes.push(it.mediaId)
                }
            }
            if (!nodes.empty()) {
                val node = nodes.pop()
                traverse(node)
            } else {
                future.complete(Unit)
            }
        }
    }

    private fun traverse(key: String) {
        browser.subscribe(key, callback)
    }

    fun start(root: String) {
        browser.subscribe(root, callback)
        future.get(10, TimeUnit.SECONDS)
    }

}
