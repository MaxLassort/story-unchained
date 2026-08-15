package com.maxlass.studio.pack.service

import com.maxlass.studio.infrastructure.config.StudioProperties
import com.maxlass.studio.pack.domain.dto.UpdatePackMetadataCommand
import com.maxlass.studio.pack.port.external.LoadUnofficialMetadataFromFilePort
import com.maxlass.studio.settings.service.SettingsService
import org.springframework.stereotype.Service

/**
 * Use case: loads unofficial pack metadata from the configured unofficial JSON path
 * (settings.unofficialDbPath or default ~/.studio/db/unofficial.json) and updates the app database for each matching non-official pack.
 */
@Service
class SyncUnofficialMetadataUseCase(
    private val getAllPacks: GetAllPacksUseCase,
    private val loadUnofficialMetadataFromFilePort: LoadUnofficialMetadataFromFilePort,
    private val updatePackMetadata: UpdatePackMetadataUseCase,
    private val settingsService: SettingsService,
    private val studioProperties: StudioProperties,
) {
    suspend fun invoke() {
        val path = settingsService.getSettings().unofficialDbPath?.takeIf { it.isNotBlank() }
            ?: studioProperties.defaultUnofficialJsonPath.toString()
        val fromFile = loadUnofficialMetadataFromFilePort.loadFromPath(path)
        if (fromFile.isEmpty()) return
        val packs = getAllPacks.invoke()
        packs
            .filter { !it.metadata.official && it.id in fromFile }
            .forEach { pack ->
                val entry = requireNotNull(fromFile[pack.id]) { "Entry for ${pack.id} in fromFile" }
                updatePackMetadata.invoke(
                    UpdatePackMetadataCommand(
                        packId = pack.id,
                        title = entry.title,
                        description = entry.description,
                        linkedOfficialPackId = pack.metadata.linkedOfficialPackId
                    )
                )
            }
    }
}
