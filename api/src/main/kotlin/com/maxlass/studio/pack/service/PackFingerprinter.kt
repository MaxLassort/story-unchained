package com.maxlass.studio.pack.service

import com.maxlass.studio.infrastructure.persistence.PackJpaRepository
import com.maxlass.studio.infrastructure.persistence.PackMetadataJpaRepository
import com.maxlass.studio.infrastructure.persistence.PackScanIndexEntity
import com.maxlass.studio.infrastructure.persistence.PackScanIndexJpaRepository
import com.maxlass.studio.infrastructure.persistence.PackVariantEntity
import com.maxlass.studio.infrastructure.persistence.PackVariantJpaRepository
import com.maxlass.studio.pack.domain.dto.OfficialMetadataDto
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private const val INDEX_VALID = "VALID"
private const val INDEX_INVALID = "INVALID"
private const val INDEX_DELETED = "DELETED"

data class EntrySnapshot(
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModifiedMs: Long
)

data class ScanIndexState(
    val isDirectory: Boolean,
    val size: Long,
    val lastModifiedMs: Long,
    val contentHash: String?,
    val packId: String?,
    val detectedFormat: String?,
    val lastStatus: String?
)

@Service
class PackFingerprinter(
    private val scanIndexRepository: PackScanIndexJpaRepository,
    private val packVariantRepository: PackVariantJpaRepository,
    private val metadataRepository: PackMetadataJpaRepository,
    private val packRepository: PackJpaRepository,
) {

    fun buildSnapshot(file: File): EntrySnapshot =
        EntrySnapshot(
            path = file.absolutePath,
            isDirectory = file.isDirectory,
            size = if (file.isDirectory) directoryFingerprintSize(file.toPath()) else file.length(),
            lastModifiedMs = file.lastModified()
        )

    fun computeContentHash(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        if (file.isFile) {
            digest.update(Files.readAllBytes(file.toPath()))
        } else if (file.isDirectory) {
            Files.walk(file.toPath()).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .map { "${file.toPath().relativize(it)}:${Files.size(it)}:${Files.getLastModifiedTime(it).toMillis()}" }
                    .sorted()
                    .forEach { digest.update(it.toByteArray()) }
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun shouldProcessEntry(snapshot: EntrySnapshot, existing: ScanIndexState?): Boolean {
        if (existing == null) return true
        if (existing.lastStatus == INDEX_DELETED) return true
        if (existing.packId != null && !packRepository.existsById(existing.packId)) return true
        if (snapshot.isDirectory != existing.isDirectory ||
            snapshot.size != existing.size ||
            snapshot.lastModifiedMs != existing.lastModifiedMs
        ) {
            val newHash = computeContentHash(File(snapshot.path))
            return newHash != existing.contentHash
        }
        return false
    }

    fun getIndexByPath(path: String): ScanIndexState? =
        scanIndexRepository.findById(path).orElse(null)?.toScanIndexState()

    @Transactional
    fun upsertIndex(
        snapshot: EntrySnapshot,
        contentHash: String?,
        status: String,
        packId: String?,
        detectedFormat: String?
    ) {
        val now = System.currentTimeMillis()
        val existing = scanIndexRepository.findById(snapshot.path).orElse(null)
        if (existing != null) {
            existing.isDirectory = snapshot.isDirectory
            existing.size = snapshot.size
            existing.lastModifiedMs = snapshot.lastModifiedMs
            existing.contentHash = contentHash
            existing.lastSeenAtEpochMs = now
            existing.lastStatus = status
            existing.packId = packId
            existing.detectedFormat = detectedFormat
            scanIndexRepository.save(existing)
        } else {
            scanIndexRepository.save(
                PackScanIndexEntity(
                    path = snapshot.path,
                    isDirectory = snapshot.isDirectory,
                    size = snapshot.size,
                    lastModifiedMs = snapshot.lastModifiedMs,
                    contentHash = contentHash,
                    lastSeenAtEpochMs = now,
                    lastStatus = status,
                    packId = packId,
                    detectedFormat = detectedFormat,
                )
            )
        }
    }

    @Transactional
    fun markDeletedEntries(currentPaths: Set<String>) {
        val existingPaths = scanIndexRepository.findAll().map { it.path }.toSet()
        val deleted = existingPaths - currentPaths
        if (deleted.isEmpty()) return

        val packIdsToPossiblyDelete = mutableSetOf<String>()
        deleted.forEach { path ->
            val affectedVariants = packVariantRepository.findAll()
                .filter { it.storagePath == path }
            val affectedPackIds = affectedVariants.map { it.id.packId }.toSet()
            packIdsToPossiblyDelete.addAll(affectedPackIds)
            packVariantRepository.deleteAll(affectedVariants)
            scanIndexRepository.findById(path).ifPresent { index ->
                index.lastStatus = INDEX_DELETED
                index.lastSeenAtEpochMs = System.currentTimeMillis()
                scanIndexRepository.save(index)
            }
        }
        packIdsToPossiblyDelete.forEach { packId ->
            val hasRemaining = packVariantRepository.findAll()
                .any { it.id.packId == packId }
            if (!hasRemaining) {
                metadataRepository.deleteById(packId)
                packRepository.deleteById(packId)
                scanIndexRepository.findAll().filter { it.packId == packId }.forEach { index ->
                    index.lastStatus = INDEX_DELETED
                    index.lastSeenAtEpochMs = System.currentTimeMillis()
                    scanIndexRepository.save(index)
                }
            }
        }
    }

    @Transactional
    fun refreshOfficialMetadataIfPossible(packId: String?, officialCache: Map<String, OfficialMetadataDto>) {
        if (packId.isNullOrBlank()) return
        val fromOfficial = officialCache[packId] ?: return
        metadataRepository.findById(packId).ifPresent { meta ->
            meta.title = fromOfficial.title
            meta.description = fromOfficial.description
            meta.thumbnail = fromOfficial.thumbnailUrl
            meta.official = true
            meta.linkedOfficialPackId = null
            metadataRepository.save(meta)
        }
    }

    private fun directoryFingerprintSize(path: Path): Long =
        runCatching {
            Files.walk(path).use { stream ->
                stream.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
            }
        }.getOrDefault(0L)
}

private fun PackScanIndexEntity.toScanIndexState(): ScanIndexState =
    ScanIndexState(
        isDirectory = isDirectory,
        size = size,
        lastModifiedMs = lastModifiedMs,
        contentHash = contentHash,
        packId = packId,
        detectedFormat = detectedFormat,
        lastStatus = lastStatus
    )
