package com.maxlass.studio.device.driver

import com.maxlass.studio.pack.format.utils.BytesUtils
import com.maxlass.studio.pack.format.utils.XxteaCipher
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * FS pack ciphering: XXTEA with the shared key (Lunii v2) and AES-CBC with the device-specific
 * key (Lunii v3), plus boot-file generation. Mirrors `studio.driver.fs.CipherUtils` +
 * `AESCBCCipher`.
 */
object FsCipher {

    private const val BOOT_FILENAME = "bt"

    private val CLEAR_FILES = setOf("ni", "nm", ".cleartext")
    private val NO_COPY_FILES = setOf(".cleartext")

    fun shouldBeCopied(path: Path): Boolean = path.fileName.toString() !in NO_COPY_FILES

    fun shouldBeCiphered(path: Path): Boolean = path.fileName.toString() !in CLEAR_FILES

    fun addBootFileV2(packFolder: Path, deviceUuid: ByteArray) {
        val specificKey = computeSpecificKeyV2FromUuid(deviceUuid)
        val riCipheredBlock = Files.newInputStream(File(packFolder.toFile(), "ri").toPath()).use { it.readNBytes(64) }
        val btCiphered = cipherFirstBlockSpecificKeyV2(riCipheredBlock, specificKey)
        Files.write(packFolder.resolve(BOOT_FILENAME), btCiphered)
    }

    fun addBootFileV3(packFolder: Path, deviceKeyV3: FsDeviceKeyV3) {
        Files.write(packFolder.resolve(BOOT_FILENAME), deviceKeyV3.bt)
    }

    fun cipherFirstBlockCommonKey(data: ByteArray): ByteArray =
        cipherFirstBlock(data, XxteaCipher.COMMON_KEY, blockSize = 512, maxWords = 128)

    fun decipherFirstBlockCommonKey(data: ByteArray): ByteArray =
        decipherFirstBlock(data, XxteaCipher.COMMON_KEY, blockSize = 512, maxWords = 128)

    private fun cipherFirstBlockSpecificKeyV2(data: ByteArray, specificKey: ByteArray): ByteArray =
        cipherFirstBlock(data, specificKey, blockSize = 64, maxWords = 16)

    fun cipherFirstBlockSpecificKeyV3(data: ByteArray, deviceKeyV3: FsDeviceKeyV3): ByteArray {
        val block = data.copyOfRange(0, minOf(512, data.size))
        val encryptedBlock = aesCbc(block, deviceKeyV3, Cipher.ENCRYPT_MODE)
        val outputLength = encryptedBlock.size + maxOf(0, data.size - 512)
        val bb = ByteBuffer.allocate(outputLength)
        bb.put(encryptedBlock)
        if (data.size > 512) {
            bb.put(data.copyOfRange(512, data.size))
        }
        return bb.array()
    }

    fun decipherFirstBlockSpecificKeyV3(data: ByteArray, deviceKeyV3: FsDeviceKeyV3): ByteArray {
        val block = data.copyOfRange(0, minOf(512, data.size))
        val decryptedBlock = aesCbc(block, deviceKeyV3, Cipher.DECRYPT_MODE)
        val bb = ByteBuffer.allocate(data.size)
        bb.put(decryptedBlock)
        if (data.size > 512) {
            bb.put(data.copyOfRange(512, data.size))
        }
        return bb.array()
    }

    private fun cipherFirstBlock(data: ByteArray, key: ByteArray, blockSize: Int, maxWords: Int): ByteArray {
        val block = data.copyOfRange(0, minOf(blockSize, data.size))
        val dataInt = BytesUtils.toIntArray(block, ByteOrder.LITTLE_ENDIAN)
        val encryptedInt = XxteaCipher.btea(
            dataInt,
            minOf(maxWords, data.size / 4),
            BytesUtils.toIntArray(key, ByteOrder.BIG_ENDIAN),
        )
        val encryptedBlock = BytesUtils.toByteArray(encryptedInt, ByteOrder.LITTLE_ENDIAN)
        val bb = ByteBuffer.allocate(data.size)
        bb.put(encryptedBlock)
        if (data.size > blockSize) {
            bb.put(data.copyOfRange(blockSize, data.size))
        }
        return bb.array()
    }

    private fun decipherFirstBlock(data: ByteArray, key: ByteArray, blockSize: Int, maxWords: Int): ByteArray {
        val block = data.copyOfRange(0, minOf(blockSize, data.size))
        val dataInt = BytesUtils.toIntArray(block, ByteOrder.LITTLE_ENDIAN)
        val decryptedInt = XxteaCipher.btea(
            dataInt,
            -minOf(maxWords, data.size / 4),
            BytesUtils.toIntArray(key, ByteOrder.BIG_ENDIAN),
        )
        val decryptedBlock = BytesUtils.toByteArray(decryptedInt, ByteOrder.LITTLE_ENDIAN)
        val bb = ByteBuffer.allocate(data.size)
        bb.put(decryptedBlock)
        if (data.size > blockSize) {
            bb.put(data.copyOfRange(blockSize, data.size))
        }
        return bb.array()
    }

    private fun computeSpecificKeyV2FromUuid(uuid: ByteArray): ByteArray {
        val btKey = decipherFirstBlockCommonKey(uuid)
        return byteArrayOf(
            btKey[11], btKey[10], btKey[9], btKey[8],
            btKey[15], btKey[14], btKey[13], btKey[12],
            btKey[3], btKey[2], btKey[1], btKey[0],
            btKey[7], btKey[6], btKey[5], btKey[4],
        )
    }

    private fun aesCbc(bytes: ByteArray, keyV3: FsDeviceKeyV3, mode: Int): ByteArray {
        var data = bytes
        val incomplete = data.size % 16
        if (incomplete > 0) {
            data = data.copyOf(data.size + (16 - incomplete))
        }
        val iv = IvParameterSpec(BytesUtils.reverseEndianness(keyV3.aesIv))
        val key = SecretKeySpec(BytesUtils.reverseEndianness(keyV3.aesKey), "AES")
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(mode, key, iv)
        return cipher.doFinal(data)
    }
}
