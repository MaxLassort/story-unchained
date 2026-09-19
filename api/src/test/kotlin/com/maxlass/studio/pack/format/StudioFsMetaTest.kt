package com.maxlass.studio.pack.format

import com.maxlass.studio.pack.domain.model.PackMetadata
import com.maxlass.studio.pack.port.external.PackFileMetadata
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files

class StudioFsMetaTest : StringSpec({

    "write then read round-trips Unchained display metadata and PNG" {
        val dir = Files.createTempDirectory("studio-fs-meta-")
        try {
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            val metadata = PackMetadata(
                title = "Ma histoire",
                description = "Desc",
                version = 1,
                factoryDisabled = false,
                nightModeAvailable = true,
                locale = "fr_FR",
                ageMin = 3,
                ageMax = 6,
                storyCount = 2,
                unchained = true,
            )
            StudioFsMeta.write(dir, metadata, png)

            val payload = StudioFsMeta.read(dir)
            payload shouldNotBe null
            payload!!.unchained shouldBe true
            payload.title shouldBe "Ma histoire"
            payload.description shouldBe "Desc"
            payload.locale shouldBe "fr_FR"
            payload.ageMin shouldBe 3
            payload.ageMax shouldBe 6
            payload.storyCount shouldBe 2
            payload.thumbnail shouldBe StudioFsMeta.THUMBNAIL_FILENAME
            StudioFsMeta.readThumbnailBytes(dir) shouldBe png
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    "write refuses non-Unchained packs" {
        val dir = Files.createTempDirectory("studio-fs-meta-no-")
        try {
            val metadata = PackMetadata(
                title = "X",
                description = null,
                version = 1,
                factoryDisabled = false,
                nightModeAvailable = false,
                unchained = false,
            )
            val thrown = runCatching { StudioFsMeta.write(dir, metadata, null) }.exceptionOrNull()
            thrown shouldNotBe null
            Files.exists(dir.resolve(StudioFsMeta.META_FILENAME)) shouldBe false
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    "update patches fields without wiping existing thumbnail" {
        val dir = Files.createTempDirectory("studio-fs-meta-upd-")
        try {
            val png = byteArrayOf(1, 2, 3, 4)
            StudioFsMeta.write(
                dir,
                PackMetadata(
                    title = "Old",
                    description = "D",
                    version = 1,
                    factoryDisabled = false,
                    nightModeAvailable = false,
                    unchained = true,
                ),
                png,
            )
            StudioFsMeta.update(dir, PackFileMetadata(title = "New"), unchained = true)
            val payload = StudioFsMeta.read(dir)!!
            payload.title shouldBe "New"
            payload.description shouldBe "D"
            payload.unchained shouldBe true
            StudioFsMeta.readThumbnailBytes(dir) shouldBe png
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
})
