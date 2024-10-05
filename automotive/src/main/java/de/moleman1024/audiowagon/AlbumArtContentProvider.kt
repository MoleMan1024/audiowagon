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
import android.os.ParcelFileDescriptor.AutoCloseOutputStream
import androidx.core.content.res.ResourcesCompat
import de.moleman1024.audiowagon.enums.BinderStatus
import de.moleman1024.audiowagon.enums.LifecycleEvent
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.ART_URI_PART
import de.moleman1024.audiowagon.medialibrary.ART_URI_PART_ALBUM
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.Integer.min
import java.time.Instant
import kotlin.concurrent.thread

private const val TAG = "AlbumArtContentProv"
private val logger = Logger

// a linux pipe can fit max 64k
private const val PIPE_CHUNK_SIZE_BYTES = 16384

// When AudioBrowserService binder connection is unbound, this is usually because we are shutting down or going to
// sleep. To avoid that the media GUI re-binds immediately to fetch more album art, we block re-binding for some seconds
private const val SECONDS_TO_BLOCK_AFTER_UNBIND: Long = 4

/**
 * A content provider so that media browser GUI can retrieve album art.
 * It connects locally via Binder to [AudioBrowserService] to be able to retrieve album art from USB filesystem.
 *
 * See https://developer.android.com/guide/topics/providers/content-provider-creating
 */
@ExperimentalCoroutinesApi
class AlbumArtContentProvider : ContentProvider() {
    private var defaultAlbumArtAlbums: ByteArray = ByteArray(1)
    private var defaultAlbumArtTracks: ByteArray = ByteArray(1)
    private var audioBrowserService: AudioBrowserService? = null
    private var binderStatus: BinderStatus = BinderStatus.UNKNOWN
    private val uuid = Util.generateUUID()
    private var lastUnbindTimestamp: Instant? = null
    private val audioBrowserLifecycleObserver: ((event: LifecycleEvent) -> Unit) = {
        when (it) {
            LifecycleEvent.DESTROY, LifecycleEvent.SUSPEND, LifecycleEvent.IDLE -> {
                logger.debug(TAG, "AudioBrowserService lifecycle changed to: $it")
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
            lastUnbindTimestamp = Util.getLocalDateTimeNowInstant()
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (context == null) {
            return null
        }
        logger.verbose(TAG, "openFile(uri=$uri, mode=$mode)")
        init()
        val parcelFileDescriptor = createParcelFileDescriptor(uri)
        logger.verbose(TAG, "Got parcel file descriptor for: $uri")
        return parcelFileDescriptor
    }

    private fun createParcelFileDescriptor(uri: Uri): ParcelFileDescriptor? = runBlocking(Dispatchers.IO) {
        val future: Deferred<ParcelFileDescriptor?> = async {
            var albumArtByteArray = defaultAlbumArtTracks
            if (uri.toString().contains("$ART_URI_PART/$ART_URI_PART_ALBUM")) {
                albumArtByteArray = defaultAlbumArtAlbums
            }
            if (audioBrowserService != null) {
                try {
                    val resolvedAlbumArt = audioBrowserService?.getAlbumArtForURI(uri)
                    if (resolvedAlbumArt != null) {
                        albumArtByteArray = resolvedAlbumArt
                    }
                } catch (exc: RuntimeException) {
                    // can happen when data source is cleared during metadata retrieval
                    logger.exception(TAG, exc.message.toString(), exc)
                }
            }
            val inOutPipe = ParcelFileDescriptor.createPipe()
            val outStream = AutoCloseOutputStream(inOutPipe[1])
            thread {
                val chunkSize = PIPE_CHUNK_SIZE_BYTES
                var remainingBytes = albumArtByteArray.size
                var offset = 0
                try {
                    while (remainingBytes > 0) {
                        val numBytesToWrite = min(chunkSize, remainingBytes)
                        outStream.write(albumArtByteArray, offset, numBytesToWrite)
                        offset += numBytesToWrite
                        remainingBytes -= numBytesToWrite
                    }
                } catch (exc: IOException) {
                    if ("Broken pipe" in exc.message.toString()) {
                        // This seems to happen when the GUI client discards the image before it is fully written?
                        // Seems to be harmless
                    } else {
                        logger.exception(TAG, exc.message.toString(), exc)
                    }
                } finally {
                    outStream.flush()
                    outStream.close()
                }
            }
            return@async inOutPipe[0]
        }
        return@runBlocking future.await()
    }

    @Synchronized
    private fun init() {
        logger.verbose(TAG, "init(binderStatus=$binderStatus)")
        if (binderStatus == BinderStatus.UNBOUND) {
            val lastUnbindTime = lastUnbindTimestamp
            if (lastUnbindTime == null) {
                bindAudioBrowserService()
            } else {
                val now = Util.getLocalDateTimeNowInstant()
                if (Util.getDifferenceInSecondsForInstants(lastUnbindTime, now) > SECONDS_TO_BLOCK_AFTER_UNBIND) {
                    bindAudioBrowserService()
                } else {
                    logger.verbose(
                        TAG, "Binder connection to AudioBrowserService was recently unbound, will not re-bind for " +
                                "some seconds"
                    )
                }
            }
        }
        if (defaultAlbumArtAlbums.size <= 1) {
            val drawable =
                context?.resources?.let { ResourcesCompat.getDrawable(it, R.drawable.album, null) }
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

    private fun bindAudioBrowserService() {
        val intent =
            Intent(AudioBrowserService::class.java.name, Uri.EMPTY, context, AudioBrowserService::class.java)
        logger.debug(TAG, "bindService(intent=$intent, connection=$connection)")
        context?.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        binderStatus = BinderStatus.BIND_REQUESTED
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
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
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
