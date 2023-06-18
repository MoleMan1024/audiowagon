/*
 * (C) Copyright 2014 mjahnen <github@mgns.tech>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package de.moleman1024.audiowagon.filestorage.usb.lowlevel.driver

import de.moleman1024.audiowagon.filestorage.usb.lowlevel.USBCommunication
import de.moleman1024.audiowagon.filestorage.usb.lowlevel.driver.scsi.ScsiBlockDevice

/**
 * A helper class to create different [BlockDeviceDriver]s.
 *
 * @author mjahnen
 */
object BlockDeviceDriverFactory {
    /**
     * This method creates a
     * [BlockDeviceDriver] which is
     * suitable for the underlying mass storage device.
     *
     * @param usbCommunication
     * The underlying USB communication.
     * @return A driver which can handle the USB mass storage device.
     */
    fun createBlockDevice(usbCommunication: USBCommunication, lun: Byte): BlockDeviceDriver {
        // we currently only support SCSI transparent command set
        return ScsiBlockDevice(usbCommunication, lun)
    }
}
