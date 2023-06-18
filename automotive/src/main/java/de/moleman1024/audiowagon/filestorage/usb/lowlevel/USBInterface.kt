/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage.usb.lowlevel

interface USBInterface {
    val interfaceIndex: Int
    val id: Int
    val interfaceClass: Int
    val interfaceSubclass: Int
    val interfaceProtocol: Int
    val endpointCount: Int

    fun getEndpoint(endpointIndex: Int): USBEndpoint
}
