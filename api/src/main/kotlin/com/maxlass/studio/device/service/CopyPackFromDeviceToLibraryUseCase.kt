package com.maxlass.studio.device.service

import com.maxlass.studio.device.domain.result.CopyPackFromDeviceToLibraryResult
import com.maxlass.studio.device.port.CopyPackFromDeviceToLibraryPort
import com.maxlass.studio.pack.service.RefreshOfficialMetadataUseCase
import com.maxlass.studio.pack.service.SyncPacksService
import com.maxlass.studio.settings.service.SettingsService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Copies a pack from the connected Lunii device to the local library, then refreshes official
 * metadata (so official packs get their thumbnail) and syncs the library so the pack appears in the list.
 */
@Service
class CopyPackFromDeviceToLibraryUseCase(
    private val copyFromDevicePort: CopyPackFromDeviceToLibraryPort,
    private val settingsService: SettingsService,
    private val syncPacksService: SyncPacksService,
    private val refreshOfficialMetadataUseCase: RefreshOfficialMetadataUseCase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        private val log = LoggerFactory.getLogger(CopyPackFromDeviceToLibraryUseCase::class.java)
    }

    suspend operator fun invoke(packId: String): CopyPackFromDeviceToLibraryResult {
        val libraryPath = settingsService.getLibraryPath()
        val result = copyFromDevicePort.copyFromDeviceToLibrary(packId, libraryPath)
        if (result is CopyPackFromDeviceToLibraryResult.Success) {
            runCatching {
                withContext(ioDispatcher) { refreshOfficialMetadataUseCase.invoke() }
            }.onFailure { log.warn("Refresh official metadata after copy failed", it) }
            syncPacksService.invoke(libraryPath)
        }
        return result
    }
}
