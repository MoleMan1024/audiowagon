package de.moleman1024.audiowagon.filestorage

import android.net.Uri
import java.util.*

interface FileLike {
    val uri: Uri
    val storageID: String
        get() = uri.authority.toString()
    val path: String
        get() {
            val filePathStringBuilder: StringBuilder = StringBuilder()
            uri.path?.let {
                filePathStringBuilder.append(it)
            }
            uri.fragment?.let {
                filePathStringBuilder.append("#$it")
            }
            return filePathStringBuilder.toString()
        }
    val name: String
        get() = path.split("/").last()
    val parentPath: String
        get() {
            val pathParts = path.split("/")
            return if (pathParts.size <= 2) {
                "/"
            } else {
                pathParts.toMutableList().dropLast(1).joinToString("/")
            }
        }
    var lastModifiedDate: Date
}
