package com.maxlass.studio.pack.service

import com.maxlass.studio.infrastructure.persistence.PackEntity
import com.maxlass.studio.infrastructure.persistence.PackJpaRepository
import com.maxlass.studio.infrastructure.persistence.PackMetadataEntity
import com.maxlass.studio.infrastructure.persistence.PackMetadataJpaRepository
import com.maxlass.studio.infrastructure.persistence.PackScanIndexJpaRepository
import com.maxlass.studio.infrastructure.persistence.PackVariantJpaRepository
import com.maxlass.studio.infrastructure.config.StudioProperties
import com.maxlass.studio.pack.cache.ThumbnailCache
import com.maxlass.studio.pack.domain.dto.OfficialMetadataDto
import com.maxlass.studio.pack.domain.model.PackFormat
import com.maxlass.studio.pack.port.external.ExtractThumbnailFromFsPackPort
import com.maxlass.studio.pack.port.external.MetaDataReaderPort
import com.maxlass.studio.pack.port.external.MetadataRefreshPort
import com.maxlass.studio.pack.domain.dto.SyncStatus
import com.maxlass.studio.pack.domain.dto.SyncStatusEvent
import com.maxlass.studio.pack.port.external.SyncEventPublisher
import com.maxlass.studio.pack.port.persistence.PackRepositoryPort
import com.maxlass.studio.pack.util.readThumbnailBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

private const val DEFAULT_BATCH_SIZE = 50
private const val DEFAULT_PARALLELISM = 6
private const val ENTRY_TIMEOUT_MS = 3_000L

data class ProcessResult(
    val synchronizedCount: Int = 0,
    val invalidQueuedCount: Int = 0,
    val failedCount: Int = 0
)

class SyncAlreadyRunningException(message: String) : RuntimeException(message)

