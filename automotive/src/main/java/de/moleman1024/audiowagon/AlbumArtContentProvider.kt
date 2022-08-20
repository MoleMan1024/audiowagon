/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon

import android.content.*
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.*
import android.os.storage.StorageManager
import androidx.core.content.res.ResourcesCompat
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.ART_URI_PART
import de.moleman1024.audiowagon.medialibrary.ART_URI_PART_ALBUM
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.io.ByteArrayOutputStream
import java.lang.Integer.min
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock


private const val TAG = "AlbumArtContentProv"
private val logger = Logger
const val AUTHORITY = "de.moleman1024.audiowagon"
const val DEFAULT_JPEG_QUALITY_PERCENTAGE = 60

/**
 * See https://developer.android.com/guide/topics/providers/content-provider-creating
 */
@ExperimentalCoroutinesApi
class AlbumArtContentProvider : ContentProvider() {
    private var defaultAlbumArtAlbums: ByteArray = ByteArray(1)
    private var defaultAlbumArtTracks: ByteArray = ByteArray(1)
    private var audioBrowserService: AudioBrowserService? = null
    private var binderStatus: BinderStatus = BinderStatus.UNKNOWN
    private val uuid = Util.generateUUID()
    private val audioBrowserLifecycleObserver: ((event: LifecycleEvent) -> Unit) = {
        when (it) {
            LifecycleEvent.DESTROY, LifecycleEvent.SUSPEND, LifecycleEvent.IDLE -> {
                audioBrowserService?.removeLifecycleObserver(uuid)
                unbindAudioBrowserService()
            }
        }
    }
    // We use a local binder connection to access the album art via AudioBrowserService. Because it is local in the
    // same process the binder RPC size restrictions do not apply
    private val connection = object : ServiceConnection {
        @Synchronized
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            logger.debug(TAG, "onServiceConnected(name=$name, service=$service)")
            if (binderStatus == BinderStatus.BOUND) {
                logger.warning(TAG, "Already bound to AudioBrowserService")
                return
            }
            val binder = service as AudioBrowserService.LocalBinder
            audioBrowserService = binder.getService()
            binderStatus = BinderStatus.BOUND
            logger.debug(TAG, "Add lifecycle observer: $uuid")
            audioBrowserService?.addLifecycleObserver(uuid, audioBrowserLifecycleObserver)
        }

        // This is called only when the AudioBrowserService process exits, it is not called when unbinding
        @Synchronized
        override fun onServiceDisconnected(name: ComponentName?) {
            logger.debug(TAG, "onServiceDisconnected(name=$name)")
            audioBrowserService = null
            binderStatus = BinderStatus.UNBOUND
        }
    }

    companion object {
        private var albumArtPixels: Int = 256

        @Synchronized
        fun getAlbumArtSizePixels(): Int {
            return albumArtPixels
        }

        @Synchronized
        fun setAlbumArtSizePixels(size: Int) {
            albumArtPixels = size
        }
    }

    override fun onCreate(): Boolean {
        logger.debug(TAG, "onCreate()")
        binderStatus = BinderStatus.UNBOUND
        return true
    }

    override fun shutdown() {
        logger.debug(TAG, "shutdown()")
        audioBrowserService?.removeLifecycleObserver(uuid)
        unbindAudioBrowserService()
        super.shutdown()
    }

    @Synchronized
    private fun unbindAudioBrowserService() {
        if (audioBrowserService != null) {
            logger.debug(TAG, "unbindAudioBrowserService() in $context")
            context?.unbindService(connection)
            audioBrowserService = null
            binderStatus = BinderStatus.UNBOUND
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (context == null) {
            return null
        }
        init()
        var albumArtByteArray = defaultAlbumArtTracks
        if (uri.toString().contains("$ART_URI_PART/$ART_URI_PART_ALBUM")) {
            albumArtByteArray = defaultAlbumArtAlbums
        }
        if (audioBrowserService != null) {
            val resolvedAlbumArt = audioBrowserService?.getAlbumArtForURI(uri)
            if (resolvedAlbumArt != null) {
                albumArtByteArray = resolvedAlbumArt
            }
        }
        val albumArtBuf = ByteBuffer.wrap(albumArtByteArray)
        val storageManager = context?.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val handler = Handler(Looper.getMainLooper())
        return storageManager.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            object : ProxyFileDescriptorCallback() {

                override fun onGetSize(): Long {
                    return albumArtBuf.array().size.toLong()
                }

                override fun onRead(offset: Long, size: Int, data: ByteArray?): Int {
                    if (albumArtBuf.limit() <= 0) {
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
                    if (albumArtBuf.limit() <= 0) {
                        return 0
                    }
                    return albumArtBuf.hashCode()
                }

                override fun onRelease() {
                    albumArtBuf.rewind()
                }
            },
            handler
        )
    }

    @Synchronized
    private fun init() {
        if (binderStatus == BinderStatus.UNBOUND) {
            val intent =
                Intent(AudioBrowserService::class.java.name, Uri.EMPTY, context, AudioBrowserService::class.java)
            logger.verbose(TAG, "bindService(intent=$intent, connection=$connection)")
            context?.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            binderStatus = BinderStatus.BIND_REQUESTED
        }
        if (defaultAlbumArtAlbums.size <= 1) {
            val drawable =
                context?.resources?.let { ResourcesCompat.getDrawable(it, R.drawable.baseline_album_24, null) }
            if (drawable != null) {
                defaultAlbumArtAlbums = createDefaultAlbumArt(drawable)
            }
        }
        if (defaultAlbumArtTracks.size <= 1) {
            val drawable =
                context?.resources?.let { ResourcesCompat.getDrawable(it, R.drawable.music_note_black_48dp, null) }
            if (drawable != null) {
                defaultAlbumArtTracks = createDefaultAlbumArt(drawable)
            }
        }
    }

    /**
     * We create a default album art bitmap instead of using the vector drawable icon directly, the latter does not
     * seem to update the album art on Polestar 2 main view
     */
    private fun createDefaultAlbumArt(drawable: Drawable): ByteArray {
        val albumArtNumPixels = getAlbumArtSizePixels()
        logger.debug(TAG, "Creating default album art with size: $albumArtNumPixels")
        val bitmap = Bitmap.createBitmap(albumArtNumPixels, albumArtNumPixels, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        val stream = ByteArrayOutputStream()
        val quality = DEFAULT_JPEG_QUALITY_PERCENTAGE
        // TODO: I am getting some warnings in log on Pixel 3 XL AAOS that this takes some time
        bitmap?.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    // some default implementations that are required for content providers but that do nothing
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
