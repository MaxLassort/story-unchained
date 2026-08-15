package com.maxlass.studio.device.driver

import com.maxlass.studio.pack.format.PackFixtures
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class FsStoryTellerDriverTest : StringSpec({

    val driver = FsStoryTellerDriver()
    val uuid = "12345678-1234-1234-1234-123456789abc"
    val folderName = "56789ABC"

    fun buildMdV2(major: Short = 2, minor: Short = 0, serial: Long = 12_345): ByteArray {
        val data = ByteArray(510)
        val le = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        le.putShort(0, 2) // metadata version
        le.putShort(4, major)
        le.putShort(6, minor)
        ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).putLong(8, serial)
        return data
    }

    fun buildMdV7(): ByteArray {
        val data = ByteArray(128)
        val le = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        le.putShort(0, 7) // metadata version
        data[2] = '3'.code.toByte() // major (ASCII)
        data[3] = '.'.code.toByte()
        data[4] = '3'.code.toByte() // minor (ASCII)
        val sn = "23023030061483".toByteArray()
        System.arraycopy(sn, 0, data, 26, sn.size)
        val aesKey = ByteArray(16) { it.toByte() }
        val aesIv = ByteArray(16) { (it + 100).toByte() }
        System.arraycopy(aesKey, 0, data, 64, 16)
        System.arraycopy(aesIv, 0, data, 80, 16)
        return data
    }

    "getDeviceInfos parses the .md metadata (v2 firmware)" {
        val deviceDir = Files.createTempDirectory("fs-device-infos")
        Files.write(deviceDir.resolve(".md"), buildMdV2())
        Files.write(deviceDir.resolve(".pi"), ByteArray(0))
        driver.partitionMountPoint = deviceDir

        val infos = driver.getDeviceInfos()

        infos.firmwareMajor shouldBe 2
        infos.firmwareMinor shouldBe 0
        infos.serialNumber shouldBe "00000000012345"
        infos.uuid.shouldNotBeNull()
        infos.uuid!!.size shouldBe 256
        infos.sdCardSizeInBytes shouldBe deviceDir.toFile().totalSpace
        infos.usedSpaceInBytes shouldBe deviceDir.toFile().totalSpace - deviceDir.toFile().freeSpace

        deviceDir.toFile().deleteRecursively()
    }

    "getDeviceInfos parses the .md metadata (v7 firmware, device key v3)" {
        val deviceDir = Files.createTempDirectory("fs-device-v7")
        Files.write(deviceDir.resolve(".md"), buildMdV7())
        Files.write(deviceDir.resolve(".pi"), ByteArray(0))
        driver.partitionMountPoint = deviceDir

        val infos = driver.getDeviceInfos()

        infos.firmwareMajor shouldBe 3
        infos.firmwareMinor shouldBe 3
        infos.serialNumber shouldBe "23023030061483\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
        infos.deviceKeyV3.shouldNotBeNull()
        infos.deviceKeyV3!!.aesKey shouldBe ByteArray(16) { it.toByte() }
        infos.deviceKeyV3!!.aesIv shouldBe ByteArray(16) { (it + 100).toByte() }
        infos.uuid!!.size shouldBe 64

        deviceDir.toFile().deleteRecursively()
    }

    "upload then download round-trips a cleartext pack (firmware v2, XXTEA common key)" {
        val deviceDir = Files.createTempDirectory("fs-device")
        Files.write(deviceDir.resolve(".md"), buildMdV2())
        Files.write(deviceDir.resolve(".pi"), ByteArray(0))
        driver.partitionMountPoint = deviceDir

        val libraryDir = Files.createTempDirectory("fs-library")
        val sourcePack = PackFixtures.buildFsPack(
            libraryDir,
            imageBytes = ByteArray(64) { it.toByte() },
            audioBytes = ByteArray(64) { (it + 100).toByte() },
        )

        driver.uploadPack(uuid, sourcePack.toString())

        // .pi updated + pack folder created on the "device"
        val packs = driver.getPacksList()
        packs.size shouldBe 1
        packs[0].uuid.toString() shouldBe uuid
        packs[0].folderName shouldBe folderName
        packs[0].version shouldBe 2
        val devicePackFolder = deviceDir.resolve(".content/$folderName")
        Files.exists(devicePackFolder) shouldBe true
        Files.exists(devicePackFolder.resolve("bt")) shouldBe true

        // Download back to the library
        val outDir = Files.createTempDirectory("fs-download")
        driver.downloadPack(uuid, outDir.toString())

        val downloaded = outDir.resolve(uuid)
        Files.readAllBytes(downloaded.resolve("ni")) shouldBe Files.readAllBytes(sourcePack.resolve("ni"))
        Files.readAllBytes(downloaded.resolve("nm")) shouldBe Files.readAllBytes(sourcePack.resolve("nm"))
        Files.readAllBytes(downloaded.resolve("rf/000/00000000")) shouldBe ByteArray(64) { it.toByte() }
        Files.readAllBytes(downloaded.resolve("sf/000/00000000")) shouldBe ByteArray(64) { (it + 100).toByte() }
        Files.exists(downloaded.resolve(".cleartext")) shouldBe true

        deviceDir.toFile().deleteRecursively()
        libraryDir.toFile().deleteRecursively()
        outDir.toFile().deleteRecursively()
    }

    "deletePack removes the pack from the index and the folder" {
        val deviceDir = Files.createTempDirectory("fs-device-delete")
        Files.write(deviceDir.resolve(".md"), buildMdV2())
        Files.write(deviceDir.resolve(".pi"), ByteArray(0))
        driver.partitionMountPoint = deviceDir

        val libraryDir = Files.createTempDirectory("fs-library-delete")
        val sourcePack = PackFixtures.buildFsPack(libraryDir)
        driver.uploadPack(uuid, sourcePack.toString())

        driver.deletePack(uuid) shouldBe true

        driver.getPacksList() shouldBe emptyList()
        Files.exists(deviceDir.resolve(".content/$folderName")) shouldBe false
        Files.readAllBytes(deviceDir.resolve(".pi")) shouldBe ByteArray(0)

        deviceDir.toFile().deleteRecursively()
        libraryDir.toFile().deleteRecursively()
    }

    "computePackFolderName uses the last 8 hex chars uppercased" {
        driver.computePackFolderName(uuid) shouldBe folderName
        driver.computePackFolderName("12345678-1234-1234-1234-123456789abc") shouldBe "56789ABC"
    }
})
