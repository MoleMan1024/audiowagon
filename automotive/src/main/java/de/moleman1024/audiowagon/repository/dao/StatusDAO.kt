/*
SPDX-FileCopyrightText: 2021-2022 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.repository.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import de.moleman1024.audiowagon.repository.entities.Status

@Dao
interface StatusDAO {

    @Insert
    fun insert(status: Status)

    @Query("SELECT * FROM status WHERE storageID = :repositoryID")
    fun queryStatus(repositoryID: String): Status?

    @Query("DELETE FROM status WHERE storageID = :repositoryID")
    fun deleteStatus(repositoryID: String)
}
