package com.maxlass.studio.pack.web

import com.maxlass.studio.core.api.ApiStatusResponse
import com.maxlass.studio.pack.service.RefreshOfficialMetadataUseCase
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/metadata")
class MetadataController(
    private val refreshMetadata: RefreshOfficialMetadataUseCase,
) {

    @PostMapping("/refresh")
    suspend fun refreshOfficial(): ResponseEntity<ApiStatusResponse> {
        refreshMetadata.invoke()
        return ResponseEntity.ok(ApiStatusResponse(ok = true, message = "Official metadata refreshed"))
    }
}
