package com.maxlass.studio.pack.service

import com.maxlass.studio.pack.domain.model.Pack
import com.maxlass.studio.pack.domain.model.PackFormat
import com.maxlass.studio.pack.domain.model.PackVariant
import com.maxlass.studio.pack.port.external.PackFormatConverterPort
import com.maxlass.studio.pack.port.persistence.PackRepositoryPort
import com.maxlass.studio.settings.service.SettingsService
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

@Service
class ConvertPackFormatUseCase(
    private val packRepository: PackRepositoryPort,
    private val converterPort: PackFormatConverterPort,
    private val settingsService: SettingsService
) {
    suspend fun archiveToRaw(packId: String): Path = convert(packId, PackFormat.ARCHIVE, PackFormat.RAW)
    suspend fun archiveToFs(packId: String): Path = convert(packId, PackFormat.ARCHIVE, PackFormat.FS)
    suspend fun rawToArchive(packId: String): Path = convert(packId, PackFormat.RAW, PackFormat.ARCHIVE)
    suspend fun rawToFs(packId: String): Path = convert(packId, PackFormat.RAW, PackFormat.FS)
    suspend fun fsToArchive(packId: String): Path = convert(packId, PackFormat.FS, PackFormat.ARCHIVE)
    suspend fun fsToRaw(packId: String): Path = convert(packId, PackFormat.FS, PackFormat.RAW)

    suspend fun convert(packId: String, sourceFormat: PackFormat, targetFormat: PackFormat): Path =
        convertInternal(packId, sourceFormat, targetFormat)

    private suspend fun convertInternal(packId: String, source: PackFormat, target: PackFormat): Path {
        val pack = findPack(packId)
        val sourcePath = pack.variants.firstOrNull { it.format == source }?.let { Path.of(it.storagePath) }
            ?: throw IllegalArgumentException(
                "Pack $packId has no $source variant. Available: ${pack.variants.map { it.format.name }.distinct().joinToString(", ")}"
            )
        val destinationDir = Path.of(settingsService.getLibraryPath()).also { Files.createDirectories(it) }
        val outputPath = converterPort.convert(sourcePath, source, target, destinationDir)
        val newVariant = PackVariant(format = target, storagePath = outputPath.toAbsolutePath().toString())
        val updatedPack = pack.copy(variants = pack.variants + newVariant)
        packRepository.savePack(updatedPack)
        return outputPath
    }

    private suspend fun findPack(packId: String): Pack =
        packRepository.getAllPacks().firstOrNull { it.id == packId }
            ?: throw NoSuchElementException("Pack $packId not found")
}
