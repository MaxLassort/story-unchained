package com.maxlass.studio.pack.adapter

import com.maxlass.studio.infrastructure.persistence.PackEntity
import com.maxlass.studio.infrastructure.persistence.PackJpaRepository
import com.maxlass.studio.infrastructure.persistence.PackMetadataEntity
import com.maxlass.studio.infrastructure.persistence.PackMetadataJpaRepository
import com.maxlass.studio.infrastructure.persistence.PackVariantEntity
import com.maxlass.studio.infrastructure.persistence.PackVariantId
import com.maxlass.studio.infrastructure.persistence.PackVariantJpaRepository
import com.maxlass.studio.pack.domain.dto.PackFilter
import com.maxlass.studio.pack.domain.model.Pack
import com.maxlass.studio.pack.domain.model.PackFormat
import com.maxlass.studio.pack.domain.model.PackMetadata
import com.maxlass.studio.pack.domain.model.PackVariant
import com.maxlass.studio.pack.port.persistence.PackRepositoryPort
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Repository
class PackRepositoryAdapter(
    private val packRepository: PackJpaRepository,
    private val metadataRepository: PackMetadataJpaRepository,
    private val variantRepository: PackVariantJpaRepository,
    transactionManager: PlatformTransactionManager,
) : PackRepositoryPort {

    private val tx = TransactionTemplate(transactionManager)

    override suspend fun savePack(pack: Pack) {
        tx.execute {
            packRepository.save(PackEntity(id = pack.id))
            metadataRepository.deleteById(pack.id)
            val linkedId = if (pack.metadata.official) null else pack.metadata.linkedOfficialPackId
            metadataRepository.save(
                PackMetadataEntity(
                    packId = pack.id,
                    title = pack.metadata.title,
                    description = pack.metadata.description,
                    thumbnail = pack.metadata.thumbnail,
                    version = pack.metadata.version,
                    factoryDisabled = pack.metadata.factoryDisabled,
                    nightModeAvailable = pack.metadata.nightModeAvailable,
                    official = pack.metadata.official,
                    linkedOfficialPackId = linkedId,
                    locale = pack.metadata.locale,
                    ageMin = pack.metadata.ageMin,
                    ageMax = pack.metadata.ageMax,
                    durationMs = pack.metadata.durationMs,
                    storyCount = pack.metadata.storyCount,
                )
            )
            pack.variants.forEach { variant ->
                variantRepository.save(
                    PackVariantEntity(
                        id = PackVariantId(packId = pack.id, format = variant.format.name),
                        storagePath = variant.storagePath
                    )
                )
            }
            Unit
        }
    }

    override suspend fun getAllPacks(): List<Pack> =
        loadPacks(packRepository.findAll().map { it.id })

    override suspend fun getPacksPage(offset: Int, limit: Int): Pair<List<Pack>, Long> {
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceAtLeast(1)
        val all = packRepository.findAll()
        val total = all.size.toLong()
        val pageIds = all.map { it.id }.drop(safeOffset).take(safeLimit)
        if (pageIds.isEmpty()) return emptyList<Pack>() to total
        return loadPacks(pageIds) to total
    }

    override suspend fun getFilteredPacksPage(offset: Int, limit: Int, filter: PackFilter): Pair<List<Pack>, Long> {
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceAtLeast(1)
        val hasMetadataFilter = filter.official != null || !filter.search.isNullOrBlank()

        val matchingIds = if (hasMetadataFilter) {
            metadataRepository.findAll().filter { meta ->
                (filter.official == null || meta.official == filter.official) &&
                    (filter.search.isNullOrBlank() || meta.title?.contains(filter.search, ignoreCase = true) == true)
            }.map { it.packId }
        } else {
            packRepository.findAll().map { it.id }
        }

        val filteredIds = if (filter.inLibrary != null || filter.locale != null) {
            val metadataById = metadataRepository.findAll().associateBy { it.packId }
            val variantCountByPackId = variantRepository.findAll()
                .groupingBy { it.id.packId }
                .eachCount()
            matchingIds.filter { id ->
                var keep = true
                if (filter.locale != null) {
                    val locale = metadataById[id]?.locale
                    keep = locale == null || locale == filter.locale
                }
                if (keep && filter.inLibrary != null) {
                    val c = variantCountByPackId[id] ?: 0
                    keep = if (filter.inLibrary) c > 0 else c == 0
                }
                keep
            }
        } else matchingIds

        val total = filteredIds.size.toLong()
        val pageIds = filteredIds.drop(safeOffset).take(safeLimit)
        if (pageIds.isEmpty()) return emptyList<Pack>() to total
        return loadPacks(pageIds) to total
    }

    override suspend fun deletePackMetadata(packId: String) {
        tx.execute {
            variantRepository.deleteAll(variantRepository.findAll().filter { it.id.packId == packId })
            metadataRepository.deleteById(packId)
            packRepository.deleteById(packId)
            Unit
        }
    }

    private fun loadPacks(ids: List<String>): List<Pack> {
        if (ids.isEmpty()) return emptyList()
        val idSet = ids.toSet()
        val metadata = metadataRepository.findAll().filter { it.packId in idSet }
            .associateBy { it.packId }
        val variants = variantRepository.findAll().filter { it.id.packId in idSet }
            .groupBy { it.id.packId }
        return ids.mapNotNull { id ->
            val meta = metadata[id] ?: return@mapNotNull null
            Pack(
                id = id,
                metadata = PackMetadata(
                    title = meta.title,
                    description = meta.description,
                    thumbnail = meta.thumbnail,
                    version = meta.version,
                    factoryDisabled = meta.factoryDisabled,
                    nightModeAvailable = meta.nightModeAvailable,
                    official = meta.official,
                    linkedOfficialPackId = meta.linkedOfficialPackId,
                    locale = meta.locale,
                    ageMin = meta.ageMin,
                    ageMax = meta.ageMax,
                    durationMs = meta.durationMs,
                    storyCount = meta.storyCount,
                ),
                variants = variants[id].orEmpty().mapNotNull { v ->
                    val format = runCatching { PackFormat.valueOf(v.id.format) }.getOrDefault(PackFormat.UNKNOWN)
                    PackVariant(format = format, storagePath = v.storagePath)
                }.distinctBy { "${it.format}:${it.storagePath}" }
            )
        }
    }
}
