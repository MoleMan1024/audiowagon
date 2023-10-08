/*
SPDX-FileCopyrightText: 2021-2022 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.enums

enum class SingletonCoroutineBehaviour {
    // cancel any coroutine in progress, wait for it to be cancelled, then launch a new one
    CANCEL_OTHER_ON_LAUNCH,
    // ignore new coroutine if another one is currently in progress and prefer to finalize that one
    PREFER_FINISH,
}
