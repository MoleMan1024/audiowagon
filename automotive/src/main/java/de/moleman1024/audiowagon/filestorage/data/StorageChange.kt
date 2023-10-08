/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.data

import de.moleman1024.audiowagon.enums.StorageAction

data class StorageChange(
    var id: String = "",
    var action: StorageAction = StorageAction.ADD,
    var error: String = ""
)
