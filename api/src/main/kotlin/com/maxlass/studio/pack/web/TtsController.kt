package com.maxlass.studio.pack.web

import com.maxlass.studio.pack.domain.dto.TtsVoicesResponse
import com.maxlass.studio.pack.service.TtsEngine
import com.maxlass.studio.pack.service.TtsVoiceCatalogService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.NotBlank
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/tts")
@Tag(name = "TTS", description = "Synthèse vocale (text-to-speech) pour la création d'histoires.")
class TtsController(
    private val ttsEngine: TtsEngine,
    private val ttsVoiceCatalogService: TtsVoiceCatalogService,
) {

    @Operation(
        summary = "Pré-écouter un texte en audio",
        description = "Synthétise le texte en MP3 avec la voix configurée dans les settings. " +
            "Le provider est choisi selon les settings (OPENAI / ELEVENLABS avec clé BYOK) ; " +
            "en cas d'échec du provider payant, bascule automatique sur le fallback gratuit " +
            "Google Translate. Utilisé par le bouton ▶ du formulaire de création d'histoire.",
    )
    @ApiResponse(responseCode = "200", description = "Audio MP3", content = [Content(mediaType = "audio/mpeg")])
    @ApiResponse(responseCode = "400", description = "Texte vide")
@GetMapping("/preview")
    suspend fun preview(
        @Parameter(description = "Texte à synthétiser (non vide)")
        @RequestParam @NotBlank text: String,
        @Parameter(description = "Voix : nom OpenAI (ex. \"alloy\") ou voice id ElevenLabs. Vide = voix par défaut")
        @RequestParam(required = false) voice: String?,
        @Parameter(description = "Langue ISO 639‑1 (ex. \"fr\"), utilisée par le fallback gratuit. Défaut : fr")
        @RequestParam(required = false) lang: String?,
        @Parameter(description = "Provider à utiliser pour cette demande : OPENAI, ELEVENLABS ou FREE (défaut : paramètres utilisateur)")
        @RequestParam(required = false) provider: String?,
    ): ResponseEntity<ByteArray> {
        val effectiveProvider = provider?.let { TtsProvider.fromValue(it) } ?: null
        val audio = ttsEngine.synthesize(text.trim(), voice, lang, effectiveProvider)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("audio/mpeg"))
            .body(audio)
    }

    @Operation(
        summary = "Voix disponibles d'un provider TTS",
        description = "Retourne les voix du provider demandé : OPENAI = liste statique de 11 voix ; " +
            "ELEVENLABS = liste live avec la clé utilisateur (sinon liste statique avec " +
            "\"fallback\": true) ; FREE = liste vide.",
    )
    @ApiResponse(responseCode = "200", description = "Voix du provider")
    @GetMapping("/voices")
    suspend fun voices(
        @Parameter(description = "Provider : OPENAI, ELEVENLABS ou FREE (défaut : provider configuré)")
        @RequestParam(required = false) provider: String?,
    ): ResponseEntity<TtsVoicesResponse> = ResponseEntity.ok(ttsVoiceCatalogService.getVoices(provider))
}