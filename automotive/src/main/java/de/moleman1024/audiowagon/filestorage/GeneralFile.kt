package de.moleman1024.audiowagon.filestorage

import android.net.Uri

// bucket for any other non-playable files not on USB drive (e.g. image files for album art)
data class GeneralFile(override val uri: Uri = Uri.EMPTY): FileLike
