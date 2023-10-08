package de.moleman1024.audiowagon.filestorage.data

import android.net.Uri
import de.moleman1024.audiowagon.filestorage.FileLike
import java.util.*

data class PlaylistFile(override val uri: Uri = Uri.EMPTY): FileLike {
    override var lastModifiedDate: Date = Date(0)
}
