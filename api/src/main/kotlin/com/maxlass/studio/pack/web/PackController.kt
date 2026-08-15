package com.maxlass.studio.pack.web

import com.maxlass.studio.core.api.ApiStatusResponse
import com.maxlass.studio.device.adapter.DriverDeviceConnector
import com.maxlass.studio.pack.cache.ThumbnailCache
import com.maxlass.studio.pack.domain.dto.PackConversionRequest
import com.maxlass.studio.pack.domain.dto.PackConversionResponse
import com.maxlass.studio.pack.domain.dto.PackFilter
import com.maxlass.studio.pack.domain.dto.PagedPacksResponse
import com.maxlass.studio.pack.domain.dto.UpdatePackMetadataCommand
import com.maxlass.studio.pack.domain.dto.UpdatePackMetadataRequest
import com.maxlass.studio.pack.domain.model.PackFormat
import com.maxlass.studio.pack.port.external.ExtractThumbnailFromFsPackPort
import com.maxlass.studio.pack.service.ConvertPackFormatUseCase
import com.maxlass.studio.pack.service.DeletePackFromLibraryUseCase
import com.maxlass.studio.pack.service.GetAllPacksUseCase
import com.maxlass.studio.pack.service.GetPacksPageUseCase
import com.maxlass.studio.pack.service.SyncPacksService
import com.maxlass.studio.pack.service.UpdatePackMetadataUseCase
import com.maxlass.studio.pack.util.findThumbnailEntry
import com.maxlass.studio.settings.service.SettingsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Path
import java.util.zip.ZipFile

private const val DEFAULT_PAGE = 0
private const val DEFAULT_PAGE_SIZE = 50
private const val MAX_PAGE_SIZE = 200

