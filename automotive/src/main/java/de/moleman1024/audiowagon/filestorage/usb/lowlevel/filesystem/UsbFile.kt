/*
 * (C) Copyright 2014-2016 mjahnen <github@mgns.tech>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package de.moleman1024.audiowagon.filestorage.usb.lowlevel.filesystem

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer

/**
 * This class represents either an file or an directory. This can be determined
 * by [.isDirectory]. Not all methods make sense for both cases. For
 * example if representing a file methods like [.createDirectory]
 * or [.createFile] do not make sense and will throw an exception.
 * On the other hand methods like [.read] or
 * [.write] do not make sense for directories and will
 * throw an exception!
 *
 * @author mjahnen
 */
interface UsbFile : Closeable {

    /**
     *
     * @return True if representing a directory.
     */
    val isDirectory: Boolean

    /**
     * Actual file or directory name or '/' for root directory.
     * @return The name of the file or directory.
     */
    /**
     * Set a new name for this file or directory.
     *
     * @param name
     * The new name.
     * @throws IOException
     * If new name is already assigned or writing to the file system
     * fails.
     */
    var name: String

    /**
     * Absolute path of a file or directory.
     * @return Absolute path separated with '/' and beginning with an '/'
     */
    val absolutePath: String

    /**
     * Returns the parent directory for the file or directory or null if this is
     * the root directory.
     *
     * @return The parent directory or null.
     */
    val parent: UsbFile?

    /**
     * Returns the file length or throws an exception if called on a directory.
     *
     * @return File length in bytes.
     */
    /**
     * Sets the new file length. This can sometimes be more efficient if all
     * needed place for a file is allocated on the disk at once and before
     * writing to it.
     *
     *
     * If the space is not allocated before writing the space must be exceeded
     * every time a new write occurs. This can sometimes be less efficient.
     *
     * @param newLength
     * The file length in bytes.
     * @throws IOException
     * If requesting the needed space fails.
     */
    var length: Long

    /**
     *
     * @return True if the current directory is the root directory, false if not or a file.
     */
    val isRoot: Boolean

    /**
     * Tries to search a corresponding entry associated with the path parameter. Path separator is '/'.
     * Parameter path must not start with an '/' (except if querying from root directory). Path is
     * treated relative to current UsbFile.
     * @param path The path to the resource to search.
     * @return UsbFile directory or file if found, null otherwise.
     */
    fun search(path: String): UsbFile?

    /**
     * Returns the time this directory or file was last modified.
     *
     * @return Time in milliseconds since January 1 00:00:00, 1970 UTC
     */
    fun lastModified(): Long

    /**
     * Lists all files in the directory. Throws an exception if called on a
     * file.
     *
     * @return String array containing all names in the directory.
     * @throws IOException
     * If reading fails
     */
    fun list(): Array<String>

    /**
     * Lists all files in the directory. Throws an exception if called on a
     * file.
     *
     * @return UsbFile array containing all files or directories in the
     * directory.
     * @throws IOException
     * If reading fails
     */
    fun listFiles(): Array<UsbFile>

    /**
     * Reads from a file or throws an exception if called on a directory.
     *
     * @param offset
     * The offset in bytes where reading in the file should be begin.
     * @param destination
     * Buffer the data shall be transferred to.
     * @throws IOException
     * If reading fails.
     */
    fun read(offset: Long, destination: ByteBuffer)

    /**
     * Writes to a file or throws an exception if called on a directory.
     *
     * @param offset
     * The offset in bytes where writing in the file should be begin.
     * @param source
     * Buffer which contains the data which shall be transferred.
     * @throws IOException
     * If writing fails.
     */
    fun write(offset: Long, source: ByteBuffer)

    /**
     * Forces a write. Every change to the file is then committed to the disk.
     * Throws an exception if called on directories.
     *
     * @throws IOException
     * If flushing fails.
     */
    fun flush()

    /**
     * Closes and flushes the file. It is essential to close a file after making
     * changes to it! Throws an exception if called on directories.
     *
     * @throws IOException
     * If closing fails.
     */
    override fun close()

    /**
     * This methods creates a new directory with the given name and returns it.
     *
     * @param name
     * The name of the new directory.
     * @return The newly created directory.
     * @throws IOException
     * If writing to the disk fails or a item with the same name
     * already exists.
     */
    fun createDirectory(name: String): UsbFile

    /**
     * This methods creates a new file with the given name and returns it.
     *
     * @param name
     * The name of the new file.
     * @return The newly created file.
     * @throws IOException
     * If writing to the disk fails or a item with the same name
     * already exists.
     */
    fun createFile(name: String): UsbFile

    companion object {
        const val separator = "/"
    }
}
