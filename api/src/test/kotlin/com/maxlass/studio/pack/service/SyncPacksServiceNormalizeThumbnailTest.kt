package com.maxlass.studio.pack.service

import com.maxlass.studio.infrastructure.config.StudioProperties
import com.maxlass.studio.infrastructure.persistence.PackJpaRepository
import com.maxlass.studio.infrastructure.persistence.PackMetadataEntity
import com.maxlass.studio.infrastructure.persistence.PackMetadataJpaRepository
import com.maxlass.studio.infrastructure.persistence.PackScanIndexJpaRepository
import com.maxlass.studio.infrastructure.persistence.PackVariantJpaRepository
import com.maxlass.studio.pack.cache.ThumbnailCache
import com.maxlass.studio.pack.domain.dto.RawPackMeta
import com.maxlass.studio.pack.domain.dto.SyncStatus
import com.maxlass.studio.pack.domain.dto.SyncStatusEvent
import com.maxlass.studio.pack.domain.model.Pack
import com.maxlass.studio.pack.domain.model.PackFormat
import com.maxlass.studio.pack.domain.model.PackMetadata
import com.maxlass.studio.pack.domain.model.PackVariant
import com.maxlass.studio.pack.port.external.ExtractThumbnailFromFsPackPort
import com.maxlass.studio.pack.port.external.MetaDataReaderPort
import com.maxlass.studio.pack.port.external.MetadataRefreshPort
import com.maxlass.studio.pack.port.external.SyncEventPublisher
import com.maxlass.studio.pack.port.persistence.PackRepositoryPort
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.springframework.transaction.PlatformTransactionManager
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.Optional
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The sync stores one metadata across all the variants of a pack. When a pack exists in several
 * formats, a later-processed variant (typically an FS folder yielding null fields) can otherwise
 * clobber the metadata read from the ARCHIVE zip. The post-sync normalization re-asserts the zip's
 * full metadata (title, description, locale, ages, duration, story count, thumbnail cover) as
 * authoritative.
 */
