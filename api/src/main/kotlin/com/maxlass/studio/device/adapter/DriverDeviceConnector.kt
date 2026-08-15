package com.maxlass.studio.device.adapter

import com.maxlass.studio.device.domain.dto.ConversionEvent
import com.maxlass.studio.device.domain.model.DeviceInfos
import com.maxlass.studio.device.domain.model.DevicePack
import com.maxlass.studio.device.domain.model.DeviceSnapshot
import com.maxlass.studio.device.domain.model.DeviceStorage
import com.maxlass.studio.device.domain.result.CopyPackFromDeviceToLibraryResult
import com.maxlass.studio.device.domain.result.CopyPackToDeviceResult
import com.maxlass.studio.device.domain.result.DeletePackFromDeviceResult
import com.maxlass.studio.device.driver.DeviceHotplugListener
import com.maxlass.studio.device.driver.FsDeviceInfos
import com.maxlass.studio.device.driver.FsStoryTellerDriver
import com.maxlass.studio.device.driver.LuniiDeviceKind
import com.maxlass.studio.device.driver.LuniiUsb
import com.maxlass.studio.device.driver.RawDeviceInfos
import com.maxlass.studio.device.driver.RawStoryTellerDriver
import com.maxlass.studio.device.driver.UsbMassStorage
import com.maxlass.studio.device.port.CopyPackFromDeviceToLibraryPort
import com.maxlass.studio.device.port.CopyPackToDevicePort
import com.maxlass.studio.device.port.DeletePackFromDevicePort
import com.maxlass.studio.device.port.GetDeviceInfosPort
import com.maxlass.studio.infrastructure.persistence.DevicePackRepository
import com.maxlass.studio.pack.domain.PACK_EXT_RAW
import com.maxlass.studio.pack.domain.model.Pack
import com.maxlass.studio.pack.domain.model.PackFormat
import com.maxlass.studio.pack.domain.model.PackVariant
import com.maxlass.studio.pack.format.utils.BytesUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import jakarta.annotation.PreDestroy

private const val DRIVER_LABEL_RAW = "raw"
private const val DRIVER_LABEL_FS = "fs"
private const val ORPHAN_CLEANUP_MAX_ATTEMPTS = 5
private const val ORPHAN_CLEANUP_RETRY_DELAY_MILLIS = 300L

/**
 * Device detection connector over the coroutine-based [RawStoryTellerDriver] and
 * [FsStoryTellerDriver], with hotplug events from [LuniiUsb]. Persistence of device packs
 * goes through [DevicePackRepository] (no direct table access).
 */
