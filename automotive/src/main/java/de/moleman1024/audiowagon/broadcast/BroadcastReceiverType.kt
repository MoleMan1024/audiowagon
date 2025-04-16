/*
SPDX-FileCopyrightText: 2025 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.broadcast

/**
 * I use an enum here to attach to the specific subclass of a ManagedBroadcastReceiver because kotlin reflection
 * to inspect the class at runtime was not working properly
 */
enum class BroadcastReceiverType {
    MEDIA,
    SYSTEM,
    USB_EXTERNAL,
    USB_INTERNAL,
}