class SyncPacksServiceNormalizeThumbnailTest : StringSpec({

    val packRepository = mockk<PackRepositoryPort>()
    val metadataReader = mockk<MetaDataReaderPort>()
    val metadataRefresh = mockk<MetadataRefreshPort>()
    val extractThumbnail = mockk<ExtractThumbnailFromFsPackPort>()
    val thumbnailCache = ThumbnailCache()
    val packJpaRepository = mockk<PackJpaRepository>()
    val packMetadataJpaRepository = mockk<PackMetadataJpaRepository>()
    val packScanIndexRepository = mockk<PackScanIndexJpaRepository>()
    val variantRepository = mockk<PackVariantJpaRepository>()

    fun service(packMetadata: PackMetadataJpaRepository = packMetadataJpaRepository) = SyncPacksService(
        packRepository = packRepository,
        metadataReader = metadataReader,
        metadataRefresh = metadataRefresh,
        extractThumbnailFromFsPack = extractThumbnail,
        thumbnailCache = thumbnailCache,
        packJpaRepository = packJpaRepository,
        packMetadataJpaRepository = packMetadata,
        packScanIndexRepository = packScanIndexRepository,
        variantRepository = variantRepository,
        fingerprinter = mockk<PackFingerprinter>(relaxed = true),
        transactionManager = mockk<PlatformTransactionManager>(relaxed = true),
        studioProperties = StudioProperties(),
        syncUnofficialMetadata = mockk<SyncUnofficialMetadataUseCase>(relaxed = true),
        eventPublisher = mockk<SyncEventPublisher>(relaxed = true),
    )

    fun archiveZip(cover: ByteArray): java.nio.file.Path {
        val path = Files.createTempFile("pack-", ".zip")
        ZipOutputStream(Files.newOutputStream(path)).use { zos ->
            zos.putNextEntry(ZipEntry("story.json"))
            zos.write(
                """
                {
                  "version": 1,
                  "title": "Titre du zip",
                  "description": "Description du zip",
                  "locale": "fr_FR",
                  "ageMin": 4,
                  "ageMax": 8,
                  "duration": 600000,
                  "storyCount": 7,
                  "stageNodes": [{"uuid": "pack-1", "squareOne": true,
                    "controlSettings": { "wheel": true, "ok": true, "home": true, "pause": true, "autoplay": false } }]
                }
                """.trimIndent().toByteArray()
            )
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("meta/thumbnail.png"))
            zos.write(cover)
            zos.closeEntry()
        }
        return path
    }

    fun dataUri(bytes: ByteArray) =
        "data:image/png;base64,${Base64.getEncoder().encodeToString(bytes)}"

    fun entity(packId: String = "pack-1") = PackMetadataEntity(
        packId = packId, title = null, version = 0, factoryDisabled = false,
        nightModeAvailable = false, official = false,
    )

    beforeTest {
        clearAllMocks()
    }

    "restores the full archive metadata over FS-null / default values for a 2-format pack" {
        val cover = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val zipPath = archiveZip(cover)
        val pack = Pack(
            id = "pack-1",
            metadata = PackMetadata(
                title = "Titre FS", description = "Desc FS", thumbnail = "data:image/png;base64,UE5H",
                version = 1, factoryDisabled = false, nightModeAvailable = false,
                official = false, linkedOfficialPackId = null,
                locale = "en", ageMin = 1, ageMax = 2, durationMs = 100, storyCount = 3,
            ),
            variants = listOf(
                PackVariant(PackFormat.ARCHIVE, zipPath.toString()),
                PackVariant(PackFormat.FS, "/tmp/pack"),
            ),
        )
        // The archive reader returns the zip's metadata; the thumbnail cover is read independently.
        every {
            metadataReader.readArchiveMetadata(any())
        } returns com.maxlass.studio.pack.domain.dto.RawPackMeta(
            uuid = "pack-1", title = "Titre du zip", description = "Description du zip", version = 3,
            isNightModeAvailable = true, locale = "fr_FR", ageMin = 4, ageMax = 8,
            durationMs = 600000, storyCount = 7,
        )
        coEvery { packRepository.getAllPacks() } returns listOf(pack)
        val meta = entity()
        every { packMetadataJpaRepository.findById("pack-1") } returns Optional.of(meta)
        every { packMetadataJpaRepository.save(any()) } answers { firstArg() }

        runBlocking { service().normalizeArchiveMetadata() }

        meta.title shouldBe "Titre du zip"
        meta.description shouldBe "Description du zip"
        meta.locale shouldBe "fr_FR"
        meta.ageMin shouldBe 4
        meta.ageMax shouldBe 8
        meta.durationMs shouldBe 600000
        meta.storyCount shouldBe 7
        meta.version.toInt() shouldBe 3
        meta.nightModeAvailable shouldBe true
        meta.thumbnail shouldBe dataUri(cover)
        verify { packMetadataJpaRepository.save(meta) }
    }

    "leaves metadata untouched when it already matches the archive" {
        val cover = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val zipPath = archiveZip(cover)
        every {
            metadataReader.readArchiveMetadata(any())
        } returns com.maxlass.studio.pack.domain.dto.RawPackMeta(
            uuid = "pack-1", title = "Titre du zip", description = "Description du zip", version = 1,
            isNightModeAvailable = false, locale = "fr_FR", ageMin = 4, ageMax = 8,
            durationMs = 600000, storyCount = 7,
        )
        val pack = Pack(
            id = "pack-1",
            metadata = PackMetadata(
                title = "Titre du zip", description = "Description du zip", thumbnail = dataUri(cover),
                version = 1, factoryDisabled = false, nightModeAvailable = false,
                official = false, linkedOfficialPackId = null,
                locale = "fr_FR", ageMin = 4, ageMax = 8, durationMs = 600000, storyCount = 7,
            ),
            variants = listOf(PackVariant(PackFormat.ARCHIVE, zipPath.toString())),
        )
        coEvery { packRepository.getAllPacks() } returns listOf(pack)
        val meta = entity().apply {
            title = "Titre du zip"; description = "Description du zip"; thumbnail = dataUri(cover)
            version = 1; locale = "fr_FR"; ageMin = 4; ageMax = 8; durationMs = 600000; storyCount = 7
        }
        every { packMetadataJpaRepository.findById("pack-1") } returns Optional.of(meta)
        every { packMetadataJpaRepository.save(any()) } answers { firstArg() }

        runBlocking { service().normalizeArchiveMetadata() }

        verify(exactly = 0) { packMetadataJpaRepository.save(any()) }
    }

    "does not override an official pack" {
        val zipPath = archiveZip(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        every {
            metadataReader.readArchiveMetadata(any())
        } returns com.maxlass.studio.pack.domain.dto.RawPackMeta(
            uuid = "official-1", title = "Zip title", description = null, version = 1,
            isNightModeAvailable = false,
        )
        val pack = Pack(
            id = "official-1",
            metadata = PackMetadata(
                title = "Official", description = null, thumbnail = "https://official/cover.png",
                version = 1, factoryDisabled = false, nightModeAvailable = false,
                official = true, linkedOfficialPackId = null,
                locale = null, ageMin = null, ageMax = null, durationMs = null, storyCount = null,
            ),
            variants = listOf(PackVariant(PackFormat.ARCHIVE, zipPath.toString())),
        )
        coEvery { packRepository.getAllPacks() } returns listOf(pack)

        runBlocking { service().normalizeArchiveMetadata() }

        verify(exactly = 0) { packMetadataJpaRepository.save(any()) }
    }

    // TODO: reintroduce a compilable failing TDD test for SyncPacksService.startSync()/runJob()
    // after the test harness / fingerprinter answers are aligned with this project's setup.
})
