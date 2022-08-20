/*
SPDX-FileCopyrightText: 2021-2022 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage

import kotlinx.coroutines.CoroutineDispatcher
import java.io.InputStream

/**
 * An inputstream with an optional lock.
 * Used for streams from USB filesystem that must be accessed exclusively.
 */
data class LockableInputStream(val inputStream: InputStream, val libaumsDispatcher: CoroutineDispatcher? = null)

