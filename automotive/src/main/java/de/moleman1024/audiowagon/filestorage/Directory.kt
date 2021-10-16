package de.moleman1024.audiowagon.filestorage

import android.net.Uri

data class Directory(override val uri: Uri = Uri.EMPTY): FileLike
