package de.moleman1024.audiowagon.repository.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class ArtistGroup(
    @PrimaryKey
    val artistGroupIndex: Int = 0,
    val startArtistId: Long = -1,
    val endArtistId: Long = -1
)
