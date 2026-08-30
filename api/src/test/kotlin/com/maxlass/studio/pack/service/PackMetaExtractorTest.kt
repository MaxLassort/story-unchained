package com.maxlass.studio.pack.service

import com.maxlass.studio.pack.cache.ThumbnailCache
import com.maxlass.studio.pack.domain.dto.OfficialMetadataDto
import com.maxlass.studio.pack.domain.dto.RawPackMeta
import com.maxlass.studio.pack.domain.model.PackFormat
import com.maxlass.studio.pack.port.external.ExtractThumbnailFromFsPackPort
import com.maxlass.studio.pack.port.external.MetaDataReaderPort
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Files
import java.util.Base64

class PackMetaExtractorTest : StringSpec({

    val metadataReader = mockk<MetaDataReaderPort>()
    val extractThumbnail = mockk<ExtractThumbnailFromFsPackPort>()
    val cache = ThumbnailCache()

    fun extractor() = PackMetaExtractor(
        metadataReader = metadataReader,
        extractThumbnailFromFsPack = extractThumbnail,
        thumbnailCache = cache,
    )

    fun fsMeta(uuid: String = "pack-1") = RawPackMeta(
        uuid = uuid,
        title = null,
        description = null,
        version = 1,
        isNightModeAvailable = false,
    )

    fun pngDataUri(bytes: ByteArray): String =
        "data:image/png;base64,${Base64.getEncoder().encodeToString(bytes)}"

    beforeTest {
        clearAllMocks()
    }

    "keeps the existing archive cover for the FS variant of a 2-format pack" {
        val existingCover = pngDataUri(byteArrayOf(0x0A, 0x0B))
        val pack = extractor().buildPack(
            file = Files.createTempDirectory("pack").toFile(),
            meta = fsMeta(),
            format = PackFormat.FS,
            officialCache = emptyMap(),
            existingThumbnail = existingCover,
            hasArchiveVariant = true,
        )
        pack.metadata.thumbnail shouldBe existingCover
        verify(exactly = 0) { extractThumbnail.extractThumbnail(any()) }
    }

    "reuses the cached cover for a 2-format pack instead of the FS default" {
        val coverBytes = byteArrayOf(0x01, 0x02, 0x03)
        cache.put("pack-1", coverBytes)
        val pack = extractor().buildPack(
            file = Files.createTempDirectory("pack").toFile(),
            meta = fsMeta(),
            format = PackFormat.FS,
            officialCache = emptyMap(),
            existingThumbnail = null,
            hasArchiveVariant = true,
        )
        pack.metadata.thumbnail shouldBe pngDataUri(coverBytes)
        verify(exactly = 0) { extractThumbnail.extractThumbnail(any()) }
    }

    "still extracts the FS image when the pack has no archive variant" {
        every { extractThumbnail.extractThumbnail(any()) } returns byteArrayOf(0x10, 0x20)
        val pack = extractor().buildPack(
            file = Files.createTempDirectory("pack").toFile(),
            meta = fsMeta(),
            format = PackFormat.FS,
            officialCache = emptyMap(),
            existingThumbnail = null,
            hasArchiveVariant = false,
        )
        pack.metadata.thumbnail shouldNotBe null
        verify(exactly = 1) { extractThumbnail.extractThumbnail(any()) }
    }

    "uses the official thumbnail URL when the pack is official" {
        val official = OfficialMetadataDto(
            title = "T", description = "D", thumbnailUrl = "https://official/cover.png",
            locale = null, ageMin = null, ageMax = null, durationMs = null, storyCount = null,
        )
        val pack = extractor().buildPack(
            file = Files.createTempDirectory("pack").toFile(),
            meta = fsMeta(uuid = "official-1"),
            format = PackFormat.FS,
            officialCache = mapOf("official-1" to official),
        )
        pack.metadata.thumbnail shouldBe "https://official/cover.png"
        verify(exactly = 0) { extractThumbnail.extractThumbnail(any()) }
    }
})