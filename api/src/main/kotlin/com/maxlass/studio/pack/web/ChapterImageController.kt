package com.maxlass.studio.pack.web

import com.maxlass.studio.pack.domain.dto.ChapterIconsResponse
import com.maxlass.studio.pack.format.utils.ChapterImageGenerator
import com.maxlass.studio.pack.format.utils.SvgIconRenderer
import com.maxlass.studio.pack.service.ChapterIconCatalogService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

/**
 * Chapter image generation: preview of bundled Lucide icons or of the generated chapter
 * number, and SVG → PNG 320x240 rendering (immediate conversion with visual feedback).
 */
@RestController
@RequestMapping("/stories/images")
@Tag(name = "Stories - Images", description = "Génération d'images de chapitre pour la création " +
    "d'histoires : icônes Lucide embarquées/recherchables et conversion SVG → PNG 320×240 " +
    "blanc sur fond noir.")
class ChapterImageController(
    private val iconCatalog: ChapterIconCatalogService,
) {

    @Operation(
        summary = "Icônes Lucide par défaut",
        description = "Liste les 50 premières icônes du catalogue Lucide complet (ordre " +
            "alphabétique, catalogue fetché depuis jsDelivr et caché 24 h ; fallback sur les " +
            "slugs connus si l'API est injoignable). L'id est le slug kebab-case Lucide " +
            "(ex. \"star\"). Utilise /icons/search pour chercher dans les ~2000 icônes.",
    )
    @ApiResponse(responseCode = "200", description = "Liste des icônes")
    @GetMapping("/icons")
    suspend fun icons(): ChapterIconsResponse = ChapterIconsResponse(iconCatalog.defaultIcons())

    @Operation(
        summary = "Rechercher des icônes Lucide",
        description = "Recherche dans le catalogue complet Lucide (~2000 icônes, catalogue " +
            "fetché depuis jsDelivr et caché 24 h). q doit faire au moins 2 caractères ; " +
            "max 50 résultats, icônes embarquées en premier.",
    )
    @ApiResponse(responseCode = "200", description = "Résultats de la recherche")
    @ApiResponse(responseCode = "400", description = "q trop court (< 2 caractères)")
    @GetMapping("/icons/search")
    suspend fun searchIcons(
        @Parameter(description = "Terme de recherche (≥ 2 caractères)")
        @RequestParam(required = false) q: String?,
    ): ChapterIconsResponse {
        val query = q?.trim().orEmpty()
        if (query.length < 2) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Query 'q' must be at least 2 characters")
        }
        return ChapterIconsResponse(iconCatalog.searchIcons(query))
    }

    @Operation(
        summary = "Prévisualiser une image de chapitre",
        description = "Rend une image PNG 320×240 blanc sur fond noir : soit une icône Lucide " +
            "(iconId, embarquée ou fetchée à la volée depuis le CDN), soit un chiffre de " +
            "chapitre généré (chapterNumber). Un seul des deux paramètres doit être fourni.",
    )
    @ApiResponse(responseCode = "200", description = "Image PNG", content = [Content(mediaType = "image/png")])
    @ApiResponse(responseCode = "400", description = "Aucun (ou les deux) paramètre fourni")
    @ApiResponse(responseCode = "404", description = "Icône inconnue")
    @GetMapping("/preview")
    suspend fun preview(
        @Parameter(description = "Slug de l'icône Lucide (ex. \"star\", \"moon-star\")")
        @RequestParam(required = false) iconId: String?,
        @Parameter(description = "Numéro de chapitre (ex. 1 pour afficher \"1\")")
        @RequestParam(required = false) chapterNumber: Int?,
    ): ResponseEntity<ByteArray> {
        val png = when {
            iconId != null -> {
                val svg = iconCatalog.loadIcon(iconId)
                    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown icon: $iconId")
                SvgIconRenderer.render(svg)
            }
            chapterNumber != null -> ChapterImageGenerator.generate(chapterNumber)
            else -> throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Exactly one of 'iconId' or 'chapterNumber' is required",
            )
        }
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .body(png)
    }

@Operation(
        summary = "Convertir un SVG en image",
        description = "Upload multipart (paramètre \"file\", fichier .svg ou " +
            "Content-Type image/svg+xml) → PNG 320×240 converti immédiatement, pour un " +
            "retour visuel direct lors de l'upload d'une icône personnelle.",
    )
    @ApiResponse(responseCode = "200", description = "Image PNG", content = [Content(mediaType = "image/png")])
    @ApiResponse(responseCode = "400", description = "Fichier vide, non-SVG, ou SVG invalide")
    @PostMapping(value = ["/render"], consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun render(
        @Parameter(
            description = "Fichier SVG à convertir",
            schema = Schema(type = "string", format = "binary"),
        )
        @RequestPart("file") file: MultipartFile,
    ): ResponseEntity<ByteArray> {
        if (file.isEmpty) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "SVG file is empty")
        }
        if (!(file.originalFilename?.lowercase()?.endsWith(".svg") == true ||
                file.contentType?.lowercase() == "image/svg+xml")
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Only SVG files are accepted")
        }
        val svg = String(file.bytes, Charsets.UTF_8)
        val png = try {
            SvgIconRenderer.render(svg)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid SVG: ${e.message}")
        }
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .body(png)
    }
}