@RestController
@RequestMapping("/packs")
class PackController(
    private val getAllPacks: GetAllPacksUseCase,
    private val getPacksPage: GetPacksPageUseCase,
    private val syncPacks: SyncPacksService,
    private val updatePackMetadata: UpdatePackMetadataUseCase,
    private val deletePackFromLibrary: DeletePackFromLibraryUseCase,
    private val convertPackFormat: ConvertPackFormatUseCase,
    private val settings: SettingsService,
    private val thumbnailCache: ThumbnailCache,
    private val extractThumbnailFromFsPack: ExtractThumbnailFromFsPackPort,
    private val driverDeviceConnector: DriverDeviceConnector,
) {
    private val conversionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @GetMapping
    suspend fun listPacks(
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) official: Boolean?,
        @RequestParam(required = false) locale: String?,
        @RequestParam(required = false) inLibrary: Boolean?,
    ): PagedPacksResponse {
        val safePage = page ?: DEFAULT_PAGE
        val safeSize = (size ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)
        val filter = PackFilter(
            search = search,
            official = official,
            locale = locale,
            inLibrary = inLibrary,
        )
        return getPacksPage.invoke(safePage, safeSize, filter)
    }

    @GetMapping("/all")
    suspend fun listAllPacks() = getAllPacks.invoke()

    @PostMapping("/sync")
    suspend fun startSync(): ResponseEntity<Any> {
        val path = settings.getLibraryPath()
        return runCatching { syncPacks.startSync(path) }
            .fold(
                onSuccess = { ResponseEntity.status(HttpStatus.ACCEPTED).body(it) },
                onFailure = { ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiStatusResponse(ok = false, error = it.message ?: "Pack synchronization failed")) }
            )
    }

    @GetMapping("/sync/{jobId}")
    suspend fun getSyncJobStatus(@PathVariable jobId: Long): ResponseEntity<Any> {
        return syncPacks.getJobStatus(jobId)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiStatusResponse(ok = false, error = "Sync job not found"))
    }

    @DeleteMapping("/{id}")
    suspend fun deletePack(@PathVariable id: String): ResponseEntity<ApiStatusResponse> =
        deletePackFromLibrary.invoke(id)
            .fold(
                onSuccess = { ResponseEntity.ok(ApiStatusResponse(ok = true)) },
                onFailure = { e ->
                    when (e) {
                        is NoSuchElementException -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(ApiStatusResponse(ok = false, error = e.message ?: "Pack not found"))
                        else -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(ApiStatusResponse(ok = false, error = e.message ?: "Delete failed"))
                    }
                }
            )

    @PatchMapping("/{id}/metadata")
    suspend fun updateMetadata(
        @PathVariable id: String,
        @RequestBody body: UpdatePackMetadataRequest,
    ): ResponseEntity<Any> = try {
        ResponseEntity.ok(updatePackMetadata.invoke(UpdatePackMetadataCommand(
            packId = id,
            title = body.title,
            description = body.description,
            linkedOfficialPackId = body.linkedOfficialPackId,
            locale = body.locale,
            ageMin = body.ageMin,
            ageMax = body.ageMax,
            durationMs = body.durationMs,
            storyCount = body.storyCount,
        )))
    } catch (e: NoSuchElementException) {
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiStatusResponse(ok = false, error = e.message ?: "Pack not found"))
    } catch (e: IllegalArgumentException) {
        ResponseEntity.badRequest().body(ApiStatusResponse(ok = false, error = e.message ?: "Invalid request"))
    }

    @PostMapping("/{id}/convert")
    suspend fun convertPack(
        @PathVariable id: String,
        @RequestBody body: PackConversionRequest,
    ): ResponseEntity<PackConversionResponse> {
        driverDeviceConnector.notifyConversion(id, body.sourceFormat.name, body.targetFormat.name, "STARTED")
        conversionScope.launch {
            try {
                convertPackFormat.convert(id, body.sourceFormat, body.targetFormat)
                driverDeviceConnector.notifyConversion(id, body.sourceFormat.name, body.targetFormat.name, "DONE")
            } catch (e: Exception) {
                driverDeviceConnector.notifyConversion(id, body.sourceFormat.name, body.targetFormat.name, "FAILED", e.message)
            }
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            PackConversionResponse(ok = true, packId = id, sourceFormat = body.sourceFormat.name, targetFormat = body.targetFormat.name)
        )
    }

    @GetMapping(value = ["/{id}/thumbnail"], produces = [MediaType.IMAGE_PNG_VALUE, "image/*"])
    suspend fun getThumbnail(@PathVariable id: String): ResponseEntity<Any> {
        thumbnailCache.get(id)?.let {
            return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(it)
        }

        val pack = getAllPacks.invoke().find { it.id == id }
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()

        if (pack.metadata.official && pack.metadata.thumbnail != null) {
            return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, pack.metadata.thumbnail)
                .build()
        }

        val archiveVariant = pack.variants.find { it.format == PackFormat.ARCHIVE }
        if (archiveVariant != null) {
            val pngBytes = readThumbnailFromZip(Path.of(archiveVariant.storagePath))
            if (pngBytes != null) {
                thumbnailCache.put(id, pngBytes)
                return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(pngBytes)
            }
        }

        val fsVariant = pack.variants.find { it.format == PackFormat.FS }
        if (fsVariant != null) {
            val pngBytes = extractThumbnailFromFsPack.extractThumbnail(Path.of(fsVariant.storagePath))
            if (pngBytes != null) {
                thumbnailCache.put(id, pngBytes)
                return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(pngBytes)
            }
        }

        return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
    }

    @PatchMapping("/{id}/thumbnail")
    suspend fun uploadThumbnail(@PathVariable id: String, @RequestBody bytes: ByteArray): ResponseEntity<Any> {
        thumbnailCache.put(id, bytes)
        runCatching {
            updatePackMetadata.invoke(UpdatePackMetadataCommand(
                packId = id, title = null, description = null,
                linkedOfficialPackId = null, thumbnailPngBytes = bytes
            ))
        }
        return ResponseEntity.ok().build()
    }

    private fun readThumbnailFromZip(zipPath: Path): ByteArray? =
        runCatching {
            ZipFile(zipPath.toFile()).use { zf ->
                val thumbEntry = findThumbnailEntry(zf) ?: return@use null
                val bytes = zf.getInputStream(thumbEntry).use { it.readBytes() }
                if (bytes.isEmpty()) null else bytes
            }
        }.getOrNull()
}
