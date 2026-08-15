package com.maxlass.studio.device.driver

import org.usb4java.Device
import org.usb4java.DeviceHandle
import org.usb4java.LibUsb
import org.usb4java.LibUsbException
import org.usb4java.Transfer
import org.usb4java.TransferCallback
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * USB Mass Storage Bulk-Only Transport (CBW/CSW) with the Lunii vendor SCSI command set,
 * as a coroutine wrapper around usb4java/libusb. Replaces `studio.driver.raw.LibUsbMassStorageHelper`.
 */
class UsbMassStorage {

    companion object {
        const val SECTOR_SIZE = 512

        private const val INTERFACE_ID = 0
        private const val ENDPOINT_IN: Byte = 0x81.toByte()
        private const val ENDPOINT_OUT: Byte = 0x02
        private const val TIMEOUT_MS = 5_000L

        private val CBW_SIGNATURE = byteArrayOf('U'.code.toByte(), 'S'.code.toByte(), 'B'.code.toByte(), 'C'.code.toByte())
        private val CBW_DIRECTION_IN = byteArrayOf(0x80.toByte())
        private val CBW_DIRECTION_OUT = byteArrayOf(0)
        private val CBW_LUN_0 = byteArrayOf(0)
        private val CBW_COMMAND_BLOCK_SIZE = byteArrayOf(16)

        private val CSW_SIGNATURE = byteArrayOf('U'.code.toByte(), 'S'.code.toByte(), 'B'.code.toByte(), 'S'.code.toByte())

        private val SCSI_READ_FROM_SPI = byteArrayOf(0xF6.toByte(), 0x05, 0x06)
        private val SCSI_READ_FROM_SD = byteArrayOf(0xF6.toByte(), 0xE1.toByte(), 0)
        private val SCSI_WRITE_TO_SD = byteArrayOf(0xF6.toByte(), 0xE2.toByte(), 0)

        private val prng = SecureRandom()
    }

    /**
     * Opens [device], detaches the kernel driver, claims the bulk interface, runs [block],
     * then releases and closes the handle. All failures are surfaced as [StoryTellerException].
     */
    suspend fun <T> withDeviceHandle(device: Device, block: suspend (DeviceHandle) -> T): T {
        val handle = DeviceHandle()
        val openResult = LibUsb.open(device, handle)
        if (openResult != LibUsb.SUCCESS) {
            throw StoryTellerException("Unable to open libusb device", LibUsbException(openResult))
        }
        try {
            val detachResult = LibUsb.detachKernelDriver(handle, INTERFACE_ID)
            if (detachResult != LibUsb.SUCCESS &&
                detachResult != LibUsb.ERROR_NOT_SUPPORTED &&
                detachResult != LibUsb.ERROR_NOT_FOUND
            ) {
                throw StoryTellerException("Unable to detach libusb kernel driver", LibUsbException(detachResult))
            }
            val claimResult = LibUsb.claimInterface(handle, INTERFACE_ID)
            if (claimResult != LibUsb.SUCCESS) {
                throw StoryTellerException("Unable to claim libusb interface", LibUsbException(claimResult))
            }
            return block(handle)
        } finally {
            LibUsb.releaseInterface(handle, INTERFACE_ID)
            LibUsb.close(handle)
        }
    }

    suspend fun readSpiSectors(handle: DeviceHandle, offset: Int, nbSectorsToRead: Short): ByteBuffer {
        val cbw = createSpiReadCbw(offset, nbSectorsToRead)
        bulkTransfer(handle, ENDPOINT_OUT, cbw)
        val data = ByteBuffer.allocateDirect(nbSectorsToRead * SECTOR_SIZE)
        bulkTransfer(handle, ENDPOINT_IN, data)
        checkCommandStatusWrapper(handle, "Read operation failed while reading from SPI")
        data.rewind()
        return data
    }

    suspend fun readSdSectors(handle: DeviceHandle, sector: Int, nbSectorsToRead: Short): ByteBuffer {
        val cbw = createSdReadCbw(sector, nbSectorsToRead)
        bulkTransfer(handle, ENDPOINT_OUT, cbw)
        val data = ByteBuffer.allocateDirect(nbSectorsToRead * SECTOR_SIZE)
        bulkTransfer(handle, ENDPOINT_IN, data)
        checkCommandStatusWrapper(handle, "Read operation failed while reading from SD")
        data.rewind()
        return data
    }

    suspend fun writeSdSectors(handle: DeviceHandle, sector: Int, nbSectorsToWrite: Short, data: ByteBuffer) {
        val cbw = createSdWriteCbw(sector, nbSectorsToWrite)
        bulkTransfer(handle, ENDPOINT_OUT, cbw)
        data.rewind()
        bulkTransfer(handle, ENDPOINT_OUT, data)
        checkCommandStatusWrapper(handle, "Read operation failed while writing to SD")
    }

