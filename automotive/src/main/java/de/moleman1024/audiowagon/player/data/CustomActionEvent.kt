/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.player.data

import de.moleman1024.audiowagon.enums.CustomAction

data class CustomActionEvent(val action: CustomAction) {
    var syncURL: String = ""
}
