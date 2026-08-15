package com.maxlass.studio.device.driver

import java.util.UUID

/** Exception thrown by the device drivers (replaces `studio.driver.StoryTellerException`). */
class StoryTellerException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Physical device type, mapped to Lunii USB VID/PID pairs. */
enum class LuniiDeviceKind {
    /** Lunii 1.x: raw sector access (vendor SCSI via libusb). VID/PID 0c45:6820. */
    RAW,

    /** Lunii 2.x/3.x: FS access via the mounted partition. VID/PID 0c45:6840 and 0483:a341. */
    FS,
}

/** Device infos read from a raw (Lunii 1.x) device. */
data class RawDeviceInfos(
    val uuid: UUID?,
    val firmwareMajor: Short,
    val firmwareMinor: Short,
    val serialNumber: String?,
    val sdCardSizeInSectors: Int,
    val usedSpaceInSectors: Int,
    val inError: Boolean,
)

/** One pack listed in the raw device pack index. */
data class RawStoryPackInfos(
    val uuid: UUID?,
    val version: Short,
    val startSector: Int,
    val sizeInSectors: Int,
    val statsOffset: Short,
    val samplingRate: Short,
)

/** Device infos read from an FS (Lunii 2.x/3.x) device partition. */
data class FsDeviceInfos(
    val uuid: ByteArray?,
    val firmwareMajor: Short,
    val firmwareMinor: Short,
    val serialNumber: String?,
    val sdCardSizeInBytes: Long,
    val usedSpaceInBytes: Long,
    val deviceKeyV3: FsDeviceKeyV3?,
)

/** One pack present in the FS device pack index (.pi). */
data class FsStoryPackInfos(
    val uuid: UUID,
    val folderName: String,
    val version: Short,
    val sizeInBytes: Long,
    val nightModeAvailable: Boolean,
)

/** Device-specific AES-CBC key material for Lunii v3 FS packs. */
data class FsDeviceKeyV3(
    val aesKey: ByteArray,
    val aesIv: ByteArray,
    val bt: ByteArray,
)

/** Transfer progress snapshot. */
data class TransferStatus(
    val done: Boolean,
    val transferred: Int,
    val total: Int,
    val speed: Double,
)
