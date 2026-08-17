package com.maxlass.studio.pack.service

import com.maxlass.studio.infrastructure.config.StudioProperties
import com.maxlass.studio.pack.domain.dto.UnofficialJsonEntry
import com.maxlass.studio.pack.domain.dto.UpdatePackMetadataCommand
import com.maxlass.studio.pack.domain.model.Pack
import com.maxlass.studio.pack.domain.model.PackFormat
import com.maxlass.studio.pack.domain.model.PackMetadata
import com.maxlass.studio.pack.domain.model.PackVariant
import com.maxlass.studio.pack.port.external.LoadUnofficialMetadataFromFilePort
import com.maxlass.studio.settings.domain.Settings
import com.maxlass.studio.settings.service.SettingsService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import java.util.Base64

class SyncUnofficialMetadataUseCaseTest : StringSpec({

    val getAllPacks = mockk<GetAllPacksUseCase>()
    val loadUnofficialMetadata = mockk<LoadUnofficialMetadataFromFilePort>()
    val updatePackMetadata = mockk<UpdatePackMetadataUseCase>()
    val settingsService = mockk<SettingsService>()
    val studioProperties = StudioProperties()

    beforeTest {
        clearAllMocks()
        coEvery { settingsService.getSettings() } returns Settings(
            libraryPath = "/tmp/lib",
            unofficialDbPath = null,
            targetDeviceType = null,
        )
    }

    fun useCase() = SyncUnofficialMetadataUseCase(
        getAllPacks = getAllPacks,
        loadUnofficialMetadataFromFilePort = loadUnofficialMetadata,
        updatePackMetadata = updatePackMetadata,
        settingsService = settingsService,
        studioProperties = studioProperties,
    )

    fun pack(
        id: String = "pack-1",
        official: Boolean = false,
        locale: String? = "fr_FR",
        ageMin: Int? = 3,
        ageMax: Int? = 6,
        durationMs: Int? = 5000,
        storyCount: Int? = 5,
    ) = Pack(
        id = id,
        metadata = PackMetadata(
            title = "Original",
            description = "Original desc",
            thumbnail = null,
            version = 1,
            factoryDisabled = false,
            nightModeAvailable = true,
            official = official,
            linkedOfficialPackId = null,
            locale = locale,
            ageMin = ageMin,
            ageMax = ageMax,
            durationMs = durationMs,
            storyCount = storyCount,
        ),
        variants = listOf(PackVariant(PackFormat.ARCHIVE, "/tmp/pack.zip")),
    )

    fun pngDataUri(bytes: ByteArray): String =
        "data:image/png;base64,${Base64.getEncoder().encodeToString(bytes)}"

    "syncs thumbnail bytes and pack properties into the update command" {
        val png = byteArrayOf(0x01, 0x02, 0x03)
        every { loadUnofficialMetadata.loadFromPath(any()) } returns mapOf(
            "pack-1" to UnofficialJsonEntry(
                uuid = "pack-1",
                title = "Studio title",
                description = "Studio desc",
                image = pngDataUri(png),
            )
        )
        coEvery { getAllPacks.invoke() } returns listOf(pack())
        coEvery { updatePackMetadata.invoke(any()) } returns pack()

        runBlocking { useCase().invoke() }

        val command = slot<UpdatePackMetadataCommand>()
        coVerify { updatePackMetadata.invoke(capture(command)) }
        command.captured.title shouldBe "Studio title"
        command.captured.description shouldBe "Studio desc"
        command.captured.thumbnailPngBytes shouldBe png
        command.captured.locale shouldBe "fr_FR"
        command.captured.ageMin shouldBe 3
        command.captured.ageMax shouldBe 6
        command.captured.durationMs shouldBe 5000
        command.captured.storyCount shouldBe 5
    }

    "does not fail when the entry has no image" {
        every { loadUnofficialMetadata.loadFromPath(any()) } returns mapOf(
            "pack-1" to UnofficialJsonEntry(uuid = "pack-1", title = "T", description = "D", image = null)
        )
        coEvery { getAllPacks.invoke() } returns listOf(pack())
        coEvery { updatePackMetadata.invoke(any()) } returns pack()

        runBlocking { useCase().invoke() }

        val command = slot<UpdatePackMetadataCommand>()
        coVerify { updatePackMetadata.invoke(capture(command)) }
        command.captured.thumbnailPngBytes shouldBe null
    }

    "does not fail when the image is an invalid base64" {
        every { loadUnofficialMetadata.loadFromPath(any()) } returns mapOf(
            "pack-1" to UnofficialJsonEntry(
                uuid = "pack-1", title = "T", description = "D", image = "data:image/png;base64,@@@"
            )
        )
        coEvery { getAllPacks.invoke() } returns listOf(pack())
        coEvery { updatePackMetadata.invoke(any()) } returns pack()

        runBlocking { useCase().invoke() }

        val command = slot<UpdatePackMetadataCommand>()
        coVerify { updatePackMetadata.invoke(capture(command)) }
        command.captured.thumbnailPngBytes shouldBe null
    }

    "ignores official packs" {
        every { loadUnofficialMetadata.loadFromPath(any()) } returns mapOf(
            "official-1" to UnofficialJsonEntry(uuid = "official-1", title = "T")
        )
        coEvery { getAllPacks.invoke() } returns listOf(pack(id = "official-1", official = true))

        runBlocking { useCase().invoke() }

        coVerify(exactly = 0) { updatePackMetadata.invoke(any()) }
    }

    "ignores packs absent from the unofficial file" {
        every { loadUnofficialMetadata.loadFromPath(any()) } returns mapOf(
            "other-pack" to UnofficialJsonEntry(uuid = "other-pack", title = "T")
        )
        coEvery { getAllPacks.invoke() } returns listOf(pack(id = "pack-1"))

        runBlocking { useCase().invoke() }

        coVerify(exactly = 0) { updatePackMetadata.invoke(any()) }
    }

    "returns early when the unofficial file is empty or missing" {
        every { loadUnofficialMetadata.loadFromPath(any()) } returns emptyMap()
        coEvery { getAllPacks.invoke() } returns listOf(pack())

        runBlocking { useCase().invoke() }

        coVerify(exactly = 0) { updatePackMetadata.invoke(any()) }
    }

    "uses the configured unofficialDbPath when set" {
        coEvery { settingsService.getSettings() } returns Settings(
            libraryPath = "/tmp/lib",
            unofficialDbPath = "/custom/unofficial.json",
            targetDeviceType = null,
        )
        every { loadUnofficialMetadata.loadFromPath("/custom/unofficial.json") } returns emptyMap()
        coEvery { getAllPacks.invoke() } returns emptyList()

        runBlocking { useCase().invoke() }

        coVerify { loadUnofficialMetadata.loadFromPath("/custom/unofficial.json") }
    }

    "falls back to the default unofficial path when unset" {
        every { loadUnofficialMetadata.loadFromPath(studioProperties.defaultUnofficialJsonPath.toString()) } returns emptyMap()
        coEvery { getAllPacks.invoke() } returns emptyList()

        runBlocking { useCase().invoke() }

        coVerify { loadUnofficialMetadata.loadFromPath(studioProperties.defaultUnofficialJsonPath.toString()) }
    }
})