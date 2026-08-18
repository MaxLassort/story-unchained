package com.maxlass.studio.pack.web

import com.maxlass.studio.pack.domain.dto.TtsVoicesResponse
import com.maxlass.studio.pack.service.TtsEngine
import com.maxlass.studio.pack.service.TtsVoiceCatalogService
import jakarta.validation.constraints.NotBlank
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/tts")
class TtsController(
    private val ttsEngine: TtsEngine,
    private val ttsVoiceCatalogService: TtsVoiceCatalogService,
) {

    @GetMapping("/preview")
    suspend fun preview(
        @RequestParam @NotBlank text: String,
        @RequestParam(required = false) voice: String?,
        @RequestParam(required = false) lang: String?,
    ): ResponseEntity<ByteArray> {
        val audio = ttsEngine.synthesize(text.trim(), voice, lang)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("audio/mpeg"))
            .body(audio)
    }

    @GetMapping("/voices")
    suspend fun voices(
        @RequestParam(required = false) provider: String?,
    ): ResponseEntity<TtsVoicesResponse> = ResponseEntity.ok(ttsVoiceCatalogService.getVoices(provider))
}