package com.maxlass.studio.device.driver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.Random

class FsCipherTest : StringSpec({

    "common key cipher/decipher round-trips (XXTEA)" {
        val data = ByteArray(700) { Random().nextInt(256).toByte() }

        val ciphered = FsCipher.cipherFirstBlockCommonKey(data)
        ciphered.size shouldBe data.size
        FsCipher.decipherFirstBlockCommonKey(ciphered) shouldBe data
    }

    "common key cipher is lossy for sub-word files (legacy behavior)" {
        // Files smaller than 4 bytes get mangled by the XXTEA path (n=0 -> empty block).
        val small = byteArrayOf(1, 2)
        val ciphered = FsCipher.cipherFirstBlockCommonKey(small)
        FsCipher.decipherFirstBlockCommonKey(ciphered).size shouldBe small.size
    }

    "v3 AES-CBC cipher/decipher round-trips with the device key" {
        val keyV3 = FsDeviceKeyV3(
            aesKey = ByteArray(16) { (it + 1).toByte() },
            aesIv = ByteArray(16) { (it + 20).toByte() },
            bt = ByteArray(32) { (it + 40).toByte() },
        )
        val data = ByteArray(600) { Random().nextInt(256).toByte() }

        val ciphered = FsCipher.cipherFirstBlockSpecificKeyV3(data, keyV3)
        FsCipher.decipherFirstBlockSpecificKeyV3(ciphered, keyV3) shouldBe data
    }

    "v3 cipher expands the first block to a 16-byte multiple" {
        val keyV3 = FsDeviceKeyV3(ByteArray(16) { 1 }, ByteArray(16) { 2 }, ByteArray(32) { 3 })
        val data = ByteArray(10)

        val ciphered = FsCipher.cipherFirstBlockSpecificKeyV3(data, keyV3)

        ciphered.size shouldBe 16
    }

    "addBootFileV2 writes a bt file from the ri first block" {
        val tempDir = Files.createTempDirectory("fs-cipher-bt")
        val packFolder = tempDir.resolve("PACK")
        Files.createDirectories(packFolder)
        Files.write(packFolder.resolve("ri"), ByteArray(64) { (it * 3).toByte() })
        val deviceUuid = ByteArray(256) { (it * 5).toByte() }

        FsCipher.addBootFileV2(packFolder, deviceUuid)

        val bt = Files.readAllBytes(packFolder.resolve("bt"))
        bt.size shouldBe 64
        tempDir.toFile().deleteRecursively()
    }

    "addBootFileV3 writes the device bt bytes as-is" {
        val tempDir = Files.createTempDirectory("fs-cipher-bt3")
        val packFolder = tempDir.resolve("PACK")
        Files.createDirectories(packFolder)
        val keyV3 = FsDeviceKeyV3(ByteArray(16) { 1 }, ByteArray(16) { 2 }, ByteArray(32) { 7 })

        FsCipher.addBootFileV3(packFolder, keyV3)

        Files.readAllBytes(packFolder.resolve("bt")) shouldBe keyV3.bt
        tempDir.toFile().deleteRecursively()
    }
})
