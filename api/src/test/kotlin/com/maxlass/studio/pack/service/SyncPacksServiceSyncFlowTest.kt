package com.maxlass.studio.pack.service

import com.maxlass.studio.infrastructure.config.StudioProperties
import com.maxlass.studio.infrastructure.persistence.PackJpaRepository
import com.maxlass.studio.infrastructure.persistence.PackMetadataJpaRepository
import com.maxlass.studio.infrastructure.persistence.PackScanIndexJpaRepository
import com.maxlass.studio.infrastructure.persistence.PackVariantJpaRepository
import com.maxlass.studio.pack.cache.ThumbnailCache
import com.maxlass.studio.pack.domain.dto.SyncStatus
import com.maxlass.studio.pack.domain.dto.SyncStatusEvent
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
import io.mockk.mockk
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.springframework.transaction.PlatformTransactionManager
import java.nio.file.Files

class SyncPacksServiceSyncFlowTest : StringSpec({
    val packRepository = mockk<PackRepositoryPort>()
    val metadataReader = mockk<MetaDataReaderPort>()
    val metadataRefresh = mockk<MetadataRefreshPort>()
    val extractThumbnail = mockk<ExtractThumbnailFromFsPackPort>()
    val packJpaRepository = mockk<PackJpaRepository>(relaxed = true)
    val packMetadataJpaRepository = mockk<PackMetadataJpaRepository>(relaxed = true)
    val packScanIndexRepository = mockk<PackScanIndexJpaRepository>(relaxed = true)
    val variantRepository = mockk<PackVariantJpaRepository>(relaxed = true)
    val fingerprinter = mockk<PackFingerprinter>(relaxed = true)
    val transactionManager = mockk<PlatformTransactionManager>(relaxed = true)
    val metadataSync = mockk<SyncUnofficialMetadataUseCase>(relaxed = true)

    beforeTest {
        clearAllMocks()
        coEvery { packRepository.getAllPacks() } returns emptyList()
        every { metadataRefresh.getOfficialMetadataMap() } returns emptyMap()
        every { packScanIndexRepository.findAll() } returns emptyList()
    }

    fun service(publisher: SyncEventPublisher, properties: StudioProperties = StudioProperties()) = SyncPacksService(
        packRepository = packRepository,
        metadataReader = metadataReader,
        metadataRefresh = metadataRefresh,
        extractThumbnailFromFsPack = extractThumbnail,
        thumbnailCache = ThumbnailCache(),
        packJpaRepository = packJpaRepository,
        packMetadataJpaRepository = packMetadataJpaRepository,
        packScanIndexRepository = packScanIndexRepository,
        variantRepository = variantRepository,
        fingerprinter = fingerprinter,
        transactionManager = transactionManager,
        studioProperties = properties,
        syncUnofficialMetadata = metadataSync,
        eventPublisher = publisher,
    )

    fun collect(publisher: SyncEventPublisher, count: Int): MutableList<SyncStatusEvent> {
        val collected = mutableListOf<SyncStatusEvent>()
        runBlocking {
            withTimeout(5_000) {
                publisher.sharedEvents.take(count).toList(collected)
            }
        }
        return collected
    }

    "publishes PENDING, RUNNING and DONE for an empty directory" {
        val publisher = SyncEventPublisher()
        val service = service(publisher)
        val directory = Files.createTempDirectory("sync-flow-test").toFile()

        val collected = mutableListOf<SyncStatusEvent>()
        val collector = launch { publisher.sharedEvents.take(4).toList(collected) }
        service.startSync(directory.absolutePath)
        withTimeout(5_000) { collector.join() }

        collected.map { it.status } shouldBe listOf(SyncStatus.PENDING, SyncStatus.RUNNING, SyncStatus.RUNNING, SyncStatus.DONE)
        collected.last().processedEntries shouldBe 0
        service.shutdown()
        directory.deleteRecursively()
    }

    "publishes FAILED when the directory does not exist" {
        val publisher = SyncEventPublisher()
        val service = service(publisher)
        val directory = Files.createTempDirectory("sync-failure-test").toFile()
        directory.deleteRecursively()

        val collected = mutableListOf<SyncStatusEvent>()
        val collector = launch { publisher.sharedEvents.take(2).toList(collected) }
        service.startSync(directory.absolutePath)
        withTimeout(5_000) { collector.join() }

        collected.map { it.status } shouldBe listOf(SyncStatus.PENDING, SyncStatus.FAILED)
        collected.last().message shouldBe "Dossier de synchronisation introuvable: ${directory.absolutePath}"
        service.shutdown()
    }

    "moves an invalid entry directly to the invalid directory" {
        val publisher = SyncEventPublisher()
        val service = service(publisher)
        val root = Files.createTempDirectory("invalid-move-test")
        val source = Files.writeString(root.resolve("broken.txt"), "invalid").toFile()
        val invalidDir = root.resolve("invalid").toFile()

        service.moveInvalidEntry(source, invalidDir, "unrecognized format") shouldBe true
        Files.exists(source.toPath()) shouldBe false
        Files.exists(invalidDir.toPath().resolve(source.name)) shouldBe true
        service.shutdown()
        root.toFile().deleteRecursively()
    }

    "returns false when an invalid entry cannot be moved" {
        val publisher = SyncEventPublisher()
        val service = service(publisher)
        val root = Files.createTempDirectory("invalid-move-failure-test")
        val source = Files.writeString(root.resolve("broken.txt"), "invalid").toFile()
        val invalidDir = root.resolve("invalid").toFile()
        invalidDir.mkdirs()
        invalidDir.resolve(source.name).mkdirs()

        service.moveInvalidEntry(source, invalidDir, "unrecognized format") shouldBe false
        Files.exists(source.toPath()) shouldBe true
        service.shutdown()
        root.toFile().deleteRecursively()
    }

    "rejects a second synchronization while the first one is active" {
        val publisher = SyncEventPublisher()
        val service = service(publisher)
        val directory = Files.createTempDirectory("sync-concurrency-test").toFile()

        service.startSync(directory.absolutePath)
        val error = runCatching { service.startSync(directory.absolutePath) }.exceptionOrNull()

        (error is SyncAlreadyRunningException) shouldBe true
        service.shutdown()
        directory.deleteRecursively()
    }
})
