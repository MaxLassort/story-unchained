package com.maxlass.studio.device.driver

import org.usb4java.Device
import org.usb4java.DeviceHandle
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Raw Lunii 1.x driver: sector-based access to the SD card via the vendor SCSI command set.
 * Replaces `studio.driver.raw.RawStoryTellerAsyncDriver`. The protocol parsing is kept in
 * pure functions so it can be unit-tested against sector buffers.
 */
class RawStoryTellerDriver(private val usb: UsbMassStorage) {

    companion object {
        const val SECTOR_SIZE = UsbMassStorage.SECTOR_SIZE

        private const val SDCARD_DEFAULT_SIZE_IN_SECTORS = 6_815_513
        private const val SDCARD_FAT16_PARTITION_SIZE_IN_SECTORS = 20_480
        private const val DEVICE_INFOS_SPI_OFFSET = 520_192
        private const val DEVICE_INFOS_SD_SECTOR_2 = 2
        private const val PACK_INDEX_SD_SECTOR = 100_000
        private const val PACK_TRANSFER_CHUNK_SIZE_IN_SECTORS = 5_000
    }

    @Volatile
    private var device: Device? = null

    fun onPlugged(device: Device) {
        this.device = device
    }

    fun onUnplugged() {
        this.device = null
    }

    suspend fun getDeviceInfos(): RawDeviceInfos {
        val device = requireDevice()
        return usb.withDeviceHandle(device) { handle ->
            val spi = usb.readSpiSectors(handle, DEVICE_INFOS_SPI_OFFSET, 1)
            val sd2 = usb.readSdSectors(handle, DEVICE_INFOS_SD_SECTOR_2, 1)
            val packs = readPackIndex(handle)
            parseDeviceInfos(spi, sd2, packs)
        }
    }

    suspend fun getPacksList(): List<RawStoryPackInfos> {
        val device = requireDevice()
        return usb.withDeviceHandle(device) { readPackIndex(it) }
    }

    suspend fun deletePack(uuid: String): Boolean {
        val device = requireDevice()
        return usb.withDeviceHandle(device) { handle ->
            val packs = readPackIndex(handle).toMutableList()
            val matched = packs.firstOrNull { it.uuid == UUID.fromString(uuid) }
                ?: throw StoryTellerException("Pack not found")
            packs.remove(matched)
            writePackIndex(handle, packs)
            true
        }
    }

    suspend fun uploadPack(input: InputStream, packSizeInSectors: Int): TransferStatus {
        val device = requireDevice()
        return usb.withDeviceHandle(device) { handle ->
            val startSector = findFirstSuitableSector(handle, packSizeInSectors)
                ?: throw StoryTellerException("Not enough free space on the device")
            var transferred = 0
            var offset = 0
            while (offset < packSizeInSectors) {
                val nbSectors = minOf(PACK_TRANSFER_CHUNK_SIZE_IN_SECTORS, packSizeInSectors - offset)
                val chunkSize = nbSectors * SECTOR_SIZE
                val chunk = input.readNBytes(chunkSize)
                require(chunk.size == chunkSize) { "Pack input stream ended prematurely" }
                val bb = ByteBuffer.allocateDirect(chunkSize).put(chunk).rewind()
                usb.writeSdSectors(handle, PACK_INDEX_SD_SECTOR + startSector + offset, nbSectors.toShort(), bb)
                transferred += chunkSize
                offset += nbSectors
            }
            val packs = readPackIndex(handle).toMutableList()
            packs.add(RawStoryPackInfos(null, 0, startSector, packSizeInSectors, 0, 0))
            writePackIndex(handle, packs)
            TransferStatus(done = true, transferred = transferred, total = packSizeInSectors * SECTOR_SIZE, speed = 0.0)
        }
    }

    suspend fun downloadPack(uuid: String, output: OutputStream): TransferStatus {
        val device = requireDevice()
        return usb.withDeviceHandle(device) { handle ->
            val packs = readPackIndex(handle)
            val pack = packs.firstOrNull { it.uuid == UUID.fromString(uuid) }
                ?: throw StoryTellerException("Pack not found")
            var transferred = 0
            var offset = 0
            while (offset < pack.sizeInSectors) {
                val nbSectors = minOf(PACK_TRANSFER_CHUNK_SIZE_IN_SECTORS, pack.sizeInSectors - offset)
                val data = usb.readSdSectors(handle, PACK_INDEX_SD_SECTOR + pack.startSector + offset, nbSectors.toShort())
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                output.write(bytes)
                transferred += bytes.size
                offset += nbSectors
            }
            TransferStatus(done = true, transferred = transferred, total = pack.sizeInSectors * SECTOR_SIZE, speed = 0.0)
        }
    }

    // --- pure protocol helpers (unit-testable) ---

