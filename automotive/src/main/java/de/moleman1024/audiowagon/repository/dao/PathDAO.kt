/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.repository.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import de.moleman1024.audiowagon.repository.MAX_DATABASE_SEARCH_ROWS
import de.moleman1024.audiowagon.repository.entities.Path

@Dao
interface PathDAO {

    @Insert
    fun insert(path: Path): Long

    @Query("SELECT * FROM path WHERE parentPath || '/' || name = :parentPath LIMIT 1")
    fun queryParentPath(parentPath: String): Path?

    @Query("SELECT * FROM path WHERE parentPath = :path ORDER BY name COLLATE NOCASE ASC")
    fun queryFilesRecursive(path: String): List<Path>

    @Query("SELECT * FROM path WHERE parentPath = '/' AND name != '' ORDER BY name COLLATE NOCASE ASC")
    fun queryRootDirs(): List<Path>

    @Query("SELECT COUNT(*) FROM path")
    fun queryNumPaths(): Int

    @Query("SELECT * FROM path ORDER BY name COLLATE NOCASE ASC")
    fun queryAll(): List<Path>

    @Query("SELECT pathId FROM path WHERE parentPath || '/' || name = :path OR parentPath || name = :path")
    fun queryIDByURI(path: String): Long?

    @Query("SELECT * FROM path WHERE pathId = :pathId ORDER BY  name COLLATE NOCASE ASC")
    fun queryByID(pathId: Long): Path?

    @Query(
        "SELECT DISTINCT path.* FROM path JOIN pathfts ON path.name = pathfts.name WHERE pathfts MATCH :query " +
                "LIMIT $MAX_DATABASE_SEARCH_ROWS"
    )
    fun search(query: String): List<Path>

    @Delete
    fun delete(path: Path)

    @Query("DELETE FROM path WHERE pathId = :pathId")
    fun deleteByID(pathId: Long)

}
