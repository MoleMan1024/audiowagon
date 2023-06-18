package de.moleman1024.audiowagon.filestorage.usb.lowlevel.filesystem

import de.moleman1024.audiowagon.log.Logger

/**
 * Created by magnusja on 3/1/17.
 */

private val logger = Logger

abstract class AbstractUsbFile : UsbFile {

    override val absolutePath: String
        get() {
            if (isRoot) {
                return "/"
            }
            return parent?.let { parent ->
                if (parent.isRoot) {
                    "/$name"
                } else parent.absolutePath + UsbFile.separator + name
            }.orEmpty() // should never happen
        }

    override fun search(path: String): UsbFile? {
        var pathVal = path
        if (!isDirectory) {
            throw UnsupportedOperationException("This is a file!")
        }
        logger.debug(TAG, "search file: $pathVal")
        if (isRoot && pathVal == UsbFile.separator) {
            return this
        }
        if (isRoot && pathVal.startsWith(UsbFile.separator)) {
            pathVal = pathVal.substring(1)
        }
        if (pathVal.endsWith(UsbFile.separator)) {
            pathVal = pathVal.substring(0, pathVal.length - 1)
        }
        val index = pathVal.indexOf(UsbFile.separator)
        if (index < 0) {
            logger.debug(TAG, "search entry: $pathVal")
            return searchThis(pathVal)
        } else {
            val subPath = pathVal.substring(index + 1)
            val dirName = pathVal.substring(0, index)
            logger.debug(TAG, "search recursively $subPath in $dirName")
            val file = searchThis(dirName)
            if (file != null && file.isDirectory) {
                logger.debug(TAG, "found directory $dirName")
                return file.search(subPath)
            }
        }
        logger.debug(TAG, "not found $pathVal")
        return null
    }

    private fun searchThis(name: String): UsbFile? {
        for (file in listFiles()) {
            // allow case insensitive search (better support for files originating from Windows)
            if (file.name.lowercase() == name.lowercase())
                return file
        }
        return null
    }

    override fun hashCode(): Int {
        return absolutePath.hashCode()
    }

    override fun toString(): String {
        return name
    }

    override fun equals(other: Any?): Boolean {
        // TODO add getFileSystem and check if file system is the same
        // TODO check reference
        return other is UsbFile && absolutePath == other.absolutePath
    }

    companion object {
        private val TAG = AbstractUsbFile::class.java.simpleName
    }
}