    /** Parses UUID / serial from the SPI device-infos sector and firmware / SD size from sector 2. */
    internal fun parseDeviceInfos(spi: ByteBuffer, sd2: ByteBuffer, packs: List<RawStoryPackInfos>): RawDeviceInfos {
        val uuidLow = spi.getLong(8)
        val uuidHigh = spi.getLong(16)
        val uuid = if (isMeaningful(uuidLow, uuidHigh)) UUID(uuidHigh, uuidLow) else null

        val serialRaw = spi.getLong(0)
        val serial = if (serialRaw != 0L && serialRaw != -1L && serialRaw != -4_294_967_296L) {
            String.format("%014d", serialRaw)
        } else {
            null
        }

        val versionChars = charArrayOf(
            sd2.get(0).toInt().toChar(), sd2.get(2).toInt().toChar(), sd2.get(4).toInt().toChar(), sd2.get(6).toInt().toChar(),
            sd2.get(8).toInt().toChar(), sd2.get(10).toInt().toChar(), sd2.get(12).toInt().toChar(),
        )
        val hasVersion = String(versionChars) == "version"
        val major: Short = if (hasVersion) sd2.get(16).toShort() else -1
        val minor: Short = if (hasVersion) sd2.get(20).toShort() else -1

        var sdCardSizeInSectors = -1
        if (major >= 1 && minor >= 1) {
            sdCardSizeInSectors = ((sd2.get(26).toInt() and 0xFF) shl 24) or
                ((sd2.get(27).toInt() and 0xFF) shl 16) or
                ((sd2.get(24).toInt() and 0xFF) shl 8) or
                (sd2.get(26).toInt() and 0xFF)
        }
        sdCardSizeInSectors = if (sdCardSizeInSectors == -1) {
            SDCARD_DEFAULT_SIZE_IN_SECTORS
        } else {
            sdCardSizeInSectors - SDCARD_FAT16_PARTITION_SIZE_IN_SECTORS
        } - PACK_INDEX_SD_SECTOR

        val errorCode = sd2.getShort(0)
        val usedSpace = packs.sumOf { it.sizeInSectors }

        return RawDeviceInfos(
            uuid = uuid,
            firmwareMajor = major,
            firmwareMinor = minor,
            serialNumber = serial,
            sdCardSizeInSectors = sdCardSizeInSectors,
            usedSpaceInSectors = usedSpace,
            inError = errorCode == 1.toShort(),
        )
    }

    /** Parses the pack index sector (entries only; per-pack version/uuid reads happen elsewhere). */
    internal fun parsePackIndex(sector: ByteBuffer): List<RawStoryPackInfos> {
        sector.rewind()
        val nbPacks = sector.short
        val packs = ArrayList<RawStoryPackInfos>(maxOf(0, nbPacks.toInt()))
        for (i in 0 until nbPacks) {
            val startSector = sector.int
            val sizeInSectors = sector.int
            val statsOffset = sector.short
            val samplingRate = sector.short
            packs.add(RawStoryPackInfos(null, 0, startSector, sizeInSectors, statsOffset, samplingRate))
        }
        return packs
    }

    /** Serializes the pack index sector. */
    internal fun serializePackIndex(packs: List<RawStoryPackInfos>): ByteBuffer {
        val bb = ByteBuffer.allocateDirect(512)
        bb.putShort(packs.size.toShort())
        for (pack in packs) {
            bb.putInt(pack.startSector)
            bb.putInt(pack.sizeInSectors)
            bb.putShort(pack.statsOffset)
            bb.putShort(pack.samplingRate)
        }
        bb.rewind()
        return bb
    }

    /** Finds the first contiguous free sector range big enough for [packSizeInSectors]. */
    internal fun findFirstSuitableSector(packs: List<RawStoryPackInfos>, packSizeInSectors: Int, sdCardSizeInSectors: Int): Int? {
        var previousUsedSector = 0
        for (pack in packs.sortedBy { it.startSector }) {
            val nextUsedSector = pack.startSector
            if (nextUsedSector - previousUsedSector >= packSizeInSectors) {
                return previousUsedSector + 1
            }
            previousUsedSector = pack.startSector + pack.sizeInSectors - 1
        }
        val firstUnavailableSector = sdCardSizeInSectors + 1
        return if (firstUnavailableSector - previousUsedSector >= packSizeInSectors) previousUsedSector + 1 else null
    }

    private fun isMeaningful(low: Long, high: Long): Boolean =
        !((low == 0L && high == 0L) || (low == -1L && high == -1L) || (low == -4_294_967_296L && high == -4_294_967_296L))

    // --- device-bound operations ---

    private suspend fun readPackIndex(handle: DeviceHandle): List<RawStoryPackInfos> {
        val sector = usb.readSdSectors(handle, PACK_INDEX_SD_SECTOR, 1)
        sector.rewind()
        val nbPacks = sector.short
        val packs = ArrayList<RawStoryPackInfos>(maxOf(0, nbPacks.toInt()))
        for (i in 0 until nbPacks) {
            val startSector = sector.int
            val sizeInSectors = sector.int
            val statsOffset = sector.short
            val samplingRate = sector.short
            val packSectors = usb.readSdSectors(handle, PACK_INDEX_SD_SECTOR + startSector, 2)
            var version = packSectors.getShort(3)
            if (version == 0.toShort()) {
                version = 1
            }
            val uuidHigh = packSectors.getLong(512)
            val uuidLow = packSectors.getLong(520)
            packs.add(RawStoryPackInfos(UUID(uuidHigh, uuidLow), version, startSector, sizeInSectors, statsOffset, samplingRate))
        }
        return packs
    }

    private suspend fun writePackIndex(handle: DeviceHandle, packs: List<RawStoryPackInfos>) {
        usb.writeSdSectors(handle, PACK_INDEX_SD_SECTOR, 1, serializePackIndex(packs))
    }

    private suspend fun findFirstSuitableSector(handle: DeviceHandle, packSizeInSectors: Int): Int? {
        val packs = readPackIndex(handle)
        val sdCardSizeInSectors = readSdCardSizeInSectors(handle)
        return findFirstSuitableSector(packs, packSizeInSectors, sdCardSizeInSectors)
    }

    private suspend fun readSdCardSizeInSectors(handle: DeviceHandle): Int {
        val spi = usb.readSpiSectors(handle, DEVICE_INFOS_SPI_OFFSET, 1)
        val sd2 = usb.readSdSectors(handle, DEVICE_INFOS_SD_SECTOR_2, 1)
        return parseDeviceInfos(spi, sd2, emptyList()).sdCardSizeInSectors
    }

    private fun requireDevice(): Device = device ?: throw StoryTellerException("No device plugged")
}
