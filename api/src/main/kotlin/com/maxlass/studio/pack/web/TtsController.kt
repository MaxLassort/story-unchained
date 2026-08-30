package com.maxlass.studio.pack.web

import com.maxlass.studio.pack.domain.dto.TtsVoicesResponse
import com.maxlass.studio.pack.port.external.TtsProvider
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
            "Le provider est choisi selon les settings (OPENAI / ELEVENLABS avec clé BYOK). " +
            "Pas de fallback silencieux : si le provider échoue ou si la clé est absente, une " +
            "erreur 409/502 est retournée avec un message invitant à basculer sur le provider " +
            "gratuit Google Translate. Utilisé par le bouton ▶ du formulaire de création d'histoire.",
    )
    @ApiResponse(responseCode = "200", description = "Audio MP3", content = [Content(mediaType = "audio/mpeg")])
    @ApiResponse(responseCode = "400", description = "Texte vide")
    @ApiResponse(responseCode = "409", description = "Clé API TTS manquante pour le provider demandé")
    @ApiResponse(responseCode = "502", description = "Le provider TTS a échoué — basculer sur FREE (Google Translate)")
    @GetMapping("/preview")
    suspend fun preview(
        @Parameter(description = "Texte à synthétiser (non vide)")
        @RequestParam @NotBlank text: String,
        @Parameter(description = "ID de la voix (ex. \"alloy\" pour OpenAI, voice ID ElevenLabs ex. \"21m00Tcm4TlvDq8ikWAM\"). Vide = voix par défaut")
        @RequestParam(required = false) voice: String?,
        @Parameter(description = "Code langue ISO 639-1 (ex. \"fr\"), utilisé par ElevenLabs et le provider gratuit Google Translate. Défaut : langue configurée ou \"fr\"")
        @RequestParam(required = false) lang: String?,
        @Parameter(description = "Provider à utiliser pour cette demande (défaut : paramètres utilisateur)")
        @RequestParam(required = false) provider: TtsProvider?,
    ): ResponseEntity<ByteArray> {
        val audio = ttsEngine.synthesize(text.trim(), voice, lang, provider)
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
        @Parameter(description = "Provider (défaut : provider configuré)")
        @RequestParam(required = false) provider: TtsProvider?,
    ): ResponseEntity<TtsVoicesResponse> = ResponseEntity.ok(ttsVoiceCatalogService.getVoices(provider?.name))
}