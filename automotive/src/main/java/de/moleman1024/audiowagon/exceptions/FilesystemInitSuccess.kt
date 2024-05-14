/*
SPDX-FileCopyrightText: 2021-2024 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.exceptions

// This is not an exception, I use it to record some statistics about successful filesystem initialization in
// Crashlytics
class FilesystemInitSuccess : RuntimeException() {
}
