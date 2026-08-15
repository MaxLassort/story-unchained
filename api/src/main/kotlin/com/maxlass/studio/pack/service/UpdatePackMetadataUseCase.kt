package com.maxlass.studio.pack.service

import com.maxlass.studio.pack.domain.dto.UpdatePackMetadataCommand
import com.maxlass.studio.pack.domain.model.Pack
import com.maxlass.studio.pack.domain.model.PackFormat
import com.maxlass.studio.pack.domain.model.PackMetadata
import com.maxlass.studio.pack.port.external.PackFileMetadata
import com.maxlass.studio.pack.port.external.UpdatePackFileMetadataPort
import com.maxlass.studio.pack.port.external.UpdateUnofficialMetadataPort
import com.maxlass.studio.pack.port.persistence.PackRepositoryPort
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.util.Base64

/**
 * Use case: updates pack metadata (title, description, linkedOfficialPackId) for the given pack.
 */
@Service
class UpdatePackMetadataUseCase(
    private val packRepository: PackRepositoryPort,
    private val updateUnofficialMetadataPort: UpdateUnofficialMetadataPort,
    private val updatePackFileMetadataPort: UpdatePackFileMetadataPort
) {

    suspend fun invoke(command: UpdatePackMetadataCommand): Pack {
        val packs = packRepository.getAllPacks()
        val pack = packs.find { it.id == command.packId }
            ?: throw NoSuchElementException("Pack not found: ${command.packId}")

        updateUnofficialMetadataPort.updateUnofficialMetadata(
            command.packId,
            command.title,
            command.description,
            pack.metadata.thumbnail
        )

        val newLinkedId = resolveLinkedOfficialPackId(pack, command.linkedOfficialPackId, packs)

        val thumbnail = command.thumbnailPngBytes?.let {
            "data:image/png;base64,${Base64.getEncoder().encodeToString(it)}"
        } ?: pack.metadata.thumbnail

        val updatedMetadata = PackMetadata(
            title = command.title ?: pack.metadata.title,
            description = command.description ?: pack.metadata.description,
            thumbnail = thumbnail,
            version = pack.metadata.version,
            factoryDisabled = pack.metadata.factoryDisabled,
            nightModeAvailable = pack.metadata.nightModeAvailable,
            official = pack.metadata.official,
            linkedOfficialPackId = newLinkedId,
            locale = command.locale ?: pack.metadata.locale,
            ageMin = command.ageMin ?: pack.metadata.ageMin,
            ageMax = command.ageMax ?: pack.metadata.ageMax,
            durationMs = command.durationMs ?: pack.metadata.durationMs,
            storyCount = command.storyCount ?: pack.metadata.storyCount,
        )
        val updatedPack = pack.copy(metadata = updatedMetadata)
        packRepository.savePack(updatedPack)

        val fileMetadata = PackFileMetadata(
            title = command.title,
            description = command.description,
            locale = command.locale,
            ageMin = command.ageMin,
            ageMax = command.ageMax,
            durationMs = command.durationMs,
            storyCount = command.storyCount,
            thumbnailPngBytes = command.thumbnailPngBytes,
        )
        if (fileMetadata.title != null || fileMetadata.description != null ||
            fileMetadata.locale != null || fileMetadata.ageMin != null || fileMetadata.ageMax != null ||
            fileMetadata.durationMs != null || fileMetadata.storyCount != null || fileMetadata.thumbnailPngBytes != null
        ) {
            val archiveVariants = pack.variants.filter { it.format == PackFormat.ARCHIVE }
            for (variant in archiveVariants) {
                updatePackFileMetadataPort.updateArchiveMetadata(Path.of(variant.storagePath), fileMetadata)
            }
        }

        return updatedPack
    }

    private fun resolveLinkedOfficialPackId(
        pack: Pack,
        linkedOfficialPackId: String?,
        packs: List<Pack>
    ): String? = when {
        pack.metadata.official -> null
        linkedOfficialPackId == null -> null
        else -> {
            val linkedPack = packs.find { it.id == linkedOfficialPackId }
                ?: throw IllegalArgumentException("Referenced pack not found: $linkedOfficialPackId")
            require(linkedPack.metadata.official) {
                "Referenced pack must be official: $linkedOfficialPackId"
            }
            linkedOfficialPackId
        }
    }
}
