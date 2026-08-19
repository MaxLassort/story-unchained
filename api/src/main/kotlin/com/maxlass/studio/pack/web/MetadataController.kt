package com.maxlass.studio.pack.web

import com.maxlass.studio.core.api.ApiStatusResponse
import com.maxlass.studio.pack.service.RefreshOfficialMetadataUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/metadata")
@Tag(name = "Métadonnées officielles", description = "Base officielle Lunii (catalogue officiel).")
class MetadataController(
    private val refreshMetadata: RefreshOfficialMetadataUseCase,
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
}
