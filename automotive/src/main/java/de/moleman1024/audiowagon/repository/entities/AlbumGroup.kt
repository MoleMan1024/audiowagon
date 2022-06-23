package de.moleman1024.audiowagon.repository.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class AlbumGroup(
    @PrimaryKey
    val albumGroupIndex: Int = 0,
    val startAlbumId: Long = -1,
    val endAlbumId: Long = -1
)
