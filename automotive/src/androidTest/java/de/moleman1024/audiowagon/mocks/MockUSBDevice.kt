/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.mocks

import android.app.PendingIntent
import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import com.github.mjdev.libaums.fs.FileSystem
import com.github.mjdev.libaums.fs.UsbFile
import com.github.mjdev.libaums.partition.PartitionTypes
import de.moleman1024.audiowagon.filestorage.usb.USBDevice
import de.moleman1024.audiowagon.filestorage.usb.USBDeviceConfig
import de.moleman1024.audiowagon.filestorage.usb.USBEndpoint
import de.moleman1024.audiowagon.filestorage.usb.USBInterface
import java.nio.ByteBuffer

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

    override fun initFilesystem(context: Context): FileSystem? {
        return object : FileSystem {
            override val capacity: Long
                get() = freeSpace + occupiedSpace
            override val chunkSize: Int
                get() = 4096
            override val freeSpace: Long
                get() = 1024 * 1024
            override val occupiedSpace: Long
                get() = 1024 * 8
            override val rootDirectory: UsbFile
                get() = getRootDir()
            override val type: Int
                get() = PartitionTypes.FAT32
            override val volumeLabel: String
                get() = "mock_volume_label"
        }
    }

    fun getRootDir(): UsbFile {
        return object : UsbFile {
            override val absolutePath: String
                get() = "/"
            override val isDirectory: Boolean
                get() = true
            override val isRoot: Boolean
                get() = true
            override var length: Long
                get() = 0
                set(value) {}
            override var name: String
                get() = "rootDir"
                set(value) {}
            override val parent: UsbFile?
                get() = null

            override fun close() {
                // no op
            }

            override fun createDirectory(name: String): UsbFile {
                TODO()
            }

            override fun createFile(name: String): UsbFile {
                TODO()
            }

            override fun createdAt(): Long {
                return 0L
            }

            override fun delete() {
                // no op
            }

            override fun flush() {
                // no op
            }

            override fun lastAccessed(): Long {
                return 0L
            }

            override fun lastModified(): Long {
                return 0L
            }

            override fun list(): Array<String> {
                return arrayOf()
            }

            override fun listFiles(): Array<UsbFile> {
                return arrayOf()
            }

            override fun moveTo(destination: UsbFile) {
                // no op
            }

            override fun read(offset: Long, destination: ByteBuffer) {
                // no op
            }

            override fun search(path: String): UsbFile? {
                return null
            }

            override fun write(offset: Long, source: ByteBuffer) {
                // no op
            }
        }
    }

    override fun close() {

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
        @Suppress("unused")
        @JvmField
        val CREATOR: Parcelable.Creator<MockUSBDevice> = object : Parcelable.Creator<MockUSBDevice> {
            override fun createFromParcel(source: Parcel?): MockUSBDevice {
                return MockUSBDevice(
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
            }

            override fun newArray(size: Int): Array<MockUSBDevice> {
                return arrayOf()
            }

        }
    }
}
