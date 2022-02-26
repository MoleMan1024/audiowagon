/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.repository.entities

import androidx.room.*

@Entity
data class Path(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val childIds: MutableList<Long> = mutableListOf(),
    // URI of file or directory
    @ColumnInfo(index = true)
    val parentURIString: String = "",
    // name of file or directory
    val name: String = ""
)
