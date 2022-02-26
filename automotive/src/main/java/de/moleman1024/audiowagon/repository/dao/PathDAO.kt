/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.repository.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import de.moleman1024.audiowagon.repository.entities.Path

@Dao
interface PathDAO {

    @Insert
    fun insert(path: Path): Long

    @Query("SELECT * FROM path WHERE parentURIString = '' ORDER BY name COLLATE NOCASE ASC")
    fun queryRoot(): List<Path>
}
