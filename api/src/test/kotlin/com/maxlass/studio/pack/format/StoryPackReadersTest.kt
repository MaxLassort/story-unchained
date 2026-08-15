package com.maxlass.studio.pack.format

import com.maxlass.studio.pack.format.reader.ArchiveStoryPackReader
import com.maxlass.studio.pack.format.reader.BinaryStoryPackReader
import com.maxlass.studio.pack.format.reader.FsStoryPackReader
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.UUID

class StoryPackReadersTest : StringSpec({

    val archiveReader = ArchiveStoryPackReader()
    val binaryReader = BinaryStoryPackReader()
    val fsReader = FsStoryPackReader()

    "archive: readMetadata extracts version/title/description/uuid" {
        val metadata = archiveReader.readMetadata(ByteArrayInputStream(PackFixtures.buildArchiveZip()))!!

        metadata.format shouldBe "archive"
        metadata.version shouldBe 1
        metadata.title shouldBe "Mon Pack"
        metadata.description shouldBe "Une description"
        metadata.uuid shouldBe PackFixtures.ARCHIVE_STAGE_1
        metadata.nightModeAvailable shouldBe true
    }

    "archive: read resolves nodes, square-one first, assets and transitions" {
        val pack = archiveReader.read(ByteArrayInputStream(PackFixtures.buildArchiveZip()))

        pack.uuid shouldBe PackFixtures.ARCHIVE_STAGE_1
        pack.version shouldBe 1
        pack.nightModeAvailable shouldBe true
        pack.enriched!!.title shouldBe "Mon Pack"
        pack.stageNodes!!.size shouldBe 2
        pack.stageNodes!!.first().uuid shouldBe PackFixtures.ARCHIVE_STAGE_1

        val stage1 = pack.stageNodes!![0]
        stage1.image.shouldNotBeNull()
        stage1.image!!.mimeType shouldBe "image/bmp"
        stage1.image!!.name shouldBe "img.bmp"
        stage1.image!!.rawData shouldBe PackFixtures.archiveBmp
        stage1.audio.shouldNotBeNull()
        stage1.audio!!.mimeType shouldBe "audio/mpeg"
        stage1.audio!!.rawData shouldBe PackFixtures.archiveMp3

        stage1.controlSettings!!.wheelEnabled shouldBe true
        stage1.controlSettings!!.okEnabled shouldBe true
        stage1.controlSettings!!.homeEnabled shouldBe false
        stage1.controlSettings!!.pauseEnabled shouldBe true
        stage1.controlSettings!!.autoJumpEnabled shouldBe false

        val okAction = stage1.okTransition!!.actionNode
        okAction.shouldNotBeNull()
        okAction.options!!.size shouldBe 1
        okAction.options!!.first().uuid shouldBe PackFixtures.ARCHIVE_STAGE_2
        stage1.okTransition!!.optionIndex shouldBe 0
    }

    "binary: readMetadata reads title/description/version/uuid" {
        val metadata = binaryReader.readMetadata(ByteArrayInputStream(PackFixtures.buildBinaryPack()))

        metadata.format shouldBe "raw"
        metadata.version shouldBe 2
        metadata.title shouldBe "Binary Title"
        metadata.description shouldBe "Binary Desc"
        metadata.uuid shouldBe PackFixtures.BINARY_UUID
    }

    "binary: read parses the header and stage records" {
        val pack = binaryReader.read(ByteArrayInputStream(PackFixtures.buildBinaryPack()))

        pack.uuid shouldBe PackFixtures.BINARY_UUID
        pack.version shouldBe 2
        pack.factoryDisabled shouldBe false
        pack.nightModeAvailable shouldBe false
        pack.stageNodes!!.size shouldBe 1
        pack.stageNodes!![0].uuid shouldBe PackFixtures.BINARY_UUID
        pack.stageNodes!![0].controlSettings!!.let {
            it.wheelEnabled shouldBe true
            it.okEnabled shouldBe true
            it.homeEnabled shouldBe true
            it.pauseEnabled shouldBe true
            it.autoJumpEnabled shouldBe false
        }
        pack.stageNodes!![0].image.shouldBeNull()
        pack.stageNodes!![0].audio.shouldBeNull()
    }

    "fs: readMetadata reads version/uuid and night mode flag" {
        val tempDir = Files.createTempDirectory("fs-reader-metadata")
        val packPath = PackFixtures.buildFsPack(tempDir)

        val metadata = fsReader.readMetadata(packPath)

        metadata.format shouldBe "fs"
        metadata.version shouldBe 2
        metadata.uuid shouldBe PackFixtures.FS_UUID
        metadata.nightModeAvailable shouldBe true

        tempDir.toFile().deleteRecursively()
    }

    "fs: read parses cleartext pack with image, audio and night mode" {
        val tempDir = Files.createTempDirectory("fs-reader")
        val packPath = PackFixtures.buildFsPack(tempDir, imageBytes = byteArrayOf(1, 2, 3, 4, 5))

        val pack = fsReader.read(packPath)

        pack.uuid shouldBe PackFixtures.FS_UUID
        pack.version shouldBe 2
        pack.nightModeAvailable shouldBe true
        pack.factoryDisabled shouldBe false
        pack.stageNodes!!.size shouldBe 1
        val stage = pack.stageNodes!![0]
        stage.uuid shouldBe PackFixtures.FS_UUID
        stage.image!!.mimeType shouldBe "image/bmp"
        stage.image!!.rawData shouldBe byteArrayOf(1, 2, 3, 4, 5)
        stage.image!!.name shouldBe "000\\00000000"
        stage.audio!!.mimeType shouldBe "audio/mpeg"
        stage.audio!!.rawData shouldBe byteArrayOf(9, 9, 9)
        stage.controlSettings!!.let {
            it.wheelEnabled shouldBe true
            it.okEnabled shouldBe true
            it.homeEnabled shouldBe true
            it.pauseEnabled shouldBe true
            it.autoJumpEnabled shouldBe true
        }

        tempDir.toFile().deleteRecursively()
    }

    "fs: read decrypts a non-cleartext pack (XXTEA round-trip)" {
        val tempDir = Files.createTempDirectory("fs-reader-ciphered")
        val image = ByteArray(64) { it.toByte() }
        val audio = ByteArray(64) { (it + 100).toByte() }
        val packPath = PackFixtures.buildFsPack(tempDir, cleartext = false, imageBytes = image, audioBytes = audio)
        PackFixtures.cipherFsPack(packPath)

        val pack = fsReader.read(packPath)

        pack.stageNodes!!.size shouldBe 1
        pack.stageNodes!![0].image!!.rawData shouldBe image
        pack.stageNodes!![0].audio!!.rawData shouldBe audio

        tempDir.toFile().deleteRecursively()
    }

    "fs: isCleartext fixes a missing marker when ri starts with 000 backslash" {
        val tempDir = Files.createTempDirectory("fs-reader-fix")
        val packPath = PackFixtures.buildFsPack(tempDir, cleartext = false)
        // Remove the cipher so ri starts with the cleartext "000\" prefix, like a broken pack.
        packPath.resolve("ri").toFile().writeBytes("000\\00000000".toByteArray(Charsets.UTF_8))

        fsReader.isCleartext(packPath, fixBrokenCleartext = true) shouldBe true
        packPath.resolve(".cleartext").toFile().exists() shouldBe true

        tempDir.toFile().deleteRecursively()
    }

    "binary: uuid construction matches java.util.UUID" {
        UUID(1L, 2L).toString() shouldBe PackFixtures.BINARY_UUID
    }
})
