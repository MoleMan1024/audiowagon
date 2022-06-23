/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.util

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class MediaBrowserSearch(private val browser: MediaBrowserCompat) {
    var items = mutableListOf<MediaBrowserCompat.MediaItem>()
    val future = CompletableFuture<Unit>()
    private val callback = object : MediaBrowserCompat.SearchCallback() {
        override fun onSearchResult(query: String, extras: Bundle?, items: MutableList<MediaBrowserCompat.MediaItem>) {
            this@MediaBrowserSearch.items = items
            future.complete(Unit)
        }
    }

    fun start(query: String, extras: Bundle? = null) {
        browser.search(query, extras, callback)
        future.get(10, TimeUnit.SECONDS)
    }

}
