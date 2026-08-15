package com.maxlass.studio.pack.format

import com.maxlass.studio.pack.format.utils.BytesUtils
import com.maxlass.studio.pack.format.utils.XxteaCipher
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builders for minimal story-pack fixtures (archive/raw/fs) used by the reader tests.
 * The generated fixtures are readable by both the pure-Kotlin readers and the legacy studio-core readers.
 */
object PackFixtures {

    const val ARCHIVE_STAGE_1 = "stage-1"
    const val ARCHIVE_STAGE_2 = "stage-2"
    const val ARCHIVE_ACTION_1 = "action-1"
    const val BINARY_UUID = "00000000-0000-0001-0000-000000000002"
    const val FS_FOLDER_NAME = "1234ABCD.test"
    const val FS_UUID = "1234ABCD"

    val archiveBmp = byteArrayOf(1, 2, 3, 4)
    val archiveMp3 = byteArrayOf(10, 11, 12)

    val storyJson: String = """
        {
          "version": 1,
          "title": "Mon Pack",
          "description": "Une description",
          "nightModeAvailable": true,
          "actionNodes": [
            { "id": "$ARCHIVE_ACTION_1", "options": ["$ARCHIVE_STAGE_2"] }
          ],
          "stageNodes": [
            {
              "uuid": "$ARCHIVE_STAGE_1",
              "squareOne": true,
              "image": "img.bmp",
              "audio": "a.mp3",
              "okTransition": { "actionNode": "$ARCHIVE_ACTION_1", "optionIndex": 0 },
              "controlSettings": { "wheel": true, "ok": true, "home": false, "pause": true, "autoplay": false }
            },
            {
              "uuid": "$ARCHIVE_STAGE_2",
              "controlSettings": { "wheel": true, "ok": true, "home": true, "pause": true, "autoplay": false }
            }
          ]
        }
    """.trimIndent()

