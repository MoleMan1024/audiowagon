/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.mocks

import android.app.PendingIntent
import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import me.jahnen.libaums.core.fs.FileSystem
import de.moleman1024.audiowagon.filestorage.usb.USBDevice
import de.moleman1024.audiowagon.filestorage.usb.USBDeviceConfig
import de.moleman1024.audiowagon.filestorage.usb.USBEndpoint
import de.moleman1024.audiowagon.filestorage.usb.USBInterface

private const val TAG = "MockUSBDevice"

class MockUSBDevice(
    override var configurationCount: Int = 1,
    override var deviceClass: Int = 8,
    override var deviceName: String = "MockUSBDevice",
    override var interfaceCount: Int = 1,
    override var manufacturerName: String? = "Mock Ltd.",
    override var productId: Int = 7715,
    override var productName: String? = "Mock USB flash drive",
    override var vendorId: Int = 5118,
    override var serialNumber: String? = "123456789ABC"
) : USBDevice, Parcelable {
    var hasPermission = true
    var endpointToggle = false
    var fileSystem = InMemoryFileSystem()

    override fun requestPermission(intent: PendingIntent) {
        // no op
    }

    override fun hasPermission(): Boolean {
        return hasPermission
    }

    override fun getConfiguration(configIndex: Int): USBDeviceConfig {
        return object : USBDeviceConfig {
            override val interfaceCount: Int
                get() = 1

            override fun getInterface(index: Int): USBInterface {
                return this.getInterface(index)
            }

        }
    }

    override fun getInterface(interfaceIndex: Int): USBInterface {
        return object : USBInterface {
            override val interfaceClass: Int
                get() = 8
            override val interfaceSubclass: Int
                get() = 6
            override val interfaceProtocol: Int
                get() = 80
            override val endpointCount: Int
                get() = 2

            override fun getEndpoint(endpointIndex: Int): USBEndpoint {
                return object : USBEndpoint {
                    override val type: Int
                        get() = 2
                    override val direction: Int
                        get() {
                            endpointToggle = !endpointToggle
                            return if (endpointToggle) {
                                0
                            } else {
                                0x80
                            }
                        }

                }
            }

        }
    }

    override fun initFilesystem(context: Context): FileSystem {
        fileSystem.init()
        usbDeviceToFileSystemMap[hashCode().toString()] = fileSystem
        return fileSystem
    }

    override fun close() {
        usbDeviceToFileSystemMap.remove(hashCode().toString())
        fileSystem.close()
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel?, flags: Int) {
        dest?.writeInt(configurationCount)
        dest?.writeInt(deviceClass)
        dest?.writeString(deviceName)
        dest?.writeInt(interfaceCount)
        dest?.writeString(manufacturerName)
        dest?.writeInt(productId)
        dest?.writeString(productName)
        dest?.writeInt(vendorId)
        dest?.writeString(serialNumber)
    }

    companion object {
        val usbDeviceToFileSystemMap = mutableMapOf<String, InMemoryFileSystem>()

        @Suppress("unused")
        @JvmField
        val CREATOR: Parcelable.Creator<MockUSBDevice> = object : Parcelable.Creator<MockUSBDevice> {
            override fun createFromParcel(source: Parcel?): MockUSBDevice {
                val mockUSBDevice = MockUSBDevice(
                    configurationCount = source!!.readInt(),
                    deviceClass = source.readInt(),
                    deviceName = source.readString().toString(),
                    interfaceCount = source.readInt(),
                    manufacturerName = source.readString(),
                    productId = source.readInt(),
                    productName = source.readString().toString(),
                    vendorId = source.readInt(),
                    serialNumber = source.readString()
                )
                if (usbDeviceToFileSystemMap.containsKey(mockUSBDevice.hashCode().toString())) {
                    mockUSBDevice.fileSystem = usbDeviceToFileSystemMap[mockUSBDevice.hashCode().toString()]!!
                }
                return mockUSBDevice
            }

            override fun newArray(size: Int): Array<MockUSBDevice> {
                return arrayOf()
            }

        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MockUSBDevice

        if (configurationCount != other.configurationCount) return false
        if (deviceClass != other.deviceClass) return false
        if (deviceName != other.deviceName) return false
        if (interfaceCount != other.interfaceCount) return false
        if (manufacturerName != other.manufacturerName) return false
        if (productId != other.productId) return false
        if (productName != other.productName) return false
        if (vendorId != other.vendorId) return false
        if (serialNumber != other.serialNumber) return false

        return true
    }

    override fun hashCode(): Int {
        var result = configurationCount
        result = 31 * result + deviceClass
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + interfaceCount
        result = 31 * result + (manufacturerName?.hashCode() ?: 0)
        result = 31 * result + productId
        result = 31 * result + (productName?.hashCode() ?: 0)
        result = 31 * result + vendorId
        result = 31 * result + (serialNumber?.hashCode() ?: 0)
        return result
    }

}
