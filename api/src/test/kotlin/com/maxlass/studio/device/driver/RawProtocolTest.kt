package com.maxlass.studio.device.driver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class RawProtocolTest : StringSpec({

    fun spiSector(serial: Long, uuidLow: Long, uuidHigh: Long): ByteBuffer {
        val bb = ByteBuffer.allocate(512).order(ByteOrder.BIG_ENDIAN)
        bb.putLong(0, serial)
        bb.putLong(8, uuidLow)
        bb.putLong(16, uuidHigh)
        return bb
    }

    fun sd2Sector(major: Byte, minor: Byte, sizeByte26: Byte, versionString: String = "version"): ByteBuffer {
        val bb = ByteBuffer.allocate(512).order(ByteOrder.BIG_ENDIAN)
        versionString.forEachIndexed { i, c -> bb.put(i * 2, c.code.toByte()) }
        bb.put(16, major)
        bb.put(20, minor)
        bb.put(24, 0)
        bb.put(26, sizeByte26)
        bb.put(27, 0)
        return bb
    }

    val driver = RawStoryTellerDriver(UsbMassStorage())

    "parseDeviceInfos reads uuid, serial, firmware and sd size" {
        val spi = spiSector(serial = 12_345, uuidLow = 1, uuidHigh = 2)
        val sd2 = sd2Sector(major = 2, minor = 1, sizeByte26 = 1)

        val infos = driver.parseDeviceInfos(spi, sd2, emptyList())

        infos.uuid shouldBe UUID(2, 1)
        infos.serialNumber shouldBe "00000000012345"
        infos.firmwareMajor shouldBe 2
        infos.firmwareMinor shouldBe 1
        // (1<<24)|0|0|1 = 16777217, then -20480 (FAT16) -100000 (pack index)
        infos.sdCardSizeInSectors shouldBe 16_656_737
        infos.usedSpaceInSectors shouldBe 0
        infos.inError shouldBe false
    }

    "parseDeviceInfos reports missing uuid/serial/firmware as null/-1" {
        val spi = spiSector(serial = 0, uuidLow = 0, uuidHigh = 0)
        val sd2 = sd2Sector(major = 0, minor = 0, sizeByte26 = 0, versionString = "xxxxxxx")

        val infos = driver.parseDeviceInfos(spi, sd2, emptyList())

        infos.uuid shouldBe null
        infos.serialNumber shouldBe null
        infos.firmwareMajor shouldBe -1
        infos.firmwareMinor shouldBe -1
        infos.sdCardSizeInSectors shouldBe 6_715_513 // default 6815513 - 100000
    }

    "parsePackIndex round-trips through serializePackIndex" {
        val packs = listOf(
            RawStoryPackInfos(null, 0, startSector = 10, sizeInSectors = 500, statsOffset = 1, samplingRate = 2),
            RawStoryPackInfos(null, 0, startSector = 600, sizeInSectors = 1000, statsOffset = 3, samplingRate = 4),
        )

        val sector = driver.serializePackIndex(packs)
        val reread = driver.parsePackIndex(sector)

        reread.size shouldBe 2
        reread[0].startSector shouldBe 10
        reread[0].sizeInSectors shouldBe 500
        reread[0].statsOffset shouldBe 1
        reread[0].samplingRate shouldBe 2
        reread[1].startSector shouldBe 600
        reread[1].sizeInSectors shouldBe 1000
    }

    "findFirstSuitableSector returns the first gap big enough" {
        val packs = listOf(
            RawStoryPackInfos(null, 0, startSector = 100, sizeInSectors = 10, statsOffset = 0, samplingRate = 0),
            RawStoryPackInfos(null, 0, startSector = 500, sizeInSectors = 10, statsOffset = 0, samplingRate = 0),
        )
        // Gap 0..99, then 110..499 (pack1 ends at 109)
        driver.findFirstSuitableSector(packs, packSizeInSectors = 50, sdCardSizeInSectors = 1000) shouldBe 1
        driver.findFirstSuitableSector(packs, packSizeInSectors = 110, sdCardSizeInSectors = 1000) shouldBe 110
    }

    "findFirstSuitableSector returns null when no gap fits" {
        val packs = listOf(
            RawStoryPackInfos(null, 0, startSector = 1, sizeInSectors = 999, statsOffset = 0, samplingRate = 0),
        )
        driver.findFirstSuitableSector(packs, packSizeInSectors = 500, sdCardSizeInSectors = 1000) shouldBe null
    }
})
