/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.repository.entities

import androidx.room.*

@Entity
data class Path(
    @PrimaryKey(autoGenerate = true)
    val pathId: Long = 0,
    @ColumnInfo(index = true)
    val parentPathId: Long = -1,
    @ColumnInfo(index = true)
    val parentPath: String = "/",
    @ColumnInfo(index = true)
    val name: String = "",
    var isDirectory: Boolean = false,
    val lastModifiedEpochTime: Long = -1
) {
    val absolutePath: String
        get() = if (parentPath != "/") {
            "${parentPath}/${name}"
        } else {
            "/${name}"
        }
}
