/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage

import android.media.MediaDataSource
import android.net.Uri
import de.moleman1024.audiowagon.Util
import de.moleman1024.audiowagon.log.Logger
import java.io.File
import java.util.*

/**
 * An interface to allow access to files and media data as [MediaDataSource].
 * Each [AudioFileStorageLocation] contains one [MediaDevice].
 */
interface MediaDevice {
   @Suppress("PropertyName")
   val TAG: String
   val logger: Logger

   suspend fun getDataSourceForURI(uri: Uri): MediaDataSource
   suspend fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource
   fun getID(): String
   fun getName(): String
   fun getFileFromURI(uri: Uri): Any

   /**
    * Traverses files/directories. Default implementation uses java.io.File API
    */
   fun walkTopDown(rootDirectory: Any): Sequence<Any> = sequence {
      val queue = LinkedList<File>()
      val allFilesDirs = mutableMapOf<String, Unit>()
      allFilesDirs[(rootDirectory as File).absolutePath] = Unit
      queue.add(rootDirectory)
      while (queue.isNotEmpty()) {
         val fileOrDirectory = queue.removeFirst()
         if (!fileOrDirectory.isDirectory) {
            if (fileOrDirectory.name.contains(Util.FILES_TO_IGNORE_REGEX)) {
               logger.debug(TAG, "Ignoring file: ${fileOrDirectory.name}")
            } else {
               logger.verbose(TAG, "Found file: ${fileOrDirectory.absolutePath}")
               yield(fileOrDirectory)
            }
         } else {
            if (fileOrDirectory.name.contains(Util.DIRECTORIES_TO_IGNORE_REGEX)) {
               logger.debug(TAG, "Ignoring directory: ${fileOrDirectory.name}")
            } else {
               logger.verbose(TAG, "Walking directory: ${fileOrDirectory.absolutePath}")
               yield(fileOrDirectory)
               for (subFileOrDir in fileOrDirectory.listFiles()?.sortedBy { it.name.lowercase() }!!) {
                  if (!allFilesDirs.containsKey(subFileOrDir.absolutePath)) {
                     allFilesDirs[subFileOrDir.absolutePath] = Unit
                     if (subFileOrDir.isDirectory) {
                        logger.verbose(TAG, "Found directory: ${subFileOrDir.absolutePath}")
                     }
                     queue.add(subFileOrDir)
                  }
               }
            }
         }
      }
   }

}
