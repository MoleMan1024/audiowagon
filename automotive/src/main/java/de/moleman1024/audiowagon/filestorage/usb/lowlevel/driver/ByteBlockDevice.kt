package de.moleman1024.audiowagon.filestorage.usb.lowlevel.driver

import de.moleman1024.audiowagon.filestorage.usb.lowlevel.Util
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Simple class which wraps around an existing [BlockDeviceDriver] to enable byte addressing
 * of content. Uses byte offsets instead of device oddsets in [ByteBlockDevice.write]
 * and [ByteBlockDevice.read]. Uses [BlockDeviceDriver.blockSize]
 * to calculate device offsets.
 */
open class ByteBlockDevice @JvmOverloads constructor(
    private val targetBlockDevice: BlockDeviceDriver,
    private val logicalOffsetToAdd: Int = 0
) :
    BlockDeviceDriver {
    override val blockSize: Int
        get() = targetBlockDevice.blockSize

    override val blocks: Long
        get() = targetBlockDevice.blocks

    @Throws(IOException::class)
    override fun init() {
        targetBlockDevice.init()
    }

    @Throws(IOException::class)
    override fun read(deviceOffset: Long, buffer: ByteBuffer) {
        var devOffset = deviceOffset / blockSize + logicalOffsetToAdd
        // TODO try to make this more efficient by for example making tmp buffer global
        if (deviceOffset % blockSize != 0L) {
            val tmp = Util.allocateByteBuffer(blockSize)
            targetBlockDevice.read(devOffset, tmp)
            tmp.clear()
            tmp.position((deviceOffset % blockSize).toInt())
            val limit = buffer.remaining().coerceAtMost(tmp.remaining())
            tmp.limit(tmp.position() + limit)
            buffer.put(tmp)
            devOffset++
        }

        if (buffer.remaining() > 0) {
            val outBuffer: ByteBuffer
            if (buffer.remaining() % blockSize != 0) {
                val rounded = blockSize - buffer.remaining() % blockSize + buffer.remaining()
                outBuffer = Util.allocateByteBuffer(rounded)
                outBuffer.limit(rounded)
            } else {
                outBuffer = buffer
            }
            targetBlockDevice.read(devOffset, outBuffer)
            if (buffer.remaining() % blockSize != 0) {
                System.arraycopy(outBuffer.array(), 0, buffer.array(), buffer.position(), buffer.remaining())
            }
            buffer.position(buffer.limit())
        }
    }

    @Throws(IOException::class)
    override fun write(deviceOffset: Long, buffer: ByteBuffer) {
        var devOffset = deviceOffset / blockSize + logicalOffsetToAdd
        // TODO try to make this more efficient by for example making tmp buffer global
        if (deviceOffset % blockSize != 0L) {
            val tmp = Util.allocateByteBuffer(blockSize)
            targetBlockDevice.read(devOffset, tmp)
            tmp.clear()
            tmp.position((deviceOffset % blockSize).toInt())
            val remaining = min(tmp.remaining(), buffer.remaining())
            tmp.put(buffer.array(), buffer.position(), remaining)
            buffer.position(buffer.position() + remaining)
            tmp.clear()
            targetBlockDevice.write(devOffset, tmp)
            devOffset++
        }
        if (buffer.remaining() > 0) {
            // TODO try to make this more efficient by for example only allocating blockSize and making it global
            val outBuffer: ByteBuffer
            if (buffer.remaining() % blockSize != 0) {
                val rounded = blockSize - buffer.remaining() % blockSize + buffer.remaining()
                outBuffer = Util.allocateByteBuffer(rounded)
                outBuffer.limit(rounded)
                // TODO: instead of just writing 0s at the end of the buffer do we need to read what is currently on the
                //  disk and save that then?
                System.arraycopy(buffer.array(), buffer.position(), outBuffer.array(), 0, buffer.remaining())
                buffer.position(buffer.limit())
            } else {
                outBuffer = buffer
            }
            targetBlockDevice.write(devOffset, outBuffer)
        }
    }
}
