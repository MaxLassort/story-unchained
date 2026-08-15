package com.maxlass.studio.pack.service

import com.maxlass.studio.infrastructure.persistence.InvalidPackMoveQueueEntity
import com.maxlass.studio.infrastructure.persistence.InvalidPackMoveQueueJpaRepository
import com.maxlass.studio.infrastructure.persistence.PackEntity
import com.maxlass.studio.infrastructure.persistence.PackJpaRepository
import com.maxlass.studio.infrastructure.persistence.PackMetadataEntity
import com.maxlass.studio.infrastructure.persistence.PackMetadataJpaRepository
import com.maxlass.studio.infrastructure.persistence.PackScanIndexJpaRepository
import com.maxlass.studio.infrastructure.persistence.PackVariantJpaRepository
import com.maxlass.studio.infrastructure.persistence.SyncJobEntity
import com.maxlass.studio.infrastructure.persistence.SyncJobJpaRepository
import com.maxlass.studio.infrastructure.config.StudioProperties
import com.maxlass.studio.pack.cache.ThumbnailCache
import com.maxlass.studio.pack.domain.dto.OfficialMetadataDto
import com.maxlass.studio.pack.domain.dto.SyncJobStartResponse
import com.maxlass.studio.pack.domain.dto.SyncJobStatusResponse
import com.maxlass.studio.pack.port.external.ExtractThumbnailFromFsPackPort
import com.maxlass.studio.pack.port.external.MetaDataReaderPort
import com.maxlass.studio.pack.port.external.MetadataRefreshPort
import com.maxlass.studio.pack.port.persistence.PackRepositoryPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

private const val DEFAULT_BATCH_SIZE = 50
private const val DEFAULT_PARALLELISM = 6
private const val ENTRY_TIMEOUT_MS = 3_000L

data class ProcessResult(
    val synchronizedCount: Int = 0,
    val invalidQueuedCount: Int = 0,
    val failedCount: Int = 0
)

