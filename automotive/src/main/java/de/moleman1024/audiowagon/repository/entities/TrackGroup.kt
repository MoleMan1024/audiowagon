package de.moleman1024.audiowagon.repository.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class TrackGroup(
    @PrimaryKey
    val trackGroupIndex: Int = 0,
    val startTrackId: Long = -1,
    val endTrackId: Long = -1
)