    fun buildArchiveZip(): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("story.json"))
            zos.write(storyJson.toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("assets/img.bmp"))
            zos.write(archiveBmp)
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("assets/a.mp3"))
            zos.write(archiveMp3)
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    fun buildBinaryPack(
        title: String? = "Binary Title",
        description: String? = "Binary Desc",
        version: Short = 2,
    ): ByteArray {
        val bb = ByteBuffer.allocate(512 * 2).order(ByteOrder.BIG_ENDIAN)
        // Header sector
        bb.putShort(1) // stages count
        bb.put(0) // factoryDisabled
        bb.putShort(version)
        bb.put(ByteArray(59))
        bb.putUtf16(title, 64)
        bb.putUtf16(description, 128)
        bb.put(ByteArray(64))
        // Single stage sector, no assets, no transitions
        bb.putLong(1) // uuidLow
        bb.putLong(2) // uuidHigh
        bb.putInt(-1) // imageOffset
        bb.putInt(0) // imageSize
        bb.putInt(-1) // audioOffset
        bb.putInt(0) // audioSize
        bb.putShort(-1) // okTransitionOffset
        bb.putShort(0) // okTransitionCount
        bb.putShort(0) // okTransitionIndex
        bb.putShort(-1) // homeTransitionOffset
        bb.putShort(0) // homeTransitionCount
        bb.putShort(0) // homeTransitionIndex
        bb.putShort(1) // wheel
        bb.putShort(1) // ok
        bb.putShort(1) // home
        bb.putShort(1) // pause
        bb.putShort(0) // autoplay
        bb.put(ByteArray(58))
        bb.putUtf16(null, 64) // enriched name (128 bytes)
        bb.putLong(0) // enriched groupId
        bb.putLong(0)
        bb.put(0) // enriched type
        bb.putShort(0) // enriched position
        bb.putShort(0)
        bb.put(ByteArray(251))
        return bb.array()
    }

    fun buildFsPack(
        root: Path,
        nightMode: Boolean = true,
        cleartext: Boolean = true,
        imageBytes: ByteArray = byteArrayOf(1, 2, 3, 4, 5),
        audioBytes: ByteArray = byteArrayOf(9, 9, 9),
    ): Path {
        val packFolder = root.resolve(FS_FOLDER_NAME).toFile()
        packFolder.mkdirs()
        if (nightMode) File(packFolder, "nm").createNewFile()
        if (cleartext) File(packFolder, ".cleartext").createNewFile()

        val niBb = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        niBb.putShort(1) // nodesList start
        niBb.putShort(2) // version
        niBb.putInt(512) // nodesList
        niBb.putInt(44) // nodeSize
        niBb.putInt(1) // stageNodesCount
        niBb.putInt(1) // imageAssetsCount
        niBb.putInt(1) // soundAssetsCount
        niBb.put(0) // factoryDisabled
        niBb.put(ByteArray(487))

        val rec = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        rec.putInt(0) // imageAssetIndexInRI
        rec.putInt(0) // soundAssetIndexInSI
        rec.putInt(-1) // okTransitionActionNodeIndexInLI
        rec.putInt(-1) // okTransitionNumberOfOptions
        rec.putInt(-1) // okTransitionSelectedOptionIndex
        rec.putInt(-1) // homeTransitionActionNodeIndexInLI
        rec.putInt(-1) // homeTransitionNumberOfOptions
        rec.putInt(-1) // homeTransitionSelectedOptionIndex
        rec.putShort(1) // wheel
        rec.putShort(1) // ok
        rec.putShort(1) // home
        rec.putShort(1) // pause
        rec.putShort(1) // autoplay
        rec.putShort(0) // pad

        FileOutputStream(File(packFolder, "ni")).use {
            it.write(niBb.array())
            it.write(rec.array())
        }

        val assetPath = "000\\00000000".toByteArray(Charsets.UTF_8)
        File(packFolder, "ri").writeBytes(assetPath)
        File(packFolder, "si").writeBytes(assetPath)
        File(packFolder, "li").writeBytes(ByteArray(0))

        val rfFile = File(packFolder, "rf/000/00000000")
        rfFile.parentFile.mkdirs()
        rfFile.writeBytes(imageBytes)
        val sfFile = File(packFolder, "sf/000/00000000")
        sfFile.parentFile.mkdirs()
        sfFile.writeBytes(audioBytes)

        return packFolder.toPath()
    }

    /** Ciphers every index/asset file (except ni) to simulate a non-cleartext FS pack. */
    fun cipherFsPack(packFolder: Path) {
        for (name in listOf("ri", "si", "li")) {
            val file = packFolder.resolve(name).toFile()
            file.writeBytes(cipherFirstBlock(file.readBytes()))
        }
        for (rel in listOf("rf/000/00000000", "sf/000/00000000")) {
            val file = packFolder.resolve(rel).toFile()
            file.writeBytes(cipherFirstBlock(file.readBytes()))
        }
    }

    fun cipherFirstBlock(data: ByteArray): ByteArray {
        val block = data.copyOfRange(0, minOf(512, data.size))
        val dataInt = BytesUtils.toIntArray(block, ByteOrder.LITTLE_ENDIAN)
        val encryptedInt = XxteaCipher.btea(
            dataInt,
            minOf(128, data.size / 4),
            BytesUtils.toIntArray(XxteaCipher.COMMON_KEY, ByteOrder.BIG_ENDIAN),
        )
        val encryptedBlock = BytesUtils.toByteArray(encryptedInt, ByteOrder.LITTLE_ENDIAN)
        val bb = ByteBuffer.allocate(data.size)
        bb.put(encryptedBlock)
        if (data.size > 512) {
            bb.put(data.copyOfRange(512, data.size))
        }
        return bb.array()
    }

    private fun ByteBuffer.putUtf16(s: String?, maxChars: Int) {
        val chars = (s ?: "").take(maxChars)
        for (c in chars) {
            putChar(c)
        }
        repeat(maxChars - chars.length) { putChar('\u0000') }
    }
}
