package com.maxlass.studio.pack.service

import com.maxlass.studio.pack.port.persistence.PackRepositoryPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File

/**
 * Deletes a pack from the library: removes files on disk then metadata in DB.
 * Returns [Result]: [Unit] on success, [NoSuchElementException] if pack not found, [Exception] on IO error.
 */
@Service
class DeletePackFromLibraryUseCase(
    private val packRepository: PackRepositoryPort
) {

    companion object {
        private val log = LoggerFactory.getLogger(DeletePackFromLibraryUseCase::class.java)
    }

    suspend operator fun invoke(packId: String): Result<Unit> {
        val pack = packRepository.getAllPacks().find { it.id == packId }
            ?: return Result.failure(NoSuchElementException("Pack not found: $packId"))
        return runCatching {
            pack.variants
                .map { it.storagePath }
                .distinct()
                .forEach { storagePath ->
                    val path = File(storagePath)
                    if (path.exists()) {
                        val deleted = if (path.isDirectory) path.deleteRecursively() else path.delete()
                        if (!deleted) log.warn("Could not delete path: {}", path.absolutePath)
                    }
                }
            if (pack.variants.isEmpty()) {
                log.warn("Pack {} has no stored variant path to delete from disk.", packId)
            }
            packRepository.deletePackMetadata(packId)
        }
    }
}
