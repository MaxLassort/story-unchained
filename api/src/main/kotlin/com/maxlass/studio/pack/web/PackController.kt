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
import com.maxlass.studio.pack.service.SyncAlreadyRunningException
import com.maxlass.studio.pack.service.SyncPacksService
import com.maxlass.studio.pack.service.UpdatePackMetadataUseCase
import com.maxlass.studio.pack.util.readThumbnailBytes
import com.maxlass.studio.settings.service.SettingsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import jakarta.annotation.PreDestroy
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

private const val DEFAULT_PAGE = 0
private const val DEFAULT_PAGE_SIZE = 50
private const val MAX_PAGE_SIZE = 200

@RestController
@RequestMapping("/packs")
@Tag(name = "Packs", description = "Bibliothèque de packs : liste, synchronisation, " +
    "métadonnées, suppression, conversion de format et thumbnails.")
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
    private val syncSseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @PreDestroy
    fun shutdown() {
        conversionScope.cancel("PackController shutting down")
        syncSseScope.cancel("PackController shutting down")
    }

    @Operation(
        summary = "Lister les packs (paginé)",
        description = "Retourne une page de packs avec filtres optionnels (texte, officiel, " +
            "langue, présence en bibliothèque).",
    )
    @GetMapping
    suspend fun listPacks(
        @Parameter(description = "Index de page (0-based). Défaut : 0")
        @RequestParam(required = false) page: Int?,
        @Parameter(description = "Taille de page (1-200). Défaut : 50")
        @RequestParam(required = false) size: Int?,
        @Parameter(description = "Filtre texte sur le titre")
        @RequestParam(required = false) search: String?,
        @Parameter(description = "Filtrer les packs officiels (true) ou non officiels (false)")
        @RequestParam(required = false) official: Boolean?,
        @Parameter(description = "Filtrer par langue (ex. \"fr\", \"en\")")
        @RequestParam(required = false) locale: String?,
        @Parameter(description = "Filtrer par présence dans la bibliothèque")
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

    @Operation(
        summary = "Tous les packs",
        description = "Liste brute de tous les packs connus (officiels + bibliothèque + appareils), " +
            "sans pagination ni filtre.",
    )
    @GetMapping("/all")
    suspend fun listAllPacks() = getAllPacks.invoke()

    @Operation(
        summary = "Synchroniser la bibliothèque",
        description = "Lance un scan du dossier bibliothèque configuré dans les settings " +
            "(détection des packs déposés/supprimés). Retourne 202 sans jobId ; la progression " +
            "est désormais disponible via le flux SSE /packs/sync/events.",
    )
    @ApiResponse(responseCode = "202", description = "Synchronisation démarrée")
    @ApiResponse(responseCode = "409", description = "Une synchronisation est déjà active")
    @ApiResponse(responseCode = "500", description = "Échec du démarrage")
    @PostMapping("/sync")
    suspend fun startSync(): ResponseEntity<Any> {
        val path = settings.getLibraryPath()
        return runCatching { syncPacks.startSync(path) }
            .fold(
                onSuccess = { ResponseEntity.status(HttpStatus.ACCEPTED).build() },
                onFailure = { e ->
                    when (e) {
                        is SyncAlreadyRunningException ->
                            ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(ApiStatusResponse(ok = false, error = e.message ?: "Sync already running"))
                        else ->
                            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body(ApiStatusResponse(ok = false, error = e.message ?: "Pack synchronization failed"))
                    }
                }
            )
    }

    @Operation(
        summary = "Progression de la synchronisation (SSE)",
        description = "Flux Server-Sent Events de la synchronisation en cours. Chaque événement " +
            "est un objet SyncStatusEvent (PENDING, RUNNING, DONE, FAILED). Ce flux remplace " +
            "l'ancien endpoint /packs/sync/{jobId}.",
    )
    @ApiResponse(responseCode = "200", description = "Flux SSE (text/event-stream)")
    @GetMapping(value = ["/sync/events"], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun syncEvents() = SseFlowEmitter.fromFlow(
        events = syncPacks.eventPublisher().sharedEvents,
        serializer = com.maxlass.studio.pack.domain.dto.SyncStatusEvent.serializer(),
        scope = syncSseScope,
    )

    @Operation(
        summary = "Supprimer un pack",
        description = "Supprime le pack (id = UUID) de la bibliothèque et de la base.",
    )
    @ApiResponse(responseCode = "200", description = "Pack supprimé")
    @ApiResponse(responseCode = "404", description = "Pack inconnu")
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

    @Operation(
        summary = "Modifier les métadonnées d'un pack",
        description = "Met à jour les champs fournis (objet partiel). Champs disponibles : " +
            "title, description, linkedOfficialPackId, locale, ageMin, ageMax, durationMs, storyCount.",
    )
    @ApiResponse(responseCode = "200", description = "Métadonnées mises à jour", content = [
        Content(examples = [
            ExampleObject(name = "Exemple", value = """
                {
                  "title": "Mon histoire",
                  "description": "Une histoire créée avec StoryUnchained",
                  "locale": "fr",
                  "ageMin": 3,
                  "ageMax": 6,
                  "durationMs": 600000,
                  "storyCount": 5
                }
            """)
        ])
    ])
    @ApiResponse(responseCode = "400", description = "Requête invalide")
    @ApiResponse(responseCode = "404", description = "Pack inconnu")
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

    @Operation(
        summary = "Convertir un pack (asynchrone)",
        description = "Lance la conversion du pack vers un autre format. Retourne 202 immédiatement ; " +
            "la progression et le résultat sont visibles dans le flux SSE /devices/events " +
            "(champ \"conversion\"). Formats : ARCHIVE (zip STUdio), RAW (binaire), FS (système de fichiers).",
    )
    @ApiResponse(responseCode = "202", description = "Conversion démarrée", content = [
        Content(examples = [
            ExampleObject(name = "Exemple", value = """
                {
                  "ok": true,
                  "packId": "<uuid>",
                  "sourceFormat": "ARCHIVE",
                  "targetFormat": "FS"
                }
            """)
        ])
    ])
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

    @Operation(
        summary = "Thumbnail d'un pack",
        description = "Retourne l'image PNG du pack. Résolution : cache mémoire → redirection " +
            "vers l'URL officielle (302) si officiel → meta/thumbnail.png du zip → extraction du pack FS.",
    )
    @ApiResponse(responseCode = "200", description = "Image PNG", content = [Content(mediaType = "image/png")])
    @ApiResponse(responseCode = "302", description = "Redirection vers l'URL officielle")
    @ApiResponse(responseCode = "404", description = "Pack inconnu ou thumbnail introuvable")
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

    @Operation(
        summary = "Uploader un thumbnail",
        description = "Enregistre le thumbnail d'un pack. Le corps doit être les **bytes bruts de " +
            "l'image** (pas de multipart, pas de JSON) avec Content-Type: image/png (ou image/*). " +
            "L'image est cachée et écrite dans le zip du pack.",
    )
    @ApiResponse(responseCode = "200", description = "Thumbnail enregistré")
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
        readThumbnailBytes(zipPath)
}
