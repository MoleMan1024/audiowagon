/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.repository.entities

import androidx.room.Entity
import androidx.room.Fts4

@Entity
@Fts4(contentEntity = Album::class)
data class AlbumFTS(
    val name: String
)