@Service
class SyncPacksService(
    private val packRepository: PackRepositoryPort,
    private val metadataReader: MetaDataReaderPort,
    private val metadataRefresh: MetadataRefreshPort,
    extractThumbnailFromFsPack: ExtractThumbnailFromFsPackPort,
    thumbnailCache: ThumbnailCache,
    private val packJpaRepository: PackJpaRepository,
    private val packMetadataJpaRepository: PackMetadataJpaRepository,
    private val packScanIndexRepository: PackScanIndexJpaRepository,
    private val variantRepository: PackVariantJpaRepository,
    private val fingerprinter: PackFingerprinter,
    transactionManager: PlatformTransactionManager,
    private val studioProperties: StudioProperties,
    private val syncUnofficialMetadata: SyncUnofficialMetadataUseCase,
    private val eventPublisher: SyncEventPublisher,
) {
    fun eventPublisher(): SyncEventPublisher = eventPublisher

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncRunning = AtomicBoolean(false)
    private val tx = TransactionTemplate(transactionManager)

    @PreDestroy
    fun shutdown() {
        backgroundScope.cancel("SyncPacksService shutting down")
    }

    private val inspector = PackFileInspector(metadataReader)
    private val extractor = PackMetaExtractor(metadataReader, extractThumbnailFromFsPack, thumbnailCache)

    companion object {
        private val log = LoggerFactory.getLogger(SyncPacksService::class.java)
        private const val INDEX_VALID = "VALID"
        private const val INDEX_INVALID = "INVALID"
    }

    suspend fun invoke(directoryPath: String): Unit = startSync(directoryPath)

    suspend fun startSync(directoryPath: String): Unit {
        if (!syncRunning.compareAndSet(false, true)) {
            throw SyncAlreadyRunningException("A pack synchronization is already running.")
        }
        try {
            eventPublisher?.publish(
                SyncStatusEvent(
                    status = SyncStatus.PENDING,
                    batchSize = DEFAULT_BATCH_SIZE,
                    parallelism = DEFAULT_PARALLELISM,
                    startedAtEpochMs = System.currentTimeMillis(),
                )
            )
            backgroundScope.launch { runJob(directoryPath) }
        } finally {
            // guard release happens in runJob; nothing to release here
            Unit
        }
    }

    /** Deletes pack rows from the database (packs + metadata + variants + indexes). */
    fun clearPacks() {
        tx.execute {
            packMetadataJpaRepository.deleteAll()
            packScanIndexRepository.deleteAll()
            variantRepository.deleteAll()
            packJpaRepository.deleteAll()
            Unit
        }
    }

    private suspend fun runJob(directoryPath: String) {
        try {
            runCatching {
                doRunJob(directoryPath)
            }.getOrElse { e ->
                log.error("Synchronisation échouée: {}", directoryPath, e)
                eventPublisher?.publish(
                    SyncStatusEvent(
                        status = SyncStatus.FAILED,
                        message = e.message ?: "Synchronisation échouée",
                        batchSize = DEFAULT_BATCH_SIZE,
                        parallelism = DEFAULT_PARALLELISM,
                        finishedAtEpochMs = System.currentTimeMillis(),
                    )
                )
            }
        } finally {
            syncRunning.set(false)
        }
    }

    private suspend fun doRunJob(directoryPath: String) {
        val directory = File(directoryPath)
        if (!directory.exists() || !directory.isDirectory) {
            eventPublisher?.publish(
                SyncStatusEvent(
                    status = SyncStatus.FAILED,
                    message = "Dossier de synchronisation introuvable: $directoryPath",
                    batchSize = DEFAULT_BATCH_SIZE,
                    parallelism = DEFAULT_PARALLELISM,
                    startedAtEpochMs = System.currentTimeMillis(),
                    finishedAtEpochMs = System.currentTimeMillis(),
                )
            )
            return
        }

        eventPublisher?.publish(
            SyncStatusEvent(
                status = SyncStatus.RUNNING,
                batchSize = DEFAULT_BATCH_SIZE,
                parallelism = DEFAULT_PARALLELISM,
                startedAtEpochMs = System.currentTimeMillis(),
            )
        )

        val invalidPacksDir = directory.toPath().parent.resolve("invalid").toFile()
            .apply { if (!exists()) mkdirs() }

        val entries = directory.listFiles()
            ?.filterNot { it.absolutePath == invalidPacksDir.absolutePath }
            ?.toList()
            ?: emptyList()

        eventPublisher?.publish(
            SyncStatusEvent(
                status = SyncStatus.RUNNING,
                totalEntries = entries.size,
                batchSize = DEFAULT_BATCH_SIZE,
                parallelism = DEFAULT_PARALLELISM,
                startedAtEpochMs = System.currentTimeMillis(),
            )
        )

        var officialCache = metadataRefresh.getOfficialMetadataMap()
        if (officialCache.isEmpty()) {
            runCatching { metadataRefresh.refreshOfficialMetadata() }
                .onFailure { log.warn("Catalogue officiel absent ou illisible — téléchargement Lunii échoué: {}", it.message) }
            officialCache = metadataRefresh.getOfficialMetadataMap()
        }
        val entriesByPath = entries.associateBy { it.absolutePath }
        insertOfficialCatalogPacks(officialCache)
        fingerprinter.markDeletedEntries(entriesByPath.keys)

        val semaphore = Semaphore(DEFAULT_PARALLELISM)
        var processed = 0
        var synchronizedCount = 0
        var invalidQueuedCount = 0
        var failedCount = 0

        entries.chunked(DEFAULT_BATCH_SIZE).forEach { batch ->
            val results = batch.map { file ->
                backgroundScope.async {
                    semaphore.withPermit {
                        withTimeout(ENTRY_TIMEOUT_MS) {
                            processEntry(file, invalidPacksDir, officialCache)
                        }
                    }
                }
            }.awaitAll()

            results.forEach { result ->
                processed += 1
                synchronizedCount += result.synchronizedCount
                invalidQueuedCount += result.invalidQueuedCount
                failedCount += result.failedCount
            }
            eventPublisher?.publish(
                SyncStatusEvent(
                    status = SyncStatus.RUNNING,
                    totalEntries = entries.size,
                    processedEntries = processed,
                    synchronizedCount = synchronizedCount,
                    invalidQueuedCount = invalidQueuedCount,
                    failedCount = failedCount,
                    batchSize = DEFAULT_BATCH_SIZE,
                    parallelism = DEFAULT_PARALLELISM,
                    startedAtEpochMs = System.currentTimeMillis(),
                )
            )
        }

        val finalMessage = buildString {
            append("Synchronisation terminée: $synchronizedCount pack(s) synchronisé(s).")
            if (invalidQueuedCount > 0) append(" $invalidQueuedCount élément(s) invalide(s) déplacé(s).")
            if (failedCount > 0) append(" $failedCount élément(s) en erreur.")
        }
        eventPublisher?.publish(
            SyncStatusEvent(
                status = SyncStatus.DONE,
                totalEntries = entries.size,
                processedEntries = entries.size,
                synchronizedCount = synchronizedCount,
                invalidQueuedCount = invalidQueuedCount,
                failedCount = failedCount,
                message = finalMessage,
                batchSize = DEFAULT_BATCH_SIZE,
                parallelism = DEFAULT_PARALLELISM,
                startedAtEpochMs = System.currentTimeMillis(),
                finishedAtEpochMs = System.currentTimeMillis(),
            )
        )

        runCatching { syncUnofficialMetadata.invoke() }
            .onFailure { log.warn("Sync des métadonnées Studio non officielles échoué: {}", it.message) }
        runCatching { normalizeArchiveMetadata() }
            .onFailure { log.warn("Normalisation des métadonnées archive échouée: {}", it.message) }
    }

    /**
     * Post-sync invariant: whenever a pack has an ARCHIVE (zip) variant, that zip is the
     * authoritative source for the pack's metadata. Because the sync stores a single metadata row
     * shared by every variant and each file entry re-saves it, a later-processed variant (typically
     * an FS folder, which yields mostly null fields) can otherwise clobber the zip's real title,
     * description, locale, ages, duration, story count and cover. Re-assert all of them from the zip
     * whatever the processing order.
     */
    internal suspend fun normalizeArchiveMetadata() {
        val packs = packRepository.getAllPacks()
        for (pack in packs) {
            if (pack.metadata.official) continue
            val archiveVariant = pack.variants.firstOrNull { it.format == PackFormat.ARCHIVE } ?: continue
            val archivePath = Path.of(archiveVariant.storagePath)
            val archiveMeta = metadataReader.readArchiveMetadata(archivePath) ?: continue
            val cover = readArchiveCoverDataUri(archivePath)
            packMetadataJpaRepository.findById(pack.id).ifPresent { meta ->
                var changed = false
                archiveMeta.title?.takeIf { it.isNotBlank() }?.let {
                    if (meta.title != it) { meta.title = it; changed = true } }
                archiveMeta.description?.takeIf { it.isNotBlank() }?.let {
                    if (meta.description != it) { meta.description = it; changed = true } }
                archiveMeta.locale?.takeIf { it.isNotBlank() }?.let {
                    if (meta.locale != it) { meta.locale = it; changed = true } }
                archiveMeta.ageMin?.let {
                    if (meta.ageMin != it) { meta.ageMin = it; changed = true } }
                archiveMeta.ageMax?.let {
                    if (meta.ageMax != it) { meta.ageMax = it; changed = true } }
                archiveMeta.durationMs?.let {
                    if (meta.durationMs != it) { meta.durationMs = it; changed = true } }
                archiveMeta.storyCount?.let {
                    if (meta.storyCount != it) { meta.storyCount = it; changed = true } }
                if (archiveMeta.version.toInt() != 0 && meta.version.toInt() != archiveMeta.version.toInt()) {
                    meta.version = archiveMeta.version
                    changed = true
                }
                if (meta.nightModeAvailable != archiveMeta.isNightModeAvailable) {
                    meta.nightModeAvailable = archiveMeta.isNightModeAvailable
                    changed = true
                }
                if (cover != null && meta.thumbnail != cover) {
                    meta.thumbnail = cover
                    changed = true
                }
                if (changed) packMetadataJpaRepository.save(meta)
            }
        }
    }

    private fun readArchiveCoverDataUri(zipPath: Path): String? =
        readThumbnailBytes(zipPath)?.let {
            "data:image/png;base64,${Base64.getEncoder().encodeToString(it)}"
        }

    private fun insertOfficialCatalogPacks(officialCache: Map<String, OfficialMetadataDto>) {
        if (officialCache.isEmpty()) return
        tx.execute {
            officialCache.forEach { (uuid, dto) ->
                packJpaRepository.save(PackEntity(id = uuid))
                packMetadataJpaRepository.deleteById(uuid)
                packMetadataJpaRepository.save(
                    PackMetadataEntity(
                        packId = uuid,
                        title = dto.title,
                        description = dto.description,
                        thumbnail = dto.thumbnailUrl,
                        version = 1,
                        factoryDisabled = false,
                        nightModeAvailable = false,
                        official = true,
                        linkedOfficialPackId = null,
                        locale = dto.locale,
                        ageMin = dto.ageMin,
                        ageMax = dto.ageMax,
                        durationMs = dto.durationMs,
                        storyCount = dto.storyCount,
                    )
                )
            }
            null
        }
    }

    private suspend fun processEntry(
        file: File,
        invalidPacksDir: File,
        officialCache: Map<String, OfficialMetadataDto>
    ): ProcessResult = runCatching {
        val snapshot = fingerprinter.buildSnapshot(file)
        val existing = fingerprinter.getIndexByPath(snapshot.path)

        if (!fingerprinter.shouldProcessEntry(snapshot, existing)) {
            fingerprinter.upsertIndex(snapshot, existing?.contentHash, INDEX_VALID, existing?.packId, existing?.detectedFormat)
            fingerprinter.refreshOfficialMetadataIfPossible(existing?.packId, officialCache)
            return ProcessResult()
        }

        val detected = inspector.detectFormatAndMetadata(file)
        val (format, meta) = detected ?: run {
            val moved = moveInvalidEntry(file, invalidPacksDir, "Format de pack non reconnu")
            fingerprinter.upsertIndex(snapshot, fingerprinter.computeContentHash(file), INDEX_INVALID, null, null)
            return if (moved) ProcessResult(invalidQueuedCount = 1) else ProcessResult(failedCount = 1)
        }

        val pack = extractor.buildPack(
            file,
            meta,
            format,
            officialCache,
            existingThumbnail = packMetadataJpaRepository.findById(meta.uuid).orElse(null)?.thumbnail,
            hasArchiveVariant = variantRepository.findAll()
                .any { it.id.packId == meta.uuid && it.id.format == PackFormat.ARCHIVE.name },
        )
        packRepository.savePack(pack)
        fingerprinter.upsertIndex(snapshot, fingerprinter.computeContentHash(file), INDEX_VALID, meta.uuid, format.name)
        ProcessResult(synchronizedCount = 1)
    }.getOrElse { e ->
        log.error("Erreur traitement entrée: {}", file.absolutePath, e)
        val snapshot = fingerprinter.buildSnapshot(file)
        val reason = e.message?.take(500) ?: "Erreur de traitement"
        val moved = moveInvalidEntry(file, invalidPacksDir, reason)
        fingerprinter.upsertIndex(snapshot, fingerprinter.computeContentHash(file), INDEX_INVALID, null, null)
        if (moved) ProcessResult(invalidQueuedCount = 1) else ProcessResult(failedCount = 1)
    }

    internal fun moveInvalidEntry(entry: File, invalidPacksDir: File, reason: String): Boolean {
        val target = Files.createDirectories(invalidPacksDir.toPath()).resolve(entry.name).toFile()
        val moved = runCatching {
            Files.move(entry.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrElse {
            // fallback: copy + delete
            runCatching {
                target.parentFile?.let { Files.createDirectories(it.toPath()) }
                Files.copy(entry.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
                Files.deleteIfExists(entry.toPath())
                true
            }.getOrElse {
                false
            }
        }
        if (!moved) {
            log.warn("Impossible de déplacer l'entrée invalide {}: {}", entry.absolutePath, reason)
        }
        return moved
    }
}
