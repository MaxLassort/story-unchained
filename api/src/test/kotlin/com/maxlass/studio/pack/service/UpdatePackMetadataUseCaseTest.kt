package com.maxlass.studio.pack.service

import com.maxlass.studio.pack.domain.dto.UpdatePackMetadataCommand
import com.maxlass.studio.pack.domain.model.Pack
import com.maxlass.studio.pack.domain.model.PackFormat
import com.maxlass.studio.pack.domain.model.PackMetadata
import com.maxlass.studio.pack.domain.model.PackVariant
import com.maxlass.studio.pack.port.external.PackFileMetadata
import com.maxlass.studio.pack.port.external.UpdatePackFileMetadataPort
import com.maxlass.studio.pack.port.external.UpdateUnofficialMetadataPort
import com.maxlass.studio.pack.port.persistence.PackRepositoryPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.Base64

class UpdatePackMetadataUseCaseTest : StringSpec({

    beforeTest { clearAllMocks() }

    val packRepository = mockk<PackRepositoryPort>()
    val updateUnofficialMetadataPort = mockk<UpdateUnofficialMetadataPort>()
    val updatePackFileMetadataPort = mockk<UpdatePackFileMetadataPort>()

    fun useCase() = UpdatePackMetadataUseCase(
        packRepository = packRepository,
        updateUnofficialMetadataPort = updateUnofficialMetadataPort,
        updatePackFileMetadataPort = updatePackFileMetadataPort,
    )

    fun command(
        packId: String = "pack-1",
        title: String? = null,
        description: String? = null,
        linkedOfficialPackId: String? = null,
        locale: String? = null,
        ageMin: Int? = null,
        ageMax: Int? = null,
        durationMs: Int? = null,
        storyCount: Int? = null,
        thumbnailPngBytes: ByteArray? = null,
    ) = UpdatePackMetadataCommand(
        packId = packId,
        title = title,
        description = description,
        linkedOfficialPackId = linkedOfficialPackId,
        locale = locale,
        ageMin = ageMin,
        ageMax = ageMax,
        durationMs = durationMs,
        storyCount = storyCount,
        thumbnailPngBytes = thumbnailPngBytes,
    )

    fun pack(
        id: String = "pack-1",
        official: Boolean = false,
        linkedOfficialPackId: String? = null,
        variants: List<PackVariant> = emptyList(),
    ) = Pack(
        id = id,
        metadata = PackMetadata(
            title = "Original title",
            description = "Original description",
            thumbnail = "data:image/png;base64,AAA=",
            version = 1,
            factoryDisabled = false,
            nightModeAvailable = true,
            official = official,
            linkedOfficialPackId = linkedOfficialPackId,
        ),
        variants = variants,
    )

    "throws NoSuchElementException when the pack does not exist" {
        coEvery { packRepository.getAllPacks() } returns emptyList()

        shouldThrow<NoSuchElementException> {
            runBlocking { useCase().invoke(command(packId = "missing")) }
        }

        coVerify(exactly = 0) { packRepository.savePack(any()) }
        verify(exactly = 0) { updateUnofficialMetadataPort.updateUnofficialMetadata(any(), any(), any(), any()) }
    }

    "updates unofficial metadata and persists the pack" {
        coEvery { packRepository.getAllPacks() } returns listOf(pack())
        coEvery { packRepository.savePack(any()) } just runs
        every { updateUnofficialMetadataPort.updateUnofficialMetadata(any(), any(), any(), any()) } just runs

        runBlocking {
            useCase().invoke(
                command(
                    packId = "pack-1",
                    title = "New title",
                    description = "New description",
                    locale = "fr_FR",
                    ageMin = 3,
                    ageMax = 6,
                    durationMs = 5000,
                    storyCount = 5,
                )
            )
        }

        verify { updateUnofficialMetadataPort.updateUnofficialMetadata("pack-1", "New title", "New description", "data:image/png;base64,AAA=") }
        coVerify {
            packRepository.savePack(
                match { saved ->
                    val m = saved.metadata
                    m.title == "New title" && m.description == "New description" &&
                        m.locale == "fr_FR" && m.ageMin == 3 && m.ageMax == 6 &&
                        m.durationMs == 5000 && m.storyCount == 5 &&
                        m.thumbnail == "data:image/png;base64,AAA=" &&
                        m.official == false && m.linkedOfficialPackId == null
                }
            )
        }
        verify(exactly = 0) { updatePackFileMetadataPort.updateArchiveMetadata(any(), any()) }
    }

    "encodes thumbnailPngBytes as a data URI before persisting" {
        coEvery { packRepository.getAllPacks() } returns listOf(pack())
        coEvery { packRepository.savePack(any()) } just runs
        every { updateUnofficialMetadataPort.updateUnofficialMetadata(any(), any(), any(), any()) } just runs

        runBlocking { useCase().invoke(command(thumbnailPngBytes = byteArrayOf(0x01, 0x02))) }

        val expected = "data:image/png;base64,${Base64.getEncoder().encodeToString(byteArrayOf(0x01, 0x02))}"
        coVerify {
            packRepository.savePack(match { it.metadata.thumbnail == expected })
        }
    }

    "writes file metadata into each archive variant" {
        val archiveVariant = PackVariant(PackFormat.ARCHIVE, "/tmp/pack.zip")
        coEvery { packRepository.getAllPacks() } returns listOf(pack(variants = listOf(archiveVariant)))
        coEvery { packRepository.savePack(any()) } just runs
        every { updateUnofficialMetadataPort.updateUnofficialMetadata(any(), any(), any(), any()) } just runs
        every { updatePackFileMetadataPort.updateArchiveMetadata(any(), any()) } returns Path.of("/tmp/pack.zip")

        runBlocking { useCase().invoke(command(title = "New title", description = "New description")) }

        verify {
            updatePackFileMetadataPort.updateArchiveMetadata(
                Path.of("/tmp/pack.zip"),
                match<PackFileMetadata> { it.title == "New title" && it.description == "New description" },
            )
        }
    }

    "does not update file metadata when only linkedOfficialPackId is provided" {
        coEvery { packRepository.getAllPacks() } returns listOf(
            pack(variants = listOf(PackVariant(PackFormat.ARCHIVE, "/tmp/pack.zip"))),
            pack(id = "official-1", official = true),
        )
        coEvery { packRepository.savePack(any()) } just runs
        every { updateUnofficialMetadataPort.updateUnofficialMetadata(any(), any(), any(), any()) } just runs

        runBlocking { useCase().invoke(command(linkedOfficialPackId = "official-1")) }

        verify(exactly = 0) { updatePackFileMetadataPort.updateArchiveMetadata(any(), any()) }
    }

    "resolves the linked official pack id for a fork" {
        coEvery { packRepository.getAllPacks() } returns listOf(pack(id = "pack-1"), pack(id = "official-1", official = true))
        coEvery { packRepository.savePack(any()) } just runs
        every { updateUnofficialMetadataPort.updateUnofficialMetadata(any(), any(), any(), any()) } just runs

        runBlocking { useCase().invoke(command(packId = "pack-1", linkedOfficialPackId = "official-1")) }

        coVerify { packRepository.savePack(match { it.metadata.linkedOfficialPackId == "official-1" }) }
    }

    "throws IllegalArgumentException when the linked pack is not official" {
        coEvery { packRepository.getAllPacks() } returns listOf(pack(id = "pack-1"), pack(id = "other-2"))
        coEvery { packRepository.savePack(any()) } just runs
        every { updateUnofficialMetadataPort.updateUnofficialMetadata(any(), any(), any(), any()) } just runs

        shouldThrow<IllegalArgumentException> {
            runBlocking { useCase().invoke(command(packId = "pack-1", linkedOfficialPackId = "other-2")) }
        }
        coVerify(exactly = 0) { packRepository.savePack(any()) }
    }

    "throws IllegalArgumentException when the referenced official pack is not found" {
        coEvery { packRepository.getAllPacks() } returns listOf(pack(id = "pack-1"))
        coEvery { packRepository.savePack(any()) } just runs
        every { updateUnofficialMetadataPort.updateUnofficialMetadata(any(), any(), any(), any()) } just runs

        shouldThrow<IllegalArgumentException> {
            runBlocking { useCase().invoke(command(packId = "pack-1", linkedOfficialPackId = "missing-official")) }
        }
    }

    "keeps linkedOfficialPackId null when the pack is already official" {
        coEvery { packRepository.getAllPacks() } returns listOf(pack(official = true))
        coEvery { packRepository.savePack(any()) } just runs
        every { updateUnofficialMetadataPort.updateUnofficialMetadata(any(), any(), any(), any()) } just runs

        runBlocking { useCase().invoke(command(linkedOfficialPackId = "official-1")) }

        coVerify { packRepository.savePack(match { it.metadata.linkedOfficialPackId == null }) }
    }

    "uses the existing pack thumbnail when no new thumbnail is provided" {
        coEvery { packRepository.getAllPacks() } returns listOf(pack())
        coEvery { packRepository.savePack(any()) } just runs
        every { updateUnofficialMetadataPort.updateUnofficialMetadata(any(), any(), any(), any()) } just runs

        runBlocking { useCase().invoke(command(title = "New title")) }

        coVerify { packRepository.savePack(match { it.metadata.thumbnail == "data:image/png;base64,AAA=" }) }
    }
})