    /** Reads the 13-byte CSW and validates it (signature, residue, status). */
    private suspend fun checkCommandStatusWrapper(handle: DeviceHandle, message: String) {
        val csw = ByteBuffer.allocateDirect(13)
        bulkTransfer(handle, ENDPOINT_IN, csw)
        csw.rewind()
        val signature = ByteArray(CSW_SIGNATURE.size)
        csw.get(signature)
        if (!Arrays.equals(signature, CSW_SIGNATURE)) {
            throw StoryTellerException("$message (invalid CSW signature)")
        }
        csw.order(ByteOrder.LITTLE_ENDIAN)
        val residue = csw.getInt(8)
        if (residue > 0) {
            throw StoryTellerException("$message (positive CSW residue $residue)")
        }
        val status = csw.get(12)
        if (status != 0.toByte()) {
            throw StoryTellerException("$message (CSW status $status)")
        }
    }

    /** Bulk transfer wrapped in a cancellable suspend call. */
    private suspend fun bulkTransfer(handle: DeviceHandle, endpoint: Byte, data: ByteBuffer): Int =
        suspendCancellableCoroutine { cont ->
            val transfer: Transfer = LibUsb.allocTransfer()
            val completed = AtomicBoolean(false)
            LibUsb.fillBulkTransfer(transfer, handle, endpoint, data, TransferCallback { xfer ->
                if (completed.compareAndSet(false, true)) {
                    try {
                        if (xfer.status() != LibUsb.TRANSFER_COMPLETED) {
                            cont.resumeWithException(
                                StoryTellerException("Transfer ${if (endpoint == ENDPOINT_IN) "IN" else "OUT"} failed: ${xfer.status()}")
                            )
                        } else {
                            cont.resume(xfer.actualLength())
                        }
                    } finally {
                        LibUsb.freeTransfer(xfer)
                    }
                }
            }, null, TIMEOUT_MS)
            val submitResult = LibUsb.submitTransfer(transfer)
            if (submitResult != LibUsb.SUCCESS) {
                if (completed.compareAndSet(false, true)) {
                    LibUsb.freeTransfer(transfer)
                }
                cont.resumeWithException(StoryTellerException("Unable to submit transfer", LibUsbException(submitResult)))
            } else {
                cont.invokeOnCancellation {
                    if (completed.compareAndSet(false, true)) {
                        LibUsb.cancelTransfer(transfer)
                    }
                }
            }
        }

    private fun createSpiReadCbw(offset: Int, nbSectorsToRead: Short): ByteBuffer {
        val bb = createCommandBlockWrapper(CBWDirection.INBOUND, nbSectorsToRead * SECTOR_SIZE)
        bb.put(SCSI_READ_FROM_SPI)
        bb.order(ByteOrder.BIG_ENDIAN)
        bb.putInt(offset)
        bb.putShort(nbSectorsToRead)
        bb.rewind()
        return bb
    }

    private fun createSdReadCbw(sector: Int, nbSectorsToRead: Short): ByteBuffer {
        val bb = createCommandBlockWrapper(CBWDirection.INBOUND, nbSectorsToRead * SECTOR_SIZE)
        bb.put(SCSI_READ_FROM_SD)
        bb.order(ByteOrder.BIG_ENDIAN)
        bb.putInt(sector)
        bb.putShort(nbSectorsToRead)
        bb.rewind()
        return bb
    }

    private fun createSdWriteCbw(sector: Int, nbSectorsToWrite: Short): ByteBuffer {
        val bb = createCommandBlockWrapper(CBWDirection.OUTBOUND, nbSectorsToWrite * SECTOR_SIZE)
        bb.put(SCSI_WRITE_TO_SD)
        bb.order(ByteOrder.BIG_ENDIAN)
        bb.putInt(sector)
        bb.putShort(nbSectorsToWrite)
        bb.rewind()
        return bb
    }

    private fun createCommandBlockWrapper(direction: CBWDirection, dataLength: Int): ByteBuffer {
        val bb = ByteBuffer.allocateDirect(31)
        bb.put(CBW_SIGNATURE)
        val random = ByteArray(4)
        prng.nextBytes(random)
        bb.put(random)
        bb.order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(dataLength)
        bb.put((direction.ordinal shl 7).toByte())
        bb.put(CBW_LUN_0)
        bb.put(CBW_COMMAND_BLOCK_SIZE)
        return bb
    }

    private enum class CBWDirection { OUTBOUND, INBOUND }
}
