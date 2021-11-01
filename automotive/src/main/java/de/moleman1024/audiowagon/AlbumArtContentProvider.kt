/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import androidx.core.content.res.ResourcesCompat
import de.moleman1024.audiowagon.log.Logger
import java.io.ByteArrayOutputStream
import java.lang.Integer.min
import java.nio.ByteBuffer


private const val TAG = "AlbumArtContentProv"
private val logger = Logger
const val AUTHORITY = "de.moleman1024.audiowagon"
const val TRACK_ART_PATH = "trackArt"

/**
 * See https://developer.android.com/guide/topics/providers/content-provider-creating
 */
class AlbumArtContentProvider : ContentProvider() {
    private var defaultAlbumArtBitmap: Bitmap? = null

    companion object {
        private var albumArtBuf: ByteBuffer? = null
        private var defaultAlbumArtByteArray: ByteArray = ByteArray(1)

        @Synchronized
        fun getAlbumArtByteBuffer(): ByteBuffer? {
            return albumArtBuf
        }

        @Synchronized
        fun setAlbumArtByteArray(albumArtByteArray: ByteArray?) {
            if (albumArtByteArray == null) {
                // no album art given, use the default
                albumArtBuf = if (defaultAlbumArtByteArray.size > 1) {
                    ByteBuffer.wrap(defaultAlbumArtByteArray)
                } else {
                    null
                }
                return
            }
            albumArtBuf = ByteBuffer.wrap(albumArtByteArray)
        }

        @Synchronized
        fun setDefaultAlbumArtByteArray(imageByteArray: ByteArray) {
            defaultAlbumArtByteArray = imageByteArray
        }
    }

    override fun onCreate(): Boolean {
        logger.debug(TAG, "onCreate()")
        createDefaultAlbumArt()
        return true
    }

    /**
     * We create a default album art bitmap instead of using the vector drawable icon directly, the latter does not
     * seem to update the album art on Polestar 2 main view
     */
    private fun createDefaultAlbumArt() {
        val drawable =
            context?.resources?.let { ResourcesCompat.getDrawable(it, R.drawable.music_note_black_48dp, null) }
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable?.setBounds(0, 0, canvas.width, canvas.height)
        drawable?.draw(canvas)
        // TODO: similar code as in getArtForAudioItem()
        val stream = ByteArrayOutputStream()
        val quality = 90
        defaultAlbumArtBitmap = bitmap
        // TODO: I am getting some warnings in log on Pixel 3 XL AAOS that this takes some time
        defaultAlbumArtBitmap?.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        defaultAlbumArtByteArray = stream.toByteArray()
        setDefaultAlbumArtByteArray(defaultAlbumArtByteArray)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        logger.debug(TAG, "openFile(uri=$uri)")
        if (context == null) {
            return null
        }
        val storageManager = context?.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val handler = Handler(Looper.getMainLooper())
        return storageManager.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            object : ProxyFileDescriptorCallback() {

                override fun onGetSize(): Long {
                    val byteBuf = getAlbumArtByteBuffer() ?: return 0
                    return byteBuf.array().size.toLong()
                }

                override fun onRead(offset: Long, size: Int, data: ByteArray?): Int {
                    val albumArtBuf = getAlbumArtByteBuffer()
                    if (albumArtBuf == null || albumArtBuf.limit() <= 0) {
                        return 0
                    }
                    if (data == null) {
                        return 0
                    }
                    albumArtBuf.position(offset.toInt())
                    val numBytesToRead = min(size, (albumArtBuf.array().size - offset).toInt())
                    val outBuffer = ByteBuffer.wrap(data)
                    albumArtBuf.get(outBuffer.array(), 0, numBytesToRead)
                    return numBytesToRead
                }

                override fun hashCode(): Int {
                    val albumArtBuf = getAlbumArtByteBuffer()
                    if (albumArtBuf == null || albumArtBuf.limit() <= 0) {
                        return 0
                    }
                    return albumArtBuf.hashCode()
                }

                override fun onRelease() {
                    albumArtBuf?.rewind()
                }
            },
            handler
        )
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        return null
    }

    override fun getType(uri: Uri): String? {
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        return 0
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        return 0
    }
}
