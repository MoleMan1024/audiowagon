/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.exceptions

import java.io.IOException

class TooManyFilesInDirException : IOException("Too many files in directory, only 128 files allowed")