@Component
class DriverDeviceConnector(
    private val devicePackRepository: DevicePackRepository,
    private val luniiUsb: LuniiUsb = LuniiUsb(),
    private val usbMassStorage: UsbMassStorage = UsbMassStorage(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GetDeviceInfosPort, CopyPackToDevicePort, DeletePackFromDevicePort, CopyPackFromDeviceToLibraryPort {

    private val rawDriver = RawStoryTellerDriver(usbMassStorage)
    private val fsDriver = FsStoryTellerDriver()

    companion object {
        private val logger = LoggerFactory.getLogger(DriverDeviceConnector::class.java)
    }

    private val _deviceState = MutableStateFlow(DeviceInfos(plugged = false))
    /** Current device state, updated when a Lunii is plugged or unplugged (hotplug events). */
    val deviceState: StateFlow<DeviceInfos> = _deviceState.asStateFlow()

    private val _onDeviceChanged = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 10)
    /** Emitted when device state changes (hotplug, copy, delete). SSE clients collect this flow. */
    val onDeviceChanged: SharedFlow<Unit> = _onDeviceChanged.asSharedFlow()

    /** Forces a notification to SSE clients that the device state has changed. */
    fun notifyDeviceChanged() {
        _onDeviceChanged.tryEmit(Unit)
    }

    private val _lastConversion = MutableStateFlow<ConversionEvent?>(null)
    val lastConversion: StateFlow<ConversionEvent?> = _lastConversion.asStateFlow()

    fun notifyConversion(packId: String, sourceFormat: String, targetFormat: String, status: String, message: String? = null) {
        _lastConversion.value = ConversionEvent(packId, sourceFormat, targetFormat, status, message)
        _onDeviceChanged.tryEmit(Unit)
    }

    init {
        luniiUsb.registerListener(LuniiDeviceKind.RAW, DeviceHotplugListener { plugged, device ->
            if (plugged) {
                scope.launch {
                    runCatching {
                        rawDriver.onPlugged(device!!)
                        mapRawToDeviceInfos(rawDriver.getDeviceInfos())
                    }.fold(
                        onSuccess = { updateDeviceState(it) },
                        onFailure = { logger.warn("Impossible de lire les infos Lunii raw: {}", it.message) }
                    )
                }
            } else {
                rawDriver.onUnplugged()
                clearDeviceState()
            }
        })
        luniiUsb.registerListener(LuniiDeviceKind.FS, DeviceHotplugListener { plugged, _ ->
            if (plugged) {
                scope.launch {
                    runCatching {
                        fsDriver.onPlugged()
                        mapFsToDeviceInfos(fsDriver.getDeviceInfos())
                    }.fold(
                        onSuccess = { updateDeviceState(it) },
                        onFailure = { logger.warn("Impossible de lire les infos Lunii fs: {}", it.message) }
                    )
                }
            } else {
                fsDriver.onUnplugged()
                clearDeviceState()
            }
        })
        luniiUsb.start()
    }

    @PreDestroy
    fun shutdown() {
        luniiUsb.stop()
    }

    private fun updateDeviceState(infos: DeviceInfos) {
        _deviceState.value = infos
        infos.uuid?.let { uuid ->
            scope.launch { runCatching { scanAndUpsertDevicePacks(uuid) }.onFailure { logger.warn("Rescan after plug failed: {}", it.message) } }
        }
        _onDeviceChanged.tryEmit(Unit)
        logger.info("Lunii détectée: firmware {}, serial {}", infos.firmware, infos.serial)
    }

    private fun clearDeviceState() {
        _deviceState.value = DeviceInfos(plugged = false)
        _onDeviceChanged.tryEmit(Unit)
    }

    override suspend fun getDeviceInfos(): DeviceInfos = _deviceState.value

    override suspend fun copyPackToDevice(pack: Pack): CopyPackToDeviceResult = withContext(ioDispatcher) {
        val state = _deviceState.value
        if (!state.plugged) return@withContext CopyPackToDeviceResult.DeviceNotPlugged
        val existingPacks = getDevicePacksFromDriver()
        if (existingPacks.any { it.uuid == pack.id }) return@withContext CopyPackToDeviceResult.PackAlreadyOnDevice
        val result = when (state.driver) {
            DRIVER_LABEL_RAW -> copyPackRaw(pack)
            DRIVER_LABEL_FS -> copyPackFs(pack)
            else -> CopyPackToDeviceResult.Error("Type de device inconnu: ${state.driver}")
        }
        if (result is CopyPackToDeviceResult.Success) {
            state.uuid?.let { scanAndUpsertDevicePacks(it) }
        }
        result
    }

    private suspend fun copyPackRaw(pack: Pack): CopyPackToDeviceResult {
        val variant = findVariant(pack, PackFormat.RAW)
            ?: return CopyPackToDeviceResult.FormatIncompatible(
                "Lunii 1.x (raw) accepte uniquement le format RAW (.pack). Variantes disponibles: ${availableFormats(pack)}."
            )
        val file = File(variant.storagePath)
        if (!file.isFile) return CopyPackToDeviceResult.Error("Fichier pack introuvable: ${variant.storagePath}")
        val sizeBytes = file.length()
        val sectors = ((sizeBytes + UsbMassStorage.SECTOR_SIZE - 1) / UsbMassStorage.SECTOR_SIZE).toInt()
        return runCatching {
            FileInputStream(file).use { input -> rawDriver.uploadPack(input, sectors) }
            CopyPackToDeviceResult.Success
        }.getOrElse { CopyPackToDeviceResult.Error(it.message ?: "Erreur copie raw") }
    }

    private suspend fun copyPackFs(pack: Pack): CopyPackToDeviceResult {
        val variant = findVariant(pack, PackFormat.FS)
            ?: return CopyPackToDeviceResult.FormatIncompatible(
                "Lunii 2.x (FS) accepte uniquement le format dossier (FS). Variantes disponibles: ${availableFormats(pack)}."
            )
        val dir = File(variant.storagePath)
        if (!dir.isDirectory) return CopyPackToDeviceResult.Error("Dossier pack introuvable: ${variant.storagePath}")
        return runCatching {
            fsDriver.uploadPack(pack.id, variant.storagePath)
            CopyPackToDeviceResult.Success
        }.getOrElse { CopyPackToDeviceResult.Error(it.message ?: "Erreur copie FS") }
    }

    private fun findVariant(pack: Pack, format: PackFormat): PackVariant? =
        pack.variants.firstOrNull { it.format == format }

    private fun availableFormats(pack: Pack): String =
        pack.variants.map { it.format.name }.distinct().ifEmpty { listOf(PackFormat.UNKNOWN.name) }.joinToString(", ")

    override suspend fun deletePackFromDevice(packId: String): DeletePackFromDeviceResult = withContext(ioDispatcher) {
        val state = _deviceState.value
        if (!state.plugged) return@withContext DeletePackFromDeviceResult.DeviceNotPlugged
        val existingPacks = getDevicePacksFromDriver()
        if (existingPacks.none { it.uuid == packId }) return@withContext DeletePackFromDeviceResult.PackNotFoundOnDevice
        val result = when (state.driver) {
            DRIVER_LABEL_RAW -> deletePackRaw(packId)
            DRIVER_LABEL_FS -> deletePackFs(packId)
            else -> DeletePackFromDeviceResult.Error("Type de device inconnu: ${state.driver}")
        }
        if (result is DeletePackFromDeviceResult.Success) {
            state.uuid?.let { scanAndUpsertDevicePacks(it) }
        }
        result
    }

    private suspend fun deletePackRaw(packId: String): DeletePackFromDeviceResult = runCatching {
        if (rawDriver.deletePack(packId)) {
            DeletePackFromDeviceResult.Success
        } else {
            DeletePackFromDeviceResult.Error("Suppression raw a échoué")
        }
    }.getOrElse { mapDeleteDriverFailure(it) }

    private suspend fun deletePackFs(packId: String): DeletePackFromDeviceResult = runCatching {
        fsDriver.deletePack(packId)
        DeletePackFromDeviceResult.Success
    }.getOrElse {
        val cause = it.cause ?: it
        val msg = cause.message ?: ""
        // Le driver a déjà mis à jour l'index .pi (le pack n'apparaît plus côté device), mais la
        // suppression du dossier .content/<folderName> a échoué (bug macOS fskit msdos : transitoire).
        if ("Failed to delete pack folder on device partition" in msg && cleanupFsOrphanFolder(packId)) {
            DeletePackFromDeviceResult.Success
        } else {
            mapDeleteDriverFailure(it)
        }
    }

    /**
     * Le driver a déjà mis à jour l'index `.pi` mais a échoué à supprimer le dossier
     * `.content/<folderName>` (bug macOS fskit msdos transitoire). On localise le dossier
     * orphelin dans les points de montage typiques et on retente la suppression.
     */
    private fun cleanupFsOrphanFolder(uuid: String): Boolean {
        val folderName = runCatching { fsDriver.computePackFolderName(uuid) }.getOrNull() ?: return false
        val orphan = findFsContentFolder(folderName) ?: run {
            logger.warn("Could not locate orphan FS pack folder for uuid {} (expected .content/{})", uuid, folderName)
            return false
        }
        logger.warn("Cleaning up orphan FS pack folder: {}", orphan.absolutePath)
        repeat(ORPHAN_CLEANUP_MAX_ATTEMPTS) { attempt ->
            if (deleteRecursivelyWithRetry(orphan)) return true
            logger.debug("Orphan folder cleanup attempt {} failed, retrying...", attempt + 1)
            runCatching { Thread.sleep(ORPHAN_CLEANUP_RETRY_DELAY_MILLIS) }
        }
        val ok = !orphan.exists()
        if (!ok) logger.error("Failed to clean up orphan FS pack folder: {}", orphan.absolutePath)
        return ok
    }

    private fun findFsContentFolder(folderName: String): File? {
        val user = runCatching { System.getProperty("user.name") }.getOrNull()
        val roots = listOfNotNull(
            File("/Volumes").takeIf { it.isDirectory },
            user?.let { File("/media/$it") }?.takeIf { it.isDirectory },
            user?.let { File("/run/media/$it") }?.takeIf { it.isDirectory },
            File("/mnt").takeIf { it.isDirectory }
        )
        return roots.asSequence()
            .flatMap { (it.listFiles() ?: emptyArray()).asSequence() }
            .map { File(it, ".content/$folderName") }
            .firstOrNull { it.isDirectory }
    }

    private fun deleteRecursivelyWithRetry(dir: File): Boolean = try {
        if (dir.exists()) dir.deleteRecursively()
        !dir.exists()
    } catch (e: Exception) {
        logger.debug("Recursive delete failed for {}: {}", dir, e.message)
        false
    }

    private fun mapDeleteDriverFailure(t: Throwable): DeletePackFromDeviceResult {
        val cause = t.cause ?: t
        val msg = cause.message?.lowercase() ?: ""
        return when {
            "no device plugged" in msg -> DeletePackFromDeviceResult.DeviceNotPlugged
            "pack not found" in msg -> DeletePackFromDeviceResult.PackNotFoundOnDevice
            else -> DeletePackFromDeviceResult.Error(cause.message ?: t.toString())
        }
    }

    override suspend fun copyFromDeviceToLibrary(packId: String, libraryPath: String): CopyPackFromDeviceToLibraryResult = withContext(ioDispatcher) {
        val state = _deviceState.value
        if (!state.plugged) return@withContext CopyPackFromDeviceToLibraryResult.DeviceNotPlugged
        val existingPacks = getDevicePacksFromDriver()
        if (existingPacks.none { it.uuid == packId }) return@withContext CopyPackFromDeviceToLibraryResult.PackNotFoundOnDevice
        when (state.driver) {
            DRIVER_LABEL_RAW -> downloadPackRawToLibrary(packId, libraryPath)
            DRIVER_LABEL_FS -> downloadPackFsToLibrary(packId, libraryPath)
            else -> CopyPackFromDeviceToLibraryResult.Error("Type de device inconnu: ${state.driver}")
        }
    }

    private suspend fun downloadPackRawToLibrary(packId: String, libraryPath: String): CopyPackFromDeviceToLibraryResult {
        val dir = File(libraryPath)
        if (!dir.exists()) dir.mkdirs()
        val destFile = File(dir, "$packId.$PACK_EXT_RAW")
        if (destFile.exists()) return CopyPackFromDeviceToLibraryResult.Error("Un pack avec cet ID existe déjà dans la bibliothèque.")
        return runCatching {
            FileOutputStream(destFile).use { out -> rawDriver.downloadPack(packId, out) }
            devicePackRepository.registerVariant(packId, PackFormat.RAW.name, destFile.absolutePath)
        }.fold(
            onSuccess = { CopyPackFromDeviceToLibraryResult.Success },
            onFailure = { mapDownloadDriverFailure(it) }
        )
    }

    private suspend fun downloadPackFsToLibrary(packId: String, libraryPath: String): CopyPackFromDeviceToLibraryResult {
        val dir = File(libraryPath)
        if (!dir.exists()) dir.mkdirs()
        val destDir = File(dir, packId)
        if (destDir.exists()) return CopyPackFromDeviceToLibraryResult.Error("Un pack avec cet ID existe déjà dans la bibliothèque.")
        return runCatching {
            fsDriver.downloadPack(packId, dir.absolutePath)
            devicePackRepository.registerVariant(packId, PackFormat.FS.name, destDir.absolutePath)
        }.fold(
            onSuccess = { CopyPackFromDeviceToLibraryResult.Success },
            onFailure = { mapDownloadDriverFailure(it) }
        )
    }

    private fun mapDownloadDriverFailure(t: Throwable): CopyPackFromDeviceToLibraryResult {
        val cause = t.cause ?: t
        val msg = cause.message?.lowercase() ?: ""
        return when {
            "no device plugged" in msg -> CopyPackFromDeviceToLibraryResult.DeviceNotPlugged
            "pack not found" in msg -> CopyPackFromDeviceToLibraryResult.PackNotFoundOnDevice
            else -> CopyPackFromDeviceToLibraryResult.Error(cause.message ?: t.toString())
        }
    }

    /**
     * Returns the list of packs on the currently connected device from the device_packs table.
     * Fast read, no driver call. Re-populated on each plug.
     */
    suspend fun getDevicePacks(): List<DevicePack> = withContext(ioDispatcher) {
        val state = _deviceState.value
        if (!state.plugged || state.uuid == null) return@withContext emptyList()
        getDevicePacksByUuid(state.uuid)
    }

    /**
     * Returns packs for a specific device UUID from the device_packs table (historical).
     */
    suspend fun getDevicePacksByUuid(deviceUuid: String): List<DevicePack> = withContext(ioDispatcher) {
        devicePackRepository.getDevicePacksByUuid(deviceUuid)
    }

    /**
     * Returns one snapshot per known device UUID, sorted by most-recently-seen first.
     * Self-healing: if a device is currently plugged, refreshes its row in the device_packs table
     * before reading (covers the case where the hotplug scan timed out at plug time).
     */
    suspend fun getDeviceSnapshots(): List<DeviceSnapshot> {
        val state = _deviceState.value
        if (state.plugged && state.uuid != null) {
            runCatching { scanAndUpsertDevicePacks(state.uuid) }
                .onFailure { logger.warn("Rescan on snapshot read failed: {}", it.message) }
        }
        return withContext(ioDispatcher) {
            devicePackRepository.getDeviceSnapshots()
        }
    }

    /**
     * Reads packs directly from the driver. Used internally for real-time checks during copy/delete.
     */
    private suspend fun getDevicePacksFromDriver(): List<DevicePack> = withContext(ioDispatcher) {
        val state = _deviceState.value
        if (!state.plugged) return@withContext emptyList()
        when (state.driver) {
            DRIVER_LABEL_RAW -> runCatching {
                rawDriver.getPacksList().map { raw ->
                    DevicePack(uuid = raw.uuid?.toString() ?: "", version = raw.version, sizeInBytes = raw.sizeInSectors * UsbMassStorage.SECTOR_SIZE.toLong())
                }
            }.getOrElse { logger.warn("Impossible de lire les packs Lunii raw: {}", it.message); emptyList() }
            DRIVER_LABEL_FS -> runCatching {
                fsDriver.getPacksList().map { fs ->
                    DevicePack(uuid = fs.uuid.toString(), version = fs.version, sizeInBytes = fs.sizeInBytes)
                }
            }.getOrElse { logger.warn("Impossible de lire les packs Lunii fs: {}", it.message); emptyList() }
            else -> emptyList()
        }
    }

    private suspend fun scanAndUpsertDevicePacks(deviceUuid: String) {
        val packs = getDevicePacksFromDriver()
        logger.info("scanAndUpsertDevicePacks({}): {} pack(s) lus depuis le driver", deviceUuid, packs.size)
        withContext(ioDispatcher) {
            devicePackRepository.scanAndUpsertDevicePacks(deviceUuid, packs)
        }
        logger.info("scanAndUpsertDevicePacks({}): terminé, {} ligne(s) écrite(s)", deviceUuid, packs.size)
    }

    private fun mapRawToDeviceInfos(infos: RawDeviceInfos): DeviceInfos {
        val firmware = if (infos.firmwareMajor.toInt() == -1) null
        else "${infos.firmwareMajor}.${infos.firmwareMinor}"
        val sizeInBytes = infos.sdCardSizeInSectors * UsbMassStorage.SECTOR_SIZE.toLong()
        val takenInBytes = infos.usedSpaceInSectors * UsbMassStorage.SECTOR_SIZE.toLong()
        return DeviceInfos(
            plugged = true,
            uuid = infos.uuid?.toString(),
            serial = infos.serialNumber,
            firmware = firmware,
            driver = DRIVER_LABEL_RAW,
            storage = DeviceStorage(
                size = sizeInBytes,
                free = sizeInBytes - takenInBytes,
                taken = takenInBytes
            ),
            error = infos.inError
        )
    }

    private fun mapFsToDeviceInfos(infos: FsDeviceInfos): DeviceInfos {
        val firmware = "${infos.firmwareMajor}.${infos.firmwareMinor}"
        val uuidString = infos.uuid?.let { BytesUtils.toHexString(it) }
        return DeviceInfos(
            plugged = true,
            uuid = uuidString,
            serial = infos.serialNumber,
            firmware = firmware,
            driver = DRIVER_LABEL_FS,
            storage = DeviceStorage(
                size = infos.sdCardSizeInBytes,
                free = infos.sdCardSizeInBytes - infos.usedSpaceInBytes,
                taken = infos.usedSpaceInBytes
            ),
            error = false
        )
    }
}