@Service
class SyncPacksService(
    private val packRepository: PackRepositoryPort,
    metadataReader: MetaDataReaderPort,
    private val metadataRefresh: MetadataRefreshPort,
    extractThumbnailFromFsPack: ExtractThumbnailFromFsPackPort,
    thumbnailCache: ThumbnailCache,
    private val packJpaRepository: PackJpaRepository,
    private val packMetadataJpaRepository: PackMetadataJpaRepository,
    private val syncJobRepository: SyncJobJpaRepository,
    private val invalidQueueRepository: InvalidPackMoveQueueJpaRepository,
    private val packScanIndexRepository: PackScanIndexJpaRepository,
    private val variantRepository: PackVariantJpaRepository,
    private val fingerprinter: PackFingerprinter,
    transactionManager: PlatformTransactionManager,
    private val studioProperties: StudioProperties,
) {
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueProcessing = AtomicBoolean(false)
    private val tx = TransactionTemplate(transactionManager)

    private val inspector = PackFileInspector(metadataReader)
    private val extractor = PackMetaExtractor(metadataReader, extractThumbnailFromFsPack, thumbnailCache)

    companion object {
        private val log = LoggerFactory.getLogger(SyncPacksService::class.java)
        private const val QUEUE_PENDING = "PENDING"
        private const val QUEUE_DONE = "DONE"
        private const val QUEUE_FAILED = "FAILED"
        private const val JOB_PENDING = "PENDING"
        private const val JOB_RUNNING = "RUNNING"
        private const val JOB_DONE = "DONE"
        private const val JOB_FAILED = "FAILED"
        private const val INDEX_VALID = "VALID"
        private const val INDEX_INVALID = "INVALID"
    }

    suspend fun invoke(directoryPath: String): SyncJobStartResponse = startSync(directoryPath)

    suspend fun startSync(directoryPath: String): SyncJobStartResponse {
        val now = System.currentTimeMillis()
        val jobId = tx.execute {
            syncJobRepository.save(
                SyncJobEntity(
                    status = JOB_PENDING,
                    startedAtEpochMs = now,
                    finishedAtEpochMs = null,
                    totalEntries = 0,
                    processedEntries = 0,
                    synchronizedCount = 0,
                    invalidQueuedCount = 0,
                    failedCount = 0,
                    message = null,
                    batchSize = DEFAULT_BATCH_SIZE,
                    parallelism = DEFAULT_PARALLELISM,
                )
            ).id
        }!!
        backgroundScope.launch { runJob(jobId, directoryPath) }
        return SyncJobStartResponse(jobId = jobId, status = JOB_PENDING)
    }

    fun getJobStatus(jobId: Long): SyncJobStatusResponse? =
        syncJobRepository.findById(jobId).map { it.toSyncJobStatus() }.orElse(null)

    /** Deletes all pack rows from the database (packs + metadata + variants + indexes + jobs). */
    fun clearPacks() {
        tx.execute {
            packMetadataJpaRepository.deleteAll()
            invalidQueueRepository.deleteAll()
            syncJobRepository.deleteAll()
            packScanIndexRepository.deleteAll()
            variantRepository.deleteAll()
            packJpaRepository.deleteAll()
            Unit
        }
    }

    private suspend fun runJob(jobId: Long, directoryPath: String) {
        updateJobStatus(jobId, JOB_RUNNING, null)
        val directory = File(directoryPath)
        if (!directory.exists() || !directory.isDirectory) {
            finishJob(jobId, JOB_FAILED, "Dossier de synchronisation introuvable: $directoryPath")
            return
        }
        val invalidPacksDir = studioProperties.defaultLibraryPath.parent.resolve("invalid").toFile()
            .apply { if (!exists()) mkdirs() }

        val entries = directory.listFiles()
            ?.filterNot { it.absolutePath == invalidPacksDir.absolutePath }
            ?.toList()
            ?: emptyList()

        setJobTotal(jobId, entries.size)

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
            updateJobProgress(jobId, processed, synchronizedCount, invalidQueuedCount, failedCount)
        }

        val finalMessage = buildString {
            append("Synchronisation terminée: $synchronizedCount pack(s) synchronisé(s).")
            if (invalidQueuedCount > 0) append(" $invalidQueuedCount élément(s) mis en file invalid.")
            if (failedCount > 0) append(" $failedCount élément(s) en erreur.")
        }
        finishJob(jobId, JOB_DONE, finalMessage)
        triggerBackgroundQueueProcessing()
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
            val queued = enqueueInvalidEntry(file, invalidPacksDir, "Format de pack non reconnu")
            fingerprinter.upsertIndex(snapshot, fingerprinter.computeContentHash(file), INDEX_INVALID, null, null)
            return if (queued) ProcessResult(invalidQueuedCount = 1) else ProcessResult(failedCount = 1)
        }

        val pack = extractor.buildPack(file, meta, format, officialCache)
        packRepository.savePack(pack)
        fingerprinter.upsertIndex(snapshot, fingerprinter.computeContentHash(file), INDEX_VALID, meta.uuid, format.name)
        ProcessResult(synchronizedCount = 1)
    }.getOrElse { e ->
        log.error("Erreur traitement entrée: {}", file.absolutePath, e)
        val snapshot = fingerprinter.buildSnapshot(file)
        val reason = e.message?.take(500) ?: "Erreur de traitement"
        val queued = enqueueInvalidEntry(file, invalidPacksDir, reason)
        fingerprinter.upsertIndex(snapshot, fingerprinter.computeContentHash(file), INDEX_INVALID, null, null)
        if (queued) ProcessResult(invalidQueuedCount = 1) else ProcessResult(failedCount = 1)
    }

    private fun enqueueInvalidEntry(entry: File, invalidPacksDir: File, reason: String): Boolean {
        val target = uniqueTargetPath(invalidPacksDir.toPath(), entry.name).toFile()
        return runCatching {
            invalidQueueRepository.save(
                InvalidPackMoveQueueEntity(
                    sourcePath = entry.absolutePath,
                    targetPath = target.absolutePath,
                    reason = reason,
                    status = QUEUE_PENDING,
                    error = null,
                    createdAtEpochMs = System.currentTimeMillis(),
                    processedAtEpochMs = null,
                )
            )
            true
        }.getOrDefault(false)
    }

    private fun uniqueTargetPath(baseDir: Path, originalName: String): Path {
        var candidate = baseDir.resolve(originalName)
        if (!Files.exists(candidate)) return candidate
        val dot = originalName.lastIndexOf('.')
        val (name, ext) = if (dot > 0) originalName.substring(0, dot) to originalName.substring(dot) else originalName to ""
        var index = 1
        while (Files.exists(candidate)) {
            candidate = baseDir.resolve("${name}_$index$ext")
            index += 1
        }
        return candidate
    }

    private fun triggerBackgroundQueueProcessing() {
        if (!queueProcessing.compareAndSet(false, true)) return
        backgroundScope.launch {
            try { processQueueBatch() }
            finally { queueProcessing.set(false) }
        }
    }

    private fun processQueueBatch() {
        val pendingRows = invalidQueueRepository.findAll()
            .filter { it.status == QUEUE_PENDING }
            .sortedBy { it.id }
        pendingRows.forEach { row ->
            val source = Path.of(row.sourcePath)
            val target = Path.of(row.targetPath)
            val result = runCatching {
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target)
                    Files.walk(source).forEach { path ->
                        val relative = source.relativize(path)
                        val out = target.resolve(relative)
                        if (Files.isDirectory(path)) Files.createDirectories(out)
                        else {
                            out.parent?.let { Files.createDirectories(it) }
                            Files.copy(path, out, StandardCopyOption.COPY_ATTRIBUTES)
                        }
                    }
                    File(source.toString()).deleteRecursively()
                } else {
                    target.parent?.let { Files.createDirectories(it) }
                    Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
                    Files.deleteIfExists(source)
                }
            }
            tx.execute {
                if (result.isSuccess) {
                    row.status = QUEUE_DONE
                    row.error = null
                    row.processedAtEpochMs = System.currentTimeMillis()
                } else {
                    row.status = QUEUE_FAILED
                    row.error = result.exceptionOrNull()?.message ?: "Unknown move error"
                    row.processedAtEpochMs = System.currentTimeMillis()
                }
                invalidQueueRepository.save(row)
                null
            }
        }
    }

    private fun setJobTotal(jobId: Long, total: Int) = tx.execute {
        syncJobRepository.findById(jobId).ifPresent { job ->
            job.totalEntries = total
            syncJobRepository.save(job)
        }
        null
    }

    private fun updateJobProgress(jobId: Long, processed: Int, sync: Int, invalid: Int, failed: Int) = tx.execute {
        syncJobRepository.findById(jobId).ifPresent { job ->
            job.processedEntries = processed
            job.synchronizedCount = sync
            job.invalidQueuedCount = invalid
            job.failedCount = failed
            syncJobRepository.save(job)
        }
        null
    }

    private fun updateJobStatus(jobId: Long, status: String, message: String?) = tx.execute {
        syncJobRepository.findById(jobId).ifPresent { job ->
            job.status = status
            job.message = message
            syncJobRepository.save(job)
        }
        null
    }

    private fun finishJob(jobId: Long, status: String, message: String) = tx.execute {
        syncJobRepository.findById(jobId).ifPresent { job ->
            job.status = status
            job.message = message
            job.finishedAtEpochMs = System.currentTimeMillis()
            syncJobRepository.save(job)
        }
        null
    }

    private fun SyncJobEntity.toSyncJobStatus(): SyncJobStatusResponse =
        SyncJobStatusResponse(
            jobId = id,
            status = status,
            totalEntries = totalEntries,
            processedEntries = processedEntries,
            synchronizedCount = synchronizedCount,
            invalidQueuedCount = invalidQueuedCount,
            failedCount = failedCount,
            message = message,
            startedAtEpochMs = startedAtEpochMs,
            finishedAtEpochMs = finishedAtEpochMs,
            batchSize = batchSize,
            parallelism = parallelism
        )
}
