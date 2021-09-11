/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage

data class DeviceChange(
    var device: MediaDevice? = null,
    var action: DeviceAction? = null,
    var error: String = ""
)
