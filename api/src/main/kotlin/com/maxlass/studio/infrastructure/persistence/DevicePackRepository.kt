package com.maxlass.studio.infrastructure.persistence

import com.maxlass.studio.device.domain.model.DevicePack
import com.maxlass.studio.pack.cache.ThumbnailCache
import com.maxlass.studio.pack.port.external.ExtractThumbnailFromFsPackPort
import com.maxlass.studio.pack.util.readThumbnailBytes
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path
import java.util.Base64

/**
 * JPA seam for device persistence: `device_packs` rows (upsert/scan/snapshots) and
 * `registerVariant` (pack + variant + metadata) used after copying a pack from device to library.
 */
@Repository
class DevicePackRepository(
    private val devicePackRepository: DevicePackJpaRepository,
    private val packRepository: PackJpaRepository,
    private val variantRepository: PackVariantJpaRepository,
    private val metadataRepository: PackMetadataJpaRepository,
    private val thumbnailCache: ThumbnailCache,
    private val extractThumbnailFromFsPack: ExtractThumbnailFromFsPackPort,
    transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)

    /** Returns packs for a device UUID from the device_packs table (historical), joined to pack metadata. */
    fun getDevicePacksByUuid(deviceUuid: String): List<DevicePack> {
        val rows = devicePackRepository.findAll().filter { it.id.deviceUuid == deviceUuid }
        return rows.toDevicePacks()
    }

    /** Returns one snapshot per known device UUID, most-recently-seen first, with full pack lists. */
    fun getDeviceSnapshots(): List<com.maxlass.studio.device.domain.model.DeviceSnapshot> {
        val allRows = devicePackRepository.findAll()
        if (allRows.isEmpty()) return emptyList()
        val byDevice = allRows.groupBy { it.id.deviceUuid }
        return byDevice.map { (deviceUuid, rows) ->
            val packs = rows.toDevicePacks()
            com.maxlass.studio.device.domain.model.DeviceSnapshot(
                uuid = deviceUuid,
                lastSeenAtEpochMs = rows.maxOf { it.lastSeenAtEpochMs },
                packCount = packs.size,
                packs = packs,
            )
        }.sortedByDescending { it.lastSeenAtEpochMs }
    }

    /** Replaces the device_packs rows for [deviceUuid] with the given [packs] (from the driver). */
    fun scanAndUpsertDevicePacks(deviceUuid: String, packs: List<DevicePack>) {
        tx.execute {
            val existing = devicePackRepository.findAll().filter { it.id.deviceUuid == deviceUuid }
            val existingUuids = existing.map { it.id.packUuid }.toSet()
            val currentUuids = packs.map { it.uuid }.toSet()
            existing.filter { it.id.packUuid !in currentUuids }
                .forEach { devicePackRepository.delete(it) }
            packs.forEach { pack ->
                val found = existing.firstOrNull { it.id.packUuid == pack.uuid }
                if (found != null) {
                    found.version = pack.version
                    found.sizeInBytes = pack.sizeInBytes
                    found.lastSeenAtEpochMs = System.currentTimeMillis()
                    devicePackRepository.save(found)
                } else {
                    devicePackRepository.save(
                        DevicePackEntity(
                            id = DevicePackId(deviceUuid = deviceUuid, packUuid = pack.uuid),
                            version = pack.version,
                            sizeInBytes = pack.sizeInBytes,
                            lastSeenAtEpochMs = System.currentTimeMillis(),
                        )
                    )
                }
            }
            Unit
        }
    }

    /**
     * Registers a pack variant discovered after copying from device to library:
     * inserts pack + variant (if absent) and a minimal metadata row when none exists.
     */
    fun registerVariant(packId: String, format: String, storagePath: String) {
        tx.execute {
            if (!packRepository.existsById(packId)) {
                packRepository.save(PackEntity(id = packId))
            }
            val variantId = PackVariantId(packId = packId, format = format)
            if (!variantRepository.existsById(variantId)) {
                variantRepository.save(PackVariantEntity(id = variantId, storagePath = storagePath))
            }
            if (!metadataRepository.existsById(packId)) {
                metadataRepository.save(
                    PackMetadataEntity(
                        packId = packId,
                        version = 1,
                        factoryDisabled = false,
                        nightModeAvailable = false,
                        official = false,
                    )
                )
            }
            Unit
        }
    }

    private fun List<DevicePackEntity>.toDevicePacks(): List<DevicePack> {
        if (isEmpty()) return emptyList()
        val uuids = map { it.id.packUuid }
        val metadata = metadataRepository.findAll()
            .filter { it.packId in uuids }
            .associateBy { it.packId }
        val variants = variantRepository.findAll()
            .filter { it.id.packId in uuids }
        val variantsByPack = variants.groupBy { it.id.packId }
        return map { row ->
            val packUuid = row.id.packUuid
            val meta = metadata[packUuid]
            val thumbnail = meta?.thumbnail ?: resolveThumbnailFromCacheOrFile(packUuid, variantsByPack[packUuid])
            DevicePack(
                uuid = packUuid,
                version = row.version,
                sizeInBytes = row.sizeInBytes,
                title = meta?.title,
                thumbnail = thumbnail,
                locale = meta?.locale,
                ageMin = meta?.ageMin,
                ageMax = meta?.ageMax,
                durationMs = meta?.durationMs,
                storyCount = meta?.storyCount,
            )
        }
    }

    private fun resolveThumbnailFromCacheOrFile(
        packId: String,
        variants: List<PackVariantEntity>?,
    ): String? {
        // 1. Check in-memory cache
        val cached = thumbnailCache.get(packId)
        if (cached != null) {
            return "data:image/png;base64,${Base64.getEncoder().encodeToString(cached)}"
        }

        if (variants.isNullOrEmpty()) return null

        // 2. Try archive variant (zip)
        val archiveVariant = variants.find { it.id.format == "ARCHIVE" }
        if (archiveVariant != null) {
            val pngBytes = runCatching { readThumbnailBytes(Path.of(archiveVariant.storagePath)) }.getOrNull()
            if (pngBytes != null) {
                thumbnailCache.put(packId, pngBytes)
                return "data:image/png;base64,${Base64.getEncoder().encodeToString(pngBytes)}"
            }
        }

        // 3. Try FS variant
        val fsVariant = variants.find { it.id.format == "FS" }
        if (fsVariant != null) {
            val pngBytes = runCatching { extractThumbnailFromFsPack.extractThumbnail(Path.of(fsVariant.storagePath)) }.getOrNull()
            if (pngBytes != null) {
                thumbnailCache.put(packId, pngBytes)
                return "data:image/png;base64,${Base64.getEncoder().encodeToString(pngBytes)}"
            }
        }

        return null
    }
}
