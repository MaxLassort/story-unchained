package com.maxlass.studio.pack.web

import com.maxlass.studio.core.api.ApiStatusResponse
import com.maxlass.studio.pack.domain.dto.PackFilter
import com.maxlass.studio.pack.domain.dto.PagedPacksResponse
import com.maxlass.studio.pack.service.ListOfficialPacksUseCase
import com.maxlass.studio.pack.service.RefreshOfficialMetadataUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/metadata")
@Tag(name = "Métadonnées officielles", description = "Base officielle Lunii (catalogue officiel).")
class MetadataController(
    private val refreshMetadata: RefreshOfficialMetadataUseCase,
    private val listOfficialPacks: ListOfficialPacksUseCase,
) {

    @Operation(
        summary = "Rafraîchir les métadonnées officielles",
        description = "Re-télécharge la base officielle Lunii (packages officiels) puis " +
            "resynchronise les packs connus. Retourne {\"ok\": true}.",
    )
    @ApiResponse(responseCode = "200", description = "Métadonnées rafraîchies")
    @PostMapping("/refresh")
    suspend fun refreshOfficial(): ResponseEntity<ApiStatusResponse> {
        refreshMetadata.invoke()
        return ResponseEntity.ok(ApiStatusResponse(ok = true, message = "Official metadata refreshed"))
    }

    @Operation(
        summary = "Lister le catalogue officiel (JSON)",
        description = "Retourne les packs du JSON officiel (paginé, filtrable). La base de données " +
            "affichée est le JSON officiel : les packs n'ont pas de variante de bibliothèque.",
    )
    @ApiResponse(responseCode = "200", description = "Page du catalogue officiel")
    @GetMapping("/official")
    suspend fun listOfficial(
        @Parameter(description = "Index de page (0-based). Défaut : 0")
        @RequestParam(required = false) page: Int?,
        @Parameter(description = "Taille de page (1-200). Défaut : 50")
        @RequestParam(required = false) size: Int?,
        @Parameter(description = "Filtre texte sur le titre (au moins un mot présent)")
        @RequestParam(required = false) search: String?,
        @Parameter(description = "Filtrer par langue (ex. \"fr_FR\")")
        @RequestParam(required = false) locale: String?,
        @Parameter(description = "Âge minimum demandé ; un pack est retenu si sa plage d'âge chevauche")
        @RequestParam(required = false) ageMin: Int?,
        @Parameter(description = "Âge maximum demandé ; un pack est retenu si sa plage d'âge chevauche")
        @RequestParam(required = false) ageMax: Int?,
    ): PagedPacksResponse {
        val filter = PackFilter(
            search = search,
            locale = locale,
            ageMin = ageMin,
            ageMax = ageMax,
        )
        return listOfficialPacks.invoke(page ?: 0, size ?: 50, filter)
    }
}
