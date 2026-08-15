package com.maxlass.studio.infrastructure.persistence

import com.maxlass.studio.device.domain.model.DevicePack
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

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
        return map { row ->
            val meta = metadata[row.id.packUuid]
            DevicePack(
                uuid = row.id.packUuid,
                version = row.version,
                sizeInBytes = row.sizeInBytes,
                title = meta?.title,
                thumbnail = meta?.thumbnail,
                locale = meta?.locale,
                ageMin = meta?.ageMin,
                ageMax = meta?.ageMax,
                durationMs = meta?.durationMs,
                storyCount = meta?.storyCount,
            )
        }
    }
}
