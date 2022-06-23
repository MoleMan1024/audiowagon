package de.moleman1024.audiowagon.repository.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Status(
    @PrimaryKey
    val storageID: String = "",
    val wasCompletedOnce: Boolean = false
)
